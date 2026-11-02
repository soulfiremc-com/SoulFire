from soulfire import common_pb2 as _common_pb2
from soulfire import api_docs_pb2 as _api_docs_pb2
from google.api import annotations_pb2 as _annotations_pb2
from google.api import field_behavior_pb2 as _field_behavior_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ClientDataRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GlobalPermissionState(_message.Message):
    __slots__ = ("global_permission", "granted")
    GLOBAL_PERMISSION_FIELD_NUMBER: _ClassVar[int]
    GRANTED_FIELD_NUMBER: _ClassVar[int]
    global_permission: _common_pb2.GlobalPermission
    granted: bool
    def __init__(self, global_permission: _Optional[_Union[_common_pb2.GlobalPermission, str]] = ..., granted: bool = ...) -> None: ...

class ServerInfo(_message.Message):
    __slots__ = ("version", "commit_hash", "branch_name", "public_api_address", "public_webdav_address", "public_docs_address", "public_mcp_address", "minecraft_version", "installation_id")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    COMMIT_HASH_FIELD_NUMBER: _ClassVar[int]
    BRANCH_NAME_FIELD_NUMBER: _ClassVar[int]
    PUBLIC_API_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    PUBLIC_WEBDAV_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    PUBLIC_DOCS_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    PUBLIC_MCP_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    MINECRAFT_VERSION_FIELD_NUMBER: _ClassVar[int]
    INSTALLATION_ID_FIELD_NUMBER: _ClassVar[int]
    version: str
    commit_hash: str
    branch_name: str
    public_api_address: str
    public_webdav_address: str
    public_docs_address: str
    public_mcp_address: str
    minecraft_version: str
    installation_id: str
    def __init__(self, version: _Optional[str] = ..., commit_hash: _Optional[str] = ..., branch_name: _Optional[str] = ..., public_api_address: _Optional[str] = ..., public_webdav_address: _Optional[str] = ..., public_docs_address: _Optional[str] = ..., public_mcp_address: _Optional[str] = ..., minecraft_version: _Optional[str] = ..., installation_id: _Optional[str] = ...) -> None: ...

class ClientDataResponse(_message.Message):
    __slots__ = ("id", "username", "role", "email", "server_permissions", "server_info")
    ID_FIELD_NUMBER: _ClassVar[int]
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    ROLE_FIELD_NUMBER: _ClassVar[int]
    EMAIL_FIELD_NUMBER: _ClassVar[int]
    SERVER_PERMISSIONS_FIELD_NUMBER: _ClassVar[int]
    SERVER_INFO_FIELD_NUMBER: _ClassVar[int]
    id: str
    username: str
    role: _common_pb2.UserRole
    email: str
    server_permissions: _containers.RepeatedCompositeFieldContainer[GlobalPermissionState]
    server_info: ServerInfo
    def __init__(self, id: _Optional[str] = ..., username: _Optional[str] = ..., role: _Optional[_Union[_common_pb2.UserRole, str]] = ..., email: _Optional[str] = ..., server_permissions: _Optional[_Iterable[_Union[GlobalPermissionState, _Mapping]]] = ..., server_info: _Optional[_Union[ServerInfo, _Mapping]] = ...) -> None: ...

class GenerateWebDAVTokenRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GenerateWebDAVTokenResponse(_message.Message):
    __slots__ = ("token",)
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    token: str
    def __init__(self, token: _Optional[str] = ...) -> None: ...

class GenerateAPITokenRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GenerateAPITokenResponse(_message.Message):
    __slots__ = ("token",)
    TOKEN_FIELD_NUMBER: _ClassVar[int]
    token: str
    def __init__(self, token: _Optional[str] = ...) -> None: ...

class UpdateSelfUsernameRequest(_message.Message):
    __slots__ = ("username",)
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    username: str
    def __init__(self, username: _Optional[str] = ...) -> None: ...

class UpdateSelfUsernameResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class UpdateSelfEmailRequest(_message.Message):
    __slots__ = ("email",)
    EMAIL_FIELD_NUMBER: _ClassVar[int]
    email: str
    def __init__(self, email: _Optional[str] = ...) -> None: ...

class UpdateSelfEmailResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InvalidateSelfSessionsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InvalidateSelfSessionsResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
