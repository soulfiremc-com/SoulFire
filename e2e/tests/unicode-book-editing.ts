import { randomUUID } from "node:crypto";
import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { PovInputEvent_Kind as Kind, PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import { BOT_NAME, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const key = (code: number, modifiers = 0) => [
  { kind: Kind.KEY, code, action: 1, modifiers },
  { kind: Kind.KEY, code, action: 0, modifiers },
];
const text = (value: string) =>
  Array.from(value, (character) => ({ kind: Kind.CHARACTER, code: character.codePointAt(0)! }));

export default {
  name: "unicode-book-editing",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      for (const test of [
        { name: "control", initial: "雪🙂界", edits: [], suffix: "", expected: "雪🙂界" },
        { name: "backspace", initial: "雪🙂", edits: key(259), suffix: "界", expected: "雪界" },
        {
          name: "delete",
          initial: "雪🙂界",
          edits: [...key(268), ...key(262), ...key(261)],
          suffix: "",
          expected: "雪界",
        },
        {
          name: "selection",
          initial: "雪🙂界",
          edits: [...key(268), ...key(262), ...key(262, 1)],
          suffix: "火",
          expected: "雪火界",
        },
        {
          name: "left",
          initial: "雪🙂界",
          edits: [...key(263), ...key(263)],
          suffix: "火",
          expected: "雪火🙂界",
        },
        {
          name: "word",
          initial: "alpha beta gamma",
          edits: key(259, 2),
          suffix: "delta",
          expected: "alpha beta delta",
        },
      ]) {
        yield* bot.closeContainer();
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} weapon.mainhand with minecraft:writable_book`);
        yield* eventually(
          bot.inventoryState().pipe(
            Effect.flatMap((state) =>
              check(
                state.slots.some((slot) => slot.itemId === "minecraft:writable_book"),
                "Writable book has not arrived",
              ),
            ),
          ),
          "10 seconds",
          "100 millis",
        );
        yield* bot.useItem({ hand: Hand.MAIN });
        yield* Effect.tryPromise({
          try: async () => {
            const abort = new AbortController();
            const sessionId = randomUUID();
            const pov = client.service(PovService);
            let sequence = 0n;
            let signFrame: bigint | undefined;
            let signed = false;
            let closed = false;
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
                { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15000)]) },
              )) {
                const send = (events: ReturnType<typeof key> | ReturnType<typeof text>) =>
                  pov.input({
                    sessionId,
                    inputToken: frame.inputToken,
                    sequence: ++sequence,
                    captured: true,
                    width: 640,
                    height: 480,
                    events,
                  });
                if (signFrame === undefined && frame.screenOpen) {
                  await send([
                    ...text(test.initial),
                    ...test.edits,
                    ...text(test.suffix),
                    ...key(258),
                    ...key(258),
                    ...key(257),
                  ]);
                  signFrame = frame.sequence;
                } else if (!signed && signFrame !== undefined && frame.sequence >= signFrame + 2n) {
                  await send([...text(test.name), ...key(257)]);
                  signed = true;
                } else if (signed && !frame.screenOpen) {
                  closed = true;
                  break;
                }
              }
            } finally {
              abort.abort();
            }
            if (!closed) throw new Error("Book did not finish signing");
          },
          catch: (cause) =>
            new CheckFailed({
              message: `${test.name}: native book input failed: ${String(cause)}`,
            }),
        });
        const read = rcon(`data get entity ${BOT_NAME} SelectedItem`);
        yield* eventually(
          read.pipe(
            Effect.flatMap((item) =>
              check(
                item.includes('"minecraft:written_book_content"'),
                "Signed book has not arrived",
              ),
            ),
          ),
          "3 seconds",
          "100 millis",
        );
        const item = yield* read;
        yield* check(
          item.includes(`raw: ${JSON.stringify(test.expected)}`),
          `${test.name}: expected ${JSON.stringify(test.expected)}, got ${item}`,
        );
      }
    }),
} satisfies E2ETest;
