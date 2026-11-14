from collections.abc import AsyncIterator, Iterator
from typing import Any

from connectrpc.method import StreamType

from soulfire.plugin_api_connect import PluginApiServiceClient, PluginApiServiceClientSync
from soulfire.plugin_api_pb2 import (
    ListPluginApisRequest,
    ListPluginApisResponse,
    PluginEvent,
    WatchPluginEventsRequest,
)


class AsyncTransportProbe(PluginApiServiceClient):
    def __init__(self) -> None:
        pass

    async def execute_unary(self, *, request: Any, method: Any, **_: Any) -> Any:
        assert isinstance(request, ListPluginApisRequest)
        assert method.stream_type is StreamType.UNARY
        return ListPluginApisResponse()

    def execute_server_stream(
        self, *, request: Any, method: Any, **_: Any
    ) -> AsyncIterator[PluginEvent]:
        assert isinstance(request, WatchPluginEventsRequest)
        assert method.stream_type is StreamType.SERVER_STREAM

        async def events() -> AsyncIterator[PluginEvent]:
            yield PluginEvent(plugin_id="example")

        return events()


class SyncTransportProbe(PluginApiServiceClientSync):
    def __init__(self) -> None:
        pass

    def execute_unary(self, *, request: Any, method: Any, **_: Any) -> Any:
        assert isinstance(request, ListPluginApisRequest)
        assert method.stream_type is StreamType.UNARY
        return ListPluginApisResponse()

    def execute_server_stream(
        self, *, request: Any, method: Any, **_: Any
    ) -> Iterator[PluginEvent]:
        assert isinstance(request, WatchPluginEventsRequest)
        assert method.stream_type is StreamType.SERVER_STREAM
        return iter([PluginEvent(plugin_id="example")])


async def test_generated_async_methods_reach_the_transport_with_stream_metadata() -> None:
    client = AsyncTransportProbe()
    assert isinstance(
        await client.list_plugin_apis(ListPluginApisRequest()), ListPluginApisResponse
    )
    assert [
        event.plugin_id async for event in client.watch_plugin_events(WatchPluginEventsRequest())
    ] == ["example"]


def test_generated_sync_methods_reach_the_transport_with_stream_metadata() -> None:
    client = SyncTransportProbe()
    assert isinstance(client.list_plugin_apis(ListPluginApisRequest()), ListPluginApisResponse)
    assert [
        event.plugin_id for event in client.watch_plugin_events(WatchPluginEventsRequest())
    ] == ["example"]
