import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  rcon,
} from "../harness.ts";

const OBJECTIVE = "sf_e2e_dismiss";

export default {
  name: "dialog-dismiss",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`, { check: false });
      yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
      yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
      yield* rcon(
        `dialog show ${BOT_NAME} {type:"minecraft:notice",title:"Dismiss QA",action:{label:"Close",action:{type:"minecraft:run_command",command:"trigger ${OBJECTIVE} set 1"}}}`,
      );
      yield* eventually(
        Effect.tryPromise({
          try: () => service.getDialog(scope),
          catch: (cause) => new CheckFailed({ message: `Container RPC failed: ${String(cause)}` }),
        }).pipe(
          Effect.flatMap((state) => check(!!state.dialog, "The server notice never appeared")),
        ),
        "5 seconds",
      );
      const response = yield* Effect.tryPromise({
        try: () => service.closeDialog(scope),
        catch: (cause) => new CheckFailed({ message: `Container RPC failed: ${String(cause)}` }),
      });
      yield* check(response.success, "The dialog dismissal was rejected");
      yield* eventually(
        rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
          Effect.flatMap((score) =>
            check(
              /has 1 \[/u.test(score),
              "Dismiss did not execute the native dialog close action",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
      yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
      yield* rcon(
        `dialog show ${BOT_NAME} {type:"minecraft:notice",title:"Escape QA",action:{label:"Close",action:{type:"minecraft:run_command",command:"trigger ${OBJECTIVE} set 1"}}}`,
      );
      yield* Effect.tryPromise({
        try: async () => {
          const controller = new AbortController();
          const sessionId = randomUuid();
          const pov = client.service(PovService);
          let sentEscape = false;
          try {
            const frames = pov.watch(
              { ...scope, sessionId, width: 320, height: 240, maxFps: 15, codecs: ["avc1"] },
              { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]) },
            );
            for await (const frame of frames) {
              if (!sentEscape && frame.screenOpen) {
                await pov.input({
                  sessionId,
                  sequence: 1n,
                  captured: true,
                  width: 320,
                  height: 240,
                  inputToken: frame.inputToken,
                  escape: true,
                });
                sentEscape = true;
              } else if (sentEscape && !frame.screenOpen) {
                return;
              }
            }
            throw new Error("The POV did not return to gameplay after Escape");
          } finally {
            controller.abort();
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Native dialog dismissal failed: ${String(cause)}` }),
      });
      yield* eventually(
        rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
          Effect.flatMap((score) =>
            check(/has 1 \[/u.test(score), "Escape did not execute the native dialog close action"),
          ),
        ),
        "5 seconds",
      );
      yield* eventually(
        Effect.tryPromise({
          try: () => service.getDialog(scope),
          catch: (cause) => new CheckFailed({ message: `Container RPC failed: ${String(cause)}` }),
        }).pipe(
          Effect.flatMap((state) =>
            check(!state.dialog, "The closed dialog is still reported as active"),
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
