import { BotSession, type SoulFireBot } from "@soulfiremc/sdk";
import { Deferred, Effect, Fiber, Stream } from "effect";
import { expect, it } from "vitest";
import { beatGame } from "../src/index.js";

it("interrupts beat-game setup before the first SDK observation arrives", () =>
  Effect.runPromise(
    Effect.scoped(
      Effect.gen(function* () {
        const started = yield* Deferred.make<undefined>();
        let active = 0;
        // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- the fake implements only the bot members beatGame reads
        const bot = {
          id: "bot",
          instanceId: "instance",
          world: { player: () => Effect.never },
          inventory: { snapshot: () => Effect.never },
          events: () => Stream.never,
          observe: () =>
            BotSession.open(() =>
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
            ),
        } as unknown as SoulFireBot;
        const fiber = yield* beatGame(bot).pipe(Effect.forkScoped);
        yield* Deferred.await(started);
        yield* Fiber.interrupt(fiber);
        expect(active).toBe(0);
      }),
    ),
  ));
