import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const TAG = "sf_e2e_use_item_shield";
const REGEN = "natural_health_regeneration";

// A shield raised with useItem stays up until releaseItem, so it blocks a hit
// that lands a second later. A live bot raised its shield against a creeper,
// the shield dropped on the next tick, and the blast went through.
export default {
  name: "use-item-shield-hold",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const original = (yield* rcon(`gamerule ${REGEN}`)).match(/\b(true|false)\s*$/u)?.[1];
        yield* check(original !== undefined, "The regeneration rule could not be read");
        yield* Effect.addFinalizer(() => rcon(`gamerule ${REGEN} ${original}`).pipe(Effect.ignore));
        yield* rcon(`gamerule ${REGEN} false`);
        yield* rcon("forceload add 3648 0 3679 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.releaseItem().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`, { check: false });
            yield* rcon(`item replace entity ${BOT_NAME} weapon.offhand with air`, {
              check: false,
            });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3648 63 0 3679 66 31 air", { check: false });
            yield* rcon("forceload remove 3648 0 3679 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3648 63 0 3679 63 31 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        // Facing east, toward the attacker.
        yield* placeBot(bot, { x: 3664.5, y: 64, z: 16.5 }, -90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(`effect give ${BOT_NAME} instant_health 1 10 true`);
        yield* rcon(`item replace entity ${BOT_NAME} weapon.offhand with shield`);
        yield* rcon(`summon zombie 3667.5 64 16.5 {Tags:["${TAG}"],NoAI:1b,Silent:1b}`);
        yield* eventually(
          bot.world
            .player()
            .pipe(
              Effect.flatMap((p) =>
                check(
                  p.health === 20 && p.offHand?.itemId === "minecraft:shield",
                  "The health and shield fixture did not arrive",
                ),
              ),
            ),
          "5 seconds",
        );
        yield* rcon(`effect clear ${BOT_NAME} instant_health`, { check: false });

        yield* bot.useItem({ hand: Hand.OFF });
        // Long past the tick in which an unheld use ends.
        yield* Effect.sleep("1 second");
        const raised = yield* bot.world.player();
        yield* check(raised.usingItem, "The shield came down without a releaseItem");
        yield* rcon(`damage ${BOT_NAME} 6 minecraft:mob_attack by @e[tag=${TAG},limit=1]`);
        yield* Effect.sleep("500 millis");
        const serverHealth = yield* rcon(`data get entity ${BOT_NAME} Health`);
        yield* check(
          serverHealth.endsWith(": 20.0f"),
          `The raised shield did not block the hit: ${serverHealth}`,
        );

        yield* bot.releaseItem();
        yield* eventually(
          bot.world
            .player()
            .pipe(
              Effect.flatMap((p) => check(!p.usingItem, "The shield stayed up after releaseItem")),
            ),
          "2 seconds",
        );
      }),
    ),
} satisfies E2ETest;
