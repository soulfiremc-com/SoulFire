import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

const STAND = { x: 3819, y: 64, z: 8 };
const FURNACE = { x: 3819, y: 64, z: 10 };

export default {
  name: "recipe-task-resume",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3808 0 3855 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3808 63 0 3855 66 15 air", { check: false });
            yield* rcon("forceload remove 3808 0 3855 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3808 63 0 3855 63 15 stone");
        for (const kind of ["brew", "smelt"] as const) {
          const brewing = kind === "brew";
          const brewCount = brewing ? 4 : 1;
          yield* bot.closeContainer();
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: 3818.5, y: 64, z: 8.5 }, -90);
          for (const [position, block] of [
            ["3819 64 8", "brewing_stand"],
            ["3819 64 10", "furnace"],
          ]) {
            yield* rcon(`setblock ${position} air`, { check: false });
            yield* rcon(`setblock ${position} ${block}`);
          }
          for (const item of [
            `potion[minecraft:potion_contents={potion:"minecraft:water"}] ${brewCount}`,
            `nether_wart ${Math.ceil(brewCount / 3)}`,
            "blaze_powder",
            `iron_ore ${brewing ? 2 : 1}`,
            `coal ${brewing ? 2 : 1}`,
          ]) {
            yield* rcon(`give ${BOT_NAME} minecraft:${item}`);
          }
          const urgent = {
            priority: BotTaskPriority.HIGH,
            conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
          };
          const brew = (preempt = false) =>
            bot.tasks.brew(
              { itemIds: ["minecraft:potion"] },
              { itemIds: ["minecraft:nether_wart"] },
              preempt ? 1 : brewCount,
              { station: STAND, ...(preempt ? urgent : {}) },
            );
          const smelt = (preempt = false) =>
            bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, 1, {
              station: FURNACE,
              fuel: { itemIds: ["minecraft:coal"] },
              ...(preempt ? urgent : {}),
            });
          const low = yield* brewing ? brew() : smelt();
          yield* Effect.addFinalizer(() => low.cancel("test cleanup").pipe(Effect.ignore));
          yield* eventually(
            rcon(
              brewing
                ? "data get block 3819 64 8 BrewTime"
                : "data get block 3819 64 10 cooking_time_spent",
            ).pipe(
              Effect.flatMap((text) =>
                check(Number(text.match(/: (\d+)$/u)?.[1]) > 0, `Vanilla did not start ${kind}`),
              ),
            ),
            "15 seconds",
            "100 millis",
          );
          // Repeat suspension without waiting for the brewing menu to reopen.
          // Smelting finishes on the server while the longer brew owns the menu.
          for (let interruption = 0; interruption < (brewing ? 2 : 1); interruption++) {
            const high = yield* brewing ? smelt(true) : brew(true);
            yield* Effect.addFinalizer(() => high.cancel("test cleanup").pipe(Effect.ignore));
            yield* eventually(
              low
                .refresh()
                .pipe(
                  Effect.flatMap((t) =>
                    check(
                      t.status === BotTaskStatus.SUSPENDED,
                      `${kind} did not yield to the higher priority recipe`,
                    ),
                  ),
                ),
              "3 seconds",
              "20 millis",
            );
            const completed = yield* high.result({ call: { timeoutMs: 30000 } });
            yield* check(
              completed.outputs.length === 1 && completed.outputs[0]?.count === 1,
              "The higher priority recipe did not collect its output",
            );
            if (brewing && interruption === 0) {
              yield* check(
                (yield* low.refresh()).status === BotTaskStatus.RUNNING,
                "Brewing did not resume after its first interruption",
              );
            }
          }
          const completed = yield* low.result({ call: { timeoutMs: 30000 } });
          yield* check(
            completed.outputs.length === (brewing ? brewCount : 1) &&
              completed.outputs.every((item) => item.count === 1),
            `${kind} lost or duplicated its batch after resuming`,
          );
          if (brewing)
            yield* check(
              completed.outputs.every((item) => item.potion?.potionId === "minecraft:awkward"),
              "The resumed brew collected the wrong potion",
            );
          else
            yield* check(
              completed.outputs[0]?.itemId === "minecraft:iron_ingot",
              "The resumed smelt collected the wrong item",
            );
          for (const [item, expected] of [
            ['minecraft:potion[minecraft:potion_contents={potion:"minecraft:awkward"}]', brewCount],
            ["minecraft:iron_ingot", brewing ? 2 : 1],
          ] as const) {
            const value = yield* rcon(`clear ${BOT_NAME} ${item} 0`);
            yield* check(
              value.includes(`Found ${expected} `),
              `Vanilla output count changed after resuming ${kind}: ${value}`,
            );
          }
          yield* check(
            (yield* bot.inventory.count("minecraft:nether_wart")) === 0n &&
              (yield* bot.inventory.count("minecraft:iron_ore")) === 0n,
            "Recipe resumption left inputs unused",
          );
          const stand = yield* rcon("data get block 3819 64 8 Items");
          const furnace = yield* rcon("data get block 3819 64 10 Items");
          yield* check(
            stand.endsWith(": []") && furnace.endsWith(": []"),
            "Resumed recipes left their output in a station",
          );
          yield* check(
            (yield* bot.inventory.snapshot()).containerType === "inventory",
            "Completed recipes left a station menu open",
          );
        }
      }),
    ),
} satisfies E2ETest;
