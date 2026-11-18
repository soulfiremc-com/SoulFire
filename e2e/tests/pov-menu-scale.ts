import { randomUUID } from "node:crypto";
import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { PovInputEvent_Kind as Kind, PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
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

const TAG = "sf_e2e_pov_scale";
// At 1280x720, vanilla's automatic GUI scale is 3. These points are inside
// the first and second native offer buttons, rather than their item icons.
const OFFER_X = 110 / 427;
const FIRST_Y = 65 / 240;
const SECOND_Y = 85 / 240;
const click = (y: number) => [
  { kind: Kind.MOVE, x: OFFER_X, y },
  { kind: Kind.BUTTON, code: 0, action: 1 },
  { kind: Kind.BUTTON, code: 0, action: 0 },
];

export default {
  name: "pov-menu-scale",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1456 -16 1488 16");
      yield* rcon("fill 1470 63 -2 1475 63 2 stone");
      yield* rcon("fill 1470 64 -2 1475 67 2 air");
      yield* placeBot(bot, { x: 1472.5, y: 64, z: 0.5 }, -90);
      yield* rcon(
        `summon minecraft:villager 1473.5 64 0.5 {Tags:["${TAG}"],NoAI:1b,Invulnerable:1b,VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:bread",count:1},maxUses:999},{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:apple",count:2},maxUses:999}]}}`,
      );
      yield* rcon(`give ${BOT_NAME} minecraft:emerald 3`);
      const reference = yield* eventually(
        bot.world
          .queryEntities({ radius: 3, selector: { entityTypes: ["minecraft:villager"] } })
          .pipe(
            Effect.map((result) => result.entities[0]?.reference),
            Effect.filterOrFail(
              (value) => value !== undefined,
              () => new CheckFailed({ message: "The scale-test villager did not load" }),
            ),
          ),
        "5 seconds",
      );
      yield* bot.interactEntity({
        entityId: reference.networkId,
        connectionEpoch: reference.connectionEpoch,
        hand: Hand.MAIN,
      });
      yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          const sessionId = randomUUID();
          const scope = { instanceId: bot.instanceId, botId: bot.id };
          const pov = client.service(PovService);
          const service = client.service(BotService);
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          let selectedFirst = false;
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 1280, height: 720, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(25_000)]) },
            )) {
              if (!frame.screenOpen) continue;
              const input = (events: ReturnType<typeof click>) =>
                pov.input({
                  sessionId,
                  sequence: nextSequence(),
                  captured: true,
                  width: 1280,
                  height: 720,
                  events,
                  feedback: { receivedSequence: frame.sequence, deliveryDelayMs: 200 },
                });
              if (!selectedFirst) {
                await input(click(FIRST_Y));
                const state = await service.getInventoryState(scope);
                if (
                  !state.slots.some((slot) => slot.slot === 2 && slot.itemId === "minecraft:bread")
                )
                  throw new Error("The native offer did not accept the baseline pointer position");
                selectedFirst = true;
              } else if (frame.width === 640 && frame.height === 360) {
                await service.getInventoryState(scope);
                await input(click(SECOND_Y));
                return;
              } else await input([]);
            }
            throw new Error("The stream did not adapt to half resolution");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Adaptive menu input failed: ${String(cause)}` }),
      });
      yield* eventually(
        bot.inventoryState().pipe(
          Effect.flatMap((state) =>
            check(
              state.slots.some(
                (slot) => slot.slot === 2 && slot.itemId === "minecraft:apple" && slot.count === 2,
              ),
              "Adaptive resolution moved the native offer away from its displayed pointer position",
            ),
          ),
        ),
        "5 seconds",
      );
    }).pipe(
      Effect.ensuring(
        eventually(bot.closeContainer(), "5 seconds").pipe(
          Effect.ignore,
          Effect.andThen(
            rcon(`clear ${BOT_NAME} minecraft:emerald 3`, { check: false }).pipe(Effect.ignore),
          ),
          Effect.andThen(
            rcon(`kill @e[type=minecraft:villager,tag=${TAG}]`, { check: false }).pipe(
              Effect.ignore,
            ),
          ),
        ),
      ),
    ),
} satisfies E2ETest;
