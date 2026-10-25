import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "fishing-task-handoff",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
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
        const observeHook = (previous = "") =>
          eventually(
            hooks().pipe(
              Effect.tap((result) =>
                check(
                  result.entities.length === 1 && result.entities[0]?.reference?.uuid !== previous,
                  "The fishing task has not cast its line",
                ),
              ),
              Effect.map((result) => result.entities[0]!.reference!.uuid),
            ),
            "5 seconds",
            "50 millis",
          );
        const waitForNoHooks = eventually(
          hooks().pipe(Effect.tap((result) => check(result.entities.length === 0, "Line remains"))),
          "5 seconds",
          "50 millis",
        );
        for (const name of ["queued", "rejected", "suspended", "resumed", "queued-start"]) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with stick`);
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with fishing_rod`);
              yield* bot.selectHotbar(0);
              yield* eventually(
                bot.inventory
                  .count({ selector: { itemIds: ["minecraft:fishing_rod"] } })
                  .pipe(
                    Effect.tap((count) => check(count === 1n, "The fishing rod has not arrived")),
                  ),
                "5 seconds",
                "50 millis",
              );
              const active = yield* bot.tasks.fish({ maximumCatches: 0, castTimeoutTicks: 20 });
              yield* Effect.addFinalizer(() => active.cancel("test cleanup").pipe(Effect.ignore));
              const originalHook = yield* observeHook();
              const preempt = name === "suspended" || name === "resumed";
              const contender = yield* bot.tasks.fish({
                maximumCatches: 0,
                castTimeoutTicks: 20,
                conflictPolicy: preempt
                  ? BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY
                  : name === "rejected"
                    ? BotTaskConflictPolicy.REJECT
                    : BotTaskConflictPolicy.QUEUE,
                priority: preempt ? BotTaskPriority.HIGH : BotTaskPriority.NORMAL,
              });
              yield* Effect.addFinalizer(() =>
                contender.cancel("test cleanup").pipe(Effect.ignore),
              );
              yield* check(
                contender.snapshot.status ===
                  (preempt
                    ? BotTaskStatus.RUNNING
                    : name === "rejected"
                      ? BotTaskStatus.FAILED
                      : BotTaskStatus.WAITING_FOR_RESOURCES),
                `${name} did not exercise the requested scheduling state`,
              );
              if (preempt) {
                yield* check(
                  (yield* active.refresh()).status === BotTaskStatus.SUSPENDED,
                  "The original fishing task was not suspended",
                );
              }
              if (name === "queued-start" || name === "resumed") {
                const retiring = name === "queued-start" ? active : contender;
                const continuing = name === "queued-start" ? contender : active;
                yield* retiring.cancel("hand off fishing control");
                yield* retiring.wait({ call: { timeoutMs: 5000 } });
                yield* observeHook(originalHook);
                yield* check(
                  (yield* continuing.refresh()).status === BotTaskStatus.RUNNING,
                  `${name} did not activate the continuing task`,
                );
                yield* continuing.cancel("finish fishing");
                yield* continuing.wait({ call: { timeoutMs: 5000 } });
                yield* waitForNoHooks;
                yield* check(
                  (yield* bot.world.player()).selectedHotbarSlot === 0,
                  `${name} restored the slot from before admission instead of before execution`,
                );
              } else {
                const retiring = name === "suspended" ? active : contender;
                const continuing = name === "suspended" ? contender : active;
                if (name !== "rejected") {
                  yield* retiring.cancel("cancel task without control");
                  yield* retiring.wait({ call: { timeoutMs: 5000 } });
                }
                // Allow removal packets to arrive, while remaining below the dry-cast timeout.
                yield* Effect.sleep("300 millis");
                const observed = yield* rcon(`execute if entity ${originalHook}`, { check: false });
                yield* check(
                  observed.startsWith("Test passed"),
                  `${name} retracted the current owner's line: ${observed}`,
                );
                yield* check(
                  (yield* continuing.refresh()).status === BotTaskStatus.RUNNING &&
                    (yield* bot.world.player()).selectedHotbarSlot === 1,
                  `${name} disturbed the active fishing control`,
                );
                yield* continuing.cancel("finish fishing");
                yield* continuing.wait({ call: { timeoutMs: 5000 } });
                yield* waitForNoHooks;
              }
              yield* bot.selectHotbar(0);
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
