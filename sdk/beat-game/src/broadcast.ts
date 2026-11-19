import { type Cause, Effect, Queue, Semaphore, Stream } from "effect";

export class ReplayBroadcast<A> {
  private readonly replay: number;
  readonly #history: A[] = [];
  readonly #subscribers = new Set<Queue.Enqueue<A, Cause.Done>>();
  readonly #mutex = Semaphore.makeUnsafe(1);
  #ended = false;

  public constructor(replay: number) {
    this.replay = replay;
    if (!Number.isSafeInteger(replay) || replay < 0) {
      throw new RangeError("replay must be a non-negative safe integer");
    }
  }

  public readonly stream: Stream.Stream<A> = Stream.unwrap(
    Effect.gen({ self: this }, function* () {
      const queue = yield* Queue.unbounded<A, Cause.Done>();
      const ended = yield* this.#mutex.withPermits(1)(
        Effect.gen({ self: this }, function* () {
          const alreadyEnded = this.#ended;
          if (!alreadyEnded) {
            this.#subscribers.add(queue);
          }
          yield* Queue.offerAll(queue, this.#history);
          if (alreadyEnded) {
            yield* Queue.end(queue);
          }
          return alreadyEnded;
        }),
      );
      if (!ended) {
        yield* Effect.addFinalizer(() =>
          this.#mutex
            .withPermits(1)(
              Effect.sync(() => {
                this.#subscribers.delete(queue);
              }),
            )
            .pipe(Effect.andThen(Queue.shutdown(queue))),
        );
      }
      return Stream.fromQueue(queue);
    }),
  );

  public publish(value: A): Effect.Effect<void> {
    return this.#mutex.withPermits(1)(
      Effect.gen({ self: this }, function* () {
        if (this.#ended) {
          return;
        }
        if (this.replay > 0) {
          this.#history.push(value);
          if (this.#history.length > this.replay) {
            this.#history.splice(0, this.#history.length - this.replay);
          }
        }
        yield* Effect.forEach(this.#subscribers, (subscriber) => Queue.offer(subscriber, value), {
          discard: true,
          concurrency: 1,
        });
      }),
    );
  }

  public end(): Effect.Effect<void> {
    return this.#mutex.withPermits(1)(
      Effect.gen({ self: this }, function* () {
        if (this.#ended) {
          return;
        }
        this.#ended = true;
        yield* Effect.forEach(this.#subscribers, Queue.end, { discard: true, concurrency: 1 });
        this.#subscribers.clear();
      }),
    );
  }
}
