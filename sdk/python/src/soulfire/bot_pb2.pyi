import datetime

from soulfire import common_pb2 as _common_pb2
from soulfire import api_docs_pb2 as _api_docs_pb2
from google.api import annotations_pb2 as _annotations_pb2
from google.api import field_behavior_pb2 as _field_behavior_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf import struct_pb2 as _struct_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class BotDesiredState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOT_DESIRED_STATE_UNSPECIFIED: _ClassVar[BotDesiredState]
    BOT_DESIRED_STATE_STOPPED: _ClassVar[BotDesiredState]
    BOT_DESIRED_STATE_RUNNING: _ClassVar[BotDesiredState]

class BotRuntimeState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOT_RUNTIME_STATE_UNSPECIFIED: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_STOPPED: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_QUEUED: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_STARTING: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_RUNNING: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_RETRYING: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_STOPPING: _ClassVar[BotRuntimeState]
    BOT_RUNTIME_STATE_FAILED: _ClassVar[BotRuntimeState]

class BotConnectionPhase(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOT_CONNECTION_PHASE_UNSPECIFIED: _ClassVar[BotConnectionPhase]
    BOT_CONNECTION_PHASE_CONNECTING: _ClassVar[BotConnectionPhase]
    BOT_CONNECTION_PHASE_CONNECTED: _ClassVar[BotConnectionPhase]
    BOT_CONNECTION_PHASE_SPAWNED: _ClassVar[BotConnectionPhase]
    BOT_CONNECTION_PHASE_DIED: _ClassVar[BotConnectionPhase]
    BOT_CONNECTION_PHASE_DISCONNECTED: _ClassVar[BotConnectionPhase]

class GameMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GAME_MODE_UNSPECIFIED: _ClassVar[GameMode]
    GAME_MODE_SURVIVAL: _ClassVar[GameMode]
    GAME_MODE_CREATIVE: _ClassVar[GameMode]
    GAME_MODE_ADVENTURE: _ClassVar[GameMode]
    GAME_MODE_SPECTATOR: _ClassVar[GameMode]

class ClickType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CLICK_TYPE_UNSPECIFIED: _ClassVar[ClickType]
    LEFT_CLICK: _ClassVar[ClickType]
    RIGHT_CLICK: _ClassVar[ClickType]
    SHIFT_LEFT_CLICK: _ClassVar[ClickType]
    DROP_ONE: _ClassVar[ClickType]
    DROP_ALL: _ClassVar[ClickType]
    SWAP_HOTBAR: _ClassVar[ClickType]
    MIDDLE_CLICK: _ClassVar[ClickType]

class SlotRegionType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SLOT_REGION_NORMAL: _ClassVar[SlotRegionType]
    SLOT_REGION_OUTPUT: _ClassVar[SlotRegionType]
    SLOT_REGION_DISPLAY: _ClassVar[SlotRegionType]
    SLOT_REGION_HOTBAR: _ClassVar[SlotRegionType]
    SLOT_REGION_ARMOR: _ClassVar[SlotRegionType]

class MouseButton(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MOUSE_BUTTON_UNSPECIFIED: _ClassVar[MouseButton]
    LEFT_BUTTON: _ClassVar[MouseButton]
    RIGHT_BUTTON: _ClassVar[MouseButton]

class DialogType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIALOG_TYPE_UNSPECIFIED: _ClassVar[DialogType]
    DIALOG_TYPE_NOTICE: _ClassVar[DialogType]
    DIALOG_TYPE_CONFIRMATION: _ClassVar[DialogType]
    DIALOG_TYPE_MULTI_ACTION: _ClassVar[DialogType]
    DIALOG_TYPE_SERVER_LINKS: _ClassVar[DialogType]
    DIALOG_TYPE_DIALOG_LIST: _ClassVar[DialogType]

class DialogAfterAction(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIALOG_AFTER_ACTION_UNSPECIFIED: _ClassVar[DialogAfterAction]
    DIALOG_AFTER_ACTION_CLOSE: _ClassVar[DialogAfterAction]
    DIALOG_AFTER_ACTION_NONE: _ClassVar[DialogAfterAction]
    DIALOG_AFTER_ACTION_WAIT_FOR_RESPONSE: _ClassVar[DialogAfterAction]
BOT_DESIRED_STATE_UNSPECIFIED: BotDesiredState
BOT_DESIRED_STATE_STOPPED: BotDesiredState
BOT_DESIRED_STATE_RUNNING: BotDesiredState
BOT_RUNTIME_STATE_UNSPECIFIED: BotRuntimeState
BOT_RUNTIME_STATE_STOPPED: BotRuntimeState
BOT_RUNTIME_STATE_QUEUED: BotRuntimeState
BOT_RUNTIME_STATE_STARTING: BotRuntimeState
BOT_RUNTIME_STATE_RUNNING: BotRuntimeState
BOT_RUNTIME_STATE_RETRYING: BotRuntimeState
BOT_RUNTIME_STATE_STOPPING: BotRuntimeState
BOT_RUNTIME_STATE_FAILED: BotRuntimeState
BOT_CONNECTION_PHASE_UNSPECIFIED: BotConnectionPhase
BOT_CONNECTION_PHASE_CONNECTING: BotConnectionPhase
BOT_CONNECTION_PHASE_CONNECTED: BotConnectionPhase
BOT_CONNECTION_PHASE_SPAWNED: BotConnectionPhase
BOT_CONNECTION_PHASE_DIED: BotConnectionPhase
BOT_CONNECTION_PHASE_DISCONNECTED: BotConnectionPhase
GAME_MODE_UNSPECIFIED: GameMode
GAME_MODE_SURVIVAL: GameMode
GAME_MODE_CREATIVE: GameMode
GAME_MODE_ADVENTURE: GameMode
GAME_MODE_SPECTATOR: GameMode
CLICK_TYPE_UNSPECIFIED: ClickType
LEFT_CLICK: ClickType
RIGHT_CLICK: ClickType
SHIFT_LEFT_CLICK: ClickType
DROP_ONE: ClickType
DROP_ALL: ClickType
SWAP_HOTBAR: ClickType
MIDDLE_CLICK: ClickType
SLOT_REGION_NORMAL: SlotRegionType
SLOT_REGION_OUTPUT: SlotRegionType
SLOT_REGION_DISPLAY: SlotRegionType
SLOT_REGION_HOTBAR: SlotRegionType
SLOT_REGION_ARMOR: SlotRegionType
MOUSE_BUTTON_UNSPECIFIED: MouseButton
LEFT_BUTTON: MouseButton
RIGHT_BUTTON: MouseButton
DIALOG_TYPE_UNSPECIFIED: DialogType
DIALOG_TYPE_NOTICE: DialogType
DIALOG_TYPE_CONFIRMATION: DialogType
DIALOG_TYPE_MULTI_ACTION: DialogType
DIALOG_TYPE_SERVER_LINKS: DialogType
DIALOG_TYPE_DIALOG_LIST: DialogType
DIALOG_AFTER_ACTION_UNSPECIFIED: DialogAfterAction
DIALOG_AFTER_ACTION_CLOSE: DialogAfterAction
DIALOG_AFTER_ACTION_NONE: DialogAfterAction
DIALOG_AFTER_ACTION_WAIT_FOR_RESPONSE: DialogAfterAction

class BotInfoRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotListRequest(_message.Message):
    __slots__ = ("instance_id",)
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    def __init__(self, instance_id: _Optional[str] = ...) -> None: ...

class BotStatus(_message.Message):
    __slots__ = ("profile_id", "desired_state", "runtime_state", "last_error", "updated_at")
    PROFILE_ID_FIELD_NUMBER: _ClassVar[int]
    DESIRED_STATE_FIELD_NUMBER: _ClassVar[int]
    RUNTIME_STATE_FIELD_NUMBER: _ClassVar[int]
    LAST_ERROR_FIELD_NUMBER: _ClassVar[int]
    UPDATED_AT_FIELD_NUMBER: _ClassVar[int]
    profile_id: str
    desired_state: BotDesiredState
    runtime_state: BotRuntimeState
    last_error: str
    updated_at: _timestamp_pb2.Timestamp
    def __init__(self, profile_id: _Optional[str] = ..., desired_state: _Optional[_Union[BotDesiredState, str]] = ..., runtime_state: _Optional[_Union[BotRuntimeState, str]] = ..., last_error: _Optional[str] = ..., updated_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class BotFleetSummary(_message.Message):
    __slots__ = ("total_bots", "desired_bots", "online_bots", "starting_bots", "retrying_bots", "failed_bots")
    TOTAL_BOTS_FIELD_NUMBER: _ClassVar[int]
    DESIRED_BOTS_FIELD_NUMBER: _ClassVar[int]
    ONLINE_BOTS_FIELD_NUMBER: _ClassVar[int]
    STARTING_BOTS_FIELD_NUMBER: _ClassVar[int]
    RETRYING_BOTS_FIELD_NUMBER: _ClassVar[int]
    FAILED_BOTS_FIELD_NUMBER: _ClassVar[int]
    total_bots: int
    desired_bots: int
    online_bots: int
    starting_bots: int
    retrying_bots: int
    failed_bots: int
    def __init__(self, total_bots: _Optional[int] = ..., desired_bots: _Optional[int] = ..., online_bots: _Optional[int] = ..., starting_bots: _Optional[int] = ..., retrying_bots: _Optional[int] = ..., failed_bots: _Optional[int] = ...) -> None: ...

class SetBotsDesiredStateRequest(_message.Message):
    __slots__ = ("instance_id", "bot_ids", "desired_state")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_IDS_FIELD_NUMBER: _ClassVar[int]
    DESIRED_STATE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_ids: _containers.RepeatedScalarFieldContainer[str]
    desired_state: BotDesiredState
    def __init__(self, instance_id: _Optional[str] = ..., bot_ids: _Optional[_Iterable[str]] = ..., desired_state: _Optional[_Union[BotDesiredState, str]] = ...) -> None: ...

class SetBotsDesiredStateResponse(_message.Message):
    __slots__ = ("bots",)
    BOTS_FIELD_NUMBER: _ClassVar[int]
    bots: _containers.RepeatedCompositeFieldContainer[BotStatus]
    def __init__(self, bots: _Optional[_Iterable[_Union[BotStatus, _Mapping]]] = ...) -> None: ...

class RestartBotsRequest(_message.Message):
    __slots__ = ("instance_id", "bot_ids")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_IDS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, instance_id: _Optional[str] = ..., bot_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class RestartBotsResponse(_message.Message):
    __slots__ = ("bots",)
    BOTS_FIELD_NUMBER: _ClassVar[int]
    bots: _containers.RepeatedCompositeFieldContainer[BotStatus]
    def __init__(self, bots: _Optional[_Iterable[_Union[BotStatus, _Mapping]]] = ...) -> None: ...

class WatchBotStatusesRequest(_message.Message):
    __slots__ = ("instance_id",)
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    def __init__(self, instance_id: _Optional[str] = ...) -> None: ...

class BotStatusSnapshot(_message.Message):
    __slots__ = ("bots",)
    BOTS_FIELD_NUMBER: _ClassVar[int]
    bots: _containers.RepeatedCompositeFieldContainer[BotStatus]
    def __init__(self, bots: _Optional[_Iterable[_Union[BotStatus, _Mapping]]] = ...) -> None: ...

class WatchBotStatusesResponse(_message.Message):
    __slots__ = ("snapshot", "update", "removed_bot_id")
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    UPDATE_FIELD_NUMBER: _ClassVar[int]
    REMOVED_BOT_ID_FIELD_NUMBER: _ClassVar[int]
    snapshot: BotStatusSnapshot
    update: BotStatus
    removed_bot_id: str
    def __init__(self, snapshot: _Optional[_Union[BotStatusSnapshot, _Mapping]] = ..., update: _Optional[_Union[BotStatus, _Mapping]] = ..., removed_bot_id: _Optional[str] = ...) -> None: ...

class BotListEntry(_message.Message):
    __slots__ = ("profile_id", "is_online", "live_state", "ping_ms", "connection_phase", "account_name", "status")
    PROFILE_ID_FIELD_NUMBER: _ClassVar[int]
    IS_ONLINE_FIELD_NUMBER: _ClassVar[int]
    LIVE_STATE_FIELD_NUMBER: _ClassVar[int]
    PING_MS_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_PHASE_FIELD_NUMBER: _ClassVar[int]
    ACCOUNT_NAME_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    profile_id: str
    is_online: bool
    live_state: BotLiveState
    ping_ms: int
    connection_phase: BotConnectionPhase
    account_name: str
    status: BotStatus
    def __init__(self, profile_id: _Optional[str] = ..., is_online: bool = ..., live_state: _Optional[_Union[BotLiveState, _Mapping]] = ..., ping_ms: _Optional[int] = ..., connection_phase: _Optional[_Union[BotConnectionPhase, str]] = ..., account_name: _Optional[str] = ..., status: _Optional[_Union[BotStatus, _Mapping]] = ...) -> None: ...

class BotListResponse(_message.Message):
    __slots__ = ("bots",)
    BOTS_FIELD_NUMBER: _ClassVar[int]
    bots: _containers.RepeatedCompositeFieldContainer[BotListEntry]
    def __init__(self, bots: _Optional[_Iterable[_Union[BotListEntry, _Mapping]]] = ...) -> None: ...

class InventorySlot(_message.Message):
    __slots__ = ("slot", "item_id", "count", "display_name", "icon_base64", "icon_mime_type")
    SLOT_FIELD_NUMBER: _ClassVar[int]
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    ICON_BASE64_FIELD_NUMBER: _ClassVar[int]
    ICON_MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    slot: int
    item_id: str
    count: int
    display_name: str
    icon_base64: str
    icon_mime_type: str
    def __init__(self, slot: _Optional[int] = ..., item_id: _Optional[str] = ..., count: _Optional[int] = ..., display_name: _Optional[str] = ..., icon_base64: _Optional[str] = ..., icon_mime_type: _Optional[str] = ...) -> None: ...

class BotLiveState(_message.Message):
    __slots__ = ("x", "y", "z", "xRot", "yRot", "health", "max_health", "food_level", "saturation_level", "inventory", "selected_hotbar_slot", "dimension", "experience_level", "experience_progress", "skin_texture_hash", "game_mode")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    XROT_FIELD_NUMBER: _ClassVar[int]
    YROT_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    MAX_HEALTH_FIELD_NUMBER: _ClassVar[int]
    FOOD_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SATURATION_LEVEL_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_FIELD_NUMBER: _ClassVar[int]
    SELECTED_HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    EXPERIENCE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    EXPERIENCE_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    SKIN_TEXTURE_HASH_FIELD_NUMBER: _ClassVar[int]
    GAME_MODE_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    xRot: float
    yRot: float
    health: float
    max_health: float
    food_level: int
    saturation_level: float
    inventory: _containers.RepeatedCompositeFieldContainer[InventorySlot]
    selected_hotbar_slot: int
    dimension: str
    experience_level: int
    experience_progress: float
    skin_texture_hash: str
    game_mode: GameMode
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., xRot: _Optional[float] = ..., yRot: _Optional[float] = ..., health: _Optional[float] = ..., max_health: _Optional[float] = ..., food_level: _Optional[int] = ..., saturation_level: _Optional[float] = ..., inventory: _Optional[_Iterable[_Union[InventorySlot, _Mapping]]] = ..., selected_hotbar_slot: _Optional[int] = ..., dimension: _Optional[str] = ..., experience_level: _Optional[int] = ..., experience_progress: _Optional[float] = ..., skin_texture_hash: _Optional[str] = ..., game_mode: _Optional[_Union[GameMode, str]] = ...) -> None: ...

class BotProxyInfo(_message.Message):
    __slots__ = ("type", "address")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    ADDRESS_FIELD_NUMBER: _ClassVar[int]
    type: _common_pb2.ProxyProto.Type
    address: str
    def __init__(self, type: _Optional[_Union[_common_pb2.ProxyProto.Type, str]] = ..., address: _Optional[str] = ...) -> None: ...

class BotConnectionInfo(_message.Message):
    __slots__ = ("proxy",)
    PROXY_FIELD_NUMBER: _ClassVar[int]
    proxy: BotProxyInfo
    def __init__(self, proxy: _Optional[_Union[BotProxyInfo, _Mapping]] = ...) -> None: ...

class BotInfoResponse(_message.Message):
    __slots__ = ("live_state", "status", "connection")
    LIVE_STATE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_FIELD_NUMBER: _ClassVar[int]
    live_state: BotLiveState
    status: BotStatus
    connection: BotConnectionInfo
    def __init__(self, live_state: _Optional[_Union[BotLiveState, _Mapping]] = ..., status: _Optional[_Union[BotStatus, _Mapping]] = ..., connection: _Optional[_Union[BotConnectionInfo, _Mapping]] = ...) -> None: ...

class BotUpdateConfigEntryRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "namespace", "key", "value")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    KEY_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    namespace: str
    key: str
    value: _struct_pb2.Value
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., namespace: _Optional[str] = ..., key: _Optional[str] = ..., value: _Optional[_Union[_struct_pb2.Value, _Mapping]] = ...) -> None: ...

class BotUpdateConfigEntryResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class BotRenderPovRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "width", "height", "max_distance", "fov", "camera_x", "camera_y", "camera_z", "y_rot", "x_rot", "include_hud", "include_hands", "include_debug_trace")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    MAX_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    FOV_FIELD_NUMBER: _ClassVar[int]
    CAMERA_X_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Y_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Z_FIELD_NUMBER: _ClassVar[int]
    Y_ROT_FIELD_NUMBER: _ClassVar[int]
    X_ROT_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_HUD_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_HANDS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_DEBUG_TRACE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    width: int
    height: int
    max_distance: int
    fov: float
    camera_x: float
    camera_y: float
    camera_z: float
    y_rot: float
    x_rot: float
    include_hud: bool
    include_hands: bool
    include_debug_trace: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., max_distance: _Optional[int] = ..., fov: _Optional[float] = ..., camera_x: _Optional[float] = ..., camera_y: _Optional[float] = ..., camera_z: _Optional[float] = ..., y_rot: _Optional[float] = ..., x_rot: _Optional[float] = ..., include_hud: bool = ..., include_hands: bool = ..., include_debug_trace: bool = ...) -> None: ...

class BotRenderPovResponse(_message.Message):
    __slots__ = ("image_base64", "image_mime_type", "metadata", "debug_trace")
    IMAGE_BASE64_FIELD_NUMBER: _ClassVar[int]
    IMAGE_MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    DEBUG_TRACE_FIELD_NUMBER: _ClassVar[int]
    image_base64: str
    image_mime_type: str
    metadata: BotRenderPovMetadata
    debug_trace: BotRenderPovDebugTrace
    def __init__(self, image_base64: _Optional[str] = ..., image_mime_type: _Optional[str] = ..., metadata: _Optional[_Union[BotRenderPovMetadata, _Mapping]] = ..., debug_trace: _Optional[_Union[BotRenderPovDebugTrace, _Mapping]] = ...) -> None: ...

class BotRenderPovMetadata(_message.Message):
    __slots__ = ("width", "height", "fov", "max_distance", "camera_x", "camera_y", "camera_z", "y_rot", "x_rot", "included_hud", "included_hands")
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    FOV_FIELD_NUMBER: _ClassVar[int]
    MAX_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    CAMERA_X_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Y_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Z_FIELD_NUMBER: _ClassVar[int]
    Y_ROT_FIELD_NUMBER: _ClassVar[int]
    X_ROT_FIELD_NUMBER: _ClassVar[int]
    INCLUDED_HUD_FIELD_NUMBER: _ClassVar[int]
    INCLUDED_HANDS_FIELD_NUMBER: _ClassVar[int]
    width: int
    height: int
    fov: float
    max_distance: int
    camera_x: float
    camera_y: float
    camera_z: float
    y_rot: float
    x_rot: float
    included_hud: bool
    included_hands: bool
    def __init__(self, width: _Optional[int] = ..., height: _Optional[int] = ..., fov: _Optional[float] = ..., max_distance: _Optional[int] = ..., camera_x: _Optional[float] = ..., camera_y: _Optional[float] = ..., camera_z: _Optional[float] = ..., y_rot: _Optional[float] = ..., x_rot: _Optional[float] = ..., included_hud: bool = ..., included_hands: bool = ...) -> None: ...

class BotRenderPovDebugTrace(_message.Message):
    __slots__ = ("render_id", "chunks_considered", "chunks_loaded", "sections_visible", "sections_meshed", "section_cache_hits", "section_cache_misses", "block_quads", "entities_considered", "entities_visible", "billboards", "weather_billboards", "vanilla_block_geometry_hits", "vanilla_block_geometry_fallbacks", "resource_block_geometry_fallbacks", "inventory_icon_ignored", "unknown_render_pipelines", "runtime_texture_mirror_skips", "opaque_triangles", "cutout_triangles", "translucent_triangles", "world_collect_nanos", "dynamic_collect_nanos", "raster_nanos", "total_nanos", "text_submissions", "text_samples", "notable_events", "detailed_failures")
    RENDER_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_CONSIDERED_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_LOADED_FIELD_NUMBER: _ClassVar[int]
    SECTIONS_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    SECTIONS_MESHED_FIELD_NUMBER: _ClassVar[int]
    SECTION_CACHE_HITS_FIELD_NUMBER: _ClassVar[int]
    SECTION_CACHE_MISSES_FIELD_NUMBER: _ClassVar[int]
    BLOCK_QUADS_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_CONSIDERED_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    BILLBOARDS_FIELD_NUMBER: _ClassVar[int]
    WEATHER_BILLBOARDS_FIELD_NUMBER: _ClassVar[int]
    VANILLA_BLOCK_GEOMETRY_HITS_FIELD_NUMBER: _ClassVar[int]
    VANILLA_BLOCK_GEOMETRY_FALLBACKS_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_BLOCK_GEOMETRY_FALLBACKS_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_ICON_IGNORED_FIELD_NUMBER: _ClassVar[int]
    UNKNOWN_RENDER_PIPELINES_FIELD_NUMBER: _ClassVar[int]
    RUNTIME_TEXTURE_MIRROR_SKIPS_FIELD_NUMBER: _ClassVar[int]
    OPAQUE_TRIANGLES_FIELD_NUMBER: _ClassVar[int]
    CUTOUT_TRIANGLES_FIELD_NUMBER: _ClassVar[int]
    TRANSLUCENT_TRIANGLES_FIELD_NUMBER: _ClassVar[int]
    WORLD_COLLECT_NANOS_FIELD_NUMBER: _ClassVar[int]
    DYNAMIC_COLLECT_NANOS_FIELD_NUMBER: _ClassVar[int]
    RASTER_NANOS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_NANOS_FIELD_NUMBER: _ClassVar[int]
    TEXT_SUBMISSIONS_FIELD_NUMBER: _ClassVar[int]
    TEXT_SAMPLES_FIELD_NUMBER: _ClassVar[int]
    NOTABLE_EVENTS_FIELD_NUMBER: _ClassVar[int]
    DETAILED_FAILURES_FIELD_NUMBER: _ClassVar[int]
    render_id: int
    chunks_considered: int
    chunks_loaded: int
    sections_visible: int
    sections_meshed: int
    section_cache_hits: int
    section_cache_misses: int
    block_quads: int
    entities_considered: int
    entities_visible: int
    billboards: int
    weather_billboards: int
    vanilla_block_geometry_hits: int
    vanilla_block_geometry_fallbacks: int
    resource_block_geometry_fallbacks: int
    inventory_icon_ignored: int
    unknown_render_pipelines: int
    runtime_texture_mirror_skips: int
    opaque_triangles: int
    cutout_triangles: int
    translucent_triangles: int
    world_collect_nanos: int
    dynamic_collect_nanos: int
    raster_nanos: int
    total_nanos: int
    text_submissions: int
    text_samples: _containers.RepeatedCompositeFieldContainer[BotRenderPovTextSubmission]
    notable_events: _containers.RepeatedScalarFieldContainer[str]
    detailed_failures: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, render_id: _Optional[int] = ..., chunks_considered: _Optional[int] = ..., chunks_loaded: _Optional[int] = ..., sections_visible: _Optional[int] = ..., sections_meshed: _Optional[int] = ..., section_cache_hits: _Optional[int] = ..., section_cache_misses: _Optional[int] = ..., block_quads: _Optional[int] = ..., entities_considered: _Optional[int] = ..., entities_visible: _Optional[int] = ..., billboards: _Optional[int] = ..., weather_billboards: _Optional[int] = ..., vanilla_block_geometry_hits: _Optional[int] = ..., vanilla_block_geometry_fallbacks: _Optional[int] = ..., resource_block_geometry_fallbacks: _Optional[int] = ..., inventory_icon_ignored: _Optional[int] = ..., unknown_render_pipelines: _Optional[int] = ..., runtime_texture_mirror_skips: _Optional[int] = ..., opaque_triangles: _Optional[int] = ..., cutout_triangles: _Optional[int] = ..., translucent_triangles: _Optional[int] = ..., world_collect_nanos: _Optional[int] = ..., dynamic_collect_nanos: _Optional[int] = ..., raster_nanos: _Optional[int] = ..., total_nanos: _Optional[int] = ..., text_submissions: _Optional[int] = ..., text_samples: _Optional[_Iterable[_Union[BotRenderPovTextSubmission, _Mapping]]] = ..., notable_events: _Optional[_Iterable[str]] = ..., detailed_failures: _Optional[_Iterable[str]] = ...) -> None: ...

class BotRenderPovTextSubmission(_message.Message):
    __slots__ = ("source", "text", "shadow", "display_mode", "light", "color", "background_color", "outline_color")
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SHADOW_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_MODE_FIELD_NUMBER: _ClassVar[int]
    LIGHT_FIELD_NUMBER: _ClassVar[int]
    COLOR_FIELD_NUMBER: _ClassVar[int]
    BACKGROUND_COLOR_FIELD_NUMBER: _ClassVar[int]
    OUTLINE_COLOR_FIELD_NUMBER: _ClassVar[int]
    source: str
    text: str
    shadow: bool
    display_mode: str
    light: int
    color: str
    background_color: str
    outline_color: str
    def __init__(self, source: _Optional[str] = ..., text: _Optional[str] = ..., shadow: bool = ..., display_mode: _Optional[str] = ..., light: _Optional[int] = ..., color: _Optional[str] = ..., background_color: _Optional[str] = ..., outline_color: _Optional[str] = ...) -> None: ...

class BotWatchPovRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "width", "height", "max_distance", "fov", "camera_x", "camera_y", "camera_z", "y_rot", "x_rot", "include_hud", "include_hands", "include_debug_trace", "interval_ms")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    MAX_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    FOV_FIELD_NUMBER: _ClassVar[int]
    CAMERA_X_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Y_FIELD_NUMBER: _ClassVar[int]
    CAMERA_Z_FIELD_NUMBER: _ClassVar[int]
    Y_ROT_FIELD_NUMBER: _ClassVar[int]
    X_ROT_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_HUD_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_HANDS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_DEBUG_TRACE_FIELD_NUMBER: _ClassVar[int]
    INTERVAL_MS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    width: int
    height: int
    max_distance: int
    fov: float
    camera_x: float
    camera_y: float
    camera_z: float
    y_rot: float
    x_rot: float
    include_hud: bool
    include_hands: bool
    include_debug_trace: bool
    interval_ms: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., max_distance: _Optional[int] = ..., fov: _Optional[float] = ..., camera_x: _Optional[float] = ..., camera_y: _Optional[float] = ..., camera_z: _Optional[float] = ..., y_rot: _Optional[float] = ..., x_rot: _Optional[float] = ..., include_hud: bool = ..., include_hands: bool = ..., include_debug_trace: bool = ..., interval_ms: _Optional[int] = ...) -> None: ...

class BotPovFrame(_message.Message):
    __slots__ = ("sequence", "captured_at", "render", "dropped_before")
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    CAPTURED_AT_FIELD_NUMBER: _ClassVar[int]
    RENDER_FIELD_NUMBER: _ClassVar[int]
    DROPPED_BEFORE_FIELD_NUMBER: _ClassVar[int]
    sequence: int
    captured_at: _timestamp_pb2.Timestamp
    render: BotRenderPovResponse
    dropped_before: int
    def __init__(self, sequence: _Optional[int] = ..., captured_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., render: _Optional[_Union[BotRenderPovResponse, _Mapping]] = ..., dropped_before: _Optional[int] = ...) -> None: ...

class BotWorldMapRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "center_x", "center_z", "radius", "sample_step", "include_entities")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    CENTER_X_FIELD_NUMBER: _ClassVar[int]
    CENTER_Z_FIELD_NUMBER: _ClassVar[int]
    RADIUS_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_STEP_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_ENTITIES_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    center_x: int
    center_z: int
    radius: int
    sample_step: int
    include_entities: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., center_x: _Optional[int] = ..., center_z: _Optional[int] = ..., radius: _Optional[int] = ..., sample_step: _Optional[int] = ..., include_entities: bool = ...) -> None: ...

class BotWorldMapColumn(_message.Message):
    __slots__ = ("x", "z", "loaded", "surface_y", "block_id", "biome_id", "sky_light", "block_light")
    X_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    LOADED_FIELD_NUMBER: _ClassVar[int]
    SURFACE_Y_FIELD_NUMBER: _ClassVar[int]
    BLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    BIOME_ID_FIELD_NUMBER: _ClassVar[int]
    SKY_LIGHT_FIELD_NUMBER: _ClassVar[int]
    BLOCK_LIGHT_FIELD_NUMBER: _ClassVar[int]
    x: int
    z: int
    loaded: bool
    surface_y: int
    block_id: str
    biome_id: str
    sky_light: int
    block_light: int
    def __init__(self, x: _Optional[int] = ..., z: _Optional[int] = ..., loaded: bool = ..., surface_y: _Optional[int] = ..., block_id: _Optional[str] = ..., biome_id: _Optional[str] = ..., sky_light: _Optional[int] = ..., block_light: _Optional[int] = ...) -> None: ...

class BotWorldMapEntity(_message.Message):
    __slots__ = ("entity_id", "entity_type", "display_name", "x", "y", "z", "yaw")
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    YAW_FIELD_NUMBER: _ClassVar[int]
    entity_id: str
    entity_type: str
    display_name: str
    x: float
    y: float
    z: float
    yaw: float
    def __init__(self, entity_id: _Optional[str] = ..., entity_type: _Optional[str] = ..., display_name: _Optional[str] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., yaw: _Optional[float] = ...) -> None: ...

class BotWorldMapResponse(_message.Message):
    __slots__ = ("dimension", "center_x", "center_z", "radius", "sample_step", "min_y", "max_y", "world_revision", "sampled_at", "columns", "entities")
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    CENTER_X_FIELD_NUMBER: _ClassVar[int]
    CENTER_Z_FIELD_NUMBER: _ClassVar[int]
    RADIUS_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_STEP_FIELD_NUMBER: _ClassVar[int]
    MIN_Y_FIELD_NUMBER: _ClassVar[int]
    MAX_Y_FIELD_NUMBER: _ClassVar[int]
    WORLD_REVISION_FIELD_NUMBER: _ClassVar[int]
    SAMPLED_AT_FIELD_NUMBER: _ClassVar[int]
    COLUMNS_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    dimension: str
    center_x: int
    center_z: int
    radius: int
    sample_step: int
    min_y: int
    max_y: int
    world_revision: int
    sampled_at: _timestamp_pb2.Timestamp
    columns: _containers.RepeatedCompositeFieldContainer[BotWorldMapColumn]
    entities: _containers.RepeatedCompositeFieldContainer[BotWorldMapEntity]
    def __init__(self, dimension: _Optional[str] = ..., center_x: _Optional[int] = ..., center_z: _Optional[int] = ..., radius: _Optional[int] = ..., sample_step: _Optional[int] = ..., min_y: _Optional[int] = ..., max_y: _Optional[int] = ..., world_revision: _Optional[int] = ..., sampled_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., columns: _Optional[_Iterable[_Union[BotWorldMapColumn, _Mapping]]] = ..., entities: _Optional[_Iterable[_Union[BotWorldMapEntity, _Mapping]]] = ...) -> None: ...

class BotInventoryClickRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "slot", "click_type", "hotbar_slot")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    SLOT_FIELD_NUMBER: _ClassVar[int]
    CLICK_TYPE_FIELD_NUMBER: _ClassVar[int]
    HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    slot: int
    click_type: ClickType
    hotbar_slot: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., slot: _Optional[int] = ..., click_type: _Optional[_Union[ClickType, str]] = ..., hotbar_slot: _Optional[int] = ...) -> None: ...

class BotInventoryClickResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotInventoryStateRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class SlotRegion(_message.Message):
    __slots__ = ("id", "label", "start_index", "slot_count", "columns", "type")
    ID_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    START_INDEX_FIELD_NUMBER: _ClassVar[int]
    SLOT_COUNT_FIELD_NUMBER: _ClassVar[int]
    COLUMNS_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    id: str
    label: str
    start_index: int
    slot_count: int
    columns: int
    type: SlotRegionType
    def __init__(self, id: _Optional[str] = ..., label: _Optional[str] = ..., start_index: _Optional[int] = ..., slot_count: _Optional[int] = ..., columns: _Optional[int] = ..., type: _Optional[_Union[SlotRegionType, str]] = ...) -> None: ...

class ContainerButton(_message.Message):
    __slots__ = ("button_id", "label", "icon_item_id", "description", "disabled", "selected")
    BUTTON_ID_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    ICON_ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    DISABLED_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    button_id: int
    label: str
    icon_item_id: str
    description: str
    disabled: bool
    selected: bool
    def __init__(self, button_id: _Optional[int] = ..., label: _Optional[str] = ..., icon_item_id: _Optional[str] = ..., description: _Optional[str] = ..., disabled: bool = ..., selected: bool = ...) -> None: ...

class ContainerTextInput(_message.Message):
    __slots__ = ("id", "label", "current_value", "max_length", "placeholder")
    ID_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    CURRENT_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_LENGTH_FIELD_NUMBER: _ClassVar[int]
    PLACEHOLDER_FIELD_NUMBER: _ClassVar[int]
    id: str
    label: str
    current_value: str
    max_length: int
    placeholder: str
    def __init__(self, id: _Optional[str] = ..., label: _Optional[str] = ..., current_value: _Optional[str] = ..., max_length: _Optional[int] = ..., placeholder: _Optional[str] = ...) -> None: ...

class BookPage(_message.Message):
    __slots__ = ("page_number", "content")
    PAGE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    page_number: int
    content: str
    def __init__(self, page_number: _Optional[int] = ..., content: _Optional[str] = ...) -> None: ...

class ContainerLayout(_message.Message):
    __slots__ = ("title", "regions", "total_slots", "buttons", "container_type", "text_inputs", "book_pages", "current_book_page")
    TITLE_FIELD_NUMBER: _ClassVar[int]
    REGIONS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_SLOTS_FIELD_NUMBER: _ClassVar[int]
    BUTTONS_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_TYPE_FIELD_NUMBER: _ClassVar[int]
    TEXT_INPUTS_FIELD_NUMBER: _ClassVar[int]
    BOOK_PAGES_FIELD_NUMBER: _ClassVar[int]
    CURRENT_BOOK_PAGE_FIELD_NUMBER: _ClassVar[int]
    title: str
    regions: _containers.RepeatedCompositeFieldContainer[SlotRegion]
    total_slots: int
    buttons: _containers.RepeatedCompositeFieldContainer[ContainerButton]
    container_type: str
    text_inputs: _containers.RepeatedCompositeFieldContainer[ContainerTextInput]
    book_pages: _containers.RepeatedCompositeFieldContainer[BookPage]
    current_book_page: int
    def __init__(self, title: _Optional[str] = ..., regions: _Optional[_Iterable[_Union[SlotRegion, _Mapping]]] = ..., total_slots: _Optional[int] = ..., buttons: _Optional[_Iterable[_Union[ContainerButton, _Mapping]]] = ..., container_type: _Optional[str] = ..., text_inputs: _Optional[_Iterable[_Union[ContainerTextInput, _Mapping]]] = ..., book_pages: _Optional[_Iterable[_Union[BookPage, _Mapping]]] = ..., current_book_page: _Optional[int] = ...) -> None: ...

class BotInventoryStateResponse(_message.Message):
    __slots__ = ("layout", "slots", "carried_item", "selected_hotbar_slot")
    LAYOUT_FIELD_NUMBER: _ClassVar[int]
    SLOTS_FIELD_NUMBER: _ClassVar[int]
    CARRIED_ITEM_FIELD_NUMBER: _ClassVar[int]
    SELECTED_HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    layout: ContainerLayout
    slots: _containers.RepeatedCompositeFieldContainer[InventorySlot]
    carried_item: InventorySlot
    selected_hotbar_slot: int
    def __init__(self, layout: _Optional[_Union[ContainerLayout, _Mapping]] = ..., slots: _Optional[_Iterable[_Union[InventorySlot, _Mapping]]] = ..., carried_item: _Optional[_Union[InventorySlot, _Mapping]] = ..., selected_hotbar_slot: _Optional[int] = ...) -> None: ...

class BotCloseContainerRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotCloseContainerResponse(_message.Message):
    __slots__ = ("success",)
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    success: bool
    def __init__(self, success: bool = ...) -> None: ...

class BotOpenInventoryRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotOpenInventoryResponse(_message.Message):
    __slots__ = ("success",)
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    success: bool
    def __init__(self, success: bool = ...) -> None: ...

class BotMouseClickRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "button")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    BUTTON_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    button: MouseButton
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., button: _Optional[_Union[MouseButton, str]] = ...) -> None: ...

class BotMouseClickResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotContainerButtonClickRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "button_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    BUTTON_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    button_id: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., button_id: _Optional[int] = ...) -> None: ...

class BotContainerButtonClickResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotSetContainerTextRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "field_id", "text")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    FIELD_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    field_id: str
    text: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., field_id: _Optional[str] = ..., text: _Optional[str] = ...) -> None: ...

class BotSetContainerTextResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotSetHotbarSlotRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "slot")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    SLOT_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    slot: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., slot: _Optional[int] = ...) -> None: ...

class BotSetHotbarSlotResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotSetMovementStateRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "forward", "backward", "left", "right", "jump", "sneak", "sprint")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    FORWARD_FIELD_NUMBER: _ClassVar[int]
    BACKWARD_FIELD_NUMBER: _ClassVar[int]
    LEFT_FIELD_NUMBER: _ClassVar[int]
    RIGHT_FIELD_NUMBER: _ClassVar[int]
    JUMP_FIELD_NUMBER: _ClassVar[int]
    SNEAK_FIELD_NUMBER: _ClassVar[int]
    SPRINT_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    forward: bool
    backward: bool
    left: bool
    right: bool
    jump: bool
    sneak: bool
    sprint: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., forward: bool = ..., backward: bool = ..., left: bool = ..., right: bool = ..., jump: bool = ..., sneak: bool = ..., sprint: bool = ...) -> None: ...

class BotSetMovementStateResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotResetMovementRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotResetMovementResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotSetRotationRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "yaw", "pitch")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    YAW_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    yaw: float
    pitch: float
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., yaw: _Optional[float] = ..., pitch: _Optional[float] = ...) -> None: ...

class BotSetRotationResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class DialogBodyElement(_message.Message):
    __slots__ = ("plain_message", "item")
    PLAIN_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    plain_message: DialogPlainMessage
    item: DialogItem
    def __init__(self, plain_message: _Optional[_Union[DialogPlainMessage, _Mapping]] = ..., item: _Optional[_Union[DialogItem, _Mapping]] = ...) -> None: ...

class DialogPlainMessage(_message.Message):
    __slots__ = ("contents", "width")
    CONTENTS_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    contents: str
    width: int
    def __init__(self, contents: _Optional[str] = ..., width: _Optional[int] = ...) -> None: ...

class DialogItem(_message.Message):
    __slots__ = ("item_id", "count", "description", "show_decoration", "show_tooltip", "width", "height")
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    SHOW_DECORATION_FIELD_NUMBER: _ClassVar[int]
    SHOW_TOOLTIP_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    item_id: str
    count: int
    description: str
    show_decoration: bool
    show_tooltip: bool
    width: int
    height: int
    def __init__(self, item_id: _Optional[str] = ..., count: _Optional[int] = ..., description: _Optional[str] = ..., show_decoration: bool = ..., show_tooltip: bool = ..., width: _Optional[int] = ..., height: _Optional[int] = ...) -> None: ...

class DialogInput(_message.Message):
    __slots__ = ("text", "boolean", "single_option", "number_range")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    BOOLEAN_FIELD_NUMBER: _ClassVar[int]
    SINGLE_OPTION_FIELD_NUMBER: _ClassVar[int]
    NUMBER_RANGE_FIELD_NUMBER: _ClassVar[int]
    text: DialogTextInput
    boolean: DialogBooleanInput
    single_option: DialogSingleOptionInput
    number_range: DialogNumberRangeInput
    def __init__(self, text: _Optional[_Union[DialogTextInput, _Mapping]] = ..., boolean: _Optional[_Union[DialogBooleanInput, _Mapping]] = ..., single_option: _Optional[_Union[DialogSingleOptionInput, _Mapping]] = ..., number_range: _Optional[_Union[DialogNumberRangeInput, _Mapping]] = ...) -> None: ...

class DialogTextInput(_message.Message):
    __slots__ = ("key", "label", "width", "label_visible", "initial", "max_length", "multiline", "multiline_max_lines", "multiline_height")
    KEY_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    LABEL_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    INITIAL_FIELD_NUMBER: _ClassVar[int]
    MAX_LENGTH_FIELD_NUMBER: _ClassVar[int]
    MULTILINE_FIELD_NUMBER: _ClassVar[int]
    MULTILINE_MAX_LINES_FIELD_NUMBER: _ClassVar[int]
    MULTILINE_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    key: str
    label: str
    width: int
    label_visible: bool
    initial: str
    max_length: int
    multiline: bool
    multiline_max_lines: int
    multiline_height: int
    def __init__(self, key: _Optional[str] = ..., label: _Optional[str] = ..., width: _Optional[int] = ..., label_visible: bool = ..., initial: _Optional[str] = ..., max_length: _Optional[int] = ..., multiline: bool = ..., multiline_max_lines: _Optional[int] = ..., multiline_height: _Optional[int] = ...) -> None: ...

class DialogBooleanInput(_message.Message):
    __slots__ = ("key", "label", "initial", "on_true", "on_false")
    KEY_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    INITIAL_FIELD_NUMBER: _ClassVar[int]
    ON_TRUE_FIELD_NUMBER: _ClassVar[int]
    ON_FALSE_FIELD_NUMBER: _ClassVar[int]
    key: str
    label: str
    initial: bool
    on_true: str
    on_false: str
    def __init__(self, key: _Optional[str] = ..., label: _Optional[str] = ..., initial: bool = ..., on_true: _Optional[str] = ..., on_false: _Optional[str] = ...) -> None: ...

class DialogSingleOptionInput(_message.Message):
    __slots__ = ("key", "label", "label_visible", "width", "options", "initial_option_id")
    KEY_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    LABEL_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    INITIAL_OPTION_ID_FIELD_NUMBER: _ClassVar[int]
    key: str
    label: str
    label_visible: bool
    width: int
    options: _containers.RepeatedCompositeFieldContainer[DialogOption]
    initial_option_id: str
    def __init__(self, key: _Optional[str] = ..., label: _Optional[str] = ..., label_visible: bool = ..., width: _Optional[int] = ..., options: _Optional[_Iterable[_Union[DialogOption, _Mapping]]] = ..., initial_option_id: _Optional[str] = ...) -> None: ...

class DialogOption(_message.Message):
    __slots__ = ("id", "display")
    ID_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_FIELD_NUMBER: _ClassVar[int]
    id: str
    display: str
    def __init__(self, id: _Optional[str] = ..., display: _Optional[str] = ...) -> None: ...

class DialogNumberRangeInput(_message.Message):
    __slots__ = ("key", "label", "label_format", "width", "start", "end", "step", "initial")
    KEY_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    LABEL_FORMAT_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    START_FIELD_NUMBER: _ClassVar[int]
    END_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    INITIAL_FIELD_NUMBER: _ClassVar[int]
    key: str
    label: str
    label_format: str
    width: int
    start: float
    end: float
    step: float
    initial: float
    def __init__(self, key: _Optional[str] = ..., label: _Optional[str] = ..., label_format: _Optional[str] = ..., width: _Optional[int] = ..., start: _Optional[float] = ..., end: _Optional[float] = ..., step: _Optional[float] = ..., initial: _Optional[float] = ...) -> None: ...

class DialogAction(_message.Message):
    __slots__ = ("open_url", "run_command", "suggest_command", "copy_to_clipboard", "show_dialog", "custom", "dynamic_run_command", "dynamic_custom")
    OPEN_URL_FIELD_NUMBER: _ClassVar[int]
    RUN_COMMAND_FIELD_NUMBER: _ClassVar[int]
    SUGGEST_COMMAND_FIELD_NUMBER: _ClassVar[int]
    COPY_TO_CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    SHOW_DIALOG_FIELD_NUMBER: _ClassVar[int]
    CUSTOM_FIELD_NUMBER: _ClassVar[int]
    DYNAMIC_RUN_COMMAND_FIELD_NUMBER: _ClassVar[int]
    DYNAMIC_CUSTOM_FIELD_NUMBER: _ClassVar[int]
    open_url: DialogOpenUrlAction
    run_command: DialogRunCommandAction
    suggest_command: DialogSuggestCommandAction
    copy_to_clipboard: DialogCopyToClipboardAction
    show_dialog: DialogShowDialogAction
    custom: DialogCustomAction
    dynamic_run_command: DialogDynamicRunCommandAction
    dynamic_custom: DialogDynamicCustomAction
    def __init__(self, open_url: _Optional[_Union[DialogOpenUrlAction, _Mapping]] = ..., run_command: _Optional[_Union[DialogRunCommandAction, _Mapping]] = ..., suggest_command: _Optional[_Union[DialogSuggestCommandAction, _Mapping]] = ..., copy_to_clipboard: _Optional[_Union[DialogCopyToClipboardAction, _Mapping]] = ..., show_dialog: _Optional[_Union[DialogShowDialogAction, _Mapping]] = ..., custom: _Optional[_Union[DialogCustomAction, _Mapping]] = ..., dynamic_run_command: _Optional[_Union[DialogDynamicRunCommandAction, _Mapping]] = ..., dynamic_custom: _Optional[_Union[DialogDynamicCustomAction, _Mapping]] = ...) -> None: ...

class DialogOpenUrlAction(_message.Message):
    __slots__ = ("url",)
    URL_FIELD_NUMBER: _ClassVar[int]
    url: str
    def __init__(self, url: _Optional[str] = ...) -> None: ...

class DialogRunCommandAction(_message.Message):
    __slots__ = ("command",)
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    command: str
    def __init__(self, command: _Optional[str] = ...) -> None: ...

class DialogSuggestCommandAction(_message.Message):
    __slots__ = ("command",)
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    command: str
    def __init__(self, command: _Optional[str] = ...) -> None: ...

class DialogCopyToClipboardAction(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: str
    def __init__(self, value: _Optional[str] = ...) -> None: ...

class DialogShowDialogAction(_message.Message):
    __slots__ = ("dialog_id",)
    DIALOG_ID_FIELD_NUMBER: _ClassVar[int]
    dialog_id: str
    def __init__(self, dialog_id: _Optional[str] = ...) -> None: ...

class DialogCustomAction(_message.Message):
    __slots__ = ("id", "payload")
    ID_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    id: str
    payload: str
    def __init__(self, id: _Optional[str] = ..., payload: _Optional[str] = ...) -> None: ...

class DialogDynamicRunCommandAction(_message.Message):
    __slots__ = ("template",)
    TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    template: str
    def __init__(self, template: _Optional[str] = ...) -> None: ...

class DialogDynamicCustomAction(_message.Message):
    __slots__ = ("id", "additions")
    ID_FIELD_NUMBER: _ClassVar[int]
    ADDITIONS_FIELD_NUMBER: _ClassVar[int]
    id: str
    additions: str
    def __init__(self, id: _Optional[str] = ..., additions: _Optional[str] = ...) -> None: ...

class DialogButton(_message.Message):
    __slots__ = ("label", "tooltip", "width", "action")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    TOOLTIP_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    label: str
    tooltip: str
    width: int
    action: DialogAction
    def __init__(self, label: _Optional[str] = ..., tooltip: _Optional[str] = ..., width: _Optional[int] = ..., action: _Optional[_Union[DialogAction, _Mapping]] = ...) -> None: ...

class ServerDialog(_message.Message):
    __slots__ = ("id", "type", "title", "external_title", "body", "inputs", "can_close_with_escape", "pause", "after_action", "action", "yes", "no", "actions", "columns", "exit_action", "button_width")
    ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    EXTERNAL_TITLE_FIELD_NUMBER: _ClassVar[int]
    BODY_FIELD_NUMBER: _ClassVar[int]
    INPUTS_FIELD_NUMBER: _ClassVar[int]
    CAN_CLOSE_WITH_ESCAPE_FIELD_NUMBER: _ClassVar[int]
    PAUSE_FIELD_NUMBER: _ClassVar[int]
    AFTER_ACTION_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    YES_FIELD_NUMBER: _ClassVar[int]
    NO_FIELD_NUMBER: _ClassVar[int]
    ACTIONS_FIELD_NUMBER: _ClassVar[int]
    COLUMNS_FIELD_NUMBER: _ClassVar[int]
    EXIT_ACTION_FIELD_NUMBER: _ClassVar[int]
    BUTTON_WIDTH_FIELD_NUMBER: _ClassVar[int]
    id: str
    type: DialogType
    title: str
    external_title: str
    body: _containers.RepeatedCompositeFieldContainer[DialogBodyElement]
    inputs: _containers.RepeatedCompositeFieldContainer[DialogInput]
    can_close_with_escape: bool
    pause: bool
    after_action: DialogAfterAction
    action: DialogButton
    yes: DialogButton
    no: DialogButton
    actions: _containers.RepeatedCompositeFieldContainer[DialogButton]
    columns: int
    exit_action: DialogButton
    button_width: int
    def __init__(self, id: _Optional[str] = ..., type: _Optional[_Union[DialogType, str]] = ..., title: _Optional[str] = ..., external_title: _Optional[str] = ..., body: _Optional[_Iterable[_Union[DialogBodyElement, _Mapping]]] = ..., inputs: _Optional[_Iterable[_Union[DialogInput, _Mapping]]] = ..., can_close_with_escape: bool = ..., pause: bool = ..., after_action: _Optional[_Union[DialogAfterAction, str]] = ..., action: _Optional[_Union[DialogButton, _Mapping]] = ..., yes: _Optional[_Union[DialogButton, _Mapping]] = ..., no: _Optional[_Union[DialogButton, _Mapping]] = ..., actions: _Optional[_Iterable[_Union[DialogButton, _Mapping]]] = ..., columns: _Optional[int] = ..., exit_action: _Optional[_Union[DialogButton, _Mapping]] = ..., button_width: _Optional[int] = ...) -> None: ...

class BotGetDialogRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotGetDialogResponse(_message.Message):
    __slots__ = ("dialog",)
    DIALOG_FIELD_NUMBER: _ClassVar[int]
    dialog: ServerDialog
    def __init__(self, dialog: _Optional[_Union[ServerDialog, _Mapping]] = ...) -> None: ...

class BotSubmitDialogRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "input_values")
    class InputValuesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    INPUT_VALUES_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    input_values: _containers.ScalarMap[str, str]
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., input_values: _Optional[_Mapping[str, str]] = ...) -> None: ...

class BotSubmitDialogResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotClickDialogButtonRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "button_index")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    BUTTON_INDEX_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    button_index: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., button_index: _Optional[int] = ...) -> None: ...

class BotClickDialogButtonResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...

class BotCloseDialogRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class BotCloseDialogResponse(_message.Message):
    __slots__ = ("success", "error")
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    success: bool
    error: str
    def __init__(self, success: bool = ..., error: _Optional[str] = ...) -> None: ...
