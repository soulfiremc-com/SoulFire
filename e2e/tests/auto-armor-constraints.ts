import { GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "auto-armor-constraints",
  run: ({ bot }) =>
    Effect.gen(function* () {
      const changeMode = (mode: "survival" | "creative") =>
        rcon(`gamemode ${mode} ${BOT_NAME}`).pipe(
          Effect.andThen(
            eventually(
              bot
                .info()
                .pipe(
                  Effect.flatMap((info) =>
                    check(
                      info.liveState?.gameMode ===
                        (mode === "survival" ? GameMode.SURVIVAL : GameMode.CREATIVE),
                      `The bot never entered ${mode} mode`,
                    ),
                  ),
                ),
              "5 seconds",
            ),
          ),
        );
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* changeMode("survival");
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      yield* rcon(
        `item replace entity ${BOT_NAME} armor.head with minecraft:leather_helmet[enchantments={"minecraft:binding_curse":1}]`,
      );
      yield* rcon(`item replace entity ${BOT_NAME} inventory.0 with minecraft:diamond_helmet`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some(
                  (slot) => slot.slot === 5 && slot.itemId === "minecraft:leather_helmet",
                ) &&
                  state.slots.some(
                    (slot) => slot.slot === 9 && slot.itemId === "minecraft:diamond_helmet",
                  ),
                "The bound armor fixture never arrived",
              ),
            ),
          ),
        "5 seconds",
      );
      const blocked = yield* bot.tasks.autoArmor({
        checkIntervalTicks: 1,
        maximumEquips: 1,
        completeWhenNoUpgrade: true,
      });
      const blockedResult = yield* blocked.result({ call: { timeoutMs: 10_000 } });
      yield* check(
        (yield* rcon(`execute if items entity ${BOT_NAME} armor.head minecraft:leather_helmet`, {
          check: false,
        })).startsWith("Test passed"),
        "Auto Armor removed bound armor in survival",
      );

      yield* changeMode("creative");
      const creative = yield* bot.tasks.autoArmor({
        checkIntervalTicks: 1,
        maximumEquips: 1,
        completeWhenNoUpgrade: true,
      });
      const creativeResult = yield* creative.result({ call: { timeoutMs: 10_000 } });
      yield* check(
        creativeResult.equips === 1,
        "Creative mode did not permit the bound armor replacement",
      );
      yield* eventually(
        rcon(`execute if items entity ${BOT_NAME} armor.head minecraft:diamond_helmet`, {
          check: false,
        }).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply))),
        "5 seconds",
      );
      yield* changeMode("survival");
      yield* rcon(`clear ${BOT_NAME}`, { check: false });
      yield* rcon(`item replace entity ${BOT_NAME} armor.head with minecraft:leather_helmet`);
      for (let slot = 0; slot < 27; slot++) {
        yield* rcon(`item replace entity ${BOT_NAME} inventory.${slot} with minecraft:stone 64`);
      }
      for (let slot = 1; slot < 8; slot++) {
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.${slot} with minecraft:stone 64`);
      }
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:netherite_sword`);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.8 with minecraft:diamond_helmet`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.filter((slot) => slot.slot >= 9 && slot.slot <= 44 && slot.count > 0)
                  .length === 36,
                "The full inventory fixture never arrived",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* bot.selectHotbar(0);
      const full = yield* bot.tasks.autoArmor({
        checkIntervalTicks: 1,
        completeWhenNoUpgrade: true,
      });
      const fullResult = yield* full.result({ call: { timeoutMs: 10_000 } });
      yield* check(
        fullResult.equips === 1,
        `Full inventory armor task reported ${fullResult.equips} upgrades`,
      );
      yield* eventually(
        rcon(`execute if items entity ${BOT_NAME} armor.head minecraft:diamond_helmet`, {
          check: false,
        }).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply))),
        "5 seconds",
      );
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) => {
            const count = (id: string) =>
              state.slots
                .filter((slot) => slot.itemId === `minecraft:${id}`)
                .reduce((total, slot) => total + slot.count, 0);
            return check(
              count("stone") === 2176 &&
                count("netherite_sword") === 1 &&
                state.slots.some(
                  (slot) => slot.slot === 36 && slot.itemId === "minecraft:netherite_sword",
                ) &&
                count("leather_helmet") === 1 &&
                count("diamond_helmet") === 1 &&
                !state.carriedItem?.count,
              "Full inventory armor swap lost or duplicated an item",
            );
          }),
        ),
        "5 seconds",
      );
      yield* check(
        blockedResult.equips === 0,
        `Auto Armor reported ${blockedResult.equips} upgrades while vanilla retained the bound helmet`,
      );
    }).pipe(
      Effect.ensuring(
        rcon(`clear ${BOT_NAME}`, { check: false }).pipe(
          Effect.andThen(rcon(`gamemode creative ${BOT_NAME}`)),
          Effect.andThen(rcon("fill 864 63 0 868 63 4 air")),
          Effect.andThen(rcon("forceload remove 864 0")),
          Effect.ignore,
        ),
      ),
    ),
} satisfies E2ETest;
