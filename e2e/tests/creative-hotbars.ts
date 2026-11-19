import { Code } from "@connectrpc/connect";
import { randomUUID } from "node:crypto";
import type { SoulFireBot, SoulFireClient } from "@soulfiremc/sdk";
import { PovService, PovInputEvent_Kind as Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect, Schedule } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const OTHER = "E2EHotbar2";

// Save or load group 9 with vanilla's C+9 or X+9 shortcut in the game view.
const hotbarShortcut = (client: SoulFireClient, bot: SoulFireBot, activator: number) =>
  Effect.tryPromise({
    try: async () => {
      const service = client.service(PovService);
      const sessionId = randomUUID();
      const abort = new AbortController();
      let sequence = 0n;
      let stage: "shortcut" | "open" | "closed" = "shortcut";
      let token = "";
      const input = (events: { kind: Kind; code: number; action: number }[], captured = true) =>
        service.input({
          sessionId,
          inputToken: token,
          sequence: ++sequence,
          captured,
          width: 1280,
          height: 720,
          events,
        });
      try {
        for await (const frame of service.watch(
          {
            instanceId: bot.instanceId,
            botId: bot.id,
            sessionId,
            width: 1280,
            height: 720,
            maxFps: 15,
          },
          { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10_000)]) },
        )) {
          token = frame.inputToken;
          if (stage === "shortcut" && !frame.screenOpen) {
            await input([
              { kind: Kind.KEY, code: activator, action: 1 },
              { kind: Kind.KEY, code: 57, action: 1 },
              { kind: Kind.KEY, code: 57, action: 0 },
              // Vanilla consumes hotbar shortcuts before opening the inventory in its tick.
              // Video frames alone do not confirm that those queued keys were processed.
              { kind: Kind.KEY, code: 69, action: 1 },
              { kind: Kind.KEY, code: 69, action: 0 },
            ]);
            stage = "open";
          } else if (stage === "open" && frame.screenOpen) {
            await input([
              { kind: Kind.KEY, code: 256, action: 1 },
              { kind: Kind.KEY, code: 256, action: 0 },
            ]);
            stage = "closed";
          } else if (stage === "closed" && !frame.screenOpen) {
            return;
          } else {
            await input([]);
          }
        }
        throw new Error("The native hotbar shortcut did not complete");
      } finally {
        if (token)
          await input([{ kind: Kind.KEY, code: activator, action: 0 }], false).catch(() => {});
        abort.abort();
      }
    },
    catch: (cause) =>
      new CheckFailed({ message: `Native hotbar shortcut failed: ${String(cause)}` }),
  });

const openSaved = (target: SoulFireBot) =>
  Effect.gen(function* () {
    yield* target.openInventory().pipe(
      Effect.retry({
        schedule: Schedule.spaced("50 millis").pipe(Schedule.upTo({ duration: "2 seconds" })),
        while: (error) =>
          error._tag === "SoulFireRpcError" &&
          error.code === Code.PermissionDenied &&
          error.message.includes("leased"),
      }),
    );
    const tab = (yield* target.inventoryState()).layout?.buttons.find(
      (button) => button.iconItemId === "minecraft:bookshelf",
    );
    if (!tab) return yield* new CheckFailed({ message: "The Saved Hotbars tab is missing" });
    yield* target.clickContainerButton(tab.buttonId);
    yield* target.clickContainerButton(-2);
    return undefined;
  });
const expectSaved = (target: SoulFireBot, item: string) =>
  eventually(
    target.inventoryState().pipe(
      Effect.flatMap((state) =>
        check(
          state.slots.some((slot) => slot.slot === 36 && slot.itemId === item),
          "The bot did not retain its own saved hotbar",
        ),
      ),
    ),
    "3 seconds",
  );

export default {
  name: "creative-hotbars",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const instance = client.instance(bot.instanceId);
      const cleanup: { otherId?: string } = {};
      yield* Effect.addFinalizer(() =>
        cleanup.otherId === undefined
          ? Effect.void
          : instance.removeAccounts([cleanup.otherId]).pipe(Effect.ignore),
      );
      const other = yield* instance.getOrCreateBot(OTHER, { readyTimeoutMs: 30_000 });
      cleanup.otherId = other.id;
      yield* rcon("forceload add 1872 -16 1904 16");
      yield* rcon("fill 1878 63 -2 1886 63 2 stone");
      yield* rcon("fill 1878 64 -2 1886 67 2 air");
      yield* placeBot(bot, { x: 1880.5, y: 64, z: 0.5 });
      yield* rcon(`clear ${OTHER}`, { check: false });
      yield* rcon(`tp ${OTHER} 1882.5 64 0.5`);
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon(`gamemode creative ${OTHER}`);
      yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with minecraft:diamond_sword`);
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.itemId === "minecraft:diamond_sword"),
              "The test sword did not arrive",
            ),
          ),
        ),
        "3 seconds",
      );
      yield* hotbarShortcut(client, bot, 67);
      yield* openSaved(bot);
      yield* expectSaved(bot, "minecraft:diamond_sword");
      yield* bot.closeContainer();
      yield* openSaved(other);
      yield* check(
        !(yield* other.inventoryState()).slots.some(
          (slot) => slot.itemId === "minecraft:diamond_sword",
        ),
        "A bot can see another bot's saved hotbar",
      );
      yield* other.closeContainer();
      yield* rcon(`item replace entity ${OTHER} hotbar.0 with minecraft:nether_star`);
      yield* eventually(
        other.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.itemId === "minecraft:nether_star"),
              "The test star did not arrive",
            ),
          ),
        ),
        "3 seconds",
      );
      yield* hotbarShortcut(client, other, 67);
      yield* openSaved(other);
      yield* expectSaved(other, "minecraft:nether_star");
      yield* other.closeContainer();
      yield* openSaved(bot);
      yield* expectSaved(bot, "minecraft:diamond_sword");
      yield* bot.closeContainer();
      const session = yield* bot.observe();
      const epoch = session.state.epoch;
      yield* bot.restart();
      yield* session
        .waitForState((state) => state.epoch !== epoch && state.player !== undefined)
        .pipe(
          Effect.timeoutOrElse({
            duration: "15 seconds",
            orElse: () =>
              Effect.fail(
                new CheckFailed({ message: "The replacement connection did not become ready" }),
              ),
          }),
        );
      yield* openSaved(bot);
      yield* expectSaved(bot, "minecraft:diamond_sword");
      yield* bot.closeContainer();
      yield* rcon(`clear ${BOT_NAME} minecraft:diamond_sword`);
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.length === 0,
                "The inventory did not clear before restoring a hotbar",
              ),
            ),
          ),
        "3 seconds",
      );
      yield* hotbarShortcut(client, bot, 88);
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((data) =>
            check(
              data.includes('"minecraft:diamond_sword"') &&
                !data.includes('"minecraft:nether_star"'),
              `Vanilla did not retain the bot's restored hotbar: ${data}`,
            ),
          ),
        ),
        "3 seconds",
      );
    }).pipe(
      Effect.ensuring(bot.closeContainer().pipe(Effect.ignore)),
      Effect.ensuring(rcon(`gamemode survival ${BOT_NAME}`).pipe(Effect.ignore)),
      Effect.scoped,
    ),
} satisfies E2ETest;
