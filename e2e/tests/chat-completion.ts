import { Code } from "@connectrpc/connect";
import { Effect, Result } from "effect";
import { BOT_NAME, check, type E2ETest, rcon } from "../harness.ts";

export default {
  name: "chat-completion",
  run: ({ bot }) =>
    Effect.gen(function* () {
      const cases = [
        { input: "", cursor: 0, start: 0, length: 0, expected: BOT_NAME },
        { input: " ", cursor: 0, start: 0, length: 0, expected: `${BOT_NAME} ` },
        { input: " E2", cursor: 0, start: 0, length: 0, expected: `${BOT_NAME} E2` },
        { input: "Hello E2", cursor: 8, start: 6, length: 2, expected: `Hello ${BOT_NAME}` },
        { input: "😀 E2", cursor: 5, start: 3, length: 2, expected: `😀 ${BOT_NAME}` },
      ];
      for (const test of cases) {
        const response = yield* bot.chat.complete(test.input, { cursor: test.cursor });
        yield* check(
          response.start === test.start && response.length === test.length,
          `Unexpected completion range ${response.start}:${response.length} at cursor ${test.cursor}`,
        );
        const suggestion = response.suggestions.find((item) => item.text === BOT_NAME);
        yield* check(suggestion !== undefined, "Completion did not include the connected bot");
        const applied =
          test.input.slice(0, response.start) +
          (suggestion?.text ?? "") +
          test.input.slice(response.start + response.length);
        yield* check(applied === test.expected, "Applying completion changed unrelated text");
      }
      const command = yield* bot.chat.complete("/he", { cursor: 3 });
      yield* check(
        command.start === 1 &&
          command.length === 2 &&
          command.suggestions.some((item) => item.text === "help"),
        "Command completion lost its leading-slash offset",
      );
      for (const cursor of [-1, 3]) {
        const response = yield* Effect.result(bot.chat.complete("E2", { cursor }));
        yield* check(
          Result.isFailure(response) &&
            response.failure._tag === "SoulFireRpcError" &&
            response.failure.code === Code.InvalidArgument,
          `Invalid completion cursor ${cursor} was not rejected`,
        );
      }
      yield* check((yield* rcon("list")).includes(BOT_NAME), "Completion disconnected the bot");
    }),
} satisfies E2ETest;
