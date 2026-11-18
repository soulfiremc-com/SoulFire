import { Code, ConnectError } from "@connectrpc/connect";
import { BotService, ClickType } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
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

const CHEST = { x: 1681, y: 64, z: 0 };
const CRAFTER = { x: 1681, y: 64, z: 1 };
const ANVIL = { x: 1681, y: 64, z: -1 };
const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({ try: call, catch: (cause) => new CheckFailed({ message: String(cause) }) });
const expectAborted = (call: () => Promise<unknown>) =>
  rpc(async () => {
    try {
      await call();
    } catch (error) {
      if (error instanceof ConnectError && error.code === Code.Aborted) return;
      throw error;
    }
    throw new Error("An action targeting a stale inventory menu was accepted");
  });

export default {
  name: "inventory-menu-identity",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      yield* bot.closeContainer();
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* rcon("forceload add 1664 -16 1696 16");
      yield* rcon("fill 1678 63 -3 1683 63 3 stone");
      yield* rcon("fill 1678 64 -3 1683 67 3 air");
      yield* rcon("setblock 1681 64 0 chest");
      yield* rcon("setblock 1681 64 1 crafter");
      yield* rcon("setblock 1681 64 -1 anvil");
      yield* placeBot(bot, { x: 1680.5, y: 64, z: 0.5 }, -90);
      yield* bot.inventory.open(CHEST);
      const first = yield* bot.inventoryState();
      yield* bot.closeContainer();
      yield* bot.inventory.open(CRAFTER);
      const current = yield* bot.inventoryState();
      const stale = { ...scope, expectedMenuId: first.menuId || "old-menu" };
      for (const action of [
        () => service.clickInventorySlot({ ...stale, slot: 0, clickType: ClickType.LEFT_CLICK }),
        () => service.clickContainerButton({ ...stale, buttonId: 0 }),
        () => service.setContainerText({ ...stale, fieldId: "item_name", text: "stale" }),
        () => service.closeContainer(stale),
      ]) {
        yield* expectAborted(action);
        const unchanged = yield* bot.inventoryState();
        yield* check(
          unchanged.menuId === current.menuId &&
            unchanged.layout?.containerType === "crafter" &&
            unchanged.layout.buttons.find((button) => button.buttonId === 0)?.selected === false,
          "A stale action changed or closed the replacement crafter",
        );
        const disabled = yield* rcon("data get block 1681 64 1 disabled_slots");
        yield* check(/\[I;\s*\]/.test(disabled), "A stale action toggled the vanilla crafter");
      }
      yield* check(!!first.menuId && current.menuId !== first.menuId, "Menus reused an identity");
      yield* rpc(() =>
        service.clickContainerButton({ ...scope, expectedMenuId: current.menuId, buttonId: 0 }),
      );
      yield* eventually(
        rcon("data get block 1681 64 1 disabled_slots").pipe(
          Effect.flatMap((data) =>
            check(/\[I;\s*0\]/.test(data), "A current button did not reach vanilla"),
          ),
        ),
        "3 seconds",
      );
      yield* check(
        (yield* bot.inventoryState()).menuId === current.menuId,
        "A slot update changed the menu identity",
      );
      yield* rpc(() => service.closeContainer({ ...scope, expectedMenuId: current.menuId }));
      yield* bot.inventory.open(CHEST);
      const chest = yield* bot.inventoryState();
      yield* rcon("item replace block 1681 64 0 container.0 with diamond 7");
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.slot === 0 && slot.count === 7),
              "Chest contents did not arrive",
            ),
          ),
        ),
        "3 seconds",
      );
      yield* rpc(() =>
        service.clickInventorySlot({
          ...scope,
          expectedMenuId: chest.menuId,
          slot: 0,
          clickType: ClickType.LEFT_CLICK,
        }),
      );
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.menuId === chest.menuId && state.carriedItem?.count === 7,
                "A current slot click failed or changed menu identity",
              ),
            ),
          ),
        "3 seconds",
      );
      yield* rpc(() =>
        service.clickInventorySlot({
          ...scope,
          expectedMenuId: chest.menuId,
          slot: 0,
          clickType: ClickType.LEFT_CLICK,
        }),
      );
      yield* rpc(() => service.closeContainer({ ...scope, expectedMenuId: chest.menuId }));
      yield* bot.inventory.open(ANVIL);
      const oldAnvil = yield* bot.inventoryState();
      yield* bot.closeContainer();
      yield* bot.inventory.open(ANVIL);
      const anvil = yield* bot.inventoryState();
      yield* check(
        anvil.menuId !== oldAnvil.menuId,
        "Reopening the same anvil reused its identity",
      );
      yield* rcon(`give ${BOT_NAME} iron_sword`);
      const sword = yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) => {
            const slot = state.slots.find(
              (candidate) => candidate.itemId === "minecraft:iron_sword",
            );
            return slot
              ? Effect.succeed(slot.slot)
              : Effect.fail(new CheckFailed({ message: "Sword did not arrive" }));
          }),
        ),
        "3 seconds",
      );
      yield* bot.clickInventory(sword);
      yield* bot.clickInventory(0);
      yield* expectAborted(() =>
        service.setContainerText({
          ...scope,
          expectedMenuId: oldAnvil.menuId,
          fieldId: "item_name",
          text: "Stale name",
        }),
      );
      yield* check(
        !(yield* bot.inventoryState()).layout?.textInputs.some(
          (input) => input.currentValue === "Stale name",
        ),
        "A stale rename changed the anvil draft",
      );
      yield* rpc(() =>
        service.setContainerText({
          ...scope,
          expectedMenuId: anvil.menuId,
          fieldId: "item_name",
          text: "Current name",
        }),
      );
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.menuId === anvil.menuId &&
                  state.layout?.textInputs.some(
                    (input) => input.currentValue === "Current name",
                  ) === true,
                "A current rename did not update its own menu",
              ),
            ),
          ),
        "3 seconds",
      );
      yield* bot.closeContainer();
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      const playerInventory = yield* bot.inventoryState();
      yield* bot.openInventory();
      const creative = yield* bot.inventoryState();
      yield* check(
        creative.menuId !== playerInventory.menuId,
        "Creative and player menus reused an identity",
      );
      const search = creative.layout?.buttons.find(
        (button) => button.iconItemId === "minecraft:compass",
      );
      if (!search) return yield* new CheckFailed({ message: "Creative search tab is missing" });
      yield* rpc(() =>
        service.clickContainerButton({
          ...scope,
          expectedMenuId: creative.menuId,
          buttonId: search.buttonId,
        }),
      );
      yield* rpc(() =>
        service.setContainerText({
          ...scope,
          expectedMenuId: creative.menuId,
          fieldId: "search",
          text: "diamond",
        }),
      );
      yield* check(
        (yield* bot.inventoryState()).menuId === creative.menuId,
        "Creative tab or search changed menu identity",
      );
      yield* bot.closeContainer();
      yield* bot.openInventory();
      const reopened = yield* bot.inventoryState();
      yield* check(
        reopened.menuId !== creative.menuId,
        "Reopened creative inventory reused its identity",
      );
      yield* expectAborted(() =>
        service.setContainerText({
          ...scope,
          expectedMenuId: creative.menuId,
          fieldId: "search",
          text: "stale query",
        }),
      );
      yield* check(
        !(yield* bot.inventoryState()).layout?.textInputs.some(
          (input) => input.currentValue === "stale query",
        ),
        "A stale search changed the reopened creative inventory",
      );
      return undefined;
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
