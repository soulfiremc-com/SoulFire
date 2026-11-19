import { Effect, Fiber, Stream } from "effect";
import { describe, expect, it } from "vitest";
import { rpc, rpcStream } from "../src/transport.js";

describe("RPC interruption", () => {
  it("aborts a pending unary call", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let markStarted!: () => void;
          const started = new Promise<void>((resolve) => {
            markStarted = resolve;
          });
          let aborted = false;
          const fiber = yield* rpc(
            "pending",
            (signal) =>
              new Promise<void>((_, reject) => {
                signal.addEventListener(
                  "abort",
                  () => {
                    aborted = true;
                    // oxlint-disable-next-line typescript/prefer-promise-reject-errors -- AbortSignal.reason is typed any; it holds the abort error
                    reject(signal.reason);
                  },
                  { once: true },
                );
                markStarted();
              }),
          ).pipe(Effect.forkScoped);
          yield* Effect.promise(() => started);
          yield* Fiber.interrupt(fiber);
          expect(aborted).toBe(true);
        }),
      ),
    ));

  it("aborts a pending stream read before closing its async iterator", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let markReading!: () => void;
          const reading = new Promise<void>((resolve) => {
            markReading = resolve;
          });
          const cleanup: string[] = [];
          const source = rpcStream("pending", (signal) =>
            (async function* () {
              try {
                yield 1;
                await new Promise<void>((_, reject) => {
                  signal.addEventListener(
                    "abort",
                    () => {
                      cleanup.push("abort");
                      // oxlint-disable-next-line typescript/prefer-promise-reject-errors -- AbortSignal.reason is typed any; it holds the abort error
                      reject(signal.reason);
                    },
                    { once: true },
                  );
                  markReading();
                });
              } finally {
                cleanup.push("return");
              }
            })(),
          );
          const fiber = yield* source.pipe(Stream.runDrain, Effect.forkScoped);
          yield* Effect.promise(() => reading);
          yield* Fiber.interrupt(fiber).pipe(Effect.timeout("1 second"));
          expect(cleanup).toEqual(["abort", "return"]);
        }),
      ),
    ));

  it("opens a fresh RPC for each stream consumer and closes early readers", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        let opened = 0;
        let closed = 0;
        const source = rpcStream("repeat", () =>
          (async function* () {
            opened += 1;
            try {
              yield opened;
              yield -1;
            } finally {
              closed += 1;
            }
          })(),
        );
        const result = yield* source.pipe(Stream.take(1), Stream.runCollect);
        const first = Array.from(result);
        const result1 = yield* source.pipe(Stream.take(1), Stream.runCollect);
        const second = Array.from(result1);
        expect(first).toEqual([1]);
        expect(second).toEqual([2]);
        expect(closed).toBe(2);
      }),
    ));
});
