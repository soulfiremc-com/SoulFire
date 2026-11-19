import { Effect, Result } from "effect";
import {
  encodeJson,
  BOT_NAME,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const UPGRADES = [
  {
    slot: "head",
    menuSlot: 5,
    initial: "leather_helmet",
    weaker: "golden_helmet",
    best: "diamond_helmet",
  },
  {
    slot: "chest",
    menuSlot: 6,
    initial: "diamond_chestplate",
    weaker: "iron_chestplate",
    best: "netherite_chestplate",
  },
] as const;

export default {
  name: "auto-armor",
  run: ({ bot, client }) => {
    const instance = client.instance(bot.instanceId);
    const enablePlugin = (enabled: boolean) =>
      instance.setConfigEntry({
        namespace: "auto-armor",
        key: "enabled",
        value: { kind: { case: "boolValue", value: enabled } },
      });
    return Effect.gen(function* () {
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      const failures: string[] = [];
      for (const mode of ["task", "plugin"] as const) {
        for (const upgrade of UPGRADES) {
          yield* enablePlugin(false);
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(
            `item replace entity ${BOT_NAME} armor.${upgrade.slot} with minecraft:${upgrade.initial}`,
          );
          yield* rcon(
            `item replace entity ${BOT_NAME} inventory.0 with minecraft:${upgrade.weaker}`,
          );
          yield* rcon(`item replace entity ${BOT_NAME} inventory.1 with minecraft:${upgrade.best}`);
          yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.flatMap((state) =>
                  check(
                    state.slots.some(
                      (slot) =>
                        slot.slot === upgrade.menuSlot &&
                        slot.itemId === `minecraft:${upgrade.initial}`,
                    ) &&
                      state.slots.some(
                        (slot) => slot.slot === 9 && slot.itemId === `minecraft:${upgrade.weaker}`,
                      ) &&
                      state.slots.some(
                        (slot) => slot.slot === 10 && slot.itemId === `minecraft:${upgrade.best}`,
                      ),
                    "The armor fixture never reached the bot",
                  ),
                ),
              ),
            "5 seconds",
          );
          const recommendation = yield* bot.inventory.bestArmor(upgrade.slot);
          yield* check(
            recommendation?.slot?.slot === 10,
            `Inventory recommendations disagreed with ${upgrade.best}: ${encodeJson(recommendation)}`,
          );
          if (mode === "task") {
            const task = yield* bot.tasks.autoArmor({
              checkIntervalTicks: 1,
              completeWhenNoUpgrade: true,
            });
            const result = yield* task.result({ call: { timeoutMs: 10_000 } });
            if (result.equips !== 1)
              failures.push(`${mode}/${upgrade.slot} reported ${result.equips} upgrades`);
          } else {
            yield* enablePlugin(true);
          }
          const equipped = yield* Effect.result(
            eventually(
              rcon(
                `execute if items entity ${BOT_NAME} armor.${upgrade.slot} minecraft:${upgrade.best}`,
                { check: false },
              ).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply))),
              "6 seconds",
            ),
          );
          yield* enablePlugin(false);
          if (Result.isFailure(equipped)) {
            const actual = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
            failures.push(`${mode}/${upgrade.slot} did not equip ${upgrade.best}: ${actual}`);
          } else {
            yield* eventually(
              bot
                .inventoryState()
                .pipe(
                  Effect.flatMap((state) =>
                    check(
                      (!state.carriedItem || state.carriedItem.count === 0) &&
                        [upgrade.initial, upgrade.weaker, upgrade.best].every(
                          (item) =>
                            state.slots
                              .filter((slot) => slot.itemId === `minecraft:${item}`)
                              .reduce((count, slot) => count + slot.count, 0) === 1,
                        ),
                      `${mode}/${upgrade.slot} lost or duplicated armor`,
                    ),
                  ),
                ),
              "5 seconds",
            );
          }
        }
      }
      yield* check(failures.length === 0, failures.join("; "));
    }).pipe(
      Effect.ensuring(
        enablePlugin(false).pipe(
          Effect.andThen(rcon(`clear ${BOT_NAME}`, { check: false })),
          Effect.andThen(rcon(`gamemode creative ${BOT_NAME}`)),
          Effect.andThen(rcon("fill 864 63 0 868 63 4 air")),
          Effect.andThen(rcon("forceload remove 864 0")),
          Effect.ignore,
        ),
      ),
    );
  },
} satisfies E2ETest;
