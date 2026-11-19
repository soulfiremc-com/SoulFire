import { RangedAttackCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
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

const TAG = "sf_e2e_combat_ranged";

export default {
  name: "combat-ranged",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3600 0 3647 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3600 63 0 3647 63 15 air", { check: false });
            yield* rcon("forceload remove 3600 0 3647 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3600 63 0 3647 63 15 stone");
        const health = rcon(`data get entity @e[tag=${TAG},type=villager,limit=1] Health`).pipe(
          Effect.map((text) => Number(text.match(/: ([\d.]+)f$/u)?.[1])),
        );
        for (const mode of [
          "bow",
          "crossbow",
          "loaded",
          "empty",
          "cancel-bow",
          "cancel-crossbow",
        ] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("600 millis");
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: 3610.5, y: 64, z: 8.5 }, -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          const weapon = mode === "bow" || mode === "cancel-bow" ? "bow" : "crossbow";
          const stack =
            mode === "loaded"
              ? 'crossbow[minecraft:charged_projectiles=[{id:"minecraft:arrow",count:1}]]'
              : weapon;
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:${stack}`);
          if (mode !== "loaded" && mode !== "empty") yield* rcon(`give ${BOT_NAME} arrow 1`);
          yield* bot.selectHotbar(0);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((p) =>
                  check(
                    p.mainHand?.itemId === `minecraft:${weapon}`,
                    "Waiting for the ranged weapon",
                  ),
                ),
              ),
            "5 seconds",
          );
          yield* rcon(`summon villager 3622.5 64 8.5 {Tags:["${TAG}"],NoAI:1b}`);
          const target = yield* eventually(
            bot.world
              .queryEntities({
                radius: 24,
                selector: { entityTypes: ["minecraft:villager"], alive: true },
              })
              .pipe(
                Effect.map((result) => result.entities[0]?.reference),
                Effect.filterOrFail(
                  (reference) => reference !== undefined,
                  () => new CheckFailed({ message: "The ranged target never became observable" }),
                ),
              ),
            "5 seconds",
          );
          const before = yield* health;
          const task = yield* bot.tasks.rangedAttack(target, { maximumShots: 1, strafe: false });
          yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
          if (mode.startsWith("cancel")) {
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap((p) => check(p.usingItem, "The weapon never started charging")),
                ),
              "3 seconds",
              "50 millis",
            );
            yield* task.cancel("Cancel while charging");
            yield* task.wait({ call: { timeoutMs: 5000 } });
          } else {
            const result = yield* task.result({ call: { timeoutMs: 10000 } });
            yield* check(
              result.shotsReleased === (mode === "empty" ? 0 : 1) &&
                result.reason ===
                  (mode === "empty"
                    ? RangedAttackCompletionReason.NO_AMMUNITION
                    : RangedAttackCompletionReason.SHOT_LIMIT_REACHED),
              `${mode} did not report the expected shot outcome`,
            );
            if (mode === "empty")
              yield* check((yield* health) === before, "An empty crossbow damaged the target");
            else {
              yield* eventually(
                health.pipe(
                  Effect.flatMap((value) =>
                    check(value < before, `${mode} reported a shot without damaging the target`),
                  ),
                ),
                "3 seconds",
              );
              yield* check(
                (yield* bot.inventory.count("minecraft:arrow")) === 0n,
                "The shot left its arrow unused",
              );
            }
          }
          yield* Effect.sleep("500 millis");
          yield* check(!(yield* bot.world.player()).usingItem, `${mode} left the use input held`);
        }
      }),
    ),
} satisfies E2ETest;
