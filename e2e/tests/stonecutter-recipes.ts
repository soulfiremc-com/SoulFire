import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
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

const EXPECTED = new Set([
  "minecraft:chiseled_quartz_block",
  "minecraft:quartz_bricks",
  "minecraft:quartz_pillar",
  "minecraft:quartz_slab",
  "minecraft:quartz_stairs",
]);

export default {
  name: "stonecutter-recipes",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 384 -16 416 16");
      yield* rcon("fill 398 63 -2 403 63 2 minecraft:stone");
      yield* rcon("fill 398 64 -2 403 67 2 minecraft:air");
      yield* rcon("setblock 401 64 0 minecraft:stonecutter");
      yield* placeBot(bot, { x: 400.5, y: 64, z: 0.5 }, -90);
      yield* rcon(`give ${BOT_NAME} minecraft:quartz_block 4`);
      yield* bot.inventory.open({ x: 401, y: 64, z: 0 });
      const input = yield* eventually(
        bot.inventoryState().pipe(
          Effect.map((state) =>
            state.slots.find((slot) => slot.itemId === "minecraft:quartz_block"),
          ),
          Effect.filterOrFail(
            (slot) => slot !== undefined,
            () => new CheckFailed({ message: "The quartz never reached the stonecutter menu" }),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(input.slot);
      yield* bot.clickInventory(0);
      const state = yield* eventually(
        bot.inventoryState().pipe(
          Effect.filterOrFail(
            (current) => current.slots.some((slot) => slot.slot === 0),
            () => new CheckFailed({ message: "The stonecutter input is empty" }),
          ),
        ),
        "5 seconds",
      );
      const buttons = state.layout?.buttons ?? [];
      yield* check(
        buttons.length === EXPECTED.size &&
          buttons.every((button) => EXPECTED.has(button.iconItemId ?? "")),
        `The stonecutter exposes missing or nonexistent quartz recipes: ${buttons.map((button) => button.iconItemId).join(", ")}`,
      );
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const invalid = yield* Effect.tryPromise({
        try: () => service.clickContainerButton({ ...scope, buttonId: buttons.length }),
        catch: (cause) => new CheckFailed({ message: `Container RPC failed: ${String(cause)}` }),
      });
      yield* check(!invalid.success, "A nonexistent stonecutter recipe was accepted");
      const recipe = buttons.find((button) => button.iconItemId === "minecraft:quartz_slab");
      if (!recipe) return yield* new CheckFailed({ message: "The quartz slab recipe is missing" });
      const selected = yield* Effect.tryPromise({
        try: () => service.clickContainerButton({ ...scope, buttonId: recipe.buttonId }),
        catch: (cause) => new CheckFailed({ message: `Container RPC failed: ${String(cause)}` }),
      });
      yield* check(selected.success, "The quartz slab recipe was rejected");
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((current) =>
            check(
              current.slots.some((slot) => slot.slot === 1 && slot.itemId === recipe.iconItemId),
              "The selected recipe did not produce quartz slabs",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* bot.transferInventorySlot(1);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((inventory) =>
            check(
              inventory.includes("minecraft:quartz_slab"),
              "Vanilla did not retain the crafted slabs",
            ),
          ),
        ),
        "5 seconds",
      );
      return undefined;
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
