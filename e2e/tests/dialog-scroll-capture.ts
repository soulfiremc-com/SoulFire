import { randomUUID } from "node:crypto";
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

const OBJECTIVE = "sf_scroll";
// At 320x240, nine rows overflow the body. This point is inside
// the last visible button after scrolling to the bottom.
const click = [
  { kind: Kind.MOVE, x: 0.5, y: 194 / 240 },
  { kind: Kind.BUTTON, code: 0, action: 1 },
  { kind: Kind.BUTTON, code: 0, action: 0 },
];
const scroll = (y: number) => [
  { kind: Kind.MOVE, x: 0.5, y: 0.5 },
  { kind: Kind.SCROLL, y },
];
const resetScore = Effect.gen(function* () {
  yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
  yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
});
const expectScore = (value: number) =>
  eventually(
    rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
      Effect.flatMap((score) =>
        check(
          score.includes(`has ${value} [`),
          `The native dialog selected the wrong row: expected ${value}, got ${score}`,
        ),
      ),
    ),
    "5 seconds",
  );

export default {
  name: "dialog-scroll-capture",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const runPromise = Effect.runPromiseWith(yield* Effect.context());
      yield* rcon("forceload add 2496 0 2511 15");
      yield* rcon("fill 2496 63 0 2511 63 15 stone");
      yield* placeBot(bot, { x: 2504.5, y: 64, z: 8.5 });
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`, { check: false });
      yield* resetScore;
      yield* rcon(
        `dialog show ${BOT_NAME} ${JSON.stringify({
          type: "multi_action",
          title: "Scroll QA",
          pause: false,
          after_action: "none",
          columns: 1,
          actions: Array.from({ length: 9 }, (_, index) => ({
            label: `Row ${index + 1}`,
            action: { type: "run_command", command: `trigger ${OBJECTIVE} set ${index + 1}` },
          })),
          exit_action: { label: "Close" },
        })}`,
      );
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const service = client.service(BotService);
      yield* eventually(
        Effect.tryPromise({
          try: () => service.getDialog(scope),
          catch: (cause) => new CheckFailed({ message: `Dialog read failed: ${String(cause)}` }),
        }).pipe(Effect.flatMap((state) => check(!!state.dialog, "The scroll dialog did not open"))),
        "5 seconds",
      );
      yield* Effect.tryPromise({
        try: async () => {
          const pov = client.service(PovService);
          const abort = new AbortController();
          const sessionId = randomUUID();
          let sequence = 0n;
          let scrolledAt: bigint | undefined;
          const input = (events: NonNullable<Parameters<typeof pov.input>[0]["events"]>) =>
            pov.input({
              sessionId,
              sequence: ++sequence,
              captured: true,
              width: 320,
              height: 240,
              events,
            });
          try {
            for await (const frame of pov.watch(
              { ...scope, sessionId, width: 320, height: 240, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(20_000)]) },
            )) {
              if (!frame.screenOpen) {
                await input([]);
                continue;
              }
              if (scrolledAt === undefined) {
                await input(scroll(-100));
                scrolledAt = frame.sequence;
                continue;
              }
              if (frame.sequence < scrolledAt + 2n) {
                await input([]);
                continue;
              }
              await input(click);
              await runPromise(expectScore(9));
              await runPromise(resetScore);
              await runPromise(expectScore(0));
              await runPromise(
                bot.renderPov({ width: 320, height: 1000, maxDistance: 32, includeHud: true }),
              );
              await input(click);
              await runPromise(expectScore(9));
              await runPromise(resetScore);
              await runPromise(expectScore(0));
              await input([...scroll(100), ...click]);
              await runPromise(expectScore(8));
              return;
            }
            throw new Error("The scroll dialog viewer ended early");
          } finally {
            abort.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Dialog scroll capture failed: ${String(cause)}` }),
      });
    }).pipe(
      Effect.ensuring(
        rcon(`dialog clear ${BOT_NAME}`, { check: false }).pipe(
          Effect.ignore,
          Effect.andThen(
            rcon(`scoreboard objectives remove ${OBJECTIVE}`, { check: false }).pipe(Effect.ignore),
          ),
        ),
      ),
    ),
} satisfies E2ETest;
