import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, placeBot, rcon } from "../harness.ts";

// The beat-game bot pours water at its feet against an enderman: it selects
// the bucket, looks straight down, and uses it with tick retries. This checks
// that the water lands where the bot stands and drives off an enderman beside
// it.
export default {
  name: "water-against-enderman",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        // The test world is peaceful, where no monster can exist.
        const difficulty = (yield* rcon("difficulty")).match(/is (\w+)\s*$/u)?.[1]?.toLowerCase();
        yield* check(difficulty !== undefined, "The difficulty could not be read");
        yield* Effect.addFinalizer(() =>
          rcon(`difficulty ${difficulty}`, { check: false }).pipe(Effect.ignore),
        );
        yield* rcon("difficulty easy");
        yield* rcon("forceload add 4096 0 4127 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon("kill @e[type=minecraft:enderman,x=4096,y=60,z=0,dx=32,dy=20,dz=32]", {
              check: false,
            });
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 4096 63 0 4127 70 31 air", { check: false });
            yield* rcon("forceload remove 4096 0 4127 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4096 63 0 4127 70 31 air");
        yield* rcon("fill 4096 63 0 4127 63 31 end_stone");
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* placeBot(bot, { x: 4112.5, y: 64, z: 16.5 });
        yield* rcon(`clear ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with water_bucket`);
        yield* bot.selectHotbar(0);
        yield* rcon("summon minecraft:enderman 4113.5 64 16.5");
        const summoned = yield* rcon(
          "execute if entity @e[type=minecraft:enderman,x=4112,y=64,z=16,distance=..3]",
        );
        yield* check(summoned.includes("passed"), `No enderman stands beside the bot: ${summoned}`);

        yield* bot.look(0, 90);
        yield* bot.useItem({ hand: Hand.MAIN, retryTicks: 6 });
        yield* Effect.sleep("500 millis");

        const placed = yield* rcon("execute if block 4112 64 16 minecraft:water");
        yield* check(placed.includes("passed"), `No water at the bot's feet: ${placed}`);

        yield* Effect.sleep("3 seconds");
        const nearby = yield* rcon(
          "execute if entity @e[type=minecraft:enderman,x=4112,y=64,z=16,distance=..3]",
        );
        const health = yield* rcon(
          "data get entity @e[type=minecraft:enderman,limit=1,sort=nearest,x=4112,y=64,z=16] Health",
        );
        yield* check(
          !nearby.includes("passed") || !health.endsWith(": 40.0f"),
          `The enderman stayed beside the water unharmed: ${nearby} ${health}`,
        );
      }),
    ),
} satisfies E2ETest;
