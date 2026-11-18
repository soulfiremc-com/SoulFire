import { BotRuntimeState } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
import { check, type E2ETest, eventually } from "../harness.ts";

export default {
  name: "game-thread-request-shutdown",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        let polling = true;
        let slowestRequestMs = 0;
        let requests = 0;
        const poll = async () => {
          while (polling) {
            const started = performance.now();
            // Reads may succeed or report that the connection is unavailable. Neither may hang.
            await Effect.runPromise(bot.world.player({ timeoutMs: 15_000 }).pipe(Effect.result));
            slowestRequestMs = Math.max(slowestRequestMs, performance.now() - started);
            requests++;
            await new Promise((resolve) => {
              setTimeout(resolve, 10);
            });
          }
        };
        const readers = [poll(), poll(), poll(), poll()];
        const finish = Effect.promise(async () => {
          polling = false;
          await Promise.all(readers);
        });
        yield* Effect.addFinalizer(() => finish);
        for (let cycle = 0; cycle < 6; cycle++) {
          const epoch = (yield* bot.world.player()).connectionEpoch;
          yield* bot.restart();
          yield* eventually(
            bot
              .status()
              .pipe(
                Effect.flatMap((status) =>
                  check(status.runtimeState === BotRuntimeState.RUNNING, "Bot has not restarted"),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((player) =>
                  check(player.connectionEpoch !== epoch, "Still observing the old game thread"),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
        }
        yield* finish;
        yield* check(requests > 0, "No concurrent world requests completed");
        yield* check(
          slowestRequestMs < 2000,
          `A world request waited ${Math.round(slowestRequestMs)} ms during shutdown`,
        );
      }),
    ),
} satisfies E2ETest;
