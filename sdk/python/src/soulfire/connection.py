from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Final

from .plugin_api_pb2 import PluginApiDescriptor
from .sdk_pb2 import SdkApiVersion, SdkHandshakeResponse, SdkIdentity

SDK_VERSION: Final = "2.11.0"
SDK_API_VERSION: Final = SdkApiVersion(major=1, minor=0, patch=0)


class SoulFireCompatibilityError(RuntimeError):
    """The server and SDK cannot safely communicate."""


class SoulFireCapabilityError(RuntimeError):
    def __init__(self, capability: str) -> None:
        super().__init__(f"SoulFire capability is unavailable: {capability}")
        self.capability = capability


@dataclass(frozen=True, slots=True)
class RequiredPlugin:
    plugin_id: str
    version_range: str | None = None


@dataclass(frozen=True, slots=True)
class ServerMetadata:
    id: str
    version: str
    commit_hash: str
    branch_name: str
    api_version: SdkApiVersion
    minecraft_version: str
    supported_minecraft_versions: tuple[str, ...]
    transports: tuple[int, ...]


class CapabilitySet:
    __slots__ = ("_revisions",)

    def __init__(self, response: SdkHandshakeResponse) -> None:
        self._revisions = MappingProxyType(
            {capability.id: capability.revision for capability in response.capabilities}
        )

    def supports(self, capability: str, *, minimum_revision: int = 1) -> bool:
        return self._revisions.get(capability, 0) >= minimum_revision

    def require(self, capability: str, *, minimum_revision: int = 1) -> None:
        if not self.supports(capability, minimum_revision=minimum_revision):
            raise SoulFireCapabilityError(capability)

    def revision(self, capability: str) -> int | None:
        return self._revisions.get(capability)

    @property
    def revisions(self) -> MappingProxyType[str, int]:
        return self._revisions


@dataclass(frozen=True, slots=True)
class ConnectionMetadata:
    server: ServerMetadata
    identity: SdkIdentity
    capabilities: CapabilitySet
    plugins: tuple[PluginApiDescriptor, ...]
    limits: MappingProxyType[str, int]

    @classmethod
    def from_response(cls, response: SdkHandshakeResponse) -> ConnectionMetadata:
        if not response.HasField("api_version") or not response.HasField("identity"):
            raise SoulFireCompatibilityError("SoulFire returned an incomplete SDK handshake")
        return cls(
            server=ServerMetadata(
                id=response.server_id,
                version=response.soulfire_version,
                commit_hash=response.commit_hash,
                branch_name=response.branch_name,
                api_version=response.api_version,
                minecraft_version=response.native_minecraft_version,
                supported_minecraft_versions=tuple(response.supported_minecraft_versions),
                transports=tuple(response.transports),
            ),
            identity=response.identity,
            capabilities=CapabilitySet(response),
            plugins=tuple(response.plugins),
            limits=MappingProxyType({limit.id: limit.value for limit in response.limits}),
        )
