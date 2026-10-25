import { SleepCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

export default {
  name: "sleep-bed-discovery",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            // Disconnect also wakes the player if this run happened at night.
            yield* bot.stop();
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds", "100 millis");
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon("fill 4260 63 4 4278 64 12 air");
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon("fill 4260 63 4 4278 63 12 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
        const missing = yield* bot.tasks.sleep({ searchRadius: 24 });
        yield* Effect.addFinalizer(() => missing.cancel("test cleanup").pipe(Effect.ignore));
        const noBed = yield* missing.result({ call: { timeoutMs: 5000 } });
        yield* check(
          noBed.reason === SleepCompletionReason.NO_BED_FOUND,
          "The empty search found a bed",
        );
        const waiting = yield* bot.tasks.sleep({
          searchRadius: 24,
          waitUntilPossible: true,
          retryIntervalTicks: 100,
        });
        yield* Effect.addFinalizer(() => waiting.cancel("test cleanup").pipe(Effect.ignore));
        yield* eventually(
          waiting
            .refresh()
            .pipe(
              Effect.tap((task) =>
                check((task.progress?.current ?? 0n) > 0n, "The first empty search has not run"),
              ),
            ),
          "5 seconds",
          "50 millis",
        );
        yield* Effect.sleep("300 millis");
        const before = yield* serverPosition;
        yield* check(Math.abs(before.x - 4262.5) < 0.1, "The waiting bot moved without a bed");
        for (const [color, foot] of [
          ["blue", 4277],
          ["red", 4271],
        ] as const) {
          yield* rcon(`setblock ${foot} 64 8 ${color}_bed[part=foot,facing=east]`);
          yield* rcon(`setblock ${foot + 1} 64 8 ${color}_bed[part=head,facing=east]`);
        }
        for (const x of [4271, 4272, 4277, 4278]) {
          yield* eventually(
            bot.world
              .block({ position: { x, y: 64, z: 8 } })
              .pipe(
                Effect.tap(({ block }) =>
                  check(block?.blockId.endsWith("_bed") ?? false, "Bed unseen"),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
        }
        // The red bed crosses a chunk boundary and is nearer than the blue bed.
        yield* eventually(
          serverPosition.pipe(
            Effect.tap((position) =>
              check(
                position.x >= 4268.5 && position.x < 4274 && Math.abs(position.z - 8.5) < 1,
                "The bot did not discover and approach the nearer bed",
              ),
            ),
          ),
          "10 seconds",
          "50 millis",
        );
        yield* waiting.cancel("discovery verified");
        yield* waiting.wait({ call: { timeoutMs: 5000 } });
      }),
    ),
} satisfies E2ETest;
