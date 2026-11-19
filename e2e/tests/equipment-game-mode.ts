import { GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "equipment-game-mode",
  run: ({ bot }) => {
    const changeMode = (mode: "survival" | "spectator" | "creative") =>
      rcon(`gamemode ${mode} ${BOT_NAME}`).pipe(
        Effect.andThen(
          eventually(
            bot.info().pipe(
              Effect.flatMap((info) =>
                check(
                  info.liveState?.gameMode ===
                    {
                      survival: GameMode.SURVIVAL,
                      spectator: GameMode.SPECTATOR,
                      creative: GameMode.CREATIVE,
                    }[mode],
                  `The bot did not enter ${mode} mode`,
                ),
              ),
            ),
            "5 seconds",
            "10 millis",
          ),
        ),
      );
    return Effect.gen(function* () {
      yield* changeMode("creative");
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      for (const equipment of ["totem", "armor"] as const) {
        const item = equipment === "totem" ? "totem_of_undying" : "diamond_helmet";
        const target = equipment === "totem" ? "weapon.offhand" : "armor.head";
        for (const interrupt of [false, true]) {
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(`item replace entity ${BOT_NAME} weapon.offhand with minecraft:air`);
          yield* rcon(`item replace entity ${BOT_NAME} armor.head with minecraft:air`);
          yield* rcon(`item replace entity ${BOT_NAME} inventory.0 with minecraft:${item}`);
          yield* rcon(`item replace entity ${BOT_NAME} inventory.1 with minecraft:diamond 5`);
          yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:netherite_sword`);
          yield* bot.selectHotbar(0);
          yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.flatMap((state) =>
                  check(
                    state.slots.some(
                      (slot) => slot.slot === 9 && slot.itemId === `minecraft:${item}`,
                    ) &&
                      state.slots.some(
                        (slot) => slot.slot === 36 && slot.itemId === "minecraft:netherite_sword",
                      ),
                    "The equipment fixture did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          yield* changeMode(interrupt ? "survival" : "spectator");
          const task =
            equipment === "totem"
              ? yield* bot.tasks.autoTotem({ checkIntervalTicks: 1, maximumEquips: 1 })
              : yield* bot.tasks.autoArmor({
                  checkIntervalTicks: 1,
                  maximumEquips: 1,
                  completeWhenNoUpgrade: true,
                });
          yield* Effect.gen(function* () {
            if (interrupt) {
              // Interrupt after the first native click, before the equipment-slot click.
              yield* eventually(
                bot
                  .inventoryState()
                  .pipe(
                    Effect.flatMap((state) =>
                      check(
                        equipment === "totem"
                          ? state.carriedItem?.itemId === `minecraft:${item}`
                          : state.slots.some(
                              (slot) => slot.slot === 36 && slot.itemId === `minecraft:${item}`,
                            ),
                        "The equip transaction did not start",
                      ),
                    ),
                  ),
                "5 seconds",
                "5 millis",
              );
              yield* changeMode("spectator");
            }
            yield* Effect.sleep("1500 millis");
            const pending = yield* task.refresh();
            yield* check(
              pending.status === BotTaskStatus.RUNNING && (pending.progress?.current ?? 0n) === 0n,
              `${equipment} counted an equip rejected by spectator mode (interrupted=${interrupt})`,
            );
            const rejected = yield* rcon(
              `execute unless items entity ${BOT_NAME} ${target} minecraft:${item}`,
              { check: false },
            );
            yield* check(rejected.startsWith("Test passed"), rejected);
            yield* changeMode("survival");
            const result = yield* task.result({ call: { timeoutMs: 10_000 } });
            yield* check(result.equips === 1, "The accepted equip was not counted exactly once");
            yield* eventually(
              rcon(`execute if items entity ${BOT_NAME} ${target} minecraft:${item}`, {
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
                    !state.carriedItem?.count &&
                      count(item) === 1 &&
                      count("diamond") === 5 &&
                      count("netherite_sword") === 1,
                    "The interrupted equip lost or duplicated an item",
                  );
                }),
              ),
              "5 seconds",
            );
            const sword = yield* rcon(`clear ${BOT_NAME} minecraft:netherite_sword 0`);
            const diamonds = yield* rcon(`clear ${BOT_NAME} minecraft:diamond 0`);
            yield* check(
              sword.includes("Found 1 ") && diamonds.includes("Found 5 "),
              "Vanilla did not retain the unrelated inventory items",
            );
          }).pipe(Effect.ensuring(task.cancel().pipe(Effect.ignore)));
        }
      }
      yield* changeMode("survival");
      yield* rcon(`clear ${BOT_NAME}`, { check: false });
      yield* rcon(`item replace entity ${BOT_NAME} inventory.0 with minecraft:diamond_helmet`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some(
                  (slot) => slot.slot === 9 && slot.itemId === "minecraft:diamond_helmet",
                ) && !state.slots.some((slot) => slot.slot === 5),
                "The armor wear fixture did not arrive",
              ),
            ),
          ),
        "5 seconds",
      );
      const wearing = yield* bot.tasks.autoArmor({
        checkIntervalTicks: 1,
        maximumEquips: 1,
        completeWhenNoUpgrade: true,
      });
      yield* Effect.gen(function* () {
        yield* eventually(
          bot.inventoryState().pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some(
                  (slot) => slot.slot === 5 && slot.itemId === "minecraft:diamond_helmet",
                ),
                "The helmet was not equipped before the wear check",
              ),
            ),
          ),
          "5 seconds",
          "5 millis",
        );
        yield* eventually(
          rcon(
            `execute if items entity ${BOT_NAME} armor.head minecraft:diamond_helmet run damage ${BOT_NAME} 4 minecraft:mob_attack`,
            { check: false },
          ).pipe(Effect.flatMap((reply) => check(reply.includes("Applied 4.0 damage"), reply))),
          "5 seconds",
          "5 millis",
        );
        const result = yield* wearing.result({ call: { timeoutMs: 10_000 } });
        yield* check(
          result.equips === 1,
          "Armor wear prevented a successful equip from being counted",
        );
        const damage = yield* rcon(
          `data get entity ${BOT_NAME} equipment.head.components."minecraft:damage"`,
        );
        yield* check(damage.endsWith(" 1"), "Vanilla did not damage the equipped helmet");
      }).pipe(Effect.ensuring(wearing.cancel().pipe(Effect.ignore)));
    }).pipe(
      Effect.ensuring(
        Effect.gen(function* () {
          for (const cleanup of [
            bot.closeContainer(),
            rcon(`clear ${BOT_NAME}`, { check: false }),
            changeMode("creative"),
            placeBot(bot, { x: 0.5, y: 64, z: 0.5 }),
            rcon("fill 864 63 0 868 63 4 air"),
            rcon("forceload remove 864 0"),
          ])
            yield* cleanup.pipe(Effect.ignore);
        }),
      ),
    );
  },
} satisfies E2ETest;
