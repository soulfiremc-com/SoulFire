import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "auto-totem",
  run: ({ bot }) =>
    Effect.gen(function* () {
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      for (let slot = 0; slot < 3; slot++) {
        yield* rcon(
          `item replace entity ${BOT_NAME} inventory.${slot} with minecraft:totem_of_undying`,
        );
      }
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.filter((slot) => slot.itemId === "minecraft:totem_of_undying")
                  .length === 3,
                "The three totems did not arrive",
              ),
            ),
          ),
        "5 seconds",
      );
      const task = yield* bot.tasks.autoTotem({ checkIntervalTicks: 1, maximumEquips: 2 });
      yield* Effect.gen(function* () {
        yield* eventually(
          bot.inventoryState().pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some(
                  (slot) => slot.slot === 45 && slot.itemId === "minecraft:totem_of_undying",
                ),
                "The first totem was not equipped",
              ),
            ),
          ),
          "5 seconds",
          "5 millis",
        );
        // Consume the first totem before the equip transaction finishes cleanup.
        yield* eventually(
          rcon(
            `execute if items entity ${BOT_NAME} weapon.offhand minecraft:totem_of_undying run damage ${BOT_NAME} 100 minecraft:generic`,
            { check: false },
          ).pipe(Effect.flatMap((reply) => check(reply.includes("Applied 100.0 damage"), reply))),
          "5 seconds",
          "5 millis",
        );
        const result = yield* task.result({ call: { timeoutMs: 10_000 } });
        yield* check(result.equips === 2, "The consumed totem was omitted from the equip count");
        yield* eventually(
          rcon(`execute if items entity ${BOT_NAME} weapon.offhand minecraft:totem_of_undying`, {
            check: false,
          }).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply))),
          "5 seconds",
        );
        const remaining = yield* rcon(`clear ${BOT_NAME} minecraft:totem_of_undying 0`);
        yield* check(remaining.includes("Found 2 "), "Vanilla did not consume exactly one totem");
        yield* check(
          ((yield* bot.info()).liveState?.health ?? 0) > 0,
          "The totem did not save the bot",
        );
      }).pipe(Effect.ensuring(task.cancel().pipe(Effect.ignore)));
    }).pipe(
      Effect.ensuring(
        Effect.gen(function* () {
          for (const cleanup of [
            rcon(`effect clear ${BOT_NAME}`, { check: false }),
            rcon(`clear ${BOT_NAME}`, { check: false }),
            rcon(`gamemode creative ${BOT_NAME}`),
            placeBot(bot, { x: 0.5, y: 64, z: 0.5 }),
            rcon("fill 864 63 0 868 63 4 air"),
            rcon("forceload remove 864 0"),
          ])
            yield* cleanup.pipe(Effect.ignore);
        }),
      ),
    ),
} satisfies E2ETest;
