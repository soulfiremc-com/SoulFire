import { BotService, SlotRegionType } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
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

const CRAFTER = { x: 621, y: 64, z: 0 };
const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({ try: call, catch: (cause) => new CheckFailed({ message: String(cause) }) });
export default {
  name: "crafter-controls",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      yield* rcon("forceload add 608 -16 640 16");
      yield* rcon("fill 618 63 -2 623 63 2 stone");
      yield* rcon("fill 618 64 -2 623 67 2 air");
      yield* rcon("setblock 621 64 0 crafter");
      yield* placeBot(bot, { x: 620.5, y: 64, z: 0.5 }, -90);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with diamond`);
      yield* bot.inventory.open(CRAFTER);
      const toggle = () => rpc(() => service.clickContainerButton({ ...scope, buttonId: 0 }));
      yield* check((yield* toggle()).success, "The empty crafter slot toggle was rejected");
      yield* eventually(
        rcon("data get block 621 64 0 disabled_slots").pipe(
          Effect.flatMap((data) =>
            check(/\[I;\s*0\]/u.test(data), "The crafter slot toggle never reached vanilla"),
          ),
        ),
        "5 seconds",
      );
      yield* bot.closeContainer();
      yield* bot.inventory.open(CRAFTER);
      const state = yield* bot.inventoryState();
      yield* check(
        state.layout?.buttons.find((button) => button.buttonId === 0)?.selected === true,
        "The reopened crafter lost its disabled-slot state",
      );
      const hotbar = state.layout?.regions.find(
        (region) => region.type === SlotRegionType.SLOT_REGION_HOTBAR,
      );
      const diamond = state.slots.find((slot) => slot.itemId === "minecraft:diamond");
      if (!diamond)
        return yield* new CheckFailed({ message: "The player hotbar item never arrived" });
      yield* check(
        hotbar?.startIndex === diamond.slot,
        "The crafter hotbar is shifted away from the real player slots",
      );
      const preview = state.layout?.regions.find(
        (region) => region.type === SlotRegionType.SLOT_REGION_DISPLAY,
      );
      yield* check(
        preview?.startIndex === 45 && preview.slotCount === 1,
        "The crafter preview is exposed as an interactive inventory slot",
      );
      yield* toggle();
      yield* eventually(
        rcon("data get block 621 64 0 disabled_slots").pipe(
          Effect.flatMap((data) =>
            check(/\[I;\s*\]/u.test(data), "The crafter slot never became enabled again"),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(0);
      yield* eventually(
        rcon("data get block 621 64 0 disabled_slots").pipe(
          Effect.flatMap((data) =>
            check(/\[I;\s*0\]/u.test(data), "Clicking an empty crafter slot did not disable it"),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(diamond.slot);
      yield* bot.clickInventory(0);
      yield* eventually(
        rcon("data get block 621 64 0 Items").pipe(
          Effect.flatMap((data) =>
            check(
              data.includes("minecraft:diamond"),
              "Placing an item did not enable and fill the disabled slot",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* rcon("item replace block 621 64 0 container.0 with oak_planks");
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((current) =>
            check(
              current.slots.some(
                (slot) => slot.slot === 45 && slot.itemId === "minecraft:oak_button",
              ),
              "The crafter recipe preview never synchronized",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* check(!(yield* toggle()).success, "An occupied crafter slot could be disabled");
      return undefined;
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
