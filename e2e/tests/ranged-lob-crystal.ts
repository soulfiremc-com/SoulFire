import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const TAG = "sf_e2e_ranged_lob";
// As in a real End: a crystal on a tower whose rim hides it from the ground,
// shot from twenty blocks inside the tower.
const TOWER_X = 3720;
const TOWER_TOP = 97;
const BOT_X = 3700.5;

const crystalStanding = rcon(`execute if entity @e[tag=${TAG},type=end_crystal]`, {
  check: false,
}).pipe(Effect.map((text) => text.includes("passed")));

export default {
  name: "ranged-lob-crystal",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3680 -16 3735 16");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("kill @e[type=arrow]", { check: false });
            yield* rcon(`fill ${TOWER_X - 3} 64 -3 ${TOWER_X + 3} 105 3 air`, { check: false });
            yield* rcon("fill 3690 63 -6 3730 63 6 air", { check: false });
            yield* rcon("forceload remove 3680 -16 3735 16", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3690 63 -6 3730 63 6 stone");
        yield* rcon(`fill ${TOWER_X - 3} 64 -3 ${TOWER_X + 3} ${TOWER_TOP} 3 obsidian`);
        yield* rcon(`setblock ${TOWER_X} ${TOWER_TOP} 0 bedrock`);
        yield* rcon(
          `summon end_crystal ${TOWER_X + 0.5} ${TOWER_TOP + 1} 0.5 {Tags:["${TAG}"],ShowBottom:1b}`,
        );
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: BOT_X, y: 64, z: 0.5 }, -90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:bow`);
        yield* rcon(`give ${BOT_NAME} arrow 16`);
        yield* bot.selectHotbar(0);
        const target = yield* eventually(
          bot.world
            .queryEntities({
              radius: 64,
              selector: { entityTypes: ["minecraft:end_crystal"], alive: true },
            })
            .pipe(
              Effect.map((result) => result.entities[0]?.reference),
              Effect.filterOrFail(
                (reference) => reference !== undefined,
                () => new CheckFailed({ message: "The crystal never became observable" }),
              ),
            ),
          "10 seconds",
        );
        const task = yield* bot.tasks.rangedAttack(target, {
          maximumShots: 3,
          maximumRange: 64,
          strafe: false,
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        yield* task.result({ call: { timeoutMs: 30_000 } });
        // A lob stays in the air for seconds after its release.
        yield* eventually(
          crystalStanding.pipe(
            Effect.flatMap((standing) =>
              check(!standing, "No lobbed arrow reached the crystal on its tower"),
            ),
          ),
          "10 seconds",
          "250 millis",
        );
        const player = yield* bot.world.player();
        yield* check(
          player.position !== undefined && Math.abs(player.position.x - BOT_X) < 2,
          "The bot walked toward the tower instead of shooting from the ground",
        );
      }),
    ),
} satisfies E2ETest;
