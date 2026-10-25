import { randomUUID } from "node:crypto";
import type { SoulFireTask } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Console, Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const STATION = { x: 4263, y: 64, z: 8 };
const BLOCK = "4263 64 8";

export default {
  name: "recipe-task-reconnect",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds", "100 millis");
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`data remove block ${BLOCK} RecipesUsed`, { check: false });
            yield* rcon(`data merge block ${BLOCK} {Items:[]}`, { check: false });
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
        yield* rcon("fill 4260 63 4 4278 63 12 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);

        const xpBefore = yield* rcon(`data get entity ${BOT_NAME} XpTotal`);
        const scoreBefore = yield* rcon(`data get entity ${BOT_NAME} Score`);
        for (const kind of ["smelt", "brew"] as const) {
          for (const phase of ["processing", "confirmed-batch", "pending-output"] as const) {
            yield* Effect.scoped(
              Effect.gen(function* () {
                yield* bot.closeContainer();
                yield* rcon(`clear ${BOT_NAME}`, { check: false });
                yield* rcon(`data remove block ${BLOCK} RecipesUsed`, { check: false });
                yield* rcon(`data merge block ${BLOCK} {Items:[]}`, { check: false });
                yield* rcon(`setblock ${BLOCK} air`, { check: false });
                yield* rcon(`setblock ${BLOCK} ${kind === "smelt" ? "furnace" : "brewing_stand"}`);
                yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
                const count = phase === "confirmed-batch" ? (kind === "smelt" ? 2 : 4) : 1;
                const batchSize = kind === "smelt" ? 1 : Math.min(count, 3);
                if (kind === "smelt") {
                  // Separate stacks force a confirmed boundary before a second batch.
                  for (let slot = 0; slot < count; slot++) {
                    yield* rcon(`item replace entity ${BOT_NAME} hotbar.${slot} with iron_ore`);
                  }
                  yield* rcon(`give ${BOT_NAME} coal`);
                } else {
                  yield* rcon(
                    `give ${BOT_NAME} potion[minecraft:potion_contents={potion:"minecraft:water"}] ${count}`,
                  );
                  yield* rcon(`give ${BOT_NAME} nether_wart ${Math.ceil(count / 3)}`);
                  yield* rcon(`give ${BOT_NAME} blaze_powder`);
                }
                yield* eventually(
                  bot.inventory
                    .count(kind === "smelt" ? "minecraft:iron_ore" : "minecraft:potion")
                    .pipe(
                      Effect.tap((amount) =>
                        check(amount === BigInt(count), "Recipe inputs have not arrived"),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
                const options = {
                  station: STATION,
                  reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                  idempotencyKey: randomUUID(),
                };
                const start = () =>
                  kind === "smelt"
                    ? bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, count, options)
                    : bot.tasks.brew(
                        { itemIds: ["minecraft:potion"] },
                        { itemIds: ["minecraft:nether_wart"] },
                        count,
                        options,
                      );
                const task = yield* start();
                yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
                const epochs = [(yield* bot.world.player()).connectionEpoch];
                const pause = Effect.fn(function* () {
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
                  yield* Effect.addFinalizer(() =>
                    guard.cancel("test cleanup").pipe(Effect.ignore),
                  );
                  yield* eventually(
                    task
                      .refresh()
                      .pipe(
                        Effect.tap((value) =>
                          check(
                            value.status === BotTaskStatus.SUSPENDED,
                            "Recipe missed its suspension checkpoint",
                          ),
                        ),
                      ),
                    "5 seconds",
                    "10 millis",
                  );
                  return guard;
                });
                const reconnect = Effect.fn(function* (guard: Pick<SoulFireTask, "refresh">) {
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
                  const recovered = yield* task.refresh();
                  yield* check(
                    recovered.status !== BotTaskStatus.FAILED,
                    `Recipe failed during recovery: ${JSON.stringify(recovered.failure)}`,
                  );
                  yield* check(
                    (yield* start()).id === task.id,
                    "Recovery changed durable task identity",
                  );
                });
                const waitCooking = eventually(
                  rcon(
                    `data get block ${BLOCK} ${kind === "smelt" ? "cooking_time_spent" : "BrewTime"}`,
                  ).pipe(
                    Effect.tap((text) =>
                      check(
                        Number(text.match(/: (\d+)$/)?.[1]) > 0,
                        "Vanilla has not started processing",
                      ),
                    ),
                  ),
                  "15 seconds",
                  "100 millis",
                );
                const finishBatch = (size: number) =>
                  Effect.gen(function* () {
                    for (let slot = 0; slot < (kind === "smelt" ? 1 : size); slot++) {
                      const predicate =
                        kind === "smelt"
                          ? 'Slot:2b,id:"minecraft:iron_ingot"'
                          : `Slot:${slot}b,components:{"minecraft:potion_contents":{potion:"minecraft:awkward"}}`;
                      yield* eventually(
                        rcon(`execute if data block ${BLOCK} Items[{${predicate}}]`, {
                          check: false,
                        }).pipe(
                          Effect.tap((text) =>
                            check(text.startsWith("Test passed"), "Vanilla output is not ready"),
                          ),
                        ),
                        "30 seconds",
                        "100 millis",
                      );
                    }
                    // Keep the saved player's XP intact while testing output collection.
                    if (kind === "smelt") {
                      yield* rcon(`data remove block ${BLOCK} RecipesUsed`);
                      yield* check(
                        (yield* rcon(`data get block ${BLOCK} RecipesUsed`)).endsWith(": {}"),
                        "The furnace still has pending XP credit",
                      );
                    }
                  });

                yield* waitCooking;
                const first = yield* pause();
                if (phase === "processing") {
                  yield* reconnect(first);
                  yield* reconnect(first);
                }
                yield* finishBatch(batchSize);
                yield* first.cancel("Collect the completed batch");
                if (phase === "confirmed-batch") {
                  yield* eventually(
                    task
                      .refresh()
                      .pipe(
                        Effect.tap((value) =>
                          check(
                            Number(value.progress?.current) === batchSize,
                            "The first batch has not been confirmed",
                          ),
                        ),
                      ),
                    "10 seconds",
                    "10 millis",
                  );
                  const checkpoint = yield* pause();
                  yield* reconnect(checkpoint);
                  yield* check(
                    Number((yield* task.refresh()).progress?.current) === batchSize,
                    "Reconnect reset confirmed progress",
                  );
                  yield* checkpoint.cancel("Process the remaining batch");
                  yield* waitCooking;
                  const last = yield* pause();
                  yield* finishBatch(count - batchSize);
                  yield* last.cancel("Collect the remaining output");
                } else if (phase === "pending-output") {
                  yield* eventually(
                    bot
                      .inventoryState()
                      .pipe(
                        Effect.tap((value) =>
                          check(
                            value.carriedItem?.itemId ===
                              (kind === "smelt" ? "minecraft:iron_ingot" : "minecraft:potion"),
                            "No pending recipe output cursor",
                          ),
                        ),
                      ),
                    "10 seconds",
                    "10 millis",
                  );
                  const checkpoint = yield* pause();
                  yield* check(
                    Number((yield* task.refresh()).progress?.current) === 0,
                    "The pending output was already confirmed",
                  );
                  yield* reconnect(checkpoint);
                  yield* checkpoint.cancel("Reconcile the returned output");
                }
                const result = yield* task.result({ call: { timeoutMs: 10_000 } });
                yield* check(
                  result.outputs.reduce((sum, item) => sum + item.count, 0) === count,
                  "Recovery lost or duplicated reported recipe output",
                );
                yield* check(
                  result.outputs.every((item) =>
                    kind === "smelt"
                      ? item.itemId === "minecraft:iron_ingot"
                      : item.potion?.potionId === "minecraft:awkward",
                  ),
                  "Recovered recipe returned the wrong output",
                );
                const item =
                  kind === "smelt"
                    ? "iron_ingot"
                    : 'potion[minecraft:potion_contents={potion:"minecraft:awkward"}]';
                const actual = yield* rcon(`clear ${BOT_NAME} ${item} 0`, { check: false });
                yield* check(
                  actual.includes(`Found ${count} `),
                  `Vanilla output differs: ${actual}`,
                );
                yield* check(
                  (yield* rcon(`data get block ${BLOCK} Items`)).endsWith(": []"),
                  "Recovered task left station contents behind",
                );
                const replay = yield* start();
                yield* check(
                  replay.id === task.id &&
                    (yield* replay.result()).outputs.length === result.outputs.length,
                  "Completed recipe replay restarted execution",
                );
                yield* check(
                  (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xpBefore &&
                    (yield* rcon(`data get entity ${BOT_NAME} Score`)) === scoreBefore,
                  "The recipe fixture changed saved XP or score",
                );
                yield* Console.log(JSON.stringify({ kind, phase, epochs, result, actual }));
              }),
            );
          }
        }
      }),
    ),
} satisfies E2ETest;
