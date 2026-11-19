import { Context, Deferred, Effect, Exit, Schema } from "effect";
import { describe, expect, expectTypeOf, it } from "vitest";
import {
  cleanup,
  defineBehavior,
  race,
  retry,
  sequence,
  until,
  SoulFireBehaviorError,
} from "../src/behaviors.js";
import type { SoulFireBot } from "../src/index.js";
// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- these combinator tests never call methods on the bot
const effectBot = {} as SoulFireBot;
describe("Effect behavior combinators", () => {
  it("composes typed results, retries failures, and races for first success", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let attempts = 0;
          const first = defineBehavior(() => Effect.succeed(1));
          const second = defineBehavior(() => Effect.succeed("two"));
          const unstable = defineBehavior(() =>
            Effect.suspend(() => {
              attempts += 1;
              return attempts < 3
                ? Effect.fail(
                    new SoulFireBehaviorError({
                      behavior: "unstable",
                      message: "transient",
                    }),
                  )
                : Effect.succeed(attempts);
            }),
          );
          const never = defineBehavior<number>(() => Effect.never);
          const result = yield* sequence(first, second).run(effectBot);
          expect(result).toEqual([1, "two"]);
          const retried = yield* retry(unstable, { attempts: 3 }).run(effectBot);
          expect(retried).toBe(3);
          const raceWinner = yield* race(never, first).run(effectBot);
          expect(raceWinner).toBe(1);
        }),
      ),
    ));

  it("runs cleanup when the primary behavior fails", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let cleaned = false;
          const failing = defineBehavior(() =>
            Effect.fail(
              new SoulFireBehaviorError({
                behavior: "primary",
                message: "failed",
              }),
            ),
          );
          const finalizer = defineBehavior(() =>
            Effect.sync(() => {
              cleaned = true;
            }),
          );
          const exit = yield* Effect.exit(cleanup(failing, finalizer).run(effectBot));
          expect(Exit.isFailure(exit)).toBe(true);
          expect(cleaned).toBe(true);
        }),
      ),
    ));
  it("bounds effectful predicates and resets each workflow execution", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        let value = 0;
        const action = defineBehavior(() => Effect.sync(() => ++value));
        const workflow = until(action, (result) => Effect.succeed(result % 2 === 0), {
          maximumIterations: 2,
        });
        const firstRun = yield* workflow.run(effectBot);
        expect(firstRun).toBe(2);
        const secondRun = yield* workflow.run(effectBot);
        expect(secondRun).toBe(4);
        const failure = yield* Effect.flip(
          until(action, () => false, { maximumIterations: 2 }).run(effectBot),
        );
        expect(failure).toBeInstanceOf(SoulFireBehaviorError);
        expect(value).toBe(6);
      }),
    ));

  it("releases the losing behavior when a race succeeds", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const started = yield* Deferred.make<undefined>();
        let released = false;
        const loser = defineBehavior(() =>
          Effect.acquireUseRelease(
            Deferred.succeed(started, undefined),
            () => Effect.never,
            () =>
              Effect.sync(() => {
                released = true;
              }),
          ),
        );
        const winner = defineBehavior(() => Deferred.await(started).pipe(Effect.as(1)));
        const result = yield* race(loser, winner).run(effectBot);
        expect(result).toBe(1);
        expect(released).toBe(true);
      }),
    ));
});

class Counter extends Context.Service<Counter, { readonly value: number }>()(
  "@soulfiremc/sdk/test/behaviors.test/Counter",
) {}
// oxlint-disable-next-line unicorn/throw-new-error -- Schema.TaggedError is a class factory.
class CustomFailure extends Schema.TaggedError<CustomFailure, { readonly brand: unique symbol }>()(
  "CustomFailure",
  {},
) {}

it("preserves custom behavior errors and required services through composition", () => {
  const dependent = defineBehavior(() =>
    Effect.gen(function* () {
      const counter = yield* Counter;
      if (counter.value < 0) return yield* new CustomFailure();
      return counter.value;
    }),
  );
  const composed = sequence(
    dependent,
    defineBehavior(() => Effect.succeed("done")),
  );
  expectTypeOf(composed.run(effectBot)).toEqualTypeOf<
    Effect.Effect<readonly [number, string], CustomFailure, Counter>
  >();
  return Effect.runPromise(
    composed.run(effectBot).pipe(
      Effect.provideService(Counter, { value: 4 }),
      Effect.tap((result) =>
        Effect.sync(() => {
          expect(result).toEqual([4, "done"]);
        }),
      ),
    ),
  );
});
