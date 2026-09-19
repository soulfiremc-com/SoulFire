from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class PovWatchRequest(_message.Message):
    __slots__ = ("instance_id", "bot_id", "session_id", "width", "height", "max_fps", "codecs")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    MAX_FPS_FIELD_NUMBER: _ClassVar[int]
    CODECS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    session_id: str
    width: int
    height: int
    max_fps: int
    codecs: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ..., session_id: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., max_fps: _Optional[int] = ..., codecs: _Optional[_Iterable[str]] = ...) -> None: ...

class PovFrame(_message.Message):
    __slots__ = ("sequence", "width", "height", "screen_open", "data", "timestamp_us", "key_frame", "codec", "cursor_shape", "target_bitrate", "input_token", "target_fps", "clipboard", "clipboard_sequence", "open_url")
    class CursorShape(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        ARROW: _ClassVar[PovFrame.CursorShape]
        TEXT: _ClassVar[PovFrame.CursorShape]
        CROSSHAIR: _ClassVar[PovFrame.CursorShape]
        POINTER: _ClassVar[PovFrame.CursorShape]
        RESIZE_NS: _ClassVar[PovFrame.CursorShape]
        RESIZE_EW: _ClassVar[PovFrame.CursorShape]
        RESIZE_ALL: _ClassVar[PovFrame.CursorShape]
        NOT_ALLOWED: _ClassVar[PovFrame.CursorShape]
    ARROW: PovFrame.CursorShape
    TEXT: PovFrame.CursorShape
    CROSSHAIR: PovFrame.CursorShape
    POINTER: PovFrame.CursorShape
    RESIZE_NS: PovFrame.CursorShape
    RESIZE_EW: PovFrame.CursorShape
    RESIZE_ALL: PovFrame.CursorShape
    NOT_ALLOWED: PovFrame.CursorShape
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    SCREEN_OPEN_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_US_FIELD_NUMBER: _ClassVar[int]
    KEY_FRAME_FIELD_NUMBER: _ClassVar[int]
    CODEC_FIELD_NUMBER: _ClassVar[int]
    CURSOR_SHAPE_FIELD_NUMBER: _ClassVar[int]
    TARGET_BITRATE_FIELD_NUMBER: _ClassVar[int]
    INPUT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    TARGET_FPS_FIELD_NUMBER: _ClassVar[int]
    CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    CLIPBOARD_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    OPEN_URL_FIELD_NUMBER: _ClassVar[int]
    sequence: int
    width: int
    height: int
    screen_open: bool
    data: bytes
    timestamp_us: int
    key_frame: bool
    codec: str
    cursor_shape: PovFrame.CursorShape
    target_bitrate: int
    input_token: str
    target_fps: int
    clipboard: str
    clipboard_sequence: int
    open_url: str
    def __init__(self, sequence: _Optional[int] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., screen_open: bool = ..., data: _Optional[bytes] = ..., timestamp_us: _Optional[int] = ..., key_frame: bool = ..., codec: _Optional[str] = ..., cursor_shape: _Optional[_Union[PovFrame.CursorShape, str]] = ..., target_bitrate: _Optional[int] = ..., input_token: _Optional[str] = ..., target_fps: _Optional[int] = ..., clipboard: _Optional[str] = ..., clipboard_sequence: _Optional[int] = ..., open_url: _Optional[str] = ...) -> None: ...

class PovInputRequest(_message.Message):
    __slots__ = ("session_id", "sequence", "captured", "width", "height", "events", "escape", "request_key_frame", "feedback", "input_token", "clipboard", "read_clipboard", "max_fps")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    CAPTURED_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    ESCAPE_FIELD_NUMBER: _ClassVar[int]
    REQUEST_KEY_FRAME_FIELD_NUMBER: _ClassVar[int]
    FEEDBACK_FIELD_NUMBER: _ClassVar[int]
    INPUT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    READ_CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    MAX_FPS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    sequence: int
    captured: bool
    width: int
    height: int
    events: _containers.RepeatedCompositeFieldContainer[PovInputEvent]
    escape: bool
    request_key_frame: bool
    feedback: PovStreamFeedback
    input_token: str
    clipboard: str
    read_clipboard: bool
    max_fps: int
    def __init__(self, session_id: _Optional[str] = ..., sequence: _Optional[int] = ..., captured: bool = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., events: _Optional[_Iterable[_Union[PovInputEvent, _Mapping]]] = ..., escape: bool = ..., request_key_frame: bool = ..., feedback: _Optional[_Union[PovStreamFeedback, _Mapping]] = ..., input_token: _Optional[str] = ..., clipboard: _Optional[str] = ..., read_clipboard: bool = ..., max_fps: _Optional[int] = ...) -> None: ...

class PovStreamFeedback(_message.Message):
    __slots__ = ("received_sequence", "delivery_delay_ms", "decoder_queue_size", "decoder_recoveries")
    RECEIVED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    DELIVERY_DELAY_MS_FIELD_NUMBER: _ClassVar[int]
    DECODER_QUEUE_SIZE_FIELD_NUMBER: _ClassVar[int]
    DECODER_RECOVERIES_FIELD_NUMBER: _ClassVar[int]
    received_sequence: int
    delivery_delay_ms: float
    decoder_queue_size: int
    decoder_recoveries: int
    def __init__(self, received_sequence: _Optional[int] = ..., delivery_delay_ms: _Optional[float] = ..., decoder_queue_size: _Optional[int] = ..., decoder_recoveries: _Optional[int] = ...) -> None: ...

class PovInputResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PovInputEvent(_message.Message):
    __slots__ = ("kind", "code", "action", "modifiers", "x", "y", "relative")
    class Kind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        KEY: _ClassVar[PovInputEvent.Kind]
        CHARACTER: _ClassVar[PovInputEvent.Kind]
        BUTTON: _ClassVar[PovInputEvent.Kind]
        MOVE: _ClassVar[PovInputEvent.Kind]
        SCROLL: _ClassVar[PovInputEvent.Kind]
    KEY: PovInputEvent.Kind
    CHARACTER: PovInputEvent.Kind
    BUTTON: PovInputEvent.Kind
    MOVE: PovInputEvent.Kind
    SCROLL: PovInputEvent.Kind
    KIND_FIELD_NUMBER: _ClassVar[int]
    CODE_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    MODIFIERS_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    RELATIVE_FIELD_NUMBER: _ClassVar[int]
    kind: PovInputEvent.Kind
    code: int
    action: int
    modifiers: int
    x: float
    y: float
    relative: bool
    def __init__(self, kind: _Optional[_Union[PovInputEvent.Kind, str]] = ..., code: _Optional[int] = ..., action: _Optional[int] = ..., modifiers: _Optional[int] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., relative: bool = ...) -> None: ...
