import { Code, ConnectError } from "@connectrpc/connect";
import { InventoryService } from "@soulfiremc/sdk/generated/soulfire/inventory_pb";
import { Effect } from "effect";
import { CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";
import { nativeInput } from "../native-input.ts";

const NAME = "FleetE2EA";

export default {
  name: "respawn-inventory-revision",
  run: ({ client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* check(
          !(yield* rcon("list")).includes(NAME),
          "The respawn fixture bot is already online",
        );
        const instance = yield* Effect.acquireRelease(
          client.getOrCreateInstance("e2e-respawn-revision", {
            server: `127.0.0.1:${process.env.SOULFIRE_E2E_MINECRAFT_PORT ?? 25566}`,
          }),
          (owned) => owned.delete().pipe(Effect.ignore),
        );
        for (const namespace of [
          "auto-eat",
          "auto-armor",
          "auto-totem",
          "auto-jump",
          "anti-afk",
          "auto-respawn",
          "auto-reconnect",
        ]) {
          yield* instance.setConfigEntry({
            namespace,
            key: "enabled",
            value: { kind: { case: "boolValue", value: false } },
          });
        }
        const bot = yield* instance.getOrCreateBot(NAME, { readyTimeoutMs: 60000 });
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* eventually(
              Effect.gen(function* () {
                if ((yield* bot.world.player()).dead) yield* bot.respawn().pipe(Effect.ignore);
                yield* check(!(yield* bot.world.player()).dead, "Cleanup respawn did not complete");
              }),
              "15 seconds",
              "100 millis",
            );
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${NAME}`);
            yield* rcon(`clear ${NAME}`, { check: false });
            yield* rcon(`effect clear ${NAME}`, { check: false });
            yield* bot.selectHotbar(0);
            yield* rcon(`tp ${NAME} 0.5 64 0.5`);
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.tap((player) =>
                    check(
                      player.position?.x === 0.5 &&
                        player.position.y === 64 &&
                        player.position.z === 0.5,
                      "Cleanup position did not arrive",
                    ),
                  ),
                ),
              "10 seconds",
              "100 millis",
            );
            yield* bot.stop();
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        const initial = yield* bot.world.player();
        yield* check(
          initial.experience?.level === 0 &&
            initial.experience.progress === 0 &&
            initial.experience.total === 0,
          "The death fixture must have zero XP",
        );
        yield* check(
          (yield* bot.inventory.snapshot()).slots.every((slot) => !slot.item),
          "The death fixture must have an empty inventory",
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        const service = client.service(InventoryService);
        const run = Effect.runPromiseWith(yield* Effect.context());
        for (const native of [false, true, false, true]) {
          yield* rcon(`gamemode survival ${NAME}`);
          yield* rcon(`tp ${NAME} 4264.5 64 8.5`);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((player) =>
                  check(
                    player.position?.x === 4264.5 && player.health === 20,
                    "The death fixture is not ready",
                  ),
                ),
              ),
            "10 seconds",
            "100 millis",
          );
          yield* bot.waitForChunks({ radiusChunks: 2, timeoutMs: 10000 });
          const before = yield* bot.inventory.snapshot();
          yield* rcon(`kill ${NAME}`);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((player) =>
                  check(player.dead && player.health === 0, "The bot did not die"),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          yield* Effect.sleep("1500 millis");
          yield* check((yield* bot.world.player()).dead, "Auto-respawn hid the death screen");
          if (native) {
            yield* nativeInput(client, bot, async (input) => {
              await new Promise((resolve) => setTimeout(resolve, 1200));
              await input.click(213, 142);
              await run(
                eventually(
                  bot.world
                    .player()
                    .pipe(
                      Effect.tap((player) =>
                        check(
                          !player.dead && player.health === 20,
                          "The native Respawn button did not respawn",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                ),
              );
            });
            yield* Effect.sleep("2 seconds");
          } else {
            yield* bot.respawn();
          }
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((player) =>
                  check(
                    !player.dead &&
                      player.health === 20 &&
                      player.position?.x === 0.5 &&
                      player.position.z === 0.5,
                    "Respawning did not restore health and spawn position",
                  ),
                ),
              ),
            "10 seconds",
            "50 millis",
          );
          const after = yield* bot.inventory.snapshot();
          yield* check(
            before.menuId !== after.menuId,
            "Respawn reused the old inventory menu identity",
          );
          const rejected = yield* Effect.tryPromise({
            try: async () => {
              try {
                await service.selectHotbarItem({
                  scope: { instanceId: bot.instanceId, botId: bot.id },
                  selection: { case: "hotbarSlot", value: 1 },
                  expectedRevision: before.revision,
                });
                return false;
              } catch (cause) {
                if (cause instanceof ConnectError && cause.code === Code.Aborted) return true;
                throw cause;
              }
            },
            catch: (cause) => new CheckFailed({ message: String(cause) }),
          });
          yield* check(rejected, "A pre-death inventory revision changed the respawned player");
          yield* check(
            (yield* rcon(`data get entity ${NAME} SelectedItemSlot`)).endsWith(" 0"),
            "A rejected stale action reached vanilla",
          );
          yield* bot.inventory.selectHotbar({
            selection: { case: "hotbarSlot", value: 1 },
            expectedRevision: after.revision,
          });
          yield* eventually(
            rcon(`data get entity ${NAME} SelectedItemSlot`).pipe(
              Effect.tap((data) =>
                check(data.endsWith(" 1"), "A fresh revision could not select the hotbar"),
              ),
            ),
            "5 seconds",
          );
          yield* bot.selectHotbar(0);
          yield* eventually(
            rcon(`data get entity ${NAME} SelectedItemSlot`).pipe(
              Effect.tap((data) =>
                check(data.endsWith(" 0"), "The hotbar reset did not reach vanilla"),
              ),
            ),
            "5 seconds",
          );
          yield* check(
            !(yield* bot.inventory.snapshot()).carried,
            "Respawn left an inventory cursor stack",
          );
        }
      }),
    ),
} satisfies E2ETest;
