import type { InventorySlot } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
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

const STAND = { x: 1649, y: 64, z: 0 };
const label = (slot: InventorySlot) => slot.tooltipLines[0] ?? slot.displayName ?? slot.itemId;

export default {
  name: "brewing-metadata",
  run: ({ bot }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1632 -16 1664 16");
      yield* rcon("fill 1646 63 -2 1651 63 2 stone");
      yield* rcon("fill 1646 64 -2 1651 67 2 air");
      yield* rcon("setblock 1649 64 0 brewing_stand");
      yield* placeBot(bot, { x: 1648.5, y: 64, z: 0.5 }, -90);
      yield* rcon(
        `give ${BOT_NAME} minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}] 3`,
      );
      yield* rcon(`give ${BOT_NAME} minecraft:blaze_powder`);
      yield* rcon(`give ${BOT_NAME} minecraft:nether_wart`);
      yield* bot.inventory.open(STAND);
      const water = yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) => {
            const potions = state.slots.filter(
              (s) => s.slot >= 5 && s.itemId === "minecraft:potion",
            );
            const [potion] = potions;
            return potions.length === 3 && potion !== undefined
              ? Effect.succeed(potion)
              : Effect.fail(new CheckFailed({ message: "The water bottles did not arrive" }));
          }),
        ),
        "5 seconds",
      );
      for (let bottle = 0; bottle < 3; bottle++) {
        const potion = yield* eventually(
          bot.inventoryState().pipe(
            Effect.map((s) => s.slots.find((v) => v.slot >= 5 && v.itemId === "minecraft:potion")),
            Effect.filterOrFail(
              (v) => v !== undefined,
              () => new CheckFailed({ message: "A water bottle disappeared before placement" }),
            ),
          ),
          "5 seconds",
        );
        yield* bot.transferInventorySlot(potion.slot);
      }
      for (const item of ["blaze_powder", "nether_wart"]) {
        const material = yield* eventually(
          bot.inventoryState().pipe(
            Effect.map((s) => s.slots.find((v) => v.slot >= 5 && v.itemId === `minecraft:${item}`)),
            Effect.filterOrFail(
              (v) => v !== undefined,
              () => new CheckFailed({ message: "A brewing material disappeared" }),
            ),
          ),
          "5 seconds",
        );
        yield* bot.transferInventorySlot(material.slot);
      }
      yield* eventually(
        rcon("data get block 1649 64 0 Items").pipe(
          Effect.flatMap((data) =>
            check(
              (data.match(/minecraft:awkward/g) ?? []).length === 3,
              "Vanilla did not brew all three awkward potions",
            ),
          ),
        ),
        "30 seconds",
        "500 millis",
      );
      const brewed = yield* eventually(
        bot.inventoryState().pipe(
          Effect.filterOrFail(
            (s) =>
              [0, 1, 2].every((index) =>
                s.slots.some(
                  (v) => v.slot === index && v.itemId === "minecraft:potion" && v.count === 1,
                ),
              ),
            () => new CheckFailed({ message: "The brewed bottles did not synchronize" }),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        brewed.slots
          .filter((s) => s.slot < 3)
          .every(
            (s) =>
              label(s) !== label(water) && s.tooltipLines.length > 1 && s.displayName === undefined,
          ),
        "Water bottles and brewed potions have indistinguishable labels or missing native details",
      );
      const brewedPotion = yield* present(brewed.slots[0], "The brewed potion slot is missing");
      yield* bot.clickInventory(0);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.carriedItem?.tooltipLines.length === brewedPotion.tooltipLines.length &&
                  label(state.carriedItem) === label(brewedPotion),
                "Picking up the brewed potion lost its native tooltip",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* bot.clickInventory(0);
      for (const slot of [0, 1, 2]) yield* bot.transferInventorySlot(slot);
      yield* eventually(
        bot.info().pipe(
          Effect.flatMap((info) => {
            const potions = info.liveState?.inventory.filter(
              (s) => s.itemId === "minecraft:potion",
            );
            return check(
              potions?.length === 3 && potions.every((s) => label(s) !== label(water)),
              "Bot info lost the brewed item names",
            );
          }),
        ),
        "5 seconds",
      );
      for (const enchantment of ["mending", "unbreaking"]) {
        yield* rcon(
          `give ${BOT_NAME} minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:${enchantment}":1}]`,
        );
      }
      const books = yield* eventually(
        bot.inventoryState().pipe(
          Effect.map((s) => s.slots.filter((v) => v.itemId === "minecraft:enchanted_book")),
          Effect.filterOrFail(
            (v) => v.length === 2,
            () => new CheckFailed({ message: "The enchanted books did not arrive" }),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        books.every((b) => b.tooltipLines.length > 1) &&
          JSON.stringify(books[0]?.tooltipLines) !== JSON.stringify(books[1]?.tooltipLines),
        "Enchanted books lost their distinct native enchantment details",
      );
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
