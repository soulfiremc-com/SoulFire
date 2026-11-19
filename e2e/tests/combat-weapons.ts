import { AttackEntityCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

const TAG = "sf_e2e_combat_weapons";

export default {
  name: "combat-weapons",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3360 0 3391 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3360 63 0 3391 63 15 air", { check: false });
            yield* rcon("forceload remove 3360 0 3391 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3360 63 0 3391 63 15 stone");
        const health = rcon(`data get entity @e[tag=${TAG},type=cow,limit=1] Health`).pipe(
          Effect.map((text) => Number(text.match(/: ([\d.]+)f$/u)?.[1])),
        );
        for (const mode of [
          "sword",
          "spear-behind",
          "spear-close",
          "spear-reach",
          "spear-selected",
        ] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("600 millis");
          yield* placeBot(bot, { x: 3370.5, y: 64, z: 8.5 }, mode === "spear-behind" ? 90 : -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          const weapon = mode === "sword" ? "wooden_sword" : "wooden_spear";
          const weaponSlot = mode === "spear-selected" ? 1 : 0;
          yield* rcon(
            `item replace entity ${BOT_NAME} hotbar.${weaponSlot} with minecraft:${weapon}`,
          );
          if (mode === "spear-selected")
            yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:stick`);
          yield* bot.selectHotbar(0);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((player) =>
                  check(
                    player.mainHand?.itemId ===
                      `minecraft:${mode === "spear-selected" ? "stick" : weapon}`,
                    "The starting item never reached the bot",
                  ),
                ),
              ),
            "5 seconds",
          );
          const distance = mode === "spear-close" ? 0.75 : mode === "spear-reach" ? 5.8 : 3;
          yield* rcon(`summon cow ${3370.5 + distance} 64 8.5 {Tags:["${TAG}"],NoAI:1b}`);
          const target = yield* eventually(
            bot.world
              .queryEntities({
                radius: 8,
                selector: { entityTypes: ["minecraft:cow"], alive: true },
              })
              .pipe(
                Effect.map((result) => result.entities[0]?.reference),
                Effect.filterOrFail(
                  (reference) => reference !== undefined,
                  () => new CheckFailed({ message: "The cow never became observable" }),
                ),
              ),
            "5 seconds",
          );
          yield* Effect.sleep("1500 millis");
          const before = yield* health;
          if (mode === "sword" || mode === "spear-behind") {
            yield* bot.attackEntity({
              entityId: target.networkId,
              connectionEpoch: target.connectionEpoch,
            });
          } else {
            const task = yield* bot.tasks.attackEntity(target, {
              maximumAttacks: 1,
              selectBestWeapon: mode === "spear-selected",
              restoreSelectedSlot: false,
              attackRange: mode === "spear-reach" ? 6 : 3,
              ...(mode === "spear-selected"
                ? { weapon: { itemIds: ["minecraft:wooden_spear"] } }
                : {}),
            });
            yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
            const result = yield* task.result({ call: { timeoutMs: 10_000 } });
            yield* check(
              result.attacks === 1 &&
                result.reason === AttackEntityCompletionReason.ATTACK_LIMIT_REACHED,
              `${mode} did not respect the attack limit`,
            );
          }
          yield* eventually(
            health.pipe(
              Effect.flatMap((value) =>
                check(value < before, `${mode} reported an attack without damaging the cow`),
              ),
            ),
            "3 seconds",
          );
          if (mode === "spear-close")
            yield* check(
              (yield* serverPosition).x < 3370,
              "The task did not make room for its spear",
            );
          if (mode === "spear-reach")
            yield* check(
              (yield* serverPosition).x > 3371,
              "The task did not approach within its weapon's reach",
            );
          if (mode === "spear-selected")
            yield* check(
              (yield* bot.world.player()).mainHand?.itemId === "minecraft:wooden_spear",
              "The task did not select its requested spear",
            );
          if (mode === "spear-behind") {
            const afterFirst = yield* health;
            yield* bot.attackEntity({
              entityId: target.networkId,
              connectionEpoch: target.connectionEpoch,
            });
            yield* eventually(
              health.pipe(
                Effect.flatMap((value) =>
                  check(value < afterFirst, "The next spear attack ignored the weapon's charge"),
                ),
              ),
              "3 seconds",
            );
          }
        }
      }),
    ),
} satisfies E2ETest;
