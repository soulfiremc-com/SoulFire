import { create, createRegistry } from "@bufbuild/protobuf";
import { anyPack } from "@bufbuild/protobuf/wkt";
import { Code, ConnectError, createRouterTransport } from "@connectrpc/connect";
import * as HttpClient from "effect/http/HttpClient";
import * as HttpClientResponse from "effect/http/HttpClientResponse";
import { Effect, Stream } from "effect";
import { describe, expect, it, vi } from "vitest";

import { examplePlugin } from "../src/example-plugin.js";
import {
  BossBarEventKind,
  BotActionStatus,
  BotEventSchema,
  BotLiveService,
  EntityEventKind,
  PlayerListEventKind,
  ResourcePackEventKind,
  ScoreboardEventKind,
  WeatherEventKind,
} from "../src/generated/soulfire/bot_live_pb.js";
import { BotLiveStateSchema } from "../src/generated/soulfire/bot_pb.js";
import { UserRole } from "../src/generated/soulfire/common_pb.js";
import { PathfinderService, PathPlanStatus } from "../src/generated/soulfire/pathfinding_pb.js";
import {
  ExamplePluginService,
  file_soulfire_plugin_example_v1_example,
} from "../src/generated/soulfire/plugin/example/v1/example_pb.js";
import { PluginApiDescriptorSchema } from "../src/generated/soulfire/plugin_api_pb.js";
import {
  SdkService,
  SdkTransport,
  type SdkHandshakeRequest,
} from "../src/generated/soulfire/sdk_pb.js";
import {
  BotTaskEventSchema,
  BotTaskFailureSchema,
  BotTaskSchema,
  BotTaskService,
  BotTaskStatus,
  GoToTaskResultSchema,
  GoToTaskSchema,
} from "../src/generated/soulfire/task_pb.js";
import { SoulFire } from "../src/index.js";
import { resolveRelease } from "../src/local-server.js";
import { goals } from "../src/pathfinding.js";
import { makeEffectHttpClientFetch } from "../src/platform.js";
import { ReflectivePlugin } from "../src/plugins.js";
import { emptyBotSessionState, reduceBotSessionState } from "../src/session.js";

describe("Soulfire", () => {
  it("creates a scoped bot through the public connection hierarchy", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com/",
            token: "token",
          });
          const bot = soulfire.instance("instance-id").bot("bot-id");
          expect(bot.instanceId).toBe("instance-id");
          expect(bot.id).toBe("bot-id");
          expect(soulfire.localServer).toBeUndefined();
          yield* soulfire.close();
        }),
      ),
    ));

  it("negotiates capabilities before returning a ready Promise client", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let request: SdkHandshakeRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(SdkService, {
              handshake(value) {
                request = value;
                return handshakeResponse();
              },
            });
            registerExamplePlugin(service);
          });
          const soulfire = yield* SoulFire.connect({
            baseUrl: "https://soulfire.example.com",
            requiredCapabilities: ["plugin.rpc.v1"],
            requiredPlugins: [{ pluginId: "example", versionRange: "^1.0.0" }],
            transport,
          });
          expect(request).toMatchObject({
            sdkName: "@soulfiremc/sdk",
            minimumApiVersion: { major: 1, minor: 0, patch: 0 },
            maximumApiVersion: { major: 1, minor: 0, patch: 0 },
            requiredCapabilities: ["plugin.rpc.v1"],
            requiredPlugins: [
              {
                pluginId: "example",
                versionRange: "^1.0.0",
              },
            ],
          });
          expect(soulfire.server.id).toBe("server-id");
          expect(soulfire.capabilities.supports("plugin.rpc.v1")).toBe(true);
          expect(soulfire.plugins.requireDescriptor("example").apiMajorVersion).toBe(1);
          const example = yield* soulfire.plugins.require(examplePlugin);
          expect((yield* example.echo("instance-id", "hello")).message).toBe("hello");
          yield* soulfire.close();
        }),
      ),
    ));

  it("preserves structured RPC diagnostics in the Effect error channel", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              sendChat() {
                throw new ConnectError(
                  "temporarily unavailable",
                  Code.Unavailable,
                  new Headers({ "x-request-id": "request-42" }),
                );
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const error = yield* Effect.flip(
            soulfire.instance("instance-id").bot("bot-id").sendChat("hello"),
          );
          expect(error).toMatchObject({
            _tag: "SoulFireRpcError",
            operation: "SoulFireBot.sendChat",
            code: Code.Unavailable,
            requestId: "request-42",
            retryable: true,
          });
          expect(error.message).toBe("[unavailable] temporarily unavailable");
          yield* soulfire.close();
        }),
      ),
    ));

  it("preserves task failure messages in the Effect error channel", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(BotTaskService, {
              startBotTask(request) {
                return create(BotTaskSchema, {
                  taskId: "task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
              async *watchBotTask() {
                yield create(BotTaskEventSchema, {
                  task: create(BotTaskSchema, {
                    taskId: "task-id",
                    instanceId: "instance-id",
                    botId: "bot-id",
                    status: BotTaskStatus.FAILED,
                    revision: 2n,
                    failure: create(BotTaskFailureSchema, {
                      code: "INVALID_ARGUMENT",
                      message: "radius must not exceed 128.0",
                      retryable: false,
                    }),
                  }),
                });
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const task = yield* soulfire
            .instance("instance-id")
            .bot("bot-id")
            .tasks.start(GoToTaskSchema, {}, GoToTaskResultSchema);
          const error = yield* Effect.flip(task.result());
          expect(error).toMatchObject({
            _tag: "SoulFireTaskFailed",
            message: "radius must not exceed 128.0",
            task: {
              taskId: "task-id",
              status: BotTaskStatus.FAILED,
              failure: {
                code: "INVALID_ARGUMENT",
                message: "radius must not exceed 128.0",
              },
            },
          });
          yield* soulfire.close();
        }),
      ),
    ));

  it("exposes scoped Effect connection ergonomics", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(SdkService, {
              handshake() {
                return handshakeResponse();
              },
            });
            registerExamplePlugin(service);
            service(BotLiveService, {
              sendChat() {
                return {
                  result: {
                    actionId: "00000000-0000-0000-0000-000000000002",
                    status: BotActionStatus.COMPLETED,
                  },
                };
              },
            });
            service(BotTaskService, {
              startBotTask(request) {
                return create(BotTaskSchema, {
                  taskId: "task-id",
                  instanceId: request.instanceId,
                  botId: request.botId,
                  status: BotTaskStatus.RUNNING,
                  revision: 1n,
                });
              },
              async *watchBotTask() {
                yield create(BotTaskEventSchema, {
                  task: create(BotTaskSchema, {
                    taskId: "task-id",
                    instanceId: "instance-id",
                    botId: "bot-id",
                    status: BotTaskStatus.COMPLETED,
                    revision: 2n,
                    result: anyPack(GoToTaskResultSchema, create(GoToTaskResultSchema)),
                  }),
                });
              },
            });
            service(PathfinderService, {
              planPath() {
                return {
                  plan: {
                    status: PathPlanStatus.COMPLETE,
                    start: {
                      x: 0,
                      y: 64,
                      z: 0,
                      dimension: "minecraft:overworld",
                    },
                    steps: [],
                    blocksToBreak: [],
                    blocksToPlace: [],
                    maximumTicks: 0n,
                    partialReason: undefined,
                  },
                };
              },
            });
          });
          const result = yield* Effect.scoped(
            Effect.gen(function* () {
              const soulfire = yield* SoulFire.connect({
                baseUrl: "https://soulfire.example.com",
                transport,
              });
              const action = yield* soulfire
                .instance("instance-id")
                .bot("bot-id")
                .sendChat("hello");
              const example = yield* soulfire.plugins.require(examplePlugin);
              const echoed = yield* example.echo("instance-id", "effect");
              const task = yield* soulfire
                .instance("instance-id")
                .bot("bot-id")
                .tasks.start(GoToTaskSchema, {}, GoToTaskResultSchema);
              const taskResult = yield* task.result();
              const plan = yield* soulfire
                .instance("instance-id")
                .bot("bot-id")
                .pathfinder.plan(goals.block({ x: 4, y: 64, z: -2 }));
              const ticks = yield* Stream.runCollect(example.watchTicks("instance-id", 3));
              return {
                actionId: action.actionId,
                echoed: echoed.message,
                ticks: [...ticks].map((tick) => tick.sequence),
                taskResultType: taskResult.$typeName,
                pathStatus: plan.status,
                serverId: soulfire.server.id,
                hasPluginRpc: soulfire.capabilities.supports("plugin.rpc.v1"),
              };
            }),
          );
          expect(result).toEqual({
            actionId: "00000000-0000-0000-0000-000000000002",
            echoed: "effect",
            serverId: "server-id",
            hasPluginRpc: true,
            ticks: [1, 2, 3],
            taskResultType: "soulfire.v1.GoToTaskResult",
            pathStatus: PathPlanStatus.COMPLETE,
          });
        }),
      ),
    ));

  it("invokes unknown plugin unary and streaming methods reflectively", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            registerExamplePlugin(service);
          });
          const plugin = new ReflectivePlugin(
            create(PluginApiDescriptorSchema, {
              pluginId: "example",
              pluginVersion: "1.0.0",
              services: [
                {
                  name: "ExamplePluginService",
                  fullName: "soulfire.plugin.example.v1.ExamplePluginService",
                },
              ],
            }),
            createRegistry(file_soulfire_plugin_example_v1_example),
            transport,
          );
          const response = yield* plugin.call(
            "soulfire.plugin.example.v1.ExamplePluginService",
            "Echo",
            { instanceId: "instance-id", message: "reflective" },
          );
          const ticks = Array.from(
            yield* Stream.runCollect(
              Stream.map(
                plugin.stream("soulfire.plugin.example.v1.ExamplePluginService", "WatchTicks", {
                  instanceId: "instance-id",
                  count: 3,
                }),
                (tick) => tick.json,
              ),
            ),
          );
          expect(response).toMatchObject({
            typeName: "soulfire.plugin.example.v1.EchoResponse",
            json: { message: "reflective" },
          });
          expect(ticks).toEqual([{ sequence: 1 }, { sequence: 2 }, { sequence: 3 }]);
        }),
      ),
    ));

  it("adapts an Effect Platform HTTP client to ConnectRPC fetch", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const requests: string[] = [];
          const httpClient = HttpClient.make((request, url) => {
            requests.push(`${request.method} ${url.pathname}`);
            return Effect.succeed(
              HttpClientResponse.fromWeb(
                request,
                new Response("ok", {
                  headers: { "X-Test": "effect-platform" },
                  status: 201,
                }),
              ),
            );
          });
          const fetch = makeEffectHttpClientFetch(httpClient);
          const response = yield* Effect.promise(() =>
            fetch("https://soulfire.example.com/soulfire.v1.SdkService/Handshake", {
              body: new Uint8Array([1, 2, 3]),
              method: "POST",
            }),
          );
          expect(requests).toEqual(["POST /soulfire.v1.SdkService/Handshake"]);
          expect(response.status).toBe(201);
          expect(response.headers.get("X-Test")).toBe("effect-platform");
          expect(yield* Effect.promise(() => response.text())).toBe("ok");
        }),
      ),
    ));

  it("maintains synchronized session state from snapshots and deltas", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let releaseDelta: (() => void) | undefined;
          const deltaGate = new Promise<void>((resolve) => {
            releaseDelta = resolve;
          });
          const transport = createRouterTransport(({ service }) => {
            service(SdkService, {
              handshake() {
                return handshakeResponse();
              },
            });
            service(BotLiveService, {
              async *watchBotEvents() {
                yield create(BotEventSchema, {
                  envelope: {
                    botId: "bot-id",
                    sequence: 1n,
                    snapshotRevision: 1n,
                    streamEpoch: "00000000-0000-0000-0000-000000000001",
                  },
                  event: {
                    case: "snapshot",
                    value: create(BotLiveStateSchema, {
                      health: 20,
                      maxHealth: 20,
                      x: 1,
                      y: 64,
                      z: 2,
                    }),
                  },
                });
                await deltaGate;
                yield create(BotEventSchema, {
                  envelope: {
                    botId: "bot-id",
                    sequence: 2n,
                    snapshotRevision: 1n,
                    streamEpoch: "00000000-0000-0000-0000-000000000001",
                  },
                  event: {
                    case: "stateDelta",
                    value: {
                      health: 14,
                      x: 3,
                    },
                  },
                });
                await new Promise(() => undefined);
              },
            });
          });
          const soulfire = yield* SoulFire.connect({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const session = yield* soulfire.instance("instance-id").bot("bot-id").observe();
          expect(session.state.player?.health).toBe(20);
          const delta = session.once("stateDelta");
          releaseDelta?.();
          yield* delta;
          expect(session.state.player).toMatchObject({
            health: 14,
            maxHealth: 20,
            x: 3,
            y: 64,
            z: 2,
          });
          expect(session.state.sequence).toBe(2n);
          yield* session.close();
          yield* soulfire.close();
        }),
      ),
    ));

  it("indexes semantic entity and block snapshots from live events", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let releaseWorldEvents: (() => void) | undefined;
          const worldEventGate = new Promise<void>((resolve) => {
            releaseWorldEvents = resolve;
          });
          const transport = createRouterTransport(({ service }) => {
            service(SdkService, {
              handshake() {
                return handshakeResponse();
              },
            });
            service(BotLiveService, {
              async *watchBotEvents() {
                yield create(BotEventSchema, {
                  envelope: {
                    botId: "bot-id",
                    sequence: 1n,
                    snapshotRevision: 1n,
                    streamEpoch: "00000000-0000-0000-0000-000000000001",
                  },
                  event: {
                    case: "snapshot",
                    value: create(BotLiveStateSchema, {}),
                  },
                });
                await worldEventGate;
                yield create(BotEventSchema, {
                  envelope: {
                    botId: "bot-id",
                    sequence: 2n,
                    snapshotRevision: 2n,
                    streamEpoch: "00000000-0000-0000-0000-000000000001",
                  },
                  event: {
                    case: "entityEvent",
                    value: {
                      kind: EntityEventKind.ENTITY_EVENT_SPAWN,
                      entity: {
                        entityId: 42,
                        entityType: "minecraft:zombie",
                        position: { x: 2, y: 64, z: 3 },
                      },
                      snapshot: {
                        reference: {
                          connectionEpoch: "00000000-0000-0000-0000-000000000002",
                          networkId: 42,
                        },
                        entityType: "minecraft:zombie",
                        health: 20,
                      },
                    },
                  },
                });
                yield create(BotEventSchema, {
                  envelope: {
                    botId: "bot-id",
                    sequence: 3n,
                    snapshotRevision: 3n,
                    streamEpoch: "00000000-0000-0000-0000-000000000001",
                  },
                  event: {
                    case: "blockUpdate",
                    value: {
                      position: {
                        dimension: "minecraft:overworld",
                        x: 1,
                        y: 64,
                        z: 2,
                      },
                      oldBlockId: "minecraft:stone",
                      newBlockId: "minecraft:oak_log",
                      block: {
                        blockId: "minecraft:oak_log",
                        properties: { axis: "y" },
                      },
                    },
                  },
                });
                await new Promise(() => undefined);
              },
            });
          });
          const soulfire = yield* SoulFire.connect({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const session = yield* soulfire.instance("instance-id").bot("bot-id").observe();
          const blockUpdate = session.once("blockUpdate");
          releaseWorldEvents?.();
          yield* blockUpdate;
          expect(session.state.entitySnapshots.get(42)).toMatchObject({
            entityType: "minecraft:zombie",
            health: 20,
          });
          expect(session.state.blockSnapshots.get("minecraft:overworld:1:64:2")).toMatchObject({
            blockId: "minecraft:oak_log",
            properties: { axis: "y" },
          });
          yield* session.close();
          yield* soulfire.close();
        }),
      ),
    ));

  it("reduces environment, social, boss-bar, and scoreboard state", () => {
    let state = emptyBotSessionState();
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "environment",
          value: {
            change: {
              case: "weather",
              value: {
                kind: WeatherEventKind.WEATHER_EVENT_STARTED_RAINING,
              },
            },
          },
        },
      }),
    );
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "playerList",
          value: {
            kind: PlayerListEventKind.PLAYER_LIST_EVENT_UPSERT,
            entries: [
              {
                changedFields: ["add_player"],
                latencyMs: 42,
                profileId: "00000000-0000-0000-0000-000000000042",
                profileName: "Alex",
              },
            ],
          },
        },
      }),
    );
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "bossBar",
          value: {
            bossBarId: "00000000-0000-0000-0000-000000000043",
            kind: BossBarEventKind.BOSS_BAR_EVENT_ADD,
            name: { plainText: "Raid" },
            progress: 0.75,
          },
        },
      }),
    );
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "scoreboard",
          value: {
            displayName: { plainText: "Kills" },
            kind: ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_ADD,
            objectiveName: "kills",
            renderType: "integer",
          },
        },
      }),
    );
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "resourcePack",
          value: {
            kind: ResourcePackEventKind.RESOURCE_PACK_EVENT_OFFERED,
            packId: "00000000-0000-0000-0000-000000000044",
            required: true,
            url: "https://example.com/pack.zip",
          },
        },
      }),
    );
    state = reduceBotSessionState(
      state,
      create(BotEventSchema, {
        event: {
          case: "scoreboard",
          value: {
            kind: ScoreboardEventKind.SCOREBOARD_EVENT_SCORE_SET,
            objectiveName: "kills",
            owner: "Alex",
            score: 7,
          },
        },
      }),
    );

    expect(state.environment.raining).toBe(true);
    expect(state.playerList.get("00000000-0000-0000-0000-000000000042")).toMatchObject({
      latencyMs: 42,
      profileName: "Alex",
    });
    expect(state.bossBars.get("00000000-0000-0000-0000-000000000043")).toMatchObject({
      name: { plainText: "Raid" },
      progress: 0.75,
    });
    expect(state.scoreboard.objectives.get("kills")).toMatchObject({
      displayName: { plainText: "Kills" },
      renderType: "integer",
    });
    expect(state.scoreboard.scores.get("kills\u0000Alex")).toMatchObject({
      owner: "Alex",
      score: 7,
    });
    expect(state.resourcePacks.get("00000000-0000-0000-0000-000000000044")).toMatchObject({
      required: true,
      url: "https://example.com/pack.zip",
    });
  });
});

function handshakeResponse() {
  return {
    serverId: "server-id",
    soulfireVersion: "3.0.0",
    commitHash: "commit",
    branchName: "main",
    apiVersion: { major: 1, minor: 0, patch: 0 },
    nativeMinecraftVersion: "1.21.11",
    supportedMinecraftVersions: ["1.21.11"],
    transports: [SdkTransport.GRPC_WEB],
    capabilities: [{ id: "plugin.rpc.v1", revision: 1 }],
    plugins: [
      {
        pluginId: "example",
        pluginVersion: "1.0.0",
        apiMajorVersion: 1,
        services: [
          {
            fullName: "soulfire.plugin.example.v1.ExamplePluginService",
            methods: [],
            name: "ExamplePluginService",
          },
        ],
        permissions: [],
        eventTypeUrls: [],
        taskTypeUrls: [],
        taskTypes: [],
      },
    ],
    limits: [{ id: "grpc.request_bytes", value: 1024n }],
    identity: {
      id: "00000000-0000-0000-0000-000000000001",
      username: "developer",
      email: "dev@example.com",
      role: UserRole.ADMIN,
      grantedGlobalPermissions: ["READ_CLIENT_DATA"],
    },
  };
}

function registerExamplePlugin(
  service: Parameters<Parameters<typeof createRouterTransport>[0]>[0]["service"],
): void {
  service(ExamplePluginService, {
    echo(request) {
      return { message: request.message };
    },
    async *watchTicks(request) {
      for (let sequence = 1; sequence <= request.count; sequence += 1) {
        yield { sequence };
      }
    },
  });
}

describe("release resolution", () => {
  it("uses the latest official Soulfire release by default", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const fetch = vi.fn(async () =>
            Response.json({
              assets: [],
              tag_name: "2.9.1",
            }),
          );
          const release = yield* Effect.promise(() => resolveRelease(undefined, fetch));
          expect(release.tag_name).toBe("2.9.1");
          expect(fetch).toHaveBeenCalledWith(
            "https://api.github.com/repos/soulfiremc-com/SoulFire/releases/latest",
            expect.objectContaining({
              headers: expect.objectContaining({
                "User-Agent": "@soulfiremc/sdk",
              }),
            }),
          );
        }),
      ),
    ));

  it("escapes an explicitly requested release tag", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const fetch = vi.fn(async () =>
            Response.json({
              assets: [],
              tag_name: "release/candidate",
            }),
          );
          yield* Effect.promise(() => resolveRelease("release/candidate", fetch));
          expect(fetch).toHaveBeenCalledWith(
            "https://api.github.com/repos/soulfiremc-com/SoulFire/releases/tags/release%2Fcandidate",
            expect.anything(),
          );
        }),
      ),
    ));
});
