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
import { nativeInput } from "../native-input.ts";

const ANVIL = { x: 4263, y: 64, z: 8 };
const cases = [
  { input: "A".repeat(50) + "B", expected: "A".repeat(50) },
  { input: "A".repeat(49) + "🙂B", expected: "A".repeat(49) },
  { input: "A".repeat(48) + "🙂B", expected: "A".repeat(48) + "🙂" },
  { input: "雪🙂界", expected: "雪🙂界" },
  { input: "§cOrange\u0000\t\r\n\u001f\u007f雪🙂", expected: "cOrange雪🙂" },
  { input: "§".repeat(10) + "A".repeat(50) + "B", expected: "A".repeat(50) },
  { input: "§".repeat(10) + "A".repeat(49) + "🙂B", expected: "A".repeat(49) },
];

export default {
  name: "container-text-limits",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const service = client.service(BotService);
        const run = Effect.runPromiseWith(yield* Effect.context());
        const scope = { instanceId: bot.instanceId, botId: bot.id };
        const setText = (menuId: string, fieldId: string, text: string) =>
          Effect.tryPromise({
            try: () =>
              service.setContainerText({ ...scope, expectedMenuId: menuId, fieldId, text }),
            catch: (cause) => new CheckFailed({ message: String(cause) }),
          }).pipe(
            Effect.tap((result) => check(result.success, "The native text setter rejected text")),
          );
        const expectField = (menuId: string, fieldId: string, value: string) =>
          eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    state.menuId === menuId &&
                      state.layout?.textInputs.some(
                        (input) => input.id === fieldId && input.currentValue === value,
                      ) === true,
                    `The ${fieldId} field did not retain whole characters within its limit`,
                  ),
                ),
              ),
            "3 seconds",
            "50 millis",
          );
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.closeContainer();
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("setblock 4263 64 8 air", { check: false });
            yield* rcon("fill 4262 63 7 4266 63 9 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4262 63 7 4266 63 9 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 4264.5, y: 64, z: 8.5 }, 90);
        yield* bot.openInventory();
        const creative = yield* bot.inventoryState();
        const searchTab = creative.layout?.buttons.find(
          (button) => button.iconItemId === "minecraft:compass",
        );
        yield* check(searchTab !== undefined, "The creative search tab is missing");
        yield* bot.clickContainerButton(searchTab!.buttonId);
        yield* nativeInput(client, bot, async (input) => {
          await input.click(298, 36);
          await input.type("§diamond sword");
          await run(expectField(creative.menuId, "search", "diamond sword"));
        });
        yield* Effect.sleep("2 seconds");
        for (const { input, expected } of cases) {
          yield* setText(creative.menuId, "search", input);
          yield* expectField(creative.menuId, "search", expected);
        }
        yield* setText(creative.menuId, "search", "diamond sword");
        const searched = yield* expectField(creative.menuId, "search", "diamond sword");
        yield* check(
          searched.slots.some(
            (slot) => slot.slot === 0 && slot.itemId === "minecraft:diamond_sword",
          ),
          "Searching after a clipped value did not refresh the catalog",
        );
        yield* bot.closeContainer();
        yield* rcon("setblock 4263 64 8 anvil");
        for (const { input, expected } of cases) {
          yield* rcon(`give ${BOT_NAME} iron_sword`);
          yield* bot.inventory.open(ANVIL);
          const opened = yield* eventually(
            bot.inventoryState().pipe(
              Effect.tap((state) =>
                check(
                  state.slots.some((slot) => slot.itemId === "minecraft:iron_sword"),
                  "The rename input item did not arrive",
                ),
              ),
            ),
            "3 seconds",
          );
          yield* bot.clickInventory(
            opened.slots.find((slot) => slot.itemId === "minecraft:iron_sword")!.slot,
          );
          yield* bot.clickInventory(0);
          yield* setText(opened.menuId, "item_name", input);
          yield* expectField(opened.menuId, "item_name", expected);
          yield* eventually(
            bot.inventoryState().pipe(
              Effect.tap((state) =>
                check(
                  state.slots.some((slot) => slot.slot === 2 && slot.displayName === expected),
                  "The anvil responder did not send the bounded value to vanilla",
                ),
              ),
            ),
            "3 seconds",
            "50 millis",
          );
          yield* bot.clickInventory(2);
          yield* bot.clickInventory(3);
          yield* bot.closeContainer();
          yield* eventually(
            rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
              Effect.tap((data) =>
                check(data.includes(expected), "Vanilla did not retain the bounded item name"),
              ),
            ),
            "3 seconds",
          );
          yield* rcon(`clear ${BOT_NAME}`);
        }
      }),
    ),
} satisfies E2ETest;
