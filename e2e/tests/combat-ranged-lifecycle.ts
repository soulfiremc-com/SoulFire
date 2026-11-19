import { goals } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
  RangedAttackCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
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

const TAG = "sf_e2e_ranged_lifecycle";

export default {
  name: "combat-ranged-lifecycle",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3664 0 3711 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3664 63 0 3711 63 15 air", { check: false });
            yield* rcon("forceload remove 3664 0 3711 15", { check: false });
            yield* rcon("forceload remove 3900 8", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("forceload add 3900 8");
        yield* rcon("fill 3664 63 0 3711 63 15 stone");
        const health = rcon(`data get entity @e[tag=${TAG},limit=1] Health`).pipe(
          Effect.map((text) => Number(text.match(/: ([\d.]+)f$/u)?.[1])),
        );
        const arrows = rcon(
          `data get entity ${BOT_NAME} Inventory[{id:"minecraft:arrow"}].count`,
        ).pipe(Effect.map((text) => Number(text.match(/: (\d+)$/u)?.[1])));
        for (const mode of ["cancel", "suspend", "unavailable"] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("600 millis");
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: 3674.5, y: 64, z: 8.5 }, -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with stick`);
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with bow`);
          yield* rcon(`give ${BOT_NAME} arrow 8`);
          yield* bot.selectHotbar(0);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(p.mainHand?.itemId === "minecraft:stick", "Waiting for the starting slot"),
                ),
              ),
            "5 seconds",
          );
          yield* rcon(`summon villager 3684.5 64 8.5 {Tags:["${TAG}"],NoAI:1b,NoGravity:1b}`);
          const target = yield* eventually(
            bot.world
              .queryEntities({
                radius: 24,
                selector: { entityTypes: ["minecraft:villager"], alive: true },
              })
              .pipe(
                Effect.map((result) => result.entities[0]?.reference),
                Effect.filterOrFail(
                  (reference) => reference !== undefined,
                  () => new CheckFailed({ message: "The ranged target never became observable" }),
                ),
              ),
            "5 seconds",
          );
          const before = yield* health;
          yield* check((yield* arrows) === 8, "The arrow fixture was not ready");
          const task = yield* bot.tasks.rangedAttack(target, { maximumShots: 1, strafe: false });
          yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
          yield* eventually(
            bot.world
              .player()
              .pipe(Effect.flatMap((p) => check(p.usingItem, "The bow never began drawing"))),
            "3 seconds",
            "20 millis",
          );
          // A partial draw must be strong enough to fire if cleanup releases it.
          yield* Effect.sleep("450 millis");
          let diversion: Effect.Success<ReturnType<typeof bot.tasks.goTo>> | undefined;
          if (mode === "cancel") {
            yield* task.cancel("Cancel a partial draw");
            yield* task.wait({ call: { timeoutMs: 5000 } });
          } else if (mode === "suspend") {
            diversion = yield* bot.tasks.goTo(goals.block({ x: 3666, y: 64, z: 8 }), {
              priority: BotTaskPriority.HIGH,
              conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
              path: { timeoutSeconds: 10 },
            });
            const activeDiversion = diversion;
            yield* Effect.addFinalizer(() =>
              activeDiversion.cancel("test cleanup").pipe(Effect.ignore),
            );
            yield* eventually(
              task
                .refresh()
                .pipe(
                  Effect.flatMap((t) =>
                    check(
                      t.status === BotTaskStatus.SUSPENDED,
                      "The ranged task was not suspended",
                    ),
                  ),
                ),
              "3 seconds",
              "20 millis",
            );
          } else {
            yield* rcon(`tp @e[tag=${TAG}] 3900 64 8.5`);
            yield* eventually(
              bot.world
                .queryEntities({
                  radius: 24,
                  selector: { entityTypes: ["minecraft:villager"], alive: true },
                })
                .pipe(
                  Effect.flatMap((r) =>
                    check(r.entities.length === 0, "The target remained observable"),
                  ),
                ),
              "3 seconds",
              "20 millis",
            );
          }
          yield* Effect.sleep("600 millis");
          yield* check(!(yield* bot.world.player()).usingItem, `${mode} left the bow drawn`);
          yield* check((yield* arrows) === 8, `${mode} fired an uncounted arrow`);
          yield* check((yield* health) === before, `${mode} damaged the target while stopping`);
          if (mode === "cancel") {
            yield* check(
              (yield* bot.world.player()).mainHand?.itemId === "minecraft:stick",
              "Cancellation did not restore the original slot",
            );
            continue;
          }
          if (diversion) {
            yield* diversion.cancel("Resume the ranged task");
            yield* diversion.wait({ call: { timeoutMs: 5000 } });
          } else {
            yield* rcon(`tp @e[tag=${TAG}] 3684.5 64 8.5`);
          }
          const result = yield* task.result({ call: { timeoutMs: 10_000 } });
          yield* check(
            result.reason === RangedAttackCompletionReason.SHOT_LIMIT_REACHED &&
              result.shotsReleased === 1,
            `${mode} did not resume its shot`,
          );
          yield* eventually(
            health.pipe(
              Effect.flatMap((value) =>
                check(value < before, "The resumed shot did not damage the target"),
              ),
            ),
            "3 seconds",
          );
          yield* check((yield* arrows) === 7, "Resuming consumed more than one arrow");
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(
                    !p.usingItem && p.mainHand?.itemId === "minecraft:stick",
                    "Completion did not release use and restore the original slot",
                  ),
                ),
              ),
            "3 seconds",
          );
        }
      }),
    ),
} satisfies E2ETest;
