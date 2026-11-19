import { PovService, PovInputEvent_Kind as Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  required,
} from "../harness.ts";

const TABLE = { x: 1569, y: 64, z: 0 };
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
  Array.from(value, (c) => ({
    kind: Kind.CHARACTER,
    code: required(c.codePointAt(0), "a code point"),
  }));

export default {
  name: "recipe-book-search",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const effectContext = yield* Effect.context();
      const runPromise = Effect.runPromiseWith(effectContext);
      yield* rcon("forceload add 1552 -16 1584 16");
      yield* rcon("fill 1566 63 -2 1571 63 2 stone");
      yield* rcon("fill 1566 64 -2 1571 67 2 air");
      yield* rcon("setblock 1569 64 0 crafting_table");
      yield* placeBot(bot, { x: 1568.5, y: 64, z: 0.5 }, -90);
      yield* rcon(`give ${BOT_NAME} minecraft:oak_log`);
      yield* rcon(`give ${BOT_NAME} minecraft:oak_planks 4`);
      for (const recipe of ["oak_planks", "crafting_table"]) {
        yield* rcon(`recipe give ${BOT_NAME} minecraft:${recipe}`, { check: false });
      }
      yield* bot.inventory.open(TABLE);
      const bookState = yield* rcon(`data get entity ${BOT_NAME} recipeBook.isGuiOpen`);
      const bookOpen = bookState.trim().endsWith("1b");
      const filterState = yield* rcon(
        `data get entity ${BOT_NAME} recipeBook.isFilteringCraftable`,
      );
      const craftableOnly = filterState.trim().endsWith("1b");
      yield* Effect.tryPromise({
        try: async () => {
          const pov = client.service(PovService);
          const expectOutput = (item: string, count: number, message: string) =>
            runPromise(
              eventually(
                bot.inventoryState().pipe(
                  Effect.flatMap((state) =>
                    check(
                      state.slots.some(
                        (slot) => slot.slot === 0 && slot.itemId === item && slot.count === count,
                      ),
                      message,
                    ),
                  ),
                ),
                "3 seconds",
              ),
            );
          const scope = { instanceId: bot.instanceId, botId: bot.id };
          const sessionId = randomUuid();
          const abort = new AbortController();
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          let phase = 0;
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 1280, height: 720, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              if (!frame.screenOpen) continue;
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
                requestKeyFrame = false,
              ) =>
                pov.input({
                  sessionId,
                  sequence: nextSequence(),
                  captured: true,
                  width,
                  height,
                  events,
                  requestKeyFrame,
                });
              if (phase === 0) {
                // Vanilla uses GUI scale 3 here. The book is at (54,37), and
                // the closed-book toggle is at (130,71) in its 427x240 GUI.
                if (!bookOpen) await input(click(140 / 427, 81 / 240));
                await input(click(40 / 427, 53 / 240));
                if (!craftableOnly) await input(click(177 / 427, 57 / 240));
                await input([...click(110 / 427, 56 / 240), ...key(65, 2), ...text("oak planks")]);
                await input([], 1280, 720, true);
                phase = 1;
                continue;
              }
              if (phase === 1) {
                if (!frame.keyFrame) continue;
                await input(click(77 / 427, 80 / 240));
                await expectOutput(
                  "minecraft:oak_planks",
                  4,
                  "The original recipe search did not place oak planks",
                );
                await input([...click(110 / 427, 56 / 240), ...key(65, 2)]);
                await input([], 1600, 900);
                phase = 2;
                continue;
              }
              if (frame.width !== 1600 || frame.height !== 900) continue;
              if (phase === 2) {
                // Replace the selected query without clicking the rebuilt search box.
                await input(text("crafting table"), 1600, 900, true);
                phase = 3;
                continue;
              }
              if (!frame.keyFrame) continue;
              // At this size the 534x300 GUI puts the book at (107,67).
              await input(click(130 / 534, 110 / 300), 1600, 900);
              await expectOutput(
                "minecraft:crafting_table",
                1,
                "Resizing lost the recipe search selection or focus",
              );
              await input(click(388 / 534, 110 / 300), 1600, 900);
              await input(click(272 / 534, 159 / 300), 1600, 900);
              return;
            }
            throw new Error("The resized crafting screen did not appear");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native recipe book input failed: ${String(cause)}` }),
      });
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes('"minecraft:crafting_table"'),
              "Vanilla did not retain the crafted table",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(Effect.ensuring(eventually(bot.closeContainer(), "5 seconds").pipe(Effect.ignore))),
} satisfies E2ETest;
