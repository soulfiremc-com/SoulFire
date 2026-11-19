import { Deferred, Effect, Fiber, Stream } from "effect";
import { describe, expect, it } from "vitest";
import { ReplayBroadcast } from "../src/broadcast.js";

describe("replay broadcasts", () => {
  it("drains buffered events on completion and replays the tail to late readers", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const broadcast = new ReplayBroadcast<number>(2);
          const started = yield* Deferred.make<undefined>();
          const release = yield* Deferred.make<undefined>();
          yield* broadcast.publish(1);
          const reader = yield* broadcast.stream.pipe(
            Stream.tap(() =>
              Deferred.succeed(started, undefined).pipe(Effect.andThen(Deferred.await(release))),
            ),
            Stream.runCollect,
            Effect.forkScoped,
          );
          yield* Deferred.await(started);
          yield* broadcast.publish(2);
          yield* broadcast.publish(3);
          yield* broadcast.end();
          yield* broadcast.publish(4);
          yield* Deferred.succeed(release, undefined);

          const joined = yield* Fiber.join(reader);
          expect(joined).toEqual([1, 2, 3]);
          const collected = yield* Stream.runCollect(broadcast.stream);
          expect(collected).toEqual([2, 3]);
        }),
      ),
    ));

  it("completes without retaining history when replay is disabled", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const broadcast = new ReplayBroadcast<number>(0);
        yield* broadcast.publish(1);
        yield* broadcast.end();
        yield* broadcast.end();

        const collected1 = yield* Stream.runCollect(broadcast.stream);
        expect(collected1).toEqual([]);
      }),
    ));
});
