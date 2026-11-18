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
  name: "loom-patterns",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 704 -16 736 16");
      yield* rcon("fill 718 63 -2 723 63 2 stone");
      yield* rcon("fill 718 64 -2 723 67 2 air");
      yield* rcon("setblock 721 64 0 loom");
      yield* placeBot(bot, { x: 720.5, y: 64, z: 0.5 }, -90);
      yield* Effect.acquireRelease(
        client
          .instance(bot.instanceId)
          .getOrCreateBot("E2ERegistryPeer", { readyTimeoutMs: 30_000 }),
        (peer) => peer.stop().pipe(Effect.ignore),
      );
      yield* bot.inventory.open({ x: 721, y: 64, z: 0 });
      yield* check(
        (yield* bot.inventoryState()).layout?.buttons.length === 0,
        "An empty loom exposes nonexistent patterns",
      );
      for (const item of ["white_banner", "red_dye", "creeper_banner_pattern"]) {
        yield* rcon(`give ${BOT_NAME} ${item}`);
        const slot = yield* eventually(
          bot.inventoryState().pipe(
            Effect.map((state) =>
              state.slots.find((candidate) => candidate.itemId === `minecraft:${item}`),
            ),
            Effect.filterOrFail(
              (found) => found !== undefined,
              () => new CheckFailed({ message: "The loom material never arrived" }),
            ),
          ),
          "5 seconds",
        );
        yield* bot.transferInventorySlot(slot.slot);
      }
      const state = yield* eventually(
        bot.inventoryState().pipe(
          Effect.filterOrFail(
            (current) =>
              current.slots.some(
                (slot) => slot.slot === 3 && slot.itemId === "minecraft:white_banner",
              ),
            () => new CheckFailed({ message: "The loom preview never arrived" }),
          ),
        ),
        "5 seconds",
      );
      yield* check(
        state.layout?.buttons.length === 1 && state.layout.buttons[0]?.selected === true,
        "The supplied pattern item did not restrict and select the actual pattern",
      );
      const service = client.service(BotService);
      const invalid = yield* Effect.tryPromise({
        try: () =>
          service.clickContainerButton({ instanceId: bot.instanceId, botId: bot.id, buttonId: 1 }),
        catch: (cause) => new CheckFailed({ message: String(cause) }),
      });
      yield* check(!invalid.success, "The loom accepted an unavailable pattern index");
      yield* bot.transferInventorySlot(3);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes('"minecraft:creeper"') && data.includes('"minecraft:banner_patterns"'),
              "Vanilla did not retain the chosen banner pattern",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(Effect.scoped, Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
