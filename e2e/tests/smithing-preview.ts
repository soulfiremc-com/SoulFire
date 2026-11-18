import { Effect } from "effect";
import { PNG } from "pngjs";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

export default {
  name: "smithing-preview",
  run: ({ bot }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1696 -16 1728 16");
      yield* rcon("fill 1710 63 -2 1715 63 2 stone");
      yield* rcon("fill 1710 64 -2 1715 67 2 air");
      yield* rcon("setblock 1713 64 0 smithing_table");
      yield* placeBot(bot, { x: 1712.5, y: 64, z: 0.5 }, -90);
      yield* bot.inventory.open({ x: 1713, y: 64, z: 0 });
      const materials = ["coast_armor_trim_smithing_template", "diamond_chestplate", "emerald"];
      for (const [destination, item] of materials.entries()) {
        yield* rcon(`give ${BOT_NAME} minecraft:${item}`);
        const slot = yield* eventually(
          bot.inventoryState().pipe(
            Effect.map((state) =>
              state.slots.find(
                (candidate) => candidate.slot >= 4 && candidate.itemId === `minecraft:${item}`,
              ),
            ),
            Effect.filterOrFail(
              (found) => found !== undefined,
              () => new CheckFailed({ message: "A smithing material did not arrive" }),
            ),
          ),
          "5 seconds",
        );
        yield* bot.clickInventory(slot.slot);
        yield* bot.clickInventory(destination);
      }
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some(
                (slot) => slot.slot === 3 && slot.itemId === "minecraft:diamond_chestplate",
              ),
              "The trimmed armor result did not arrive",
            ),
          ),
        ),
        "5 seconds",
      );
      const frame = yield* bot.renderPov({ width: 1280, height: 720, maxDistance: 16 });
      const image = yield* Effect.try({
        try: () => PNG.sync.read(Buffer.from(frame.imageBase64, "base64")),
        catch: (cause) => new CheckFailed({ message: `Invalid preview image: ${String(cause)}` }),
      });
      // Vanilla's 176x166 menu uses GUI scale 3 at 1280x720. Only inspect
      // the armor-stand preview, excluding the item slots and tooltips.
      let diamondPixels = 0;
      let emeraldPixels = 0;
      for (let y = 171; y < 351; y++) {
        for (let x = 738; x < 858; x++) {
          const offset = (y * image.width + x) * 4;
          const r = image.data[offset]!;
          const g = image.data[offset + 1]!;
          const b = image.data[offset + 2]!;
          if (g > 100 && g > r + 35 && b > r + 35) diamondPixels++;
          if (g > 90 && g > r + 30 && g > b + 30) emeraldPixels++;
        }
      }
      yield* check(
        diamondPixels > 250 && emeraldPixels > 30,
        `The native armor preview lost its diamond or emerald texture (${diamondPixels} diamond, ${emeraldPixels} emerald pixels)`,
      );
      yield* bot.transferInventorySlot(3);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("minecraft:trim") &&
                data.includes("minecraft:coast") &&
                data.includes("minecraft:emerald"),
              "The crafted trim did not reach vanilla inventory",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
