from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from typing import Generic, Never, Protocol, TypeVar

from effect_py import Effect, EffectGen, Schedule, Scope, fail, fn, gen, schedule, scoped, succeed
from effect_py.errors import TaggedError, catch_tag

from .bot import SoulFireBot
from .bot_live_pb2 import HAND_MAIN, BlockFace, PathfindOptions
from .common_pb2 import BlockPosition
from .concurrency import parallel as parallel_effects
from .concurrency import race as race_effects
from .errors import SoulFireOperationError, SoulFireStateError, SoulFireTaskError
from .resources import ensuring
from .streams import Stream
from .task_pb2 import BOT_TASK_STATUS_COMPLETED, BotTask, BotTaskEvent
from .transport import validate
from .world_pb2 import IntRange

A = TypeVar("A", covariant=True)
E = TypeVar("E", covariant=True, default=Never)
R = TypeVar("R", covariant=True, default=Never)


class BotBehavior(Protocol[A, E, R]):
    def run(self, bot: SoulFireBot) -> Effect[A, E, R]: ...


@dataclass(frozen=True, slots=True)
class FunctionBehavior(Generic[A, E, R]):
    function: Callable[[SoulFireBot], Effect[A, E, R]]

    def run(self, bot: SoulFireBot) -> Effect[A, E, R]:
        return self.function(bot)


def define_behavior[A2, E2 = Never, R2 = Never](
    function: Callable[[SoulFireBot], Effect[A2, E2, R2]],
) -> FunctionBehavior[A2, E2, R2]:
    return FunctionBehavior(function)


class SoulFireBehaviorError(TaggedError):
    """Base error for bot behavior operations."""


class SoulFireBehaviorTimeoutError(SoulFireBehaviorError):
    """A bot behavior exceeded its allowed duration."""

    def __init__(self, duration: float) -> None:
        self.duration = duration
        super().__init__(f"Behavior exceeded {duration:g} seconds")


@fn("run_behaviors")
def run_behaviors[E2 = Never, R2 = Never](
    bot: SoulFireBot,
    behaviors: Iterable[BotBehavior[object, E2, R2]],
) -> EffectGen[None, E2, R2]:
    yield from succeed(None)
    for behavior in behaviors:
        yield from behavior.run(bot)


def sequence[E2 = Never, R2 = Never](
    *behaviors: BotBehavior[object, E2, R2],
) -> BotBehavior[tuple[object, ...], E2, R2]:
    @fn("sequence")
    def run(bot: SoulFireBot) -> EffectGen[tuple[object, ...], E2, R2]:
        results: list[object] = []
        yield from succeed(None)
        for behavior in behaviors:
            results.append((yield from behavior.run(bot)))
        return tuple(results)

    return define_behavior(run)


def parallel[E2 = Never, R2 = Never](
    *behaviors: BotBehavior[object, E2, R2],
) -> BotBehavior[tuple[object, ...], E2, R2]:
    return define_behavior(
        lambda bot: parallel_effects(
            (behavior.run(bot) for behavior in behaviors), concurrency=max(1, len(behaviors))
        )
    )


def race[A2, E2 = Never, R2 = Never](
    first: BotBehavior[A2, E2, R2],
    *others: BotBehavior[A2, E2, R2],
) -> BotBehavior[A2, E2, R2]:
    return define_behavior(
        lambda bot: race_effects(first.run(bot), *(behavior.run(bot) for behavior in others))
    )


def repeat[A2, E2 = Never, R2 = Never](
    behavior: BotBehavior[A2, E2, R2],
    *,
    times: int,
) -> BotBehavior[tuple[A2, ...], E2 | SoulFireOperationError, R2]:
    @fn("repeat")
    def run(bot: SoulFireBot) -> EffectGen[tuple[A2, ...], E2 | SoulFireOperationError, R2]:
        count = yield from validate(lambda: _positive_integer(times, "times"))
        results: list[A2] = []
        for _ in range(count):
            results.append((yield from behavior.run(bot)))
        return tuple(results)

    return define_behavior(run)


def retry[A2, E2 = Never, R2 = Never](
    behavior: BotBehavior[A2, E2, R2],
    *,
    attempts: int = 3,
    delay: float = 0,
    backoff: float = 1,
    maximum_delay: float | None = None,
    while_: Callable[[E2], bool] = lambda _: True,
) -> BotBehavior[A2, E2 | SoulFireOperationError, R2]:
    @fn("retry")
    def run(bot: SoulFireBot) -> EffectGen[A2, E2 | SoulFireOperationError, R2]:
        count = yield from validate(lambda: _positive_integer(attempts, "attempts"))
        initial = yield from validate(lambda: _non_negative_finite(delay, "delay"))
        factor = yield from validate(lambda: _positive_finite(backoff, "backoff"))
        cap = (
            float("inf")
            if maximum_delay is None
            else (
                yield from validate(
                    lambda maximum_delay=maximum_delay: _non_negative_finite(
                        maximum_delay, "maximum_delay"
                    )
                )
            )
        )
        policy: Schedule[int, E2] = Schedule(
            lambda attempt, error, _: (
                (min(initial * factor**attempt, cap), attempt)
                if attempt < count - 1 and while_(error)
                else None
            )
        )
        return (yield from behavior.run(bot).pipe(schedule.retry(policy)))

    return define_behavior(run)


def timeout[A2, E2 = Never, R2 = Never](
    behavior: BotBehavior[A2, E2, R2],
    seconds: float,
) -> BotBehavior[A2, E2 | SoulFireOperationError | SoulFireBehaviorTimeoutError, R2]:
    @fn("timeout")
    def run(
        bot: SoulFireBot,
    ) -> EffectGen[A2, E2 | SoulFireOperationError | SoulFireBehaviorTimeoutError, R2]:
        duration = yield from validate(lambda: _positive_finite(seconds, "seconds"))
        return (
            yield from behavior.run(bot).pipe(
                schedule.timeout(duration),
                catch_tag(schedule.TimeoutException)(
                    lambda _: fail(SoulFireBehaviorTimeoutError(duration))
                ),
            )
        )

    return define_behavior(run)


def until[A2, E2 = Never, R2 = Never, EP = Never, RP = Never](
    behavior: BotBehavior[A2, E2, R2],
    predicate: Callable[[A2], bool | Effect[bool, EP, RP]],
    *,
    maximum_iterations: int | None = None,
) -> BotBehavior[A2, E2 | EP | SoulFireOperationError | SoulFireBehaviorError, R2 | RP]:
    @fn("until")
    def run(
        bot: SoulFireBot,
    ) -> EffectGen[A2, E2 | EP | SoulFireOperationError | SoulFireBehaviorError, R2 | RP]:
        maximum = (
            None
            if maximum_iterations is None
            else (
                yield from validate(
                    lambda maximum_iterations=maximum_iterations: _positive_integer(
                        maximum_iterations, "maximum_iterations"
                    )
                )
            )
        )
        iteration = 0
        while maximum is None or iteration < maximum:
            result = yield from behavior.run(bot)
            iteration += 1
            decision = predicate(result)
            if decision if isinstance(decision, bool) else (yield from decision):
                return result
        return (
            yield from fail(
                SoulFireBehaviorError(f"Predicate remained false after {maximum} iterations")
            )
        )

    return define_behavior(run)


def conditional[A2, E2 = Never, R2 = Never, EP = Never, RP = Never](
    predicate: Callable[[SoulFireBot], bool | Effect[bool, EP, RP]],
    when_true: BotBehavior[A2, E2, R2],
    when_false: BotBehavior[A2, E2, R2] | None = None,
) -> BotBehavior[A2 | None, E2 | EP, R2 | RP]:
    @fn("conditional")
    def run(bot: SoulFireBot) -> EffectGen[A2 | None, E2 | EP, R2 | RP]:
        decision = predicate(bot)
        if decision if isinstance(decision, bool) else (yield from decision):
            return (yield from when_true.run(bot))
        if when_false is not None:
            return (yield from when_false.run(bot))
        return (yield from succeed(None))

    return define_behavior(run)


def fallback[A2, E2 = Never, R2 = Never](
    primary: BotBehavior[A2, E2, R2],
    *alternatives: BotBehavior[A2, E2, R2],
) -> BotBehavior[A2, E2, R2]:
    def run(bot: SoulFireBot) -> Effect[A2, E2, R2]:
        result = primary.run(bot)
        for behavior in alternatives:
            result = result.catch_all(lambda _, behavior=behavior: behavior.run(bot))
        return result

    return define_behavior(run)


def cleanup[A2, E2 = Never, R2 = Never](
    behavior: BotBehavior[A2, E2, R2],
    finalizer: BotBehavior[object, object],
) -> BotBehavior[A2, E2, R2]:
    return define_behavior(lambda bot: ensuring(behavior.run(bot), finalizer.run(bot).or_die()))


def scoped_lease[A2, E2 = Never, R2 = Never](
    behavior: BotBehavior[A2, E2, R2],
    *,
    ttl_seconds: int = 30,
) -> BotBehavior[A2, E2 | SoulFireOperationError, R2]:
    @fn("scoped_lease")
    def run(bot: SoulFireBot) -> EffectGen[A2, E2 | SoulFireOperationError, R2]:
        @gen
        def leased() -> EffectGen[A2, E2 | SoulFireOperationError, R2 | Scope]:
            yield from bot.acquire_control(ttl_seconds=ttl_seconds)
            return (yield from behavior.run(bot))

        return (yield from scoped(leased))

    return define_behavior(run)


@fn("complete_task")
def _complete_task(
    stream: Stream[BotTaskEvent, SoulFireOperationError],
) -> EffectGen[None, SoulFireOperationError]:
    def latest_task(latest: BotTask | None, event: BotTaskEvent) -> BotTask | None:
        return event.task if event.HasField("task") else latest

    task = yield from stream.run_fold(None, latest_task)
    if task is None:
        return (yield from fail(SoulFireStateError("Task stream ended without a terminal result")))
    if task.status != BOT_TASK_STATUS_COMPLETED:
        return (yield from fail(SoulFireTaskError(task)))


@dataclass(frozen=True, slots=True)
class CollectBlocks:
    block_ids: Sequence[str]
    tags: Sequence[str] = ()
    count: int = 1
    search_radius: int = 32
    allow_placing: bool = False
    require_line_of_sight: bool = False
    target_y_range: IntRange | None = None

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[int, SoulFireOperationError]:
        task = yield from bot.tasks.collect_blocks(
            self.block_ids,
            tags=self.tags,
            count=self.count,
            search_radius=self.search_radius,
            require_line_of_sight=self.require_line_of_sight,
            target_y_range=self.target_y_range,
            options=PathfindOptions(allow_mining=True, allow_placing=self.allow_placing),
        )
        return (yield from task.result()).blocks_broken


@dataclass(frozen=True, slots=True)
class FollowEntity:
    entity_id: int
    radius: float = 3

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
        yield from _complete_task(
            bot.tasks.run_follow_entity(
                self.entity_id,
                distance=self.radius,
                options=PathfindOptions(allow_mining=False, allow_placing=False),
            )
        )


@dataclass(frozen=True, slots=True)
class AttackNearest:
    entity_types: Sequence[str]
    radius: float = 32
    attack_range: float = 3
    sprinting: bool = False
    maximum_attacks: int = 0

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[bool, SoulFireOperationError]:
        response = yield from bot.list_nearby_entities(
            self.radius, entity_types=self.entity_types, include_players=False
        )
        if not response.entities:
            return False
        target = response.entities[0]
        yield from _complete_task(
            bot.tasks.run_attack_entity(
                target.entity_id,
                attack_range=self.attack_range,
                sprinting=self.sprinting,
                maximum_attacks=self.maximum_attacks,
                options=PathfindOptions(allow_mining=False, allow_placing=False),
            )
        )
        return True


@dataclass(frozen=True, slots=True)
class AutoEat:
    food_item_ids: Sequence[str]
    food_level: int = 14
    check_interval_ticks: int = 20
    maximum_meals: int = 0
    complete_when_no_food: bool = False
    restore_selected_slot: bool = True

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
        yield from _complete_task(
            bot.tasks.run_auto_eat(
                self.food_item_ids,
                food_level=self.food_level,
                check_interval_ticks=self.check_interval_ticks,
                maximum_meals=self.maximum_meals,
                complete_when_no_food=self.complete_when_no_food,
                restore_selected_slot=self.restore_selected_slot,
            )
        )


@dataclass(frozen=True, slots=True)
class AutoRespawn:
    respawn_delay_ticks: int = 0
    maximum_respawns: int = 0

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
        yield from _complete_task(
            bot.tasks.run_auto_respawn(
                respawn_delay_ticks=self.respawn_delay_ticks, maximum_respawns=self.maximum_respawns
            )
        )


@dataclass(frozen=True, slots=True)
class AutoTotem:
    check_interval_ticks: int = 20
    maximum_equips: int = 0
    complete_when_no_totem: bool = False
    replace_occupied_offhand: bool = False

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
        yield from _complete_task(
            bot.tasks.run_auto_totem(
                check_interval_ticks=self.check_interval_ticks,
                maximum_equips=self.maximum_equips,
                complete_when_no_totem=self.complete_when_no_totem,
                replace_occupied_offhand=self.replace_occupied_offhand,
            )
        )


@dataclass(frozen=True, slots=True)
class AutoArmor:
    check_interval_ticks: int = 20
    maximum_equips: int = 0
    complete_when_no_upgrade: bool = False

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
        yield from _complete_task(
            bot.tasks.run_auto_armor(
                check_interval_ticks=self.check_interval_ticks,
                maximum_equips=self.maximum_equips,
                complete_when_no_upgrade=self.complete_when_no_upgrade,
            )
        )


@dataclass(frozen=True, slots=True)
class BuildPlacement:
    against: BlockPosition
    face: BlockFace
    hotbar_slot: int | None = None


@dataclass(frozen=True, slots=True)
class Build:
    placements: Sequence[BuildPlacement]

    @fn("behavior.run")
    def run(self, bot: SoulFireBot) -> EffectGen[int, SoulFireOperationError]:
        placed = 0
        for placement in self.placements:
            if placement.hotbar_slot is not None:
                yield from bot.select_hotbar(placement.hotbar_slot)
            yield from bot.place_block(placement.against, placement.face, HAND_MAIN)
            placed += 1
        return placed


def _positive_integer(value: int, name: str) -> int:
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _non_negative_finite(value: float, name: str) -> float:
    if value < 0 or value == float("inf") or value != value:
        raise ValueError(f"{name} must be a finite non-negative number")
    return value


def _positive_finite(value: float, name: str) -> float:
    if value <= 0 or value == float("inf") or value != value:
        raise ValueError(f"{name} must be a finite positive number")
    return value
