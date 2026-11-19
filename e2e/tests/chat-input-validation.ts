import { Code } from "@connectrpc/connect";
import { Effect, Result } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, rcon } from "../harness.ts";

const OBJECTIVE = "sf_chat_input";

export default {
  name: "chat-input-validation",
  run: ({ bot }) =>
    Effect.gen(function* () {
      for (const character of ["\0", "\t", "\n", "\r", "\u001F", "\u007F", "§"]) {
        const cases = [
          { kind: "public chat", action: bot.chat.send(`invalid${character}text`) },
          { kind: "command", action: bot.chat.command(`help${character}`) },
          { kind: "whisper", action: bot.chat.whisper(BOT_NAME, `invalid${character}text`) },
        ];
        for (const test of cases) {
          const result = yield* Effect.result(test.action);
          yield* check(
            Result.isFailure(result) &&
              result.failure._tag === "SoulFireRpcError" &&
              result.failure.code === Code.InvalidArgument,
            `${test.kind} accepted illegal character ${character.codePointAt(0)}`,
          );
          yield* check(
            (yield* rcon("list")).includes(BOT_NAME),
            `${test.kind} disconnected the bot after rejecting invalid text`,
          );
        }
      }

      const unicode = "sf_valid_漢字_😀_\u0085";
      yield* Effect.all(
        [
          bot.chat.waitFor(unicode, { timeoutMs: 5000 }),
          Effect.sleep("200 millis").pipe(Effect.andThen(bot.chat.send(unicode))),
        ],
        { concurrency: 2 },
      );
      const whisper = `${unicode}_whisper`;
      yield* Effect.all(
        [
          bot.chat.waitFor(whisper, { timeoutMs: 5000 }),
          Effect.sleep("200 millis").pipe(Effect.andThen(bot.chat.whisper(BOT_NAME, whisper))),
        ],
        { concurrency: 2 },
      );
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`);
      yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
      yield* bot.chat.command(`/trigger ${OBJECTIVE} set 7`);
      yield* eventually(
        rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
          Effect.flatMap((score) =>
            check(/has 7 \[/u.test(score), `Valid command was not applied: ${score}`),
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
