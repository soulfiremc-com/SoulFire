import { BlockFace, Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { Console, Effect } from "effect";
import {
  encodeJson,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  rcon,
} from "../harness.ts";

const END = "execute in minecraft:the_end run";
const DRAGON = "@e[type=ender_dragon,limit=1]";
// The same geometry as the beat-game runner's bed attack: a perched dragon's
// head hangs 6.5 blocks along its yaw and one block below its body, and the
// bot stands three blocks past the head behind a one-block wall.
const HEAD_REACH = 6.5;
const STAND_DISTANCE = 3;

const dragonNumber = (path: string) =>
  rcon(`${END} data get entity ${DRAGON} ${path}`).pipe(
    Effect.map((text) => Number(text.match(/: (-?[\d.]+)[fbd]?$/u)?.[1])),
  );
const botHealth = rcon(`${END} data get entity ${BOT_NAME} Health`).pipe(
  Effect.map((text) => Number(text.match(/: ([\d.]+)f$/u)?.[1])),
);
const topSolid = (x: number, z: number, from: number) =>
  Effect.gen(function* () {
    for (let y = from; y > from - 16; y--) {
      const out = yield* rcon(`${END} execute unless block ${x} ${y} ${z} #minecraft:air`, {
        check: false,
      });
      if (out.includes("passed")) {
        return y;
      }
    }
    return yield* new CheckFailed({ message: `No ground at ${x} ${z}` });
  });

// Checks that a bed set off under a perched dragon's head hurts it badly
// while the wall keeps the bot alive.
export default {
  name: "bed-explosion-dragon",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon(`${END} forceload add -32 -32 32 32`);
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* rcon(`execute in minecraft:overworld run tp ${BOT_NAME} 0.5 64 0.5`, {
              check: false,
            });
            yield* rcon(`${END} forceload remove -32 -32 32 32`, { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* rcon(`clear ${BOT_NAME}`, { check: false });
        yield* rcon(`effect clear ${BOT_NAME}`, { check: false });
        // Entering the End starts the dragon fight and builds the exit portal.
        yield* rcon(`${END} tp ${BOT_NAME} 0.5 90 20.5`);
        yield* eventually(
          dragonNumber("Health").pipe(
            Effect.flatMap((health) => check(health > 0, "No dragon spawned")),
          ),
          "30 seconds",
          "500 millis",
        );
        // The bot stays in creative while the dragon flies, so it cannot die first.
        yield* rcon(`${END} data merge entity ${DRAGON} {DragonPhase:2}`);
        yield* eventually(
          dragonNumber("DragonPhase").pipe(
            Effect.flatMap((phase) =>
              check(phase >= 5 && phase <= 7, `The dragon is not perched (phase ${phase})`),
            ),
          ),
          "120 seconds",
          "250 millis",
        );
        const x = yield* dragonNumber("Pos[0]");
        const y = yield* dragonNumber("Pos[1]");
        const z = yield* dragonNumber("Pos[2]");
        const yaw = ((yield* dragonNumber("Rotation[0]")) * Math.PI) / 180;
        const head = { x: Math.sin(yaw), z: -Math.cos(yaw) };
        const along = (distance: number) => ({
          x: Math.floor(x + head.x * distance),
          z: Math.floor(z + head.z * distance),
        });
        const bed = along(HEAD_REACH);
        const stand = along(HEAD_REACH + STAND_DISTANCE);
        const wall = along(HEAD_REACH + STAND_DISTANCE - 1);
        const headY = Math.floor(y - 1);
        const bedGround = yield* topSolid(bed.x, bed.z, headY);
        const standGround = yield* topSolid(stand.x, stand.z, headY);
        yield* rcon(`${END} setblock ${wall.x} ${standGround + 1} ${wall.z} end_stone`);
        yield* rcon(
          `${END} tp ${BOT_NAME} ${stand.x + 0.5} ${standGround + 1} ${stand.z + 0.5} facing entity ${DRAGON}`,
        );
        yield* bot.waitForChunks({ radiusChunks: 2, timeoutMs: 10_000 }, { timeoutMs: 15_000 });
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:red_bed`);
        yield* bot.selectHotbar(0);
        yield* Effect.sleep("500 millis");
        const dragonBefore = yield* dragonNumber("Health");
        const botBefore = yield* botHealth;
        yield* bot.placeBlock({
          against: { x: bed.x, y: bedGround, z: bed.z },
          face: BlockFace.UP,
          hand: Hand.MAIN,
        });
        yield* bot.interactBlock({
          position: { x: bed.x, y: bedGround + 1, z: bed.z },
          face: BlockFace.UP,
          hand: Hand.MAIN,
        });
        yield* Effect.sleep("1 second");
        const dragonAfter = yield* dragonNumber("Health");
        const botAfter = yield* botHealth;
        yield* Console.log(
          encodeJson({
            dragonBefore,
            dragonAfter,
            botBefore,
            botAfter,
          }),
        );
        yield* check(
          dragonAfter < dragonBefore - 20,
          "One bed did not hurt the perched dragon badly",
        );
        yield* check(botAfter > 0, "The bed explosion killed the bot");
      }),
    ),
} satisfies E2ETest;
