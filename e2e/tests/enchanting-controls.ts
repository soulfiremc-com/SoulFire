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

export default {
  name: "enchanting-controls",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1008 -16 1040 16");
      yield* rcon("fill 1018 63 -3 1024 63 3 stone");
      yield* rcon("fill 1018 64 -3 1024 68 3 air");
      let shelves = 0;
      for (let x = -2; x <= 2; x++)
        for (let z = -2; z <= 2; z++) {
          if (Math.max(Math.abs(x), Math.abs(z)) !== 2 || shelves === 15) continue;
          yield* rcon(`setblock ${1021 + x} 64 ${z} bookshelf`);
          shelves++;
        }
      yield* rcon("setblock 1021 64 0 enchanting_table");
      yield* placeBot(bot, { x: 1022.5, y: 64, z: 0.5 }, 90);
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* rcon(`experience set ${BOT_NAME} 0 levels`);
      yield* bot.inventory.open({ x: 1021, y: 64, z: 0 });
      const put = (item: string, destination: number, count = 1) =>
        Effect.gen(function* () {
          yield* rcon(`give ${BOT_NAME} ${item} ${count}`);
          const slot = yield* eventually(
            bot.inventoryState().pipe(
              Effect.map((state) =>
                state.slots.find(
                  (candidate) => candidate.slot > 1 && candidate.itemId === `minecraft:${item}`,
                ),
              ),
              Effect.filterOrFail(
                (found) => found !== undefined,
                () => new CheckFailed({ message: "The enchanting material never arrived" }),
              ),
            ),
            "5 seconds",
          );
          yield* bot.clickInventory(slot.slot);
          yield* bot.clickInventory(destination);
        });
      yield* put("lapis_lazuli", 1, 3);
      yield* put("stone", 0);
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.slot === 0 && slot.itemId === "minecraft:stone"),
              "The non-enchantable item never arrived",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        (yield* bot.inventoryState()).layout?.buttons.every((button) => button.disabled) === true,
        "A non-enchantable item exposes active enchantments",
      );
      const service = client.service(BotService);
      const click = (buttonId: number) =>
        Effect.tryPromise({
          try: () =>
            service.clickContainerButton({ instanceId: bot.instanceId, botId: bot.id, buttonId }),
          catch: (cause) => new CheckFailed({ message: String(cause) }),
        });
      yield* check(!(yield* click(0)).success, "An unavailable enchantment reports success");
      yield* bot.transferInventorySlot(0);
      yield* put("iron_sword", 0);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons.every((button) => button.disabled) === true,
                "Enchantments ignore the player's experience requirement",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* rcon(`experience set ${BOT_NAME} 29 levels`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons[0]?.disabled === false &&
                  state.layout.buttons[2]?.disabled === true,
                "The highest offer ignores its synchronized level requirement",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* rcon(`experience set ${BOT_NAME} 30 levels`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons.every((button) => !button.disabled) === true,
                "Eligible enchantments remain disabled",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* check((yield* click(2)).success, "The survival enchantment was rejected");
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} XpLevel`).pipe(
          Effect.flatMap((data) =>
            check(data.endsWith("27"), "The survival enchantment did not consume three levels"),
          ),
        ),
        "5 seconds",
      );
      yield* bot.transferInventorySlot(0);
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon(`experience set ${BOT_NAME} 0 levels`);
      yield* put("iron_pickaxe", 0);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons.every((button) => !button.disabled) === true &&
                  !state.slots.some((slot) => slot.slot === 1),
                "Creative enchanting incorrectly requires currency or experience",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* check((yield* click(2)).success, "The creative enchantment was rejected");
      yield* bot.transferInventorySlot(0);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("minecraft:enchantments") && data.includes("minecraft:iron_pickaxe"),
              "Vanilla did not retain the creative enchantment",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
