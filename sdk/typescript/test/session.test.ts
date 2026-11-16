import { Code, ConnectError } from "@connectrpc/connect";
import { create } from "@bufbuild/protobuf";
import { Deferred, Effect, Fiber, Stream } from "effect";
import { TestClock } from "effect/testing";
import { describe, expect, it } from "vitest";
import { rpcError } from "../src/errors.js";
import { BotEventSchema } from "../src/generated/soulfire/bot_live_pb.js";
import { BotSession, type BotEventStreamFactory } from "../src/session.js";

describe("observation scopes", () => {
  it("resumes after a stream failure and releases the reconnected subscription", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const requests: Parameters<BotEventStreamFactory>[0][] = [];
          let active = 0;
          const resumed = yield* Deferred.make<void>();
          const session = yield* BotSession.open((request) =>
            Stream.unwrap(
              Effect.acquireRelease(
                Effect.sync(() => {
                  requests.push(request);
                  active += 1;
                  return requests.length;
                }),
                () =>
                  Effect.sync(() => {
                    active -= 1;
                  }),
              ).pipe(
                Effect.map((attempt) => {
                  const event = create(BotEventSchema, {
                    envelope: {
                      sequence: BigInt(attempt),
                      streamEpoch: "epoch",
                      snapshotRevision: 1n,
                    },
                    event:
                      attempt === 1
                        ? {
                            case: "snapshot",
                            value: { health: 20, maxHealth: 20 },
                          }
                        : { case: "stateDelta", value: { health: 14 } },
                  });
                  return attempt === 1
                    ? Stream.concat(
                        Stream.make(event),
                        Stream.fail(
                          rpcError("watch", new ConnectError("disconnected", Code.Unavailable)),
                        ),
                      )
                    : Stream.make(event).pipe(
                        Stream.tap(() => Deferred.succeed(resumed, undefined)),
                        Stream.concat(Stream.never),
                      );
                }),
              ),
            ),
          );
          expect(session.state.player?.health).toBe(20);
          yield* TestClock.adjust(250);
          yield* Deferred.await(resumed);
          expect(requests).toHaveLength(2);
          expect(requests[1]?.afterSequence).toBe(1n);
          expect(requests[1]?.streamEpoch).toBe("epoch");
          expect(session.state.player?.health).toBe(14);
          yield* session.close();
          expect(active).toBe(0);
        }),
      ).pipe(Effect.provide(TestClock.layer())),
    ));

  it("resumes after the transport cancels a stream the session still uses", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let attempts = 0;
          const resumed = yield* Deferred.make<void>();
          const session = yield* BotSession.open(() =>
            Stream.suspend(() => {
              attempts += 1;
              const event = create(BotEventSchema, {
                envelope: { sequence: BigInt(attempts), streamEpoch: "epoch" },
              });
              return attempts === 1
                ? Stream.concat(
                    Stream.make(event),
                    Stream.fail(rpcError("watch", new ConnectError("aborted", Code.Canceled))),
                  )
                : Stream.make(event).pipe(
                    Stream.tap(() => Deferred.succeed(resumed, undefined)),
                    Stream.concat(Stream.never),
                  );
            }),
          );
          yield* TestClock.adjust(250);
          yield* Deferred.await(resumed);
          expect(attempts).toBe(2);
          expect(session.state.sequence).toBe(2n);
        }),
      ).pipe(Effect.provide(TestClock.layer())),
    ));

  it("closes a subscription interrupted before its first event", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let active = 0;
          const started = yield* Deferred.make<void>();
          const open = BotSession.open(() =>
            Stream.unwrap(
              Effect.acquireRelease(
                Effect.sync(() => {
                  active += 1;
                }),
                () =>
                  Effect.sync(() => {
                    active -= 1;
                  }),
              ).pipe(
                Effect.tap(() => Deferred.succeed(started, undefined)),
                Effect.as(Stream.never),
              ),
            ),
          );
          const fiber = yield* open.pipe(Effect.forkScoped);
          yield* Deferred.await(started);
          yield* Fiber.interrupt(fiber);
          expect(active).toBe(0);
        }),
      ),
    ));
  it("propagates terminal RPC failures to current and later subscribers", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const release = yield* Deferred.make<void>();
          const failure = rpcError("watch", new ConnectError("revoked", Code.PermissionDenied));
          const session = yield* BotSession.open(() =>
            Stream.concat(
              Stream.make(create(BotEventSchema, { envelope: { sequence: 1n } })),
              Stream.fromEffect(Deferred.await(release).pipe(Effect.andThen(Effect.fail(failure)))),
            ),
          );
          const waiter = yield* session.once("stateDelta").pipe(Effect.forkScoped);
          yield* Effect.yieldNow;
          yield* Deferred.succeed(release, undefined);
          expect(yield* Fiber.join(waiter).pipe(Effect.flip)).toBe(failure);
          expect(yield* session.once("stateDelta").pipe(Effect.flip)).toBe(failure);
        }),
      ),
    ));
});
