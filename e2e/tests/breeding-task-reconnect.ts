import { randomUUID } from "node:crypto";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
  BreedCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const TAG = "sf_e2e_breed_reconnect";

export default {
  name: "breeding-task-reconnect",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds", "100 millis");
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 4260 63 4 4278 63 12 air");
            yield* rcon("kill @e[type=item,x=4260,y=-200,z=4,dx=18,dy=300,dz=8]", { check: false });
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
        const xpBefore = yield* rcon(`data get entity ${BOT_NAME} XpTotal`);
        const scoreBefore = yield* rcon(`data get entity ${BOT_NAME} Score`);
        for (const partialPair of [false, true]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
              yield* eventually(
                bot.world
                  .queryEntities({
                    radius: 32,
                    selector: { entityTypes: ["minecraft:cow"], alive: true },
                  })
                  .pipe(
                    Effect.tap((value) =>
                      check(value.entities.length === 0, "The scene has another cow"),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
              yield* bot.selectHotbar(0);
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with wheat 32`);
              yield* eventually(
                bot.inventory
                  .count("minecraft:wheat")
                  .pipe(
                    Effect.tap((count) =>
                      check(count === 32n, "The breeding food has not arrived"),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
              const positions = partialPair ? [4263, 4275] : [4263, 4264, 4268, 4269, 4274, 4275];
              for (const [index, x] of positions.entries()) {
                // NoAI confirms feeding without producing an untagged calf or XP.
                yield* rcon(
                  `summon cow ${x}.5 64 8.5 {Tags:["${TAG}","${TAG}_${index}"],NoAI:1b,NoGravity:1b,Invulnerable:1b,Silent:1b,DeathLootTable:"minecraft:empty"}`,
                );
              }
              yield* eventually(
                bot.world
                  .queryEntities({
                    radius: 32,
                    selector: { entityTypes: ["minecraft:cow"], alive: true },
                  })
                  .pipe(
                    Effect.tap((value) =>
                      check(
                        value.entities.length === positions.length,
                        "The cows have not arrived",
                      ),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
              const options = {
                animals: { entityTypes: ["minecraft:cow"] },
                center: { x: 4270, y: 64, z: 8 },
                radius: 12,
                maximumPairs: partialPair ? 1 : 2,
                completeWhenNoPair: true,
                breedingTimeoutTicks: 40,
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                idempotencyKey: randomUUID(),
              };
              const task = yield* bot.tasks.breed(options);
              yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
              yield* eventually(
                task
                  .refresh()
                  .pipe(
                    Effect.tap((value) =>
                      check(
                        partialPair
                          ? value.progress?.message === "Walking to second breeding animal" &&
                              Number(value.progress?.current) === 0
                          : Number(value.progress?.current) === 1,
                        `Breeding checkpoint: status=${value.status}, current=${value.progress?.current}, message=${value.progress?.message}, failure=${value.failure?.message}`,
                      ),
                    ),
                  ),
                "15 seconds",
                "10 millis",
              );
              const position = (yield* bot.world.player()).position!;
              const guard = yield* bot.tasks.guard(
                { x: Math.floor(position.x), y: Math.floor(position.y), z: Math.floor(position.z) },
                { entityTypes: ["minecraft:zombie"] },
                {
                  priority: BotTaskPriority.HIGH,
                  conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
                  completeWhenClear: false,
                  reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                },
              );
              yield* Effect.addFinalizer(() => guard.cancel("test cleanup").pipe(Effect.ignore));
              yield* eventually(
                task
                  .refresh()
                  .pipe(
                    Effect.tap((value) =>
                      check(
                        value.status === BotTaskStatus.SUSPENDED &&
                          Number(value.progress?.current) === (partialPair ? 0 : 1),
                        "Breeding missed its suspension checkpoint",
                      ),
                    ),
                  ),
                "5 seconds",
                "10 millis",
              );
              const love = Effect.gen(function* () {
                const values = [];
                for (const index of positions.keys()) {
                  const data = yield* rcon(
                    `data get entity @e[tag=${TAG}_${index},limit=1] InLove`,
                  );
                  values.push(Number(data.match(/:\s*(\d+)$/)?.[1]));
                }
                return values;
              });
              const before = yield* love;
              yield* check(
                before.filter((value) => value > 0).length === (partialPair ? 1 : 2),
                "The checkpoint does not match vanilla feeding state",
              );
              const epochs = [(yield* bot.world.player()).connectionEpoch];
              for (let cycle = 0; cycle < 2; cycle++) {
                yield* bot.restart();
                const player = yield* eventually(
                  bot.world
                    .player()
                    .pipe(
                      Effect.tap((value) =>
                        check(
                          !epochs.includes(value.connectionEpoch),
                          "The old connection is still observed",
                        ),
                      ),
                    ),
                  "40 seconds",
                  "50 millis",
                );
                epochs.push(player.connectionEpoch);
                yield* eventually(
                  guard
                    .refresh()
                    .pipe(
                      Effect.tap((value) =>
                        check(
                          value.status === BotTaskStatus.RUNNING,
                          "The guard has not recovered",
                        ),
                      ),
                    ),
                  "10 seconds",
                  "50 millis",
                );
                yield* check(
                  (yield* bot.tasks.breed(options)).id === task.id,
                  "Recovery changed task identity",
                );
              }
              yield* guard.cancel("Resume breeding");
              const result = yield* task.result({ call: { timeoutMs: 30_000 } });
              const after = yield* love;
              yield* Console.log(JSON.stringify({ partialPair, epochs, before, after, result }));
              yield* check(
                result.reason === BreedCompletionReason.PAIR_LIMIT_REACHED &&
                  result.pairsStarted === options.maximumPairs &&
                  result.animalsFed === options.maximumPairs * 2 &&
                  result.failedPairs === 0 &&
                  after.filter((value) => value > 0).length === options.maximumPairs * 2,
                "Reconnect changed the confirmed breeding limit or feeding totals",
              );
              yield* eventually(
                bot.world
                  .player()
                  .pipe(
                    Effect.tap((player) =>
                      check(
                        player.selectedHotbarSlot === 0,
                        "Breeding did not restore its initial selected slot",
                      ),
                    ),
                  ),
                "3 seconds",
                "50 millis",
              );
              const replay = yield* bot.tasks.breed(options);
              yield* check(
                replay.id === task.id && (yield* replay.result()).animalsFed === result.animalsFed,
                "Completed replay restarted breeding",
              );
              yield* check(
                (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xpBefore &&
                  (yield* rcon(`data get entity ${BOT_NAME} Score`)) === scoreBefore,
                "The breeding fixture changed XP or score",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
