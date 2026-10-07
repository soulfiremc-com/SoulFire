from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from math import isfinite
from typing import NotRequired, Protocol, TypedDict

from effect_py import EffectGen, fail, fn, sync
from google.protobuf.any_pb2 import Any as AnyMessage
from google.protobuf.message import Message

from .bot_live_pb2 import PathfindGoal, PathfindOptions
from .common_pb2 import BlockPosition
from .domain_pb2 import EntityReference
from .errors import SoulFireOperationError, SoulFireTaskError, operation_error
from .inventory_pb2 import ItemSelector
from .recipe_pb2 import (
    BrewTask,
    BrewTaskResult,
    CraftTask,
    CraftTaskResult,
    SmeltTask,
    SmeltTaskResult,
    VillagerTradeTask,
    VillagerTradeTaskResult,
)
from .selectors import block_selectors
from .streams import Stream
from .task_connect import BotTaskServiceClient
from .task_pb2 import (
    BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
    BOT_TASK_DISCONNECT_POLICY_CANCEL_WITH_CALL,
    BOT_TASK_DISCONNECT_POLICY_UNSPECIFIED,
    BOT_TASK_PRIORITY_UNSPECIFIED,
    BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
    BOT_TASK_STATUS_CANCELLED,
    BOT_TASK_STATUS_COMPLETED,
    BOT_TASK_STATUS_FAILED,
    BOT_TASK_STATUS_TIMED_OUT,
    BUILD_MIRROR_NONE,
    BUILD_ROTATION_NONE,
    CONTAINER_TRANSFER_DIRECTION_DEPOSIT,
    CONTAINER_TRANSFER_DIRECTION_WITHDRAW,
    AttackEntityTask,
    AttackEntityTaskResult,
    AttackNearestTask,
    AttackNearestTaskResult,
    AutoArmorTask,
    AutoArmorTaskResult,
    AutoEatTask,
    AutoEatTaskResult,
    AutoRespawnTask,
    AutoRespawnTaskResult,
    AutoTotemTask,
    AutoTotemTaskResult,
    BotTask,
    BotTaskConflictPolicy,
    BotTaskDisconnectPolicy,
    BotTaskEvent,
    BotTaskPriority,
    BotTaskReconnectPolicy,
    BotTaskStatus,
    BreedTask,
    BreedTaskResult,
    BuildBlock,
    BuildMaterialSubstitution,
    BuildMirror,
    BuildOffset,
    BuildRotation,
    BuildTask,
    BuildTaskResult,
    CancelBotTaskRequest,
    CollectBlocksTask,
    CollectBlocksTaskResult,
    ContainerTransferDirection,
    ContainerTransferOperation,
    ContainerTransferTask,
    ContainerTransferTaskResult,
    ExcavateTask,
    ExcavateTaskResult,
    ExploreTask,
    ExploreTaskResult,
    FarmTask,
    FarmTaskResult,
    FishTask,
    FishTaskResult,
    FleeTask,
    FleeTaskResult,
    FollowEntityTask,
    FollowEntityTaskResult,
    GetBotTaskRequest,
    GoToTask,
    GoToTaskResult,
    GuardTask,
    GuardTaskResult,
    ListBotTasksRequest,
    LoadoutRequirement,
    MaintainLoadoutTask,
    MaintainLoadoutTaskResult,
    RangedAttackTask,
    RangedAttackTaskResult,
    SleepTask,
    SleepTaskResult,
    StartBotTaskRequest,
    WatchBotTaskRequest,
    WatchBotTasksRequest,
)
from .transport import rpc, rpc_stream, validate
from .world_pb2 import EntitySelector, IntRange

type HeaderFactory = Callable[[dict[str, str] | None], dict[str, str] | None]


class TaskStartOptions(TypedDict):
    """Scheduling, lifecycle, and request options for a bot task."""

    conflict_policy: NotRequired[BotTaskConflictPolicy]
    reconnect_policy: NotRequired[BotTaskReconnectPolicy]
    disconnect_policy: NotRequired[BotTaskDisconnectPolicy]
    priority: NotRequired[BotTaskPriority]
    deadline: NotRequired[datetime | None]
    parent_task_id: NotRequired[str | None]
    causation_id: NotRequired[str | None]
    idempotency_key: NotRequired[str | None]
    headers: NotRequired[dict[str, str] | None]
    timeout_ms: NotRequired[int | None]


class EntityTargetLike(Protocol):
    @property
    def network_id(self) -> int: ...

    @property
    def connection_epoch(self) -> str: ...


type FollowEntityTarget = EntityTargetLike | int
type AttackEntityTarget = EntityTargetLike | int


@dataclass(frozen=True, slots=True)
class ContainerTransferSpec:
    selector: ItemSelector
    count: int
    allow_partial: bool = False


@dataclass(frozen=True, slots=True)
class LoadoutRequirementSpec:
    selector: ItemSelector
    minimum_count: int
    target_count: int
    maximum_count: int = 0


@dataclass(frozen=True, slots=True)
class SchematicBlock:
    x: int
    y: int
    z: int
    block_id: str
    properties: Mapping[str, str] = field(default_factory=dict[str, str])


def is_terminal_task_status(status: BotTaskStatus) -> bool:
    return status in {
        BOT_TASK_STATUS_COMPLETED,
        BOT_TASK_STATUS_CANCELLED,
        BOT_TASK_STATUS_FAILED,
        BOT_TASK_STATUS_TIMED_OUT,
    }


class SoulFireTask[ResultT: Message]:
    def __init__(
        self,
        client: BotTaskServiceClient,
        snapshot: BotTask,
        result_type: type[ResultT],
        header_factory: HeaderFactory,
    ) -> None:
        self._client = client
        self._snapshot = snapshot
        self._result_type = result_type
        self._header_factory = header_factory

    @property
    def id(self) -> str:
        return self._snapshot.task_id

    @property
    def snapshot(self) -> BotTask:
        return self._snapshot

    @property
    def terminal(self) -> bool:
        return is_terminal_task_status(self._snapshot.status)

    @fn("SoulFireTask.refresh")
    def refresh(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[BotTask, SoulFireOperationError]:
        self._snapshot = yield from rpc(
            "SoulFireTask.refresh",
            lambda: self._client.get_bot_task(
                GetBotTaskRequest(task_id=self.id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        return self._snapshot

    def events(
        self,
        *,
        after_revision: int | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireTask.events",
            lambda: self._client.watch_bot_task(
                WatchBotTaskRequest(
                    task_id=self.id,
                    after_revision=self._snapshot.revision
                    if after_revision is None
                    else after_revision,
                    follow=True,
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireTask.wait")
    def wait(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[BotTask, SoulFireOperationError]:
        if self.terminal:
            return self._snapshot

        def update(event: BotTaskEvent) -> None:
            if event.HasField("task"):
                self._snapshot = event.task

        yield from self.events(headers=headers, timeout_ms=timeout_ms).run_for_each(
            lambda event: sync(lambda: update(event))
        )
        if not self.terminal:
            yield from self.refresh(headers=headers, timeout_ms=timeout_ms)
        return self._snapshot

    @fn("SoulFireTask.cancel")
    def cancel(
        self,
        reason: str = "",
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotTask, SoulFireOperationError]:
        self._snapshot = yield from rpc(
            "SoulFireTask.cancel",
            lambda: self._client.cancel_bot_task(
                CancelBotTaskRequest(task_id=self.id, reason=reason),
                headers=self._header_factory(headers),
                timeout_ms=timeout_ms,
            ),
        )
        return self._snapshot

    @fn("SoulFireTask.result")
    def result(
        self, *, headers: dict[str, str] | None = None, timeout_ms: int | None = None
    ) -> EffectGen[ResultT, SoulFireOperationError]:
        task = yield from self.wait(headers=headers, timeout_ms=timeout_ms)
        if task.status != BOT_TASK_STATUS_COMPLETED or not task.HasField("result"):
            return (
                yield from fail(operation_error("SoulFireTask.result", SoulFireTaskError(task)))
            )
        result = self._result_type()
        if not task.result.Unpack(result):
            return (
                yield from fail(
                    operation_error(
                        "SoulFireTask.result",
                        SoulFireTaskError(_result_type_failure(task, result.DESCRIPTOR.full_name)),
                    )
                )
            )
        return result


class SoulFireTasks:
    def __init__(
        self,
        instance_id: str,
        bot_id: str,
        client: BotTaskServiceClient,
        header_factory: HeaderFactory,
    ) -> None:
        self._instance_id = instance_id
        self._bot_id = bot_id
        self._client = client
        self._header_factory = header_factory

    @fn("SoulFireTasks.start")
    def start[ResultT: Message](
        self,
        task_input: Message,
        result_type: type[ResultT],
        *,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        disconnect_policy: BotTaskDisconnectPolicy = BOT_TASK_DISCONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        parent_task_id: str | None = None,
        causation_id: str | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ResultT], SoulFireOperationError]:
        packed = AnyMessage()
        packed.Pack(task_input)
        request = yield from validate(
            lambda: _start_request(
                instance_id=self._instance_id,
                bot_id=self._bot_id,
                input=packed,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                disconnect_policy=disconnect_policy,
                priority=priority,
                deadline=deadline,
                parent_task_id=parent_task_id,
                causation_id=causation_id,
                idempotency_key=idempotency_key,
            )
        )
        task = yield from rpc(
            "SoulFireTasks.start",
            lambda: self._client.start_bot_task(
                request, headers=self._header_factory(headers), timeout_ms=timeout_ms
            ),
        )
        return SoulFireTask(self._client, task, result_type, self._header_factory)

    def run(
        self,
        task_input: Message,
        *,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        disconnect_policy: BotTaskDisconnectPolicy = BOT_TASK_DISCONNECT_POLICY_CANCEL_WITH_CALL,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        parent_task_id: str | None = None,
        causation_id: str | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        packed = AnyMessage()
        packed.Pack(task_input)
        return rpc_stream(
            "SoulFireTasks.run",
            lambda: self._client.run_bot_task(
                _start_request(
                    instance_id=self._instance_id,
                    bot_id=self._bot_id,
                    input=packed,
                    conflict_policy=conflict_policy,
                    reconnect_policy=reconnect_policy,
                    disconnect_policy=disconnect_policy,
                    priority=priority,
                    deadline=deadline,
                    parent_task_id=parent_task_id,
                    causation_id=causation_id,
                    idempotency_key=idempotency_key,
                ),
                headers=self._header_factory(headers),
                timeout_ms=timeout_ms,
            ),
        )

    def run_go_to(
        self,
        goal: PathfindGoal,
        *,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            GoToTask(goal=goal, **{} if options is None else {"options": options}),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.go_to")
    def go_to(
        self,
        goal: PathfindGoal,
        *,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[GoToTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                GoToTask(goal=goal, **{} if options is None else {"options": options}),
                GoToTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_follow_entity(
        self,
        target: FollowEntityTarget,
        *,
        distance: float = 3,
        options: PathfindOptions | None = None,
        target_unavailable_timeout_seconds: int = 0,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _follow_entity_task(target, distance, options, target_unavailable_timeout_seconds),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.follow_entity")
    def follow_entity(
        self,
        target: FollowEntityTarget,
        *,
        distance: float = 3,
        options: PathfindOptions | None = None,
        target_unavailable_timeout_seconds: int = 0,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[FollowEntityTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _follow_entity_task(
                            target, distance, options, target_unavailable_timeout_seconds
                        )
                    )
                ),
                FollowEntityTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_attack_entity(
        self,
        target: AttackEntityTarget,
        *,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        options: PathfindOptions | None = None,
        target_unavailable_timeout_seconds: int = 0,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        use_offhand_shield: bool = False,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _attack_entity_task(
                target,
                attack_range,
                sprinting,
                maximum_attacks,
                options,
                target_unavailable_timeout_seconds,
                select_best_weapon,
                weapon,
                restore_selected_slot,
                use_offhand_shield,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.attack_entity")
    def attack_entity(
        self,
        target: AttackEntityTarget,
        *,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        options: PathfindOptions | None = None,
        target_unavailable_timeout_seconds: int = 0,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        use_offhand_shield: bool = False,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AttackEntityTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _attack_entity_task(
                            target,
                            attack_range,
                            sprinting,
                            maximum_attacks,
                            options,
                            target_unavailable_timeout_seconds,
                            select_best_weapon,
                            weapon,
                            restore_selected_slot,
                            use_offhand_shield,
                        )
                    )
                ),
                AttackEntityTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_attack_nearest(
        self,
        selector: EntitySelector,
        *,
        radius: float = 32,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 0,
        no_target_timeout_seconds: int = 0,
        complete_when_no_target: bool = False,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _attack_nearest_task(
                selector,
                radius,
                attack_range,
                sprinting,
                maximum_attacks,
                maximum_targets,
                no_target_timeout_seconds,
                complete_when_no_target,
                select_best_weapon,
                weapon,
                restore_selected_slot,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.attack_nearest")
    def attack_nearest(
        self,
        selector: EntitySelector,
        *,
        radius: float = 32,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 1,
        no_target_timeout_seconds: int = 0,
        complete_when_no_target: bool = True,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AttackNearestTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _attack_nearest_task(
                            selector,
                            radius,
                            attack_range,
                            sprinting,
                            maximum_attacks,
                            maximum_targets,
                            no_target_timeout_seconds,
                            complete_when_no_target,
                            select_best_weapon,
                            weapon,
                            restore_selected_slot,
                            options,
                        )
                    )
                ),
                AttackNearestTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_ranged_attack(
        self,
        target: AttackEntityTarget,
        *,
        minimum_range: float = 8,
        maximum_range: float = 24,
        maximum_shots: int = 0,
        target_unavailable_timeout_seconds: int = 10,
        weapon: ItemSelector | None = None,
        bow_draw_ticks: int = 20,
        lead_target: bool = True,
        compensate_gravity: bool = True,
        strafe: bool = True,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _ranged_attack_task(
                target,
                minimum_range,
                maximum_range,
                maximum_shots,
                target_unavailable_timeout_seconds,
                weapon,
                bow_draw_ticks,
                lead_target,
                compensate_gravity,
                strafe,
                restore_selected_slot,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.ranged_attack")
    def ranged_attack(
        self,
        target: AttackEntityTarget,
        *,
        minimum_range: float = 8,
        maximum_range: float = 24,
        maximum_shots: int = 0,
        target_unavailable_timeout_seconds: int = 10,
        weapon: ItemSelector | None = None,
        bow_draw_ticks: int = 20,
        lead_target: bool = True,
        compensate_gravity: bool = True,
        strafe: bool = True,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[RangedAttackTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _ranged_attack_task(
                            target,
                            minimum_range,
                            maximum_range,
                            maximum_shots,
                            target_unavailable_timeout_seconds,
                            weapon,
                            bow_draw_ticks,
                            lead_target,
                            compensate_gravity,
                            strafe,
                            restore_selected_slot,
                            options,
                        )
                    )
                ),
                RangedAttackTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_flee(
        self,
        threats: EntitySelector,
        *,
        trigger_radius: float = 8,
        safe_distance: float = 16,
        safe_seconds: int = 2,
        complete_when_safe: bool = False,
        maximum_escapes: int = 0,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _flee_task(
                threats,
                trigger_radius,
                safe_distance,
                safe_seconds,
                complete_when_safe,
                maximum_escapes,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.flee")
    def flee(
        self,
        threats: EntitySelector,
        *,
        trigger_radius: float = 8,
        safe_distance: float = 16,
        safe_seconds: int = 2,
        complete_when_safe: bool = True,
        maximum_escapes: int = 0,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[FleeTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _flee_task(
                            threats,
                            trigger_radius,
                            safe_distance,
                            safe_seconds,
                            complete_when_safe,
                            maximum_escapes,
                            options,
                        )
                    )
                ),
                FleeTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_guard(
        self,
        position: BlockPosition,
        threats: EntitySelector,
        *,
        guard_radius: float = 16,
        maximum_pursuit_distance: float = 24,
        return_radius: float = 3,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 0,
        complete_when_clear: bool = False,
        clear_seconds: int = 3,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _guard_task(
                position,
                None,
                threats,
                guard_radius,
                maximum_pursuit_distance,
                return_radius,
                attack_range,
                sprinting,
                maximum_attacks,
                maximum_targets,
                complete_when_clear,
                clear_seconds,
                select_best_weapon,
                weapon,
                restore_selected_slot,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.guard")
    def guard(
        self,
        position: BlockPosition,
        threats: EntitySelector,
        *,
        guard_radius: float = 16,
        maximum_pursuit_distance: float = 24,
        return_radius: float = 3,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 0,
        complete_when_clear: bool = True,
        clear_seconds: int = 3,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[GuardTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _guard_task(
                            position,
                            None,
                            threats,
                            guard_radius,
                            maximum_pursuit_distance,
                            return_radius,
                            attack_range,
                            sprinting,
                            maximum_attacks,
                            maximum_targets,
                            complete_when_clear,
                            clear_seconds,
                            select_best_weapon,
                            weapon,
                            restore_selected_slot,
                            options,
                        )
                    )
                ),
                GuardTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_protect(
        self,
        entity: AttackEntityTarget,
        threats: EntitySelector,
        *,
        guard_radius: float = 16,
        maximum_pursuit_distance: float = 24,
        return_radius: float = 3,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 0,
        complete_when_clear: bool = False,
        clear_seconds: int = 3,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _guard_task(
                None,
                entity,
                threats,
                guard_radius,
                maximum_pursuit_distance,
                return_radius,
                attack_range,
                sprinting,
                maximum_attacks,
                maximum_targets,
                complete_when_clear,
                clear_seconds,
                select_best_weapon,
                weapon,
                restore_selected_slot,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.protect")
    def protect(
        self,
        entity: AttackEntityTarget,
        threats: EntitySelector,
        *,
        guard_radius: float = 16,
        maximum_pursuit_distance: float = 24,
        return_radius: float = 3,
        attack_range: float = 3,
        sprinting: bool = False,
        maximum_attacks: int = 0,
        maximum_targets: int = 0,
        complete_when_clear: bool = True,
        clear_seconds: int = 3,
        select_best_weapon: bool = True,
        weapon: ItemSelector | None = None,
        restore_selected_slot: bool = True,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[GuardTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _guard_task(
                            None,
                            entity,
                            threats,
                            guard_radius,
                            maximum_pursuit_distance,
                            return_radius,
                            attack_range,
                            sprinting,
                            maximum_attacks,
                            maximum_targets,
                            complete_when_clear,
                            clear_seconds,
                            select_best_weapon,
                            weapon,
                            restore_selected_slot,
                            options,
                        )
                    )
                ),
                GuardTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_sleep(
        self,
        bed: BlockPosition | None = None,
        *,
        search_radius: int = 24,
        wait_until_possible: bool = True,
        retry_interval_ticks: int = 20,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _sleep_task(bed, search_radius, wait_until_possible, retry_interval_ticks, options),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.sleep")
    def sleep(
        self,
        bed: BlockPosition | None = None,
        *,
        search_radius: int = 24,
        wait_until_possible: bool = False,
        retry_interval_ticks: int = 20,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[SleepTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _sleep_task(
                            bed, search_radius, wait_until_possible, retry_interval_ticks, options
                        )
                    )
                ),
                SleepTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_fish(
        self,
        *,
        maximum_catches: int = 0,
        maximum_failed_casts: int = 0,
        rod: ItemSelector | None = None,
        cast_timeout_ticks: int = 100,
        bite_timeout_ticks: int = 12000,
        complete_when_no_rod: bool = False,
        restore_selected_slot: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _fish_task(
                maximum_catches,
                maximum_failed_casts,
                rod,
                cast_timeout_ticks,
                bite_timeout_ticks,
                complete_when_no_rod,
                restore_selected_slot,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.fish")
    def fish(
        self,
        *,
        maximum_catches: int = 1,
        maximum_failed_casts: int = 0,
        rod: ItemSelector | None = None,
        cast_timeout_ticks: int = 100,
        bite_timeout_ticks: int = 12000,
        complete_when_no_rod: bool = True,
        restore_selected_slot: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[FishTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _fish_task(
                            maximum_catches,
                            maximum_failed_casts,
                            rod,
                            cast_timeout_ticks,
                            bite_timeout_ticks,
                            complete_when_no_rod,
                            restore_selected_slot,
                        )
                    )
                ),
                FishTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_farm(
        self,
        crop_ids: Iterable[str] = (),
        *,
        center: BlockPosition | None = None,
        radius: int = 24,
        maximum_harvests: int = 0,
        replant: bool = True,
        complete_when_no_mature_crops: bool = False,
        options: PathfindOptions | None = None,
        rescan_interval_ticks: int = 100,
        restore_selected_slot: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _farm_task(
                crop_ids,
                center,
                radius,
                maximum_harvests,
                replant,
                complete_when_no_mature_crops,
                options,
                rescan_interval_ticks,
                restore_selected_slot,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.farm")
    def farm(
        self,
        crop_ids: Iterable[str] = (),
        *,
        center: BlockPosition | None = None,
        radius: int = 24,
        maximum_harvests: int = 1,
        replant: bool = True,
        complete_when_no_mature_crops: bool = True,
        options: PathfindOptions | None = None,
        rescan_interval_ticks: int = 100,
        restore_selected_slot: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[FarmTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _farm_task(
                            crop_ids,
                            center,
                            radius,
                            maximum_harvests,
                            replant,
                            complete_when_no_mature_crops,
                            options,
                            rescan_interval_ticks,
                            restore_selected_slot,
                        )
                    )
                ),
                FarmTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_breed(
        self,
        animals: EntitySelector | None = None,
        *,
        food: ItemSelector | None = None,
        center: BlockPosition | None = None,
        radius: int = 24,
        maximum_pairs: int = 0,
        complete_when_no_pair: bool = False,
        complete_when_no_food: bool = False,
        options: PathfindOptions | None = None,
        rescan_interval_ticks: int = 100,
        breeding_timeout_ticks: int = 100,
        restore_selected_slot: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _breed_task(
                animals,
                food,
                center,
                radius,
                maximum_pairs,
                complete_when_no_pair,
                complete_when_no_food,
                options,
                rescan_interval_ticks,
                breeding_timeout_ticks,
                restore_selected_slot,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.breed")
    def breed(
        self,
        animals: EntitySelector | None = None,
        *,
        food: ItemSelector | None = None,
        center: BlockPosition | None = None,
        radius: int = 24,
        maximum_pairs: int = 1,
        complete_when_no_pair: bool = True,
        complete_when_no_food: bool = True,
        options: PathfindOptions | None = None,
        rescan_interval_ticks: int = 100,
        breeding_timeout_ticks: int = 100,
        restore_selected_slot: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[BreedTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _breed_task(
                            animals,
                            food,
                            center,
                            radius,
                            maximum_pairs,
                            complete_when_no_pair,
                            complete_when_no_food,
                            options,
                            rescan_interval_ticks,
                            breeding_timeout_ticks,
                            restore_selected_slot,
                        )
                    )
                ),
                BreedTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_explore(
        self,
        *,
        origin: BlockPosition | None = None,
        radius: int = 256,
        waypoint_spacing: int = 64,
        maximum_waypoints: int = 0,
        options: PathfindOptions | None = None,
        return_to_origin: bool = False,
        purpose: str = "sdk-explore",
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _explore_task(
                origin,
                radius,
                waypoint_spacing,
                maximum_waypoints,
                options,
                return_to_origin,
                purpose,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.explore")
    def explore(
        self,
        *,
        origin: BlockPosition | None = None,
        radius: int = 256,
        waypoint_spacing: int = 64,
        maximum_waypoints: int = 1,
        options: PathfindOptions | None = None,
        return_to_origin: bool = False,
        purpose: str = "sdk-explore",
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ExploreTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _explore_task(
                            origin,
                            radius,
                            waypoint_spacing,
                            maximum_waypoints,
                            options,
                            return_to_origin,
                            purpose,
                        )
                    )
                ),
                ExploreTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_stash(
        self,
        container: BlockPosition,
        operations: Iterable[ContainerTransferSpec | ContainerTransferOperation],
        *,
        options: PathfindOptions | None = None,
        close_container: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _container_transfer_task(
                container,
                CONTAINER_TRANSFER_DIRECTION_DEPOSIT,
                operations,
                options,
                close_container,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.stash")
    def stash(
        self,
        container: BlockPosition,
        operations: Iterable[ContainerTransferSpec | ContainerTransferOperation],
        *,
        options: PathfindOptions | None = None,
        close_container: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ContainerTransferTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _container_transfer_task(
                            container,
                            CONTAINER_TRANSFER_DIRECTION_DEPOSIT,
                            operations,
                            options,
                            close_container,
                        )
                    )
                ),
                ContainerTransferTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_withdraw(
        self,
        container: BlockPosition,
        operations: Iterable[ContainerTransferSpec | ContainerTransferOperation],
        *,
        options: PathfindOptions | None = None,
        close_container: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _container_transfer_task(
                container,
                CONTAINER_TRANSFER_DIRECTION_WITHDRAW,
                operations,
                options,
                close_container,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.withdraw")
    def withdraw(
        self,
        container: BlockPosition,
        operations: Iterable[ContainerTransferSpec | ContainerTransferOperation],
        *,
        options: PathfindOptions | None = None,
        close_container: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ContainerTransferTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _container_transfer_task(
                            container,
                            CONTAINER_TRANSFER_DIRECTION_WITHDRAW,
                            operations,
                            options,
                            close_container,
                        )
                    )
                ),
                ContainerTransferTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_maintain_loadout(
        self,
        container: BlockPosition,
        requirements: Iterable[LoadoutRequirementSpec | LoadoutRequirement],
        *,
        options: PathfindOptions | None = None,
        check_interval_ticks: int = 100,
        maximum_rebalances: int = 0,
        complete_when_satisfied: bool = False,
        close_container: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _maintain_loadout_task(
                container,
                requirements,
                options,
                check_interval_ticks,
                maximum_rebalances,
                complete_when_satisfied,
                close_container,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.maintain_loadout")
    def maintain_loadout(
        self,
        container: BlockPosition,
        requirements: Iterable[LoadoutRequirementSpec | LoadoutRequirement],
        *,
        options: PathfindOptions | None = None,
        check_interval_ticks: int = 100,
        maximum_rebalances: int = 0,
        complete_when_satisfied: bool = False,
        close_container: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[MaintainLoadoutTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _maintain_loadout_task(
                            container,
                            requirements,
                            options,
                            check_interval_ticks,
                            maximum_rebalances,
                            complete_when_satisfied,
                            close_container,
                        )
                    )
                ),
                MaintainLoadoutTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    @fn("SoulFireTasks.balance_loadout")
    def balance_loadout(
        self,
        container: BlockPosition,
        requirements: Iterable[LoadoutRequirementSpec | LoadoutRequirement],
        *,
        options: PathfindOptions | None = None,
        check_interval_ticks: int = 100,
        close_container: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[MaintainLoadoutTaskResult], SoulFireOperationError]:
        return (
            yield from self.maintain_loadout(
                container,
                requirements,
                options=options,
                check_interval_ticks=check_interval_ticks,
                maximum_rebalances=1,
                complete_when_satisfied=True,
                close_container=close_container,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_auto_eat(
        self,
        food_item_ids: Iterable[str] = (),
        *,
        food_level: int = 14,
        check_interval_ticks: int = 20,
        maximum_meals: int = 0,
        complete_when_no_food: bool = False,
        restore_selected_slot: bool = True,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _auto_eat_task(
                food_item_ids,
                food_level,
                check_interval_ticks,
                maximum_meals,
                complete_when_no_food,
                restore_selected_slot,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.auto_eat")
    def auto_eat(
        self,
        food_item_ids: Iterable[str] = (),
        *,
        food_level: int = 14,
        check_interval_ticks: int = 20,
        maximum_meals: int = 0,
        complete_when_no_food: bool = False,
        restore_selected_slot: bool = True,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AutoEatTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _auto_eat_task(
                            food_item_ids,
                            food_level,
                            check_interval_ticks,
                            maximum_meals,
                            complete_when_no_food,
                            restore_selected_slot,
                        )
                    )
                ),
                AutoEatTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_auto_respawn(
        self,
        *,
        respawn_delay_ticks: int = 0,
        maximum_respawns: int = 0,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _auto_respawn_task(respawn_delay_ticks, maximum_respawns),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.auto_respawn")
    def auto_respawn(
        self,
        *,
        respawn_delay_ticks: int = 0,
        maximum_respawns: int = 0,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AutoRespawnTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _auto_respawn_task(respawn_delay_ticks, maximum_respawns)
                    )
                ),
                AutoRespawnTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_auto_totem(
        self,
        *,
        check_interval_ticks: int = 20,
        maximum_equips: int = 0,
        complete_when_no_totem: bool = False,
        replace_occupied_offhand: bool = False,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _auto_totem_task(
                check_interval_ticks,
                maximum_equips,
                complete_when_no_totem,
                replace_occupied_offhand,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.auto_totem")
    def auto_totem(
        self,
        *,
        check_interval_ticks: int = 20,
        maximum_equips: int = 0,
        complete_when_no_totem: bool = False,
        replace_occupied_offhand: bool = False,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AutoTotemTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _auto_totem_task(
                            check_interval_ticks,
                            maximum_equips,
                            complete_when_no_totem,
                            replace_occupied_offhand,
                        )
                    )
                ),
                AutoTotemTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_auto_armor(
        self,
        *,
        check_interval_ticks: int = 20,
        maximum_equips: int = 0,
        complete_when_no_upgrade: bool = False,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _auto_armor_task(check_interval_ticks, maximum_equips, complete_when_no_upgrade),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.auto_armor")
    def auto_armor(
        self,
        *,
        check_interval_ticks: int = 20,
        maximum_equips: int = 0,
        complete_when_no_upgrade: bool = False,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[AutoArmorTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _auto_armor_task(
                            check_interval_ticks, maximum_equips, complete_when_no_upgrade
                        )
                    )
                ),
                AutoArmorTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_collect_blocks(
        self,
        block_ids: str | Iterable[str] = (),
        *,
        tags: Iterable[str] = (),
        count: int = 1,
        search_radius: int = 32,
        avoid_submerged_targets: bool = False,
        require_line_of_sight: bool = False,
        target_y_range: IntRange | None = None,
        options: PathfindOptions | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _collect_blocks_task(
                block_ids,
                tags,
                count,
                search_radius,
                avoid_submerged_targets,
                require_line_of_sight,
                target_y_range,
                options,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.collect_blocks")
    def collect_blocks(
        self,
        block_ids: str | Iterable[str] = (),
        *,
        tags: Iterable[str] = (),
        count: int = 1,
        search_radius: int = 32,
        avoid_submerged_targets: bool = False,
        require_line_of_sight: bool = False,
        target_y_range: IntRange | None = None,
        options: PathfindOptions | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[CollectBlocksTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _collect_blocks_task(
                            block_ids,
                            tags,
                            count,
                            search_radius,
                            avoid_submerged_targets,
                            require_line_of_sight,
                            target_y_range,
                            options,
                        )
                    )
                ),
                CollectBlocksTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_excavate(
        self,
        from_position: BlockPosition,
        to_position: BlockPosition,
        *,
        options: PathfindOptions | None = None,
        maximum_blocks: int = 0,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _excavate_task(from_position, to_position, options, maximum_blocks),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.excavate")
    def excavate(
        self,
        from_position: BlockPosition,
        to_position: BlockPosition,
        *,
        options: PathfindOptions | None = None,
        maximum_blocks: int = 0,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ExcavateTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _excavate_task(from_position, to_position, options, maximum_blocks)
                    )
                ),
                ExcavateTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_build(
        self,
        origin: BlockPosition,
        blocks: Iterable[SchematicBlock],
        *,
        rotation: BuildRotation = BUILD_ROTATION_NONE,
        mirror: BuildMirror = BUILD_MIRROR_NONE,
        substitutions: Mapping[str, Iterable[str]] | None = None,
        options: PathfindOptions | None = None,
        break_incorrect_blocks: bool = True,
        restore_selected_slot: bool = True,
        partition_index: int = 0,
        partition_count: int = 1,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _build_task(
                origin,
                blocks,
                rotation,
                mirror,
                substitutions,
                options,
                break_incorrect_blocks,
                restore_selected_slot,
                partition_index,
                partition_count,
            ),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.build")
    def build(
        self,
        origin: BlockPosition,
        blocks: Iterable[SchematicBlock],
        *,
        rotation: BuildRotation = BUILD_ROTATION_NONE,
        mirror: BuildMirror = BUILD_MIRROR_NONE,
        substitutions: Mapping[str, Iterable[str]] | None = None,
        options: PathfindOptions | None = None,
        break_incorrect_blocks: bool = True,
        restore_selected_slot: bool = True,
        partition_index: int = 0,
        partition_count: int = 1,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[BuildTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _build_task(
                            origin,
                            blocks,
                            rotation,
                            mirror,
                            substitutions,
                            options,
                            break_incorrect_blocks,
                            restore_selected_slot,
                            partition_index,
                            partition_count,
                        )
                    )
                ),
                BuildTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_craft(
        self,
        recipe_id: str,
        *,
        count: int = 1,
        station: BlockPosition | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _craft_task(recipe_id, count, station),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.craft")
    def craft(
        self,
        recipe_id: str,
        *,
        count: int = 1,
        station: BlockPosition | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[CraftTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (yield from validate(lambda: _craft_task(recipe_id, count, station))),
                CraftTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_smelt(
        self,
        input: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _smelt_task(input, count, fuel, station),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.smelt")
    def smelt(
        self,
        input: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[SmeltTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (yield from validate(lambda: _smelt_task(input, count, fuel, station))),
                SmeltTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_brew(
        self,
        input: ItemSelector,
        ingredient: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        expected_result: ItemSelector | None = None,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _brew_task(input, ingredient, count, fuel, station, expected_result),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.brew")
    def brew(
        self,
        input: ItemSelector,
        ingredient: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        expected_result: ItemSelector | None = None,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[BrewTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _brew_task(input, ingredient, count, fuel, station, expected_result)
                    )
                ),
                BrewTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    def run_villager_trade(
        self,
        offer_index: int,
        *,
        count: int = 1,
        expected_result: ItemSelector | None = None,
        close_when_done: bool = False,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return self.run(
            _villager_trade_task(offer_index, count, expected_result, close_when_done),
            reconnect_policy=reconnect_policy,
            deadline=deadline,
            idempotency_key=idempotency_key,
            headers=headers,
            timeout_ms=timeout_ms,
        )

    @fn("SoulFireTasks.villager_trade")
    def villager_trade(
        self,
        offer_index: int,
        *,
        count: int = 1,
        expected_result: ItemSelector | None = None,
        close_when_done: bool = False,
        conflict_policy: BotTaskConflictPolicy = BOT_TASK_CONFLICT_POLICY_UNSPECIFIED,
        reconnect_policy: BotTaskReconnectPolicy = BOT_TASK_RECONNECT_POLICY_UNSPECIFIED,
        priority: BotTaskPriority = BOT_TASK_PRIORITY_UNSPECIFIED,
        deadline: datetime | None = None,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[VillagerTradeTaskResult], SoulFireOperationError]:
        return (
            yield from self.start(
                (
                    yield from validate(
                        lambda: _villager_trade_task(
                            offer_index, count, expected_result, close_when_done
                        )
                    )
                ),
                VillagerTradeTaskResult,
                conflict_policy=conflict_policy,
                reconnect_policy=reconnect_policy,
                priority=priority,
                deadline=deadline,
                idempotency_key=idempotency_key,
                headers=headers,
                timeout_ms=timeout_ms,
            )
        )

    @fn("SoulFireTasks.get")
    def get[ResultT: Message](
        self,
        task_id: str,
        result_type: type[ResultT],
        *,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[ResultT], SoulFireOperationError]:
        task = yield from rpc(
            "SoulFireTasks.get",
            lambda: self._client.get_bot_task(
                GetBotTaskRequest(task_id=task_id), headers=headers, timeout_ms=timeout_ms
            ),
        )
        yield from validate(lambda: _require_task_scope(task, self._instance_id, self._bot_id))
        return SoulFireTask(self._client, task, result_type, self._header_factory)

    @fn("SoulFireTasks.list")
    def list(
        self,
        *,
        statuses: Iterable[BotTaskStatus] = (),
        include_terminal: bool = False,
        page_size: int = 100,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[list[BotTask], SoulFireOperationError]:
        tasks: list[BotTask] = []
        requested_statuses = tuple(statuses)
        page_token = ""
        while True:
            response = yield from rpc(
                "SoulFireTasks.list",
                lambda page_token=page_token: self._client.list_bot_tasks(
                    ListBotTasksRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        statuses=requested_statuses,
                        include_terminal=include_terminal,
                        page_size=page_size,
                        page_token=page_token,
                    ),
                    headers=headers,
                    timeout_ms=timeout_ms,
                ),
            )
            tasks.extend(response.tasks)
            page_token = response.next_page_token
            if not page_token:
                return tasks

    def watch(
        self,
        *,
        statuses: Iterable[BotTaskStatus] = (),
        after_sequence: int = 0,
        include_snapshot: bool = True,
        headers: dict[str, str] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[BotTaskEvent, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireTasks.watch",
            lambda: self._client.watch_bot_tasks(
                WatchBotTasksRequest(
                    instance_id=self._instance_id,
                    bot_id=self._bot_id,
                    statuses=statuses,
                    after_sequence=after_sequence,
                    include_snapshot=include_snapshot,
                ),
                headers=headers,
                timeout_ms=timeout_ms,
            ),
        )


def _require_task_scope(task: BotTask, instance_id: str, bot_id: str) -> None:
    if task.instance_id != instance_id or task.bot_id != bot_id:
        raise ValueError(f"Task {task.task_id} does not belong to bot {bot_id}")


def _follow_entity_task(
    target: FollowEntityTarget,
    distance: float,
    options: PathfindOptions | None,
    target_unavailable_timeout_seconds: int,
) -> FollowEntityTask:
    if not isfinite(distance) or distance <= 0:
        raise ValueError("distance must be finite and greater than zero")
    if target_unavailable_timeout_seconds < 0:
        raise ValueError("target_unavailable_timeout_seconds must be non-negative")
    if isinstance(target, int):
        network_id = target
        connection_epoch = ""
    else:
        network_id = target.network_id
        connection_epoch = target.connection_epoch
    if network_id <= 0:
        raise ValueError("target network_id must be positive")
    task = FollowEntityTask(
        target=PathfindGoal.EntityGoal(
            entity_id=network_id, radius=distance, connection_epoch=connection_epoch
        ),
        target_unavailable_timeout_seconds=target_unavailable_timeout_seconds,
    )
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _attack_entity_task(
    target: AttackEntityTarget,
    attack_range: float,
    sprinting: bool,
    maximum_attacks: int,
    options: PathfindOptions | None,
    target_unavailable_timeout_seconds: int,
    select_best_weapon: bool,
    weapon: ItemSelector | None,
    restore_selected_slot: bool,
    use_offhand_shield: bool,
) -> AttackEntityTask:
    if not isfinite(attack_range) or not 0 < attack_range <= 6:
        raise ValueError("attack_range must be finite, greater than zero, and at most six")
    if maximum_attacks < 0:
        raise ValueError("maximum_attacks must be non-negative")
    if target_unavailable_timeout_seconds < 0:
        raise ValueError("target_unavailable_timeout_seconds must be non-negative")
    reference = _entity_reference(target)
    task = AttackEntityTask(
        target=reference,
        attack_range=attack_range,
        sprinting=sprinting,
        maximum_attacks=maximum_attacks,
        target_unavailable_timeout_seconds=target_unavailable_timeout_seconds,
        select_best_weapon=select_best_weapon,
        restore_selected_slot=restore_selected_slot,
        use_offhand_shield=use_offhand_shield,
    )
    if weapon is not None:
        task.weapon.CopyFrom(weapon)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _attack_nearest_task(
    selector: EntitySelector,
    radius: float,
    attack_range: float,
    sprinting: bool,
    maximum_attacks: int,
    maximum_targets: int,
    no_target_timeout_seconds: int,
    complete_when_no_target: bool,
    select_best_weapon: bool,
    weapon: ItemSelector | None,
    restore_selected_slot: bool,
    options: PathfindOptions | None,
) -> AttackNearestTask:
    if not isfinite(radius) or not 0 < radius <= 128:
        raise ValueError("radius must be finite, greater than zero, and at most 128")
    if not isfinite(attack_range) or not 0 < attack_range <= 6:
        raise ValueError("attack_range must be finite, greater than zero, and at most six")
    if maximum_attacks < 0:
        raise ValueError("maximum_attacks must be non-negative")
    if maximum_targets < 0:
        raise ValueError("maximum_targets must be non-negative")
    if no_target_timeout_seconds < 0:
        raise ValueError("no_target_timeout_seconds must be non-negative")
    task = AttackNearestTask(
        selector=selector,
        radius=radius,
        attack_range=attack_range,
        sprinting=sprinting,
        maximum_attacks=maximum_attacks,
        maximum_targets=maximum_targets,
        no_target_timeout_seconds=no_target_timeout_seconds,
        complete_when_no_target=complete_when_no_target,
        select_best_weapon=select_best_weapon,
        restore_selected_slot=restore_selected_slot,
    )
    if weapon is not None:
        task.weapon.CopyFrom(weapon)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _ranged_attack_task(
    target: AttackEntityTarget,
    minimum_range: float,
    maximum_range: float,
    maximum_shots: int,
    target_unavailable_timeout_seconds: int,
    weapon: ItemSelector | None,
    bow_draw_ticks: int,
    lead_target: bool,
    compensate_gravity: bool,
    strafe: bool,
    restore_selected_slot: bool,
    options: PathfindOptions | None,
) -> RangedAttackTask:
    if not isfinite(minimum_range) or not 0 < minimum_range < 64:
        raise ValueError("minimum_range must be finite, greater than zero, and smaller than 64")
    if not isfinite(maximum_range) or maximum_range <= minimum_range or maximum_range > 64:
        raise ValueError("maximum_range must be finite, greater than minimum_range, and at most 64")
    if maximum_shots < 0:
        raise ValueError("maximum_shots must be non-negative")
    if not 0 < target_unavailable_timeout_seconds <= 3600:
        raise ValueError("target_unavailable_timeout_seconds must be between one and 3,600")
    if not 3 <= bow_draw_ticks <= 20:
        raise ValueError("bow_draw_ticks must be between three and twenty")
    task = RangedAttackTask(
        target=_entity_reference(target),
        minimum_range=minimum_range,
        maximum_range=maximum_range,
        maximum_shots=maximum_shots,
        target_unavailable_timeout_seconds=target_unavailable_timeout_seconds,
        bow_draw_ticks=bow_draw_ticks,
        lead_target=lead_target,
        compensate_gravity=compensate_gravity,
        strafe=strafe,
        restore_selected_slot=restore_selected_slot,
    )
    if weapon is not None:
        task.weapon.CopyFrom(weapon)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _flee_task(
    threats: EntitySelector,
    trigger_radius: float,
    safe_distance: float,
    safe_seconds: int,
    complete_when_safe: bool,
    maximum_escapes: int,
    options: PathfindOptions | None,
) -> FleeTask:
    if not isfinite(trigger_radius) or not 0 < trigger_radius <= 128:
        raise ValueError("trigger_radius must be finite, greater than zero, and at most 128")
    if not isfinite(safe_distance) or not trigger_radius < safe_distance <= 128:
        raise ValueError(
            "safe_distance must be finite, greater than trigger_radius, and at most 128"
        )
    if safe_seconds <= 0:
        raise ValueError("safe_seconds must be positive")
    if maximum_escapes < 0:
        raise ValueError("maximum_escapes must be non-negative")
    task = FleeTask(
        threats=threats,
        trigger_radius=trigger_radius,
        safe_distance=safe_distance,
        safe_seconds=safe_seconds,
        complete_when_safe=complete_when_safe,
        maximum_escapes=maximum_escapes,
    )
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _guard_task(
    position: BlockPosition | None,
    entity: AttackEntityTarget | None,
    threats: EntitySelector,
    guard_radius: float,
    maximum_pursuit_distance: float,
    return_radius: float,
    attack_range: float,
    sprinting: bool,
    maximum_attacks: int,
    maximum_targets: int,
    complete_when_clear: bool,
    clear_seconds: int,
    select_best_weapon: bool,
    weapon: ItemSelector | None,
    restore_selected_slot: bool,
    options: PathfindOptions | None,
) -> GuardTask:
    if (position is None) == (entity is None):
        raise ValueError("guard task requires exactly one protected subject")
    if not isfinite(guard_radius) or not 0 < guard_radius <= 128:
        raise ValueError("guard_radius must be finite, greater than zero, and at most 128")
    if (
        not isfinite(maximum_pursuit_distance)
        or maximum_pursuit_distance < guard_radius
        or maximum_pursuit_distance > 256
    ):
        raise ValueError(
            "maximum_pursuit_distance must be finite, at least guard_radius, and at most 256"
        )
    if not isfinite(return_radius) or not 0 < return_radius <= guard_radius:
        raise ValueError(
            "return_radius must be finite, greater than zero, and at most guard_radius"
        )
    if not isfinite(attack_range) or not 0 < attack_range <= 6:
        raise ValueError("attack_range must be finite, greater than zero, and at most six")
    if maximum_attacks < 0:
        raise ValueError("maximum_attacks must be non-negative")
    if maximum_targets < 0:
        raise ValueError("maximum_targets must be non-negative")
    if clear_seconds <= 0:
        raise ValueError("clear_seconds must be positive")
    task = GuardTask(
        threats=threats,
        guard_radius=guard_radius,
        maximum_pursuit_distance=maximum_pursuit_distance,
        return_radius=return_radius,
        attack_range=attack_range,
        sprinting=sprinting,
        maximum_attacks=maximum_attacks,
        maximum_targets=maximum_targets,
        complete_when_clear=complete_when_clear,
        clear_seconds=clear_seconds,
        select_best_weapon=select_best_weapon,
        restore_selected_slot=restore_selected_slot,
    )
    if position is not None:
        task.position.CopyFrom(position)
    if entity is not None:
        task.entity.CopyFrom(_entity_reference(entity))
    if weapon is not None:
        task.weapon.CopyFrom(weapon)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _sleep_task(
    bed: BlockPosition | None,
    search_radius: int,
    wait_until_possible: bool,
    retry_interval_ticks: int,
    options: PathfindOptions | None,
) -> SleepTask:
    if not 0 < search_radius <= 32:
        raise ValueError("search_radius must be between one and 32")
    if not 0 < retry_interval_ticks <= 1200:
        raise ValueError("retry_interval_ticks must be between one and 1,200")
    task = SleepTask(
        search_radius=search_radius,
        wait_until_possible=wait_until_possible,
        retry_interval_ticks=retry_interval_ticks,
    )
    if bed is not None:
        task.bed.CopyFrom(bed)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _fish_task(
    maximum_catches: int,
    maximum_failed_casts: int,
    rod: ItemSelector | None,
    cast_timeout_ticks: int,
    bite_timeout_ticks: int,
    complete_when_no_rod: bool,
    restore_selected_slot: bool,
) -> FishTask:
    if maximum_catches < 0:
        raise ValueError("maximum_catches must be non-negative")
    if maximum_failed_casts < 0:
        raise ValueError("maximum_failed_casts must be non-negative")
    if not 0 < cast_timeout_ticks <= 1200:
        raise ValueError("cast_timeout_ticks must be between one and 1,200")
    if not 0 < bite_timeout_ticks <= 72000:
        raise ValueError("bite_timeout_ticks must be between one and 72,000")
    task = FishTask(
        maximum_catches=maximum_catches,
        maximum_failed_casts=maximum_failed_casts,
        cast_timeout_ticks=cast_timeout_ticks,
        bite_timeout_ticks=bite_timeout_ticks,
        complete_when_no_rod=complete_when_no_rod,
        restore_selected_slot=restore_selected_slot,
    )
    if rod is not None:
        task.rod.CopyFrom(rod)
    return task


def _farm_task(
    crop_ids: Iterable[str],
    center: BlockPosition | None,
    radius: int,
    maximum_harvests: int,
    replant: bool,
    complete_when_no_mature_crops: bool,
    options: PathfindOptions | None,
    rescan_interval_ticks: int,
    restore_selected_slot: bool,
) -> FarmTask:
    if not 0 < radius <= 48:
        raise ValueError("radius must be between one and 48")
    if maximum_harvests < 0:
        raise ValueError("maximum_harvests must be non-negative")
    if not 0 < rescan_interval_ticks <= 72000:
        raise ValueError("rescan_interval_ticks must be between one and 72,000")
    task = FarmTask(
        crop_ids=tuple(crop_ids),
        radius=radius,
        maximum_harvests=maximum_harvests,
        replant=replant,
        complete_when_no_mature_crops=complete_when_no_mature_crops,
        rescan_interval_ticks=rescan_interval_ticks,
        restore_selected_slot=restore_selected_slot,
    )
    if center is not None:
        task.center.CopyFrom(center)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _breed_task(
    animals: EntitySelector | None,
    food: ItemSelector | None,
    center: BlockPosition | None,
    radius: int,
    maximum_pairs: int,
    complete_when_no_pair: bool,
    complete_when_no_food: bool,
    options: PathfindOptions | None,
    rescan_interval_ticks: int,
    breeding_timeout_ticks: int,
    restore_selected_slot: bool,
) -> BreedTask:
    if not 0 < radius <= 64:
        raise ValueError("radius must be between one and 64")
    if maximum_pairs < 0:
        raise ValueError("maximum_pairs must be non-negative")
    if not 0 < rescan_interval_ticks <= 72000:
        raise ValueError("rescan_interval_ticks must be between one and 72,000")
    if not 0 < breeding_timeout_ticks <= 1200:
        raise ValueError("breeding_timeout_ticks must be between one and 1,200")
    task = BreedTask(
        radius=radius,
        maximum_pairs=maximum_pairs,
        complete_when_no_pair=complete_when_no_pair,
        complete_when_no_food=complete_when_no_food,
        rescan_interval_ticks=rescan_interval_ticks,
        breeding_timeout_ticks=breeding_timeout_ticks,
        restore_selected_slot=restore_selected_slot,
    )
    if animals is not None:
        task.animals.CopyFrom(animals)
    if food is not None:
        task.food.CopyFrom(food)
    if center is not None:
        task.center.CopyFrom(center)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _explore_task(
    origin: BlockPosition | None,
    radius: int,
    waypoint_spacing: int,
    maximum_waypoints: int,
    options: PathfindOptions | None,
    return_to_origin: bool,
    purpose: str,
) -> ExploreTask:
    if not 0 < radius <= 4096:
        raise ValueError("radius must be between one and 4,096")
    if not 8 <= waypoint_spacing <= 512:
        raise ValueError("waypoint_spacing must be between eight and 512")
    effective_spacing = min(waypoint_spacing, radius)
    if (radius + effective_spacing - 1) // effective_spacing > 32:
        raise ValueError("radius must span at most 32 waypoint intervals")
    if maximum_waypoints < 0:
        raise ValueError("maximum_waypoints must be non-negative")
    if not purpose or len(purpose) > 64:
        raise ValueError("purpose must contain between one and 64 characters")
    task = ExploreTask(
        radius=radius,
        waypoint_spacing=waypoint_spacing,
        maximum_waypoints=maximum_waypoints,
        return_to_origin=return_to_origin,
        purpose=purpose,
    )
    if origin is not None:
        task.origin.CopyFrom(origin)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _container_transfer_task(
    container: BlockPosition,
    direction: ContainerTransferDirection,
    operations: Iterable[ContainerTransferSpec | ContainerTransferOperation],
    options: PathfindOptions | None,
    close_container: bool,
) -> ContainerTransferTask:
    normalized = tuple(_container_transfer_operation(value) for value in operations)
    if not 0 < len(normalized) <= 64:
        raise ValueError("operations must contain between one and 64 transfers")
    task = ContainerTransferTask(
        container=container,
        direction=direction,
        operations=normalized,
        close_container=close_container,
    )
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _container_transfer_operation(
    value: ContainerTransferSpec | ContainerTransferOperation,
) -> ContainerTransferOperation:
    if isinstance(value, ContainerTransferSpec):
        selector = value.selector
        count = value.count
        allow_partial = value.allow_partial
    else:
        selector = value.selector
        count = value.count
        allow_partial = value.allow_partial
    if not 0 < count <= 1000000:
        raise ValueError("transfer count must be between one and 1,000,000")
    return ContainerTransferOperation(selector=selector, count=count, allow_partial=allow_partial)


def _maintain_loadout_task(
    container: BlockPosition,
    requirements: Iterable[LoadoutRequirementSpec | LoadoutRequirement],
    options: PathfindOptions | None,
    check_interval_ticks: int,
    maximum_rebalances: int,
    complete_when_satisfied: bool,
    close_container: bool,
) -> MaintainLoadoutTask:
    normalized = tuple(
        LoadoutRequirement(
            selector=value.selector,
            minimum_count=value.minimum_count,
            target_count=value.target_count,
            maximum_count=value.maximum_count,
        )
        for value in requirements
    )
    if not 0 < len(normalized) <= 64:
        raise ValueError("requirements must contain between one and 64 entries")
    for requirement in normalized:
        if (
            requirement.minimum_count < 0
            or requirement.target_count < requirement.minimum_count
            or (
                requirement.maximum_count > 0
                and requirement.maximum_count < requirement.target_count
            )
        ):
            raise ValueError(
                "Each requirement needs minimum_count <= target_count <= maximum_count "
                "when maximum_count is set"
            )
    if check_interval_ticks <= 0:
        raise ValueError("check_interval_ticks must be positive")
    if maximum_rebalances < 0:
        raise ValueError("maximum_rebalances must be non-negative")
    task = MaintainLoadoutTask(
        container=container,
        requirements=normalized,
        check_interval_ticks=check_interval_ticks,
        maximum_rebalances=maximum_rebalances,
        complete_when_satisfied=complete_when_satisfied,
        close_container=close_container,
    )
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _auto_eat_task(
    food_item_ids: Iterable[str],
    food_level: int,
    check_interval_ticks: int,
    maximum_meals: int,
    complete_when_no_food: bool,
    restore_selected_slot: bool,
) -> AutoEatTask:
    if not 0 <= food_level <= 20:
        raise ValueError("food_level must be between zero and twenty")
    if check_interval_ticks <= 0:
        raise ValueError("check_interval_ticks must be positive")
    if maximum_meals < 0:
        raise ValueError("maximum_meals must be non-negative")
    return AutoEatTask(
        food_item_ids=tuple(food_item_ids),
        food_level=food_level,
        check_interval_ticks=check_interval_ticks,
        maximum_meals=maximum_meals,
        complete_when_no_food=complete_when_no_food,
        restore_selected_slot=restore_selected_slot,
    )


def _auto_respawn_task(respawn_delay_ticks: int, maximum_respawns: int) -> AutoRespawnTask:
    if respawn_delay_ticks < 0:
        raise ValueError("respawn_delay_ticks must be non-negative")
    if maximum_respawns < 0:
        raise ValueError("maximum_respawns must be non-negative")
    return AutoRespawnTask(
        respawn_delay_ticks=respawn_delay_ticks, maximum_respawns=maximum_respawns
    )


def _auto_totem_task(
    check_interval_ticks: int,
    maximum_equips: int,
    complete_when_no_totem: bool,
    replace_occupied_offhand: bool,
) -> AutoTotemTask:
    if check_interval_ticks <= 0:
        raise ValueError("check_interval_ticks must be positive")
    if maximum_equips < 0:
        raise ValueError("maximum_equips must be non-negative")
    return AutoTotemTask(
        check_interval_ticks=check_interval_ticks,
        maximum_equips=maximum_equips,
        complete_when_no_totem=complete_when_no_totem,
        replace_occupied_offhand=replace_occupied_offhand,
    )


def _auto_armor_task(
    check_interval_ticks: int, maximum_equips: int, complete_when_no_upgrade: bool
) -> AutoArmorTask:
    if check_interval_ticks <= 0:
        raise ValueError("check_interval_ticks must be positive")
    if maximum_equips < 0:
        raise ValueError("maximum_equips must be non-negative")
    return AutoArmorTask(
        check_interval_ticks=check_interval_ticks,
        maximum_equips=maximum_equips,
        complete_when_no_upgrade=complete_when_no_upgrade,
    )


def _collect_blocks_task(
    block_ids: str | Iterable[str],
    tags: Iterable[str],
    count: int,
    search_radius: int,
    avoid_submerged_targets: bool,
    require_line_of_sight: bool,
    target_y_range: IntRange | None,
    options: PathfindOptions | None,
) -> CollectBlocksTask:
    ids, selector_tags = block_selectors(block_ids)
    block_tags = (*selector_tags, *tags)
    if not ids and (not block_tags):
        raise ValueError("block_ids or tags must contain at least one selector")
    if count <= 0:
        raise ValueError("count must be positive")
    if search_radius <= 0:
        raise ValueError("search_radius must be positive")
    task = CollectBlocksTask(
        block_ids=ids,
        tags=block_tags,
        count=count,
        search_radius=search_radius,
        avoid_submerged_targets=avoid_submerged_targets,
        require_line_of_sight=require_line_of_sight,
    )
    if options is not None:
        task.options.CopyFrom(options)
    if target_y_range is not None:
        task.target_y_range.CopyFrom(target_y_range)
    return task


def _excavate_task(
    from_position: BlockPosition,
    to_position: BlockPosition,
    options: PathfindOptions | None,
    maximum_blocks: int,
) -> ExcavateTask:
    if maximum_blocks < 0:
        raise ValueError("maximum_blocks must be non-negative")
    task = ExcavateTask(corner_a=from_position, corner_b=to_position, maximum_blocks=maximum_blocks)
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _build_task(
    origin: BlockPosition,
    blocks: Iterable[SchematicBlock],
    rotation: BuildRotation,
    mirror: BuildMirror,
    substitutions: Mapping[str, Iterable[str]] | None,
    options: PathfindOptions | None,
    break_incorrect_blocks: bool,
    restore_selected_slot: bool,
    partition_index: int,
    partition_count: int,
) -> BuildTask:
    placements = tuple(blocks)
    if not placements:
        raise ValueError("blocks must contain at least one placement")
    if partition_count <= 0:
        raise ValueError("partition_count must be positive")
    if partition_index < 0 or partition_index >= partition_count:
        raise ValueError("partition_index must be non-negative and smaller than partition_count")
    task = BuildTask(
        origin=origin,
        blocks=[
            BuildBlock(
                offset=BuildOffset(x=block.x, y=block.y, z=block.z),
                block_id=block.block_id,
                properties=dict(block.properties),
            )
            for block in placements
        ],
        rotation=rotation,
        mirror=mirror,
        substitutions=[
            BuildMaterialSubstitution(
                source_block_id=source, replacement_block_ids=tuple(replacements)
            )
            for source, replacements in (substitutions or {}).items()
        ],
        break_incorrect_blocks=break_incorrect_blocks,
        restore_selected_slot=restore_selected_slot,
        partition_index=partition_index,
        partition_count=partition_count,
    )
    if options is not None:
        task.options.CopyFrom(options)
    return task


def _craft_task(recipe_id: str, count: int, station: BlockPosition | None) -> CraftTask:
    if not recipe_id:
        raise ValueError("recipe_id must not be empty")
    if count <= 0:
        raise ValueError("count must be positive")
    task = CraftTask(recipe_id=recipe_id, count=count)
    if station is not None:
        task.station.CopyFrom(station)
    return task


def _smelt_task(
    input: ItemSelector, count: int, fuel: ItemSelector | None, station: BlockPosition | None
) -> SmeltTask:
    if count <= 0:
        raise ValueError("count must be positive")
    task = SmeltTask(input=input, count=count)
    if fuel is not None:
        task.fuel.CopyFrom(fuel)
    if station is not None:
        task.station.CopyFrom(station)
    return task


def _brew_task(
    input: ItemSelector,
    ingredient: ItemSelector,
    count: int,
    fuel: ItemSelector | None,
    station: BlockPosition | None,
    expected_result: ItemSelector | None,
) -> BrewTask:
    if count <= 0:
        raise ValueError("count must be positive")
    task = BrewTask(input=input, ingredient=ingredient, count=count)
    if fuel is not None:
        task.fuel.CopyFrom(fuel)
    if station is not None:
        task.station.CopyFrom(station)
    if expected_result is not None:
        task.expected_result.CopyFrom(expected_result)
    return task


def _villager_trade_task(
    offer_index: int, count: int, expected_result: ItemSelector | None, close_when_done: bool
) -> VillagerTradeTask:
    if offer_index < 0:
        raise ValueError("offer_index must be non-negative")
    if count <= 0:
        raise ValueError("count must be positive")
    task = VillagerTradeTask(offer_index=offer_index, count=count, close_when_done=close_when_done)
    if expected_result is not None:
        task.expected_result.CopyFrom(expected_result)
    return task


def _entity_reference(target: AttackEntityTarget) -> EntityReference:
    if isinstance(target, int):
        network_id = target
        connection_epoch = ""
        uuid = None
    else:
        network_id = target.network_id
        connection_epoch = target.connection_epoch
        uuid = getattr(target, "uuid", None)
    if network_id <= 0:
        raise ValueError("target network_id must be positive")
    reference = EntityReference(network_id=network_id, connection_epoch=connection_epoch)
    if isinstance(uuid, str) and uuid:
        reference.uuid = uuid
    return reference


def _start_request(
    *,
    instance_id: str,
    bot_id: str,
    input: AnyMessage,
    conflict_policy: BotTaskConflictPolicy,
    reconnect_policy: BotTaskReconnectPolicy,
    disconnect_policy: BotTaskDisconnectPolicy,
    priority: BotTaskPriority,
    deadline: datetime | None,
    parent_task_id: str | None,
    causation_id: str | None,
    idempotency_key: str | None,
) -> StartBotTaskRequest:
    request = StartBotTaskRequest(
        instance_id=instance_id,
        bot_id=bot_id,
        input=input,
        conflict_policy=conflict_policy,
        reconnect_policy=reconnect_policy,
        disconnect_policy=disconnect_policy,
        priority=priority,
    )
    if deadline is not None:
        if deadline.tzinfo is None or deadline.utcoffset() is None:
            raise ValueError("deadline must be timezone-aware")
        request.deadline.FromDatetime(deadline.astimezone(UTC))
    if parent_task_id is not None:
        request.parent_task_id = parent_task_id
    if causation_id is not None:
        request.causation_id = causation_id
    if idempotency_key is not None:
        request.idempotency_key = idempotency_key
    return request


def _result_type_failure(task: BotTask, expected_type: str) -> BotTask:
    failed = BotTask()
    failed.CopyFrom(task)
    failed.failure.code = "result_type_mismatch"
    failed.failure.message = (
        f"Task returned {task.result.type_url or 'no type'}, expected {expected_type}"
    )
    failed.failure.retryable = False
    return failed
