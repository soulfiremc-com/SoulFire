// Two adult cows in a small pen, and wheat in the bot's inventory: the breed task feeds both, and
// a calf is born. On the client an animal's age is only -1 (baby) or 1 (adult); the real age stays
// on the server. Before #1019 the task looked for age 0, found no pair, and this test failed.
import { BreedCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

/** The pen's centre, where the bot stands (feet). */
const CENTER = { x: 200, y: 64, z: 0 };
/** Blocks from the centre to the fence. */
const HALF = 3;
/** Marks the two adults, so the calf is the cow without it. */
const TAG = "e2e_breed_parent";
const CALF_TIMEOUT = "30 seconds";

export default {
  name: "breed-cows",
  run: ({ bot }) => {
    const { x, y, z } = CENTER;
    const pen = `x=${x - HALF},y=${y - 1},z=${z - HALF},dx=${2 * HALF},dy=3,dz=${2 * HALF}`;
    const removeCows = rcon(`kill @e[type=minecraft:cow,${pen}]`, { check: false });
    const cleanUp = Effect.andThen(removeCows, rcon(`clear ${BOT_NAME}`, { check: false })).pipe(
      Effect.ignore,
    );

    return Effect.gen(function* () {
      yield* rcon(`forceload add ${x - 16} ${z - 16} ${x + 16} ${z + 16}`);
      yield* removeCows;
      yield* rcon(
        `fill ${x - HALF - 1} ${y - 2} ${z - HALF - 1} ${x + HALF + 1} ${y + 3} ${z + HALF + 1} minecraft:air`,
      );
      yield* rcon(
        `fill ${x - HALF} ${y - 1} ${z - HALF} ${x + HALF} ${y - 1} ${z + HALF} minecraft:grass_block`,
      );
      yield* rcon(
        `fill ${x - HALF} ${y} ${z - HALF} ${x + HALF} ${y} ${z + HALF} minecraft:oak_fence`,
      );
      yield* rcon(
        `fill ${x - HALF + 1} ${y} ${z - HALF + 1} ${x + HALF - 1} ${y} ${z + HALF - 1} minecraft:air`,
      );
      yield* placeBot(bot, { x: x + 0.5, y, z: z + 0.5 });

      yield* rcon(`summon minecraft:cow ${x - 1.5} ${y} ${z + 0.5} {Tags:["${TAG}"]}`);
      yield* rcon(`summon minecraft:cow ${x + 2.5} ${y} ${z + 0.5} {Tags:["${TAG}"]}`);
      yield* rcon(`give ${BOT_NAME} minecraft:wheat 8`);
      yield* eventually(
        Effect.flatMap(bot.inventory.count({ selector: { itemIds: ["minecraft:wheat"] } }), (n) =>
          check(n === 8n, "the bot never had the wheat"),
        ),
        "10 seconds",
      );
      yield* eventually(
        Effect.flatMap(
          bot.world.queryEntities({ radius: 16, selector: { entityTypes: ["minecraft:cow"] } }),
          ({ entities }) => check(entities.length === 2, "Soulfire never saw both cows"),
        ),
        "10 seconds",
      );

      const task = yield* bot.tasks.breed({
        animals: { entityTypes: ["minecraft:cow"] },
        maximumPairs: 1,
      });
      const result = yield* task.result();
      yield* check(
        result.pairsStarted === 1,
        `the breed task started ${result.pairsStarted} pairs, fed ${result.animalsFed} animals, and ended with ${BreedCompletionReason[result.reason]}`,
      );

      yield* eventually(
        Effect.flatMap(
          rcon(`execute if entity @e[type=minecraft:cow,tag=!${TAG},${pen}]`, { check: false }),
          (out) =>
            check(
              out.startsWith("Test passed"),
              `no calf within ${CALF_TIMEOUT} of feeding both cows`,
            ),
        ),
        CALF_TIMEOUT,
      );
    }).pipe(Effect.ensuring(cleanUp));
  },
} satisfies E2ETest;
