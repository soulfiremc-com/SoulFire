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

const hasHelmet = (item: string) =>
  rcon(`execute if items entity ${BOT_NAME} armor.head minecraft:${item}`, {
    check: false,
  }).pipe(Effect.flatMap((reply) => check(reply.startsWith("Test passed"), reply)));

export default {
  name: "runtime-settings",
  run: ({ bot, client }) => {
    const service = client.service(BotService);
    const enabled = (value: boolean) =>
      Effect.tryPromise({
        try: () =>
          service.updateBotConfigEntry({
            instanceId: bot.instanceId,
            botId: bot.id,
            namespace: "auto-armor",
            key: "enabled",
            value: { kind: { case: "boolValue", value } },
          }),
        catch: (cause) =>
          new CheckFailed({ message: `Changing bot settings failed: ${String(cause)}` }),
      });
    const giveUpgrade = Effect.gen(function* () {
      yield* rcon(`clear ${BOT_NAME}`, { check: false });
      yield* rcon(`item replace entity ${BOT_NAME} armor.head with minecraft:leather_helmet`);
      yield* rcon(`item replace entity ${BOT_NAME} inventory.0 with minecraft:diamond_helmet`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some(
                  (slot) => slot.slot === 5 && slot.itemId === "minecraft:leather_helmet",
                ) &&
                  state.slots.some(
                    (slot) => slot.slot === 9 && slot.itemId === "minecraft:diamond_helmet",
                  ),
                "The armor fixture never reached the bot",
              ),
            ),
          ),
        "5 seconds",
      );
    });
    return Effect.gen(function* () {
      yield* rcon("forceload add 864 0");
      yield* rcon("fill 864 63 0 868 63 4 stone");
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
      yield* giveUpgrade;
      // The instance keeps automation disabled. Only the per-bot RPC changes its behavior.
      yield* enabled(true);
      yield* eventually(hasHelmet("diamond_helmet"), "6 seconds");
      yield* enabled(false);
      yield* giveUpgrade;
      yield* Effect.sleep("3 seconds");
      yield* hasHelmet("leather_helmet");
      yield* enabled(true);
      yield* eventually(hasHelmet("diamond_helmet"), "6 seconds");
      yield* check(
        (yield* bot.world.player()).position !== undefined,
        "The bot stopped responding after settings updates",
      );
    }).pipe(
      Effect.ensuring(
        enabled(false).pipe(
          Effect.andThen(rcon(`clear ${BOT_NAME}`, { check: false })),
          Effect.andThen(rcon(`gamemode creative ${BOT_NAME}`)),
          // Leave the platform before removing it, or the bot falls out of the world.
          Effect.andThen(placeBot(bot, { x: 0.5, y: 64, z: 0.5 })),
          Effect.andThen(rcon("fill 864 63 0 868 63 4 air")),
          Effect.andThen(rcon("forceload remove 864 0")),
          Effect.ignore,
        ),
      ),
    );
  },
} satisfies E2ETest;
