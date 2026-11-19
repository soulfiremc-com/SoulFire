import { randomUUID } from "node:crypto";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { PovService, PovInputEvent_Kind as Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  required,
} from "../harness.ts";

const key = (code: number, modifiers = 0) => [
  { kind: Kind.KEY, code, modifiers, action: 1 },
  { kind: Kind.KEY, code, modifiers, action: 0 },
];
const click = (x: number, y: number) => [
  { kind: Kind.MOVE, x, y },
  { kind: Kind.BUTTON, code: 0, action: 1 },
  { kind: Kind.BUTTON, code: 0, action: 0 },
];
const text = (value: string) =>
  Array.from(value, (character) => ({
    kind: Kind.CHARACTER,
    code: required(character.codePointAt(0), "a code point"),
  }));

export default {
  name: "creative-inventory-search",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1824 -16 1856 16");
      yield* rcon("fill 1830 63 -2 1836 63 2 stone");
      yield* rcon("fill 1830 64 -2 1836 67 2 air");
      yield* placeBot(bot, { x: 1832.5, y: 64, z: 0.5 });
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* bot.openInventory();
      const searchTab = (yield* bot.inventoryState()).layout?.buttons.find(
        (button) => button.iconItemId === "minecraft:compass",
      );
      if (!searchTab)
        return yield* new CheckFailed({ message: "The creative search tab is missing" });
      yield* bot.clickContainerButton(searchTab.buttonId);
      yield* Effect.tryPromise({
        try: async () => {
          const pov = client.service(PovService);
          const inventory = client.service(BotService);
          const scope = { instanceId: bot.instanceId, botId: bot.id };
          const sessionId = randomUUID();
          const abort = new AbortController();
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          let phase = 0;
          const expectQuery = async (query: string, item: string, label: string) => {
            const deadline = Date.now() + 3000;
            do {
              const state = await inventory.getInventoryState(scope);
              if (
                state.layout?.textInputs.some(
                  (field) => field.id === "search" && field.currentValue === query,
                ) &&
                state.slots.some((slot) => slot.slot === 0 && slot.itemId === item)
              )
                return;
              await new Promise((resolve) => {
                setTimeout(resolve, 50);
              });
            } while (Date.now() < deadline);
            throw new Error(label);
          };
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 1280, height: 720, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              if (!frame.screenOpen) {
                if (phase === 3) return;
                continue;
              }
              const input = (
                events: {
                  kind: Kind;
                  code?: number;
                  modifiers?: number;
                  action?: number;
                  x?: number;
                  y?: number;
                }[],
                width = 1280,
                height = 720,
                escape = false,
              ) =>
                pov.input({
                  sessionId,
                  inputToken: frame.inputToken,
                  sequence: nextSequence(),
                  captured: true,
                  width,
                  height,
                  events,
                  escape,
                });
              if (phase === 0) {
                await input([
                  ...click(235 / 427, 62 / 240),
                  ...key(65, 2),
                  ...text("diamond sword"),
                ]);
                await expectQuery(
                  "diamond sword",
                  "minecraft:diamond_sword",
                  "The initial creative search failed",
                );
                // Verify that selecting backwards and replacing works before resizing.
                await input([...key(269), ...key(268, 1), ...text("nether star")]);
                await expectQuery(
                  "nether star",
                  "minecraft:nether_star",
                  "The original search selection did not replace the query",
                );
                await input([...key(65, 2), ...text("diamond sword"), ...key(269), ...key(268, 1)]);
                await expectQuery(
                  "diamond sword",
                  "minecraft:diamond_sword",
                  "The baseline query was not restored",
                );
                await input([], 1600, 900);
                phase = 1;
                continue;
              }
              if (frame.width !== 1600 || frame.height !== 900) continue;
              if (phase === 1) {
                await input(text("nether star"), 1600, 900);
                await expectQuery(
                  "nether star",
                  "minecraft:nether_star",
                  "Resizing lost the creative search selection or focus",
                );
                await input(click(186 / 534, 108 / 300), 1600, 900);
                await input(click(186 / 534, 202 / 300), 1600, 900);
                phase = 2;
                continue;
              }
              if (phase === 2) {
                await input([], 1600, 900, true);
                phase = 3;
              }
            }
            throw new Error("The resized creative screen did not close");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native creative search failed: ${String(cause)}` }),
      });
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes('"minecraft:nether_star"') &&
                !data.includes('"minecraft:diamond_sword"'),
              "Vanilla did not retain the item selected after resizing creative search",
            ),
          ),
        ),
        "5 seconds",
      );
      return undefined;
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
