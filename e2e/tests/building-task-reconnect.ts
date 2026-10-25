import { randomUUID } from "node:crypto";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
  BuildBlockStatus,
  BuildCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "building-task-reconnect",
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
        for (const mixed of [false, true]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`gamemode creative ${BOT_NAME}`);
              yield* rcon("fill 4260 64 4 4278 64 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with stone 64`);
              yield* eventually(
                bot.inventory
                  .count({ selector: { itemIds: ["minecraft:stone"] } })
                  .pipe(
                    Effect.tap((count) => check(count === 64n, "Build materials have not arrived")),
                  ),
                "5 seconds",
                "50 millis",
              );
              if (mixed) {
                yield* rcon("setblock 4263 64 8 stone");
                yield* eventually(
                  bot.world
                    .block({ position: { x: 4263, y: 64, z: 8 } })
                    .pipe(
                      Effect.tap(({ block }) =>
                        check(
                          block?.blockId === "minecraft:stone",
                          "The initial block has not arrived",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const positions = mixed ? [4263, 4265, 4267, 4276] : [4263, 4269, 4276];
              const blocks = positions.map((x, index) => ({
                offset: { x: x - 4263, y: 0, z: 0 },
                blockId: mixed && index === 1 ? "minecraft:gold_block" : "minecraft:stone",
              }));
              const origin = { x: 4263, y: 64, z: 8 };
              const options = {
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                restoreSelectedSlot: true,
                idempotencyKey: randomUUID(),
              };
              const build = yield* bot.tasks.build(origin, blocks, options);
              yield* Effect.addFinalizer(() => build.cancel("test cleanup").pipe(Effect.ignore));
              const milestone = mixed ? 3 : 1;
              yield* eventually(
                build
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        Number(task.progress?.current) === milestone &&
                          task.status === BotTaskStatus.RUNNING,
                        "The build has not reached its confirmed-outcome checkpoint",
                      ),
                    ),
                  ),
                "20 seconds",
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
                build
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        task.status === BotTaskStatus.SUSPENDED &&
                          Number(task.progress?.current) === milestone,
                        "The confirmed build checkpoint was not suspended",
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
                  (yield* bot.tasks.build(origin, blocks, options)).id === build.id,
                  "Recovery changed build identity",
                );
              }
              yield* guard.cancel("Resume the build");
              const result = yield* build.result({ call: { timeoutMs: 30_000 } });
              const world = [];
              for (const [index, x] of positions.entries()) {
                const expected = mixed && index === 1 ? "air" : "stone";
                world.push(
                  (yield* rcon(`execute if block ${x} 64 8 ${expected}`, { check: false })) ===
                    "Test passed",
                );
              }
              yield* Console.log(JSON.stringify({ mixed, epochs, result, world }));
              const placed = BuildBlockStatus.PLACED;
              const expectedOutcomes = mixed
                ? [
                    BuildBlockStatus.ALREADY_CORRECT,
                    BuildBlockStatus.MISSING_MATERIAL,
                    placed,
                    placed,
                  ]
                : [placed, placed, placed];
              yield* check(
                result.reason ===
                  (mixed ? BuildCompletionReason.PARTIAL : BuildCompletionReason.COMPLETED) &&
                  result.blocksPlaced === (mixed ? 2 : 3) &&
                  result.blocksAlreadyCorrect === (mixed ? 1 : 0) &&
                  result.blocksFailed === (mixed ? 1 : 0) &&
                  result.outcomes.length === positions.length &&
                  result.outcomes.every(
                    (outcome, index) =>
                      outcome.status === expectedOutcomes[index] &&
                      outcome.position?.x === positions[index],
                  ) &&
                  world.every(Boolean),
                "Reconnect changed the confirmed build outcomes",
              );
              yield* eventually(
                bot.world
                  .player()
                  .pipe(
                    Effect.tap((player) =>
                      check(
                        player.selectedHotbarSlot === 0,
                        "Build did not restore its initial slot",
                      ),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
              const replay = yield* bot.tasks.build(origin, blocks, options);
              yield* check(
                replay.id === build.id &&
                  (yield* replay.result()).blocksPlaced === result.blocksPlaced,
                "Completed replay restarted building",
              );
              yield* check(
                (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xpBefore &&
                  (yield* rcon(`data get entity ${BOT_NAME} Score`)) === scoreBefore,
                "The build fixture changed XP or score",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
