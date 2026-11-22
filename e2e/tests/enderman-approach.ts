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

const TAG = "sf_e2e_enderman_approach";

// An enderman turns on a player who looks at its head. An attack that chases
// an enderman should reach it before it does.
export default {
  name: "enderman-approach",
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
        yield* rcon("forceload add 3776 0 3807 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3776 63 0 3807 68 31 air", { check: false });
            yield* rcon("forceload remove 3776 0 3807 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3776 63 0 3807 68 31 air");
        yield* rcon("fill 3776 63 0 3807 63 31 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 3780.5, y: 64, z: 16.5 }, -90);
        yield* rcon(`item replace entity ${BOT_NAME} weapon.mainhand with stone_sword`);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        // Slowness keeps it in place without blocking its line of sight.
        yield* rcon(
          `summon enderman 3792.5 64 16.5 {Tags:["${TAG}"],PersistenceRequired:1b,Rotation:[90f,0f]}`,
        );
        yield* rcon(`effect give @e[tag=${TAG}] slowness 60 255 true`);
        const target = yield* eventually(
          bot.world
            .queryEntities({
              radius: 24,
              selector: { entityTypes: ["minecraft:enderman"], alive: true },
            })
            .pipe(
              Effect.map((result) => result.entities[0]?.reference),
              Effect.filterOrFail(
                (reference) => reference !== undefined,
                () => new CheckFailed({ message: "The enderman never became observable" }),
              ),
            ),
          "5 seconds",
        );
        const task = yield* bot.tasks.attackEntity(target, {
          maximumAttacks: 1,
          selectBestWeapon: false,
          restoreSelectedSlot: false,
          sprinting: false,
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        let angeredAt: number | undefined;
        for (let tick = 0; tick < 50 && angeredAt === undefined; tick += 1) {
          yield* Effect.sleep("100 millis");
          const anger = yield* rcon(`data get entity @e[tag=${TAG},limit=1] anger_end_time`, {
            check: false,
          });
          // A calm enderman's anger ends at -1.
          const angerEnd = Number(/(-?\d+)L?\s*$/u.exec(anger.trim())?.[1] ?? -1);
          if (angerEnd >= 0) {
            const player = yield* bot.world.player();
            angeredAt = Math.abs((player.position?.x ?? 0) - 3792.5);
          }
        }
        yield* check(
          angeredAt === undefined || angeredAt <= 4,
          `The chase angered the enderman ${angeredAt?.toFixed(1)} blocks away`,
        );
      }),
    ),
} satisfies E2ETest;
