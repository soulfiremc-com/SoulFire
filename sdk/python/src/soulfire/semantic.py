from __future__ import annotations

import re
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, NotRequired, TypedDict, Unpack, cast

from effect_py import Effect, EffectGen, Scope, acquire_release, fail, fn, gen, schedule
from effect_py.errors import catch_tag

from .actions import require_action
from .bot_live_pb2 import BotActionResult, BotChatEvent, BotEvent, BotEventFilter, ChatSource
from .chat_connect import ChatServiceClient
from .chat_pb2 import (
    ChatScope,
    SendCommandRequest,
    SendPublicChatRequest,
    SendWhisperRequest,
    TabCompleteRequest,
    TabCompleteResponse,
)
from .common_pb2 import BlockPosition, WorldPosition
from .domain_pb2 import BlockSnapshot, EntityReference, EntitySnapshot, PlayerSnapshot, Vec3
from .errors import (
    SoulFireContainerClosedError,
    SoulFireOperationError,
    SoulFireStateError,
    SoulFireTimeoutError,
    operation_error,
)
from .inventory_connect import InventoryServiceClient
from .inventory_pb2 import (
    INVENTORY_AREA_CONTAINER,
    INVENTORY_AREA_PLAYER,
    INVENTORY_RECOMMENDATION_KIND_ARMOR,
    INVENTORY_RECOMMENDATION_KIND_FOOD,
    INVENTORY_RECOMMENDATION_KIND_MELEE_WEAPON,
    INVENTORY_RECOMMENDATION_KIND_SCAFFOLD,
    INVENTORY_RECOMMENDATION_KIND_TOOL,
    CloseSemanticContainerRequest,
    ContainerSnapshot,
    CountItemsRequest,
    EquipItemRequest,
    FindInventorySlotsRequest,
    FindInventorySlotsResponse,
    GetContainerSnapshotRequest,
    InventoryArea,
    InventoryItemRecommendation,
    InventoryMutationResponse,
    InventoryRecommendationKind,
    InventoryScope,
    ItemSelector,
    MoveInventoryItemRequest,
    OpenBlockContainerRequest,
    RankInventoryItemsRequest,
    RankInventoryItemsResponse,
    SelectHotbarItemRequest,
    TossItemsRequest,
    TransferItemsRequest,
    UnequipItemRequest,
)
from .recipe_connect import RecipeServiceClient
from .recipe_pb2 import (
    BrewTaskResult,
    CanCraftRequest,
    CanCraftResponse,
    CraftTaskResult,
    ListRecipesRequest,
    ListRecipesResponse,
    ListVillagerTradesRequest,
    ListVillagerTradesResponse,
    SmeltTaskResult,
    VillagerTradeTaskResult,
)
from .registry_connect import RegistryServiceClient
from .registry_pb2 import (
    GetRegistryEntryRequest,
    GetRegistryEntryResponse,
    GetRegistryIdentityRequest,
    GetRegistryIdentityResponse,
    ListRegistryEntriesRequest,
    ListRegistryEntriesResponse,
    ListRegistryTagsRequest,
    ListRegistryTagsResponse,
    RegistryKind,
)
from .selectors import item_selector
from .streams import Stream
from .tasks import SoulFireTask, SoulFireTasks
from .transport import rpc, validate
from .world_connect import WorldServiceClient
from .world_pb2 import (
    QUERY_SORT_UNSPECIFIED,
    BlockSelector,
    CanSeeBlockRequest,
    CanSeeBlockResponse,
    EntitySelector,
    EstimateDigTimeRequest,
    EstimateDigTimeResponse,
    EstimateExplosionDamageRequest,
    EstimateExplosionDamageResponse,
    GetPlayerSnapshotRequest,
    GetWorldBlockRequest,
    GetWorldBlockResponse,
    GetWorldEntityRequest,
    GetWorldEntityResponse,
    QueryBlocksRequest,
    QueryBlocksResponse,
    QueryEntitiesRequest,
    QueryEntitiesResponse,
    QueryRegion,
    QuerySort,
    RaycastRequest,
    RaycastResponse,
)

type HeaderProvider = Callable[[dict[str, str] | None], dict[str, str] | None]


class InventoryRankingOptions(TypedDict):
    selector: NotRequired[ItemSelector]
    areas: NotRequired[Iterable[InventoryArea]]
    prefer_hotbar: NotRequired[bool]
    preferred_enchantment_ids: NotRequired[Iterable[str]]
    excluded_enchantment_ids: NotRequired[Iterable[str]]
    prefer_high_durability: NotRequired[bool]
    timeout_ms: NotRequired[int | None]


def _optional(value: object | None, field: str) -> dict[str, Any]:
    return {} if value is None else {field: value}


def _ranking_request(
    scope: InventoryScope,
    kind: InventoryRecommendationKind,
    *,
    selector: ItemSelector | None,
    areas: Iterable[InventoryArea],
    target_block: BlockPosition | None,
    equipment_slot: str | None,
    limit: int,
    prefer_hotbar: bool,
    preferred_enchantment_ids: Iterable[str],
    excluded_enchantment_ids: Iterable[str],
    prefer_high_durability: bool,
) -> RankInventoryItemsRequest:
    return RankInventoryItemsRequest(
        scope=scope,
        kind=kind,
        areas=areas,
        limit=limit,
        prefer_hotbar=prefer_hotbar,
        preferred_enchantment_ids=preferred_enchantment_ids,
        excluded_enchantment_ids=excluded_enchantment_ids,
        prefer_high_durability=prefer_high_durability,
        **_optional(selector, "selector"),
        **_optional(target_block, "target_block"),
        **_optional(equipment_slot, "equipment_slot"),
    )


def _first_recommendation(
    response: RankInventoryItemsResponse,
) -> InventoryItemRecommendation | None:
    return response.recommendations[0] if response.recommendations else None


type ChatMatcher = str | re.Pattern[str] | Callable[[BotChatEvent], bool]
type ChatEventStream = Callable[
    [BotEventFilter, int | None], Stream[BotEvent, SoulFireOperationError]
]


@dataclass(frozen=True, slots=True)
class ChatMatch:
    event: BotChatEvent
    captures: tuple[str, ...] = ()
    groups: Mapping[str, str] = MappingProxyType({})


def match_chat(event: BotChatEvent, matcher: ChatMatcher) -> ChatMatch | None:
    if isinstance(matcher, str):
        return ChatMatch(event) if matcher in event.plain_text else None
    if isinstance(matcher, re.Pattern):
        result = matcher.search(event.plain_text)
        if result is None:
            return None
        return ChatMatch(
            event,
            tuple(value or "" for value in result.groups()),
            MappingProxyType({name: value or "" for name, value in result.groupdict().items()}),
        )
    return ChatMatch(event) if matcher(event) else None


class SoulFireChat:
    def __init__(
        self,
        instance_id: str,
        bot_id: str,
        client: ChatServiceClient,
        headers: HeaderProvider,
        event_stream: ChatEventStream | None = None,
    ) -> None:
        self._scope = ChatScope(instance_id=instance_id, bot_id=bot_id)
        self._client = client
        self._headers = headers
        self._event_stream = event_stream

    @fn("SoulFireChat.send")
    def send(
        self, message: str, *, idempotency_key: str | None = None, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireChat.send",
            lambda: self._client.send_public_chat(
                SendPublicChatRequest(
                    scope=self._scope,
                    message=message,
                    **_optional(idempotency_key, "idempotency_key"),
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: require_action(response.result)))

    @fn("SoulFireChat.command")
    def command(
        self, command: str, *, idempotency_key: str | None = None, timeout_ms: int | None = None
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireChat.command",
            lambda: self._client.send_command(
                SendCommandRequest(
                    scope=self._scope,
                    command=command,
                    **_optional(idempotency_key, "idempotency_key"),
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: require_action(response.result)))

    @fn("SoulFireChat.whisper")
    def whisper(
        self,
        recipient: str,
        message: str,
        *,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[BotActionResult, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireChat.whisper",
            lambda: self._client.send_whisper(
                SendWhisperRequest(
                    scope=self._scope,
                    recipient=recipient,
                    message=message,
                    **_optional(idempotency_key, "idempotency_key"),
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        return (yield from validate(lambda: require_action(response.result)))

    @fn("SoulFireChat.complete")
    def complete(
        self, value: str, *, cursor: int | None = None, timeout_ms: int | None = None
    ) -> EffectGen[TabCompleteResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireChat.complete",
                lambda: self._client.tab_complete(
                    TabCompleteRequest(
                        scope=self._scope, input=value, **_optional(cursor, "cursor")
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    def watch(
        self,
        matcher: ChatMatcher,
        *,
        sources: Iterable[ChatSource] | None = None,
        timeout_ms: int | None = None,
    ) -> Stream[ChatMatch, SoulFireOperationError]:
        @gen
        def prepare() -> EffectGen[
            Stream[ChatMatch, SoulFireOperationError], SoulFireOperationError
        ]:
            if self._event_stream is None:
                return (yield from fail(SoulFireStateError("The bot event stream is unavailable")))
            accepted = None if sources is None else frozenset(sources)
            matches = (
                self._event_stream(BotEventFilter(include_chat=True), timeout_ms)
                .filter(
                    lambda envelope: (
                        envelope.WhichOneof("event") == "chat"
                        and (accepted is None or envelope.chat.source in accepted)
                    )
                )
                .map(lambda envelope: match_chat(envelope.chat, matcher))
            )
            return matches.filter(lambda match: match is not None).map(_require_chat_match)

        return Stream.unwrap(prepare)

    @fn("SoulFireChat.wait_for")
    def wait_for(
        self,
        matcher: ChatMatcher,
        *,
        sources: Iterable[ChatSource] | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[ChatMatch, SoulFireOperationError]:
        effect = self.watch(matcher, sources=sources, timeout_ms=timeout_ms).run_head()
        if timeout_ms is not None:
            effect = effect.pipe(
                schedule.timeout(timeout_ms / 1000),
                catch_tag(schedule.TimeoutException)(
                    lambda _: fail(SoulFireTimeoutError("Timed out waiting for chat"))
                ),
            )
        value = yield from effect
        if value is None:
            return (
                yield from fail(
                    SoulFireStateError("The bot event stream ended before chat matched")
                )
            )
        return value


def _require_chat_match(match: ChatMatch | None) -> ChatMatch:
    assert match is not None
    return match


class SoulFireWorld:
    def __init__(self, instance_id: str, bot_id: str, client: WorldServiceClient) -> None:
        self._instance_id = instance_id
        self._bot_id = bot_id
        self._client = client

    @fn("SoulFireWorld.player")
    def player(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[PlayerSnapshot, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireWorld.player",
            lambda: self._client.get_player_snapshot(
                GetPlayerSnapshotRequest(instance_id=self._instance_id, bot_id=self._bot_id),
                timeout_ms=timeout_ms,
            ),
        )
        return response.player

    @fn("SoulFireWorld.block")
    def block(
        self,
        position: BlockPosition,
        *,
        include_block_entity: bool = False,
        include_shapes: bool = False,
        timeout_ms: int | None = None,
    ) -> EffectGen[GetWorldBlockResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.block",
                lambda: self._client.get_world_block(
                    GetWorldBlockRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        position=position,
                        include_block_entity=include_block_entity,
                        include_shapes=include_shapes,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.query_blocks")
    def query_blocks(
        self,
        region: QueryRegion,
        selector: BlockSelector,
        *,
        sort: QuerySort = QUERY_SORT_UNSPECIFIED,
        page_size: int = 0,
        page_token: str = "",
        timeout_ms: int | None = None,
    ) -> EffectGen[QueryBlocksResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.query_blocks",
                lambda: self._client.query_blocks(
                    QueryBlocksRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        region=region,
                        selector=selector,
                        sort=sort,
                        page_size=page_size,
                        page_token=page_token,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.entity")
    def entity(
        self, reference: EntityReference, *, timeout_ms: int | None = None
    ) -> EffectGen[GetWorldEntityResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.entity",
                lambda: self._client.get_world_entity(
                    GetWorldEntityRequest(
                        instance_id=self._instance_id, bot_id=self._bot_id, entity=reference
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.query_entities")
    def query_entities(
        self,
        radius: float,
        selector: EntitySelector,
        *,
        origin: WorldPosition | None = None,
        sort: QuerySort = QUERY_SORT_UNSPECIFIED,
        page_size: int = 0,
        page_token: str = "",
        timeout_ms: int | None = None,
    ) -> EffectGen[QueryEntitiesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.query_entities",
                lambda: self._client.query_entities(
                    QueryEntitiesRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        radius=radius,
                        selector=selector,
                        sort=sort,
                        page_size=page_size,
                        page_token=page_token,
                        **_optional(origin, "origin"),
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.raycast")
    def raycast(
        self,
        origin: WorldPosition,
        direction: Vec3,
        maximum_distance: float,
        *,
        include_fluids: bool = False,
        include_entities: bool = True,
        timeout_ms: int | None = None,
    ) -> EffectGen[RaycastResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.raycast",
                lambda: self._client.raycast(
                    RaycastRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        origin=origin,
                        direction=direction,
                        maximum_distance=maximum_distance,
                        include_fluids=include_fluids,
                        include_entities=include_entities,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.raycast_from_player")
    def raycast_from_player(
        self,
        maximum_distance: float = 6,
        *,
        include_fluids: bool = False,
        include_entities: bool = True,
        timeout_ms: int | None = None,
    ) -> EffectGen[RaycastResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.raycast_from_player",
                lambda: self._client.raycast(
                    RaycastRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        maximum_distance=maximum_distance,
                        include_fluids=include_fluids,
                        include_entities=include_entities,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.block_at_cursor")
    def block_at_cursor(
        self, maximum_distance: float = 256, *, timeout_ms: int | None = None
    ) -> EffectGen[BlockSnapshot | None, SoulFireOperationError]:
        response = yield from self.raycast_from_player(
            maximum_distance, include_entities=False, timeout_ms=timeout_ms
        )
        return response.block if response.HasField("block") else None

    @fn("SoulFireWorld.entity_at_cursor")
    def entity_at_cursor(
        self, maximum_distance: float = 3.5, *, timeout_ms: int | None = None
    ) -> EffectGen[EntitySnapshot | None, SoulFireOperationError]:
        response = yield from self.raycast_from_player(
            maximum_distance, include_entities=True, timeout_ms=timeout_ms
        )
        return response.entity if response.HasField("entity") else None

    @fn("SoulFireWorld.estimate_explosion_damage")
    def estimate_explosion_damage(
        self,
        target: EntityReference,
        center: WorldPosition,
        power: float,
        *,
        timeout_ms: int | None = None,
    ) -> EffectGen[EstimateExplosionDamageResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.estimate_explosion_damage",
                lambda: self._client.estimate_explosion_damage(
                    EstimateExplosionDamageRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        target=target,
                        center=center,
                        power=power,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.can_see_block")
    def can_see_block(
        self, position: BlockPosition, *, timeout_ms: int | None = None
    ) -> EffectGen[CanSeeBlockResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.can_see_block",
                lambda: self._client.can_see_block(
                    CanSeeBlockRequest(
                        instance_id=self._instance_id, bot_id=self._bot_id, position=position
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireWorld.estimate_dig_time")
    def estimate_dig_time(
        self, position: BlockPosition, *, timeout_ms: int | None = None
    ) -> EffectGen[EstimateDigTimeResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireWorld.estimate_dig_time",
                lambda: self._client.estimate_dig_time(
                    EstimateDigTimeRequest(
                        instance_id=self._instance_id, bot_id=self._bot_id, position=position
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )


class _InventoryBase:
    def __init__(self, instance_id: str, bot_id: str, headers: HeaderProvider) -> None:
        self._scope = InventoryScope(instance_id=instance_id, bot_id=bot_id)
        self._headers = headers


class SoulFireContainer:
    def __init__(
        self,
        scope: InventoryScope,
        client: InventoryServiceClient,
        headers: HeaderProvider,
        snapshot: ContainerSnapshot,
    ) -> None:
        self._scope = scope
        self._client = client
        self._headers = headers
        self._snapshot = snapshot
        self._closed = False

    @property
    def snapshot(self) -> ContainerSnapshot:
        return self._snapshot

    @property
    def closed(self) -> bool:
        return self._closed

    @fn("SoulFireContainer.refresh")
    def refresh(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        yield from validate(self._require_open)
        response = yield from rpc(
            "SoulFireContainer.refresh",
            lambda: self._client.get_container_snapshot(
                GetContainerSnapshotRequest(scope=self._scope), timeout_ms=timeout_ms
            ),
        )
        if (
            response.container.container_id != self._snapshot.container_id
            or response.container.menu_id != self._snapshot.menu_id
        ):
            self._closed = True
            return (
                yield from fail(
                    operation_error(
                        "SoulFireContainer.refresh",
                        SoulFireContainerClosedError(self._snapshot.container_id),
                    )
                )
            )
        self._snapshot = response.container
        return self._snapshot

    @fn("SoulFireContainer.deposit")
    def deposit(
        self,
        selector: ItemSelector,
        count: int,
        *,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        return (
            yield from self._transfer(
                selector,
                count,
                from_area=INVENTORY_AREA_PLAYER,
                to_area=INVENTORY_AREA_CONTAINER,
                idempotency_key=idempotency_key,
                timeout_ms=timeout_ms,
            )
        )

    @fn("SoulFireContainer.withdraw")
    def withdraw(
        self,
        selector: ItemSelector,
        count: int,
        *,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        return (
            yield from self._transfer(
                selector,
                count,
                from_area=INVENTORY_AREA_CONTAINER,
                to_area=INVENTORY_AREA_PLAYER,
                idempotency_key=idempotency_key,
                timeout_ms=timeout_ms,
            )
        )

    @fn("SoulFireContainer.close")
    def close(
        self, *, idempotency_key: str | None = None, timeout_ms: int | None = None
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        if self._closed:
            return self._snapshot
        response = yield from rpc(
            "SoulFireContainer.close",
            lambda: self._client.close_semantic_container(
                CloseSemanticContainerRequest(
                    scope=self._scope,
                    container_id=self._snapshot.container_id,
                    **_optional(idempotency_key, "idempotency_key"),
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        self._closed = True
        self._snapshot = response.container
        return self._snapshot

    @fn("SoulFireContainer._transfer")
    def _transfer(
        self,
        selector: ItemSelector,
        count: int,
        *,
        from_area: InventoryArea,
        to_area: InventoryArea,
        idempotency_key: str | None,
        timeout_ms: int | None,
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        yield from validate(self._require_open)
        response = yield from rpc(
            "SoulFireContainer._transfer",
            lambda: self._client.transfer_items(
                TransferItemsRequest(
                    scope=self._scope,
                    selector=selector,
                    count=count,
                    to=to_area,
                    # Servers that predate menu ids (0) check the revision instead.
                    **(
                        {"expected_revision": self._snapshot.revision}
                        if self._snapshot.menu_id == 0
                        else {"menu_id": self._snapshot.menu_id}
                    ),
                    **_optional(idempotency_key, "idempotency_key"),
                    **{"from": cast(Any, from_area)},
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        self._snapshot = response.container
        return self._snapshot

    def _require_open(self) -> None:
        if self._closed:
            raise SoulFireContainerClosedError(self._snapshot.container_id)


class SoulFireInventory(_InventoryBase):
    def __init__(
        self, instance_id: str, bot_id: str, client: InventoryServiceClient, headers: HeaderProvider
    ) -> None:
        super().__init__(instance_id, bot_id, headers)
        self._client = client

    @fn("SoulFireInventory.snapshot")
    def snapshot(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[ContainerSnapshot, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireInventory.snapshot",
            lambda: self._client.get_container_snapshot(
                GetContainerSnapshotRequest(scope=self._scope), timeout_ms=timeout_ms
            ),
        )
        return response.container

    @fn("SoulFireInventory.count")
    def count(
        self,
        selector: str | ItemSelector,
        *,
        areas: Iterable[InventoryArea] = (),
        timeout_ms: int | None = None,
    ) -> EffectGen[int, SoulFireOperationError]:
        normalized = yield from validate(lambda: item_selector(selector))
        response = yield from rpc(
            "SoulFireInventory.count",
            lambda: self._client.count_items(
                CountItemsRequest(scope=self._scope, selector=normalized, areas=areas),
                timeout_ms=timeout_ms,
            ),
        )
        return response.count

    @fn("SoulFireInventory.find")
    def find(
        self,
        selector: ItemSelector,
        *,
        areas: Iterable[InventoryArea] = (),
        timeout_ms: int | None = None,
    ) -> EffectGen[FindInventorySlotsResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.find",
                lambda: self._client.find_inventory_slots(
                    FindInventorySlotsRequest(scope=self._scope, selector=selector, areas=areas),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.rank")
    def rank(
        self,
        kind: InventoryRecommendationKind,
        *,
        selector: ItemSelector | None = None,
        areas: Iterable[InventoryArea] = (),
        target_block: BlockPosition | None = None,
        equipment_slot: str | None = None,
        limit: int = 10,
        prefer_hotbar: bool = False,
        preferred_enchantment_ids: Iterable[str] = (),
        excluded_enchantment_ids: Iterable[str] = (),
        prefer_high_durability: bool = False,
        timeout_ms: int | None = None,
    ) -> EffectGen[RankInventoryItemsResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.rank",
                lambda: self._client.rank_inventory_items(
                    _ranking_request(
                        self._scope,
                        kind,
                        selector=selector,
                        areas=areas,
                        target_block=target_block,
                        equipment_slot=equipment_slot,
                        limit=limit,
                        prefer_hotbar=prefer_hotbar,
                        preferred_enchantment_ids=preferred_enchantment_ids,
                        excluded_enchantment_ids=excluded_enchantment_ids,
                        prefer_high_durability=prefer_high_durability,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.best_tool")
    def best_tool(
        self, target_block: BlockPosition, **options: Unpack[InventoryRankingOptions]
    ) -> EffectGen[InventoryItemRecommendation | None, SoulFireOperationError]:
        return _first_recommendation(
            (
                yield from self.rank(
                    INVENTORY_RECOMMENDATION_KIND_TOOL,
                    target_block=target_block,
                    limit=1,
                    **options,
                )
            )
        )

    @fn("SoulFireInventory.best_weapon")
    def best_weapon(
        self, **options: Unpack[InventoryRankingOptions]
    ) -> EffectGen[InventoryItemRecommendation | None, SoulFireOperationError]:
        return _first_recommendation(
            (yield from self.rank(INVENTORY_RECOMMENDATION_KIND_MELEE_WEAPON, limit=1, **options))
        )

    @fn("SoulFireInventory.best_armor")
    def best_armor(
        self, equipment_slot: str, **options: Unpack[InventoryRankingOptions]
    ) -> EffectGen[InventoryItemRecommendation | None, SoulFireOperationError]:
        return _first_recommendation(
            (
                yield from self.rank(
                    INVENTORY_RECOMMENDATION_KIND_ARMOR,
                    equipment_slot=equipment_slot,
                    limit=1,
                    **options,
                )
            )
        )

    @fn("SoulFireInventory.best_food")
    def best_food(
        self, **options: Unpack[InventoryRankingOptions]
    ) -> EffectGen[InventoryItemRecommendation | None, SoulFireOperationError]:
        return _first_recommendation(
            (yield from self.rank(INVENTORY_RECOMMENDATION_KIND_FOOD, limit=1, **options))
        )

    @fn("SoulFireInventory.best_scaffold")
    def best_scaffold(
        self, **options: Unpack[InventoryRankingOptions]
    ) -> EffectGen[InventoryItemRecommendation | None, SoulFireOperationError]:
        return _first_recommendation(
            (yield from self.rank(INVENTORY_RECOMMENDATION_KIND_SCAFFOLD, limit=1, **options))
        )

    @fn("SoulFireInventory.move")
    def move(
        self,
        source_slot: int,
        destination_slot: int,
        *,
        count: int | None = None,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.move",
                lambda: self._client.move_inventory_item(
                    MoveInventoryItemRequest(
                        scope=self._scope,
                        source_slot=source_slot,
                        destination_slot=destination_slot,
                        expected_revision=expected_revision,
                        **_optional(count, "count"),
                        **_optional(idempotency_key, "idempotency_key"),
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.transfer")
    def transfer(
        self,
        selector: ItemSelector,
        count: int,
        *,
        from_area: InventoryArea,
        to_area: InventoryArea,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.transfer",
                lambda: self._client.transfer_items(
                    TransferItemsRequest(
                        scope=self._scope,
                        selector=selector,
                        count=count,
                        to=to_area,
                        expected_revision=expected_revision,
                        **_optional(idempotency_key, "idempotency_key"),
                        **{"from": cast(Any, from_area)},
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.toss")
    def toss(
        self,
        selector: ItemSelector,
        count: int,
        *,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.toss",
                lambda: self._client.toss_items(
                    TossItemsRequest(
                        scope=self._scope,
                        selector=selector,
                        count=count,
                        expected_revision=expected_revision,
                        **_optional(idempotency_key, "idempotency_key"),
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.select_hotbar")
    def select_hotbar(
        self,
        *,
        slot: int | None = None,
        selector: ItemSelector | None = None,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        if (slot is None) == (selector is None):
            return (
                yield from fail(
                    operation_error(
                        "SoulFireInventory.select_hotbar",
                        ValueError("Provide exactly one of slot or selector"),
                    )
                )
            )
        return (
            yield from rpc(
                "SoulFireInventory.select_hotbar",
                lambda: self._client.select_hotbar_item(
                    SelectHotbarItemRequest(
                        scope=self._scope,
                        expected_revision=expected_revision,
                        **_optional(slot, "hotbar_slot"),
                        **_optional(selector, "selector"),
                        **_optional(idempotency_key, "idempotency_key"),
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.equip")
    def equip(
        self,
        selector: ItemSelector,
        equipment_slot: str,
        *,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.equip",
                lambda: self._client.equip_item(
                    EquipItemRequest(
                        scope=self._scope,
                        selector=selector,
                        equipment_slot=equipment_slot,
                        expected_revision=expected_revision,
                        **_optional(idempotency_key, "idempotency_key"),
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireInventory.unequip")
    def unequip(
        self,
        equipment_slot: str,
        *,
        destination_area: InventoryArea | None = None,
        expected_revision: int = 0,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[InventoryMutationResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireInventory.unequip",
                lambda: self._client.unequip_item(
                    UnequipItemRequest(
                        scope=self._scope,
                        equipment_slot=equipment_slot,
                        expected_revision=expected_revision,
                        **_optional(destination_area, "destination_area"),
                        **_optional(idempotency_key, "idempotency_key"),
                    ),
                    headers=self._headers(None),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    def open(
        self,
        position: BlockPosition,
        *,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> Effect[SoulFireContainer, SoulFireOperationError, Scope]:
        return acquire_release(
            self._open(position, idempotency_key=idempotency_key, timeout_ms=timeout_ms),
            lambda container, _: container.close(timeout_ms=timeout_ms).or_die(),
        )

    @fn("SoulFireInventory.open")
    def _open(
        self,
        position: BlockPosition,
        *,
        idempotency_key: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireContainer, SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireInventory.open",
            lambda: self._client.open_block_container(
                OpenBlockContainerRequest(
                    scope=self._scope,
                    position=position,
                    **_optional(idempotency_key, "idempotency_key"),
                ),
                headers=self._headers(None),
                timeout_ms=timeout_ms,
            ),
        )
        return SoulFireContainer(self._scope, self._client, self._headers, response.container)


class SoulFireRecipes:
    def __init__(
        self, scope: InventoryScope, client: RecipeServiceClient, tasks: SoulFireTasks
    ) -> None:
        self._scope = scope
        self._client = client
        self._tasks = tasks

    @fn("SoulFireRecipes.list")
    def list(
        self,
        *,
        result_item_id: str | None = None,
        ingredient: ItemSelector | None = None,
        recipe_types: Iterable[str] = (),
        page_size: int = 0,
        page_token: str = "",
        timeout_ms: int | None = None,
    ) -> EffectGen[ListRecipesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRecipes.list",
                lambda: self._client.list_recipes(
                    ListRecipesRequest(
                        scope=self._scope,
                        recipe_types=recipe_types,
                        page_size=page_size,
                        page_token=page_token,
                        **_optional(result_item_id, "result_item_id"),
                        **_optional(ingredient, "ingredient"),
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireRecipes.can_craft")
    def can_craft(
        self, recipe_id: str, *, count: int = 1, timeout_ms: int | None = None
    ) -> EffectGen[CanCraftResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRecipes.can_craft",
                lambda: self._client.can_craft(
                    CanCraftRequest(scope=self._scope, recipe_id=recipe_id, count=count),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireRecipes.list_villager_trades")
    def list_villager_trades(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[ListVillagerTradesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRecipes.list_villager_trades",
                lambda: self._client.list_villager_trades(
                    ListVillagerTradesRequest(scope=self._scope), timeout_ms=timeout_ms
                ),
            )
        )

    @fn("SoulFireRecipes.craft")
    def craft(
        self,
        recipe_id: str,
        *,
        count: int = 1,
        station: BlockPosition | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[CraftTaskResult], SoulFireOperationError]:
        return (
            yield from self._tasks.craft(
                recipe_id, count=count, station=station, timeout_ms=timeout_ms
            )
        )

    @fn("SoulFireRecipes.smelt")
    def smelt(
        self,
        input: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[SmeltTaskResult], SoulFireOperationError]:
        return (
            yield from self._tasks.smelt(
                input, count=count, fuel=fuel, station=station, timeout_ms=timeout_ms
            )
        )

    @fn("SoulFireRecipes.brew")
    def brew(
        self,
        input: ItemSelector,
        ingredient: ItemSelector,
        *,
        count: int = 1,
        fuel: ItemSelector | None = None,
        station: BlockPosition | None = None,
        expected_result: ItemSelector | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[BrewTaskResult], SoulFireOperationError]:
        return (
            yield from self._tasks.brew(
                input,
                ingredient,
                count=count,
                fuel=fuel,
                station=station,
                expected_result=expected_result,
                timeout_ms=timeout_ms,
            )
        )

    @fn("SoulFireRecipes.villager_trade")
    def villager_trade(
        self,
        offer_index: int,
        *,
        count: int = 1,
        expected_result: ItemSelector | None = None,
        close_when_done: bool = False,
        timeout_ms: int | None = None,
    ) -> EffectGen[SoulFireTask[VillagerTradeTaskResult], SoulFireOperationError]:
        return (
            yield from self._tasks.villager_trade(
                offer_index,
                count=count,
                expected_result=expected_result,
                close_when_done=close_when_done,
                timeout_ms=timeout_ms,
            )
        )


class SoulFireRegistry:
    def __init__(self, instance_id: str, bot_id: str, client: RegistryServiceClient) -> None:
        self._instance_id = instance_id
        self._bot_id = bot_id
        self._client = client

    @fn("SoulFireRegistry.identity")
    def identity(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[GetRegistryIdentityResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRegistry.identity",
                lambda: self._client.get_registry_identity(
                    GetRegistryIdentityRequest(instance_id=self._instance_id, bot_id=self._bot_id),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireRegistry.entries")
    def entries(
        self,
        kind: RegistryKind,
        *,
        id_prefix: str = "",
        tags: Iterable[str] = (),
        page_size: int = 0,
        page_token: str = "",
        timeout_ms: int | None = None,
    ) -> EffectGen[ListRegistryEntriesResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRegistry.entries",
                lambda: self._client.list_registry_entries(
                    ListRegistryEntriesRequest(
                        kind=kind,
                        id_prefix=id_prefix,
                        tags=tags,
                        page_size=page_size,
                        page_token=page_token,
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireRegistry.entry")
    def entry(
        self, kind: RegistryKind, item_id: str, *, timeout_ms: int | None = None
    ) -> EffectGen[GetRegistryEntryResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRegistry.entry",
                lambda: self._client.get_registry_entry(
                    GetRegistryEntryRequest(
                        kind=kind, id=item_id, instance_id=self._instance_id, bot_id=self._bot_id
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireRegistry.tags")
    def tags(
        self,
        kind: RegistryKind,
        *,
        prefix: str = "",
        page_size: int = 0,
        page_token: str = "",
        timeout_ms: int | None = None,
    ) -> EffectGen[ListRegistryTagsResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireRegistry.tags",
                lambda: self._client.list_registry_tags(
                    ListRegistryTagsRequest(
                        kind=kind,
                        prefix=prefix,
                        page_size=page_size,
                        page_token=page_token,
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )
