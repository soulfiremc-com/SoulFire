import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
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

const STAND = { x: 3819, y: 64, z: 8 };
const FURNACE = { x: 3819, y: 64, z: 10 };

export default {
  name: "recipe-task-handoff",
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
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 3818.5, y: 64, z: 8.5 }, -90);
        yield* rcon("setblock 3819 64 8 brewing_stand");
        yield* rcon("setblock 3819 64 10 furnace");
        for (const item of [
          'potion[minecraft:potion_contents={potion:"minecraft:water"}] 2',
          "nether_wart 2",
          "blaze_powder 2",
          "oak_log",
          "iron_ore",
          "coal",
        ]) {
          yield* rcon(`give ${BOT_NAME} minecraft:${item}`);
        }
        const recipe = yield* eventually(
          bot.recipes.list({ resultItemId: "minecraft:oak_planks" }).pipe(
            Effect.map((r) => r.recipes[0]),
            Effect.filterOrFail(
              (r) => r !== undefined,
              () => new CheckFailed({ message: "The plank recipe never became available" }),
            ),
          ),
          "5 seconds",
        );
        const owner = yield* bot.tasks.brew(
          { itemIds: ["minecraft:potion"] },
          { itemIds: ["minecraft:nether_wart"] },
          1,
          { station: STAND, priority: BotTaskPriority.HIGH },
        );
        yield* Effect.addFinalizer(() => owner.cancel("test cleanup").pipe(Effect.ignore));
        yield* eventually(
          rcon("data get block 3819 64 8 BrewTime").pipe(
            Effect.flatMap((text) =>
              check(
                Number(text.match(/: (\d+)$/u)?.[1]) > 0,
                "Vanilla never started the owner brew",
              ),
            ),
          ),
          "15 seconds",
          "100 millis",
        );
        const opened = yield* bot.inventory.snapshot();
        yield* check(
          opened.containerType === "brewing",
          "The owner did not open its brewing stand",
        );
        for (const kind of ["craft", "smelt", "brew"] as const) {
          const options = { conflictPolicy: BotTaskConflictPolicy.QUEUE };
          const queued =
            kind === "craft"
              ? yield* bot.tasks.craft(recipe.recipeId, 1, options)
              : kind === "smelt"
                ? yield* bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, 1, {
                    ...options,
                    station: FURNACE,
                  })
                : yield* bot.tasks.brew(
                    { itemIds: ["minecraft:potion"] },
                    { itemIds: ["minecraft:nether_wart"] },
                    1,
                    { ...options, station: STAND },
                  );
          yield* Effect.addFinalizer(() => queued.cancel("test cleanup").pipe(Effect.ignore));
          yield* eventually(
            queued
              .refresh()
              .pipe(
                Effect.flatMap((t) =>
                  check(
                    t.status === BotTaskStatus.WAITING_FOR_RESOURCES,
                    `${kind} did not wait for the brewing owner`,
                  ),
                ),
              ),
            "3 seconds",
            "20 millis",
          );
          yield* queued.cancel("Cancel a recipe that never owned the menu");
          yield* queued.wait({ call: { timeoutMs: 5000 } });
          yield* Effect.sleep("200 millis");
          const current = yield* bot.inventory.snapshot();
          yield* check(
            current.menuId === opened.menuId && current.containerType === "brewing",
            `Cancelling queued ${kind} closed the owner's brewing stand`,
          );
          yield* check(
            (yield* owner.refresh()).status === BotTaskStatus.RUNNING,
            `Cancelling queued ${kind} stopped the owner brew`,
          );
        }
        const brewed = yield* owner.result({ call: { timeoutMs: 25000 } });
        yield* check(
          brewed.outputs.length === 1 &&
            brewed.outputs[0]?.potion?.potionId === "minecraft:awkward",
          "The owner did not collect its brewed potion",
        );
        const potion = yield* rcon(
          `data get entity ${BOT_NAME} Inventory[{id:"minecraft:potion",components:{"minecraft:potion_contents":{potion:"minecraft:awkward"}}}].count`,
        );
        yield* check(
          Number(potion.match(/: (\d+)$/u)?.[1]) === 1,
          "Vanilla did not deliver the brewed potion",
        );
        const craft = yield* bot.tasks.craft(recipe.recipeId);
        yield* Effect.addFinalizer(() => craft.cancel("test cleanup").pipe(Effect.ignore));
        const crafted = yield* craft.result({ call: { timeoutMs: 10000 } });
        yield* check(
          crafted.crafted === 1 && crafted.result?.itemId === "minecraft:oak_planks",
          "An active crafting task did not produce planks",
        );
        const smelt = yield* bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, 1, {
          station: FURNACE,
          fuel: { itemIds: ["minecraft:coal"] },
        });
        yield* Effect.addFinalizer(() => smelt.cancel("test cleanup").pipe(Effect.ignore));
        const smelted = yield* smelt.result({ call: { timeoutMs: 20000 } });
        yield* check(
          smelted.outputs.some(
            (item) => item.itemId === "minecraft:iron_ingot" && item.count === 1,
          ),
          "An active smelting task did not produce its ingot",
        );
        yield* check(
          (yield* bot.inventory.snapshot()).containerType === "inventory",
          "Completed recipe tasks left their menu open",
        );
        for (const [item, count] of [
          ["oak_planks", 4],
          ["iron_ingot", 1],
        ] as const) {
          const actual = yield* rcon(
            `data get entity ${BOT_NAME} Inventory[{id:"minecraft:${item}"}].count`,
          );
          yield* check(
            Number(actual.match(/: (\d+)$/u)?.[1]) === count,
            `Vanilla did not deliver ${item}: ${actual}`,
          );
        }
      }),
    ),
} satisfies E2ETest;
