import { goals } from "@soulfiremc/sdk";
import {
  BotLiveService,
  type PathfindProgress,
  PathfindStatus,
} from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import {
  BotTaskConflictPolicy,
  BotTaskService,
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
  serverPosition,
} from "../harness.ts";

export default {
  name: "pathfinding-stream",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
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
        const service = client.service(BotLiveService);
        const taskService = client.service(BotTaskService);
        for (const name of [
          "invalid-options",
          "already-at-goal",
          "traverse",
          "abort-first-frame",
          "abort-moving",
          "stop-moving",
        ]) {
          yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
          const nearby = name === "invalid-options" || name === "already-at-goal";
          const frames = yield* Effect.tryPromise({
            try: async (signal) => {
              const controller = new AbortController();
              const frames: PathfindProgress[] = [];
              let cancelled = false;
              try {
                for await (const frame of service.goTo(
                  {
                    instanceId: bot.instanceId,
                    botId: bot.id,
                    goal: goals.block({ x: nearby ? 4262 : 4276, y: 64, z: 8 }),
                    ...(name === "invalid-options"
                      ? { options: { minimumY: 70, maximumY: 60 } }
                      : {}),
                  },
                  {
                    signal: AbortSignal.any([
                      signal,
                      controller.signal,
                      AbortSignal.timeout(15000),
                    ]),
                  },
                )) {
                  frames.push(frame);
                  const moving = (frame.position?.x ?? 0) > 4264;
                  if (name === "abort-first-frame" || (name === "abort-moving" && moving)) {
                    cancelled = true;
                    controller.abort();
                  } else if (name === "stop-moving" && moving && !cancelled) {
                    cancelled = true;
                    await service.stopPathfinding({ instanceId: bot.instanceId, botId: bot.id });
                  }
                }
              } catch (error) {
                if (!controller.signal.aborted) throw error;
              }
              return frames;
            },
            catch: (error) => new CheckFailed({ message: `${name}: ${String(error)}` }),
          });
          const first = frames[0];
          yield* check(!!first?.actionId, `${name} did not identify its task`);
          yield* check(
            first?.position !== undefined && first.distanceRemaining !== undefined,
            `${name} omitted the initial position or remaining distance`,
          );
          const taskId = first!.actionId;
          const getTask = Effect.tryPromise({
            try: () => taskService.getBotTask({ taskId }),
            catch: (error) => new CheckFailed({ message: String(error) }),
          });
          if (name === "invalid-options") {
            const task = yield* getTask;
            yield* check(
              task.status === BotTaskStatus.FAILED &&
                first?.status === PathfindStatus.FAILED &&
                !!task.failure?.message &&
                first.error === task.failure.message,
              "The initial failure frame lost the provider failure details",
            );
          } else if (nearby || name === "traverse") {
            const task = yield* getTask;
            yield* check(
              task.status === BotTaskStatus.COMPLETED &&
                frames.at(-1)?.status === PathfindStatus.COMPLETED,
              `${name} did not finish both the task and stream`,
            );
            const position = yield* serverPosition;
            yield* check(
              Math.abs(position.x - (nearby ? 4262.5 : 4276.5)) < 0.75,
              `${name} completed before reaching the goal`,
            );
            if (!nearby) {
              yield* check(
                frames.some(
                  (frame) => (frame.distanceRemaining ?? 100) < first!.distanceRemaining!,
                ),
                "Traversal never reported progress toward the goal",
              );
            }
          } else {
            yield* eventually(
              getTask.pipe(
                Effect.tap((task) =>
                  check(task.status === BotTaskStatus.CANCELLED, `${name} left its task active`),
                ),
              ),
              "5 seconds",
              "50 millis",
            );
            if (name === "stop-moving") {
              yield* check(
                frames.at(-1)?.status === PathfindStatus.CANCELLED,
                "Stopping pathfinding did not close the stream with cancellation",
              );
            }
            yield* Effect.sleep("400 millis");
            const stopped = yield* serverPosition;
            yield* Effect.sleep("600 millis");
            const later = yield* serverPosition;
            yield* check(
              Math.hypot(stopped.x - later.x, stopped.z - later.z) < 0.05,
              `${name} kept moving after cancellation`,
            );
            // REJECT exposes leaked control ownership instead of replacing it.
            const sentinel = yield* bot.tasks.goTo(
              goals.block({ x: Math.floor(later.x), y: 64, z: Math.floor(later.z) }),
              { conflictPolicy: BotTaskConflictPolicy.REJECT },
            );
            yield* Effect.addFinalizer(() => sentinel.cancel("test cleanup").pipe(Effect.ignore));
            const terminal = yield* sentinel.wait({ call: { timeoutMs: 5000 } });
            yield* check(
              terminal.status === BotTaskStatus.COMPLETED,
              `${name} did not release movement control`,
            );
          }
        }
      }),
    ),
} satisfies E2ETest;
