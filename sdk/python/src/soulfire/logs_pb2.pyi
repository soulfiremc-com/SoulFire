import datetime

from soulfire import api_docs_pb2 as _api_docs_pb2
from google.api import annotations_pb2 as _annotations_pb2
from google.api import field_behavior_pb2 as _field_behavior_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LogString(_message.Message):
    __slots__ = ("id", "message", "instance_id", "bot_account_id", "script_id", "personal", "instance_name", "bot_account_name", "timestamp", "logger_name", "level", "terminal_interactions")
    ID_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ACCOUNT_ID_FIELD_NUMBER: _ClassVar[int]
    SCRIPT_ID_FIELD_NUMBER: _ClassVar[int]
    PERSONAL_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_NAME_FIELD_NUMBER: _ClassVar[int]
    BOT_ACCOUNT_NAME_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    LOGGER_NAME_FIELD_NUMBER: _ClassVar[int]
    LEVEL_FIELD_NUMBER: _ClassVar[int]
    TERMINAL_INTERACTIONS_FIELD_NUMBER: _ClassVar[int]
    id: str
    message: str
    instance_id: str
    bot_account_id: str
    script_id: str
    personal: bool
    instance_name: str
    bot_account_name: str
    timestamp: _timestamp_pb2.Timestamp
    logger_name: str
    level: str
    terminal_interactions: bool
    def __init__(self, id: _Optional[str] = ..., message: _Optional[str] = ..., instance_id: _Optional[str] = ..., bot_account_id: _Optional[str] = ..., script_id: _Optional[str] = ..., personal: bool = ..., instance_name: _Optional[str] = ..., bot_account_name: _Optional[str] = ..., timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., logger_name: _Optional[str] = ..., level: _Optional[str] = ..., terminal_interactions: bool = ...) -> None: ...

class GlobalLogScope(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InstanceLogScope(_message.Message):
    __slots__ = ("instance_id",)
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    def __init__(self, instance_id: _Optional[str] = ...) -> None: ...

class BotLogScope(_message.Message):
    __slots__ = ("instance_id", "bot_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    BOT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    bot_id: str
    def __init__(self, instance_id: _Optional[str] = ..., bot_id: _Optional[str] = ...) -> None: ...

class InstanceScriptLogScope(_message.Message):
    __slots__ = ("instance_id", "script_id")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    SCRIPT_ID_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    script_id: str
    def __init__(self, instance_id: _Optional[str] = ..., script_id: _Optional[str] = ...) -> None: ...

class PersonalLogScope(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class LogScope(_message.Message):
    __slots__ = ("instance", "bot", "instance_script", "personal")
    GLOBAL_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_FIELD_NUMBER: _ClassVar[int]
    BOT_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_SCRIPT_FIELD_NUMBER: _ClassVar[int]
    PERSONAL_FIELD_NUMBER: _ClassVar[int]
    instance: InstanceLogScope
    bot: BotLogScope
    instance_script: InstanceScriptLogScope
    personal: PersonalLogScope
    def __init__(self, instance: _Optional[_Union[InstanceLogScope, _Mapping]] = ..., bot: _Optional[_Union[BotLogScope, _Mapping]] = ..., instance_script: _Optional[_Union[InstanceScriptLogScope, _Mapping]] = ..., personal: _Optional[_Union[PersonalLogScope, _Mapping]] = ..., **kwargs) -> None: ...

class PreviousLogRequest(_message.Message):
    __slots__ = ("scope", "count")
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    scope: LogScope
    count: int
    def __init__(self, scope: _Optional[_Union[LogScope, _Mapping]] = ..., count: _Optional[int] = ...) -> None: ...

class PreviousLogResponse(_message.Message):
    __slots__ = ("messages",)
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    messages: _containers.RepeatedCompositeFieldContainer[LogString]
    def __init__(self, messages: _Optional[_Iterable[_Union[LogString, _Mapping]]] = ...) -> None: ...

class LogRequest(_message.Message):
    __slots__ = ("scope",)
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    scope: LogScope
    def __init__(self, scope: _Optional[_Union[LogScope, _Mapping]] = ...) -> None: ...

class LogResponse(_message.Message):
    __slots__ = ("message",)
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    message: LogString
    def __init__(self, message: _Optional[_Union[LogString, _Mapping]] = ...) -> None: ...
