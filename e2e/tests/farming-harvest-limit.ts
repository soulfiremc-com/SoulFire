import { FarmCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "farming-harvest-limit",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon("fill 4260 63 4 4278 64 12 air");
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        for (const name of ["one-harvest", "two-harvests", "missing-seeds", "no-replant"]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon("fill 4260 64 4 4278 64 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              yield* rcon("fill 4265 63 8 4266 63 8 farmland[moisture=7]");
              yield* rcon("fill 4265 64 8 4266 64 8 wheat[age=7]");
              if (name !== "missing-seeds") {
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with wheat_seeds 16`);
                yield* eventually(
                  bot.inventory
                    .count({ selector: { itemIds: ["minecraft:wheat_seeds"] } })
                    .pipe(Effect.tap((count) => check(count === 16n, "Seeds have not arrived"))),
                  "5 seconds",
                  "50 millis",
                );
              }
              for (const x of [4265, 4266]) {
                yield* eventually(
                  bot.world
                    .block({ position: { x, y: 64, z: 8 } })
                    .pipe(
                      Effect.tap(({ block }) =>
                        check(
                          block?.blockId === "minecraft:wheat" && block.properties.age === "7",
                          "The bot has not observed both mature crops",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const maximumHarvests = name === "two-harvests" ? 2 : 1;
              const replants = name !== "missing-seeds" && name !== "no-replant";
              const task = yield* bot.tasks.farm({
                center: { x: 4265, y: 64, z: 8 },
                radius: 2,
                maximumHarvests,
                replant: name !== "no-replant",
              });
              yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
              const result = yield* task.result({ call: { timeoutMs: 15000 } });
              yield* check(
                result.reason ===
                  (name === "missing-seeds"
                    ? FarmCompletionReason.NO_REPLANT_ITEM
                    : FarmCompletionReason.HARVEST_LIMIT_REACHED) &&
                  result.cropsHarvested === maximumHarvests &&
                  result.cropsReplanted === (replants ? maximumHarvests : 0),
                `${name} ended before its full crop cycle: ${JSON.stringify(result)}`,
              );
              for (const x of [4265, 4266]) {
                const expected =
                  x === 4266 && maximumHarvests === 1
                    ? "wheat[age=7]"
                    : replants
                      ? "wheat[age=0]"
                      : "air";
                yield* check(
                  (yield* rcon(`execute if block ${x} 64 8 ${expected}`, { check: false })) ===
                    "Test passed",
                  `${name} left the wrong crop state on the vanilla server`,
                );
              }
              yield* check(
                (yield* bot.world.player()).selectedHotbarSlot === 0,
                `${name} did not restore the original selection`,
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
