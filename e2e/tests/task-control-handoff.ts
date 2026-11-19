import { BotTaskConflictPolicy, BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
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

const TAG = "sf_e2e_control_handoff";

export default {
  name: "task-control-handoff",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const difficulty = (yield* rcon("difficulty"))
          .match(/difficulty is (Peaceful|Easy|Normal|Hard)/u)?.[1]
          ?.toLowerCase();
        yield* check(!!difficulty, "Cannot determine the difficulty to restore");
        yield* Effect.addFinalizer(() => rcon(`difficulty ${difficulty}`).pipe(Effect.ignore));
        yield* rcon("difficulty hard");
        yield* rcon("forceload add 3728 0 3775 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`effect clear ${BOT_NAME}`, { check: false });
            yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`, { check: false });
            yield* rcon(`effect give ${BOT_NAME} saturation 1 255 true`, { check: false });
            yield* Effect.sleep("1100 millis");
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3728 63 0 3775 63 15 air", { check: false });
            yield* rcon("forceload remove 3728 0 3775 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3728 63 0 3775 63 15 stone");
        for (const mode of [
          "ranged-suspended",
          "melee-suspended",
          "ranged-queued",
          "melee-queued",
        ] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("600 millis");
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: 3738.5, y: 64, z: 8.5 }, -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`);
          yield* rcon(`effect give ${BOT_NAME} saturation 1 255 true`);
          yield* Effect.sleep("1100 millis");
          yield* rcon(`effect give ${BOT_NAME} hunger 10 255 true`);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) => check(p.food <= 10, "The hunger fixture never took effect")),
              ),
            "10 seconds",
            "100 millis",
          );
          yield* rcon(`effect clear ${BOT_NAME} hunger`);
          const ranged = mode.startsWith("ranged");
          const queued = mode.endsWith("queued");
          const weapon = ranged ? "bow" : "wooden_sword";
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with stick`);
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with ${weapon}`);
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with bread 3`);
          yield* rcon(`give ${BOT_NAME} arrow 8`);
          yield* bot.selectHotbar(0);
          yield* rcon(`summon villager 3748.5 64 8.5 {Tags:["${TAG}"],NoAI:1b}`);
          const target = yield* eventually(
            bot.world
              .queryEntities({
                radius: 24,
                selector: { entityTypes: ["minecraft:villager"], alive: true },
              })
              .pipe(
                Effect.map((r) => r.entities[0]?.reference),
                Effect.filterOrFail(
                  (r) => r !== undefined,
                  () => new CheckFailed({ message: "The handoff target never became observable" }),
                ),
              ),
            "5 seconds",
          );
          const startCombat = () =>
            ranged
              ? bot.tasks.rangedAttack(target, {
                  maximumShots: 1,
                  strafe: true,
                  conflictPolicy: BotTaskConflictPolicy.QUEUE,
                })
              : bot.tasks.attackEntity(target, {
                  selectBestWeapon: true,
                  useOffhandShield: true,
                  conflictPolicy: BotTaskConflictPolicy.QUEUE,
                });
          const suspended = queued ? undefined : yield* startCombat();
          if (suspended) {
            yield* Effect.addFinalizer(() => suspended.cancel("test cleanup").pipe(Effect.ignore));
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) =>
                    check(
                      p.mainHand?.itemId === `minecraft:${weapon}`,
                      "The combat task never selected its weapon",
                    ),
                  ),
                ),
              "3 seconds",
              "20 millis",
            );
          }
          const meal = yield* bot.tasks.autoEat(["minecraft:bread"], {
            checkIntervalTicks: 1,
            maximumMeals: 1,
          });
          yield* Effect.addFinalizer(() => meal.cancel("test cleanup").pipe(Effect.ignore));
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(
                    p.usingItem && p.mainHand?.itemId === "minecraft:bread",
                    "The replacement meal never started",
                  ),
                ),
              ),
            "3 seconds",
            "20 millis",
          );
          const combat = suspended ?? (yield* startCombat());
          if (queued)
            yield* Effect.addFinalizer(() => combat.cancel("test cleanup").pipe(Effect.ignore));
          yield* eventually(
            combat
              .refresh()
              .pipe(
                Effect.flatMap((t) =>
                  check(
                    t.status ===
                      (queued ? BotTaskStatus.WAITING_FOR_RESOURCES : BotTaskStatus.SUSPENDED),
                    "The combat task did not yield its resources",
                  ),
                ),
              ),
            "3 seconds",
            "20 millis",
          );
          const foodBefore = (yield* bot.world.player()).food;
          yield* Effect.sleep("400 millis");
          const positionBefore = yield* serverPosition;
          yield* combat.cancel("Cancel the combat task after it yielded control");
          yield* combat.wait({ call: { timeoutMs: 5000 } });
          yield* Effect.sleep("100 millis");
          const during = yield* bot.world.player();
          const positionAfter = yield* serverPosition;
          yield* check(
            Math.hypot(positionAfter.x - positionBefore.x, positionAfter.z - positionBefore.z) <
              0.05,
            `${mode} left movement held after yielding control`,
          );
          yield* check(
            during.usingItem && during.mainHand?.itemId === "minecraft:bread",
            `${mode} cancellation interrupted the replacement meal`,
          );
          const result = yield* meal.result({ call: { timeoutMs: 6000 } });
          yield* check(
            result.mealsEaten === 1 && result.finalFoodLevel > foodBefore,
            `${mode} reported a meal without restoring food`,
          );
          const bread = yield* rcon(
            `data get entity ${BOT_NAME} Inventory[{id:"minecraft:bread"}].count`,
          );
          yield* check(
            Number(bread.match(/: (\d+)$/u)?.[1]) === 2,
            `${mode} did not consume exactly one bread on vanilla`,
          );
          const food = yield* rcon(`data get entity ${BOT_NAME} foodLevel`);
          yield* check(
            Number(food.match(/: (\d+)$/u)?.[1]) > foodBefore,
            `${mode} did not restore food on vanilla`,
          );
        }
      }),
    ),
} satisfies E2ETest;
