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

const BEACON = { x: 821, y: 68, z: 0 };
const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({ try: call, catch: (cause) => new CheckFailed({ message: String(cause) }) });
export default {
  name: "beacon-effects",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 800 -16 848 16");
      yield* rcon("fill 816 64 -5 826 72 5 air");
      for (let layer = 0; layer < 4; layer++) {
        const radius = 4 - layer;
        yield* rcon(
          `fill ${821 - radius} ${64 + layer} ${-radius} ${821 + radius} ${64 + layer} ${radius} iron_block`,
        );
      }
      yield* rcon("setblock 821 68 0 beacon");
      yield* placeBot(bot, { x: 822.5, y: 68, z: 0.5 }, 90);
      yield* rcon(`give ${BOT_NAME} iron_ingot 2`);
      yield* bot.inventory.open(BEACON);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons.some(
                  (button) => button.buttonId === 9 && !button.disabled,
                ) === true,
                "The beacon pyramid never activated",
              ),
            ),
          ),
        "10 seconds",
      );
      const iron = (yield* bot.inventoryState()).slots.find(
        (slot) => slot.itemId === "minecraft:iron_ingot",
      );
      if (!iron) return yield* new CheckFailed({ message: "The beacon payment never arrived" });
      yield* bot.clickInventory(iron.slot);
      yield* bot.clickInventory(0);
      yield* bot.clickInventory(iron.slot);
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.slot === 0 && slot.itemId === "minecraft:iron_ingot"),
              "The beacon payment never reached its native slot",
            ),
          ),
        ),
        "5 seconds",
      );
      const service = client.service(BotService);
      const click = (buttonId: number) =>
        rpc(() =>
          service.clickContainerButton({ instanceId: bot.instanceId, botId: bot.id, buttonId }),
        );
      yield* check((yield* click(0)).success, "Beacon primary selection was rejected");
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.buttons.some((button) => button.buttonId === 0 && button.selected) ===
                  true,
                "The beacon primary selection was not synchronized",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* check((yield* click(-2)).success, "The amplified beacon effect was rejected");
      yield* check((yield* click(-1)).success, "Beacon confirmation was rejected");
      for (const field of ["primary_effect", "secondary_effect"]) {
        yield* eventually(
          rcon(`data get block 821 68 0 ${field}`).pipe(
            Effect.flatMap((data) =>
              check(
                data.includes("minecraft:speed"),
                "The confirmed beacon effect never reached vanilla",
              ),
            ),
          ),
          "5 seconds",
        );
      }
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.layout?.containerType === "inventory",
                "Beacon confirmation did not close its native menu",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* bot.inventory.open(BEACON);
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.layout?.buttons
                .filter((button) => button.selected)
                .map((button) => button.buttonId)
                .toSorted((a, b) => a - b)
                .join() === "-2,0",
              "The reopened beacon lost its primary and secondary selection",
            ),
          ),
        ),
        "5 seconds",
      );
      const remaining = (yield* bot.inventoryState()).slots.find(
        (slot) => slot.itemId === "minecraft:iron_ingot",
      );
      if (!remaining)
        return yield* new CheckFailed({ message: "Beacon confirmation consumed too much payment" });
      yield* check(remaining.count === 1, "Beacon confirmation consumed too much payment");
      yield* bot.transferInventorySlot(remaining.slot);
      yield* click(2);
      yield* check(!(yield* click(999)).success, "An unavailable beacon action was accepted");
      yield* check((yield* click(-3)).success, "Beacon cancellation was rejected");
      yield* check(
        (yield* rcon("data get block 821 68 0 primary_effect")).includes("minecraft:speed"),
        "Cancel applied the pending beacon effect",
      );
      return undefined;
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
