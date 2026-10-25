import { randomUUID } from "node:crypto";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
  ExcavateCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "excavation-task-reconnect",
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
        const xpBefore = yield* rcon(`data get entity ${BOT_NAME} XpTotal`);
        const scoreBefore = yield* rcon(`data get entity ${BOT_NAME} Score`);
        const targets = [
          { x: 4263, y: 64, z: 8 },
          { x: 4268, y: 64, z: 8 },
          { x: 4273, y: 64, z: 8 },
          { x: 4277, y: 64, z: 6 },
          { x: 4277, y: 64, z: 10 },
        ];
        const from = { x: 4263, y: 64, z: 6 };
        const to = { x: 4277, y: 64, z: 10 };
        for (const maximumBlocks of [3, 0]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`gamemode creative ${BOT_NAME}`);
              yield* rcon("fill 4260 64 4 4278 64 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              for (const position of targets) {
                yield* rcon(`setblock ${position.x} ${position.y} ${position.z} stone`);
                yield* eventually(
                  bot.world
                    .block({ position })
                    .pipe(
                      Effect.tap(({ block }) =>
                        check(
                          block?.blockId === "minecraft:stone",
                          "The excavation block has not arrived",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const options = {
                maximumBlocks,
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                idempotencyKey: randomUUID(),
              };
              const excavation = yield* bot.tasks.excavate(from, to, options);
              yield* Effect.addFinalizer(() =>
                excavation.cancel("test cleanup").pipe(Effect.ignore),
              );
              yield* eventually(
                excavation
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        Number(task.progress?.current) === 1 &&
                          task.status === BotTaskStatus.RUNNING,
                        "Excavation has not reached its confirmed-block checkpoint",
                      ),
                    ),
                  ),
                "15 seconds",
                "10 millis",
              );
              const position = (yield* bot.world.player()).position!;
              const guard = yield* bot.tasks.guard(
                { x: Math.floor(position.x), y: 64, z: Math.floor(position.z) },
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
                excavation
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        task.status === BotTaskStatus.SUSPENDED &&
                          Number(task.progress?.current) === 1,
                        "The excavation checkpoint was not suspended",
                      ),
                    ),
                  ),
                "5 seconds",
                "20 millis",
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
                      Effect.tap((task) =>
                        check(task.status === BotTaskStatus.RUNNING, "The guard has not recovered"),
                      ),
                    ),
                  "10 seconds",
                  "50 millis",
                );
                yield* check(
                  (yield* bot.tasks.excavate(from, to, options)).id === excavation.id,
                  "Recovery changed excavation identity",
                );
              }
              yield* guard.cancel("Resume excavation");
              const result = yield* excavation.result({ call: { timeoutMs: 30_000 } });
              const expectedCount = maximumBlocks || targets.length;
              const world = [];
              for (const [index, target] of targets.entries()) {
                const expected = index < expectedCount ? "air" : "stone";
                world.push(
                  (yield* rcon(`execute if block ${target.x} ${target.y} ${target.z} ${expected}`, {
                    check: false,
                  })) === "Test passed",
                );
              }
              yield* Console.log(JSON.stringify({ maximumBlocks, epochs, result, world }));
              yield* check(
                result.blocksBroken === expectedCount &&
                  result.blocksSkipped === 70 &&
                  result.unreachableBlocks === 0 &&
                  result.reason ===
                    (maximumBlocks
                      ? ExcavateCompletionReason.BLOCK_LIMIT_REACHED
                      : ExcavateCompletionReason.AREA_CLEARED) &&
                  world.every(Boolean),
                "Reconnect changed the excavation limit or counts",
              );
              const replay = yield* bot.tasks.excavate(from, to, options);
              yield* check(
                replay.id === excavation.id &&
                  (yield* replay.result()).blocksBroken === expectedCount,
                "Completed replay restarted excavation",
              );
              yield* check(
                (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xpBefore &&
                  (yield* rcon(`data get entity ${BOT_NAME} Score`)) === scoreBefore,
                "The excavation fixture changed XP or score",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
