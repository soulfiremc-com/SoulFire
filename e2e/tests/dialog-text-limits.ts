import { randomUUID } from "node:crypto";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { PovInputEvent_Kind as Kind, PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import { BOT_NAME, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const key = (code: number, modifiers = 0) => [
  { kind: Kind.KEY, code, modifiers, action: 1 },
  { kind: Kind.KEY, code, modifiers, action: 0 },
];
const text = (value: string) =>
  Array.from(value, (character) => ({ kind: Kind.CHARACTER, code: character.codePointAt(0)! }));
const cases = [
  {
    name: "single-replace",
    multiline: false,
    initial: "ABCD",
    limit: 4,
    keys: key(65, 2),
    insert: "X",
    expected: "X",
  },
  {
    name: "multi-replace",
    multiline: true,
    initial: "ABCD",
    limit: 4,
    keys: key(65, 2),
    insert: "X",
    expected: "X",
  },
  {
    name: "multi-partial",
    multiline: true,
    initial: "ABCD",
    limit: 4,
    keys: [...key(268), ...key(262), ...key(262, 1)],
    insert: "Z",
    expected: "AZCD",
  },
  {
    name: "single-emoji",
    multiline: false,
    initial: "A",
    limit: 2,
    keys: key(269),
    insert: "🙂B",
    expected: "AB",
  },
  {
    name: "multi-emoji",
    multiline: true,
    initial: "A",
    limit: 2,
    keys: key(269),
    insert: "🙂B",
    expected: "AB",
  },
  {
    name: "multi-fit",
    multiline: true,
    initial: "A",
    limit: 3,
    keys: key(269),
    insert: "🙂",
    expected: "A🙂",
  },
  {
    name: "multi-paste-replace",
    multiline: true,
    initial: "ABCD",
    limit: 4,
    keys: key(65, 2),
    paste: "WXYZ",
    expected: "WXYZ",
  },
  {
    name: "multi-paste-boundary",
    multiline: true,
    initial: "",
    limit: 2,
    keys: [],
    paste: "A🙂B",
    expected: "A",
  },
  {
    name: "multi-selected-boundary",
    multiline: true,
    initial: "ABCD",
    limit: 4,
    keys: key(65, 2),
    paste: "ABC🙂X",
    expected: "ABC",
  },
];

export default {
  name: "dialog-text-limits",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const service = client.service(BotService);
      const read = Effect.tryPromise({
        try: () => service.getDialog(scope),
        catch: (cause) => new CheckFailed({ message: String(cause) }),
      });
      const show = (title: string, initial: string, limit: number, multiline: boolean) =>
        Effect.gen(function* () {
          yield* rcon(
            `dialog show ${BOT_NAME} ${JSON.stringify({
              type: "multi_action",
              title,
              pause: false,
              after_action: "none",
              inputs: [
                {
                  type: "text",
                  key: "notes",
                  label: "Notes",
                  initial,
                  max_length: limit,
                  ...(multiline ? { multiline: { max_lines: 4, height: 60 } } : {}),
                },
              ],
              actions: [{ label: "Continue" }],
            })}`,
          );
          yield* eventually(
            read.pipe(
              Effect.flatMap((state) =>
                check(state.dialog?.title === title, "Replacement dialog has not arrived"),
              ),
            ),
            "5 seconds",
            "100 millis",
          );
        });
      yield* show("Text limit setup", "", 4, false);
      yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          const sessionId = randomUUID();
          const pov = client.service(PovService);
          const frames = pov
            .watch(
              { ...scope, sessionId, width: 640, height: 480, maxFps: 15 },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(30000)]) },
            )
            [Symbol.asyncIterator]();
          let sequence = 0n;
          try {
            const first = await frames.next();
            if (first.done || !first.value.screenOpen)
              throw new Error("Setup dialog is not visible");
            const send = (
              events: ReturnType<typeof key> | ReturnType<typeof text>,
              clipboard?: string,
            ) =>
              pov.input({
                sessionId,
                inputToken: first.value.inputToken,
                sequence: ++sequence,
                captured: true,
                width: 640,
                height: 480,
                events,
                clipboard,
              });
            // Establish keyboard navigation so each replacement starts at the warning button.
            await send(key(258));
            await frames.next();
            await frames.next();
            for (const test of cases) {
              await Effect.runPromise(show(test.name, test.initial, test.limit, test.multiline));
              // The next tab focuses the field after the dialog's warning button.
              await send([...key(258), ...test.keys, ...text(test.insert ?? "")], test.paste);
              await frames.next();
              await frames.next();
              await Effect.runPromise(
                eventually(
                  read.pipe(
                    Effect.flatMap((state) => {
                      const input = state.dialog?.inputs[0]?.input;
                      const actual = input?.case === "text" ? input.value.initial : undefined;
                      return check(
                        actual === test.expected,
                        `${test.name}: expected ${JSON.stringify(test.expected)}, got ${JSON.stringify(actual)}`,
                      );
                    }),
                  ),
                  "2 seconds",
                  "100 millis",
                ),
              );
            }
          } finally {
            abort.abort();
            await frames.return?.();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native text limit check failed: ${String(cause)}` }),
      });
    }).pipe(
      Effect.ensuring(rcon(`dialog clear ${BOT_NAME}`, { check: false }).pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
