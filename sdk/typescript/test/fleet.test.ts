import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { anyPack } from "@bufbuild/protobuf/wkt";
import { createRouterTransport } from "@connectrpc/connect";
import { Deferred, Effect, Fiber, Stream } from "effect";
import { describe, expect, it } from "vitest";

import {
  BotDesiredState,
  BotRuntimeState,
  BotService,
  type SetBotsDesiredStateRequest,
} from "../src/generated/soulfire/bot_pb.js";
import { MinecraftAccountProto_AccountTypeProto } from "../src/generated/soulfire/common_pb.js";
import { InstanceService } from "../src/generated/soulfire/instance_pb.js";
import {
  AutoRespawnTaskResultSchema,
  AutoRespawnTaskSchema,
  BotTaskService,
  BotTaskStatus,
} from "../src/generated/soulfire/task_pb.js";
import { SoulFire } from "../src/index.js";

describe("SoulFireFleet", () => {
  it("selects by live state and metadata, distributes work, and controls the result", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let lifecycleRequest: SetBotsDesiredStateRequest | undefined;
          const transport = fleetTransport({
            onLifecycle(request) {
              lifecycleRequest = request;
            },
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const fleet = soulfire.instance("instance-id").fleet;
          const selector = {
            online: true,
            dimensions: ["minecraft:overworld"],
            minimumHealth: 10,
            near: {
              x: 0,
              y: 64,
              z: 0,
              radius: 32,
              dimension: "minecraft:overworld",
            },
            metadata: [
              {
                namespace: "fleet",
                key: "role",
                equals: "builder",
              },
            ],
            orderBy: "health" as const,
          };
          const selected = yield* fleet.select(selector);
          const assignments = yield* fleet.distribute(["one", "two", "three"], selector);
          yield* fleet.start(selector);
          yield* soulfire.close();
          expect(selected.map(({ id }) => id)).toEqual(["healthy", "nearby"]);
          expect(assignments.map(({ bot, items }) => [bot.id, items])).toEqual([
            ["healthy", ["one", "three"]],
            ["nearby", ["two"]],
          ]);
          expect(lifecycleRequest?.botIds).toEqual(["healthy", "nearby"]);
        }),
      ),
    ));

  it("starts typed tasks with bounded concurrency and aggregates results", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let active = 0;
          let maximumActive = 0;
          const transport = fleetTransport({
            async onStartTask() {
              active++;
              maximumActive = Math.max(maximumActive, active);
              await new Promise((resolve) => {
                setTimeout(resolve, 5);
              });
              active--;
            },
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const fleet = soulfire.instance("instance-id").fleet;
          const group = yield* fleet.startTasks(
            { botIds: ["healthy", "nearby"] },
            AutoRespawnTaskSchema,
            (_bot, index) =>
              ({
                maximumRespawns: index + 1,
              }) satisfies MessageInitShape<typeof AutoRespawnTaskSchema>,
            AutoRespawnTaskResultSchema,
            { concurrency: 1 },
          );
          const events = Array.from(yield* Stream.runCollect(group.events()));
          const report = yield* group.results();
          yield* soulfire.close();
          expect(maximumActive).toBe(1);
          expect(group.size).toBe(2);
          expect(events.map(({ bot }) => bot.id)).toEqual(["healthy", "nearby"]);
          expect(report.rejected).toHaveLength(0);
          expect(report.fulfilled.map(({ value }) => value.respawns)).toEqual([1, 1]);
        }),
      ),
    ));
  it("cancels accepted tasks when fleet acquisition is interrupted", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const cancelled: string[] = [];
          const waiting = yield* Deferred.make<void>();
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport: fleetTransport({
              onCancelTask: (id) => {
                cancelled.push(id);
              },
            }),
          });
          const fiber = yield* soulfire
            .instance("instance-id")
            .fleet.startTasks(
              { botIds: ["healthy", "nearby"] },
              AutoRespawnTaskSchema,
              (_bot, index) =>
                index === 0
                  ? { maximumRespawns: 1 }
                  : Deferred.succeed(waiting, undefined).pipe(Effect.andThen(Effect.never)),
              AutoRespawnTaskResultSchema,
              { concurrency: 1 },
            )
            .pipe(Effect.forkScoped);
          yield* Deferred.await(waiting);
          yield* Fiber.interrupt(fiber);
          expect(cancelled).toEqual(["task-healthy"]);
        }),
      ),
    ));
});

function fleetTransport(options: {
  onLifecycle?: (request: SetBotsDesiredStateRequest) => void;
  onStartTask?: () => Promise<void>;
  onCancelTask?: (taskId: string) => void;
}) {
  return createRouterTransport(({ service }) => {
    service(BotService, {
      getBotList() {
        return {
          bots: [
            bot("healthy", 20, 4, 4),
            bot("nearby", 14, 8, 8),
            bot("far", 18, 96, 96),
            {
              profileId: "offline",
              isOnline: false,
              connectionPhase: 5,
              accountName: "Offline",
              status: {
                profileId: "offline",
                desiredState: BotDesiredState.STOPPED,
                runtimeState: BotRuntimeState.STOPPED,
              },
            },
          ],
        };
      },
      setBotsDesiredState(request) {
        options.onLifecycle?.(request);
        return {
          bots: request.botIds.map((profileId) => ({
            profileId,
            desiredState: request.desiredState,
            runtimeState: BotRuntimeState.STARTING,
          })),
        };
      },
    });
    service(InstanceService, {
      getInstanceInfo() {
        return {
          result: {
            case: "info",
            value: {
              config: {
                accounts: ["healthy", "nearby", "far", "offline"].map((profileId) => ({
                  profileId,
                  lastKnownName: profileId,
                  type: MinecraftAccountProto_AccountTypeProto.OFFLINE,
                  persistentMetadata: [
                    {
                      namespace: "fleet",
                      entries: [
                        {
                          key: "role",
                          value: {
                            kind: {
                              case: "stringValue",
                              value: profileId === "far" ? "scout" : "builder",
                            },
                          },
                        },
                      ],
                    },
                  ],
                })),
              },
            },
          },
        };
      },
    });
    service(BotTaskService, {
      cancelBotTask(request) {
        options.onCancelTask?.(request.taskId);
        return { taskId: request.taskId, status: BotTaskStatus.CANCELLED };
      },
      async startBotTask(request) {
        await options.onStartTask?.();
        return {
          taskId: `task-${request.botId}`,
          instanceId: request.instanceId,
          botId: request.botId,
          taskType: "soulfire.v1.AutoRespawnTask",
          status: BotTaskStatus.COMPLETED,
          revision: 1n,
          result: anyPack(
            AutoRespawnTaskResultSchema,
            create(AutoRespawnTaskResultSchema, { respawns: 1 }),
          ),
        };
      },
      async *watchBotTask(request) {
        yield {
          sequence: 1n,
          task: {
            taskId: request.taskId,
            status: BotTaskStatus.COMPLETED,
          },
        };
      },
    });
  });
}

function bot(profileId: string, health: number, x: number, z: number) {
  return {
    profileId,
    isOnline: true,
    connectionPhase: 3,
    accountName: profileId,
    status: {
      profileId,
      desiredState: BotDesiredState.STOPPED,
      runtimeState: BotRuntimeState.RUNNING,
    },
    liveState: {
      x,
      y: 64,
      z,
      health,
      maxHealth: 20,
      foodLevel: 20,
      dimension: "minecraft:overworld",
    },
  };
}
