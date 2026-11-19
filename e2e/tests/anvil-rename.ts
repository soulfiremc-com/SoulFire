import { PovService, PovInputEvent_Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
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
} from "../harness.ts";

const DRAFT = "Anvil QA sword";
const ICON_NAME = `X${DRAFT}`;
const NAME = `Y${DRAFT}`;
const ANVIL = { x: 301, y: 64, z: 0 };

export default {
  name: "anvil-rename",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 288 -16 320 16");
      yield* rcon("fill 298 63 -2 303 63 2 minecraft:stone");
      yield* rcon("fill 298 64 -2 303 67 2 minecraft:air");
      yield* rcon(`setblock ${ANVIL.x} ${ANVIL.y} ${ANVIL.z} minecraft:anvil`);
      yield* placeBot(bot, { x: 300.5, y: 64, z: 0.5 }, -90);
      yield* rcon(`experience set ${BOT_NAME} 5 levels`);
      yield* rcon(`give ${BOT_NAME} minecraft:iron_sword 1`);
      yield* bot.inventory.open(ANVIL);

      const sword = yield* eventually(
        bot.inventoryState().pipe(
          Effect.map((state) => state.slots.find((slot) => slot.itemId === "minecraft:iron_sword")),
          Effect.filterOrFail(
            (slot) => slot !== undefined,
            () => new CheckFailed({ message: "The sword never reached the open anvil menu" }),
          ),
        ),
        "5 seconds",
      );
      yield* bot.clickInventory(sword.slot);
      yield* bot.clickInventory(0);
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some((slot) => slot.slot === 0 && slot.itemId === "minecraft:iron_sword"),
              "The sword never reached the anvil input",
            ),
          ),
        ),
        "5 seconds",
      );

      const response = yield* Effect.tryPromise({
        try: () =>
          client.service(BotService).setContainerText({
            instanceId: bot.instanceId,
            botId: bot.id,
            fieldId: "item_name",
            text: DRAFT,
          }),
        catch: (cause) => new CheckFailed({ message: `Rename request failed: ${String(cause)}` }),
      });
      yield* check(response.success, response.error ?? "The rename request was rejected");
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some((slot) => slot.slot === 2 && slot.displayName === DRAFT) &&
                  state.layout?.textInputs.some((input) => input.currentValue === DRAFT) === true,
                "The rename was accepted but its native draft and output were not synchronized",
              ),
            ),
          ),
        "5 seconds",
      );
      yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          const sessionId = randomUuid();
          const pov = client.service(PovService);
          const service = client.service(BotService);
          const scope = { instanceId: bot.instanceId, botId: bot.id };
          let resizing = false;
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 640, height: 480, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              if (!frame.screenOpen) continue;
              if (resizing) {
                if (frame.width !== 320 || frame.height !== 240) continue;
                await pov.input({
                  sessionId,
                  sequence: 4n,
                  captured: true,
                  width: 320,
                  height: 240,
                  events: [{ kind: PovInputEvent_Kind.CHARACTER, code: "Y".codePointAt(0) ?? 0 }],
                });
                return;
              }
              await pov.input({
                sessionId,
                sequence: 1n,
                captured: true,
                width: 640,
                height: 480,
                events: [
                  { kind: PovInputEvent_Kind.KEY, code: 268, action: 1 },
                  { kind: PovInputEvent_Kind.KEY, code: 268, action: 0 },
                ],
              });
              const inventory = await service.getInventoryState(scope);
              if (!inventory.slots.some((slot) => !!slot.iconBase64?.length))
                throw new Error("Inventory capture produced no item icons");
              await pov.input({
                sessionId,
                sequence: 2n,
                captured: true,
                width: 640,
                height: 480,
                events: [{ kind: PovInputEvent_Kind.CHARACTER, code: "X".codePointAt(0) ?? 0 }],
              });
              const edited = await service.getInventoryState(scope);
              if (!edited.layout?.textInputs.some((input) => input.currentValue === ICON_NAME))
                throw new Error("Rendering inventory icons moved the native anvil caret");
              await pov.input({
                sessionId,
                sequence: 3n,
                captured: true,
                width: 320,
                height: 240,
                events: [
                  { kind: PovInputEvent_Kind.KEY, code: 268, action: 1 },
                  { kind: PovInputEvent_Kind.KEY, code: 268, action: 0 },
                  { kind: PovInputEvent_Kind.KEY, code: 262, modifiers: 1, action: 1 },
                  { kind: PovInputEvent_Kind.KEY, code: 262, modifiers: 1, action: 0 },
                ],
              });
              resizing = true;
            }
            throw new Error("The native anvil screen never appeared");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native anvil input failed: ${String(cause)}` }),
      });
      yield* eventually(
        bot
          .inventoryState()
          .pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some((slot) => slot.slot === 2 && slot.displayName === NAME) &&
                  state.layout?.textInputs.some((input) => input.currentValue === NAME) === true,
                "Resizing POV lost the native anvil selection",
              ),
            ),
          ),
        "5 seconds",
      );
      // Wait for cancellation of the POV stream to release its control lease.
      yield* eventually(bot.transferInventorySlot(2), "5 seconds");
      yield* eventually(
        rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
          Effect.flatMap((inventory) =>
            check(
              inventory.includes(NAME),
              "Vanilla did not retain the renamed sword after taking the output",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(Effect.ensuring(bot.closeContainer().pipe(Effect.ignore))),
} satisfies E2ETest;
