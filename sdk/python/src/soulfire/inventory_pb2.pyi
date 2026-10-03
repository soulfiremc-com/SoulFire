from google.api import annotations_pb2 as _annotations_pb2
from google.api import field_behavior_pb2 as _field_behavior_pb2
from soulfire import api_docs_pb2 as _api_docs_pb2
from soulfire import bot_pb2 as _bot_pb2
from soulfire import bot_live_pb2 as _bot_live_pb2
from soulfire import common_pb2 as _common_pb2
from soulfire import domain_pb2 as _domain_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class InventoryArea(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    INVENTORY_AREA_UNSPECIFIED: _ClassVar[InventoryArea]
    INVENTORY_AREA_CONTAINER: _ClassVar[InventoryArea]
    INVENTORY_AREA_MAIN: _ClassVar[InventoryArea]
    INVENTORY_AREA_HOTBAR: _ClassVar[InventoryArea]
    INVENTORY_AREA_ARMOR: _ClassVar[InventoryArea]
    INVENTORY_AREA_OFFHAND: _ClassVar[InventoryArea]
    INVENTORY_AREA_CRAFTING: _ClassVar[InventoryArea]
    INVENTORY_AREA_CURSOR: _ClassVar[InventoryArea]
    INVENTORY_AREA_PLAYER: _ClassVar[InventoryArea]

class InventoryRecommendationKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    INVENTORY_RECOMMENDATION_KIND_UNSPECIFIED: _ClassVar[InventoryRecommendationKind]
    INVENTORY_RECOMMENDATION_KIND_TOOL: _ClassVar[InventoryRecommendationKind]
    INVENTORY_RECOMMENDATION_KIND_MELEE_WEAPON: _ClassVar[InventoryRecommendationKind]
    INVENTORY_RECOMMENDATION_KIND_ARMOR: _ClassVar[InventoryRecommendationKind]
    INVENTORY_RECOMMENDATION_KIND_FOOD: _ClassVar[InventoryRecommendationKind]
    INVENTORY_RECOMMENDATION_KIND_SCAFFOLD: _ClassVar[InventoryRecommendationKind]
INVENTORY_AREA_UNSPECIFIED: InventoryArea
INVENTORY_AREA_CONTAINER: InventoryArea
INVENTORY_AREA_MAIN: InventoryArea
INVENTORY_AREA_HOTBAR: InventoryArea
INVENTORY_AREA_ARMOR: InventoryArea
INVENTORY_AREA_OFFHAND: InventoryArea
INVENTORY_AREA_CRAFTING: InventoryArea
INVENTORY_AREA_CURSOR: InventoryArea
INVENTORY_AREA_PLAYER: InventoryArea
INVENTORY_RECOMMENDATION_KIND_UNSPECIFIED: InventoryRecommendationKind
INVENTORY_RECOMMENDATION_KIND_TOOL: InventoryRecommendationKind
INVENTORY_RECOMMENDATION_KIND_MELEE_WEAPON: InventoryRecommendationKind
INVENTORY_RECOMMENDATION_KIND_ARMOR: InventoryRecommendationKind
INVENTORY_RECOMMENDATION_KIND_FOOD: InventoryRecommendationKind
INVENTORY_RECOMMENDATION_KIND_SCAFFOLD: InventoryRecommendationKind

class ItemSelector(_message.Message):
    __slots__ = ("item_ids", "tags", "fingerprint", "name_contains", "enchantment_ids", "minimum_count", "minimum_remaining_durability")
    ITEM_IDS_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    FINGERPRINT_FIELD_NUMBER: _ClassVar[int]
    NAME_CONTAINS_FIELD_NUMBER: _ClassVar[int]
    ENCHANTMENT_IDS_FIELD_NUMBER: _ClassVar[int]
    MINIMUM_COUNT_FIELD_NUMBER: _ClassVar[int]
    MINIMUM_REMAINING_DURABILITY_FIELD_NUMBER: _ClassVar[int]
    item_ids: _containers.RepeatedScalarFieldContainer[str]
    tags: _containers.RepeatedScalarFieldContainer[str]
    fingerprint: str
    name_contains: str
    enchantment_ids: _containers.RepeatedScalarFieldContainer[str]
    minimum_count: int
    minimum_remaining_durability: int
    def __init__(self, item_ids: _Optional[_Iterable[str]] = ..., tags: _Optional[_Iterable[str]] = ..., fingerprint: _Optional[str] = ..., name_contains: _Optional[str] = ..., enchantment_ids: _Optional[_Iterable[str]] = ..., minimum_count: _Optional[int] = ..., minimum_remaining_durability: _Optional[int] = ...) -> None: ...

class InventorySlotSnapshot(_message.Message):
    __slots__ = ("slot", "area", "item", "may_place", "may_pickup")
    SLOT_FIELD_NUMBER: _ClassVar[int]
    AREA_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    MAY_PLACE_FIELD_NUMBER: _ClassVar[int]
    MAY_PICKUP_FIELD_NUMBER: _ClassVar[int]
    slot: int
    area: InventoryArea
    item: _domain_pb2.ItemStackSnapshot
    may_place: bool
    may_pickup: bool
    def __init__(self, slot: _Optional[int] = ..., area: _Optional[_Union[InventoryArea, str]] = ..., item: _Optional[_Union[_domain_pb2.ItemStackSnapshot, _Mapping]] = ..., may_place: bool = ..., may_pickup: bool = ...) -> None: ...

class ContainerSnapshot(_message.Message):
    __slots__ = ("container_id", "state_id", "revision", "container_type", "title", "layout", "slots", "carried", "selected_hotbar_slot", "path_building_block_count", "menu_id")
    CONTAINER_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_ID_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_TYPE_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    LAYOUT_FIELD_NUMBER: _ClassVar[int]
    SLOTS_FIELD_NUMBER: _ClassVar[int]
    CARRIED_FIELD_NUMBER: _ClassVar[int]
    SELECTED_HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    PATH_BUILDING_BLOCK_COUNT_FIELD_NUMBER: _ClassVar[int]
    MENU_ID_FIELD_NUMBER: _ClassVar[int]
    container_id: int
    state_id: int
    revision: int
    container_type: str
    title: _domain_pb2.TextComponent
    layout: _bot_pb2.ContainerLayout
    slots: _containers.RepeatedCompositeFieldContainer[InventorySlotSnapshot]
    carried: _domain_pb2.ItemStackSnapshot
    selected_hotbar_slot: int
    path_building_block_count: int
    menu_id: int
    def __init__(self, container_id: _Optional[int] = ..., state_id: _Optional[int] = ..., revision: _Optional[int] = ..., container_type: _Optional[str] = ..., title: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., layout: _Optional[_Union[_bot_pb2.ContainerLayout, _Mapping]] = ..., slots: _Optional[_Iterable[_Union[InventorySlotSnapshot, _Mapping]]] = ..., carried: _Optional[_Union[_domain_pb2.ItemStackSnapshot, _Mapping]] = ..., selected_hotbar_slot: _Optional[int] = ..., path_building_block_count: _Optional[int] = ..., menu_id: _Optional[int] = ...) -> None: ...

class InventoryScope(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class GetContainerSnapshotRequest(_message.Message):
    __slots__ = ("scope",)
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ...) -> None: ...

class GetContainerSnapshotResponse(_message.Message):
    __slots__ = ("container",)
    CONTAINER_FIELD_NUMBER: _ClassVar[int]
    container: ContainerSnapshot
    def __init__(self, container: _Optional[_Union[ContainerSnapshot, _Mapping]] = ...) -> None: ...

class CountItemsRequest(_message.Message):
    __slots__ = ("scope", "selector", "areas")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    AREAS_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    selector: ItemSelector
    areas: _containers.RepeatedScalarFieldContainer[InventoryArea]
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., areas: _Optional[_Iterable[_Union[InventoryArea, str]]] = ...) -> None: ...

class CountItemsResponse(_message.Message):
    __slots__ = ("count",)
    COUNT_FIELD_NUMBER: _ClassVar[int]
    count: int
    def __init__(self, count: _Optional[int] = ...) -> None: ...

class FindInventorySlotsRequest(_message.Message):
    __slots__ = ("scope", "selector", "areas")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    AREAS_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    selector: ItemSelector
    areas: _containers.RepeatedScalarFieldContainer[InventoryArea]
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., areas: _Optional[_Iterable[_Union[InventoryArea, str]]] = ...) -> None: ...

class FindInventorySlotsResponse(_message.Message):
    __slots__ = ("slots", "revision")
    SLOTS_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    slots: _containers.RepeatedCompositeFieldContainer[InventorySlotSnapshot]
    revision: int
    def __init__(self, slots: _Optional[_Iterable[_Union[InventorySlotSnapshot, _Mapping]]] = ..., revision: _Optional[int] = ...) -> None: ...

class InventoryItemScoreFactor(_message.Message):
    __slots__ = ("name", "contribution", "detail")
    NAME_FIELD_NUMBER: _ClassVar[int]
    CONTRIBUTION_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    name: str
    contribution: float
    detail: str
    def __init__(self, name: _Optional[str] = ..., contribution: _Optional[float] = ..., detail: _Optional[str] = ...) -> None: ...

class InventoryItemRecommendation(_message.Message):
    __slots__ = ("slot", "score", "factors")
    SLOT_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    FACTORS_FIELD_NUMBER: _ClassVar[int]
    slot: InventorySlotSnapshot
    score: float
    factors: _containers.RepeatedCompositeFieldContainer[InventoryItemScoreFactor]
    def __init__(self, slot: _Optional[_Union[InventorySlotSnapshot, _Mapping]] = ..., score: _Optional[float] = ..., factors: _Optional[_Iterable[_Union[InventoryItemScoreFactor, _Mapping]]] = ...) -> None: ...

class RankInventoryItemsRequest(_message.Message):
    __slots__ = ("scope", "kind", "selector", "areas", "target_block", "equipment_slot", "limit", "prefer_hotbar", "preferred_enchantment_ids", "excluded_enchantment_ids", "prefer_high_durability")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    AREAS_FIELD_NUMBER: _ClassVar[int]
    TARGET_BLOCK_FIELD_NUMBER: _ClassVar[int]
    EQUIPMENT_SLOT_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    PREFER_HOTBAR_FIELD_NUMBER: _ClassVar[int]
    PREFERRED_ENCHANTMENT_IDS_FIELD_NUMBER: _ClassVar[int]
    EXCLUDED_ENCHANTMENT_IDS_FIELD_NUMBER: _ClassVar[int]
    PREFER_HIGH_DURABILITY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    kind: InventoryRecommendationKind
    selector: ItemSelector
    areas: _containers.RepeatedScalarFieldContainer[InventoryArea]
    target_block: _common_pb2.BlockPosition
    equipment_slot: str
    limit: int
    prefer_hotbar: bool
    preferred_enchantment_ids: _containers.RepeatedScalarFieldContainer[str]
    excluded_enchantment_ids: _containers.RepeatedScalarFieldContainer[str]
    prefer_high_durability: bool
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., kind: _Optional[_Union[InventoryRecommendationKind, str]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., areas: _Optional[_Iterable[_Union[InventoryArea, str]]] = ..., target_block: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., equipment_slot: _Optional[str] = ..., limit: _Optional[int] = ..., prefer_hotbar: bool = ..., preferred_enchantment_ids: _Optional[_Iterable[str]] = ..., excluded_enchantment_ids: _Optional[_Iterable[str]] = ..., prefer_high_durability: bool = ...) -> None: ...

class RankInventoryItemsResponse(_message.Message):
    __slots__ = ("recommendations", "revision")
    RECOMMENDATIONS_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    recommendations: _containers.RepeatedCompositeFieldContainer[InventoryItemRecommendation]
    revision: int
    def __init__(self, recommendations: _Optional[_Iterable[_Union[InventoryItemRecommendation, _Mapping]]] = ..., revision: _Optional[int] = ...) -> None: ...

class MoveInventoryItemRequest(_message.Message):
    __slots__ = ("scope", "source_slot", "destination_slot", "count", "expected_revision", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_SLOT_FIELD_NUMBER: _ClassVar[int]
    DESTINATION_SLOT_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    source_slot: int
    destination_slot: int
    count: int
    expected_revision: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., source_slot: _Optional[int] = ..., destination_slot: _Optional[int] = ..., count: _Optional[int] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class TransferItemsRequest(_message.Message):
    __slots__ = ("scope", "selector", "count", "to", "expected_revision", "idempotency_key", "menu_id")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    FROM_FIELD_NUMBER: _ClassVar[int]
    TO_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    MENU_ID_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    selector: ItemSelector
    count: int
    to: InventoryArea
    expected_revision: int
    idempotency_key: str
    menu_id: int
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., count: _Optional[int] = ..., to: _Optional[_Union[InventoryArea, str]] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ..., menu_id: _Optional[int] = ..., **kwargs) -> None: ...

class TossItemsRequest(_message.Message):
    __slots__ = ("scope", "selector", "count", "expected_revision", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    selector: ItemSelector
    count: int
    expected_revision: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., count: _Optional[int] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class SelectHotbarItemRequest(_message.Message):
    __slots__ = ("scope", "hotbar_slot", "selector", "expected_revision", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    hotbar_slot: int
    selector: ItemSelector
    expected_revision: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., hotbar_slot: _Optional[int] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class EquipItemRequest(_message.Message):
    __slots__ = ("scope", "selector", "equipment_slot", "expected_revision", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    EQUIPMENT_SLOT_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    selector: ItemSelector
    equipment_slot: str
    expected_revision: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., selector: _Optional[_Union[ItemSelector, _Mapping]] = ..., equipment_slot: _Optional[str] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class UnequipItemRequest(_message.Message):
    __slots__ = ("scope", "equipment_slot", "destination_area", "expected_revision", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    EQUIPMENT_SLOT_FIELD_NUMBER: _ClassVar[int]
    DESTINATION_AREA_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    equipment_slot: str
    destination_area: InventoryArea
    expected_revision: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., equipment_slot: _Optional[str] = ..., destination_area: _Optional[_Union[InventoryArea, str]] = ..., expected_revision: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class InventoryMutationResponse(_message.Message):
    __slots__ = ("action_id", "container")
    ACTION_ID_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_FIELD_NUMBER: _ClassVar[int]
    action_id: str
    container: ContainerSnapshot
    def __init__(self, action_id: _Optional[str] = ..., container: _Optional[_Union[ContainerSnapshot, _Mapping]] = ...) -> None: ...

class OpenBlockContainerRequest(_message.Message):
    __slots__ = ("scope", "position", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    position: _common_pb2.BlockPosition
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., idempotency_key: _Optional[str] = ...) -> None: ...

class CloseSemanticContainerRequest(_message.Message):
    __slots__ = ("scope", "container_id", "idempotency_key")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_ID_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    scope: InventoryScope
    container_id: int
    idempotency_key: str
    def __init__(self, scope: _Optional[_Union[InventoryScope, _Mapping]] = ..., container_id: _Optional[int] = ..., idempotency_key: _Optional[str] = ...) -> None: ...
