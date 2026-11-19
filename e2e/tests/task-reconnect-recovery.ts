import { randomUUID } from "node:crypto";
import { goals } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskReconnectPolicy,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "task-reconnect-recovery",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
        const key = randomUUID();
        const goal = goals.block({ x: 0, y: 64, z: 0 });
        const completed = yield* bot.tasks.goTo(goal, { idempotencyKey: `${key}-complete` });
        const result = yield* completed.result({ call: { timeoutMs: 5000 } });
        const options = {
          completeWhenClear: false,
          priority: BotTaskPriority.HIGH,
          reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
          conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
          idempotencyKey: `${key}-guard`,
        };
        const startGuard = () =>
          bot.tasks.guard({ x: 0, y: 64, z: 0 }, { entityTypes: ["minecraft:zombie"] }, options);
        const guard = yield* startGuard();
        yield* Effect.addFinalizer(() => guard.cancel("test cleanup").pipe(Effect.ignore));
        const monitor = yield* bot.tasks.autoEat([], {
          reconnectPolicy: BotTaskReconnectPolicy.FAIL,
        });
        yield* Effect.addFinalizer(() => monitor.cancel("test cleanup").pipe(Effect.ignore));
        const expectRunning = () =>
          eventually(
            guard
              .refresh()
              .pipe(
                Effect.flatMap((task) =>
                  check(
                    task.status === BotTaskStatus.RUNNING,
                    `Guard did not recover: ${task.status}, ${task.failure?.code ?? ""}`,
                  ),
                ),
              ),
            "10 seconds",
            "50 millis",
          );
        yield* expectRunning();
        const epochs = new Set([(yield* bot.world.player()).connectionEpoch]);
        for (let cycle = 0; cycle < 3; cycle++) {
          yield* bot.restart();
          yield* bot.connect({ readyTimeoutMs: 30_000 });
          const player = yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((current) =>
                  check(!epochs.has(current.connectionEpoch), "Old connection"),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          epochs.add(player.connectionEpoch);
          yield* expectRunning();
          yield* check((yield* startGuard()).id === guard.id, "Recovery changed task identity");
          const conflict = yield* bot.tasks.goTo(goal, {
            conflictPolicy: BotTaskConflictPolicy.REJECT,
          });
          const rejected = yield* conflict.wait({ call: { timeoutMs: 5000 } });
          yield* check(
            rejected.status === BotTaskStatus.FAILED &&
              rejected.failure?.code === "resource_conflict",
            "The recovered guard did not own its resources",
          );
          const failed = yield* monitor.refresh();
          yield* check(
            failed.status === BotTaskStatus.FAILED && failed.failure?.code === "connection_lost",
            "FAIL reconnect policy resumed its execution",
          );
          const replay = yield* bot.tasks.goTo(goal, { idempotencyKey: `${key}-complete` });
          yield* check(replay.id === completed.id, "Reconnect lost completed task identity");
          const replayResult = yield* replay.result({ call: { timeoutMs: 5000 } });
          yield* check(
            replayResult.finalPosition?.x === result.finalPosition?.x &&
              replayResult.finalPosition?.y === result.finalPosition?.y &&
              replayResult.finalPosition?.z === result.finalPosition?.z,
            "Reconnect changed the completed task result",
          );
        }
        yield* guard.cancel("Cancel the replacement execution");
        yield* check(
          (yield* guard.wait({ call: { timeoutMs: 5000 } })).status === BotTaskStatus.CANCELLED,
          "The recovered execution did not cancel",
        );
        const sentinel = yield* bot.tasks.goTo(goal, {
          conflictPolicy: BotTaskConflictPolicy.REJECT,
        });
        yield* sentinel.result({ call: { timeoutMs: 5000 } });

        const paused = yield* bot.tasks.guard(
          { x: 0, y: 64, z: 0 },
          { entityTypes: ["minecraft:zombie"] },
          { ...options, idempotencyKey: `${key}-cancel-before-recovery` },
        );
        yield* Effect.addFinalizer(() => paused.cancel("test cleanup").pipe(Effect.ignore));
        yield* bot.stop();
        yield* eventually(
          paused
            .refresh()
            .pipe(
              Effect.flatMap((task) =>
                check(task.status === BotTaskStatus.RECOVERING, "Task did not pause on disconnect"),
              ),
            ),
          "10 seconds",
        );
        yield* paused.cancel("Cancel before the replacement connection exists");
        yield* bot.start();
        yield* bot.connect({ readyTimeoutMs: 30_000 });
        yield* eventually(
          bot.world
            .player()
            .pipe(
              Effect.flatMap((player) =>
                check(
                  !epochs.has(player.connectionEpoch),
                  "Cancelled task still on old connection",
                ),
              ),
            ),
          "30 seconds",
          "50 millis",
        );
        yield* check(
          (yield* paused.refresh()).status === BotTaskStatus.CANCELLED,
          "Reconnection resurrected a cancelled task",
        );
        const afterCancel = yield* bot.tasks.goTo(goal, {
          conflictPolicy: BotTaskConflictPolicy.REJECT,
        });
        yield* afterCancel.result({ call: { timeoutMs: 5000 } });
      }),
    ),
} satisfies E2ETest;
