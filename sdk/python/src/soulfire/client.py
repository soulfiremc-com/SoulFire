from __future__ import annotations

import asyncio
import os
import random
from collections.abc import AsyncIterator, Callable, Iterable
from types import MappingProxyType
from typing import Any, Literal, Never, Protocol, TypedDict, cast

from connectrpc.client import ConnectClient
from connectrpc.code import Code
from connectrpc.interceptor import (
    BidiStreamInterceptor,
    ClientStreamInterceptor,
    Interceptor,
    MetadataInterceptor,
    ServerStreamInterceptor,
    UnaryInterceptor,
)
from connectrpc.protocol import ProtocolType
from effect_py import (
    Effect,
    EffectGen,
    Scope,
    acquire_release,
    add_finalizer,
    fail,
    fn,
    from_async,
    gen,
    layer,
    scoped,
    sync,
    try_async,
)
from effect_py.errors import catch_tag
from google.protobuf import json_format
from google.protobuf.struct_pb2 import Value

from ._auth import BearerAuthInterceptor, TokenProvider
from ._install import LocalServerHandle, LocalSoulFireServer
from .admin import SoulFireAdmin
from .bot import SoulFireBot
from .bot_connect import BotServiceClient
from .bot_live_connect import BotLiveServiceClient
from .bot_live_pb2 import BotEventFilter
from .bot_pb2 import (
    BOT_DESIRED_STATE_RUNNING,
    BOT_DESIRED_STATE_STOPPED,
    BotListEntry,
    BotListRequest,
    BotStatus,
    RestartBotsRequest,
    SetBotsDesiredStateRequest,
    WatchBotStatusesRequest,
    WatchBotStatusesResponse,
)
from .chat_connect import ChatServiceClient
from .client_connect import ClientServiceClient
from .command_connect import CommandServiceClient
from .common_pb2 import (
    AccountTypeCredentials,
    AccountTypeDeviceCode,
    MinecraftAccountProto,
    ProxyProto,
)
from .connection import (
    SDK_API_VERSION,
    SDK_VERSION,
    CapabilitySet,
    ConnectionMetadata,
    RequiredPlugin,
    ServerMetadata,
    SoulFireCompatibilityError,
)
from .download_connect import DownloadServiceClient
from .errors import (
    RpcErrorInterceptor,
    SoulFireInstallError,
    SoulFireOperationError,
    SoulFireRpcError,
    SoulFireValidationError,
    operation_error,
)
from .fleet import SoulFireFleet
from .instance_connect import InstanceServiceClient
from .instance_live_connect import InstanceLiveServiceClient
from .instance_live_pb2 import InstanceEvent, InstanceEventFilter, WatchInstanceEventsRequest
from .instance_pb2 import (
    BOT_AUTHENTICATION_MICROSOFT,
    BOT_AUTHENTICATION_OFFLINE,
    InstanceAddAccountsBatchRequest,
    InstanceAddProxiesBatchRequest,
    InstanceCreateRequest,
    InstanceDeleteRequest,
    InstanceGetOrCreateBotRequest,
    InstanceGetOrCreateBotResponse,
    InstanceGetOrCreateRequest,
    InstanceInfo,
    InstanceInfoRequest,
    InstanceListRequest,
    InstanceListResponse,
    InstanceRemoveAccountsBatchRequest,
    InstanceRemoveProxiesBatchRequest,
    InstanceUpdateConfigEntryRequest,
    InstanceUpdateMetaRequest,
)
from .inventory_connect import InventoryServiceClient
from .login_connect import LoginServiceClient
from .login_pb2 import EmailCodeRequest, LoginRequest, NextAuthFlowResponse
from .logs_connect import LogsServiceClient
from .mc_auth_connect import MCAuthServiceClient
from .mc_auth_pb2 import (
    CredentialsAuthRequest,
    CredentialsAuthResponse,
    DeviceCode,
    DeviceCodeAuthRequest,
    DeviceCodeAuthResponse,
    RefreshRequest,
    RefreshResponse,
)
from .metrics_connect import MetricsServiceClient
from .pathfinding_connect import PathfinderServiceClient
from .plugin_api_connect import PluginApiServiceClient
from .plugin_stats_connect import PluginStatsServiceClient
from .plugins import PluginCatalog
from .protocol_connect import BotProtocolServiceClient
from .recipe_connect import RecipeServiceClient
from .registry_connect import RegistryServiceClient
from .script_connect import ScriptServiceClient
from .sdk_connect import SdkServiceClient
from .sdk_pb2 import RequiredPlugin as RequiredPluginMessage
from .sdk_pb2 import SdkHandshakeRequest, SdkIdentity
from .server_connect import ServerServiceClient
from .streams import Stream
from .task_connect import BotTaskServiceClient
from .transport import rpc, rpc_stream, validate
from .user_connect import UserServiceClient
from .world_connect import WorldServiceClient

type ClientInterceptor = (
    UnaryInterceptor
    | ClientStreamInterceptor
    | ServerStreamInterceptor
    | BidiStreamInterceptor
    | MetadataInterceptor[Any]
)


class ManagedInstallOptions(TypedDict, total=False):
    """Options for a local SoulFire server managed by the SDK."""

    directory: str | os.PathLike[str] | None
    version: str | None
    java_args: Iterable[str]
    port: int | None
    startup_timeout: float
    on_log: Callable[[str], None] | None
    timeout_ms: int | None
    interceptors: Iterable[ClientInterceptor]


def normalize_base_url(base_url: str) -> str:
    normalized = base_url.strip().rstrip("/")
    if not normalized:
        raise ValueError("SoulFire base_url must not be empty")
    return normalized


class SoulFire:
    """A scoped client for a SoulFire server and its bot instances.

    Use :meth:`connect` for an existing server, :meth:`install` for a managed
    local server, or :meth:`create_bot` for a ready bot in one operation.
    Direct construction creates RPC clients without a handshake or scope cleanup.

    SDK operations return lazy ``Effect`` values. Compose them with ``yield from``
    inside an Effect workflow, then run the workflow with ``run_async``.
    Keep all client and bot work inside ``scoped``. The scope closes transports
    and any local server that this client manages.

    See Also:
        `Python tutorial <https://soulfiremc.com/docs/sdk/python>`_.
    """

    def __init__(
        self,
        base_url: str,
        *,
        token: TokenProvider | None = None,
        timeout_ms: int | None = None,
        interceptors: Iterable[ClientInterceptor] = (),
        required_capabilities: Iterable[str] = (),
        required_plugins: Iterable[RequiredPlugin] = (),
    ) -> None:
        self._token = token
        self._address = normalize_base_url(base_url)
        self._timeout_ms = timeout_ms
        self._interceptors = (
            BearerAuthInterceptor(lambda: self._token),
            RpcErrorInterceptor(),
            *interceptors,
        )
        self._clients: list[Any] = []
        self._local_server_handle: LocalServerHandle | None = None
        self._required_capabilities = tuple(required_capabilities)
        self._required_plugins = tuple(required_plugins)
        self._connection: ConnectionMetadata | None = None
        self._plugins: PluginCatalog | None = None
        self.bot_service = self.service(BotServiceClient)
        self.bot_live = self.service(BotLiveServiceClient)
        self.bot_tasks = self.service(BotTaskServiceClient)
        self.pathfinder_service = self.service(PathfinderServiceClient)
        self.chat_service = self.service(ChatServiceClient)
        self.inventory_service = self.service(InventoryServiceClient)
        self.recipe_service = self.service(RecipeServiceClient)
        self.registry_service = self.service(RegistryServiceClient)
        self.world_service = self.service(WorldServiceClient)
        self.protocol_service = self.service(BotProtocolServiceClient)
        self.client_service = self.service(ClientServiceClient)
        self.command_service = self.service(CommandServiceClient)
        self.download_service = self.service(DownloadServiceClient)
        self.logs_service = self.service(LogsServiceClient)
        self.metrics_service = self.service(MetricsServiceClient)
        self.plugin_stats_service = self.service(PluginStatsServiceClient)
        self.script_service = self.service(ScriptServiceClient)
        self.server_service = self.service(ServerServiceClient)
        self.user_service = self.service(UserServiceClient)
        self.instance_service = self.service(InstanceServiceClient)
        self.instance_live = self.service(InstanceLiveServiceClient)
        self.login_service = self.service(LoginServiceClient)
        self.mc_auth_service = self.service(MCAuthServiceClient)
        self._sdk_service = self.service(SdkServiceClient)
        self._plugin_api_service = self.service(PluginApiServiceClient)
        self._reflective_plugin_client = ConnectClient(
            self._address,
            protocol=ProtocolType.GRPC_WEB,
            timeout_ms=self._timeout_ms,
            interceptors=cast(Iterable[Interceptor], self._interceptors),
        )
        self._clients.append(self._reflective_plugin_client)

    @classmethod
    @fn("SoulFire.connect")
    def connect(
        cls,
        base_url: str,
        *,
        token: TokenProvider | None = None,
        timeout_ms: int | None = None,
        interceptors: Iterable[ClientInterceptor] = (),
        required_capabilities: Iterable[str] = (),
        required_plugins: Iterable[RequiredPlugin] = (),
    ) -> EffectGen[SoulFire, SoulFireOperationError, Scope]:
        """Connect to an existing server and check SDK compatibility.

        The handshake checks the API version, required capabilities, and required
        plugin versions. The scope closes the client's transports on exit.
        It does not stop the remote SoulFire server.

        Args:
            base_url: SoulFire gRPC-Web URL, including ``http://`` or ``https://``.
                This is separate from the Minecraft server address.
            token: Bearer token or token provider. Omit it for a public server.
            timeout_ms: Default RPC timeout in milliseconds. ``None`` uses the
                transport default. This is separate from bot readiness timeouts.
            interceptors: Additional ConnectRPC interceptors.
            required_capabilities: Capability identifiers the server must support.
            required_plugins: Plugin identifiers and version constraints to check.

        Returns:
            An Effect that produces a client after the handshake. Requires ``Scope``.

        Notes:
            Transport, authentication, and compatibility errors use the Effect error
            channel. Use :meth:`unauthenticated` when login must precede the handshake.
        """
        client = yield from cls.unauthenticated(
            base_url,
            token=token,
            timeout_ms=timeout_ms,
            interceptors=interceptors,
            required_capabilities=required_capabilities,
            required_plugins=required_plugins,
        )
        yield from client.negotiate()
        return client

    @classmethod
    @fn("SoulFire.unauthenticated")
    def unauthenticated(
        cls,
        base_url: str,
        *,
        token: TokenProvider | None = None,
        timeout_ms: int | None = None,
        interceptors: Iterable[ClientInterceptor] = (),
        required_capabilities: Iterable[str] = (),
        required_plugins: Iterable[RequiredPlugin] = (),
    ) -> EffectGen[SoulFire, SoulFireOperationError, Scope]:
        """Create a scoped client without an SDK handshake.

        Use this client for the login flow on a server that requires authentication.
        After login, negotiate compatibility before reading server metadata.
        The scope closes all transports. This method accepts the same connection
        options as :meth:`connect`.

        Returns:
            An Effect that produces a client. Requires ``Scope``.
        """
        return (
            yield from acquire_release(
                validate(
                    lambda: cls(
                        base_url,
                        token=token,
                        timeout_ms=timeout_ms,
                        interceptors=interceptors,
                        required_capabilities=required_capabilities,
                        required_plugins=required_plugins,
                    )
                ),
                lambda client, _: client.close(),
            )
        )

    @classmethod
    @fn("SoulFire.install")
    def install(
        cls,
        *,
        directory: str | os.PathLike[str] | None = None,
        version: str | None = None,
        java_args: Iterable[str] = (),
        port: int | None = None,
        startup_timeout: float = 120.0,
        on_log: Callable[[str], None] | None = None,
        timeout_ms: int | None = None,
        interceptors: Iterable[ClientInterceptor] = (),
    ) -> EffectGen[SoulFire, SoulFireOperationError, Scope]:
        """Download and start a local SoulFire server owned by the scope.

        The installer downloads Java and SoulFire when needed, starts the process,
        then connects with its local token and completes the SDK handshake.
        The scope closes the client and stops the managed process on exit.
        Downloaded files and server data remain available for later runs.

        Args:
            directory: Installation and data directory. Defaults to ``.soulfire``
                in the current working directory.
            version: Release tag to install. ``None`` selects the latest release.
            java_args: Extra JVM arguments before the SoulFire JAR.
            port: Local gRPC-Web port. ``None`` selects an available port.
            startup_timeout: Maximum startup wait in seconds. Defaults to 120.
            on_log: Callback for server log lines.
            timeout_ms: Default RPC timeout in milliseconds after startup.
            interceptors: Additional ConnectRPC interceptors.

        Returns:
            An Effect that produces a connected client. Requires ``Scope``.

        Notes:
            Installation failures use ``SoulFireInstallError`` in the Effect error
            channel. Startup timeout and RPC timeout control different stages.
        """
        from ._install import install_local_server

        handle = yield from acquire_release(
            try_async(
                lambda: asyncio.to_thread(
                    install_local_server,
                    directory=directory,
                    version=version,
                    java_args=tuple(java_args),
                    port=port,
                    startup_timeout=startup_timeout,
                    on_log=on_log,
                ),
                lambda error: SoulFireInstallError(str(error)),
            ),
            lambda handle, _: from_async(lambda handle=handle: asyncio.to_thread(handle.close)),
        )
        client = yield from cls.unauthenticated(
            handle.info.base_url,
            token=handle.token,
            timeout_ms=timeout_ms,
            interceptors=interceptors,
        )
        client._local_server_handle = handle
        yield from client.negotiate()
        return client

    def set_token(self, token: TokenProvider | None) -> None:
        """Replace the bearer token or token provider for subsequent RPC calls.

        This synchronous change does not perform a handshake or restart open streams.
        """
        self._token = token

    @classmethod
    @fn("SoulFire.create_bot")
    def create_bot(
        cls,
        *,
        server: str,
        username: str,
        auth: Literal["offline", "microsoft"] = "offline",
        instance_name: str | None = None,
        name: str | None = None,
        ready_timeout: float = 30.0,
        on_device_code: Callable[[DeviceCode], Effect[None, SoulFireOperationError]] | None = None,
        installation: ManagedInstallOptions | None = None,
    ) -> EffectGen[SoulFireBot, SoulFireOperationError, Scope]:
        """Install SoulFire and create or reuse a ready bot by name.

        The instance name defaults to the Minecraft server address. The bot name
        and offline username default to ``username``. Repeated calls reuse named
        resources. Conflicting account or server configuration fails explicitly.
        The scope owns the managed process, observation, and bot startup.

        Args:
            server: Minecraft address, such as ``localhost:25565``.
            username: Minecraft username for a new offline account.
            auth: ``"offline"`` by default. ``"microsoft"`` enables device-code login
                when no matching account exists.
            instance_name: Stable instance name. Defaults to the trimmed address.
            name: Stable bot name within the instance. Defaults to ``username``.
            ready_timeout: Positive, finite readiness deadline in seconds.
                Defaults to 30. The bot must receive an initial player snapshot.
            on_device_code: Effect callback for the first Microsoft login code.
                Without a callback, the SDK prints the sign-in URL and code.
            installation: Local server options accepted by :meth:`install`.

        Returns:
            An Effect that produces a ready, observed bot. Requires ``Scope``.

        See Also:
            :meth:`SoulFireInstance.get_or_create_bot` for several bots on one client.
        """
        client = yield from cls.install(**(installation or {}))
        instance = yield from client.get_or_create_instance(
            instance_name if instance_name is not None else server.strip(), server=server
        )
        return (
            yield from instance.get_or_create_bot(
                name if name is not None else username,
                username=username,
                auth=auth,
                ready_timeout=ready_timeout,
                on_device_code=on_device_code,
            )
        )

    @property
    def local_server(self) -> LocalSoulFireServer | None:
        handle = self._local_server_handle
        return None if handle is None else handle.info

    @property
    def server(self) -> ServerMetadata:
        return self._require_connection().server

    @property
    def identity(self) -> SdkIdentity:
        return self._require_connection().identity

    @property
    def capabilities(self) -> CapabilitySet:
        return self._require_connection().capabilities

    @property
    def limits(self) -> MappingProxyType[str, int]:
        return self._require_connection().limits

    @property
    def plugins(self) -> PluginCatalog:
        if self._plugins is None:
            raise RuntimeError("SoulFire connection has not completed its SDK handshake")
        return self._plugins

    @property
    def admin(self) -> SoulFireAdmin:
        return SoulFireAdmin(
            client=self.client_service,
            server=self.server_service,
            users=self.user_service,
            logs=self.logs_service,
            metrics=self.metrics_service,
            commands=self.command_service,
            downloads=self.download_service,
            plugin_stats=self.plugin_stats_service,
            scripts=self.script_service,
            instances=self.instance_service,
        )

    @property
    def local_server_logs(self) -> tuple[str, ...]:
        handle = self._local_server_handle
        return () if handle is None else handle.logs

    @property
    def is_local_server_running(self) -> bool:
        handle = self._local_server_handle
        return handle is not None and handle.is_running

    @fn("SoulFire.restart_local_server")
    def restart_local_server(self) -> EffectGen[None, SoulFireOperationError]:
        handle = yield from validate(self._require_local_server)
        yield from rpc("SoulFire.restart_local_server", lambda: asyncio.to_thread(handle.restart))

    @fn("SoulFire.stop_local_server")
    def stop_local_server(self) -> EffectGen[None, SoulFireOperationError]:
        handle = yield from validate(self._require_local_server)
        yield from rpc("SoulFire.stop_local_server", lambda: asyncio.to_thread(handle.stop))

    def service[ClientT](self, client_type: Callable[..., ClientT]) -> ClientT:
        client = client_type(
            self._address,
            protocol=ProtocolType.GRPC_WEB,
            timeout_ms=self._timeout_ms,
            interceptors=self._interceptors,
        )
        self._clients.append(client)
        return client

    def instance(self, instance_id: str) -> SoulFireInstance:
        """Create an instance handle without a network request.

        Args:
            instance_id: Existing instance UUID, not its friendly name.

        Returns:
            A handle whose operations use this client's transports. Creating the
            handle does not check that the instance exists or that access is allowed.

        See Also:
            :meth:`get_or_create_instance` to provision an instance by name.
        """
        return SoulFireInstance(
            instance_id,
            self.bot_service,
            self.bot_live,
            self.instance_service,
            self.mc_auth_service,
            self.bot_tasks,
            self.pathfinder_service,
            self.chat_service,
            self.inventory_service,
            self.recipe_service,
            self.registry_service,
            self.world_service,
            self.protocol_service,
            None if self._connection is None else self._connection.capabilities,
            self.instance_live,
        )

    @fn("SoulFire.instances")
    def instances(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[list[InstanceListResponse.Instance], SoulFireOperationError]:
        response = yield from rpc(
            "SoulFire.instances",
            lambda: self.instance_service.list_instances(
                InstanceListRequest(), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.instances)

    @fn("SoulFire.create_instance")
    def create_instance(
        self,
        friendly_name: str,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireInstance, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFire.create_instance",
            lambda: self.instance_service.create_instance(
                InstanceCreateRequest(friendlyName=friendly_name),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return self.instance(response.id)

    @fn("SoulFire.get_or_create_instance")
    def get_or_create_instance(
        self,
        name: str,
        *,
        server: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireInstance, SoulFireOperationError]:
        """Find or create an instance by name for the authenticated user.

        Args:
            name: Stable instance name used for provisioning.
            server: Minecraft address to configure. A conflicting address fails.
            timeout_ms: RPC timeout in milliseconds.

        Returns:
            An Effect that produces an instance handle. Requires the negotiated
            ``instance.provisioning.v1`` capability.

        Notes:
            Reuse the returned instance to provision several bots. This operation
            does not start bots or delete the instance when a scope closes.
        """
        yield from validate(lambda: self.capabilities.require("instance.provisioning.v1"))
        request = InstanceGetOrCreateRequest(name=name)
        if server is not None:
            request.server = server
        response = yield from rpc(
            "SoulFire.get_or_create_instance",
            lambda: self.instance_service.get_or_create_instance(request, timeout_ms=timeout_ms),
        )
        return self.instance(response.id)

    @fn("SoulFire.begin_login")
    def begin_login(
        self, email: str, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[NextAuthFlowResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFire.begin_login",
                lambda: self.login_service.login(
                    LoginRequest(email=email), headers=headers, timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFire.complete_login")
    def complete_login(
        self,
        auth_flow_token: str,
        code: str,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[NextAuthFlowResponse, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFire.complete_login",
            lambda: self.login_service.email_code(
                EmailCodeRequest(auth_flow_token=auth_flow_token, code=code),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        if response.WhichOneof("next") == "success":
            self.set_token(response.success.token)
            yield from self.negotiate()
        return response

    @fn("SoulFire.close")
    def close(self) -> EffectGen[None]:
        """Close client transports and stop its managed local server, if any.

        The connection scope calls this operation automatically. Prefer scope cleanup
        so active workflows finish before their transports close. This operation
        leaves downloaded files and persistent server data in place.
        """
        clients, self._clients = self._clients, []
        handle, self._local_server_handle = self._local_server_handle, None

        @gen
        def release() -> EffectGen[None, Never, Scope]:
            if handle is not None:
                yield from add_finalizer(
                    lambda _, handle=handle: from_async(lambda: asyncio.to_thread(handle.close))
                )
            for client in clients:
                yield from add_finalizer(lambda _, client=client: from_async(client.close))

        yield from scoped(release)

    def _require_local_server(self) -> LocalServerHandle:
        if self._local_server_handle is None:
            raise RuntimeError("This client does not manage a local SoulFire server")
        return self._local_server_handle

    def _require_connection(self) -> ConnectionMetadata:
        if self._connection is None:
            raise RuntimeError("SoulFire connection has not completed its SDK handshake")
        return self._connection

    @fn("SoulFire.negotiate")
    def negotiate(self) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFire.handshake", lambda: self._sdk_service.handshake(self._handshake_request())
        ).pipe(
            catch_tag(SoulFireRpcError)(
                lambda error: (
                    fail(SoulFireCompatibilityError(str(error)))
                    if error.code == Code.FAILED_PRECONDITION
                    else fail(error)
                )
            )
        )
        self._connection = yield from validate(lambda: ConnectionMetadata.from_response(response))
        self._plugins = PluginCatalog(
            self._plugin_api_service,
            self.service,
            self._connection.plugins,
            self._reflective_plugin_client,
        )

    def _handshake_request(self) -> SdkHandshakeRequest:
        return SdkHandshakeRequest(
            sdk_name="soulfire",
            sdk_version=SDK_VERSION,
            minimum_api_version=SDK_API_VERSION,
            maximum_api_version=SDK_API_VERSION,
            required_capabilities=self._required_capabilities,
            required_plugins=[
                RequiredPluginMessage(
                    plugin_id=plugin.plugin_id,
                    **{}
                    if plugin.version_range is None
                    else {"version_range": plugin.version_range},
                )
                for plugin in self._required_plugins
            ],
        )


class SoulFireInstance:
    """An instance handle that groups bot accounts and configuration.

    Obtain a handle from :meth:`SoulFire.instance` or
    :meth:`SoulFire.get_or_create_instance`. Use :meth:`get_or_create_bot` for
    normal provisioning and :meth:`bot` for an existing bot UUID.
    The instance itself persists after the client scope closes.
    """

    def __init__(
        self,
        instance_id: str,
        bot_service: BotServiceClient,
        bot_live: BotLiveServiceClient,
        instance_service: InstanceServiceClient,
        mc_auth_service: MCAuthServiceClient | None = None,
        bot_tasks: BotTaskServiceClient | None = None,
        pathfinder_service: PathfinderServiceClient | None = None,
        chat_service: ChatServiceClient | None = None,
        inventory_service: InventoryServiceClient | None = None,
        recipe_service: RecipeServiceClient | None = None,
        registry_service: RegistryServiceClient | None = None,
        world_service: WorldServiceClient | None = None,
        protocol_service: BotProtocolServiceClient | None = None,
        capabilities: CapabilitySet | None = None,
        instance_live: InstanceLiveServiceClient | None = None,
    ) -> None:
        self.id = instance_id
        self._bot_service = bot_service
        self._bot_live = bot_live
        self._instance_service = instance_service
        self._mc_auth_service = mc_auth_service
        self._bot_tasks = bot_tasks
        self._pathfinder_service = pathfinder_service
        self._chat_service = chat_service
        self._inventory_service = inventory_service
        self._recipe_service = recipe_service
        self._registry_service = registry_service
        self._world_service = world_service
        self._protocol_service = protocol_service
        self._capabilities = capabilities
        self._instance_live = instance_live

    @property
    def fleet(self) -> SoulFireFleet:
        return SoulFireFleet(self, self._capabilities)

    @fn("SoulFireInstance.get_or_create_bot")
    def get_or_create_bot(
        self,
        name: str,
        *,
        auth: Literal["offline", "microsoft"] = "offline",
        username: str | None = None,
        start: bool = True,
        ready_timeout: float = 30.0,
        on_device_code: Callable[[DeviceCode], Effect[None, SoulFireOperationError]] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireBot, SoulFireOperationError, Scope]:
        """Provision a named account and return a bot, ready by default.

        Bot names belong to this instance. Repeated calls reuse the named account;
        conflicting usernames or authentication methods fail explicitly.
        With ``start=True``, this operation calls :meth:`SoulFireBot.connect`.
        Scope cleanup stops a bot started by the SDK and preserves a bot that
        was already running. The account remains in the instance.

        Args:
            name: Stable bot name within this instance.
            auth: ``"offline"`` or ``"microsoft"``. Defaults to ``"offline"``.
            username: Minecraft username for provisioning an offline account.
            start: Start and observe the bot when true. With false, returns an
                unconnected handle for configuration before :meth:`SoulFireBot.connect`.
            ready_timeout: Positive, finite readiness deadline in seconds.
                Defaults to 30 and applies when ``start`` is true.
            on_device_code: Effect callback for Microsoft device-code login.
                Without a callback, the SDK prints the sign-in URL and code.
            timeout_ms: RPC timeout in milliseconds.

        Returns:
            An Effect that produces a bot handle. Requires ``Scope``.
        """
        if auth not in ("offline", "microsoft"):
            return (yield from fail(SoulFireValidationError("auth must be offline or microsoft")))
        request = InstanceGetOrCreateBotRequest(
            id=self.id,
            name=name,
            auth=BOT_AUTHENTICATION_MICROSOFT
            if auth == "microsoft"
            else BOT_AUTHENTICATION_OFFLINE,
        )
        if username is not None:
            request.username = username

        def provision() -> Effect[InstanceGetOrCreateBotResponse, SoulFireOperationError]:
            return rpc(
                "SoulFireInstance.get_or_create_bot",
                lambda: self._instance_service.get_or_create_bot(request, timeout_ms=timeout_ms),
            )

        @gen
        def authenticate() -> EffectGen[InstanceGetOrCreateBotResponse, SoulFireOperationError]:
            def show_code(event: DeviceCodeAuthResponse) -> Effect[None, SoulFireOperationError]:
                if event.WhichOneof("data") != "device_code":
                    return sync(lambda: None)
                code = event.device_code
                if on_device_code is not None:
                    return on_device_code(code)
                return sync(
                    lambda: print(f"Sign in at {code.verification_uri} with code {code.user_code}")
                )

            account = (
                yield from self.login_device_code(
                    AccountTypeDeviceCode.MICROSOFT_JAVA_DEVICE_CODE, timeout_ms=timeout_ms
                )
                .tap(show_code)
                .filter(lambda event: event.WhichOneof("data") == "account")
                .map(lambda event: event.account)
                .run_head()
            )
            if account is None:
                return (
                    yield from fail(
                        operation_error(
                            "SoulFireInstance.get_or_create_bot",
                            RuntimeError("Microsoft authentication ended without an account"),
                        )
                    )
                )
            request.account.CopyFrom(account)
            return (yield from provision())

        response = yield from provision().pipe(
            catch_tag(SoulFireRpcError)(
                lambda error: (
                    authenticate
                    if auth == "microsoft" and error.code == Code.NOT_FOUND
                    else fail(error)
                )
            )
        )
        bot = self.bot(response.bot_id)
        if start:
            yield from bot.connect(ready_timeout=ready_timeout, timeout_ms=timeout_ms)
        return bot

    def bot(self, bot_id: str) -> SoulFireBot:
        """Create a bot handle without a network request or readiness wait.

        Args:
            bot_id: Existing bot UUID, not its username or provisioning name.

        Returns:
            A bot handle with no attached observation session. Use
            :meth:`SoulFireBot.connect` for scoped readiness and live state.
        """
        return SoulFireBot(
            self.id,
            bot_id,
            self._bot_service,
            self._bot_live,
            self._bot_tasks,
            self._pathfinder_service,
            self._chat_service,
            self._inventory_service,
            self._recipe_service,
            self._registry_service,
            self._world_service,
            self._protocol_service,
        )

    @fn("SoulFireInstance.info")
    def info(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[InstanceInfo, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireInstance.info",
            lambda: self._instance_service.get_instance_info(
                InstanceInfoRequest(id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        if response.WhichOneof("result") != "info":
            return (
                yield from fail(
                    operation_error(
                        "SoulFireInstance.info",
                        RuntimeError(f"SoulFire did not return instance {self.id}"),
                    )
                )
            )
        return response.info

    @fn("SoulFireInstance.delete")
    def delete(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.delete",
            lambda: self._instance_service.delete_instance(
                InstanceDeleteRequest(id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )

    @fn("SoulFireInstance.update_name")
    def update_name(
        self,
        friendly_name: str,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.update_name",
            lambda: self._instance_service.update_instance_meta(
                InstanceUpdateMetaRequest(id=self.id, friendly_name=friendly_name),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.set_config_entry")
    def set_config_entry(
        self,
        namespace: str,
        key: str,
        value: Any,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.set_config_entry",
            lambda: self._instance_service.update_instance_config_entry(
                InstanceUpdateConfigEntryRequest(
                    id=self.id, namespace=namespace, key=key, value=_to_proto_value(value)
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.add_accounts")
    def add_accounts(
        self,
        accounts: Iterable[MinecraftAccountProto],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.add_accounts",
            lambda: self._instance_service.add_instance_accounts_batch(
                InstanceAddAccountsBatchRequest(id=self.id, accounts=accounts),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.remove_accounts")
    def remove_accounts(
        self,
        profile_ids: Iterable[str],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.remove_accounts",
            lambda: self._instance_service.remove_instance_accounts_batch(
                InstanceRemoveAccountsBatchRequest(
                    id=self.id, profile_ids=dict.fromkeys(profile_ids)
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.add_proxies")
    def add_proxies(
        self,
        proxies: Iterable[ProxyProto],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.add_proxies",
            lambda: self._instance_service.add_instance_proxies_batch(
                InstanceAddProxiesBatchRequest(id=self.id, proxies=proxies),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.remove_proxies")
    def remove_proxies(
        self,
        addresses: Iterable[str],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireInstance.remove_proxies",
            lambda: self._instance_service.remove_instance_proxies_batch(
                InstanceRemoveProxiesBatchRequest(id=self.id, addresses=dict.fromkeys(addresses)),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    def login_credentials(
        self,
        service: AccountTypeCredentials,
        payload: Iterable[str],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[CredentialsAuthResponse, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireInstance.login_credentials",
            lambda: self._require_mc_auth().login_credentials(
                CredentialsAuthRequest(instance_id=self.id, service=service, payload=payload),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    def login_device_code(
        self,
        service: AccountTypeDeviceCode,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[DeviceCodeAuthResponse, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireInstance.login_device_code",
            lambda: self._require_mc_auth().login_device_code(
                DeviceCodeAuthRequest(instance_id=self.id, service=service),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireInstance.refresh_account")
    def refresh_account(
        self,
        account: MinecraftAccountProto,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[RefreshResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInstance.refresh_account",
                lambda: self._require_mc_auth().refresh(
                    RefreshRequest(instance_id=self.id, account=account),
                    headers=headers,
                    timeout_ms=timeout_ms,
                ),
            )
        )

    def _require_mc_auth(self) -> MCAuthServiceClient:
        if self._mc_auth_service is None:
            raise RuntimeError("Minecraft authentication is unavailable")
        return self._mc_auth_service

    @fn("SoulFireInstance.bots")
    def bots(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[list[BotListEntry], SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireInstance.bots",
            lambda: self._bot_service.get_bot_list(
                BotListRequest(instance_id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return list(response.bots)

    def watch_bot_statuses(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> Stream[WatchBotStatusesResponse, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireInstance.watch_bot_statuses",
            lambda: self._bot_service.watch_bot_statuses(
                WatchBotStatusesRequest(instance_id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )

    def events(
        self,
        filter: InstanceEventFilter | None = None,
        *,
        bot_ids: Iterable[str] = (),
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[InstanceEvent, SoulFireOperationError]:
        """Watch one multiplexed event stream for bots in this instance."""

        def create() -> AsyncIterator[InstanceEvent]:
            if self._instance_live is None:
                raise RuntimeError("The instance live service is unavailable")
            event_filter = filter or _default_instance_event_filter()
            if filter is None:
                event_filter.bot_ids.extend(dict.fromkeys(bot_ids))
            elif tuple(bot_ids):
                raise ValueError("Pass bot_ids in filter or as bot_ids, not both")
            return self._instance_live.watch_instance_events(
                WatchInstanceEventsRequest(instance_id=self.id, filter=event_filter),
                headers=headers,
                timeout_ms=timeout_ms,
            )

        return rpc_stream("SoulFireInstance.events", create)

    @fn("SoulFireInstance.start")
    def start(
        self,
        *,
        bot_ids: Iterable[str] | None = None,
        count: int | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        selected = yield from self._select_bot_ids(
            bot_ids,
            count,
            lambda bot: bot.status.desired_state != BOT_DESIRED_STATE_RUNNING,
            headers,
            timeout_ms,
        )
        if not selected:
            return []
        response = yield from rpc(
            "SoulFireInstance.start",
            lambda: self._bot_service.set_bots_desired_state(
                SetBotsDesiredStateRequest(
                    instance_id=self.id, bot_ids=selected, desired_state=BOT_DESIRED_STATE_RUNNING
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.bots)

    @fn("SoulFireInstance.stop")
    def stop(
        self,
        *,
        bot_ids: Iterable[str] | None = None,
        count: int | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        selected = yield from self._select_bot_ids(
            bot_ids,
            count,
            lambda bot: bot.status.desired_state == BOT_DESIRED_STATE_RUNNING,
            headers,
            timeout_ms,
        )
        if not selected:
            return []
        response = yield from rpc(
            "SoulFireInstance.stop",
            lambda: self._bot_service.set_bots_desired_state(
                SetBotsDesiredStateRequest(
                    instance_id=self.id, bot_ids=selected, desired_state=BOT_DESIRED_STATE_STOPPED
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.bots)

    @fn("SoulFireInstance.restart")
    def restart(
        self,
        *,
        bot_ids: Iterable[str] | None = None,
        count: int | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        selected = yield from self._select_bot_ids(
            bot_ids,
            count,
            lambda bot: bot.status.desired_state == BOT_DESIRED_STATE_RUNNING,
            headers,
            timeout_ms,
        )
        if not selected:
            return []
        response = yield from rpc(
            "SoulFireInstance.restart",
            lambda: self._bot_service.restart_bots(
                RestartBotsRequest(instance_id=self.id, bot_ids=selected),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.bots)

    @fn("SoulFireInstance._select_bot_ids")
    def _select_bot_ids(
        self,
        bot_ids: Iterable[str] | None,
        count: int | None,
        count_filter: Callable[[BotListEntry], bool],
        headers: dict[str, str] | None,
        timeout_ms: int | None,
    ) -> EffectGen[list[str], SoulFireOperationError]:
        explicit = _explicit_bot_ids(bot_ids, count)
        if explicit is not None:
            return explicit
        bots = yield from self.bots(headers=headers, timeout_ms=timeout_ms)
        candidates = [bot for bot in bots if count_filter(bot)]
        if count is None:
            return [bot.profile_id for bot in candidates]
        normalized_count = _normalize_count(count)
        if normalized_count == 0:
            return []
        if (yield from self._shuffle_accounts_enabled(headers, timeout_ms)):
            random.shuffle(candidates)
        return [bot.profile_id for bot in candidates[:normalized_count]]

    @fn("SoulFireInstance._shuffle_accounts_enabled")
    def _shuffle_accounts_enabled(
        self, headers: dict[str, str] | None, timeout_ms: int | None
    ) -> EffectGen[bool, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireInstance._shuffle_accounts_enabled",
            lambda: self._instance_service.get_instance_info(
                InstanceInfoRequest(id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        if response.WhichOneof("result") != "info":
            return False
        return _has_shuffle_accounts(response.info.config.settings)


def _explicit_bot_ids(bot_ids: Iterable[str] | None, count: int | None) -> list[str] | None:
    if bot_ids is not None and count is not None:
        raise ValueError("Use either bot_ids or count, not both")
    if bot_ids is None:
        return None
    return list(dict.fromkeys(bot_ids))


def _default_instance_event_filter() -> InstanceEventFilter:
    return InstanceEventFilter(
        bot_events=BotEventFilter(
            include_block_updates=True,
            include_boss_bars=True,
            include_chat=True,
            include_damage=True,
            include_entity_events=True,
            include_environment=True,
            include_inventory=True,
            include_lifecycle=True,
            include_player_list=True,
            include_resource_packs=True,
            include_scoreboard=True,
            include_state_deltas=True,
            include_titles=True,
        )
    )


def _normalize_count(count: int) -> int:
    if isinstance(count, bool):
        raise TypeError("Bot count must be an integer")
    return max(0, int(count))


def _has_shuffle_accounts(settings: Iterable[Any]) -> bool:
    for namespace in settings:
        if namespace.namespace != "account":
            continue
        for entry in namespace.entries:
            if entry.key == "shuffle-accounts" and entry.value.WhichOneof("kind") == "bool_value":
                return entry.value.bool_value
    return False


def _to_proto_value(value: Any) -> Value:
    if isinstance(value, Value):
        return value
    return json_format.ParseDict(value, Value())


class SoulFireService(Protocol):
    @property
    def admin(self) -> SoulFireAdmin: ...

    @property
    def plugins(self) -> PluginCatalog: ...

    @property
    def server(self) -> ServerMetadata: ...

    @property
    def capabilities(self) -> CapabilitySet: ...

    def instances(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> Effect[list[InstanceListResponse.Instance], SoulFireOperationError]: ...

    def create_instance(
        self,
        friendly_name: str,
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Effect[SoulFireInstance, SoulFireOperationError]: ...

    def instance(self, instance_id: str) -> SoulFireInstance: ...

    def get_or_create_instance(
        self, name: str, *, server: str | None = None, timeout_ms: int | None = None
    ) -> Effect[SoulFireInstance, SoulFireOperationError]: ...


def connection_layer(
    base_url: str,
    *,
    token: TokenProvider | None = None,
    timeout_ms: int | None = None,
    required_capabilities: Iterable[str] = (),
    required_plugins: Iterable[RequiredPlugin] = (),
) -> layer.Layer[SoulFireService, SoulFireOperationError]:
    return layer.effect(
        SoulFireService,
        SoulFire.connect(
            base_url,
            token=token,
            timeout_ms=timeout_ms,
            required_capabilities=required_capabilities,
            required_plugins=required_plugins,
        ),
    )
