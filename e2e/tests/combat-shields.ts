import { goals } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
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
  serverPosition,
} from "../harness.ts";

const TAG = "sf_e2e_combat_shields";
const REGEN = "natural_health_regeneration";
const shoot = rcon(
  `execute at ${BOT_NAME} run summon arrow ~8 ~2.2 ~ {Tags:["${TAG}"],Motion:[-1.0d,0.1d,0.0d],damage:4.0d}`,
);

export default {
  name: "combat-shields",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const original = (yield* rcon(`gamerule ${REGEN}`)).match(/\b(true|false)\s*$/)?.[1];
        yield* check(original !== undefined, "The regeneration rule could not be read");
        yield* Effect.addFinalizer(() => rcon(`gamerule ${REGEN} ${original}`).pipe(Effect.ignore));
        yield* rcon(`gamerule ${REGEN} false`);
        yield* rcon("forceload add 3520 0 3583 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`, { check: false });
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) =>
                    check(p.health === 20, "Waiting for fixture health restoration"),
                  ),
                ),
              "3 seconds",
            ).pipe(Effect.ignore);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3520 63 0 3583 66 31 air", { check: false });
            yield* rcon("forceload remove 3520 0 3583 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3520 63 0 3583 63 31 stone");
        // Keep the arrow and the escaping bot in the same lane.
        yield* rcon("fill 3520 64 15 3583 66 15 stone");
        yield* rcon("fill 3520 64 17 3583 66 17 stone");
        for (const mode of ["unshielded", "flee", "attack"] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("600 millis");
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: 3552.5, y: 64, z: 16.5 }, -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`);
          if (mode !== "unshielded")
            yield* rcon(`item replace entity ${BOT_NAME} weapon.offhand with shield`);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(
                    p.health === 20 &&
                      (mode === "unshielded" || p.offHand?.itemId === "minecraft:shield"),
                    "The health and shield fixture did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          yield* rcon(`effect clear ${BOT_NAME} instant_health`, { check: false });
          if (mode === "unshielded") {
            yield* Effect.sleep("4 seconds");
            yield* shoot;
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) =>
                    check(p.health < 20, "The baseline arrow never damaged the unshielded bot"),
                  ),
                ),
              "3 seconds",
            );
            continue;
          }
          yield* rcon(
            `summon cow ${mode === "flee" ? 3555.5 : 3562.5} 64 16.5 {Tags:["${TAG}"],NoAI:1b}`,
          );
          const target = yield* eventually(
            bot.world
              .queryEntities({
                radius: 24,
                selector: { entityTypes: ["minecraft:cow"], alive: true },
              })
              .pipe(
                Effect.map((result) => result.entities[0]?.reference),
                Effect.filterOrFail(
                  (reference) => reference !== undefined,
                  () => new CheckFailed({ message: "The task target never became observable" }),
                ),
              ),
            "5 seconds",
          );
          const task =
            mode === "flee"
              ? yield* bot.tasks.flee(
                  { entityTypes: ["minecraft:cow"] },
                  {
                    triggerRadius: 4,
                    safeDistance: 20,
                    completeWhenSafe: false,
                    path: { timeoutSeconds: 15 },
                  },
                )
              : yield* bot.tasks.attackEntity(target, {
                  maximumAttacks: 1,
                  selectBestWeapon: false,
                  restoreSelectedSlot: false,
                  useOffhandShield: true,
                });
          yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
          if (mode === "flee")
            yield* eventually(
              serverPosition.pipe(
                Effect.flatMap((p) => check(p.x < 3551.5, "The bot never began fleeing")),
              ),
              "5 seconds",
            );
          else yield* Effect.sleep("300 millis");
          yield* shoot;
          yield* Effect.sleep("900 millis");
          const blocked = yield* bot.world.player();
          const serverHealth = yield* rcon(`data get entity ${BOT_NAME} Health`);
          yield* check(
            blocked.health === 20 && serverHealth.endsWith(": 20.0f"),
            `${mode} failed to block the incoming arrow: ${serverHealth}`,
          );
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(
                    (p.offHand?.damage ?? 0) > 0,
                    `${mode} did not block damage with its shield`,
                  ),
                ),
              ),
            "3 seconds",
          );
          if (mode === "flee") {
            const afterBlock = yield* serverPosition;
            yield* eventually(
              serverPosition.pipe(
                Effect.flatMap((p) =>
                  check(p.x < afterBlock.x - 0.6, "Flee did not resume escaping after blocking"),
                ),
              ),
              "3 seconds",
            );
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) =>
                    check(!p.usingItem, "Flee kept holding its shield after the arrow passed"),
                  ),
                ),
              "3 seconds",
            );
            yield* shoot;
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) => check(p.usingItem, "Flee did not raise its shield again")),
                ),
              "2 seconds",
              "20 millis",
            );
          } else {
            const diversion = yield* bot.tasks.goTo(goals.block({ x: 3530, y: 64, z: 16 }), {
              priority: BotTaskPriority.HIGH,
              conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
              path: { timeoutSeconds: 10 },
            });
            yield* Effect.addFinalizer(() => diversion.cancel("test cleanup").pipe(Effect.ignore));
            yield* eventually(
              task
                .refresh()
                .pipe(
                  Effect.flatMap((state) =>
                    check(
                      state.status === BotTaskStatus.SUSPENDED,
                      "The attack task was not suspended",
                    ),
                  ),
                ),
              "3 seconds",
            );
            yield* Effect.sleep("200 millis");
            yield* check(
              !(yield* bot.world.player()).usingItem,
              "Suspending the attack task left the shield held",
            );
            yield* diversion.cancel("resume shield task");
            yield* diversion.wait({ call: { timeoutMs: 5000 } });
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) =>
                    check(p.usingItem, "The resumed attack task did not raise its shield"),
                  ),
                ),
              "3 seconds",
            );
          }
          yield* task.cancel("cancel while blocking");
          yield* check(
            (yield* task.wait({ call: { timeoutMs: 5000 } })).status === BotTaskStatus.CANCELLED,
            `${mode} did not cancel`,
          );
          yield* Effect.sleep("500 millis");
          yield* check(
            !(yield* bot.world.player()).usingItem,
            `${mode} left the use key held after cancellation`,
          );
        }
      }),
    ),
} satisfies E2ETest;
