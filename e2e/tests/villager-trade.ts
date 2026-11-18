import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
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

const TAG = "sf_e2e_trade";

export default {
  name: "villager-trade",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon(`kill @e[type=minecraft:villager,tag=${TAG}]`, { check: false });
      yield* rcon("forceload add 480 -16 528 16");
      yield* rcon("fill 498 63 -2 503 63 2 minecraft:stone");
      yield* rcon("fill 498 64 -2 503 67 2 minecraft:air");
      yield* placeBot(bot, { x: 500.5, y: 64, z: 0.5 }, -90);
      yield* rcon(
        `summon minecraft:villager 501.5 64 0.5 {Tags:["${TAG}"],NoAI:1b,Invulnerable:1b,VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:8},sell:{id:"minecraft:bread",count:1},maxUses:999,priceMultiplier:0.25f},{buy:{id:"minecraft:emerald",count:8},sell:{id:"minecraft:apple",count:2},maxUses:999,demand:3,priceMultiplier:0.25f},{buy:{id:"minecraft:emerald",count:8},buyB:{id:"minecraft:diamond",count:2},sell:{id:"minecraft:golden_apple",count:1},maxUses:999,demand:100,priceMultiplier:0.25f}]}}`,
      );
      yield* rcon(`give ${BOT_NAME} minecraft:emerald 64`);
      yield* eventually(
        bot.inventory
          .count({ selector: { itemIds: ["minecraft:emerald"] } })
          .pipe(
            Effect.flatMap((count) => check(count === 64n, "The emeralds never reached the bot")),
          ),
        "5 seconds",
      );
      const target = yield* eventually(
        bot.world
          .queryEntities({ radius: 4, selector: { entityTypes: ["minecraft:villager"] } })
          .pipe(
            Effect.map((result) => result.entities[0]?.reference),
            Effect.filterOrFail(
              (reference) => reference !== undefined,
              () => new CheckFailed({ message: "The villager never became observable" }),
            ),
          ),
        "5 seconds",
      );
      yield* bot.interactEntity({
        entityId: target.networkId,
        connectionEpoch: target.connectionEpoch,
        hand: Hand.MAIN,
      });
      const state = yield* eventually(
        bot.inventoryState().pipe(
          Effect.filterOrFail(
            (current) =>
              current.layout?.containerType === "merchant" && current.layout.buttons.length === 3,
            () => new CheckFailed({ message: "The villager trade menu did not open" }),
          ),
        ),
        "5 seconds",
      );
      const assertPrices = () =>
        Effect.gen(function* () {
          const offers = (yield* bot.recipes.listVillagerTrades()).offers;
          const buttons = (yield* bot.inventoryState()).layout?.buttons ?? [];
          for (const offer of offers) {
            const button = buttons.find((candidate) => candidate.buttonId === offer.offerIndex);
            const displayed = Array.from(button?.description?.matchAll(/(\d+)x/g) ?? [], (match) =>
              Number(match[1]),
            );
            const expected = [offer.firstCost!.count];
            if (offer.secondCost) expected.push(offer.secondCost.count);
            yield* check(
              displayed.length === expected.length &&
                displayed.every((count, index) => count === expected[index]),
              `Trade ${offer.offerIndex} advertises ${displayed.join(", ")} but vanilla requires ${expected.join(", ")}`,
            );
          }
          return offers;
        });
      const offers = yield* assertPrices();
      yield* check(
        offers[1]?.firstCost?.count === 14 && offers[2]?.firstCost?.count === 64,
        "The fixture did not produce demand-adjusted and clamped prices",
      );
      const trade = state.layout?.buttons.find((button) => button.iconItemId === "minecraft:apple");
      if (!trade) return yield* new CheckFailed({ message: "The apple trade is missing" });
      const selected = yield* Effect.tryPromise({
        try: () =>
          client.service(BotService).clickContainerButton({
            instanceId: bot.instanceId,
            botId: bot.id,
            buttonId: trade.buttonId,
          }),
        catch: (cause) => new CheckFailed({ message: `Trade selection failed: ${String(cause)}` }),
      });
      yield* check(selected.success, "Trade selection was rejected");
      const selection = (yield* bot.inventoryState()).layout?.buttons.filter(
        (button) => button.selected,
      );
      yield* check(
        selection?.length === 1 && selection[0]?.buttonId === trade.buttonId,
        "The inventory controls did not reflect the selected trade",
      );
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((current) =>
            check(
              current.slots.some(
                (slot) => slot.slot === 2 && slot.itemId === "minecraft:apple" && slot.count === 2,
              ),
              "The selected trade never filled the payment and result slots",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(2);
      yield* bot.clickInventory(3);
      yield* bot.closeContainer();
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((inventory) =>
            check(
              inventory.includes("minecraft:apple") && inventory.includes("minecraft:emerald"),
              "Vanilla did not retain the trade result and remaining payment",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        (yield* rcon(`clear ${BOT_NAME} minecraft:emerald 0`)).includes("Found 50"),
        "Vanilla did not charge the advertised demand-adjusted price",
      );
      const uses = yield* rcon(
        `data get entity @e[type=minecraft:villager,tag=${TAG},limit=1] Offers.Recipes[1].uses`,
      );
      yield* check(
        uses.endsWith("data: 1"),
        "Vanilla did not count exactly one use of the selected offer",
      );

      yield* rcon(`effect give ${BOT_NAME} minecraft:hero_of_the_village 120 1 true`);
      yield* bot.interactEntity({
        entityId: target.networkId,
        connectionEpoch: target.connectionEpoch,
        hand: Hand.MAIN,
      });
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((current) =>
              check(current.layout?.buttons.length === 3, "Offers not ready"),
            ),
          ),
        "5 seconds",
      );
      const discounted = yield* assertPrices();
      yield* check(
        discounted[0]?.firstCost?.count === 6,
        "The fixture did not apply the hero discount",
      );
      const bread = yield* Effect.tryPromise({
        try: () =>
          client.service(BotService).clickContainerButton({
            instanceId: bot.instanceId,
            botId: bot.id,
            buttonId: 0,
          }),
        catch: (cause) => new CheckFailed({ message: String(cause) }),
      });
      yield* check(bread.success, "The discounted offer was rejected");
      const reopenedSelection = (yield* bot.inventoryState()).layout?.buttons.filter(
        (button) => button.selected,
      );
      yield* check(
        reopenedSelection?.length === 1 && reopenedSelection[0]?.buttonId === 0,
        "Reopening the merchant retained a different offer selection",
      );
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((current) =>
            check(
              current.slots.some((slot) => slot.slot === 2 && slot.itemId === "minecraft:bread"),
              "Discounted output missing",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(2);
      yield* bot.clickInventory(4);
      yield* bot.closeContainer();
      yield* eventually(
        rcon(`clear ${BOT_NAME} minecraft:emerald 0`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("Found 44"),
              "Vanilla did not charge exactly six emeralds for the discounted trade",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        (yield* rcon(`clear ${BOT_NAME} minecraft:bread 0`)).includes("Found 1"),
        "The discounted bread was not stored exactly once",
      );
      return undefined;
    }).pipe(
      Effect.ensuring(
        bot
          .closeContainer()
          .pipe(
            Effect.ignore,
            Effect.andThen(
              rcon(`effect clear ${BOT_NAME} minecraft:hero_of_the_village`, { check: false }).pipe(
                Effect.ignore,
              ),
            ),
            Effect.andThen(
              rcon(`kill @e[type=minecraft:villager,tag=${TAG}]`, { check: false }).pipe(
                Effect.ignore,
              ),
            ),
          ),
      ),
    ),
} satisfies E2ETest;
