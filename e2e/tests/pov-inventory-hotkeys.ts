import { BotService, GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { PovService, PovInputEvent_Kind as Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Clock, Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const key = (code: number) => [
  { kind: Kind.KEY, code, action: 1 },
  { kind: Kind.KEY, code, action: 0 },
];
const move = (x: number) => ({ kind: Kind.MOVE, x: x / 427, y: 172 / 240 });

export default {
  name: "pov-inventory-hotkeys",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1920 -16 1952 16");
      yield* rcon("fill 1926 63 -2 1930 63 2 stone");
      yield* rcon("fill 1926 64 -2 1930 67 2 air");
      yield* placeBot(bot, { x: 1928.5, y: 64, z: 0.5 });
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:oak_planks 12`);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with minecraft:nether_star 3`);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with minecraft:bow`);
      yield* eventually(
        bot
          .info()
          .pipe(
            Effect.flatMap((info) =>
              check(info.liveState?.gameMode === GameMode.CREATIVE, "Creative mode did not arrive"),
            ),
          ),
        "3 seconds",
      );
      yield* bot.openInventory();
      const tab = (yield* bot.inventoryState()).layout?.buttons.find(
        (button) => button.iconItemId === "minecraft:chest",
      );
      if (!tab) return yield* new CheckFailed({ message: "The creative Inventory tab is missing" });
      yield* bot.clickContainerButton(tab.buttonId);

      const clock = yield* Clock.Clock;
      const effectContext = yield* Effect.context();
      const runPromise = Effect.runPromiseWith(effectContext);
      yield* Effect.tryPromise({
        try: async () => {
          const pov = client.service(PovService);
          const inventory = client.service(BotService);
          const scope = { instanceId: bot.instanceId, botId: bot.id };
          const sessionId = randomUuid();
          const abort = new AbortController();
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          let phase = 0;
          let beforeHover = 0n;
          const expect = async (slot: number, item: string, count: number, label: string) => {
            const deadline = clock.currentTimeMillisUnsafe() + 3000;
            do {
              const state = await inventory.getInventoryState(scope);
              if (
                state.slots.some(
                  (value) => value.slot === slot && value.itemId === item && value.count === count,
                )
              )
                return;
              await runPromise(Effect.sleep(50));
            } while (clock.currentTimeMillisUnsafe() < deadline);
            throw new Error(label);
          };
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 1280, height: 720, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              if (!frame.screenOpen) {
                if (phase === 4) return;
                continue;
              }
              const input = (
                events: NonNullable<Parameters<typeof pov.input>[0]["events"]>,
                requestKeyFrame = false,
                escape = false,
              ) =>
                pov.input({
                  sessionId,
                  inputToken: frame.inputToken,
                  sequence: nextSequence(),
                  captured: true,
                  width: 1280,
                  height: 720,
                  events,
                  requestKeyFrame,
                  escape,
                });
              if (phase === 0) {
                await expect(36, "minecraft:oak_planks", 12, "The test planks did not arrive");
              } else {
                if (!frame.keyFrame || frame.sequence <= beforeHover) continue;
                // Render the first slot, then move and press a hotkey in one input batch.
                // No video frame can refresh the native hovered slot between those events.
                if (phase === 1) {
                  await input([move(151), ...key(70)]);
                  await expect(
                    45,
                    "minecraft:nether_star",
                    3,
                    "F targeted the previously rendered slot",
                  );
                } else if (phase === 2) {
                  await input([move(169), ...key(50)]);
                  await expect(
                    37,
                    "minecraft:bow",
                    1,
                    "The number key targeted the previously rendered slot",
                  );
                } else {
                  await input([move(151), ...key(81)]);
                  await expect(
                    36,
                    "minecraft:oak_planks",
                    12,
                    "Q dropped from the previously rendered slot",
                  );
                  const state = await inventory.getInventoryState(scope);
                  if (state.slots.some((slot) => slot.itemId === "minecraft:bow"))
                    throw new Error("Q did not drop the newly hovered bow");
                  await input([], false, true);
                  phase = 4;
                  continue;
                }
              }
              beforeHover = frame.sequence;
              await input([move(133)], true);
              phase++;
            }
            throw new Error("The inventory hotkey screen did not close");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native inventory hotkeys failed: ${String(cause)}` }),
      });
      yield* eventually(
        Effect.gen(function* () {
          const inventory = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
          const offhand = yield* rcon(`data get entity ${BOT_NAME} equipment.offhand`);
          yield* check(
            inventory.includes("count: 12") &&
              inventory.includes('"minecraft:oak_planks"') &&
              !inventory.includes('"minecraft:bow"'),
            "Vanilla inventory disagrees with the hotkey results",
          );
          yield* check(
            offhand.includes("count: 3") && offhand.includes('"minecraft:nether_star"'),
            "Vanilla did not retain the newly hovered offhand item",
          );
        }),
        "3 seconds",
      );
      return undefined;
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
      Effect.ensuring(rcon("kill @e[type=item,x=1928,y=64,z=0,distance=..8]").pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
