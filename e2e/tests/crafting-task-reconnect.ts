import { randomUUID } from "node:crypto";
import { goals, type SoulFireTask } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
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
} from "../harness.ts";

const TABLE = { x: 4215, y: 64, z: 8 };
const URGENT = {
  priority: BotTaskPriority.HIGH,
  conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
};

const waitStatus = (task: Pick<SoulFireTask, "refresh">, status: BotTaskStatus) =>
  eventually(
    task
      .refresh()
      .pipe(
        Effect.flatMap((snapshot) =>
          check(snapshot.status === status, `Task did not reach status ${status}`),
        ),
      ),
    "10 seconds",
    "20 millis",
  );
const count = (item: string, expected: number) =>
  eventually(
    rcon(`clear ${BOT_NAME} ${item} 0`, { check: false }).pipe(
      Effect.flatMap((text) =>
        check(
          expected === 0
            ? text.includes("No items were found")
            : text.includes(`Found ${expected} `),
          `Vanilla ${item} count differs from ${expected}: ${text}`,
        ),
      ),
    ),
    "3 seconds",
  );

export default {
  name: "crafting-task-reconnect",
  run: ({ bot }) => {
    const guardAt = (position: { x: number; y: number; z: number }, critical = false) =>
      bot.tasks.guard(
        position,
        { entityTypes: ["minecraft:zombie"] },
        {
          ...URGENT,
          priority: critical ? BotTaskPriority.CRITICAL : BotTaskPriority.HIGH,
          completeWhenClear: false,
          reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
        },
      );
    const recipeFor = (item: string) =>
      eventually(
        bot.recipes.list({ resultItemId: item }).pipe(
          Effect.map((response) => response.recipes[0]),
          Effect.filterOrFail(
            (recipe) => recipe !== undefined,
            () => new CheckFailed({ message: "The crafting recipe did not load" }),
          ),
        ),
        "5 seconds",
      );
    return Effect.scoped(
      Effect.gen(function* () {
        const reconnect = Effect.fn(function* (guard: Pick<SoulFireTask, "refresh">) {
          const before = (yield* bot.world.player()).connectionEpoch;
          yield* bot.restart();
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((player) =>
                  check(player.connectionEpoch !== before, "Still observing the old connection"),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          yield* waitStatus(guard, BotTaskStatus.RUNNING);
        });
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
          }).pipe(Effect.ignore),
        );
        for (const logs of [16, 64]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
              yield* rcon(`give ${BOT_NAME} oak_log ${logs}`);
              const recipe = yield* recipeFor("minecraft:oak_planks");
              const options = {
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
                idempotencyKey: randomUUID(),
              };
              const craft = yield* bot.tasks.craft(recipe.recipeId, 16, options);
              yield* Effect.addFinalizer(() => craft.cancel("test cleanup").pipe(Effect.ignore));
              for (const completed of logs === 16 ? [3, 8] : [3]) {
                yield* eventually(
                  craft
                    .refresh()
                    .pipe(
                      Effect.flatMap((task) =>
                        check(
                          Number(task.progress?.current ?? 0) >= completed,
                          "No craft progress",
                        ),
                      ),
                    ),
                  "15 seconds",
                  "10 millis",
                );
                const guard = yield* guardAt({ x: 0, y: 64, z: 0 });
                yield* Effect.addFinalizer(() => guard.cancel("test cleanup").pipe(Effect.ignore));
                yield* waitStatus(craft, BotTaskStatus.SUSPENDED);
                const menu = yield* bot.inventoryState();
                yield* check(
                  !menu.carriedItem && !menu.slots.some((slot) => slot.slot >= 1 && slot.slot <= 4),
                  "The confirmed-progress fixture still has crafting inputs or a cursor item",
                );
                yield* reconnect(guard);
                yield* check(
                  (yield* bot.tasks.craft(recipe.recipeId, 16, options)).id === craft.id,
                  "Recovery changed the durable task identity",
                );
                yield* guard.cancel("Resume crafting on the new connection");
                yield* waitStatus(guard, BotTaskStatus.CANCELLED);
              }
              const result = yield* craft.result({ call: { timeoutMs: 20_000 } });
              yield* check(result.crafted === 16, "Recovery changed the requested operation count");
              yield* count("oak_planks", 64);
              yield* count("oak_log", logs - 16);
              const replay = yield* bot.tasks.craft(recipe.recipeId, 16, options);
              yield* check(
                replay.id === craft.id &&
                  (yield* replay.result({ call: { timeoutMs: 5000 } })).crafted === 16,
                "Completed result replay restarted crafting",
              );
            }),
          );
        }

        const forced: number[] = [];
        for (const x of [4208, 4224]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked"))
            forced.push(x);
        }
        yield* rcon("forceload add 4208 0 4239 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("fill 4208 63 0 4239 66 15 air", { check: false });
            for (const x of forced) yield* rcon(`forceload remove ${x} 0 ${x + 15} 15`);
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4208 63 0 4239 63 15 stone");
        yield* rcon("setblock 4215 64 8 crafting_table");
        for (const operations of [1, 4]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* placeBot(bot, { x: 4216.5, y: 64, z: 8.5 });
              yield* rcon(`give ${BOT_NAME} oak_planks ${operations * 8}`);
              const recipe = yield* recipeFor("minecraft:chest");
              const craft = yield* bot.tasks.craft(recipe.recipeId, operations, {
                station: TABLE,
                reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
              });
              yield* Effect.addFinalizer(() => craft.cancel("test cleanup").pipe(Effect.ignore));
              yield* eventually(
                bot
                  .inventoryState()
                  .pipe(
                    Effect.flatMap((menu) =>
                      check(
                        menu.carriedItem?.itemId === "minecraft:chest",
                        "No pending crafting output",
                      ),
                    ),
                  ),
                "10 seconds",
                "10 millis",
              );
              const diversion = yield* bot.tasks.goTo(
                goals.block({ x: 4232, y: 64, z: 8 }),
                URGENT,
              );
              yield* Effect.addFinalizer(() =>
                diversion.cancel("test cleanup").pipe(Effect.ignore),
              );
              yield* eventually(
                bot
                  .inventoryState()
                  .pipe(
                    Effect.flatMap((menu) =>
                      check(
                        !menu.carriedItem && menu.layout?.containerType === "inventory",
                        "Navigation did not close the table and return its cursor item",
                      ),
                    ),
                  ),
                "5 seconds",
                "20 millis",
              );
              const position = (yield* bot.world.player()).position!;
              const guard = yield* guardAt(
                {
                  x: Math.floor(position.x),
                  y: Math.floor(position.y),
                  z: Math.floor(position.z),
                },
                true,
              );
              yield* Effect.addFinalizer(() => guard.cancel("test cleanup").pipe(Effect.ignore));
              yield* waitStatus(craft, BotTaskStatus.SUSPENDED);
              yield* check(
                Number((yield* craft.refresh()).progress?.current ?? 0) === 0,
                "The pending-output fixture already confirmed the first operation",
              );
              yield* count("chest", 1);
              yield* count("oak_planks", (operations - 1) * 8);
              yield* reconnect(guard);
              yield* diversion.cancel("Do not resume the old navigation");
              yield* guard.cancel("Resume crafting with the stored pending output");
              yield* waitStatus(guard, BotTaskStatus.CANCELLED);
              const result = yield* craft.result({ call: { timeoutMs: 20_000 } });
              yield* check(
                result.crafted === operations,
                "The stored pending output was counted incorrectly",
              );
              yield* check(
                Number((yield* craft.refresh()).progress?.current ?? 0) === operations,
                "Completed recovery reported stale operation progress",
              );
              yield* count("chest", operations);
              yield* count("oak_planks", 0);
              yield* eventually(
                bot
                  .inventoryState()
                  .pipe(
                    Effect.flatMap((menu) =>
                      check(
                        !menu.carriedItem && menu.layout?.containerType === "inventory",
                        "Recovery left a cursor item or crafting table open",
                      ),
                    ),
                  ),
                "3 seconds",
              );
            }),
          );
        }
      }),
    );
  },
} satisfies E2ETest;
