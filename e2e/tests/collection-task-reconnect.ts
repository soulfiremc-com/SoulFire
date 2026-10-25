import { randomUUID } from "node:crypto";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
  CollectBlocksCompletionReason,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "collection-task-reconnect",
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

        for (const partial of [true, false]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon(`gamemode creative ${BOT_NAME}`);
              yield* rcon("kill @e[type=item,x=4260,y=-200,z=4,dx=18,dy=300,dz=8]", {
                check: false,
              });
              yield* rcon("fill 4260 64 4 4278 64 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              yield* rcon(`gamemode survival ${BOT_NAME}`);
              for (const position of targets) {
                yield* rcon(`setblock ${position.x} ${position.y} ${position.z} dirt`);
                yield* eventually(
                  bot.world
                    .block({ position })
                    .pipe(
                      Effect.tap(({ block }) =>
                        check(
                          block?.blockId === "minecraft:dirt",
                          "The collection block has not arrived",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
              }
              const observed = yield* bot.world.queryBlocks({
                selector: { blockIds: ["minecraft:dirt"] },
                region: {
                  region: {
                    case: "box",
                    value: {
                      minimum: { x: 4242, y: 64, z: -12 },
                      maximum: { x: 4282, y: 64, z: 28 },
                    },
                  },
                },
                pageSize: 500,
              });
              yield* check(
                observed.blocks.length === targets.length &&
                  !observed.nextPageToken &&
                  observed.blocks.every(({ position }) =>
                    targets.some(
                      (target) =>
                        target.x === position?.x &&
                        target.y === position.y &&
                        target.z === position.z,
                    ),
                  ),
                "The collection search contains unexpected matching blocks outside the owned targets",
              );
              const options = {
                count: 3,
                searchRadius: 20,
                targetYRange: { minimum: 64, maximum: 64 },
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                idempotencyKey: randomUUID(),
              };
              const collection = yield* bot.tasks.collectBlocks("minecraft:dirt", options);
              yield* Effect.addFinalizer(() =>
                collection.cancel("test cleanup").pipe(Effect.ignore),
              );
              yield* eventually(
                collection
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        Number(task.progress?.current) === (partial ? 0 : 1) &&
                          (!partial || task.progress?.message === "Mining nearby matching block") &&
                          task.status === BotTaskStatus.RUNNING,
                        "Collection has not reached its confirmed-block checkpoint",
                      ),
                    ),
                  ),
                "15 seconds",
                "10 millis",
              );
              if (partial) yield* Effect.sleep("200 millis");
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
                collection
                  .refresh()
                  .pipe(
                    Effect.tap((task) =>
                      check(
                        task.status === BotTaskStatus.SUSPENDED &&
                          Number(task.progress?.current) === (partial ? 0 : 1),
                        "The collection checkpoint was not suspended",
                      ),
                    ),
                  ),
                "5 seconds",
                "20 millis",
              );
              const initialBlock = yield* rcon("execute if block 4263 64 8 dirt", { check: false });
              yield* check(
                initialBlock === (partial ? "Test passed" : "Test failed"),
                "The vanilla checkpoint does not match the expected break state",
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
                  (yield* bot.tasks.collectBlocks("minecraft:dirt", options)).id === collection.id,
                  "Recovery changed collection identity",
                );
              }
              yield* guard.cancel("Resume collection");
              const result = yield* collection.result({ call: { timeoutMs: 30_000 } });
              const expectedCount = 3;
              const world = [];
              for (const [index, target] of targets.entries()) {
                const expected = index < expectedCount ? "air" : "dirt";
                world.push(
                  (yield* rcon(`execute if block ${target.x} ${target.y} ${target.z} ${expected}`, {
                    check: false,
                  })) === "Test passed",
                );
              }
              yield* Console.log(JSON.stringify({ partial, epochs, result, world }));
              yield* check(
                result.blocksBroken === expectedCount &&
                  result.reason === CollectBlocksCompletionReason.TARGET_REACHED &&
                  world.every(Boolean),
                "Reconnect changed the block collection limit",
              );
              let conservation;
              yield* eventually(
                Effect.gen(function* () {
                  const inventory = Number(
                    yield* bot.inventory.count({ selector: { itemIds: ["minecraft:dirt"] } }),
                  );
                  const dropped = yield* rcon(
                    "execute as @e[type=item,x=4260,y=-200,z=4,dx=18,dy=300,dz=8] run data get entity @s Item.count",
                    { check: false },
                  );
                  const dropCount = [...dropped.matchAll(/entity data: (\d+)/g)].reduce(
                    (sum, match) => sum + Number(match[1]),
                    0,
                  );
                  conservation = { inventory, dropCount, raw: dropped };
                  yield* check(
                    inventory + dropCount === expectedCount,
                    "The collected items and remaining drops do not match removed dirt",
                  );
                }),
                "5 seconds",
                "100 millis",
              );
              yield* Console.log(JSON.stringify({ partial, conservation }));
              const replay = yield* bot.tasks.collectBlocks("minecraft:dirt", options);
              yield* check(
                replay.id === collection.id &&
                  (yield* replay.result()).blocksBroken === expectedCount,
                "Completed replay restarted collection",
              );
              yield* check(
                (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xpBefore &&
                  (yield* rcon(`data get entity ${BOT_NAME} Score`)) === scoreBefore,
                "The collection fixture changed XP or score",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
