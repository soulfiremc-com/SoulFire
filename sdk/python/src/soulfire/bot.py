from __future__ import annotations

import math
from collections.abc import Iterable

from effect_py import (
    Effect,
    EffectGen,
    Scope,
    acquire_release,
    add_finalizer,
    clock,
    fail,
    fn,
    gen,
    schedule,
    scoped,
    sync,
)
from effect_py.errors import catch_tag

from .actions import action_headers, require_action
from .bot_connect import BotServiceClient
from .bot_live_connect import BotLiveServiceClient
from .bot_live_pb2 import (
    HAND_MAIN,
    AcquireBotControlRequest,
    AttackEntityRequest,
    BlockFace,
    BotActionResult,
    BotControlLease,
    BotEvent,
    BotEventFilter,
    CreativeItemStack,
    DigBlockRequest,
    DismountRequest,
    FindBlocksRequest,
    FindBlocksResponse,
    GetBlockRequest,
    GetBlockResponse,
    GoToRequest,
    Hand,
    InteractBlockRequest,
    InteractEntityRequest,
    ListNearbyEntitiesRequest,
    ListNearbyEntitiesResponse,
    MountEntityRequest,
    MountEntityResponse,
    PathfindGoal,
    PathfindOptions,
    PathfindProgress,
    PlaceBlockRequest,
    ReleaseBotControlRequest,
    ReleaseItemRequest,
    RenewBotControlRequest,
    ResourcePackResponse,
    RespawnRequest,
    RespondResourcePackRequest,
    SetCreativeSlotRequest,
    SetFlyingRequest,
    SetVehicleControlRequest,
    SetVehicleControlResponse,
    SleepRequest,
    StartElytraFlightRequest,
    StopPathfindingRequest,
    SwingArmRequest,
    UpdateSignRequest,
    UseItemRequest,
    WaitForChunksRequest,
    WaitForChunksResponse,
    WakeRequest,
    WatchBotEventsRequest,
    WriteBookRequest,
)
from .bot_pb2 import (
    BOT_DESIRED_STATE_RUNNING,
    BOT_DESIRED_STATE_STOPPED,
    DROP_ALL,
    DROP_ONE,
    LEFT_CLICK,
    SHIFT_LEFT_CLICK,
    BotCloseContainerRequest,
    BotContainerButtonClickRequest,
    BotInfoRequest,
    BotInfoResponse,
    BotInventoryClickRequest,
    BotInventoryStateRequest,
    BotInventoryStateResponse,
    BotOpenInventoryRequest,
    BotResetMovementRequest,
    BotSetHotbarSlotRequest,
    BotSetMovementStateRequest,
    BotSetRotationRequest,
    BotStatus,
    ClickType,
    RestartBotsRequest,
    SetBotsDesiredStateRequest,
)
from .camera import SoulFireCamera
from .chat_connect import ChatServiceClient
from .common_pb2 import BlockPosition
from .errors import (
    SoulFireOperationError,
    SoulFireTimeoutError,
    SoulFireValidationError,
    operation_error,
)
from .inventory_connect import InventoryServiceClient
from .inventory_pb2 import InventoryScope
from .pathfinding import SoulFirePathfinder
from .pathfinding_connect import PathfinderServiceClient
from .protocol import SoulFireProtocol
from .protocol_connect import BotProtocolServiceClient
from .recipe_connect import RecipeServiceClient
from .registry_connect import RegistryServiceClient
from .semantic import (
    SoulFireChat,
    SoulFireInventory,
    SoulFireRecipes,
    SoulFireRegistry,
    SoulFireWorld,
)
from .session import BotSession, BotSessionOptions, BotSessionState, empty_bot_session_state
from .streams import End, Stream
from .task_connect import BotTaskServiceClient
from .task_pb2 import CollectBlocksTaskResult
from .tasks import SoulFireTasks
from .transport import rpc, rpc_stream, validate
from .world_connect import WorldServiceClient


def default_event_filter() -> BotEventFilter:
    return BotEventFilter(
        include_state_deltas=True,
        include_chat=True,
        include_lifecycle=True,
        include_inventory=True,
        include_damage=True,
        include_resource_packs=True,
        include_titles=True,
    )


def _require_success(success: bool, error: str, fallback: str) -> None:
    if not success:
        raise RuntimeError(error or fallback)


def _required_service[ServiceT](service: ServiceT | None, name: str) -> ServiceT:
    if service is None:
        raise RuntimeError(f"The {name} service is unavailable")
    return service


_require_action = require_action
_action_headers = action_headers


class SoulFireBot:
    """One bot's lifecycle, actions, live state, and server tasks.

    Obtain this handle from :meth:`soulfire.SoulFire.create_bot`,
    :meth:`soulfire.SoulFireInstance.get_or_create_bot`, or
    :meth:`soulfire.SoulFireInstance.bot`. Direct construction requires RPC clients
    and is intended for transport integration.

    Use :meth:`connect` before reading :attr:`state`. The scope owns observation
    and stops a bot that this operation started. Task helpers return lazy Effects
    or Streams. A method call alone does not execute a bot operation.

    Actions report rejection through the Effect error channel. Use
    :attr:`tasks` for server jobs and :meth:`collect` for a collection workflow
    that waits for completion and cancels unfinished work on interruption.
    """

    def __init__(
        self,
        instance_id: str,
        bot_id: str,
        bot_client: BotServiceClient,
        live_client: BotLiveServiceClient,
        task_client: BotTaskServiceClient | None = None,
        pathfinder_client: PathfinderServiceClient | None = None,
        chat_client: ChatServiceClient | None = None,
        inventory_client: InventoryServiceClient | None = None,
        recipe_client: RecipeServiceClient | None = None,
        registry_client: RegistryServiceClient | None = None,
        world_client: WorldServiceClient | None = None,
        protocol_client: BotProtocolServiceClient | None = None,
    ) -> None:
        self.instance_id = instance_id
        self.id = bot_id
        self._bot_client = bot_client
        self._live_client = live_client
        self._task_client = task_client
        self._pathfinder_client = pathfinder_client
        self._chat_client = chat_client
        self._inventory_client = inventory_client
        self._recipe_client = recipe_client
        self._registry_client = registry_client
        self._world_client = world_client
        self._protocol_client = protocol_client
        self._control_token: str | None = None
        self._session: BotSession | None = None

    @property
    def state(self) -> BotSessionState:
        """Latest state from the session attached by :meth:`connect`.

        Before connection or after scope cleanup, this property returns an empty
        state. It performs no network request. State can lag behind the server;
        use a session predicate to wait for a required update.
        """
        return self._session.state if self._session is not None else empty_bot_session_state()

    @fn("SoulFireBot.connect")
    def connect(
        self, *, ready_timeout: float = 30.0, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError, Scope]:
        """Start the bot if necessary and wait for its initial player snapshot.

        An existing attached session makes this operation a no-op. Otherwise, it
        reads the bot's desired state, starts a stopped bot, and attaches observation.
        The scope closes observation and stops the bot only if this operation
        started it. A previously running bot keeps its running state.

        Args:
            ready_timeout: Positive, finite deadline in seconds for startup and the
                initial player snapshot. Defaults to 30.
            timeout_ms: Per-RPC timeout in milliseconds. This is separate from the
                overall readiness deadline.

        Returns:
            An Effect with no result value. Requires ``Scope``.

        Notes:
            An invalid deadline fails with ``SoulFireValidationError``. Readiness
            expiration fails with ``SoulFireTimeoutError``. RPC failures also use the
            Effect error channel. :meth:`start` alone does not attach observation.
        """
        if self._session is not None:
            return
        if not math.isfinite(ready_timeout) or ready_timeout <= 0:
            yield from fail(
                SoulFireValidationError("ready_timeout must be a positive finite number")
            )
        deadline = (yield from clock.now()) + ready_timeout

        def timeout[A](
            operation: Effect[A, SoulFireOperationError],
        ) -> Effect[A, SoulFireOperationError]:
            @gen
            def run() -> EffectGen[A, SoulFireOperationError]:
                remaining = deadline - (yield from clock.now())
                timed = operation.pipe(schedule.timeout(max(0.0, remaining)))

                def on_timeout(_: schedule.TimeoutException) -> Effect[A, SoulFireOperationError]:
                    return fail(
                        SoulFireTimeoutError(
                            "Timed out waiting for the bot's initial player snapshot"
                        )
                    )

                return (yield from catch_tag(schedule.TimeoutException)(on_timeout)(timed))

            return run

        current = yield from timeout(self.info(timeout_ms=timeout_ms))
        info = yield from acquire_release(
            sync(lambda: current),
            lambda info, _: (
                sync(lambda: None)
                if info.status.desired_state == BOT_DESIRED_STATE_RUNNING
                else self.stop().map(lambda _: None).or_die()
            ),
        )
        if info.status.desired_state != BOT_DESIRED_STATE_RUNNING:
            yield from timeout(self.start(timeout_ms=timeout_ms))
        remaining = max(0.0, deadline - (yield from clock.now()))
        session = yield from self.observe(ready_timeout=remaining)
        yield from timeout(session.wait_for_state(lambda state: state.player is not None))
        self._session = session
        yield from add_finalizer(lambda _: sync(lambda: self._clear_session(session)))

    def _clear_session(self, session: BotSession) -> None:
        if self._session is session:
            self._session = None

    @fn("SoulFireBot.collect")
    def collect(
        self,
        target: str | Iterable[str],
        *,
        count: int = 1,
        search_radius: int = 32,
        avoid_submerged_targets: bool = False,
        require_line_of_sight: bool = False,
        options: PathfindOptions | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[CollectBlocksTaskResult, SoulFireOperationError]:
        """Collect matching blocks and wait for the typed task result.

        This helper owns the collection task. On interruption or failure before task
        completion, scope cleanup requests cancellation of unfinished server work.
        For a task handle with explicit ownership, use
        :meth:`SoulFireTasks.collect_blocks` through :attr:`tasks`.

        Args:
            target: A block ID, block tag prefixed with ``#``, or iterable of selectors.
                For example, ``"minecraft:oak_log"`` or ``"#minecraft:logs"``.
            count: Number of blocks to collect. Defaults to 1.
            search_radius: Search distance in blocks. Defaults to 32, at most 64.
            avoid_submerged_targets: Skip targets covered by fluid up to the bot's height.
            require_line_of_sight: Restrict selection to visible blocks.
            options: Pathfinding configuration, or server defaults when omitted.
            timeout_ms: RPC timeout in milliseconds. This does not set a task deadline.

        Returns:
            An Effect that produces ``CollectBlocksTaskResult`` on completion.
            Non-successful task status fails through the Effect error channel.

        Examples:
            Inside an Effect workflow::

                result = yield from bot.collect("#minecraft:logs", count=16)
                yield from sync(lambda: print(result))
        """

        @gen
        def run() -> EffectGen[CollectBlocksTaskResult, SoulFireOperationError, Scope]:
            task = yield from acquire_release(
                self.tasks.collect_blocks(
                    target,
                    count=count,
                    search_radius=search_radius,
                    avoid_submerged_targets=avoid_submerged_targets,
                    require_line_of_sight=require_line_of_sight,
                    options=options,
                    timeout_ms=timeout_ms,
                ),
                lambda task, _: (
                    sync(lambda: None)
                    if task.terminal
                    else task.cancel().map(lambda _: None).or_die()
                ),
            )
            return (yield from task.result(timeout_ms=timeout_ms))

        return (yield from scoped(run))

    @property
    def tasks(self) -> SoulFireTasks:
        """Durable server jobs for this bot.

        Start methods return a task handle after acceptance. ``run_*`` methods
        return event streams with cancellation tied to the stream by default.
        """
        if self._task_client is None:
            raise RuntimeError("The bot task service is unavailable")
        return SoulFireTasks(
            self.instance_id,
            self.id,
            self._task_client,
            lambda headers: _action_headers(headers, self._control_token),
        )

    @property
    def pathfinder(self) -> SoulFirePathfinder:
        return SoulFirePathfinder(
            self.instance_id,
            self.id,
            _required_service(self._pathfinder_client, "pathfinder"),
            self.tasks,
        )

    @property
    def chat(self) -> SoulFireChat:
        return SoulFireChat(
            self.instance_id,
            self.id,
            _required_service(self._chat_client, "chat"),
            lambda headers: _action_headers(headers, self._control_token),
            lambda event_filter, timeout_ms: (
                self._session.events()
                if self._session is not None and timeout_ms is None
                else self.events(event_filter, timeout_ms=timeout_ms)
            ),
        )

    @property
    def inventory(self) -> SoulFireInventory:
        return SoulFireInventory(
            self.instance_id,
            self.id,
            _required_service(self._inventory_client, "inventory"),
            lambda headers: _action_headers(headers, self._control_token),
        )

    @property
    def recipes(self) -> SoulFireRecipes:
        return SoulFireRecipes(
            InventoryScope(instance_id=self.instance_id, bot_id=self.id),
            _required_service(self._recipe_client, "recipe"),
            self.tasks,
        )

    @property
    def registry(self) -> SoulFireRegistry:
        return SoulFireRegistry(
            self.instance_id, self.id, _required_service(self._registry_client, "registry")
        )

    @property
    def world(self) -> SoulFireWorld:
        return SoulFireWorld(
            self.instance_id, self.id, _required_service(self._world_client, "world")
        )

    @property
    def camera(self) -> SoulFireCamera:
        return SoulFireCamera(self.instance_id, self.id, self._bot_client)

    @property
    def protocol(self) -> SoulFireProtocol:
        return SoulFireProtocol(
            self.instance_id, self.id, _required_service(self._protocol_client, "protocol")
        )

    @fn("SoulFireBot.start")
    def start(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotStatus, SoulFireOperationError]:
        """Set the bot's desired state to running and return its status.

        This operation does not wait for a player snapshot, attach observation, or
        stop the bot at scope exit. Use :meth:`connect` for those lifecycle guarantees.
        ``timeout_ms`` controls the RPC timeout in milliseconds.
        """
        response = yield from rpc(
            "SoulFireBot.start",
            lambda: self._bot_client.set_bots_desired_state(
                SetBotsDesiredStateRequest(
                    instance_id=self.instance_id,
                    bot_ids=[self.id],
                    desired_state=BOT_DESIRED_STATE_RUNNING,
                ),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _required_status(response.bots, self.id)))

    @fn("SoulFireBot.stop")
    def stop(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotStatus, SoulFireOperationError]:
        """Set the bot's desired state to stopped and return its status.

        This is an explicit server state change. It does not remove the account or
        instance. ``timeout_ms`` controls the RPC timeout in milliseconds.
        """
        response = yield from rpc(
            "SoulFireBot.stop",
            lambda: self._bot_client.set_bots_desired_state(
                SetBotsDesiredStateRequest(
                    instance_id=self.instance_id,
                    bot_ids=[self.id],
                    desired_state=BOT_DESIRED_STATE_STOPPED,
                ),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _required_status(response.bots, self.id)))

    @fn("SoulFireBot.restart")
    def restart(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotStatus, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.restart",
            lambda: self._bot_client.restart_bots(
                RestartBotsRequest(instance_id=self.instance_id, bot_ids=[self.id]),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _required_status(response.bots, self.id)))

    @fn("SoulFireBot.status")
    def status(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotStatus, SoulFireOperationError]:
        return (yield from self.info(timeout_ms=timeout_ms)).status

    @fn("SoulFireBot.info")
    def info(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotInfoResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.info",
                lambda: self._bot_client.get_bot_info(
                    BotInfoRequest(instance_id=self.instance_id, bot_id=self.id),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireBot.wait_for_online")
    def wait_for_online(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotStatus, SoulFireOperationError]:
        """Wait for live state or a snapshot and return the latest bot status.

        This operation does not start the bot or attach a session to :attr:`state`.
        An event stream that ends before readiness fails through the Effect error
        channel. ``timeout_ms`` controls RPC timeout in milliseconds.
        """

        @gen
        def wait() -> EffectGen[BotStatus, SoulFireOperationError, Scope]:
            current = yield from self.info(timeout_ms=timeout_ms)
            latest_status = current.status
            if current.HasField("live_state"):
                return latest_status
            _cursor = yield from self.events(timeout_ms=timeout_ms).open
            while True:
                _item = yield from _cursor.next()
                if isinstance(_item, End):
                    break
                event = _item.value
                event_type = event.WhichOneof("event")
                if event_type == "status":
                    latest_status = event.status
                elif event_type == "snapshot":
                    return latest_status
            return (
                yield from fail(
                    operation_error(
                        "SoulFireBot.wait_for_online",
                        RuntimeError(f"Bot {self.id} event stream ended before it came online"),
                    )
                )
            )

        return (yield from scoped(wait))

    def events(
        self, event_filter: BotEventFilter | None = None, *, timeout_ms: int | None = None
    ) -> Stream[BotEvent, SoulFireOperationError]:
        """Observe filtered bot events through a lazy Stream.

        With no custom filter or timeout, an attached session supplies the events.
        Otherwise, consumption opens a separate RPC stream. The default filter
        includes state deltas, chat, lifecycle, inventory, damage, resource packs,
        and titles. Consuming events alone does not attach :attr:`state`.

        Args:
            event_filter: Explicit event selection, or the SDK default when omitted.
            timeout_ms: RPC timeout in milliseconds.

        Returns:
            A Stream that produces ``BotEvent`` values. Interruption closes the
            subscription; it does not stop the bot.
        """
        if self._session is not None and event_filter is None and timeout_ms is None:
            return self._session.events()
        return rpc_stream(
            "SoulFireBot.events",
            lambda: self._live_client.watch_bot_events(
                WatchBotEventsRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    filter=event_filter or default_event_filter(),
                ),
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireBot.observe")
    def observe(
        self,
        options: BotSessionOptions | None = None,
        *,
        timeout_ms: int | None = None,
        ready_timeout: float | None = None,
    ) -> EffectGen[BotSession, SoulFireOperationError, Scope]:
        """Open a scoped session that maintains state from bot events.

        With no custom options, reuse the session attached by :meth:`connect`, if
        present. Otherwise, return a separate session. The scope owns its
        subscription. This operation does not start a stopped bot or attach the
        new session to :attr:`state`; read the returned session's state instead.

        Args:
            options: Session filters, buffering, and resumption configuration.
            timeout_ms: RPC timeout in milliseconds.
            ready_timeout: Session readiness wait in seconds. ``None`` uses the
                session default.

        Returns:
            An Effect that produces ``BotSession``. Requires ``Scope``.
        """
        if self._session is not None and options is None:
            return self._session

        def stream(request: WatchBotEventsRequest) -> Stream[BotEvent, SoulFireOperationError]:
            request.instance_id = self.instance_id
            request.bot_id = self.id
            return rpc_stream(
                "SoulFireBot.observe",
                lambda: self._live_client.watch_bot_events(request, timeout_ms=timeout_ms),
            )

        return (yield from BotSession.open(stream, options, ready_timeout=ready_timeout))

    @fn("SoulFireBot.send_chat")
    def send_chat(
        self, message: str, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        from .bot_live_pb2 import SendChatRequest

        response = yield from rpc(
            "SoulFireBot.send_chat",
            lambda: self._live_client.send_chat(
                SendChatRequest(instance_id=self.instance_id, bot_id=self.id, message=message),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.get_block")
    def get_block(
        self, position: BlockPosition, *, timeout_ms: int | None = None
    ) -> EffectGen[GetBlockResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.get_block",
                lambda: self._live_client.get_block(
                    GetBlockRequest(
                        instance_id=self.instance_id, bot_id=self.id, position=position
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireBot.find_blocks")
    def find_blocks(
        self,
        block_ids: Iterable[str],
        *,
        max_distance: int,
        max_count: int,
        timeout_ms: int | None = None,
    ) -> EffectGen[FindBlocksResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.find_blocks",
                lambda: self._live_client.find_blocks(
                    FindBlocksRequest(
                        instance_id=self.instance_id,
                        bot_id=self.id,
                        block_ids=block_ids,
                        max_distance=max_distance,
                        max_count=max_count,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireBot.list_nearby_entities")
    def list_nearby_entities(
        self,
        radius: float,
        *,
        entity_types: Iterable[str] = (),
        include_players: bool = True,
        timeout_ms: int | None = None,
    ) -> EffectGen[ListNearbyEntitiesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.list_nearby_entities",
                lambda: self._live_client.list_nearby_entities(
                    ListNearbyEntitiesRequest(
                        instance_id=self.instance_id,
                        bot_id=self.id,
                        radius=radius,
                        entity_types=entity_types,
                        include_players=include_players,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireBot.dig_block")
    def dig_block(
        self, position: BlockPosition, *, cancel: bool = False, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.dig_block",
            lambda: self._live_client.dig_block(
                DigBlockRequest(
                    instance_id=self.instance_id, bot_id=self.id, position=position, cancel=cancel
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.place_block")
    def place_block(
        self,
        against: BlockPosition,
        face: BlockFace,
        hand: Hand = HAND_MAIN,
        *,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.place_block",
            lambda: self._live_client.place_block(
                PlaceBlockRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    against=against,
                    face=face,
                    hand=hand,
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.interact_block")
    def interact_block(
        self,
        position: BlockPosition,
        face: BlockFace,
        hand: Hand = HAND_MAIN,
        *,
        sneaking: bool = False,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.interact_block",
            lambda: self._live_client.interact_block(
                InteractBlockRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    position=position,
                    face=face,
                    hand=hand,
                    sneaking=sneaking,
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.use_item")
    def use_item(
        self, hand: Hand = HAND_MAIN, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.use_item",
            lambda: self._live_client.use_item(
                UseItemRequest(instance_id=self.instance_id, bot_id=self.id, hand=hand),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.release_item")
    def release_item(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.release_item",
            lambda: self._live_client.release_item(
                ReleaseItemRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.attack_entity")
    def attack_entity(
        self, entity_id: int, *, sprinting: bool = False, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.attack_entity",
            lambda: self._live_client.attack_entity(
                AttackEntityRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    entity_id=entity_id,
                    sprinting=sprinting,
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.interact_entity")
    def interact_entity(
        self,
        entity_id: int,
        *,
        hand: Hand = HAND_MAIN,
        sneaking: bool = False,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.interact_entity",
            lambda: self._live_client.interact_entity(
                InteractEntityRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    entity_id=entity_id,
                    hand=hand,
                    sneaking=sneaking,
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.swing_arm")
    def swing_arm(
        self, hand: Hand = HAND_MAIN, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.swing_arm",
            lambda: self._live_client.swing_arm(
                SwingArmRequest(instance_id=self.instance_id, bot_id=self.id, hand=hand),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.respawn")
    def respawn(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.respawn",
            lambda: self._live_client.respawn(
                RespawnRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.sleep")
    def sleep(
        self, bed: BlockPosition, hand: Hand = HAND_MAIN, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.sleep",
            lambda: self._live_client.sleep(
                SleepRequest(instance_id=self.instance_id, bot_id=self.id, bed=bed, hand=hand),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.wake")
    def wake(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.wake",
            lambda: self._live_client.wake(
                WakeRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.mount")
    def mount(
        self, entity_id: int, hand: Hand = HAND_MAIN, *, timeout_ms: int | None = None
    ) -> EffectGen[MountEntityResponse, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.mount",
            lambda: self._live_client.mount_entity(
                MountEntityRequest(
                    instance_id=self.instance_id, bot_id=self.id, entity_id=entity_id, hand=hand
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        _require_action(response.result)
        return response

    @fn("SoulFireBot.dismount")
    def dismount(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.dismount",
            lambda: self._live_client.dismount(
                DismountRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.set_vehicle_control")
    def set_vehicle_control(
        self,
        *,
        forward: bool | None = None,
        backward: bool | None = None,
        left: bool | None = None,
        right: bool | None = None,
        jump: bool | None = None,
        sneak: bool | None = None,
        sprint: bool | None = None,
        yaw: float | None = None,
        pitch: float | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SetVehicleControlResponse, SoulFireOperationError]:
        request = SetVehicleControlRequest(instance_id=self.instance_id, bot_id=self.id)
        _apply_vehicle_control(
            request,
            forward=forward,
            backward=backward,
            left=left,
            right=right,
            jump=jump,
            sneak=sneak,
            sprint=sprint,
            yaw=yaw,
            pitch=pitch,
        )
        response = yield from rpc(
            "SoulFireBot.set_vehicle_control",
            lambda: self._live_client.set_vehicle_control(
                request, headers=_action_headers(None, self._control_token), timeout_ms=timeout_ms
            ),
        )
        _require_action(response.result)
        return response

    @fn("SoulFireBot.update_sign")
    def update_sign(
        self,
        position: BlockPosition,
        lines: Iterable[str],
        *,
        front_text: bool = True,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.update_sign",
            lambda: self._live_client.update_sign(
                UpdateSignRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    position=position,
                    front_text=front_text,
                    lines=list(lines),
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.write_book")
    def write_book(
        self,
        inventory_slot: int,
        pages: Iterable[str],
        *,
        title: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.write_book",
            lambda: self._live_client.write_book(
                WriteBookRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    inventory_slot=inventory_slot,
                    pages=list(pages),
                    **{} if title is None else {"title": title},
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.respond_resource_pack")
    def respond_resource_pack(
        self, pack_id: str, response: ResourcePackResponse, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        result = yield from rpc(
            "SoulFireBot.respond_resource_pack",
            lambda: self._live_client.respond_resource_pack(
                RespondResourcePackRequest(
                    instance_id=self.instance_id, bot_id=self.id, pack_id=pack_id, response=response
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return _require_action(result.result)

    @fn("SoulFireBot.set_flying")
    def set_flying(
        self, flying: bool, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.set_flying",
            lambda: self._live_client.set_flying(
                SetFlyingRequest(instance_id=self.instance_id, bot_id=self.id, flying=flying),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.start_elytra_flight")
    def start_elytra_flight(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.start_elytra_flight",
            lambda: self._live_client.start_elytra_flight(
                StartElytraFlightRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.set_creative_slot")
    def set_creative_slot(
        self,
        slot: int,
        item_id: str | None = None,
        *,
        count: int = 1,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.set_creative_slot",
            lambda: self._live_client.set_creative_slot(
                SetCreativeSlotRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    slot=slot,
                    **{}
                    if item_id is None
                    else {"item": CreativeItemStack(item_id=item_id, count=count)},
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: _require_action(response.result)))

    @fn("SoulFireBot.wait_for_chunks")
    def wait_for_chunks(
        self, radius_chunks: int = 0, *, wait_timeout_ms: int = 0, timeout_ms: int | None = None
    ) -> EffectGen[WaitForChunksResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.wait_for_chunks",
                lambda: self._live_client.wait_for_chunks(
                    WaitForChunksRequest(
                        instance_id=self.instance_id,
                        bot_id=self.id,
                        radius_chunks=radius_chunks,
                        timeout_ms=wait_timeout_ms,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    def go_to(
        self,
        goal: PathfindGoal,
        options: PathfindOptions | None = None,
        *,
        timeout_ms: int | None = None,
    ) -> Stream[PathfindProgress, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireBot.go_to",
            lambda: self._live_client.go_to(
                GoToRequest(
                    instance_id=self.instance_id, bot_id=self.id, goal=goal, options=options
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireBot.stop_pathfinding")
    def stop_pathfinding(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireBot.stop_pathfinding",
            lambda: self._live_client.stop_pathfinding(
                StopPathfindingRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireBot.inventory_state")
    def inventory_state(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotInventoryStateResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireBot.inventory_state",
                lambda: self._bot_client.get_inventory_state(
                    BotInventoryStateRequest(instance_id=self.instance_id, bot_id=self.id),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireBot.click_inventory")
    def click_inventory(
        self,
        slot: int,
        click_type: ClickType = LEFT_CLICK,
        *,
        hotbar_slot: int = 0,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.click_inventory",
            lambda: self._bot_client.click_inventory_slot(
                BotInventoryClickRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    slot=slot,
                    click_type=click_type,
                    hotbar_slot=hotbar_slot,
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, response.error, "Inventory click failed")
        )

    @fn("SoulFireBot.transfer_inventory_slot")
    def transfer_inventory_slot(
        self, slot: int, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from self.click_inventory(slot, SHIFT_LEFT_CLICK, timeout_ms=timeout_ms)

    @fn("SoulFireBot.drop_inventory_slot")
    def drop_inventory_slot(
        self, slot: int, *, all: bool = True, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from self.click_inventory(slot, DROP_ALL if all else DROP_ONE, timeout_ms=timeout_ms)

    @fn("SoulFireBot.move_inventory_stack")
    def move_inventory_stack(
        self, from_slot: int, to_slot: int, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from self.click_inventory(from_slot, timeout_ms=timeout_ms)
        yield from self.click_inventory(to_slot, timeout_ms=timeout_ms)
        state = yield from self.inventory_state(timeout_ms=timeout_ms)
        if state.HasField("carried_item") and state.carried_item.count > 0:
            yield from self.click_inventory(from_slot, timeout_ms=timeout_ms)

    @fn("SoulFireBot.select_hotbar")
    def select_hotbar(
        self, slot: int, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.select_hotbar",
            lambda: self._bot_client.set_hotbar_slot(
                BotSetHotbarSlotRequest(instance_id=self.instance_id, bot_id=self.id, slot=slot),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(
                response.success, response.error, "Selecting a hotbar slot failed"
            )
        )

    @fn("SoulFireBot.set_movement")
    def set_movement(
        self,
        *,
        forward: bool | None = None,
        backward: bool | None = None,
        left: bool | None = None,
        right: bool | None = None,
        jump: bool | None = None,
        sneak: bool | None = None,
        sprint: bool | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[None, SoulFireOperationError]:
        values = {
            key: value
            for key, value in {
                "forward": forward,
                "backward": backward,
                "left": left,
                "right": right,
                "jump": jump,
                "sneak": sneak,
                "sprint": sprint,
            }.items()
            if value is not None
        }
        response = yield from rpc(
            "SoulFireBot.set_movement",
            lambda: self._bot_client.set_movement_state(
                BotSetMovementStateRequest(instance_id=self.instance_id, bot_id=self.id, **values),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, response.error, "Updating movement failed")
        )

    @fn("SoulFireBot.reset_movement")
    def reset_movement(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.reset_movement",
            lambda: self._bot_client.reset_movement(
                BotResetMovementRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, response.error, "Resetting movement failed")
        )

    @fn("SoulFireBot.look")
    def look(
        self, yaw: float, pitch: float, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.look",
            lambda: self._bot_client.set_rotation(
                BotSetRotationRequest(
                    instance_id=self.instance_id, bot_id=self.id, yaw=yaw, pitch=pitch
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, response.error, "Updating rotation failed")
        )

    @fn("SoulFireBot.open_inventory")
    def open_inventory(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.open_inventory",
            lambda: self._bot_client.open_inventory(
                BotOpenInventoryRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, "", "Opening inventory failed")
        )

    @fn("SoulFireBot.close_container")
    def close_container(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.close_container",
            lambda: self._bot_client.close_container(
                BotCloseContainerRequest(instance_id=self.instance_id, bot_id=self.id),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(response.success, "", "Closing container failed")
        )

    @fn("SoulFireBot.click_container_button")
    def click_container_button(
        self, button_id: int, *, timeout_ms: int | None = None
    ) -> EffectGen[None, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.click_container_button",
            lambda: self._bot_client.click_container_button(
                BotContainerButtonClickRequest(
                    instance_id=self.instance_id, bot_id=self.id, button_id=button_id
                ),
                headers=_action_headers(None, self._control_token),
                timeout_ms=timeout_ms,
            ),
        )
        yield from validate(
            lambda: _require_success(
                response.success, response.error, "Clicking a container button failed"
            )
        )

    def acquire_control(
        self, *, ttl_seconds: int = 30, timeout_ms: int | None = None
    ) -> Effect[SoulFireBotControlLease, SoulFireOperationError, Scope]:
        """Acquire exclusive action control and release it at scope exit.

        Action requests from this handle include the lease token. Renew the lease
        before expiry for longer workflows; renewal is not automatic.
        Acquiring another lease on the same handle fails while a token is active.

        Args:
            ttl_seconds: Lease lifetime in seconds. Defaults to 30.
            timeout_ms: RPC timeout in milliseconds.

        Returns:
            An Effect that produces a control lease. Requires ``Scope``.

        See Also:
            :meth:`SoulFireBotControlLease.renew` for manual renewal.
        """
        return acquire_release(
            self._acquire_control(ttl_seconds=ttl_seconds, timeout_ms=timeout_ms),
            lambda lease, _: lease.release(timeout_ms=timeout_ms).or_die(),
        )

    @fn("SoulFireBot.acquire_control")
    def _acquire_control(
        self, *, ttl_seconds: int = 30, timeout_ms: int | None = None
    ) -> EffectGen[SoulFireBotControlLease, SoulFireOperationError]:
        if self._control_token is not None:
            return (
                yield from fail(
                    operation_error(
                        "SoulFireBot.acquire_control",
                        RuntimeError(f"Bot {self.id} control is already leased by this client"),
                    )
                )
            )
        response = yield from rpc(
            "SoulFireBot.acquire_control",
            lambda: self._live_client.acquire_bot_control(
                AcquireBotControlRequest(
                    instance_id=self.instance_id, bot_id=self.id, ttl_seconds=ttl_seconds
                ),
                timeout_ms=timeout_ms,
            ),
        )
        if not response.HasField("lease"):
            return (
                yield from fail(
                    operation_error(
                        "SoulFireBot.acquire_control",
                        RuntimeError("SoulFire did not return the acquired control lease"),
                    )
                )
            )
        self._control_token = response.lease.token
        return SoulFireBotControlLease(self, response.lease)

    @fn("SoulFireBot.renew_control")
    def renew_control(
        self, lease: BotControlLease, ttl_seconds: int, timeout_ms: int | None
    ) -> EffectGen[BotControlLease, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireBot.renew_control",
            lambda: self._live_client.renew_bot_control(
                RenewBotControlRequest(
                    instance_id=self.instance_id,
                    bot_id=self.id,
                    token=lease.token,
                    ttl_seconds=ttl_seconds,
                ),
                timeout_ms=timeout_ms,
            ),
        )
        if not response.HasField("lease"):
            return (
                yield from fail(
                    operation_error(
                        "SoulFireBot.renew_control",
                        RuntimeError("SoulFire did not return the renewed control lease"),
                    )
                )
            )
        self._control_token = response.lease.token
        return response.lease

    @fn("SoulFireBot.release_control")
    def release_control(
        self, lease: BotControlLease, timeout_ms: int | None
    ) -> EffectGen[None, SoulFireOperationError]:
        yield from rpc(
            "SoulFireBot.release_control",
            lambda: self._live_client.release_bot_control(
                ReleaseBotControlRequest(
                    instance_id=self.instance_id, bot_id=self.id, token=lease.token
                ),
                timeout_ms=timeout_ms,
            ),
        )
        if self._control_token == lease.token:
            self._control_token = None


class SoulFireBotControlLease:
    """Exclusive action control with explicit renewal and scoped release.

    Obtain a lease from :meth:`SoulFireBot.acquire_control`. The scope releases
    it automatically. Call :meth:`renew` before expiry for long workflows.
    This object does not renew the lease in the background.
    """

    def __init__(self, bot: SoulFireBot, lease: BotControlLease) -> None:
        self._bot = bot
        self._lease: BotControlLease | None = lease

    @property
    def value(self) -> BotControlLease:
        if self._lease is None:
            raise RuntimeError("The bot control lease has been released")
        return self._lease

    @fn("SoulFireBotControlLease.renew")
    def renew(
        self, *, ttl_seconds: int = 30, timeout_ms: int | None = None
    ) -> EffectGen[BotControlLease, SoulFireOperationError]:
        """Extend the active lease and return the updated server lease.

        ``ttl_seconds`` is the new lifetime in seconds, defaulting to 30.
        A released or expired lease fails through the Effect error channel.
        ``timeout_ms`` controls RPC timeout in milliseconds.
        """
        self._lease = yield from self._bot.renew_control(
            (yield from validate(lambda: self.value)), ttl_seconds, timeout_ms
        )
        return self._lease

    @fn("SoulFireBotControlLease.release")
    def release(self, *, timeout_ms: int | None = None) -> EffectGen[None, SoulFireOperationError]:
        """Release the lease and clear this handle's action token.

        Repeated calls after a successful release do nothing. The owning scope
        calls this method automatically. ``timeout_ms`` controls RPC timeout in
        milliseconds.
        """
        if self._lease is None:
            return
        yield from self._bot.release_control(self._lease, timeout_ms)
        self._lease = None


def _apply_vehicle_control(
    request: SetVehicleControlRequest,
    *,
    forward: bool | None,
    backward: bool | None,
    left: bool | None,
    right: bool | None,
    jump: bool | None,
    sneak: bool | None,
    sprint: bool | None,
    yaw: float | None,
    pitch: float | None,
) -> None:
    if forward is not None:
        request.forward = forward
    if backward is not None:
        request.backward = backward
    if left is not None:
        request.left = left
    if right is not None:
        request.right = right
    if jump is not None:
        request.jump = jump
    if sneak is not None:
        request.sneak = sneak
    if sprint is not None:
        request.sprint = sprint
    if yaw is not None:
        request.yaw = yaw
    if pitch is not None:
        request.pitch = pitch


def _required_status(statuses: Iterable[BotStatus], bot_id: str) -> BotStatus:
    for status in statuses:
        if status.profile_id == bot_id:
            return status
    raise RuntimeError(f"SoulFire did not return status for bot {bot_id}")
