import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.method import IdempotencyLevel, MethodInfo, StreamType
from connectrpc.request import Headers, RequestContext

from soulfire.errors import (
    RpcErrorInterceptor,
    RpcErrorInterceptorSync,
    SoulFireRpcError,
)


def request_context() -> RequestContext[object, object]:
    context = RequestContext(
        method=MethodInfo(
            name="SendChat",
            service_name="soulfire.v1.BotLiveService",
            input=object,
            output=object,
            idempotency_level=IdempotencyLevel.UNKNOWN,
            stream_type=StreamType.UNARY,
        ),
        http_method="POST",
        request_headers=Headers(),
    )
    context.response_headers["x-request-id"] = "request-42"
    return context


@pytest.mark.asyncio
async def test_async_interceptor_normalizes_connect_errors() -> None:
    with pytest.raises(SoulFireRpcError) as raised:
        await RpcErrorInterceptor().on_end(
            None,
            request_context(),
            ConnectError(Code.UNAVAILABLE, "temporarily unavailable"),
        )

    assert raised.value.operation == "soulfire.v1.BotLiveService/SendChat"
    assert raised.value.code is Code.UNAVAILABLE
    assert raised.value.request_id == "request-42"
    assert raised.value.retryable


def test_sync_interceptor_normalizes_connect_errors() -> None:
    with pytest.raises(SoulFireRpcError) as raised:
        RpcErrorInterceptorSync().on_end_sync(
            None,
            request_context(),
            ConnectError(Code.PERMISSION_DENIED, "not allowed"),
        )

    assert raised.value.code is Code.PERMISSION_DENIED
    assert not raised.value.retryable
