import { Code, ConnectError } from "@connectrpc/connect";
import { Data, type Cause } from "effect";
import type { BotTask } from "./generated/soulfire/task_pb.js";
import { SoulFireActionError } from "./actions.js";
import { SoulFireCapabilityError, SoulFireCompatibilityError } from "./connection.js";

export class SoulFireTaskError extends Error {
  public constructor(public readonly task: BotTask) {
    super(task.failure?.message ?? `Task ${task.taskId} ended in status ${task.status}`);
    this.name = "SoulFireTaskError";
  }
}

type TaggedErrorConstructor<Tag extends string, Fields> = new (
  args: Fields,
) => Cause.YieldableError & {
  readonly _tag: Tag;
} & Readonly<Fields>;

interface SoulFireConnectionErrorFields {
  readonly cause: unknown;
}

const SoulFireConnectionErrorBase: TaggedErrorConstructor<
  "SoulFireConnectionError",
  SoulFireConnectionErrorFields
> = Data.TaggedError("SoulFireConnectionError")<SoulFireConnectionErrorFields>;

export class SoulFireConnectionError extends SoulFireConnectionErrorBase {}

interface SoulFireRpcErrorFields {
  readonly operation: string;
  readonly cause: unknown;
  readonly message: string;
  readonly code?: Code;
  readonly requestId?: string;
  readonly retryable: boolean;
}

const SoulFireRpcErrorBase: TaggedErrorConstructor<"SoulFireRpcError", SoulFireRpcErrorFields> =
  Data.TaggedError("SoulFireRpcError")<SoulFireRpcErrorFields>;

export class SoulFireRpcError extends SoulFireRpcErrorBase {}

interface SoulFireTaskFailedFields {
  readonly task: SoulFireTaskError["task"];
  readonly cause: SoulFireTaskError;
  readonly message: string;
}

const SoulFireTaskFailedBase: TaggedErrorConstructor<
  "SoulFireTaskFailed",
  SoulFireTaskFailedFields
> = Data.TaggedError("SoulFireTaskFailed")<SoulFireTaskFailedFields>;

export class SoulFireTaskFailed extends SoulFireTaskFailedBase {}

interface SoulFirePluginErrorFields {
  readonly pluginId: string;
  readonly cause: unknown;
}

const SoulFirePluginErrorBase: TaggedErrorConstructor<
  "SoulFirePluginError",
  SoulFirePluginErrorFields
> = Data.TaggedError("SoulFirePluginError")<SoulFirePluginErrorFields>;

export class SoulFirePluginError extends SoulFirePluginErrorBase {}

interface LocalErrorFields {
  readonly operation: string;
  readonly message: string;
  readonly cause?: unknown;
}

const SoulFireValidationErrorBase: TaggedErrorConstructor<
  "SoulFireValidationError",
  LocalErrorFields
> = Data.TaggedError("SoulFireValidationError")<LocalErrorFields>;

export class SoulFireValidationError extends SoulFireValidationErrorBase {}

const SoulFireStateErrorBase: TaggedErrorConstructor<"SoulFireStateError", LocalErrorFields> =
  Data.TaggedError("SoulFireStateError")<LocalErrorFields>;

export class SoulFireStateError extends SoulFireStateErrorBase {}

const SoulFireInstallErrorBase: TaggedErrorConstructor<"SoulFireInstallError", LocalErrorFields> =
  Data.TaggedError("SoulFireInstallError")<LocalErrorFields>;

export class SoulFireInstallError extends SoulFireInstallErrorBase {}

const SoulFireTimeoutErrorBase: TaggedErrorConstructor<"SoulFireTimeoutError", LocalErrorFields> =
  Data.TaggedError("SoulFireTimeoutError")<LocalErrorFields>;

export class SoulFireTimeoutError extends SoulFireTimeoutErrorBase {}

export type SoulFireOperationError =
  | SoulFireRpcError
  | SoulFireTaskFailed
  | SoulFireActionError
  | SoulFireCapabilityError
  | SoulFireCompatibilityError
  | SoulFireValidationError
  | SoulFireStateError
  | SoulFireInstallError
  | SoulFireTimeoutError;

export function operationError(operation: string, cause: unknown): SoulFireOperationError {
  if (
    cause instanceof SoulFireRpcError ||
    cause instanceof SoulFireTaskFailed ||
    cause instanceof SoulFireActionError ||
    cause instanceof SoulFireCapabilityError ||
    cause instanceof SoulFireCompatibilityError ||
    cause instanceof SoulFireValidationError ||
    cause instanceof SoulFireStateError ||
    cause instanceof SoulFireInstallError ||
    cause instanceof SoulFireTimeoutError
  )
    return cause;
  if (cause instanceof SoulFireTaskError)
    return new SoulFireTaskFailed({
      task: cause.task,
      cause,
      message: cause.message,
    });
  if (cause instanceof ConnectError) return rpcError(operation, cause);
  const fields = { operation, cause, message: errorMessage(cause, operation) };
  if (operation.startsWith("install.")) return new SoulFireInstallError(fields);
  if (cause instanceof TypeError || cause instanceof RangeError)
    return new SoulFireValidationError(fields);
  return new SoulFireStateError(fields);
}

export function rpcError(operation: string, cause: unknown): SoulFireRpcError {
  if (!(cause instanceof ConnectError)) {
    return new SoulFireRpcError({
      operation,
      cause,
      message: errorMessage(cause, operation),
      retryable: false,
    });
  }
  const requestId =
    cause.metadata.get("x-soulfire-request-id") ?? cause.metadata.get("x-request-id") ?? undefined;
  return new SoulFireRpcError({
    operation,
    cause,
    message: cause.message,
    code: cause.code,
    retryable: isRetryableCode(cause.code),
    ...(requestId === undefined ? {} : { requestId }),
  });
}

function errorMessage(cause: unknown, operation: string): string {
  return cause instanceof Error && cause.message.length > 0
    ? cause.message
    : `Soulfire RPC ${operation} failed`;
}

function isRetryableCode(code: Code): boolean {
  return (
    code === Code.Aborted ||
    code === Code.DeadlineExceeded ||
    code === Code.ResourceExhausted ||
    code === Code.Unavailable
  );
}
