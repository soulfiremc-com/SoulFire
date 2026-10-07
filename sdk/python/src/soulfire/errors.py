from __future__ import annotations

from dataclasses import dataclass

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from effect_py.errors import TaggedError

from .bot_live_pb2 import BotActionResult
from .task_pb2 import BotTask

_RETRYABLE_CODES = frozenset(
    {Code.ABORTED, Code.DEADLINE_EXCEEDED, Code.RESOURCE_EXHAUSTED, Code.UNAVAILABLE}
)


@dataclass(frozen=True, slots=True)
class RpcFailureContext:
    operation: str
    code: Code
    request_id: str | None
    retryable: bool


class SoulFireRpcError(TaggedError):
    """An RPC request failed with server status and retry context."""

    def __init__(self, context: RpcFailureContext, cause: ConnectError) -> None:
        self.context = context
        self.operation = context.operation
        self.code = context.code
        self.request_id = context.request_id
        self.retryable = context.retryable
        self.cause = cause
        super().__init__(cause.message)


class RpcErrorInterceptor:
    async def on_start[RequestT, ResponseT](
        self, _ctx: RequestContext[RequestT, ResponseT]
    ) -> None:
        return None

    async def on_end[RequestT, ResponseT](
        self, _token: None, ctx: RequestContext[RequestT, ResponseT], error: Exception | None
    ) -> None:
        if isinstance(error, ConnectError):
            raise _rpc_error(ctx, error) from error


class RpcErrorInterceptorSync:
    def on_start_sync[RequestT, ResponseT](self, _ctx: RequestContext[RequestT, ResponseT]) -> None:
        return None

    def on_end_sync[RequestT, ResponseT](
        self, _token: None, ctx: RequestContext[RequestT, ResponseT], error: Exception | None
    ) -> None:
        if isinstance(error, ConnectError):
            raise _rpc_error(ctx, error) from error


def _rpc_error[RequestT, ResponseT](
    ctx: RequestContext[RequestT, ResponseT], cause: ConnectError
) -> SoulFireRpcError:
    request_id = (
        ctx.response_trailers.get("x-soulfire-request-id")
        or ctx.response_trailers.get("x-request-id")
        or ctx.response_headers.get("x-soulfire-request-id")
        or ctx.response_headers.get("x-request-id")
    )
    return SoulFireRpcError(
        RpcFailureContext(
            operation=f"{ctx.method.service_name}/{ctx.method.name}",
            code=cause.code,
            request_id=request_id,
            retryable=cause.code in _RETRYABLE_CODES,
        ),
        cause,
    )


class SoulFireActionError(TaggedError):
    """A bot action returned an unsuccessful result."""

    def __init__(self, result: BotActionResult) -> None:
        self.result = result
        super().__init__(result.error or f"Bot action {result.action_id} did not complete")


class SoulFireCompatibilityError(TaggedError):
    """The server and SDK cannot safely communicate."""


class SoulFireCapabilityError(TaggedError):
    """The server does not provide a required SDK capability."""

    def __init__(self, capability: str) -> None:
        super().__init__(f"SoulFire capability is unavailable: {capability}")
        self.capability = capability


class SoulFireTaskError(TaggedError):
    """A bot task ended with a failure or an unsuccessful status."""

    def __init__(self, task: BotTask) -> None:
        self.task = task
        message = task.failure.message if task.HasField("failure") else ""
        super().__init__(message or f"Task {task.task_id} ended in status {task.status}")


class SoulFirePluginNotFoundError(TaggedError):
    """A required plugin is not installed on the server."""

    def __init__(self, plugin_id: str) -> None:
        super().__init__(f"SoulFire plugin is not installed: {plugin_id}")
        self.plugin_id = plugin_id


class SoulFirePluginCompatibilityError(TaggedError):
    """An installed plugin is incompatible with the SDK requirements."""

    def __init__(self, plugin_id: str, message: str) -> None:
        super().__init__(message)
        self.plugin_id = plugin_id


class SoulFirePluginDescriptorError(TaggedError):
    """The SDK could not use a plugin's API descriptor."""

    def __init__(self, plugin_id: str, message: str) -> None:
        super().__init__(message)
        self.plugin_id = plugin_id


class SoulFireContainerClosedError(TaggedError):
    """An inventory operation attempted to use a closed container."""

    def __init__(self, container_id: int) -> None:
        self.container_id = container_id
        super().__init__(f"Container {container_id} is already closed")


class SoulFireValidationError(TaggedError):
    """An SDK operation received an invalid value or argument type."""


class SoulFireStateError(TaggedError):
    """An SDK operation cannot proceed in the current state."""


class SoulFireInstallError(TaggedError):
    """The SDK could not install or start a managed SoulFire server."""


class SoulFireTimeoutError(TaggedError):
    """An SDK operation exceeded its allowed duration."""


type SoulFireOperationError = (
    SoulFireRpcError
    | SoulFireValidationError
    | SoulFireStateError
    | SoulFireInstallError
    | SoulFireTimeoutError
    | SoulFireActionError
    | SoulFireTaskError
    | SoulFireCompatibilityError
    | SoulFireCapabilityError
    | SoulFirePluginNotFoundError
    | SoulFirePluginCompatibilityError
    | SoulFirePluginDescriptorError
    | SoulFireContainerClosedError
)


def operation_error(operation: str, error: Exception) -> SoulFireOperationError:
    if isinstance(
        error,
        (
            SoulFireRpcError,
            SoulFireValidationError,
            SoulFireStateError,
            SoulFireInstallError,
            SoulFireTimeoutError,
            SoulFireActionError,
            SoulFireTaskError,
            SoulFireCompatibilityError,
            SoulFireCapabilityError,
            SoulFirePluginNotFoundError,
            SoulFirePluginCompatibilityError,
            SoulFirePluginDescriptorError,
            SoulFireContainerClosedError,
        ),
    ):
        return error
    if isinstance(error, ConnectError):
        return rpc_error(operation, error)
    if isinstance(error, (ValueError, TypeError)):
        return SoulFireValidationError(str(error))
    if isinstance(error, RuntimeError):
        return SoulFireStateError(str(error))
    raise error


def rpc_error(operation: str, error: Exception) -> SoulFireRpcError:
    if isinstance(error, SoulFireRpcError):
        return error
    if not isinstance(error, ConnectError):
        raise error
    return SoulFireRpcError(
        RpcFailureContext(operation, error.code, None, error.code in _RETRYABLE_CODES), error
    )
