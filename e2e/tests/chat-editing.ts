import { randomUUID } from "node:crypto";
import { PovInputEvent_Kind as Kind, PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import { BOT_NAME, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const OBJECTIVE = "sf_e2e_chat";
const text = (value: string) =>
  Array.from(value, (character) => ({ kind: Kind.CHARACTER, code: character.codePointAt(0)! }));
const key = (code: number, modifiers = 0) => [
  { kind: Kind.KEY, code, modifiers, action: 1 },
  { kind: Kind.KEY, code, modifiers, action: 0 },
];

export default {
  name: "chat-editing",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`);
      yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
      yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
      yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          const sessionId = randomUUID();
          const pov = client.service(PovService);
          let sequence = 0n;
          const nextSequence = () => ++sequence;
          let phase = 0;
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
              const input = (events: ReturnType<typeof text>, width = 640, height = 480) =>
                pov.input({
                  sessionId,
                  sequence: nextSequence(),
                  captured: true,
                  width,
                  height,
                  events,
                });
              if (phase === 0 && !frame.screenOpen) {
                await input(key(84));
                phase = 1;
              } else if (phase === 1 && frame.screenOpen) {
                await input([
                  ...text(`/trigger ${OBJECTIVE} set 13`),
                  ...key(269),
                  ...key(263),
                  ...key(263),
                  ...key(262, 1),
                ]);
                await input([], 320, 240);
                phase = 2;
              } else if (phase === 2 && frame.width === 320 && frame.height === 240) {
                await input([...text("2"), ...key(257)], 320, 240);
                phase = 3;
              } else if (phase === 3 && !frame.screenOpen) {
                return;
              }
            }
            throw new Error("The chat screen did not submit and close");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native chat editing failed: ${String(cause)}` }),
      });
      yield* eventually(
        rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
          Effect.flatMap((score) =>
            check(/has 23 \[/.test(score), `POV resize changed the edited command: ${score}`),
          ),
        ),
        "5 seconds",
      );
    }).pipe(
      Effect.ensuring(
        rcon(`scoreboard objectives remove ${OBJECTIVE}`, { check: false }).pipe(Effect.ignore),
      ),
    ),
} satisfies E2ETest;
