from collections.abc import AsyncIterator
from typing import Any, cast

from connectrpc.client import ConnectClient
from connectrpc.method import StreamType
from effect_py import gen, run_async, scoped, succeed
from google.protobuf import any_pb2, descriptor_pb2

from soulfire import ExamplePluginClient, PluginCatalog, ReflectivePlugin, example_plugin
from soulfire.plugin.example.v1 import example_pb2
from soulfire.plugin.example.v1.example_connect import ExamplePluginServiceClient
from soulfire.plugin.example.v1.example_pb2 import EchoResponse
from soulfire.plugin_api_connect import PluginApiServiceClient
from soulfire.plugin_api_pb2 import (
    PLUGIN_EVENT_KIND_DATA,
    PLUGIN_EVENT_KIND_READY,
    PluginApiDescriptor,
    PluginEvent,
    PluginEventTypeDescriptor,
    PluginRpcServiceDescriptor,
    WatchPluginEventsRequest,
)


class FakeExampleService:
    async def echo(self, request: object, **_: object) -> EchoResponse:
        return EchoResponse(message=request.message)


class FakeReflectiveClient:
    async def execute_unary(self, *, request: Any, method: Any, **_: Any) -> Any:
        assert method.stream_type is StreamType.UNARY
        return method.output(message=request.message)

    def execute_server_stream(self, *, request: Any, method: Any, **_: Any) -> AsyncIterator[Any]:
        assert method.stream_type is StreamType.SERVER_STREAM

        async def values() -> AsyncIterator[Any]:
            for sequence in range(1, request.count + 1):
                yield method.output(sequence=sequence)

        return values()


class FakePluginApiService:
    def __init__(self) -> None:
        self.request: WatchPluginEventsRequest | None = None

    async def watch_plugin_events(
        self, request: WatchPluginEventsRequest, **_: object
    ) -> AsyncIterator[PluginEvent]:
        self.request = request
        yield PluginEvent(sequence=41, kind=PLUGIN_EVENT_KIND_READY, resume_gap=True)
        payload = any_pb2.Any()
        payload.Pack(example_pb2.Tick(sequence=42))
        yield PluginEvent(
            sequence=42,
            kind=PLUGIN_EVENT_KIND_DATA,
            plugin_id="example",
            type_url=payload.type_url,
            payload=payload,
        )


async def test_companion_module_uses_the_catalog_transport() -> None:

    @gen
    def workflow():
        yield from succeed(None)
        service = FakeExampleService()

        def create_service(_: type[ExamplePluginServiceClient]) -> ExamplePluginServiceClient:
            return cast(ExamplePluginServiceClient, service)

        descriptor = PluginApiDescriptor(
            plugin_id="example",
            plugin_version="1.0.0",
            api_major_version=1,
            services=[
                PluginRpcServiceDescriptor(
                    name="ExamplePluginService",
                    full_name="soulfire.plugin.example.v1.ExamplePluginService",
                )
            ],
        )
        catalog = PluginCatalog(
            cast(PluginApiServiceClient, object()), create_service, (descriptor,)
        )
        client = catalog.require(example_plugin)
        assert isinstance(client, ExamplePluginClient)
        assert (yield from client.echo("instance-id", "hello")).message == "hello"

    await run_async(scoped(workflow).or_die())


async def test_reflective_plugin_validates_and_invokes_dynamic_messages() -> None:

    @gen
    def workflow():
        yield from succeed(None)
        descriptor = PluginApiDescriptor(
            plugin_id="example",
            plugin_version="1.0.0",
            api_major_version=1,
            services=[
                PluginRpcServiceDescriptor(
                    name="ExamplePluginService",
                    full_name="soulfire.plugin.example.v1.ExamplePluginService",
                )
            ],
        )
        plugin = ReflectivePlugin(
            descriptor,
            _descriptor_set(example_pb2.DESCRIPTOR),
            cast(ConnectClient, FakeReflectiveClient()),
        )
        response = yield from plugin.call(
            "soulfire.plugin.example.v1.ExamplePluginService",
            "Echo",
            {"instanceId": "instance-id", "message": "hello"},
        )
        ticks = [
            tick.json["sequence"]
            for tick in (
                yield from plugin.stream(
                    "soulfire.plugin.example.v1.ExamplePluginService",
                    "WatchTicks",
                    {"instanceId": "instance-id", "count": 3},
                ).run_collect()
            )
        ]
        assert response.type_name == "soulfire.plugin.example.v1.EchoResponse"
        assert response.json == {"message": "hello"}
        assert ticks == [1, 2, 3]

    await run_async(scoped(workflow).or_die())


async def test_plugin_catalog_decodes_typed_event_streams() -> None:

    @gen
    def workflow():
        yield from succeed(None)
        service = FakePluginApiService()
        type_url = "type.googleapis.com/soulfire.plugin.example.v1.Tick"
        descriptor = PluginApiDescriptor(
            plugin_id="example",
            event_type_urls=[type_url],
            event_types=[PluginEventTypeDescriptor(type_url=type_url)],
        )
        catalog = PluginCatalog(
            cast(PluginApiServiceClient, service), lambda client_type: client_type, (descriptor,)
        )
        events = list(
            (
                yield from catalog.typed_events(
                    "example", example_pb2.Tick, instance_id="instance-id", after_sequence=40
                ).run_collect()
            )
        )
        assert service.request is not None
        assert service.request.plugin_ids == ["example"]
        assert service.request.type_urls == [type_url]
        assert service.request.instance_id == "instance-id"
        assert service.request.after_sequence == 40
        assert events[0].event.resume_gap is True
        assert events[0].value is None
        assert events[1].value == example_pb2.Tick(sequence=42)

    await run_async(scoped(workflow).or_die())


async def test_reflective_plugin_decodes_unknown_event_payloads() -> None:

    @gen
    def workflow():
        yield from succeed(None)
        service = FakePluginApiService()
        plugin = ReflectivePlugin(
            PluginApiDescriptor(plugin_id="example"),
            _descriptor_set(example_pb2.DESCRIPTOR),
            cast(ConnectClient, FakeReflectiveClient()),
            cast(PluginApiServiceClient, service),
        )
        events = list((yield from plugin.events().run_collect()))
        assert events[1].message is not None
        assert events[1].message.type_name == "soulfire.plugin.example.v1.Tick"
        assert events[1].message.json == {"sequence": 42}

    await run_async(scoped(workflow).or_die())


def _descriptor_set(root: Any) -> bytes:
    files: dict[str, Any] = {}

    def collect(file: Any) -> None:
        if file.name in files:
            return
        files[file.name] = file
        for dependency in file.dependencies:
            collect(dependency)

    collect(root)
    descriptor_set = descriptor_pb2.FileDescriptorSet()
    for file in files.values():
        descriptor_set.file.add().ParseFromString(file.serialized_pb)
    return descriptor_set.SerializeToString()
