import type { CallOptions } from "@connectrpc/connect";
import { Cause, Effect, Stream } from "effect";

import { rpcError, type SoulFireRpcError } from "./errors.js";

/** Converts a ConnectRPC unary call at the transport boundary. */
export function rpc<A>(
  operation: string,
  call: (signal: AbortSignal) => Promise<A>,
): Effect.Effect<A, SoulFireRpcError> {
  return Effect.tryPromise({
    try: call,
    catch: (cause) => rpcError(operation, cause),
  }).pipe(Effect.withSpan(operation));
}

/** Acquires a fresh transport stream for each subscription. */
export function rpcStream<A>(
  operation: string,
  call: (signal: AbortSignal) => AsyncIterable<A>,
): Stream.Stream<A, SoulFireRpcError> {
  return Stream.unwrap(
    Effect.acquireRelease(
      Effect.try({
        try: () => {
          const controller = new AbortController();
          const iterator = call(controller.signal)[Symbol.asyncIterator]();
          return { controller, iterator };
        },
        catch: (cause) => rpcError(operation, cause),
      }),
      ({ controller, iterator }) =>
        Effect.gen(function* () {
          // Abort pending next() before awaiting an async generator's return().
          controller.abort();
          if (iterator.return !== undefined) {
            yield* Effect.tryPromise({
              try: () => iterator.return!(),
              catch: (cause) => rpcError(operation, cause),
            }).pipe(Effect.ignore);
          }
        }),
    ).pipe(
      Effect.map(({ iterator }) =>
        Stream.fromEffectRepeat(
          Effect.tryPromise({
            try: () => iterator.next(),
            catch: (cause) => rpcError(operation, cause),
          }).pipe(
            Effect.flatMap((result) => (result.done ? Cause.done() : Effect.succeed(result.value))),
          ),
        ),
      ),
    ),
  );
}

export function withSignal(options: CallOptions | undefined, signal: AbortSignal): CallOptions {
  return {
    ...options,
    signal: options?.signal === undefined ? signal : AbortSignal.any([options.signal, signal]),
  };
}

/**
 * Call options for a stream that stays open for as long as its consumer runs.
 * The client's default timeout bounds requests, so it must not end such a
 * stream unless the caller sets its own timeout.
 */
export function withWatchSignal(
  options: CallOptions | undefined,
  signal: AbortSignal,
): CallOptions {
  return withSignal({ timeoutMs: 0, ...options }, signal);
}
