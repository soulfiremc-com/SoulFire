from __future__ import annotations

from collections.abc import Iterable

from effect_py import EffectGen, fn

from .errors import SoulFireOperationError
from .protocol_connect import BotProtocolServiceClient
from .protocol_pb2 import (
    BotProtocolInfo,
    BotProtocolRequest,
    ListPacketSchemasRequest,
    PacketDirection,
    PacketSchema,
    RawPacketEvent,
    SendRawPacketRequest,
    SendRawPacketResponse,
    WatchPacketsRequest,
)
from .streams import Stream
from .transport import rpc, rpc_stream


class SoulFireProtocol:
    """Advanced access to Soulfire's native Minecraft packet codec."""

    def __init__(self, instance_id: str, bot_id: str, client: BotProtocolServiceClient) -> None:
        self._instance_id = instance_id
        self._bot_id = bot_id
        self._client = client

    @fn("SoulFireProtocol.info")
    def info(
        self, *, timeout_ms: int | None = None
    ) -> EffectGen[BotProtocolInfo, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireProtocol.info",
                lambda: self._client.get_protocol_info(
                    BotProtocolRequest(instance_id=self._instance_id, bot_id=self._bot_id),
                    timeout_ms=timeout_ms,
                ),
            )
        )

    @fn("SoulFireProtocol.schemas")
    def schemas(
        self, direction: PacketDirection, *, timeout_ms: int | None = None
    ) -> EffectGen[list[PacketSchema], SoulFireOperationError]:
        response = yield from rpc(
            "SoulFireProtocol.schemas",
            lambda: self._client.list_packet_schemas(
                ListPacketSchemasRequest(
                    instance_id=self._instance_id, bot_id=self._bot_id, direction=direction
                ),
                timeout_ms=timeout_ms,
            ),
        )
        return list(response.packets)

    def packets(
        self,
        *,
        directions: Iterable[PacketDirection] = (),
        names: Iterable[str] = (),
        include_encoded_packet: bool = False,
        maximum_encoded_bytes: int = 0,
        timeout_ms: int | None = None,
    ) -> Stream[RawPacketEvent, SoulFireOperationError]:
        return rpc_stream(
            "SoulFireProtocol.packets",
            lambda: self._client.watch_packets(
                WatchPacketsRequest(
                    instance_id=self._instance_id,
                    bot_id=self._bot_id,
                    directions=directions,
                    names=names,
                    include_encoded_packet=include_encoded_packet,
                    maximum_encoded_bytes=maximum_encoded_bytes,
                ),
                timeout_ms=timeout_ms,
            ),
        )

    @fn("SoulFireProtocol.send")
    def send(
        self,
        encoded_packet: bytes,
        *,
        expected_name: str | None = None,
        timeout_ms: int | None = None,
    ) -> EffectGen[SendRawPacketResponse, SoulFireOperationError]:
        return (
            yield from rpc(
                "SoulFireProtocol.send",
                lambda: self._client.send_raw_packet(
                    SendRawPacketRequest(
                        instance_id=self._instance_id,
                        bot_id=self._bot_id,
                        encoded_packet=encoded_packet,
                        **{} if expected_name is None else {"expected_name": expected_name},
                    ),
                    timeout_ms=timeout_ms,
                ),
            )
        )
