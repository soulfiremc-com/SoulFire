import datetime

from soulfire import api_docs_pb2 as _api_docs_pb2
from soulfire import bot_pb2 as _bot_pb2
from soulfire import common_pb2 as _common_pb2
from soulfire import domain_pb2 as _domain_pb2
from google.api import annotations_pb2 as _annotations_pb2
from google.api import field_behavior_pb2 as _field_behavior_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class BlockFace(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BLOCK_FACE_UNSPECIFIED: _ClassVar[BlockFace]
    BLOCK_FACE_DOWN: _ClassVar[BlockFace]
    BLOCK_FACE_UP: _ClassVar[BlockFace]
    BLOCK_FACE_NORTH: _ClassVar[BlockFace]
    BLOCK_FACE_SOUTH: _ClassVar[BlockFace]
    BLOCK_FACE_WEST: _ClassVar[BlockFace]
    BLOCK_FACE_EAST: _ClassVar[BlockFace]

class Hand(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    HAND_UNSPECIFIED: _ClassVar[Hand]
    HAND_MAIN: _ClassVar[Hand]
    HAND_OFF: _ClassVar[Hand]

class ChatSource(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHAT_SOURCE_UNSPECIFIED: _ClassVar[ChatSource]
    CHAT_SOURCE_PLAYER: _ClassVar[ChatSource]
    CHAT_SOURCE_SYSTEM: _ClassVar[ChatSource]
    CHAT_SOURCE_ACTION_BAR: _ClassVar[ChatSource]
    CHAT_SOURCE_WHISPER: _ClassVar[ChatSource]
    CHAT_SOURCE_UNKNOWN: _ClassVar[ChatSource]

class BotLifecycleKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOT_LIFECYCLE_UNSPECIFIED: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_CONNECTING: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_CONNECTED: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_SPAWNED: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_DIED: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_RESPAWNED: _ClassVar[BotLifecycleKind]
    BOT_LIFECYCLE_DISCONNECTED: _ClassVar[BotLifecycleKind]

class EntityEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ENTITY_EVENT_UNSPECIFIED: _ClassVar[EntityEventKind]
    ENTITY_EVENT_SPAWN: _ClassVar[EntityEventKind]
    ENTITY_EVENT_UPDATE: _ClassVar[EntityEventKind]
    ENTITY_EVENT_DESPAWN: _ClassVar[EntityEventKind]

class WeatherEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    WEATHER_EVENT_UNSPECIFIED: _ClassVar[WeatherEventKind]
    WEATHER_EVENT_STARTED_RAINING: _ClassVar[WeatherEventKind]
    WEATHER_EVENT_STOPPED_RAINING: _ClassVar[WeatherEventKind]
    WEATHER_EVENT_RAIN_LEVEL_CHANGED: _ClassVar[WeatherEventKind]
    WEATHER_EVENT_THUNDER_LEVEL_CHANGED: _ClassVar[WeatherEventKind]

class PlayerListEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PLAYER_LIST_EVENT_UNSPECIFIED: _ClassVar[PlayerListEventKind]
    PLAYER_LIST_EVENT_UPSERT: _ClassVar[PlayerListEventKind]
    PLAYER_LIST_EVENT_REMOVE: _ClassVar[PlayerListEventKind]

class BossBarEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOSS_BAR_EVENT_UNSPECIFIED: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_ADD: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_REMOVE: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_UPDATE_PROGRESS: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_UPDATE_NAME: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_UPDATE_STYLE: _ClassVar[BossBarEventKind]
    BOSS_BAR_EVENT_UPDATE_PROPERTIES: _ClassVar[BossBarEventKind]

class SoundEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SOUND_EVENT_UNSPECIFIED: _ClassVar[SoundEventKind]
    SOUND_EVENT_PLAY_AT_POSITION: _ClassVar[SoundEventKind]
    SOUND_EVENT_PLAY_AT_ENTITY: _ClassVar[SoundEventKind]
    SOUND_EVENT_STOP: _ClassVar[SoundEventKind]

class ScoreboardEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SCOREBOARD_EVENT_UNSPECIFIED: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_OBJECTIVE_ADD: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_OBJECTIVE_REMOVE: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_OBJECTIVE_UPDATE: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_DISPLAY_OBJECTIVE: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_SCORE_SET: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_SCORE_RESET: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_TEAM_ADD: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_TEAM_REMOVE: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_TEAM_UPDATE: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_TEAM_PLAYERS_ADD: _ClassVar[ScoreboardEventKind]
    SCOREBOARD_EVENT_TEAM_PLAYERS_REMOVE: _ClassVar[ScoreboardEventKind]

class ResourcePackEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RESOURCE_PACK_EVENT_UNSPECIFIED: _ClassVar[ResourcePackEventKind]
    RESOURCE_PACK_EVENT_OFFERED: _ClassVar[ResourcePackEventKind]
    RESOURCE_PACK_EVENT_REMOVED: _ClassVar[ResourcePackEventKind]
    RESOURCE_PACK_EVENT_CLEARED: _ClassVar[ResourcePackEventKind]

class TitleEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TITLE_EVENT_UNSPECIFIED: _ClassVar[TitleEventKind]
    TITLE_EVENT_TITLE: _ClassVar[TitleEventKind]
    TITLE_EVENT_SUBTITLE: _ClassVar[TitleEventKind]
    TITLE_EVENT_TIMES: _ClassVar[TitleEventKind]
    TITLE_EVENT_CLEAR: _ClassVar[TitleEventKind]
    TITLE_EVENT_RESET: _ClassVar[TitleEventKind]

class ChunkEventKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHUNK_EVENT_UNSPECIFIED: _ClassVar[ChunkEventKind]
    CHUNK_EVENT_LOAD: _ClassVar[ChunkEventKind]
    CHUNK_EVENT_UNLOAD: _ClassVar[ChunkEventKind]

class BotActionStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BOT_ACTION_STATUS_UNSPECIFIED: _ClassVar[BotActionStatus]
    BOT_ACTION_STATUS_COMPLETED: _ClassVar[BotActionStatus]
    BOT_ACTION_STATUS_CANCELLED: _ClassVar[BotActionStatus]
    BOT_ACTION_STATUS_FAILED: _ClassVar[BotActionStatus]

class ResourcePackResponse(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RESOURCE_PACK_RESPONSE_UNSPECIFIED: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_ACCEPTED: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_DOWNLOADED: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_SUCCESSFULLY_LOADED: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_DECLINED: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_FAILED_DOWNLOAD: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_INVALID_URL: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_FAILED_RELOAD: _ClassVar[ResourcePackResponse]
    RESOURCE_PACK_RESPONSE_DISCARDED: _ClassVar[ResourcePackResponse]

class PathfindSearchMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PATHFIND_SEARCH_MODE_UNSPECIFIED: _ClassVar[PathfindSearchMode]
    PATHFIND_SEARCH_MODE_PRECISION: _ClassVar[PathfindSearchMode]
    PATHFIND_SEARCH_MODE_NORMAL: _ClassVar[PathfindSearchMode]
    PATHFIND_SEARCH_MODE_URGENT: _ClassVar[PathfindSearchMode]
    PATHFIND_SEARCH_MODE_ESCAPE: _ClassVar[PathfindSearchMode]

class PathfindStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PATHFIND_STATUS_UNSPECIFIED: _ClassVar[PathfindStatus]
    PATHFIND_STATUS_PLANNING: _ClassVar[PathfindStatus]
    PATHFIND_STATUS_MOVING: _ClassVar[PathfindStatus]
    PATHFIND_STATUS_COMPLETED: _ClassVar[PathfindStatus]
    PATHFIND_STATUS_FAILED: _ClassVar[PathfindStatus]
    PATHFIND_STATUS_CANCELLED: _ClassVar[PathfindStatus]
BLOCK_FACE_UNSPECIFIED: BlockFace
BLOCK_FACE_DOWN: BlockFace
BLOCK_FACE_UP: BlockFace
BLOCK_FACE_NORTH: BlockFace
BLOCK_FACE_SOUTH: BlockFace
BLOCK_FACE_WEST: BlockFace
BLOCK_FACE_EAST: BlockFace
HAND_UNSPECIFIED: Hand
HAND_MAIN: Hand
HAND_OFF: Hand
CHAT_SOURCE_UNSPECIFIED: ChatSource
CHAT_SOURCE_PLAYER: ChatSource
CHAT_SOURCE_SYSTEM: ChatSource
CHAT_SOURCE_ACTION_BAR: ChatSource
CHAT_SOURCE_WHISPER: ChatSource
CHAT_SOURCE_UNKNOWN: ChatSource
BOT_LIFECYCLE_UNSPECIFIED: BotLifecycleKind
BOT_LIFECYCLE_CONNECTING: BotLifecycleKind
BOT_LIFECYCLE_CONNECTED: BotLifecycleKind
BOT_LIFECYCLE_SPAWNED: BotLifecycleKind
BOT_LIFECYCLE_DIED: BotLifecycleKind
BOT_LIFECYCLE_RESPAWNED: BotLifecycleKind
BOT_LIFECYCLE_DISCONNECTED: BotLifecycleKind
ENTITY_EVENT_UNSPECIFIED: EntityEventKind
ENTITY_EVENT_SPAWN: EntityEventKind
ENTITY_EVENT_UPDATE: EntityEventKind
ENTITY_EVENT_DESPAWN: EntityEventKind
WEATHER_EVENT_UNSPECIFIED: WeatherEventKind
WEATHER_EVENT_STARTED_RAINING: WeatherEventKind
WEATHER_EVENT_STOPPED_RAINING: WeatherEventKind
WEATHER_EVENT_RAIN_LEVEL_CHANGED: WeatherEventKind
WEATHER_EVENT_THUNDER_LEVEL_CHANGED: WeatherEventKind
PLAYER_LIST_EVENT_UNSPECIFIED: PlayerListEventKind
PLAYER_LIST_EVENT_UPSERT: PlayerListEventKind
PLAYER_LIST_EVENT_REMOVE: PlayerListEventKind
BOSS_BAR_EVENT_UNSPECIFIED: BossBarEventKind
BOSS_BAR_EVENT_ADD: BossBarEventKind
BOSS_BAR_EVENT_REMOVE: BossBarEventKind
BOSS_BAR_EVENT_UPDATE_PROGRESS: BossBarEventKind
BOSS_BAR_EVENT_UPDATE_NAME: BossBarEventKind
BOSS_BAR_EVENT_UPDATE_STYLE: BossBarEventKind
BOSS_BAR_EVENT_UPDATE_PROPERTIES: BossBarEventKind
SOUND_EVENT_UNSPECIFIED: SoundEventKind
SOUND_EVENT_PLAY_AT_POSITION: SoundEventKind
SOUND_EVENT_PLAY_AT_ENTITY: SoundEventKind
SOUND_EVENT_STOP: SoundEventKind
SCOREBOARD_EVENT_UNSPECIFIED: ScoreboardEventKind
SCOREBOARD_EVENT_OBJECTIVE_ADD: ScoreboardEventKind
SCOREBOARD_EVENT_OBJECTIVE_REMOVE: ScoreboardEventKind
SCOREBOARD_EVENT_OBJECTIVE_UPDATE: ScoreboardEventKind
SCOREBOARD_EVENT_DISPLAY_OBJECTIVE: ScoreboardEventKind
SCOREBOARD_EVENT_SCORE_SET: ScoreboardEventKind
SCOREBOARD_EVENT_SCORE_RESET: ScoreboardEventKind
SCOREBOARD_EVENT_TEAM_ADD: ScoreboardEventKind
SCOREBOARD_EVENT_TEAM_REMOVE: ScoreboardEventKind
SCOREBOARD_EVENT_TEAM_UPDATE: ScoreboardEventKind
SCOREBOARD_EVENT_TEAM_PLAYERS_ADD: ScoreboardEventKind
SCOREBOARD_EVENT_TEAM_PLAYERS_REMOVE: ScoreboardEventKind
RESOURCE_PACK_EVENT_UNSPECIFIED: ResourcePackEventKind
RESOURCE_PACK_EVENT_OFFERED: ResourcePackEventKind
RESOURCE_PACK_EVENT_REMOVED: ResourcePackEventKind
RESOURCE_PACK_EVENT_CLEARED: ResourcePackEventKind
TITLE_EVENT_UNSPECIFIED: TitleEventKind
TITLE_EVENT_TITLE: TitleEventKind
TITLE_EVENT_SUBTITLE: TitleEventKind
TITLE_EVENT_TIMES: TitleEventKind
TITLE_EVENT_CLEAR: TitleEventKind
TITLE_EVENT_RESET: TitleEventKind
CHUNK_EVENT_UNSPECIFIED: ChunkEventKind
CHUNK_EVENT_LOAD: ChunkEventKind
CHUNK_EVENT_UNLOAD: ChunkEventKind
BOT_ACTION_STATUS_UNSPECIFIED: BotActionStatus
BOT_ACTION_STATUS_COMPLETED: BotActionStatus
BOT_ACTION_STATUS_CANCELLED: BotActionStatus
BOT_ACTION_STATUS_FAILED: BotActionStatus
RESOURCE_PACK_RESPONSE_UNSPECIFIED: ResourcePackResponse
RESOURCE_PACK_RESPONSE_ACCEPTED: ResourcePackResponse
RESOURCE_PACK_RESPONSE_DOWNLOADED: ResourcePackResponse
RESOURCE_PACK_RESPONSE_SUCCESSFULLY_LOADED: ResourcePackResponse
RESOURCE_PACK_RESPONSE_DECLINED: ResourcePackResponse
RESOURCE_PACK_RESPONSE_FAILED_DOWNLOAD: ResourcePackResponse
RESOURCE_PACK_RESPONSE_INVALID_URL: ResourcePackResponse
RESOURCE_PACK_RESPONSE_FAILED_RELOAD: ResourcePackResponse
RESOURCE_PACK_RESPONSE_DISCARDED: ResourcePackResponse
PATHFIND_SEARCH_MODE_UNSPECIFIED: PathfindSearchMode
PATHFIND_SEARCH_MODE_PRECISION: PathfindSearchMode
PATHFIND_SEARCH_MODE_NORMAL: PathfindSearchMode
PATHFIND_SEARCH_MODE_URGENT: PathfindSearchMode
PATHFIND_SEARCH_MODE_ESCAPE: PathfindSearchMode
PATHFIND_STATUS_UNSPECIFIED: PathfindStatus
PATHFIND_STATUS_PLANNING: PathfindStatus
PATHFIND_STATUS_MOVING: PathfindStatus
PATHFIND_STATUS_COMPLETED: PathfindStatus
PATHFIND_STATUS_FAILED: PathfindStatus
PATHFIND_STATUS_CANCELLED: PathfindStatus

class BlockState(_message.Message):
    __slots__ = ("position", "block_id", "properties")
    class PropertiesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    POSITION_FIELD_NUMBER: _ClassVar[int]
    BLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    position: _common_pb2.BlockPosition
    block_id: str
    properties: _containers.ScalarMap[str, str]
    def __init__(self, position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., block_id: _Optional[str] = ..., properties: _Optional[_Mapping[str, str]] = ...) -> None: ...

class NearbyEntity(_message.Message):
    __slots__ = ("entity_id", "entity_type", "position", "distance", "display_name", "is_player", "health")
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    IS_PLAYER_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    entity_type: str
    position: _common_pb2.WorldPosition
    distance: float
    display_name: str
    is_player: bool
    health: float
    def __init__(self, entity_id: _Optional[int] = ..., entity_type: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., distance: _Optional[float] = ..., display_name: _Optional[str] = ..., is_player: bool = ..., health: _Optional[float] = ...) -> None: ...

class BotEventFilter(_message.Message):
    __slots__ = ("include_state_deltas", "include_chat", "include_lifecycle", "include_entity_events", "entity_radius", "include_block_updates", "block_radius", "include_inventory", "include_damage", "include_environment", "include_player_list", "include_boss_bars", "include_sounds", "include_particles", "include_scoreboard", "include_resource_packs", "include_titles", "include_chunks")
    INCLUDE_STATE_DELTAS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_CHAT_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_LIFECYCLE_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_ENTITY_EVENTS_FIELD_NUMBER: _ClassVar[int]
    ENTITY_RADIUS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_BLOCK_UPDATES_FIELD_NUMBER: _ClassVar[int]
    BLOCK_RADIUS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_INVENTORY_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_DAMAGE_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_ENVIRONMENT_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_PLAYER_LIST_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_BOSS_BARS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_SOUNDS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_PARTICLES_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_SCOREBOARD_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_RESOURCE_PACKS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_TITLES_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    include_state_deltas: bool
    include_chat: bool
    include_lifecycle: bool
    include_entity_events: bool
    entity_radius: float
    include_block_updates: bool
    block_radius: float
    include_inventory: bool
    include_damage: bool
    include_environment: bool
    include_player_list: bool
    include_boss_bars: bool
    include_sounds: bool
    include_particles: bool
    include_scoreboard: bool
    include_resource_packs: bool
    include_titles: bool
    include_chunks: bool
    def __init__(self, include_state_deltas: bool = ..., include_chat: bool = ..., include_lifecycle: bool = ..., include_entity_events: bool = ..., entity_radius: _Optional[float] = ..., include_block_updates: bool = ..., block_radius: _Optional[float] = ..., include_inventory: bool = ..., include_damage: bool = ..., include_environment: bool = ..., include_player_list: bool = ..., include_boss_bars: bool = ..., include_sounds: bool = ..., include_particles: bool = ..., include_scoreboard: bool = ..., include_resource_packs: bool = ..., include_titles: bool = ..., include_chunks: bool = ...) -> None: ...

class WatchBotEventsRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "filter", "after_sequence", "stream_epoch", "heartbeat_interval_seconds")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    FILTER_FIELD_NUMBER: _ClassVar[int]
    AFTER_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    STREAM_EPOCH_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_INTERVAL_SECONDS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    filter: BotEventFilter
    after_sequence: int
    stream_epoch: str
    heartbeat_interval_seconds: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., filter: _Optional[_Union[BotEventFilter, _Mapping]] = ..., after_sequence: _Optional[int] = ..., stream_epoch: _Optional[str] = ..., heartbeat_interval_seconds: _Optional[int] = ...) -> None: ...

class BotStateDelta(_message.Message):
    __slots__ = ("x", "y", "z", "x_rot", "y_rot", "health", "max_health", "food_level", "saturation_level", "selected_hotbar_slot", "dimension", "experience_level", "experience_progress", "game_mode")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    X_ROT_FIELD_NUMBER: _ClassVar[int]
    Y_ROT_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    MAX_HEALTH_FIELD_NUMBER: _ClassVar[int]
    FOOD_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SATURATION_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SELECTED_HOTBAR_SLOT_FIELD_NUMBER: _ClassVar[int]
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    EXPERIENCE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    EXPERIENCE_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    GAME_MODE_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    x_rot: float
    y_rot: float
    health: float
    max_health: float
    food_level: int
    saturation_level: float
    selected_hotbar_slot: int
    dimension: str
    experience_level: int
    experience_progress: float
    game_mode: _bot_pb2.GameMode
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., x_rot: _Optional[float] = ..., y_rot: _Optional[float] = ..., health: _Optional[float] = ..., max_health: _Optional[float] = ..., food_level: _Optional[int] = ..., saturation_level: _Optional[float] = ..., selected_hotbar_slot: _Optional[int] = ..., dimension: _Optional[str] = ..., experience_level: _Optional[int] = ..., experience_progress: _Optional[float] = ..., game_mode: _Optional[_Union[_bot_pb2.GameMode, str]] = ...) -> None: ...

class BotChatEvent(_message.Message):
    __slots__ = ("source", "plain_text", "json_component", "sender_name", "sender_id", "received_at")
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    PLAIN_TEXT_FIELD_NUMBER: _ClassVar[int]
    JSON_COMPONENT_FIELD_NUMBER: _ClassVar[int]
    SENDER_NAME_FIELD_NUMBER: _ClassVar[int]
    SENDER_ID_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_AT_FIELD_NUMBER: _ClassVar[int]
    source: ChatSource
    plain_text: str
    json_component: str
    sender_name: str
    sender_id: str
    received_at: _timestamp_pb2.Timestamp
    def __init__(self, source: _Optional[_Union[ChatSource, str]] = ..., plain_text: _Optional[str] = ..., json_component: _Optional[str] = ..., sender_name: _Optional[str] = ..., sender_id: _Optional[str] = ..., received_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class BotLifecycleEvent(_message.Message):
    __slots__ = ("kind", "message")
    KIND_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    kind: BotLifecycleKind
    message: str
    def __init__(self, kind: _Optional[_Union[BotLifecycleKind, str]] = ..., message: _Optional[str] = ...) -> None: ...

class BotEntityEvent(_message.Message):
    __slots__ = ("kind", "entity", "snapshot")
    KIND_FIELD_NUMBER: _ClassVar[int]
    ENTITY_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    kind: EntityEventKind
    entity: NearbyEntity
    snapshot: _domain_pb2.EntitySnapshot
    def __init__(self, kind: _Optional[_Union[EntityEventKind, str]] = ..., entity: _Optional[_Union[NearbyEntity, _Mapping]] = ..., snapshot: _Optional[_Union[_domain_pb2.EntitySnapshot, _Mapping]] = ...) -> None: ...

class BotBlockUpdateEvent(_message.Message):
    __slots__ = ("position", "old_block_id", "new_block_id", "block")
    POSITION_FIELD_NUMBER: _ClassVar[int]
    OLD_BLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    NEW_BLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    position: _common_pb2.BlockPosition
    old_block_id: str
    new_block_id: str
    block: _domain_pb2.BlockSnapshot
    def __init__(self, position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., old_block_id: _Optional[str] = ..., new_block_id: _Optional[str] = ..., block: _Optional[_Union[_domain_pb2.BlockSnapshot, _Mapping]] = ...) -> None: ...

class BotInventoryEvent(_message.Message):
    __slots__ = ("state",)
    STATE_FIELD_NUMBER: _ClassVar[int]
    state: _bot_pb2.BotInventoryStateResponse
    def __init__(self, state: _Optional[_Union[_bot_pb2.BotInventoryStateResponse, _Mapping]] = ...) -> None: ...

class BotDamageEvent(_message.Message):
    __slots__ = ("previous_health", "health", "amount", "damage_type")
    PREVIOUS_HEALTH_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    DAMAGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    previous_health: float
    health: float
    amount: float
    damage_type: str
    def __init__(self, previous_health: _Optional[float] = ..., health: _Optional[float] = ..., amount: _Optional[float] = ..., damage_type: _Optional[str] = ...) -> None: ...

class ClockSnapshot(_message.Message):
    __slots__ = ("clock_id", "total_ticks", "partial_tick", "rate")
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TICKS_FIELD_NUMBER: _ClassVar[int]
    PARTIAL_TICK_FIELD_NUMBER: _ClassVar[int]
    RATE_FIELD_NUMBER: _ClassVar[int]
    clock_id: str
    total_ticks: int
    partial_tick: float
    rate: float
    def __init__(self, clock_id: _Optional[str] = ..., total_ticks: _Optional[int] = ..., partial_tick: _Optional[float] = ..., rate: _Optional[float] = ...) -> None: ...

class BotTimeEvent(_message.Message):
    __slots__ = ("game_time", "clocks")
    GAME_TIME_FIELD_NUMBER: _ClassVar[int]
    CLOCKS_FIELD_NUMBER: _ClassVar[int]
    game_time: int
    clocks: _containers.RepeatedCompositeFieldContainer[ClockSnapshot]
    def __init__(self, game_time: _Optional[int] = ..., clocks: _Optional[_Iterable[_Union[ClockSnapshot, _Mapping]]] = ...) -> None: ...

class BotWeatherEvent(_message.Message):
    __slots__ = ("kind", "level")
    KIND_FIELD_NUMBER: _ClassVar[int]
    LEVEL_FIELD_NUMBER: _ClassVar[int]
    kind: WeatherEventKind
    level: float
    def __init__(self, kind: _Optional[_Union[WeatherEventKind, str]] = ..., level: _Optional[float] = ...) -> None: ...

class BotGameEvent(_message.Message):
    __slots__ = ("event", "parameter")
    EVENT_FIELD_NUMBER: _ClassVar[int]
    PARAMETER_FIELD_NUMBER: _ClassVar[int]
    event: str
    parameter: float
    def __init__(self, event: _Optional[str] = ..., parameter: _Optional[float] = ...) -> None: ...

class BotEnvironmentEvent(_message.Message):
    __slots__ = ("time", "weather", "game_event")
    TIME_FIELD_NUMBER: _ClassVar[int]
    WEATHER_FIELD_NUMBER: _ClassVar[int]
    GAME_EVENT_FIELD_NUMBER: _ClassVar[int]
    time: BotTimeEvent
    weather: BotWeatherEvent
    game_event: BotGameEvent
    def __init__(self, time: _Optional[_Union[BotTimeEvent, _Mapping]] = ..., weather: _Optional[_Union[BotWeatherEvent, _Mapping]] = ..., game_event: _Optional[_Union[BotGameEvent, _Mapping]] = ...) -> None: ...

class PlayerListEntrySnapshot(_message.Message):
    __slots__ = ("profile_id", "profile_name", "listed", "latency_ms", "game_mode", "display_name", "show_hat", "list_order", "changed_fields")
    PROFILE_ID_FIELD_NUMBER: _ClassVar[int]
    PROFILE_NAME_FIELD_NUMBER: _ClassVar[int]
    LISTED_FIELD_NUMBER: _ClassVar[int]
    LATENCY_MS_FIELD_NUMBER: _ClassVar[int]
    GAME_MODE_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    SHOW_HAT_FIELD_NUMBER: _ClassVar[int]
    LIST_ORDER_FIELD_NUMBER: _ClassVar[int]
    CHANGED_FIELDS_FIELD_NUMBER: _ClassVar[int]
    profile_id: str
    profile_name: str
    listed: bool
    latency_ms: int
    game_mode: _bot_pb2.GameMode
    display_name: _domain_pb2.TextComponent
    show_hat: bool
    list_order: int
    changed_fields: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, profile_id: _Optional[str] = ..., profile_name: _Optional[str] = ..., listed: bool = ..., latency_ms: _Optional[int] = ..., game_mode: _Optional[_Union[_bot_pb2.GameMode, str]] = ..., display_name: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., show_hat: bool = ..., list_order: _Optional[int] = ..., changed_fields: _Optional[_Iterable[str]] = ...) -> None: ...

class BotPlayerListEvent(_message.Message):
    __slots__ = ("kind", "entries", "removed_profile_ids")
    KIND_FIELD_NUMBER: _ClassVar[int]
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    REMOVED_PROFILE_IDS_FIELD_NUMBER: _ClassVar[int]
    kind: PlayerListEventKind
    entries: _containers.RepeatedCompositeFieldContainer[PlayerListEntrySnapshot]
    removed_profile_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, kind: _Optional[_Union[PlayerListEventKind, str]] = ..., entries: _Optional[_Iterable[_Union[PlayerListEntrySnapshot, _Mapping]]] = ..., removed_profile_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class BotBossBarEvent(_message.Message):
    __slots__ = ("boss_bar_id", "kind", "name", "progress", "color", "overlay", "darken_screen", "play_music", "create_world_fog")
    BOSS_BAR_ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    PROGRESS_FIELD_NUMBER: _ClassVar[int]
    COLOR_FIELD_NUMBER: _ClassVar[int]
    OVERLAY_FIELD_NUMBER: _ClassVar[int]
    DARKEN_SCREEN_FIELD_NUMBER: _ClassVar[int]
    PLAY_MUSIC_FIELD_NUMBER: _ClassVar[int]
    CREATE_WORLD_FOG_FIELD_NUMBER: _ClassVar[int]
    boss_bar_id: str
    kind: BossBarEventKind
    name: _domain_pb2.TextComponent
    progress: float
    color: str
    overlay: str
    darken_screen: bool
    play_music: bool
    create_world_fog: bool
    def __init__(self, boss_bar_id: _Optional[str] = ..., kind: _Optional[_Union[BossBarEventKind, str]] = ..., name: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., progress: _Optional[float] = ..., color: _Optional[str] = ..., overlay: _Optional[str] = ..., darken_screen: bool = ..., play_music: bool = ..., create_world_fog: bool = ...) -> None: ...

class BotSoundEvent(_message.Message):
    __slots__ = ("kind", "sound_id", "source", "position", "entity_id", "volume", "pitch", "seed")
    KIND_FIELD_NUMBER: _ClassVar[int]
    SOUND_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    VOLUME_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    SEED_FIELD_NUMBER: _ClassVar[int]
    kind: SoundEventKind
    sound_id: str
    source: str
    position: _common_pb2.WorldPosition
    entity_id: int
    volume: float
    pitch: float
    seed: int
    def __init__(self, kind: _Optional[_Union[SoundEventKind, str]] = ..., sound_id: _Optional[str] = ..., source: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., entity_id: _Optional[int] = ..., volume: _Optional[float] = ..., pitch: _Optional[float] = ..., seed: _Optional[int] = ...) -> None: ...

class BotParticleEvent(_message.Message):
    __slots__ = ("particle_id", "position", "offset", "max_speed", "count", "always_show", "override_limiter", "options")
    PARTICLE_ID_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    MAX_SPEED_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    ALWAYS_SHOW_FIELD_NUMBER: _ClassVar[int]
    OVERRIDE_LIMITER_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    particle_id: str
    position: _common_pb2.WorldPosition
    offset: _domain_pb2.Vec3
    max_speed: float
    count: int
    always_show: bool
    override_limiter: bool
    options: str
    def __init__(self, particle_id: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., offset: _Optional[_Union[_domain_pb2.Vec3, _Mapping]] = ..., max_speed: _Optional[float] = ..., count: _Optional[int] = ..., always_show: bool = ..., override_limiter: bool = ..., options: _Optional[str] = ...) -> None: ...

class BotScoreboardEvent(_message.Message):
    __slots__ = ("kind", "objective_name", "display_name", "render_type", "display_slot", "owner", "score", "team_name", "players", "prefix", "suffix", "color", "name_tag_visibility", "collision_rule", "allow_friendly_fire", "see_friendly_invisibles")
    KIND_FIELD_NUMBER: _ClassVar[int]
    OBJECTIVE_NAME_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_NAME_FIELD_NUMBER: _ClassVar[int]
    RENDER_TYPE_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_SLOT_FIELD_NUMBER: _ClassVar[int]
    OWNER_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    TEAM_NAME_FIELD_NUMBER: _ClassVar[int]
    PLAYERS_FIELD_NUMBER: _ClassVar[int]
    PREFIX_FIELD_NUMBER: _ClassVar[int]
    SUFFIX_FIELD_NUMBER: _ClassVar[int]
    COLOR_FIELD_NUMBER: _ClassVar[int]
    NAME_TAG_VISIBILITY_FIELD_NUMBER: _ClassVar[int]
    COLLISION_RULE_FIELD_NUMBER: _ClassVar[int]
    ALLOW_FRIENDLY_FIRE_FIELD_NUMBER: _ClassVar[int]
    SEE_FRIENDLY_INVISIBLES_FIELD_NUMBER: _ClassVar[int]
    kind: ScoreboardEventKind
    objective_name: str
    display_name: _domain_pb2.TextComponent
    render_type: str
    display_slot: str
    owner: str
    score: int
    team_name: str
    players: _containers.RepeatedScalarFieldContainer[str]
    prefix: _domain_pb2.TextComponent
    suffix: _domain_pb2.TextComponent
    color: str
    name_tag_visibility: str
    collision_rule: str
    allow_friendly_fire: bool
    see_friendly_invisibles: bool
    def __init__(self, kind: _Optional[_Union[ScoreboardEventKind, str]] = ..., objective_name: _Optional[str] = ..., display_name: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., render_type: _Optional[str] = ..., display_slot: _Optional[str] = ..., owner: _Optional[str] = ..., score: _Optional[int] = ..., team_name: _Optional[str] = ..., players: _Optional[_Iterable[str]] = ..., prefix: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., suffix: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., color: _Optional[str] = ..., name_tag_visibility: _Optional[str] = ..., collision_rule: _Optional[str] = ..., allow_friendly_fire: bool = ..., see_friendly_invisibles: bool = ...) -> None: ...

class BotResourcePackEvent(_message.Message):
    __slots__ = ("kind", "pack_id", "url", "hash", "required", "prompt")
    KIND_FIELD_NUMBER: _ClassVar[int]
    PACK_ID_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    HASH_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_FIELD_NUMBER: _ClassVar[int]
    PROMPT_FIELD_NUMBER: _ClassVar[int]
    kind: ResourcePackEventKind
    pack_id: str
    url: str
    hash: str
    required: bool
    prompt: _domain_pb2.TextComponent
    def __init__(self, kind: _Optional[_Union[ResourcePackEventKind, str]] = ..., pack_id: _Optional[str] = ..., url: _Optional[str] = ..., hash: _Optional[str] = ..., required: bool = ..., prompt: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ...) -> None: ...

class BotTitleEvent(_message.Message):
    __slots__ = ("kind", "text", "fade_in_ticks", "stay_ticks", "fade_out_ticks")
    KIND_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    FADE_IN_TICKS_FIELD_NUMBER: _ClassVar[int]
    STAY_TICKS_FIELD_NUMBER: _ClassVar[int]
    FADE_OUT_TICKS_FIELD_NUMBER: _ClassVar[int]
    kind: TitleEventKind
    text: _domain_pb2.TextComponent
    fade_in_ticks: int
    stay_ticks: int
    fade_out_ticks: int
    def __init__(self, kind: _Optional[_Union[TitleEventKind, str]] = ..., text: _Optional[_Union[_domain_pb2.TextComponent, _Mapping]] = ..., fade_in_ticks: _Optional[int] = ..., stay_ticks: _Optional[int] = ..., fade_out_ticks: _Optional[int] = ...) -> None: ...

class BotChunkEvent(_message.Message):
    __slots__ = ("kind", "chunk_x", "chunk_z", "dimension")
    KIND_FIELD_NUMBER: _ClassVar[int]
    CHUNK_X_FIELD_NUMBER: _ClassVar[int]
    CHUNK_Z_FIELD_NUMBER: _ClassVar[int]
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    kind: ChunkEventKind
    chunk_x: int
    chunk_z: int
    dimension: str
    def __init__(self, kind: _Optional[_Union[ChunkEventKind, str]] = ..., chunk_x: _Optional[int] = ..., chunk_z: _Optional[int] = ..., dimension: _Optional[str] = ...) -> None: ...

class BotEventEnvelope(_message.Message):
    __slots__ = ("bot_id", "stream_epoch", "sequence", "observed_at", "snapshot_revision", "causation_id", "task_id")
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    STREAM_EPOCH_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_AT_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_REVISION_FIELD_NUMBER: _ClassVar[int]
    CAUSATION_ID_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    bot_id: str
    stream_epoch: str
    sequence: int
    observed_at: _timestamp_pb2.Timestamp
    snapshot_revision: int
    causation_id: str
    task_id: str
    def __init__(self, bot_id: _Optional[str] = ..., stream_epoch: _Optional[str] = ..., sequence: _Optional[int] = ..., observed_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., snapshot_revision: _Optional[int] = ..., causation_id: _Optional[str] = ..., task_id: _Optional[str] = ...) -> None: ...

class BotResyncRequired(_message.Message):
    __slots__ = ("reason", "requested_epoch", "requested_after_sequence")
    REASON_FIELD_NUMBER: _ClassVar[int]
    REQUESTED_EPOCH_FIELD_NUMBER: _ClassVar[int]
    REQUESTED_AFTER_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    reason: str
    requested_epoch: str
    requested_after_sequence: int
    def __init__(self, reason: _Optional[str] = ..., requested_epoch: _Optional[str] = ..., requested_after_sequence: _Optional[int] = ...) -> None: ...

class BotHeartbeat(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class BotEvent(_message.Message):
    __slots__ = ("snapshot", "state_delta", "chat", "lifecycle", "entity_event", "block_update", "status", "inventory", "damage", "resync_required", "heartbeat", "environment", "player_list", "boss_bar", "sound", "particle", "scoreboard", "resource_pack", "title", "chunk", "envelope")
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    STATE_DELTA_FIELD_NUMBER: _ClassVar[int]
    CHAT_FIELD_NUMBER: _ClassVar[int]
    LIFECYCLE_FIELD_NUMBER: _ClassVar[int]
    ENTITY_EVENT_FIELD_NUMBER: _ClassVar[int]
    BLOCK_UPDATE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_FIELD_NUMBER: _ClassVar[int]
    DAMAGE_FIELD_NUMBER: _ClassVar[int]
    RESYNC_REQUIRED_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    ENVIRONMENT_FIELD_NUMBER: _ClassVar[int]
    PLAYER_LIST_FIELD_NUMBER: _ClassVar[int]
    BOSS_BAR_FIELD_NUMBER: _ClassVar[int]
    SOUND_FIELD_NUMBER: _ClassVar[int]
    PARTICLE_FIELD_NUMBER: _ClassVar[int]
    SCOREBOARD_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_PACK_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    CHUNK_FIELD_NUMBER: _ClassVar[int]
    ENVELOPE_FIELD_NUMBER: _ClassVar[int]
    snapshot: _bot_pb2.BotLiveState
    state_delta: BotStateDelta
    chat: BotChatEvent
    lifecycle: BotLifecycleEvent
    entity_event: BotEntityEvent
    block_update: BotBlockUpdateEvent
    status: _bot_pb2.BotStatus
    inventory: BotInventoryEvent
    damage: BotDamageEvent
    resync_required: BotResyncRequired
    heartbeat: BotHeartbeat
    environment: BotEnvironmentEvent
    player_list: BotPlayerListEvent
    boss_bar: BotBossBarEvent
    sound: BotSoundEvent
    particle: BotParticleEvent
    scoreboard: BotScoreboardEvent
    resource_pack: BotResourcePackEvent
    title: BotTitleEvent
    chunk: BotChunkEvent
    envelope: BotEventEnvelope
    def __init__(self, snapshot: _Optional[_Union[_bot_pb2.BotLiveState, _Mapping]] = ..., state_delta: _Optional[_Union[BotStateDelta, _Mapping]] = ..., chat: _Optional[_Union[BotChatEvent, _Mapping]] = ..., lifecycle: _Optional[_Union[BotLifecycleEvent, _Mapping]] = ..., entity_event: _Optional[_Union[BotEntityEvent, _Mapping]] = ..., block_update: _Optional[_Union[BotBlockUpdateEvent, _Mapping]] = ..., status: _Optional[_Union[_bot_pb2.BotStatus, _Mapping]] = ..., inventory: _Optional[_Union[BotInventoryEvent, _Mapping]] = ..., damage: _Optional[_Union[BotDamageEvent, _Mapping]] = ..., resync_required: _Optional[_Union[BotResyncRequired, _Mapping]] = ..., heartbeat: _Optional[_Union[BotHeartbeat, _Mapping]] = ..., environment: _Optional[_Union[BotEnvironmentEvent, _Mapping]] = ..., player_list: _Optional[_Union[BotPlayerListEvent, _Mapping]] = ..., boss_bar: _Optional[_Union[BotBossBarEvent, _Mapping]] = ..., sound: _Optional[_Union[BotSoundEvent, _Mapping]] = ..., particle: _Optional[_Union[BotParticleEvent, _Mapping]] = ..., scoreboard: _Optional[_Union[BotScoreboardEvent, _Mapping]] = ..., resource_pack: _Optional[_Union[BotResourcePackEvent, _Mapping]] = ..., title: _Optional[_Union[BotTitleEvent, _Mapping]] = ..., chunk: _Optional[_Union[BotChunkEvent, _Mapping]] = ..., envelope: _Optional[_Union[BotEventEnvelope, _Mapping]] = ...) -> None: ...

class BotActionResult(_message.Message):
    __slots__ = ("action_id", "status", "error")
    ACTION_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    action_id: str
    status: BotActionStatus
    error: str
    def __init__(self, action_id: _Optional[str] = ..., status: _Optional[_Union[BotActionStatus, str]] = ..., error: _Optional[str] = ...) -> None: ...

class SendChatRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "message")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    message: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class SendChatResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class GetBlockRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "position")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    position: _common_pb2.BlockPosition
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ...) -> None: ...

class GetBlockResponse(_message.Message):
    __slots__ = ("loaded", "block")
    LOADED_FIELD_NUMBER: _ClassVar[int]
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    loaded: bool
    block: BlockState
    def __init__(self, loaded: bool = ..., block: _Optional[_Union[BlockState, _Mapping]] = ...) -> None: ...

class FindBlocksRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "block_ids", "max_distance", "max_count")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    BLOCK_IDS_FIELD_NUMBER: _ClassVar[int]
    MAX_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    MAX_COUNT_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    block_ids: _containers.RepeatedScalarFieldContainer[str]
    max_distance: int
    max_count: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., block_ids: _Optional[_Iterable[str]] = ..., max_distance: _Optional[int] = ..., max_count: _Optional[int] = ...) -> None: ...

class FindBlocksResponse(_message.Message):
    __slots__ = ("blocks",)
    BLOCKS_FIELD_NUMBER: _ClassVar[int]
    blocks: _containers.RepeatedCompositeFieldContainer[BlockState]
    def __init__(self, blocks: _Optional[_Iterable[_Union[BlockState, _Mapping]]] = ...) -> None: ...

class ListNearbyEntitiesRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "radius", "entity_types", "include_players")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    RADIUS_FIELD_NUMBER: _ClassVar[int]
    ENTITY_TYPES_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_PLAYERS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    radius: float
    entity_types: _containers.RepeatedScalarFieldContainer[str]
    include_players: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., radius: _Optional[float] = ..., entity_types: _Optional[_Iterable[str]] = ..., include_players: bool = ...) -> None: ...

class ListNearbyEntitiesResponse(_message.Message):
    __slots__ = ("entities",)
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    entities: _containers.RepeatedCompositeFieldContainer[NearbyEntity]
    def __init__(self, entities: _Optional[_Iterable[_Union[NearbyEntity, _Mapping]]] = ...) -> None: ...

class DigBlockRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "position", "cancel")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    CANCEL_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    position: _common_pb2.BlockPosition
    cancel: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., cancel: bool = ...) -> None: ...

class DigBlockResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class PlaceBlockRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "against", "face", "hand")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    AGAINST_FIELD_NUMBER: _ClassVar[int]
    FACE_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    against: _common_pb2.BlockPosition
    face: BlockFace
    hand: Hand
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., against: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., face: _Optional[_Union[BlockFace, str]] = ..., hand: _Optional[_Union[Hand, str]] = ...) -> None: ...

class PlaceBlockResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class InteractBlockRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "position", "face", "hand", "sneaking")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    FACE_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    SNEAKING_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    position: _common_pb2.BlockPosition
    face: BlockFace
    hand: Hand
    sneaking: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., face: _Optional[_Union[BlockFace, str]] = ..., hand: _Optional[_Union[Hand, str]] = ..., sneaking: bool = ...) -> None: ...

class InteractBlockResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class UseItemRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "hand", "retry_ticks")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    RETRY_TICKS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    hand: Hand
    retry_ticks: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., hand: _Optional[_Union[Hand, str]] = ..., retry_ticks: _Optional[int] = ...) -> None: ...

class UseItemResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class ReleaseItemRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class ReleaseItemResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class AttackEntityRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "entity_id", "sprinting", "connection_epoch")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    SPRINTING_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_EPOCH_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    entity_id: int
    sprinting: bool
    connection_epoch: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., entity_id: _Optional[int] = ..., sprinting: bool = ..., connection_epoch: _Optional[str] = ...) -> None: ...

class AttackEntityResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class InteractEntityRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "entity_id", "hand", "sneaking", "connection_epoch")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    SNEAKING_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_EPOCH_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    entity_id: int
    hand: Hand
    sneaking: bool
    connection_epoch: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., entity_id: _Optional[int] = ..., hand: _Optional[_Union[Hand, str]] = ..., sneaking: bool = ..., connection_epoch: _Optional[str] = ...) -> None: ...

class InteractEntityResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class SwingArmRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "hand")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    hand: Hand
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., hand: _Optional[_Union[Hand, str]] = ...) -> None: ...

class SwingArmResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class RespawnRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class RespawnResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class SleepRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "bed", "hand")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    BED_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    bed: _common_pb2.BlockPosition
    hand: Hand
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., bed: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., hand: _Optional[_Union[Hand, str]] = ...) -> None: ...

class SleepResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class WakeRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class WakeResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class MountEntityRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "entity_id", "hand", "connection_epoch")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    HAND_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_EPOCH_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    entity_id: int
    hand: Hand
    connection_epoch: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., entity_id: _Optional[int] = ..., hand: _Optional[_Union[Hand, str]] = ..., connection_epoch: _Optional[str] = ...) -> None: ...

class MountEntityResponse(_message.Message):
    __slots__ = ("result", "vehicle")
    RESULT_FIELD_NUMBER: _ClassVar[int]
    VEHICLE_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    vehicle: _domain_pb2.EntityReference
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ..., vehicle: _Optional[_Union[_domain_pb2.EntityReference, _Mapping]] = ...) -> None: ...

class DismountRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class DismountResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class SetVehicleControlRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "forward", "backward", "left", "right", "jump", "sneak", "sprint", "yaw", "pitch")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    FORWARD_FIELD_NUMBER: _ClassVar[int]
    BACKWARD_FIELD_NUMBER: _ClassVar[int]
    LEFT_FIELD_NUMBER: _ClassVar[int]
    RIGHT_FIELD_NUMBER: _ClassVar[int]
    JUMP_FIELD_NUMBER: _ClassVar[int]
    SNEAK_FIELD_NUMBER: _ClassVar[int]
    SPRINT_FIELD_NUMBER: _ClassVar[int]
    YAW_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    forward: bool
    backward: bool
    left: bool
    right: bool
    jump: bool
    sneak: bool
    sprint: bool
    yaw: float
    pitch: float
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., forward: bool = ..., backward: bool = ..., left: bool = ..., right: bool = ..., jump: bool = ..., sneak: bool = ..., sprint: bool = ..., yaw: _Optional[float] = ..., pitch: _Optional[float] = ...) -> None: ...

class SetVehicleControlResponse(_message.Message):
    __slots__ = ("result", "vehicle")
    RESULT_FIELD_NUMBER: _ClassVar[int]
    VEHICLE_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    vehicle: _domain_pb2.EntityReference
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ..., vehicle: _Optional[_Union[_domain_pb2.EntityReference, _Mapping]] = ...) -> None: ...

class UpdateSignRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "position", "front_text", "lines")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    FRONT_TEXT_FIELD_NUMBER: _ClassVar[int]
    LINES_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    position: _common_pb2.BlockPosition
    front_text: bool
    lines: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., front_text: bool = ..., lines: _Optional[_Iterable[str]] = ...) -> None: ...

class UpdateSignResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class WriteBookRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "inventory_slot", "pages", "title")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_SLOT_FIELD_NUMBER: _ClassVar[int]
    PAGES_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    inventory_slot: int
    pages: _containers.RepeatedScalarFieldContainer[str]
    title: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., inventory_slot: _Optional[int] = ..., pages: _Optional[_Iterable[str]] = ..., title: _Optional[str] = ...) -> None: ...

class WriteBookResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class RespondResourcePackRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "pack_id", "response")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    PACK_ID_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    pack_id: str
    response: ResourcePackResponse
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., pack_id: _Optional[str] = ..., response: _Optional[_Union[ResourcePackResponse, str]] = ...) -> None: ...

class RespondResourcePackResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class SetFlyingRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "flying")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    FLYING_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    flying: bool
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., flying: bool = ...) -> None: ...

class SetFlyingResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class StartElytraFlightRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class StartElytraFlightResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class CreativeItemStack(_message.Message):
    __slots__ = ("item_id", "count")
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    item_id: str
    count: int
    def __init__(self, item_id: _Optional[str] = ..., count: _Optional[int] = ...) -> None: ...

class SetCreativeSlotRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "slot", "item")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    SLOT_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    slot: int
    item: CreativeItemStack
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., slot: _Optional[int] = ..., item: _Optional[_Union[CreativeItemStack, _Mapping]] = ...) -> None: ...

class SetCreativeSlotResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: BotActionResult
    def __init__(self, result: _Optional[_Union[BotActionResult, _Mapping]] = ...) -> None: ...

class WaitForChunksRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "radius_chunks", "timeout_ms")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    RADIUS_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    radius_chunks: int
    timeout_ms: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., radius_chunks: _Optional[int] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class WaitForChunksResponse(_message.Message):
    __slots__ = ("center_chunk_x", "center_chunk_z", "loaded_chunks", "required_chunks", "dimension")
    CENTER_CHUNK_X_FIELD_NUMBER: _ClassVar[int]
    CENTER_CHUNK_Z_FIELD_NUMBER: _ClassVar[int]
    LOADED_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    center_chunk_x: int
    center_chunk_z: int
    loaded_chunks: int
    required_chunks: int
    dimension: str
    def __init__(self, center_chunk_x: _Optional[int] = ..., center_chunk_z: _Optional[int] = ..., loaded_chunks: _Optional[int] = ..., required_chunks: _Optional[int] = ..., dimension: _Optional[str] = ...) -> None: ...

class PathfindGoal(_message.Message):
    __slots__ = ("block", "near", "entity", "xz", "y", "break_block", "place_block", "away_from_position", "away_from_entity", "any")
    class BlockGoal(_message.Message):
        __slots__ = ("position", "radius")
        POSITION_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        position: _common_pb2.BlockPosition
        radius: float
        def __init__(self, position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ..., radius: _Optional[float] = ...) -> None: ...
    class NearGoal(_message.Message):
        __slots__ = ("position", "radius")
        POSITION_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        position: _common_pb2.WorldPosition
        radius: float
        def __init__(self, position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., radius: _Optional[float] = ...) -> None: ...
    class EntityGoal(_message.Message):
        __slots__ = ("entity_id", "radius", "connection_epoch")
        ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        CONNECTION_EPOCH_FIELD_NUMBER: _ClassVar[int]
        entity_id: int
        radius: float
        connection_epoch: str
        def __init__(self, entity_id: _Optional[int] = ..., radius: _Optional[float] = ..., connection_epoch: _Optional[str] = ...) -> None: ...
    class XZGoal(_message.Message):
        __slots__ = ("x", "z", "dimension", "radius")
        X_FIELD_NUMBER: _ClassVar[int]
        Z_FIELD_NUMBER: _ClassVar[int]
        DIMENSION_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        x: float
        z: float
        dimension: str
        radius: float
        def __init__(self, x: _Optional[float] = ..., z: _Optional[float] = ..., dimension: _Optional[str] = ..., radius: _Optional[float] = ...) -> None: ...
    class YGoal(_message.Message):
        __slots__ = ("y", "dimension")
        Y_FIELD_NUMBER: _ClassVar[int]
        DIMENSION_FIELD_NUMBER: _ClassVar[int]
        y: int
        dimension: str
        def __init__(self, y: _Optional[int] = ..., dimension: _Optional[str] = ...) -> None: ...
    class BreakBlockGoal(_message.Message):
        __slots__ = ("position",)
        POSITION_FIELD_NUMBER: _ClassVar[int]
        position: _common_pb2.BlockPosition
        def __init__(self, position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ...) -> None: ...
    class PlaceBlockGoal(_message.Message):
        __slots__ = ("position",)
        POSITION_FIELD_NUMBER: _ClassVar[int]
        position: _common_pb2.BlockPosition
        def __init__(self, position: _Optional[_Union[_common_pb2.BlockPosition, _Mapping]] = ...) -> None: ...
    class AwayFromPositionGoal(_message.Message):
        __slots__ = ("position", "radius")
        POSITION_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        position: _common_pb2.WorldPosition
        radius: float
        def __init__(self, position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., radius: _Optional[float] = ...) -> None: ...
    class AwayFromEntityGoal(_message.Message):
        __slots__ = ("entity_id", "radius", "connection_epoch")
        ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
        RADIUS_FIELD_NUMBER: _ClassVar[int]
        CONNECTION_EPOCH_FIELD_NUMBER: _ClassVar[int]
        entity_id: int
        radius: float
        connection_epoch: str
        def __init__(self, entity_id: _Optional[int] = ..., radius: _Optional[float] = ..., connection_epoch: _Optional[str] = ...) -> None: ...
    class AnyGoal(_message.Message):
        __slots__ = ("goals",)
        GOALS_FIELD_NUMBER: _ClassVar[int]
        goals: _containers.RepeatedCompositeFieldContainer[PathfindGoal]
        def __init__(self, goals: _Optional[_Iterable[_Union[PathfindGoal, _Mapping]]] = ...) -> None: ...
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    NEAR_FIELD_NUMBER: _ClassVar[int]
    ENTITY_FIELD_NUMBER: _ClassVar[int]
    XZ_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    BREAK_BLOCK_FIELD_NUMBER: _ClassVar[int]
    PLACE_BLOCK_FIELD_NUMBER: _ClassVar[int]
    AWAY_FROM_POSITION_FIELD_NUMBER: _ClassVar[int]
    AWAY_FROM_ENTITY_FIELD_NUMBER: _ClassVar[int]
    ANY_FIELD_NUMBER: _ClassVar[int]
    block: PathfindGoal.BlockGoal
    near: PathfindGoal.NearGoal
    entity: PathfindGoal.EntityGoal
    xz: PathfindGoal.XZGoal
    y: PathfindGoal.YGoal
    break_block: PathfindGoal.BreakBlockGoal
    place_block: PathfindGoal.PlaceBlockGoal
    away_from_position: PathfindGoal.AwayFromPositionGoal
    away_from_entity: PathfindGoal.AwayFromEntityGoal
    any: PathfindGoal.AnyGoal
    def __init__(self, block: _Optional[_Union[PathfindGoal.BlockGoal, _Mapping]] = ..., near: _Optional[_Union[PathfindGoal.NearGoal, _Mapping]] = ..., entity: _Optional[_Union[PathfindGoal.EntityGoal, _Mapping]] = ..., xz: _Optional[_Union[PathfindGoal.XZGoal, _Mapping]] = ..., y: _Optional[_Union[PathfindGoal.YGoal, _Mapping]] = ..., break_block: _Optional[_Union[PathfindGoal.BreakBlockGoal, _Mapping]] = ..., place_block: _Optional[_Union[PathfindGoal.PlaceBlockGoal, _Mapping]] = ..., away_from_position: _Optional[_Union[PathfindGoal.AwayFromPositionGoal, _Mapping]] = ..., away_from_entity: _Optional[_Union[PathfindGoal.AwayFromEntityGoal, _Mapping]] = ..., any: _Optional[_Union[PathfindGoal.AnyGoal, _Mapping]] = ...) -> None: ...

class PathfindOptions(_message.Message):
    __slots__ = ("allow_mining", "allow_placing", "timeout_seconds", "search_timeout_seconds", "break_block_penalty", "place_block_penalty", "avoid_fluids", "additional_place_item_ids", "sprint", "minimum_y", "maximum_y", "search_mode", "maximum_quality_bound", "smooth_camera", "maximum_expanded_states", "maximum_fall_distance", "maximum_parkour_gap", "protected_blocks", "walking_pitch")
    ALLOW_MINING_FIELD_NUMBER: _ClassVar[int]
    ALLOW_PLACING_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_SECONDS_FIELD_NUMBER: _ClassVar[int]
    SEARCH_TIMEOUT_SECONDS_FIELD_NUMBER: _ClassVar[int]
    BREAK_BLOCK_PENALTY_FIELD_NUMBER: _ClassVar[int]
    PLACE_BLOCK_PENALTY_FIELD_NUMBER: _ClassVar[int]
    AVOID_FLUIDS_FIELD_NUMBER: _ClassVar[int]
    ADDITIONAL_PLACE_ITEM_IDS_FIELD_NUMBER: _ClassVar[int]
    SPRINT_FIELD_NUMBER: _ClassVar[int]
    MINIMUM_Y_FIELD_NUMBER: _ClassVar[int]
    MAXIMUM_Y_FIELD_NUMBER: _ClassVar[int]
    SEARCH_MODE_FIELD_NUMBER: _ClassVar[int]
    MAXIMUM_QUALITY_BOUND_FIELD_NUMBER: _ClassVar[int]
    SMOOTH_CAMERA_FIELD_NUMBER: _ClassVar[int]
    MAXIMUM_EXPANDED_STATES_FIELD_NUMBER: _ClassVar[int]
    MAXIMUM_FALL_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    MAXIMUM_PARKOUR_GAP_FIELD_NUMBER: _ClassVar[int]
    PROTECTED_BLOCKS_FIELD_NUMBER: _ClassVar[int]
    WALKING_PITCH_FIELD_NUMBER: _ClassVar[int]
    allow_mining: bool
    allow_placing: bool
    timeout_seconds: int
    search_timeout_seconds: int
    break_block_penalty: float
    place_block_penalty: float
    avoid_fluids: bool
    additional_place_item_ids: _containers.RepeatedScalarFieldContainer[str]
    sprint: bool
    minimum_y: int
    maximum_y: int
    search_mode: PathfindSearchMode
    maximum_quality_bound: float
    smooth_camera: bool
    maximum_expanded_states: int
    maximum_fall_distance: int
    maximum_parkour_gap: int
    protected_blocks: _containers.RepeatedCompositeFieldContainer[_common_pb2.BlockPosition]
    walking_pitch: float
    def __init__(self, allow_mining: bool = ..., allow_placing: bool = ..., timeout_seconds: _Optional[int] = ..., search_timeout_seconds: _Optional[int] = ..., break_block_penalty: _Optional[float] = ..., place_block_penalty: _Optional[float] = ..., avoid_fluids: bool = ..., additional_place_item_ids: _Optional[_Iterable[str]] = ..., sprint: bool = ..., minimum_y: _Optional[int] = ..., maximum_y: _Optional[int] = ..., search_mode: _Optional[_Union[PathfindSearchMode, str]] = ..., maximum_quality_bound: _Optional[float] = ..., smooth_camera: bool = ..., maximum_expanded_states: _Optional[int] = ..., maximum_fall_distance: _Optional[int] = ..., maximum_parkour_gap: _Optional[int] = ..., protected_blocks: _Optional[_Iterable[_Union[_common_pb2.BlockPosition, _Mapping]]] = ..., walking_pitch: _Optional[float] = ...) -> None: ...

class GoToRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "goal", "options")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    GOAL_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    goal: PathfindGoal
    options: PathfindOptions
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., goal: _Optional[_Union[PathfindGoal, _Mapping]] = ..., options: _Optional[_Union[PathfindOptions, _Mapping]] = ...) -> None: ...

class PathfindProgress(_message.Message):
    __slots__ = ("status", "distance_remaining", "position", "error", "action_id")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_REMAINING_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    ACTION_ID_FIELD_NUMBER: _ClassVar[int]
    status: PathfindStatus
    distance_remaining: float
    position: _common_pb2.WorldPosition
    error: str
    action_id: str
    def __init__(self, status: _Optional[_Union[PathfindStatus, str]] = ..., distance_remaining: _Optional[float] = ..., position: _Optional[_Union[_common_pb2.WorldPosition, _Mapping]] = ..., error: _Optional[str] = ..., action_id: _Optional[str] = ...) -> None: ...

class StopPathfindingRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class StopPathfindingResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class AcquireBotControlRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "ttl_seconds")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    TTL_SECONDS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    ttl_seconds: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., ttl_seconds: _Optional[int] = ...) -> None: ...

class BotControlLease(_message.Message):
    __slots__ = ("token", "expires_at")
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_FIELD_NUMBER: _ClassVar[int]
    token: str
    expires_at: _timestamp_pb2.Timestamp
    def __init__(self, token: _Optional[str] = ..., expires_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class AcquireBotControlResponse(_message.Message):
    __slots__ = ("lease",)
    LEASE_FIELD_NUMBER: _ClassVar[int]
    lease: BotControlLease
    def __init__(self, lease: _Optional[_Union[BotControlLease, _Mapping]] = ...) -> None: ...

class RenewBotControlRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "token", "ttl_seconds")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    TTL_SECONDS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    token: str
    ttl_seconds: int
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., token: _Optional[str] = ..., ttl_seconds: _Optional[int] = ...) -> None: ...

class RenewBotControlResponse(_message.Message):
    __slots__ = ("lease",)
    LEASE_FIELD_NUMBER: _ClassVar[int]
    lease: BotControlLease
    def __init__(self, lease: _Optional[_Union[BotControlLease, _Mapping]] = ...) -> None: ...

class ReleaseBotControlRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "token")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    token: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., token: _Optional[str] = ...) -> None: ...

class ReleaseBotControlResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
