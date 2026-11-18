import { Code } from "@connectrpc/connect";
import { Effect, Result, Schedule } from "effect";
import { BotService, ClickType } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  present,
  rcon,
} from "../harness.ts";
import { nativeInput } from "../native-input.ts";

export default {
  name: "creative-inventory-controls",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const run = Effect.runPromiseWith(yield* Effect.context());
      yield* rcon("forceload add 1776 -16 1808 16");
      yield* rcon("fill 1782 63 -2 1788 63 2 stone");
      yield* rcon("fill 1782 64 -2 1788 67 2 air");
      yield* placeBot(bot, { x: 1784.5, y: 64, z: 0.5 });
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* bot.openInventory();
      yield* nativeInput(client, bot, async (input) => {
        await input.click(298, 36);
        await input.type("diamond sword");
        await run(
          eventually(
            bot.inventoryState().pipe(
              Effect.flatMap((state) =>
                check(
                  state.slots.some(
                    (slot) => slot.slot === 0 && slot.itemId === "minecraft:diamond_sword",
                  ),
                  "The native creative search did not load",
                ),
              ),
            ),
            "5 seconds",
          ),
        );
      });
      yield* bot.clickInventory(0).pipe(
        Effect.retry({
          schedule: Schedule.spaced("50 millis").pipe(Schedule.upTo({ duration: "2 seconds" })),
          while: (error) =>
            error._tag === "SoulFireRpcError" &&
            error.code === Code.PermissionDenied &&
            error.message.includes("leased"),
        }),
      );
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.carriedItem?.itemId === "minecraft:diamond_sword" &&
                  state.slots.some(
                    (slot) => slot.slot === 0 && slot.itemId === "minecraft:diamond_sword",
                  ),
                "The inventory RPC removed the creative catalog item instead of copying it",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* bot.clickInventory(45);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("Slot: 0b") && data.includes("minecraft:diamond_sword"),
              "The creative inventory RPC did not send the selected item to vanilla",
            ),
          ),
        ),
        "5 seconds",
      );
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const stateCheck = (
        predicate: (state: Awaited<ReturnType<typeof service.getInventoryState>>) => boolean,
        message: string,
      ) =>
        eventually(
          bot.inventoryState().pipe(Effect.flatMap((state) => check(predicate(state), message))),
          "5 seconds",
        );
      const state = yield* bot.inventoryState();
      yield* check(
        state.layout?.containerType === "creative" &&
          state.layout.regions.some(
            (region) =>
              region.id === "creative_catalog" &&
              region.startIndex === 0 &&
              region.slotCount === 45,
          ) &&
          state.layout.regions.some(
            (region) =>
              region.id === "player_hotbar" && region.startIndex === 45 && region.slotCount === 9,
          ),
        "The creative catalog is presented as ordinary player inventory",
      );
      const { buttons: tabs } = yield* present(
        state.layout,
        "The creative inventory has no layout",
      );
      const inventoryTab = tabs.find((button) => button.iconItemId === "minecraft:chest");
      const buildingTab = tabs.find((button) => button.iconItemId === "minecraft:bricks");
      if (!inventoryTab || !buildingTab)
        return yield* new CheckFailed({ message: "The native creative tabs are missing" });
      const search = (text: string) =>
        Effect.tryPromise({
          try: () => service.setContainerText({ ...scope, fieldId: "search", text }),
          catch: (cause) =>
            new CheckFailed({ message: `Creative search failed: ${String(cause)}` }),
        });
      yield* check(
        (yield* search("nether star")).success,
        "The creative search field rejected text",
      );
      yield* stateCheck(
        (current) =>
          current.slots.some((slot) => slot.slot === 0 && slot.itemId === "minecraft:nether_star"),
        "Creative search did not refresh the catalog",
      );
      yield* bot.clickInventory(0, ClickType.MIDDLE_CLICK);
      yield* stateCheck(
        (current) => current.carriedItem?.count === 64,
        "Creative clone did not copy a full stack",
      );
      yield* bot.clickInventory(-999, ClickType.RIGHT_CLICK);
      yield* stateCheck(
        (current) => current.carriedItem?.count === 63,
        "Dropping one cursor item used the wrong creative action",
      );
      yield* bot.clickInventory(-999, ClickType.LEFT_CLICK);
      yield* stateCheck(
        (current) => current.carriedItem === undefined,
        "Dropping the remaining cursor stack failed",
      );
      const droppedStarCount = rcon(
        `execute as @e[type=item,x=1776,y=62,z=-8,dx=32,dy=8,dz=16,nbt={Item:{id:"minecraft:nether_star"}}] run data get entity @s Item.count`,
      ).pipe(
        Effect.map((data) =>
          [...data.matchAll(/data: (\d+)/g)].reduce((sum, match) => sum + Number(match[1]), 0),
        ),
      );
      yield* eventually(
        droppedStarCount.pipe(
          Effect.flatMap((count) =>
            check(count === 64, "Vanilla did not retain the dropped creative cursor stack"),
          ),
        ),
        "5 seconds",
      );
      // Remove the verified drops before the pickup delay expires during later actions.
      yield* rcon(
        'kill @e[type=item,x=1776,y=62,z=-8,dx=32,dy=8,dz=16,nbt={Item:{id:"minecraft:nether_star"}}]',
      );
      yield* bot.clickInventory(0, ClickType.MIDDLE_CLICK);
      yield* bot.clickInventory(46);
      yield* bot.clickInventory(0, ClickType.SWAP_HOTBAR, 2);
      yield* bot.clickInventory(0, ClickType.SHIFT_LEFT_CLICK);
      yield* stateCheck(
        (current) => current.carriedItem?.count === 64,
        "Creative shift-click did not copy a full stack",
      );
      yield* bot.clickInventory(48);
      for (const slot of [1, 2, 3])
        yield* eventually(
          rcon(`data get entity ${BOT_NAME} Inventory[{Slot:${slot}b}].count`).pipe(
            Effect.flatMap((data) =>
              check(
                data.endsWith(" 64"),
                "Creative placement or hotbar swap did not reach vanilla",
              ),
            ),
          ),
          "5 seconds",
        );
      yield* check(
        Result.isFailure(yield* Effect.result(bot.clickInventory(54))),
        "An out-of-range creative slot was accepted",
      );
      yield* check(
        Result.isFailure(yield* Effect.result(bot.clickInventory(0, ClickType.SWAP_HOTBAR, 9))),
        "An invalid hotbar destination was accepted",
      );
      yield* bot.clickContainerButton(buildingTab.buttonId);
      const first = (yield* bot.inventoryState()).slots.find((slot) => slot.slot === 0)?.itemId;
      yield* bot.clickContainerButton(-2);
      yield* stateCheck(
        (current) => current.slots.find((slot) => slot.slot === 0)?.itemId !== first,
        "The next creative page did not scroll",
      );
      yield* bot.clickContainerButton(-1);
      yield* stateCheck(
        (current) => current.slots.find((slot) => slot.slot === 0)?.itemId === first,
        "The previous creative page did not restore its first row",
      );
      yield* check(
        Result.isFailure(yield* Effect.result(search("nether star"))),
        "A hidden creative search field accepted text",
      );
      yield* bot.clickContainerButton(inventoryTab.buttonId);
      yield* stateCheck(
        (current) =>
          current.layout?.totalSlots === 47 &&
          current.slots.some(
            (slot) => slot.slot === 36 && slot.itemId === "minecraft:diamond_sword",
          ) &&
          current.slots.some((slot) => slot.slot === 37 && slot.count === 64) &&
          current.layout.regions.every((region) => region.startIndex >= 5),
        "The creative inventory tab lost its native slot indices or exposes hidden crafting slots",
      );
      yield* check(
        Result.isFailure(yield* Effect.result(bot.clickInventory(0))),
        "A hidden creative crafting slot was accepted",
      );
      yield* bot.inventory.move({ sourceSlot: 37, destinationSlot: 9, count: 16 });
      for (const [slot, count] of [
        [1, 48],
        [9, 16],
      ])
        yield* eventually(
          rcon(`data get entity ${BOT_NAME} Inventory[{Slot:${slot}b}].count`).pipe(
            Effect.flatMap((data) =>
              check(
                data.endsWith(` ${count}`),
                "The inventory service did not move the creative inventory items correctly",
              ),
            ),
          ),
          "5 seconds",
        );
      yield* bot.clickInventory(9);
      yield* bot.clickInventory(-999, ClickType.RIGHT_CLICK);
      yield* stateCheck(
        (current) => current.carriedItem?.count === 15,
        "Drop One discarded the whole cursor stack on the creative inventory tab",
      );
      yield* eventually(
        droppedStarCount.pipe(
          Effect.flatMap((count) =>
            check(
              count === 1,
              "Vanilla did not receive exactly one item from the creative inventory tab",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(46);
      yield* stateCheck(
        (current) => !current.carriedItem && !current.slots.some((slot) => slot.slot === 9),
        "The creative trash slot did not destroy the carried stack",
      );
      yield* bot.clickInventory(46, ClickType.SHIFT_LEFT_CLICK);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.endsWith("[]"),
              "Shift-clicking creative trash did not clear vanilla inventory",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* bot.closeContainer();
      return undefined;
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
      Effect.ensuring(
        rcon(
          'kill @e[type=item,x=1776,y=62,z=-8,dx=32,dy=8,dz=16,nbt={Item:{id:"minecraft:nether_star"}}]',
          { check: false },
        ).pipe(Effect.ignore),
      ),
    ),
} satisfies E2ETest;
