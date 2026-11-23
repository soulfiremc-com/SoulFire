import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, placeBot, rcon } from "../harness.ts";

const REGEN = "natural_health_regeneration";

// A water bucket over a fall only places once the ground is in reach, which
// lasts about one tick at full speed. A use sent early with retry ticks keeps
// trying on the server until it lands, so the bot takes no fall damage.
export default {
  name: "use-item-retry-clutch",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const original = (yield* rcon(`gamerule ${REGEN}`)).match(/\b(true|false)\s*$/u)?.[1];
        yield* check(original !== undefined, "The regeneration rule could not be read");
        yield* Effect.addFinalizer(() => rcon(`gamerule ${REGEN} ${original}`).pipe(Effect.ignore));
        yield* rcon(`gamerule ${REGEN} false`);
        yield* rcon("forceload add 3904 0 3935 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`, { check: false });
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3904 63 0 3935 82 31 air", { check: false });
            yield* rcon("forceload remove 3904 0 3935 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3904 63 0 3935 82 31 air");
        yield* rcon("fill 3904 63 0 3935 63 31 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 3920.5, y: 64, z: 16.5 });
        yield* rcon(`clear ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with water_bucket`);
        yield* bot.selectHotbar(0);
        yield* bot.look(0, 90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`);
        yield* Effect.sleep("500 millis");
        yield* rcon(`effect clear ${BOT_NAME} instant_health`, { check: false });
        yield* rcon(`tp ${BOT_NAME} 3920.5 80 16.5 0 90`);

        // Ten blocks up, the ground is far out of the bucket's reach.
        for (let tick = 0; tick < 60; tick += 1) {
          const player = yield* bot.world.player();
          if ((player.position?.y ?? 80) <= 74) {
            break;
          }
          yield* Effect.sleep("25 millis");
        }
        yield* bot.useItem({ hand: Hand.MAIN, retryTicks: 40 }).pipe(Effect.ignore);
        yield* Effect.sleep("2 seconds");

        const health = yield* rcon(`data get entity ${BOT_NAME} Health`);
        yield* check(health.endsWith(": 20.0f"), `The bot took fall damage: ${health}`);
      }),
    ),
} satisfies E2ETest;
