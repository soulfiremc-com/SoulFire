import { randomUUID } from "node:crypto";
import type { MessageInitShape } from "@bufbuild/protobuf";
import { BlockFace, Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import {
  PovInputEvent_Kind as Kind,
  type PovInputEventSchema,
  PovService,
} from "@soulfiremc/sdk/generated/soulfire/pov_pb";
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
const text = (value: string) =>
  Array.from(value, (character) => ({
    kind: Kind.CHARACTER,
    code: required(character.codePointAt(0), "a code point"),
  }));

export default {
  name: "sign-editing",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 2464 -16 2496 16");
      yield* rcon("fill 2478 63 -2 2483 63 2 stone");
      yield* rcon("fill 2478 64 -2 2483 68 2 air");
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      for (const hanging of [false, true]) {
        const y = hanging ? 65 : 64;
        if (hanging) yield* rcon("setblock 2481 66 0 stone");
        yield* rcon(
          `setblock 2481 ${y} 0 ${hanging ? "oak_hanging_sign" : "oak_sign"}[rotation=4]`,
        );
        for (const side of ["front", "back"]) {
          yield* placeBot(
            bot,
            { x: side === "front" ? 2480.5 : 2482.5, y: 64, z: 0.5 },
            side === "front" ? -90 : 90,
          );
          yield* bot.interactBlock({
            position: { x: 2481, y, z: 0 },
            face: side === "front" ? BlockFace.WEST : BlockFace.EAST,
            hand: Hand.MAIN,
          });
          const width = side === "front" ? 320 : 200;
          const height = side === "front" ? 180 : 300;
          // Native minimum layouts are 427x240 and 320x480, respectively.
          const guiHeight = side === "front" ? 240 : 480;
          yield* Effect.tryPromise({
            try: async () => {
              const abort = new AbortController();
              const sessionId = randomUUID();
              const pov = client.service(PovService);
              let sequence = 0n;
              const nextSequence = () => ++sequence;
              let phase = 0;
              let clicked = 0n;
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
                  { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(25_000)]) },
                )) {
                  const input = (
                    events: MessageInitShape<typeof PovInputEventSchema>[],
                    w = 640,
                    h = 480,
                  ) =>
                    pov.input({
                      sessionId,
                      inputToken: frame.inputToken,
                      sequence: nextSequence(),
                      captured: true,
                      width: w,
                      height: h,
                      events,
                    });
                  if (phase === 0 && frame.screenOpen) {
                    await input([
                      ...text(side),
                      ...key(257),
                      ...text("1234"),
                      ...key(268),
                      ...key(262),
                      ...key(262, 1),
                      ...key(262, 1),
                    ]);
                    await input([], width, height);
                    phase = 1;
                  } else if (phase === 1 && frame.width === width && frame.height === height) {
                    await input(
                      [
                        ...text("X"),
                        ...key(257),
                        ...text("third"),
                        ...key(264),
                        ...text("last"),
                        ...key(265),
                        ...key(268),
                        ...key(269, 1),
                      ],
                      width,
                      height,
                    );
                    await input([]);
                    phase = 2;
                  } else if (phase === 2 && frame.width === 640 && frame.height === 480) {
                    await input(text("final"));
                    await input([], width, height);
                    phase = 3;
                  } else if (phase === 3 && frame.width === width && frame.height === height) {
                    await input(
                      [
                        { kind: Kind.MOVE, x: 0.5, y: (guiHeight / 4 + 154) / guiHeight },
                        { kind: Kind.BUTTON, code: 0, action: 1 },
                        { kind: Kind.BUTTON, code: 0, action: 0 },
                      ],
                      width,
                      height,
                    );
                    clicked = frame.sequence;
                    phase = 4;
                  } else if (phase === 4) {
                    if (!frame.screenOpen) return;
                    if (frame.sequence > clicked + 15n)
                      throw new Error("The native Done button did not close the sign editor");
                    await input([], width, height);
                  }
                }
                throw new Error("The native sign editor did not close after clicking Done");
              } finally {
                abort.abort();
              }
            },
            catch: (cause) =>
              new CheckFailed({
                message: `${hanging ? "Hanging" : "Standing"} sign ${side}: ${String(cause)}`,
              }),
          });
          yield* eventually(
            rcon(`data get block 2481 ${y} 0 ${side}_text.messages`).pipe(
              Effect.flatMap((response) => {
                const expected = JSON.stringify([side, "1X4", "final", "last"]);
                return check(
                  response.slice(response.indexOf("[")).replaceAll(", ", ",") === expected,
                  `Sign editing or resize changed the saved lines: ${response}`,
                );
              }),
            ),
            "5 seconds",
          );
        }
        yield* rcon(`setblock 2481 ${y} 0 air`);
      }
    }).pipe(
      Effect.ensuring(
        rcon("fill 2478 64 -2 2483 68 2 air", { check: false }).pipe(
          Effect.ignore,
          Effect.andThen(
            rcon("forceload remove 2464 -16 2496 16", { check: false }).pipe(Effect.ignore),
          ),
        ),
      ),
    ),
} satisfies E2ETest;
