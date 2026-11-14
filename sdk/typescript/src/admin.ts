import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import { createClient, type CallOptions, type Client, type Transport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { type SoulFireOperationError } from "./errors.js";
import { rpc, rpcStream, withSignal } from "./transport.js";

import type { Timestamp } from "@bufbuild/protobuf/wkt";
import { ClientService, type ClientDataResponse } from "./generated/soulfire/client_pb.js";
import {
  CommandService,
  type CommandCompletionRequestSchema,
  type CommandCompletionResponse,
  type CommandRequestSchema,
  type CommandResponse,
} from "./generated/soulfire/command_pb.js";
import {
  DownloadService,
  type DownloadRequestSchema,
  type DownloadResponse,
} from "./generated/soulfire/download_pb.js";
import {
  InstanceService,
  type InstanceAuditLogResponse_AuditLogEntry,
} from "./generated/soulfire/instance_pb.js";
import {
  LogsService,
  type LogRequestSchema,
  type LogResponse,
  type LogString,
  type PreviousLogRequestSchema,
} from "./generated/soulfire/logs_pb.js";
import {
  MetricsService,
  type GetInstanceMetricsResponse,
  type GetServerMetricsResponse,
} from "./generated/soulfire/metrics_pb.js";
import {
  PluginStatsService,
  type PluginRuntimeStat,
} from "./generated/soulfire/plugin_stats_pb.js";
import {
  ScriptService,
  type ActivateScriptRequestSchema,
  type CreateScriptRequestSchema,
  type CreateScriptResponse,
  type DryRunScriptRequestSchema,
  type GetNodeTypesRequestSchema,
  type GetNodeTypesResponse,
  type GetRegistryDataRequestSchema,
  type GetRegistryDataResponse,
  type GetScriptResponse,
  type GetScriptStatusResponse,
  type ScriptEvent,
  type ScriptInfo,
  type ScriptLogEntry,
  type SubscribeScriptLogsRequestSchema,
  type UpdateScriptRequestSchema,
  type UpdateScriptResponse,
  type ValidateScriptRequestSchema,
  type ValidateScriptResponse,
} from "./generated/soulfire/script_pb.js";
import {
  ServerService,
  type ServerConfig,
  type ServerInfoResponse,
  type ServerUpdateConfigEntryRequestSchema,
} from "./generated/soulfire/server_pb.js";
import {
  UserService,
  type DeleteUserPluginPermissionGrantRequestSchema,
  type SetUserPluginPermissionGrantRequestSchema,
  type UpdateUserRequestSchema,
  type UserCreateRequestSchema,
  type UserInfoResponse,
  type UserListResponse_User,
  type UserPluginPermissionGrant,
} from "./generated/soulfire/user_pb.js";

type Input<T extends DescMessage> = Omit<MessageInitShape<T>, "$typeName">;
type InstanceInput<T extends DescMessage> = Omit<Input<T>, "instanceId">;
type UserInput<T extends DescMessage> = Omit<Input<T>, "userId">;

/**
 * High-level access to Soulfire's administrative control plane.
 *
 * Generated service clients remain available through `soulfire.service()` for
 * new or uncommon fields.
 */
export class SoulFireAdmin {
  readonly #client: Client<typeof ClientService>;
  readonly #server: Client<typeof ServerService>;
  readonly #users: Client<typeof UserService>;
  readonly #logs: Client<typeof LogsService>;
  readonly #metrics: Client<typeof MetricsService>;
  readonly #commands: Client<typeof CommandService>;
  readonly #downloads: Client<typeof DownloadService>;
  readonly #pluginStats: Client<typeof PluginStatsService>;
  readonly #scripts: Client<typeof ScriptService>;
  readonly #instances: Client<typeof InstanceService>;

  public constructor(transport: Transport) {
    this.#client = createClient(ClientService, transport);
    this.#server = createClient(ServerService, transport);
    this.#users = createClient(UserService, transport);
    this.#logs = createClient(LogsService, transport);
    this.#metrics = createClient(MetricsService, transport);
    this.#commands = createClient(CommandService, transport);
    this.#downloads = createClient(DownloadService, transport);
    this.#pluginStats = createClient(PluginStatsService, transport);
    this.#scripts = createClient(ScriptService, transport);
    this.#instances = createClient(InstanceService, transport);
  }

  public clientData(
    options?: CallOptions,
  ): Effect.Effect<ClientDataResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.clientData", (signal) =>
      this.#client.getClientData({}, withSignal(options, signal)),
    );
  }

  public generateWebDavToken(options?: CallOptions): Effect.Effect<string, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.generateWebDavToken", (signal) =>
        this.#client.generateWebDAVToken({}, withSignal(options, signal)),
      )).token;
    });
  }

  public generateApiToken(options?: CallOptions): Effect.Effect<string, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.generateApiToken", (signal) =>
        this.#client.generateAPIToken({}, withSignal(options, signal)),
      )).token;
    });
  }

  public updateUsername(
    username: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.updateUsername", (signal) =>
        this.#client.updateSelfUsername({ username }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public updateEmail(
    email: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.updateEmail", (signal) =>
        this.#client.updateSelfEmail({ email }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public invalidateOwnSessions(options?: CallOptions): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.invalidateOwnSessions", (signal) =>
        this.#client.invalidateSelfSessions({}, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public serverInfo(
    options?: CallOptions,
  ): Effect.Effect<ServerInfoResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.serverInfo", (signal) =>
      this.#server.getServerInfo({}, withSignal(options, signal)),
    );
  }

  public updateServerConfig(
    config: ServerConfig,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.updateServerConfig", (signal) =>
        this.#server.updateServerConfig({ config }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public setServerConfigEntry(
    request: Input<typeof ServerUpdateConfigEntryRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.setServerConfigEntry", (signal) =>
        this.#server.updateServerConfigEntry(request, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public users(
    options?: CallOptions,
  ): Effect.Effect<UserListResponse_User[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.users", (signal) =>
        this.#users.listUsers({}, withSignal(options, signal)),
      )).users;
    });
  }

  public user(
    userId: string,
    options?: CallOptions,
  ): Effect.Effect<UserInfoResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.user", (signal) =>
      this.#users.getUserInfo({ id: userId }, withSignal(options, signal)),
    );
  }

  public createUser(
    request: Input<typeof UserCreateRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<string, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.createUser", (signal) =>
        this.#users.createUser(request, withSignal(options, signal)),
      )).id;
    });
  }

  public deleteUser(
    userId: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.deleteUser", (signal) =>
        this.#users.deleteUser({ id: userId }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public updateUser(
    request: Input<typeof UpdateUserRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.updateUser", (signal) =>
        this.#users.updateUser(request, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public invalidateUserSessions(
    userId: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.invalidateUserSessions", (signal) =>
        this.#users.invalidateSessions({ id: userId }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public generateUserApiToken(
    userId: string,
    options?: CallOptions,
  ): Effect.Effect<string, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.generateUserApiToken", (signal) =>
        this.#users.generateUserAPIToken({ id: userId }, withSignal(options, signal)),
      )).token;
    });
  }

  public userPluginPermissionGrants(
    userId: string,
    options?: CallOptions,
  ): Effect.Effect<UserPluginPermissionGrant[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.userPluginPermissionGrants", (signal) =>
        this.#users.listUserPluginPermissionGrants({ userId }, withSignal(options, signal)),
      )).grants;
    });
  }

  public setUserPluginPermissionGrant(
    userId: string,
    request: UserInput<typeof SetUserPluginPermissionGrantRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<UserPluginPermissionGrant, SoulFireOperationError> {
    return rpc("SoulFireAdmin.setUserPluginPermissionGrant", (signal) =>
      this.#users.setUserPluginPermissionGrant({ ...request, userId }, withSignal(options, signal)),
    );
  }

  public deleteUserPluginPermissionGrant(
    userId: string,
    request: UserInput<typeof DeleteUserPluginPermissionGrantRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.deleteUserPluginPermissionGrant", (signal) =>
        this.#users.deleteUserPluginPermissionGrant(
          { ...request, userId },
          withSignal(options, signal),
        ),
      ),
      () => undefined,
    );
  }

  public previousLogs(
    request: Input<typeof PreviousLogRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<LogString[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.previousLogs", (signal) =>
        this.#logs.getPrevious(request, withSignal(options, signal)),
      )).messages;
    });
  }

  public logs(
    request: Input<typeof LogRequestSchema>,
    options?: CallOptions,
  ): Stream.Stream<LogResponse, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        return rpcStream("SoulFireAdmin.logs", (signal) =>
          this.#logs.subscribe(request, withSignal(options, signal)),
        );
      }),
    );
  }

  public serverMetrics(
    since?: Timestamp,
    options?: CallOptions,
  ): Effect.Effect<GetServerMetricsResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.serverMetrics", (signal) =>
      this.#metrics.getServerMetrics(
        since === undefined ? {} : { since },
        withSignal(options, signal),
      ),
    );
  }

  public instanceMetrics(
    instanceId: string,
    since?: Timestamp,
    options?: CallOptions,
  ): Effect.Effect<GetInstanceMetricsResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.instanceMetrics", (signal) =>
      this.#metrics.getInstanceMetrics(
        { instanceId, ...(since === undefined ? {} : { since }) },
        withSignal(options, signal),
      ),
    );
  }

  public executeCommand(
    request: Input<typeof CommandRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<CommandResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.executeCommand", (signal) =>
      this.#commands.executeCommand(request, withSignal(options, signal)),
    );
  }

  public completeCommand(
    request: Input<typeof CommandCompletionRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<CommandCompletionResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.completeCommand", (signal) =>
      this.#commands.tabCompleteCommand(request, withSignal(options, signal)),
    );
  }

  public download(
    request: Input<typeof DownloadRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<DownloadResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.download", (signal) =>
      this.#downloads.download(request, withSignal(options, signal)),
    );
  }

  public pluginStats(
    instanceId: string,
    options?: CallOptions,
  ): Effect.Effect<PluginRuntimeStat[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.pluginStats", (signal) =>
        this.#pluginStats.getInstancePluginStats({ instanceId }, withSignal(options, signal)),
      )).stats;
    });
  }

  public auditLog(
    instanceId: string,
    options?: CallOptions,
  ): Effect.Effect<InstanceAuditLogResponse_AuditLogEntry[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.auditLog", (signal) =>
        this.#instances.getAuditLog({ id: instanceId }, withSignal(options, signal)),
      )).entry;
    });
  }

  public scripts(
    instanceId: string,
    options?: CallOptions,
  ): Effect.Effect<ScriptInfo[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return (yield* rpc("SoulFireAdmin.scripts", (signal) =>
        this.#scripts.listScripts({ instanceId }, withSignal(options, signal)),
      )).scripts;
    });
  }

  public script(
    instanceId: string,
    scriptId: string,
    options?: CallOptions,
  ): Effect.Effect<GetScriptResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.script", (signal) =>
      this.#scripts.getScript({ instanceId, scriptId }, withSignal(options, signal)),
    );
  }

  public createScript(
    instanceId: string,
    request: InstanceInput<typeof CreateScriptRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<CreateScriptResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.createScript", (signal) =>
      this.#scripts.createScript({ ...request, instanceId }, withSignal(options, signal)),
    );
  }

  public updateScript(
    instanceId: string,
    request: InstanceInput<typeof UpdateScriptRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<UpdateScriptResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.updateScript", (signal) =>
      this.#scripts.updateScript({ ...request, instanceId }, withSignal(options, signal)),
    );
  }

  public deleteScript(
    instanceId: string,
    scriptId: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.deleteScript", (signal) =>
        this.#scripts.deleteScript({ instanceId, scriptId }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public activateScript(
    instanceId: string,
    request: InstanceInput<typeof ActivateScriptRequestSchema>,
    options?: CallOptions,
  ): Stream.Stream<ScriptEvent, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        return rpcStream("SoulFireAdmin.activateScript", (signal) =>
          this.#scripts.activateScript({ ...request, instanceId }, withSignal(options, signal)),
        );
      }),
    );
  }

  public deactivateScript(
    instanceId: string,
    scriptId: string,
    options?: CallOptions,
  ): Effect.Effect<void, SoulFireOperationError> {
    return Effect.map(
      rpc("SoulFireAdmin.deactivateScript", (signal) =>
        this.#scripts.deactivateScript({ instanceId, scriptId }, withSignal(options, signal)),
      ),
      () => undefined,
    );
  }

  public scriptStatus(
    instanceId: string,
    scriptId: string,
    options?: CallOptions,
  ): Effect.Effect<GetScriptStatusResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.scriptStatus", (signal) =>
      this.#scripts.getScriptStatus({ instanceId, scriptId }, withSignal(options, signal)),
    );
  }

  public scriptLogs(
    instanceId: string,
    request: InstanceInput<typeof SubscribeScriptLogsRequestSchema>,
    options?: CallOptions,
  ): Stream.Stream<ScriptLogEntry, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        return rpcStream("SoulFireAdmin.scriptLogs", (signal) =>
          this.#scripts.subscribeScriptLogs(
            { ...request, instanceId },
            withSignal(options, signal),
          ),
        );
      }),
    );
  }

  public nodeTypes(
    request: Input<typeof GetNodeTypesRequestSchema> = {},
    options?: CallOptions,
  ): Effect.Effect<GetNodeTypesResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.nodeTypes", (signal) =>
      this.#scripts.getNodeTypes(request, withSignal(options, signal)),
    );
  }

  public scriptRegistryData(
    request: Input<typeof GetRegistryDataRequestSchema> = {},
    options?: CallOptions,
  ): Effect.Effect<GetRegistryDataResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.scriptRegistryData", (signal) =>
      this.#scripts.getRegistryData(request, withSignal(options, signal)),
    );
  }

  public validateScript(
    instanceId: string,
    request: InstanceInput<typeof ValidateScriptRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<ValidateScriptResponse, SoulFireOperationError> {
    return rpc("SoulFireAdmin.validateScript", (signal) =>
      this.#scripts.validateScript({ ...request, instanceId }, withSignal(options, signal)),
    );
  }

  public dryRunScript(
    instanceId: string,
    request: InstanceInput<typeof DryRunScriptRequestSchema>,
    options?: CallOptions,
  ): Stream.Stream<ScriptEvent, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        return rpcStream("SoulFireAdmin.dryRunScript", (signal) =>
          this.#scripts.dryRunScript({ ...request, instanceId }, withSignal(options, signal)),
        );
      }),
    );
  }
}
