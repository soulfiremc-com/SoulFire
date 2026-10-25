import { randomUUID } from "node:crypto";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
  FarmCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const CROPS = [4263, 4266, 4270, 4274, 4277];

export default {
  name: "farming-task-reconnect",
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
            yield* rcon("fill 4260 63 4 4278 64 12 air");
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
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        for (const pendingReplant of [false, true]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`clear ${BOT_NAME}`, { check: false });
              yield* rcon("fill 4260 64 4 4278 64 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              for (const x of CROPS) {
                yield* rcon(`setblock ${x} 63 8 farmland[moisture=7]`);
                yield* rcon(`setblock ${x} 64 8 wheat[age=7]`);
                yield* eventually(
                  bot.world
                    .block({ position: { x, y: 64, z: 8 } })
                    .pipe(
                      Effect.tap(({ block }) =>
                        check(block?.properties.age === "7", "The mature crop has not arrived"),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const giveSeeds = Effect.gen(function* () {
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with wheat_seeds 16`);
                yield* eventually(
                  bot.inventory
                    .count({ selector: { itemIds: ["minecraft:wheat_seeds"] } })
                    .pipe(
                      Effect.tap((count) =>
                        check(count === 16n, "The replant seeds have not arrived"),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              });
              if (!pendingReplant) yield* giveSeeds;
              const maximumHarvests = pendingReplant ? 1 : 3;
              const options = {
                center: { x: 4270, y: 64, z: 8 },
                radius: 10,
                maximumHarvests,
                replant: true,
                restoreSelectedSlot: true,
                completeWhenNoMatureCrops: true,
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                idempotencyKey: randomUUID(),
              };
              const farm = yield* bot.tasks.farm(options);
              yield* Effect.addFinalizer(() => farm.cancel("test cleanup").pipe(Effect.ignore));
              const epochs = [(yield* bot.world.player()).connectionEpoch];
              for (const milestone of pendingReplant ? [1] : [1, 2]) {
                yield* eventually(
                  farm
                    .refresh()
                    .pipe(
                      Effect.tap((task) =>
                        check(
                          Number(task.progress?.current) === milestone &&
                            task.progress?.message ===
                              (pendingReplant ? "Waiting for replant item" : "Crop replanted"),
                          "The crop cycle has not reached its reconnect checkpoint",
                        ),
                      ),
                    ),
                  "15 seconds",
                  "10 millis",
                );
                const position = (yield* bot.world.player()).position!;
                const guard = yield* bot.tasks.guard(
                  {
                    x: Math.floor(position.x),
                    y: Math.floor(position.y),
                    z: Math.floor(position.z),
                  },
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
                  farm
                    .refresh()
                    .pipe(
                      Effect.tap((task) =>
                        check(
                          task.status === BotTaskStatus.SUSPENDED &&
                            Number(task.progress?.current) === milestone,
                          "The reconnect checkpoint was not suspended",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "20 millis",
                );
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
                      Effect.tap((task) =>
                        check(task.status === BotTaskStatus.RUNNING, "The guard has not recovered"),
                      ),
                    ),
                  "10 seconds",
                  "50 millis",
                );
                yield* check(
                  (yield* bot.tasks.farm(options)).id === farm.id,
                  "Recovery changed task identity",
                );
                if (pendingReplant) yield* giveSeeds;
                yield* guard.cancel("Resume the crop cycle");
              }
              const result = yield* farm.result({ call: { timeoutMs: 30_000 } });
              yield* check(
                result.reason === FarmCompletionReason.HARVEST_LIMIT_REACHED &&
                  result.cropsHarvested === maximumHarvests &&
                  result.cropsReplanted === maximumHarvests,
                `Reconnect changed the finite farm result: ${JSON.stringify(result)}`,
              );
              const crops = [];
              for (const x of CROPS) {
                const expected = CROPS.indexOf(x) < maximumHarvests ? 0 : 7;
                const actual = yield* rcon(`execute if block ${x} 64 8 wheat[age=${expected}]`, {
                  check: false,
                });
                crops.push({ x, expected, matches: actual === "Test passed" });
              }
              yield* Console.log(JSON.stringify({ pendingReplant, epochs, result, crops }));
              yield* check(
                crops.every((crop) => crop.matches),
                "Reconnect changed extra crops or skipped a pending replant",
              );
              yield* eventually(
                rcon(`data get entity ${BOT_NAME} SelectedItemSlot`).pipe(
                  Effect.tap((value) =>
                    check(
                      value.endsWith(": 0"),
                      `Farming did not restore its initial slot: ${value}`,
                    ),
                  ),
                ),
                "5 seconds",
                "50 millis",
              );
              const replay = yield* bot.tasks.farm(options);
              yield* check(
                replay.id === farm.id &&
                  (yield* replay.result()).cropsHarvested === maximumHarvests,
                "Completed task replay restarted farming",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
