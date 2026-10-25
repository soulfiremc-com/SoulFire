import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
  BreedCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const TAG = "sf_e2e_breed_handoff";

export default {
  name: "breeding-task-handoff",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 4260 63 4 4278 63 12 air");
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon("fill 4260 63 4 4278 63 12 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        const hooks = () =>
          bot.world.queryEntities({
            radius: 24,
            selector: { entityTypes: ["minecraft:fishing_bobber"] },
          });
        const observeHook = eventually(
          hooks().pipe(
            Effect.tap((result) => check(result.entities.length === 1, "No fishing line observed")),
            Effect.map((result) => result.entities[0]!.reference!.uuid),
          ),
          "5 seconds",
          "50 millis",
        );
        for (const mode of ["queued-cancel", "suspended-cancel", "queued-start", "resumed"]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with stick`);
              const completes = mode === "queued-start" || mode === "resumed";
              if (completes) {
                yield* eventually(
                  bot.world
                    .queryEntities({
                      radius: 32,
                      selector: { entityTypes: ["minecraft:cow"], alive: true },
                    })
                    .pipe(
                      Effect.tap((result) =>
                        check(
                          result.entities.length === 0,
                          "The breeding scene has another live cow",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with wheat 8`);
                for (const [tag, x] of [
                  [`${TAG}_a`, 4274.5],
                  [`${TAG}_b`, 4276.5],
                ] as const) {
                  // NoAI keeps the feeding test bounded without producing an untagged calf.
                  yield* rcon(
                    `summon cow ${x} 64 8.5 {Tags:["${TAG}","${tag}"],NoAI:1b,NoGravity:1b,Invulnerable:1b,Silent:1b,DeathLootTable:"minecraft:empty"}`,
                  );
                }
                yield* eventually(
                  bot.world
                    .queryEntities({
                      radius: 32,
                      selector: { entityTypes: ["minecraft:cow"], alive: true },
                    })
                    .pipe(
                      Effect.tap((result) => check(result.entities.length === 2, "Cows unseen")),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const suspended = mode === "resumed" || mode === "suspended-cancel";
              const startBreeding = (conflictPolicy: BotTaskConflictPolicy) =>
                bot.tasks.breed({
                  animals: { entityTypes: ["minecraft:cow"] },
                  center: { x: 4275, y: 64, z: 8 },
                  radius: 3,
                  maximumPairs: completes ? 1 : 0,
                  completeWhenNoPair: false,
                  conflictPolicy,
                });
              const startFishing = () =>
                bot.tasks.fish({
                  maximumCatches: 0,
                  completeWhenNoRod: false,
                  castTimeoutTicks: 20,
                  priority: suspended ? BotTaskPriority.HIGH : BotTaskPriority.NORMAL,
                  conflictPolicy: suspended
                    ? BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY
                    : BotTaskConflictPolicy.REJECT,
                });
              let breeding: Effect.Success<ReturnType<typeof startBreeding>>;
              let fishing: Effect.Success<ReturnType<typeof startFishing>>;
              if (suspended) {
                breeding = yield* startBreeding(BotTaskConflictPolicy.REJECT);
                yield* Effect.addFinalizer(() =>
                  breeding.cancel("test cleanup").pipe(Effect.ignore),
                );
                fishing = yield* startFishing();
                yield* Effect.addFinalizer(() =>
                  fishing.cancel("test cleanup").pipe(Effect.ignore),
                );
              } else {
                fishing = yield* startFishing();
                yield* Effect.addFinalizer(() =>
                  fishing.cancel("test cleanup").pipe(Effect.ignore),
                );
                if (completes) {
                  yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with fishing_rod`);
                  yield* observeHook;
                }
                breeding = yield* startBreeding(BotTaskConflictPolicy.QUEUE);
                yield* Effect.addFinalizer(() =>
                  breeding.cancel("test cleanup").pipe(Effect.ignore),
                );
              }
              yield* check(
                (yield* breeding.refresh()).status ===
                  (suspended ? BotTaskStatus.SUSPENDED : BotTaskStatus.WAITING_FOR_RESOURCES),
                `${mode} did not enter the requested scheduling state`,
              );
              if (!completes || suspended) {
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with fishing_rod`);
              }
              const hook = yield* observeHook;
              if (completes) {
                yield* fishing.cancel("let breeding execute");
                yield* fishing.wait({ call: { timeoutMs: 5000 } });
                const result = yield* breeding.result({ call: { timeoutMs: 15000 } });
                yield* check(
                  result.reason === BreedCompletionReason.PAIR_LIMIT_REACHED &&
                    result.pairsStarted === 1 &&
                    result.animalsFed === 2,
                  `${mode} did not confirm feeding both animals`,
                );
                for (const tag of [`${TAG}_a`, `${TAG}_b`]) {
                  const data = yield* rcon(`data get entity @e[tag=${tag},limit=1] InLove`);
                  yield* check(
                    Number(data.match(/:\s*(\d+)$/)?.[1] ?? 0) > 0,
                    `${mode} was not confirmed by vanilla: ${data}`,
                  );
                }
                yield* check(
                  (yield* bot.world.player()).selectedHotbarSlot === 0,
                  `${mode} restored the selection captured before admission`,
                );
              } else {
                yield* breeding.cancel("cancel task without control");
                yield* breeding.wait({ call: { timeoutMs: 5000 } });
                yield* Effect.sleep("300 millis");
                const observed = yield* rcon(`execute if entity ${hook}`, { check: false });
                yield* check(
                  observed.startsWith("Test passed") &&
                    (yield* bot.world.player()).selectedHotbarSlot === 1 &&
                    (yield* fishing.refresh()).status === BotTaskStatus.RUNNING,
                  `${mode} disrupted the current fishing owner: ${observed}`,
                );
                yield* fishing.cancel("finish fishing");
                yield* fishing.wait({ call: { timeoutMs: 5000 } });
              }
              yield* eventually(
                hooks().pipe(
                  Effect.tap((result) => check(result.entities.length === 0, "Hook remains")),
                ),
                "5 seconds",
                "50 millis",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
