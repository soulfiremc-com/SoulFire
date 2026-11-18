import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const nativeItem = (slot: string, item: string) =>
  rcon(`execute if items entity ${BOT_NAME} ${slot} minecraft:${item}`, {
    check: false,
  }).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply)));

export default {
  name: "auto-totem-cursor",
  run: ({ bot, client }) => {
    const plugin = (enabled: boolean) =>
      client.instance(bot.instanceId).setConfigEntry({
        namespace: "auto-totem",
        key: "enabled",
        value: { kind: { case: "boolValue", value: enabled } },
      });
    return Effect.gen(function* () {
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      for (const mode of ["sdk-empty", "sdk-shield", "plugin"] as const) {
        yield* plugin(false);
        yield* rcon(`clear ${BOT_NAME}`, { check: false });
        yield* rcon(
          `item replace entity ${BOT_NAME} weapon.offhand with minecraft:${mode === "sdk-shield" ? "shield" : "air"}`,
        );
        yield* rcon(`item replace entity ${BOT_NAME} inventory.0 with minecraft:totem_of_undying`);
        yield* rcon(`item replace entity ${BOT_NAME} inventory.1 with minecraft:diamond 5`);
        yield* eventually(
          bot
            .inventoryState()
            .pipe(
              Effect.flatMap((state) =>
                check(
                  state.slots.some((slot) => slot.slot === 10 && slot.count === 5) &&
                    state.slots.some(
                      (slot) => slot.slot === 9 && slot.itemId === "minecraft:totem_of_undying",
                    ),
                  "The cursor fixture did not arrive",
                ),
              ),
            ),
          "5 seconds",
        );
        yield* bot.openInventory();
        yield* bot.clickInventory(10);
        const task =
          mode === "plugin"
            ? undefined
            : yield* bot.tasks.autoTotem({
                checkIntervalTicks: 1,
                maximumEquips: 1,
                replaceOccupiedOffhand: true,
              });
        if (mode === "plugin") yield* plugin(true);
        yield* Effect.gen(function* () {
          // Span the plugin's default two-second maximum delay as well as SDK ticks.
          for (let sample = 0; sample < 25; sample++) {
            yield* Effect.sleep("100 millis");
            const state = yield* bot.inventoryState();
            yield* check(
              state.carriedItem?.itemId === "minecraft:diamond" &&
                state.carriedItem.count === 5 &&
                state.slots.some(
                  (slot) => slot.slot === 9 && slot.itemId === "minecraft:totem_of_undying",
                ) &&
                (mode === "sdk-shield"
                  ? state.slots.some(
                      (slot) => slot.slot === 45 && slot.itemId === "minecraft:shield",
                    )
                  : !state.slots.some((slot) => slot.slot === 45)),
              `${mode} changed equipment or displaced the occupied cursor`,
            );
            if (task)
              yield* check(
                (yield* task.refresh()).status === BotTaskStatus.RUNNING,
                "Auto Totem completed while the cursor was occupied",
              );
          }
          yield* nativeItem("inventory.0", "totem_of_undying");
          if (mode === "sdk-shield") yield* nativeItem("weapon.offhand", "shield");
          yield* bot.clickInventory(10);
          if (task) {
            const result = yield* task.result({ call: { timeoutMs: 10000 } });
            yield* check(result.equips === 1, "Auto Totem did not confirm exactly one equip");
          }
          yield* eventually(nativeItem("weapon.offhand", "totem_of_undying"), "5 seconds");
          yield* eventually(
            bot.inventoryState().pipe(
              Effect.flatMap((state) => {
                const count = (item: string) =>
                  state.slots
                    .filter((slot) => slot.itemId === `minecraft:${item}`)
                    .reduce((total, slot) => total + slot.count, 0);
                return check(
                  !state.carriedItem?.count &&
                    count("diamond") === 5 &&
                    count("totem_of_undying") === 1 &&
                    count("shield") === (mode === "sdk-shield" ? 1 : 0),
                  "Equipping after cursor release lost or duplicated an item",
                );
              }),
            ),
            "5 seconds",
          );
          yield* nativeItem("inventory.1", "diamond");
          if (mode === "sdk-shield") yield* nativeItem("inventory.0", "shield");
        }).pipe(
          Effect.ensuring(
            Effect.gen(function* () {
              if (task) yield* task.cancel();
              yield* plugin(false);
              yield* bot.closeContainer();
            }).pipe(Effect.ignore),
          ),
        );
      }
    }).pipe(
      Effect.ensuring(
        Effect.gen(function* () {
          for (const cleanup of [
            plugin(false),
            rcon(`clear ${BOT_NAME}`, { check: false }),
            rcon(`gamemode creative ${BOT_NAME}`),
            rcon("fill 864 63 0 868 63 4 air"),
            rcon("forceload remove 864 0"),
          ])
            yield* cleanup.pipe(Effect.ignore);
        }),
      ),
    );
  },
} satisfies E2ETest;
