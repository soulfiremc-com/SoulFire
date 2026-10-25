import { Code, ConnectError } from "@connectrpc/connect";
import { GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { InventoryService } from "@soulfiremc/sdk/generated/soulfire/inventory_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const boundHelmet = 'leather_helmet[enchantments={"minecraft:binding_curse":1}]';
const cases = [
  { mode: "survival", head: "air", count: 64 },
  { mode: "survival", head: "diamond_helmet", count: 64 },
  { mode: "creative", head: "diamond_helmet", count: 64 },
  { mode: "survival", head: "diamond_helmet", count: 1, full: true },
  {
    mode: "survival",
    head: "diamond_helmet",
    count: 64,
    full: true,
    error: Code.ResourceExhausted,
  },
  {
    mode: "creative",
    head: "diamond_helmet",
    count: 64,
    full: true,
    error: Code.ResourceExhausted,
  },
  { mode: "survival", head: "carved_pumpkin", count: 64, full: true, merge: true },
  { mode: "survival", head: boundHelmet, count: 64, error: Code.FailedPrecondition },
  { mode: "creative", head: boundHelmet, count: 64 },
] as const;

export default {
  name: "stacked-headgear",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.closeContainer();
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        yield* placeBot(bot, { x: 4264.5, y: 64, z: 8.5 });
        const service = client.service(InventoryService);
        const scope = { instanceId: bot.instanceId, botId: bot.id };
        for (const spec of cases) {
          const full = "full" in spec && spec.full;
          const merge = "merge" in spec && spec.merge;
          const error = "error" in spec ? spec.error : undefined;
          const head = spec.head.split("[")[0]!;
          yield* bot.closeContainer();
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(`gamemode ${spec.mode} ${BOT_NAME}`);
          yield* rcon(`item replace entity ${BOT_NAME} armor.head with minecraft:${spec.head}`);
          if (full) {
            for (let slot = 0; slot < 27; slot++) {
              yield* rcon(
                `item replace entity ${BOT_NAME} inventory.${slot} with minecraft:stone 64`,
              );
            }
            for (let slot = 0; slot < 9; slot++) {
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.${slot} with minecraft:stone 64`);
            }
          }
          yield* rcon(
            `item replace entity ${BOT_NAME} inventory.0 with minecraft:carved_pumpkin ${spec.count}`,
          );
          if (merge) {
            yield* rcon(
              `item replace entity ${BOT_NAME} inventory.1 with minecraft:carved_pumpkin 63`,
            );
          }
          const ready = yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    state.slots.some(
                      (slot) =>
                        slot.slot === 9 &&
                        slot.itemId === "minecraft:carved_pumpkin" &&
                        slot.count === spec.count,
                    ) &&
                      (head === "air"
                        ? !state.slots.some((slot) => slot.slot === 5)
                        : state.slots.some(
                            (slot) => slot.slot === 5 && slot.itemId === `minecraft:${head}`,
                          )) &&
                      state.slots.filter((slot) => slot.slot >= 9 && slot.slot <= 44).length ===
                        (full ? 36 : 1),
                    "The equipment fixture did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          yield* eventually(
            bot
              .info()
              .pipe(
                Effect.tap((info) =>
                  check(
                    info.liveState?.gameMode ===
                      (spec.mode === "creative" ? GameMode.CREATIVE : GameMode.SURVIVAL),
                    "The equipment game mode did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          const before = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
          const beforeHead =
            error === undefined
              ? undefined
              : yield* rcon(`data get entity ${BOT_NAME} equipment.head`);
          const response = yield* Effect.tryPromise({
            try: async () => {
              try {
                await service.equipItem({
                  scope,
                  selector: { itemIds: ["minecraft:carved_pumpkin"] },
                  equipmentSlot: "head",
                });
                return undefined;
              } catch (cause) {
                if (cause instanceof ConnectError) return cause.code;
                throw cause;
              }
            },
            catch: (cause) => new CheckFailed({ message: String(cause) }),
          });
          yield* check(
            response === error,
            "Equip accepted a blocked request or rejected a usable destination",
          );
          const equipped = error === undefined ? "carved_pumpkin" : head;
          yield* eventually(
            rcon(`execute if items entity ${BOT_NAME} armor.head minecraft:${equipped}`, {
              check: false,
            }).pipe(
              Effect.tap((data) =>
                check(
                  data.startsWith("Test passed"),
                  "Vanilla retained the wrong headgear after equip",
                ),
              ),
            ),
            "5 seconds",
          );
          if (error !== undefined) {
            yield* check(
              (yield* rcon(`data get entity ${BOT_NAME} Inventory`)) === before,
              "Rejected equipment changed the player inventory",
            );
            yield* check(
              (yield* rcon(`data get entity ${BOT_NAME} equipment.head`)) === beforeHead,
              "Rejected equipment changed the original headgear",
            );
            const after = yield* bot.inventoryState();
            yield* check(
              !after.carriedItem &&
                after.slots.length === ready.slots.length &&
                after.slots.every((slot) =>
                  ready.slots.some(
                    (old) =>
                      old.slot === slot.slot &&
                      old.itemId === slot.itemId &&
                      old.count === slot.count,
                  ),
                ),
              "Rejected equipment changed the predicted inventory or cursor",
            );
          } else {
            const pumpkinCount = spec.count - 1 + (merge ? 64 : 0);
            yield* eventually(
              rcon(`clear ${BOT_NAME} minecraft:carved_pumpkin 0`).pipe(
                Effect.tap((data) =>
                  check(
                    data.includes(`Found ${pumpkinCount + 1} `),
                    "Equip lost or duplicated pumpkins",
                  ),
                ),
              ),
              "5 seconds",
            );
            if (head !== "air" && !merge) {
              yield* check(
                (yield* rcon(`clear ${BOT_NAME} minecraft:${head} 0`)).includes("Found 1 "),
                "Equip lost the displaced helmet",
              );
            }
            yield* check(
              !(yield* bot.inventoryState()).carriedItem,
              "Equip left an item on the cursor",
            );
            if (full) {
              yield* check(
                (yield* rcon(`clear ${BOT_NAME} minecraft:stone 0`)).includes(
                  `Found ${(merge ? 34 : 35) * 64} `,
                ),
                "Equip changed unrelated inventory stacks",
              );
            }
          }
          yield* check(
            (yield* rcon("execute if entity @e[type=item,x=4264,y=64,z=8,distance=..8]", {
              check: false,
            })) === "Test failed",
            "Equip dropped an item into the world",
          );
        }
      }),
    ),
} satisfies E2ETest;
