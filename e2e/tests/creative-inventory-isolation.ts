import type { SoulFireBot } from "@soulfiremc/sdk";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";
import { nativeInput } from "../native-input.ts";

const OTHER = "E2ECreative2";

const firstItem = (target: SoulFireBot, itemId: string) =>
  eventually(
    target.inventoryState().pipe(
      Effect.flatMap((state) =>
        check(
          state.slots.some((slot) => slot.slot === 0 && slot.itemId === itemId),
          "The creative search results changed or did not load",
        ),
      ),
    ),
    "5 seconds",
    "100 millis",
  );

export default {
  name: "creative-inventory-isolation",
  run: ({ bot, client }) => {
    return Effect.gen(function* () {
      const effectContext = yield* Effect.context();
      const run = Effect.runPromiseWith(effectContext);
      const instance = client.instance(bot.instanceId);
      const cleanup: { otherId?: string } = {};
      yield* Effect.addFinalizer(() =>
        cleanup.otherId === undefined
          ? Effect.void
          : instance.removeAccounts([cleanup.otherId]).pipe(Effect.ignore),
      );
      const other = yield* instance.getOrCreateBot(OTHER, { readyTimeoutMs: 30_000 });
      cleanup.otherId = other.id;
      yield* rcon("forceload add 1728 -16 1760 16");
      yield* rcon("fill 1742 63 -2 1748 63 2 stone");
      yield* rcon("fill 1742 64 -2 1748 67 2 air");
      yield* placeBot(bot, { x: 1744.5, y: 64, z: 0.5 });
      yield* rcon(`clear ${OTHER}`, { check: false });
      yield* rcon(`tp ${OTHER} 1746.5 64 0.5`);
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon(`gamemode creative ${OTHER}`);
      yield* bot.openInventory();
      yield* nativeInput(client, bot, async (input) => {
        await input.click(298, 36);
        await input.type("diamond sword");
        await run(firstItem(bot, "minecraft:diamond_sword"));
      });
      yield* other.openInventory();
      yield* nativeInput(client, other, async (input) => {
        await input.click(298, 36);
        await input.type("nether star");
        await run(firstItem(other, "minecraft:nether_star"));
        await input.click(298, 204);
      });
      yield* firstItem(bot, "minecraft:diamond_sword");
      yield* nativeInput(client, bot, async (input) => {
        await input.click(133, 78);
        await input.click(133, 172);
        await input.escape();
      });
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("minecraft:diamond_sword") && !data.includes("minecraft:nether_star"),
              "Another bot's creative search changed the item picked by the first bot",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* nativeInput(client, other, async (input) => {
        await input.click(298, 36);
        await input.type("nether star");
        await run(firstItem(other, "minecraft:nether_star"));
        await input.click(133, 78);
        await input.click(133, 172);
        await input.escape();
      });
      yield* eventually(
        rcon(`data get entity ${OTHER} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("minecraft:nether_star") && !data.includes("minecraft:diamond_sword"),
              "The second bot did not retain its own creative selection",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
      Effect.scoped,
    );
  },
} satisfies E2ETest;
