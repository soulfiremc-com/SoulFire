// An enderman attacks a player that looks it in the eyes. A bot standing with
// a level view meets the eyes of one a block lower, even 25 blocks away. The
// bot keeps the pitch it walked with once a route ends, so a bot that walks
// looking down then stands looking down, and the enderman stays calm.
import { goals } from "@soulfiremc/sdk";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, placeBot, rcon } from "../harness.ts";

const Y = 64;

const scene = (bot: Parameters<E2ETest["run"]>[0]["bot"], x: number) =>
  Effect.gen(function* () {
    yield* rcon(`fill ${x - 2} ${Y - 3} -3 ${x + 44} ${Y + 6} 3 minecraft:air`);
    // The bot's strip, then a strip one block lower where the enderman stands.
    yield* rcon(`fill ${x - 2} ${Y - 1} -3 ${x + 14} ${Y - 1} 3 minecraft:stone`);
    yield* rcon(`fill ${x + 15} ${Y - 2} -3 ${x + 44} ${Y - 2} 3 minecraft:stone`);
    yield* placeBot(bot, { x: x + 0.5, y: Y, z: 0.5 });
    // Facing away until the scene is ready.
    yield* bot.look(90, 0);
    yield* rcon(
      `summon minecraft:enderman ${x + 37.5} ${Y - 1} 0.5 {Tags:["pitch_probe"],active_effects:[{id:"minecraft:slowness",amplifier:10b,duration:2000}]}`,
    );
    yield* Effect.sleep("1 second");
  });

// Faces the enderman at the given pitch for three seconds and reports whether
// it turned hostile.
const faceEnderman = (bot: Parameters<E2ETest["run"]>[0]["bot"], pitch: number) =>
  Effect.gen(function* () {
    yield* bot.look(-90, pitch);
    yield* Effect.sleep("3 seconds");
    const anger = yield* rcon(
      "data get entity @e[type=minecraft:enderman,tag=pitch_probe,limit=1] anger_end_time",
    );
    yield* check(anger.includes("entity data"), `The enderman is gone: ${anger}`);
    yield* rcon("kill @e[type=minecraft:enderman,tag=pitch_probe]", { check: false });
    // Unset while calm.
    return !/:\s*-1L?\s*$/u.test(anger);
  });

export default {
  name: "walking-pitch-enderman",
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
        yield* rcon("forceload add 4224 -64 4415 64");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon("kill @e[type=minecraft:enderman,tag=pitch_probe]", { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 4228 61 -3 4405 70 3 air", { check: false });
            yield* rcon("forceload remove 4224 -64 4415 64", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon(`gamemode survival ${BOT_NAME}`);

        yield* scene(bot, 4230);
        const levelAngry = yield* faceEnderman(bot, 0);
        yield* check(levelAngry, "A level gaze did not anger the lower enderman ahead");

        yield* scene(bot, 4320);
        const route = yield* bot.pathfinder.goTo(goals.block({ x: 4332, y: Y, z: 0 }), {
          path: { timeoutSeconds: 30, walkingPitch: 30, sprint: false },
        });
        yield* route.result();
        const rest = (yield* bot.world.player()).rotation?.pitch;
        yield* check(rest === 30, `The bot did not keep its walking pitch at rest: ${rest}`);
        const downAngry = yield* faceEnderman(bot, rest ?? 0);
        yield* check(!downAngry, "The enderman turned on a bot looking down");
      }),
    ),
} satisfies E2ETest;
