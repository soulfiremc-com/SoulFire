from __future__ import annotations

import math
import random
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime
from types import MappingProxyType
from typing import TYPE_CHECKING, Literal, Never

from effect_py import (
    Effect,
    EffectGen,
    Scope,
    Success,
    acquire_release,
    fail,
    fn,
    gen,
    scoped,
    succeed,
)
from effect_py.errors import TaggedError
from google.protobuf.message import Message
from google.protobuf.struct_pb2 import Value

from .bot_pb2 import BotConnectionPhase, BotDesiredState, BotListEntry, BotRuntimeState, BotStatus
from .common_pb2 import MinecraftAccountProto, SettingsNamespace
from .concurrency import parallel
from .connection import CapabilitySet
from .errors import SoulFireOperationError
from .streams import Stream
from .task_pb2 import (
    BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
    BOT_TASK_DISCONNECT_POLICY_UNSPECIFIED,
    BOT_TASK_PRIORITY_UNSPECIFIED,
    BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
    BotTask,
    BotTaskConflictPolicy,
    BotTaskDisconnectPolicy,
    BotTaskEvent,
    BotTaskPriority,
    BotTaskReconnectPolicy,
)
from .tasks import SoulFireTask
from .transport import validate

if TYPE_CHECKING:
    from .client import SoulFireInstance
type Headers = dict[str, str] | None
type FleetOrder = Literal["configured", "name", "health", "distance", "random"]


@dataclass(frozen=True, slots=True)
class FleetPoint:
    x: float
    y: float
    z: float
    dimension: str | None = None


@dataclass(frozen=True, slots=True)
class FleetRadius(FleetPoint):
    radius: float = 0


@dataclass(frozen=True, slots=True)
class FleetMetadataSelector:
    namespace: str
    key: str
    exists: bool | None = None
    equals: object = field(default_factory=lambda: _UNSET)


@dataclass(frozen=True, slots=True)
class FleetBot:
    id: str
    entry: BotListEntry
    account: MinecraftAccountProto | None
    metadata: Mapping[str, Mapping[str, object]]


type FleetPredicate = Callable[[FleetBot], bool | Effect[bool, SoulFireOperationError]]


@dataclass(frozen=True, slots=True, kw_only=True)
class FleetSelector:
    bot_ids: tuple[str, ...] = ()
    account_names: tuple[str, ...] = ()
    account_types: tuple[int, ...] = ()
    online: bool | None = None
    desired_states: tuple[BotDesiredState, ...] = ()
    runtime_states: tuple[BotRuntimeState, ...] = ()
    connection_phases: tuple[BotConnectionPhase, ...] = ()
    dimensions: tuple[str, ...] = ()
    minimum_health: float | None = None
    maximum_health: float | None = None
    minimum_food_level: int | None = None
    maximum_ping_ms: int | None = None
    near: FleetRadius | None = None
    metadata: tuple[FleetMetadataSelector, ...] = ()
    required_capabilities: tuple[str, ...] = ()
    predicate: FleetPredicate | None = None
    order_by: FleetOrder | Callable[[FleetBot], str | int | float] = "configured"
    limit: int | None = None


@dataclass(frozen=True, slots=True)
class FleetAssignment[ItemT]:
    bot: FleetBot
    items: tuple[ItemT, ...]


@dataclass(frozen=True, slots=True)
class FleetTaskStartFailure:
    bot: FleetBot
    error: Exception


@dataclass(frozen=True, slots=True)
class FleetTaskMember[ResultT: Message]:
    bot: FleetBot
    task: SoulFireTask[ResultT]


@dataclass(frozen=True, slots=True)
class FleetTaskStreamEvent:
    bot: FleetBot
    event: BotTaskEvent


@dataclass(frozen=True, slots=True)
class FleetTaskOutcome[ResultT: Message]:
    status: Literal["fulfilled", "rejected"]
    bot: FleetBot
    value: ResultT | None = None
    error: Exception | None = None


@dataclass(frozen=True, slots=True)
class FleetTaskReport[ResultT: Message]:
    outcomes: tuple[FleetTaskOutcome[ResultT], ...]

    @property
    def fulfilled(self) -> tuple[FleetTaskOutcome[ResultT], ...]:
        return tuple(outcome for outcome in self.outcomes if outcome.status == "fulfilled")

    @property
    def rejected(self) -> tuple[FleetTaskOutcome[ResultT], ...]:
        return tuple(outcome for outcome in self.outcomes if outcome.status == "rejected")


@dataclass(frozen=True, slots=True)
class FleetOperationOutcome[ValueT]:
    status: Literal["fulfilled", "rejected"]
    bot: FleetBot
    value: ValueT | None = None
    error: Exception | None = None


@dataclass(frozen=True, slots=True)
class FleetOperationReport[ValueT]:
    outcomes: tuple[FleetOperationOutcome[ValueT], ...]

    @property
    def fulfilled(self) -> tuple[FleetOperationOutcome[ValueT], ...]:
        return tuple(outcome for outcome in self.outcomes if outcome.status == "fulfilled")

    @property
    def rejected(self) -> tuple[FleetOperationOutcome[ValueT], ...]:
        return tuple(outcome for outcome in self.outcomes if outcome.status == "rejected")


class FleetTaskGroupError[ResultT: Message](TaggedError):
    """A fleet task failed. The report contains each task's outcome."""

    def __init__(self, report: FleetTaskReport[ResultT]) -> None:
        super().__init__(f"{len(report.rejected)} of {len(report.outcomes)} fleet tasks failed")
        self.report = report


@dataclass(frozen=True, slots=True, kw_only=True)
class FleetTaskStartOptions:
    concurrency: int = 8
    conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED
    reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED
    disconnect_policy: BotTaskDisconnectPolicy = BOT_TASK_DISCONNECT_POLICY_UNSPECIFIED
    priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED
    deadline: datetime | None = None
    parent_task_id: str | None = None
    causation_id: str | None = None
    idempotency_key: str | None = None
    headers: Headers = None
    timeout_ms: int | None = None


class SoulFireFleetTaskGroup[ResultT: Message]:
    def __init__(
        self,
        members: Iterable[FleetTaskMember[ResultT]],
        start_failures: Iterable[FleetTaskStartFailure],
    ) -> None:
        self.members = tuple(members)
        self.start_failures = tuple(start_failures)

    @property
    def size(self) -> int:
        return len(self.members) + len(self.start_failures)

    def events(
        self,
        *,
        after_revision: int | None = None,
        headers: Headers = None,
        timeout_ms: int | None = None,
        buffer_size: int = 64,
    ) -> Stream[FleetTaskStreamEvent, SoulFireOperationError]:
        return Stream.merge(
            *(
                member.task.events(
                    after_revision=after_revision, headers=headers, timeout_ms=timeout_ms
                ).map(lambda event, member=member: FleetTaskStreamEvent(member.bot, event))
                for member in self.members
            ),
            buffer_size=buffer_size,
        )

    @fn("SoulFireFleetTaskGroup.results")
    def results(
        self, *, concurrency: int = 8, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[FleetTaskReport[ResultT], SoulFireOperationError]:
        limit = yield from validate(lambda: _normalize_concurrency(concurrency))

        def wait(member: FleetTaskMember[ResultT]) -> Effect[FleetTaskOutcome[ResultT], Never]:
            return (
                member.task.result(headers=headers, timeout_ms=timeout_ms)
                .map(
                    lambda value: FleetTaskOutcome(status="fulfilled", bot=member.bot, value=value)
                )
                .catch_all(
                    lambda error: succeed(
                        FleetTaskOutcome[ResultT](status="rejected", bot=member.bot, error=error)
                    )
                )
            )

        outcomes = yield from parallel((wait(member) for member in self.members), concurrency=limit)
        failures = tuple(
            FleetTaskOutcome[ResultT](status="rejected", bot=failure.bot, error=failure.error)
            for failure in self.start_failures
        )
        return FleetTaskReport((*failures, *outcomes))

    @fn("SoulFireFleetTaskGroup.require_results")
    def require_results(
        self, *, concurrency: int = 8, headers: Headers = None, timeout_ms: int | None = None
    ) -> EffectGen[tuple[ResultT, ...], SoulFireOperationError | FleetTaskGroupError[ResultT]]:
        report = yield from self.results(
            concurrency=concurrency, headers=headers, timeout_ms=timeout_ms
        )
        if report.rejected:
            return (yield from fail(FleetTaskGroupError(report)))
        return tuple(outcome.value for outcome in report.fulfilled if outcome.value is not None)

    @fn("SoulFireFleetTaskGroup.cancel")
    def cancel(
        self,
        reason: str = "",
        *,
        concurrency: int = 8,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[FleetOperationReport[BotTask], SoulFireOperationError]:
        limit = yield from validate(lambda: _normalize_concurrency(concurrency))

        def cancel(
            member: FleetTaskMember[ResultT],
        ) -> Effect[FleetOperationOutcome[BotTask], Never]:
            return (
                member.task.cancel(reason, headers=headers, timeout_ms=timeout_ms)
                .map(
                    lambda value: FleetOperationOutcome(
                        status="fulfilled", bot=member.bot, value=value
                    )
                )
                .catch_all(
                    lambda error: succeed(
                        FleetOperationOutcome[BotTask](
                            status="rejected", bot=member.bot, error=error
                        )
                    )
                )
            )

        outcomes = yield from parallel(
            (cancel(member) for member in self.members), concurrency=limit
        )
        return FleetOperationReport(outcomes)


class SoulFireFleet:
    def __init__(self, instance: SoulFireInstance, capabilities: CapabilitySet | None) -> None:
        self._instance = instance
        self._capabilities = capabilities

    @fn("SoulFireFleet.select")
    def select(
        self,
        selector: FleetSelector | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[tuple[FleetBot, ...], SoulFireOperationError]:
        selected = selector or FleetSelector()
        yield from validate(lambda: _require_capabilities(selected, self._capabilities))
        entries = yield from self._instance.bots(headers=headers, timeout_ms=timeout_ms)
        info = yield from self._instance.info(headers=headers, timeout_ms=timeout_ms)
        bots = _descriptors(entries, info.config.accounts)
        bots = [bot for bot in bots if _matches_selector(bot, selected)]
        predicate = selected.predicate
        if predicate is not None:
            kept: list[FleetBot] = []
            for bot in bots:
                decision = predicate(bot)
                if decision if isinstance(decision, bool) else (yield from decision):
                    kept.append(bot)
            bots = kept
        return (yield from validate(lambda: _ordered_limited(bots, selected)))

    @fn("SoulFireFleet.start")
    def start(
        self,
        selector: FleetSelector | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        bots = yield from self.select(selector, headers=headers, timeout_ms=timeout_ms)
        return (
            yield from self._instance.start(
                bot_ids=[bot.id for bot in bots], headers=headers, timeout_ms=timeout_ms
            )
        )

    @fn("SoulFireFleet.stop")
    def stop(
        self,
        selector: FleetSelector | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        bots = yield from self.select(selector, headers=headers, timeout_ms=timeout_ms)
        return (
            yield from self._instance.stop(
                bot_ids=[bot.id for bot in bots], headers=headers, timeout_ms=timeout_ms
            )
        )

    @fn("SoulFireFleet.restart")
    def restart(
        self,
        selector: FleetSelector | None = None,
        *,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotStatus], SoulFireOperationError]:
        bots = yield from self.select(selector, headers=headers, timeout_ms=timeout_ms)
        return (
            yield from self._instance.restart(
                bot_ids=[bot.id for bot in bots], headers=headers, timeout_ms=timeout_ms
            )
        )

    @fn("SoulFireFleet.start_tasks")
    def start_tasks[ResultT: Message](
        self,
        selector: FleetSelector,
        task_input: Message
        | Callable[[FleetBot, int, int], Message | Effect[Message, SoulFireOperationError]],
        result_type: type[ResultT],
        *,
        options: FleetTaskStartOptions | None = None,
    ) -> EffectGen[SoulFireFleetTaskGroup[ResultT], SoulFireOperationError]:
        settings = options or FleetTaskStartOptions()
        limit = yield from validate(lambda: _normalize_concurrency(settings.concurrency))
        bots = yield from self.select(
            selector, headers=settings.headers, timeout_ms=settings.timeout_ms
        )

        @fn("SoulFireFleet.start_one")
        def start_one(
            index: int, descriptor: FleetBot
        ) -> EffectGen[FleetTaskMember[ResultT], SoulFireOperationError, Scope]:
            generated = yield from validate(
                lambda: (
                    task_input(descriptor, index, len(bots)) if callable(task_input) else task_input
                )
            )
            resolved = (yield from generated) if isinstance(generated, Effect) else generated
            task = yield from acquire_release(
                self._instance.bot(descriptor.id).tasks.start(
                    resolved,
                    result_type,
                    conflict_policy=settings.conflict_policy,
                    reconnect_policy=settings.reconnect_policy,
                    disconnect_policy=settings.disconnect_policy,
                    priority=settings.priority,
                    deadline=settings.deadline,
                    parent_task_id=settings.parent_task_id,
                    causation_id=settings.causation_id,
                    idempotency_key=_fleet_idempotency_key(settings.idempotency_key, descriptor.id),
                    headers=settings.headers,
                    timeout_ms=settings.timeout_ms,
                ),
                lambda task, exit: (
                    succeed(None)
                    if isinstance(exit, Success)
                    else task.cancel(
                        "fleet task start cancelled",
                        headers=settings.headers,
                        timeout_ms=settings.timeout_ms,
                    ).or_die()
                ),
            )
            return FleetTaskMember(descriptor, task)

        @gen
        def start_all() -> EffectGen[
            SoulFireFleetTaskGroup[ResultT], SoulFireOperationError, Scope
        ]:
            def settled(
                index: int, bot: FleetBot
            ) -> Effect[FleetTaskMember[ResultT] | FleetTaskStartFailure, Never, Scope]:
                return start_one(index, bot).catch_all(
                    lambda error: succeed(FleetTaskStartFailure(bot, error))
                )

            outcomes = yield from parallel(
                (settled(index, bot) for index, bot in enumerate(bots)), concurrency=limit
            )
            return SoulFireFleetTaskGroup(
                (outcome for outcome in outcomes if isinstance(outcome, FleetTaskMember)),
                (outcome for outcome in outcomes if isinstance(outcome, FleetTaskStartFailure)),
            )

        return (yield from scoped(start_all))

    @fn("SoulFireFleet.distribute")
    def distribute[ItemT](
        self,
        items: Iterable[ItemT],
        selector: FleetSelector | None = None,
        *,
        strategy: Literal["round-robin", "contiguous"] = "round-robin",
        maximum_items_per_bot: int | None = None,
        require_all: bool = True,
        headers: Headers = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[tuple[FleetAssignment[ItemT], ...], SoulFireOperationError]:
        bots = yield from self.select(selector, headers=headers, timeout_ms=timeout_ms)
        return (
            yield from validate(
                lambda: _distribute(
                    tuple(items), bots, strategy, maximum_items_per_bot, require_all
                )
            )
        )


class _Unset:
    __slots__ = ()


_UNSET = _Unset()


def _descriptors(
    entries: Iterable[BotListEntry], accounts: Iterable[MinecraftAccountProto]
) -> list[FleetBot]:
    indexed = {account.profile_id: account for account in accounts}
    return [
        FleetBot(
            id=entry.profile_id,
            entry=entry,
            account=(account := indexed.get(entry.profile_id)),
            metadata=_metadata(account.persistent_metadata if account is not None else ()),
        )
        for entry in entries
    ]


def _metadata(namespaces: Iterable[SettingsNamespace]) -> Mapping[str, Mapping[str, object]]:
    outer: dict[str, Mapping[str, object]] = {}
    for namespace in namespaces:
        outer[namespace.namespace] = MappingProxyType(
            {entry.key: _value_to_python(entry.value) for entry in namespace.entries}
        )
    return MappingProxyType(outer)


def _value_to_python(value: Value) -> object:
    match value.WhichOneof("kind"):
        case "null_value":
            return None
        case "number_value":
            return value.number_value
        case "string_value":
            return value.string_value
        case "bool_value":
            return value.bool_value
        case "struct_value":
            return {
                key: _value_to_python(child) for key, child in value.struct_value.fields.items()
            }
        case "list_value":
            return [_value_to_python(child) for child in value.list_value.values]
        case _:
            return None


def _matches_selector(bot: FleetBot, selector: FleetSelector) -> bool:
    entry = bot.entry
    live = entry.live_state if entry.HasField("live_state") else None
    if selector.bot_ids and bot.id not in selector.bot_ids:
        return False
    if selector.account_names:
        account_name = (
            entry.account_name
            if entry.HasField("account_name")
            else bot.account.last_known_name
            if bot.account is not None
            else ""
        )
        if account_name.casefold() not in {name.casefold() for name in selector.account_names}:
            return False
    if selector.account_types and (
        bot.account is None or bot.account.type not in selector.account_types
    ):
        return False
    if selector.online is not None and entry.is_online is not selector.online:
        return False
    if selector.desired_states and entry.status.desired_state not in selector.desired_states:
        return False
    if selector.runtime_states and entry.status.runtime_state not in selector.runtime_states:
        return False
    if selector.connection_phases and entry.connection_phase not in selector.connection_phases:
        return False
    if selector.dimensions and (live is None or live.dimension not in selector.dimensions):
        return False
    if selector.minimum_health is not None and (
        live is None or live.health < selector.minimum_health
    ):
        return False
    if selector.maximum_health is not None and (
        live is None or live.health > selector.maximum_health
    ):
        return False
    if selector.minimum_food_level is not None and (
        live is None or live.food_level < selector.minimum_food_level
    ):
        return False
    if selector.maximum_ping_ms is not None and (
        not entry.HasField("ping_ms") or entry.ping_ms > selector.maximum_ping_ms
    ):
        return False
    if selector.near is not None and (
        live is None
        or (selector.near.dimension is not None and live.dimension != selector.near.dimension)
        or _distance_squared(live.x, live.y, live.z, selector.near) > selector.near.radius**2
    ):
        return False
    return all(_matches_metadata(bot, condition) for condition in selector.metadata)


def _matches_metadata(bot: FleetBot, selector: FleetMetadataSelector) -> bool:
    namespace = bot.metadata.get(selector.namespace)
    present = namespace is not None and selector.key in namespace
    if selector.exists is not None and present is not selector.exists:
        return False
    if selector.equals is not _UNSET:
        return present and namespace is not None and (namespace[selector.key] == selector.equals)
    return selector.exists is False or present


def _ordered_limited(bots: list[FleetBot], selector: FleetSelector) -> tuple[FleetBot, ...]:
    order = selector.order_by
    if callable(order):
        bots.sort(key=order)
    elif order == "name":
        bots.sort(
            key=lambda bot: (
                bot.entry.account_name
                if bot.entry.HasField("account_name")
                else bot.account.last_known_name
                if bot.account is not None
                else ""
            ).casefold()
        )
    elif order == "health":
        bots.sort(
            key=lambda bot: (
                bot.entry.live_state.health if bot.entry.HasField("live_state") else -math.inf
            ),
            reverse=True,
        )
    elif order == "distance":
        near = selector.near
        if near is None:
            raise ValueError("order_by='distance' requires a near selector")
        bots.sort(
            key=lambda bot: (
                _distance_squared(
                    bot.entry.live_state.x, bot.entry.live_state.y, bot.entry.live_state.z, near
                )
                if bot.entry.HasField("live_state")
                else math.inf
            )
        )
    elif order == "random":
        random.shuffle(bots)
    if selector.limit is not None:
        if selector.limit < 0:
            raise ValueError("limit must be non-negative")
        bots = bots[: selector.limit]
    return tuple(bots)


def _distribute[ItemT](
    items: tuple[ItemT, ...],
    bots: tuple[FleetBot, ...],
    strategy: Literal["round-robin", "contiguous"],
    maximum_items_per_bot: int | None,
    require_all: bool,
) -> tuple[FleetAssignment[ItemT], ...]:
    if items and (not bots):
        raise ValueError("No bots matched the fleet selector")
    maximum = math.inf if maximum_items_per_bot is None else maximum_items_per_bot
    if maximum < 0:
        raise ValueError("maximum_items_per_bot must be non-negative")
    buckets: list[list[ItemT]] = [[] for _bot in bots]
    if strategy == "contiguous":
        offset = 0
        for index in range(len(bots)):
            remaining_bots = len(bots) - index
            size = min(maximum, math.ceil((len(items) - offset) / remaining_bots))
            integer_size = int(size)
            buckets[index].extend(items[offset : offset + integer_size])
            offset += integer_size
    else:
        bot_index = 0
        for item in items:
            while bot_index < len(bots) and len(buckets[bot_index]) >= maximum:
                bot_index += 1
            if bot_index >= len(bots):
                break
            buckets[bot_index].append(item)
            bot_index = (bot_index + 1) % len(bots)
    assigned = sum(map(len, buckets))
    if require_all and assigned != len(items):
        raise ValueError(f"Fleet capacity {assigned} is smaller than {len(items)} items")
    return tuple((FleetAssignment(bot, tuple(buckets[index])) for index, bot in enumerate(bots)))


def _require_capabilities(selector: FleetSelector, capabilities: CapabilitySet | None) -> None:
    if selector.required_capabilities and capabilities is None:
        raise RuntimeError("Fleet capability selection requires a negotiated SoulFire connection")
    if capabilities is not None:
        for capability in selector.required_capabilities:
            capabilities.require(capability)


def _distance_squared(x: float, y: float, z: float, target: FleetPoint) -> float:
    return (x - target.x) ** 2 + (y - target.y) ** 2 + (z - target.z) ** 2


def _normalize_concurrency(value: int) -> int:
    if value < 1:
        raise ValueError("concurrency must be at least 1")
    return value


def _fleet_idempotency_key(prefix: str | None, bot_id: str) -> str | None:
    return None if prefix is None else f"{prefix}:{bot_id}"
