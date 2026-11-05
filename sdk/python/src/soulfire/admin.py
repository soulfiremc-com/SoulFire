from __future__ import annotations

from effect_py import EffectGen, fn
from google.protobuf.timestamp_pb2 import Timestamp

from .client_connect import ClientServiceClient
from .client_pb2 import (
    ClientDataRequest,
    ClientDataResponse,
    GenerateAPITokenRequest,
    GenerateWebDAVTokenRequest,
    InvalidateSelfSessionsRequest,
    UpdateSelfEmailRequest,
    UpdateSelfUsernameRequest,
)
from .command_connect import CommandServiceClient
from .command_pb2 import (
    CommandCompletionRequest,
    CommandCompletionResponse,
    CommandRequest,
    CommandResponse,
)
from .download_connect import DownloadServiceClient
from .download_pb2 import DownloadRequest, DownloadResponse
from .errors import SoulFireOperationError
from .instance_connect import InstanceServiceClient
from .instance_pb2 import InstanceAuditLogRequest, InstanceAuditLogResponse
from .logs_connect import LogsServiceClient
from .logs_pb2 import LogRequest, LogResponse, LogScope, LogString, PreviousLogRequest
from .metrics_connect import MetricsServiceClient
from .metrics_pb2 import (
    GetInstanceMetricsRequest,
    GetInstanceMetricsResponse,
    GetServerMetricsRequest,
    GetServerMetricsResponse,
)
from .plugin_api_pb2 import PluginPermissionScope
from .plugin_stats_connect import PluginStatsServiceClient
from .plugin_stats_pb2 import GetInstancePluginStatsRequest, PluginRuntimeStat
from .script_connect import ScriptServiceClient
from .script_pb2 import (
    ActivateScriptRequest,
    CreateScriptRequest,
    CreateScriptResponse,
    DeactivateScriptRequest,
    DeleteScriptRequest,
    DryRunScriptRequest,
    GetNodeTypesRequest,
    GetNodeTypesResponse,
    GetRegistryDataRequest,
    GetRegistryDataResponse,
    GetScriptRequest,
    GetScriptResponse,
    GetScriptStatusRequest,
    GetScriptStatusResponse,
    ListScriptsRequest,
    ScriptEvent,
    ScriptInfo,
    ScriptLogEntry,
    SubscribeScriptLogsRequest,
    UpdateScriptRequest,
    UpdateScriptResponse,
    ValidateScriptRequest,
    ValidateScriptResponse,
)
from .server_connect import ServerServiceClient
from .server_pb2 import (
    ServerConfig,
    ServerInfoRequest,
    ServerInfoResponse,
    ServerUpdateConfigEntryRequest,
    ServerUpdateConfigRequest,
)
from .streams import Stream
from .transport import rpc, rpc_stream
from .user_connect import UserServiceClient
from .user_pb2 import (
    DeleteUserPluginPermissionGrantRequest,
    GenerateUserAPITokenRequest,
    InvalidateSessionsRequest,
    ListUserPluginPermissionGrantsRequest,
    SetUserPluginPermissionGrantRequest,
    UpdateUserRequest,
    UserCreateRequest,
    UserDeleteRequest,
    UserInfoRequest,
    UserInfoResponse,
    UserListRequest,
    UserListResponse,
    UserPluginPermissionGrant,
)

type Headers = dict[str, str] | None


class SoulFireAdmin:
    """Async high-level access to Soulfire's administrative control plane."""

    def __init__(
        self,
        *,
        client: ClientServiceClient,
        server: ServerServiceClient,
        users: UserServiceClient,
        logs: LogsServiceClient,
        metrics: MetricsServiceClient,
        commands: CommandServiceClient,
        downloads: DownloadServiceClient,
        plugin_stats: PluginStatsServiceClient,
        scripts: ScriptServiceClient,
        instances: InstanceServiceClient,
    ) -> None:
        self._client = client
        self._server = server
        self._users = users
        self._logs = logs
        self._metrics = metrics
        self._commands = commands
        self._downloads = downloads
        self._plugin_stats = plugin_stats
        self._scripts = scripts
        self._instances = instances

    @fn("SoulFireAdmin.client_data")
    def client_data(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[ClientDataResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireAdmin.client_data",
                lambda: self._client.get_client_data(
                    ClientDataRequest(), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.generate_webdav_token")
    def generate_webdav_token(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[str, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireAdmin.generate_webdav_token",
            lambda: self._client.generate_web_dav_token(
                GenerateWebDAVTokenRequest(), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return response.token

    @fn("SoulFireAdmin.generate_api_token")
    def generate_api_token(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[str, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireAdmin.generate_api_token",
            lambda: self._client.generate_api_token(
                GenerateAPITokenRequest(), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return response.token

    @fn("SoulFireAdmin.update_username")
    def update_username(
        self, username: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireAdmin.update_username",
            lambda: self._client.update_self_username(
                UpdateSelfUsernameRequest(username=username), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.update_email")
    def update_email(
        self, email: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireAdmin.update_email",
            lambda: self._client.update_self_email(
                UpdateSelfEmailRequest(email=email), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.invalidate_own_sessions")
    def invalidate_own_sessions(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireAdmin.invalidate_own_sessions",
            lambda: self._client.invalidate_self_sessions(
                InvalidateSelfSessionsRequest(), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.server_info")
    def server_info(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[ServerInfoResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.server_info",
                lambda: self._server.get_server_info(
                    ServerInfoRequest(), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.update_server_config")
    def update_server_config(
        self, config: ServerConfig, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.update_server_config",
            lambda: self._server.update_server_config(
                ServerUpdateConfigRequest(config=config), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.set_server_config_entry")
    def set_server_config_entry(
        self,
        request: ServerUpdateConfigEntryRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.set_server_config_entry",
            lambda: self._server.update_server_config_entry(
                request, headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.list_users")
    def list_users(
        self, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[list[UserListResponse.User], SoulFireOperationError]:
        response = yield from rpc(
            "admin.list_users",
            lambda: self._users.list_users(
                UserListRequest(), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.users)

    @fn("SoulFireAdmin.user")
    def user(
        self, user_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[UserInfoResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.user",
                lambda: self._users.get_user_info(
                    UserInfoRequest(id=user_id), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.create_user")
    def create_user(
        self, request: UserCreateRequest, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[str, SoulFireOperationError]:
        response = yield from rpc(
            "admin.create_user",
            lambda: self._users.create_user(request, headers=headers, timeout_ms=timeout_ms),
        )
        return response.id

    @fn("SoulFireAdmin.delete_user")
    def delete_user(
        self, user_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.delete_user",
            lambda: self._users.delete_user(
                UserDeleteRequest(id=user_id), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.update_user")
    def update_user(
        self, request: UpdateUserRequest, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.update_user",
            lambda: self._users.update_user(request, headers=headers, timeout_ms=timeout_ms),
        )

    @fn("SoulFireAdmin.invalidate_user_sessions")
    def invalidate_user_sessions(
        self, user_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.invalidate_user_sessions",
            lambda: self._users.invalidate_sessions(
                InvalidateSessionsRequest(id=user_id), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.generate_user_api_token")
    def generate_user_api_token(
        self, user_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[str, SoulFireOperationError]:
        response = yield from rpc(
            "admin.generate_user_api_token",
            lambda: self._users.generate_user_api_token(
                GenerateUserAPITokenRequest(id=user_id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return response.token

    @fn("SoulFireAdmin.user_plugin_permission_grants")
    def user_plugin_permission_grants(
        self, user_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[list[UserPluginPermissionGrant], SoulFireOperationError]:
        response = yield from rpc(
            "admin.user_plugin_permission_grants",
            lambda: self._users.list_user_plugin_permission_grants(
                ListUserPluginPermissionGrantsRequest(user_id=user_id),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.grants)

    @fn("SoulFireAdmin.set_user_plugin_permission_grant")
    def set_user_plugin_permission_grant(
        self,
        user_id: str,
        permission_id: str,
        scope: PluginPermissionScope,
        *,
        resource_id: str | None = None,
        granted: bool = True,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[UserPluginPermissionGrant, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.set_user_plugin_permission_grant",
                lambda: self._users.set_user_plugin_permission_grant(
                    SetUserPluginPermissionGrantRequest(
                        user_id=user_id,
                        permission_id=permission_id,
                        scope=scope,
                        granted=granted,
                        **{} if resource_id is None else {"resource_id": resource_id},
                    ),
                    headers=headers,
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireAdmin.delete_user_plugin_permission_grant")
    def delete_user_plugin_permission_grant(
        self,
        user_id: str,
        permission_id: str,
        scope: PluginPermissionScope,
        *,
        resource_id: str | None = None,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.delete_user_plugin_permission_grant",
            lambda: self._users.delete_user_plugin_permission_grant(
                DeleteUserPluginPermissionGrantRequest(
                    user_id=user_id,
                    permission_id=permission_id,
                    scope=scope,
                    **{} if resource_id is None else {"resource_id": resource_id},
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireAdmin.previous_logs")
    def previous_logs(
        self,
        scope: LogScope,
        count: int = 300,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[LogString], SoulFireOperationError]:
        response = yield from rpc(
            "admin.previous_logs",
            lambda: self._logs.get_previous(
                PreviousLogRequest(scope=scope, count=count), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.messages)

    def logs(
        self, scope: LogScope, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> Stream[LogResponse, SoulFireOperationError]:
        return rpc_stream(
            "admin.logs",
            lambda: self._logs.subscribe(
                LogRequest(scope=scope), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.server_metrics")
    def server_metrics(
        self,
        since: Timestamp | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetServerMetricsResponse, SoulFireOperationError]:
        request = GetServerMetricsRequest()
        if since is not None:
            request.since.CopyFrom(since)
        return (
            yield from rpc(
                "admin.server_metrics",
                lambda: self._metrics.get_server_metrics(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.instance_metrics")
    def instance_metrics(
        self,
        instance_id: str,
        since: Timestamp | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetInstanceMetricsResponse, SoulFireOperationError]:
        request = GetInstanceMetricsRequest(instance_id=instance_id)
        if since is not None:
            request.since.CopyFrom(since)
        return (
            yield from rpc(
                "admin.instance_metrics",
                lambda: self._metrics.get_instance_metrics(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.execute_command")
    def execute_command(
        self, request: CommandRequest, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[CommandResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.execute_command",
                lambda: self._commands.execute_command(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.complete_command")
    def complete_command(
        self,
        request: CommandCompletionRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[CommandCompletionResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.complete_command",
                lambda: self._commands.tab_complete_command(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.download")
    def download(
        self, request: DownloadRequest, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[DownloadResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.download",
                lambda: self._downloads.download(request, headers=headers, timeout_ms=timeout_ms),
            )
        )

    @fn("SoulFireAdmin.plugin_stats")
    def plugin_stats(
        self, instance_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[list[PluginRuntimeStat], SoulFireOperationError]:
        response = yield from rpc(
            "admin.plugin_stats",
            lambda: self._plugin_stats.get_instance_plugin_stats(
                GetInstancePluginStatsRequest(instance_id=instance_id),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.stats)

    @fn("SoulFireAdmin.audit_log")
    def audit_log(
        self, instance_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[list[InstanceAuditLogResponse.AuditLogEntry], SoulFireOperationError]:
        response = yield from rpc(
            "admin.audit_log",
            lambda: self._instances.get_audit_log(
                InstanceAuditLogRequest(id=instance_id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.entry)

    @fn("SoulFireAdmin.list_scripts")
    def list_scripts(
        self, instance_id: str, *, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[list[ScriptInfo], SoulFireOperationError]:
        response = yield from rpc(
            "admin.list_scripts",
            lambda: self._scripts.list_scripts(
                ListScriptsRequest(instance_id=instance_id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.scripts)

    @fn("SoulFireAdmin.script")
    def script(
        self,
        instance_id: str,
        script_id: str,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetScriptResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.script",
                lambda: self._scripts.get_script(
                    GetScriptRequest(instance_id=instance_id, script_id=script_id),
                    headers=headers,
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireAdmin.create_script")
    def create_script(
        self,
        request: CreateScriptRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[CreateScriptResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.create_script",
                lambda: self._scripts.create_script(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.update_script")
    def update_script(
        self,
        request: UpdateScriptRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[UpdateScriptResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.update_script",
                lambda: self._scripts.update_script(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.delete_script")
    def delete_script(
        self,
        instance_id: str,
        script_id: str,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.delete_script",
            lambda: self._scripts.delete_script(
                DeleteScriptRequest(instance_id=instance_id, script_id=script_id),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    def activate_script(
        self,
        instance_id: str,
        script_id: str,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> Stream[ScriptEvent, SoulFireOperationError]:
        return rpc_stream(
            "admin.activate_script",
            lambda: self._scripts.activate_script(
                ActivateScriptRequest(instance_id=instance_id, script_id=script_id),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireAdmin.deactivate_script")
    def deactivate_script(
        self,
        instance_id: str,
        script_id: str,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "admin.deactivate_script",
            lambda: self._scripts.deactivate_script(
                DeactivateScriptRequest(instance_id=instance_id, script_id=script_id),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireAdmin.script_status")
    def script_status(
        self,
        instance_id: str,
        script_id: str,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetScriptStatusResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.script_status",
                lambda: self._scripts.get_script_status(
                    GetScriptStatusRequest(instance_id=instance_id, script_id=script_id),
                    headers=headers,
                    timeout_ms=timeout_ms,
                ),
            )
        )

    def script_logs(
        self,
        request: SubscribeScriptLogsRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> Stream[ScriptLogEntry, SoulFireOperationError]:
        return rpc_stream(
            "admin.script_logs",
            lambda: self._scripts.subscribe_script_logs(
                request, headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireAdmin.node_types")
    def node_types(
        self,
        request: GetNodeTypesRequest | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetNodeTypesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.node_types",
                lambda: self._scripts.get_node_types(
                    request or GetNodeTypesRequest(), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.script_registry_data")
    def script_registry_data(
        self,
        request: GetRegistryDataRequest | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetRegistryDataResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.script_registry_data",
                lambda: self._scripts.get_registry_data(
                    request or GetRegistryDataRequest(), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireAdmin.validate_script")
    def validate_script(
        self,
        request: ValidateScriptRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[ValidateScriptResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "admin.validate_script",
                lambda: self._scripts.validate_script(
                    request, headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    def dry_run_script(
        self,
        request: DryRunScriptRequest,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> Stream[ScriptEvent, SoulFireOperationError]:
        return rpc_stream(
            "admin.dry_run_script",
            lambda: self._scripts.dry_run_script(request, headers=headers, timeout_ms=timeout_ms),
        )
