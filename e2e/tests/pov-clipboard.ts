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
} from "../harness.ts";

const ANVIL = { x: 1741, y: 64, z: 0 };
const key = (code: number, modifiers = 0) => [
  { kind: Kind.KEY, code, modifiers, action: 1 },
  { kind: Kind.KEY, code, modifiers, action: 0 },
];
const text = (value: string) =>
  Array.from(value, (character) => ({ kind: Kind.CHARACTER, code: character.codePointAt(0)! }));

export default {
  name: "pov-clipboard",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1728 -16 1760 16");
      yield* rcon("fill 1738 63 -2 1743 63 2 stone");
      yield* rcon("fill 1738 64 -2 1743 67 2 air");
      yield* rcon("setblock 1741 64 0 anvil");
      yield* placeBot(bot, { x: 1740.5, y: 64, z: 0.5 }, -90);
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* rcon(`experience set ${BOT_NAME} 10 levels`);
      yield* rcon(`give ${BOT_NAME} iron_sword`);
      yield* bot.inventory.open(ANVIL);
      const sword = (yield* bot.inventoryState()).slots.find(
        (slot) => slot.itemId === "minecraft:iron_sword",
      );
      if (!sword) return yield* new CheckFailed({ message: "Anvil sword is missing" });
      yield* bot.clickInventory(sword.slot);
      yield* bot.clickInventory(0);
      yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          const sessionId = randomUUID();
          const inventory = client.service(BotService);
          const pov = client.service(PovService);
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          try {
            for await (const frame of pov.watch(
              {
                instanceId: bot.instanceId,
                botId: bot.id,
                sessionId,
                width: 640,
                height: 480,
                maxFps: 15,
              },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              if (!frame.screenOpen) continue;
              const send = (
                events: ReturnType<typeof key> | ReturnType<typeof text>,
                clipboard?: string,
                captured = true,
              ) =>
                pov.input({
                  sessionId,
                  inputToken: frame.inputToken,
                  sequence: nextSequence(),
                  width: 640,
                  height: 480,
                  captured,
                  events,
                  clipboard,
                });
              const read = async () =>
                (
                  await inventory.getInventoryState({ instanceId: bot.instanceId, botId: bot.id })
                ).layout?.textInputs.find((input) => input.id === "item_name")?.currentValue;
              for (const test of [
                {
                  name: "select all before implicit paste",
                  events: key(65, 2),
                  clipboard: "Pasted",
                  expected: "Pasted",
                },
                {
                  name: "explicit paste once",
                  events: key(86, 2),
                  clipboard: "Pasted",
                  expected: "OriginalPasted",
                },
                {
                  name: "clipboard-only paste",
                  events: [],
                  clipboard: "Pasted",
                  expected: "OriginalPasted",
                },
                {
                  name: "clear selection with empty paste",
                  events: key(65, 2),
                  clipboard: "",
                  expected: "",
                },
                {
                  name: "uncaptured clipboard update",
                  events: [],
                  clipboard: "Pasted",
                  captured: false,
                  expected: "Original",
                },
                {
                  name: "explicit paste before later typing",
                  events: [...key(86, 2), ...text("X")],
                  clipboard: "Pasted",
                  expected: "OriginalPastedX",
                },
                {
                  name: "final rename",
                  events: key(65, 2),
                  clipboard: "Ordered paste",
                  expected: "Ordered paste",
                },
              ]) {
                await send([...key(65, 2), ...text("Original")]);
                if ((await read()) !== "Original")
                  throw new Error("The native editor did not reset");
                await send(test.events, test.clipboard, test.captured ?? true);
                const actual = await read();
                if (actual !== test.expected)
                  throw new Error(
                    `${test.name}: expected ${JSON.stringify(test.expected)}, got ${JSON.stringify(actual)}`,
                  );
              }
              return;
            }
            throw new Error("The native anvil screen never appeared");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `POV clipboard editing failed: ${String(cause)}` }),
      });
      yield* eventually(bot.transferInventorySlot(2), "5 seconds");
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((inventory) =>
            check(
              inventory.includes("Ordered paste"),
              "Vanilla did not retain the pasted item name",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
