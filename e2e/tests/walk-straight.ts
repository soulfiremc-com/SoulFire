// The bot walks along a straight line of blocks, one block wide, and stops on its last block.
import { goals } from "@soulfiremc/sdk";
import { Effect } from "effect";
import {
  encodeJson,
  blockOf,
  check,
  type E2ETest,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

/** Where the bot starts (feet). The line is the row of blocks under it, going +x. */
const START = { x: 100, y: 64, z: 0 };
const LENGTH = 20;

export default {
  name: "walk-straight",
  run: ({ bot }) =>
    Effect.gen(function* () {
      const { x, y, z } = START;
      yield* rcon(`forceload add ${x - 16} ${z - 16} ${x + LENGTH + 16} ${z + 16}`);
      yield* rcon(
        `fill ${x - 2} ${y - 4} ${z - 2} ${x + LENGTH + 2} ${y + 4} ${z + 2} minecraft:air`,
      );
      yield* rcon(`fill ${x} ${y - 1} ${z} ${x + LENGTH} ${y - 1} ${z} minecraft:stone`);
      yield* placeBot(bot, { x: x + 0.5, y, z: z + 0.5 });

      const goal = { x: x + LENGTH, y, z };
      const route = yield* bot.pathfinder.goTo(goals.block(goal), { path: { timeoutSeconds: 30 } });
      yield* route.result();

      const position = yield* serverPosition;
      const at = blockOf(position);
      yield* check(
        at.x === goal.x && at.y === goal.y && at.z === goal.z,
        `route done, but the bot is at ${encodeJson(at)}, not ${encodeJson(goal)}`,
      );
    }),
} satisfies E2ETest;
