import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Code, ConnectError } from "@connectrpc/connect";
import { Effect } from "effect";
import {
  encodeJson,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  rcon,
} from "../harness.ts";

const OBJECTIVE = "sf_dialog_id";

const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({
    try: call,
    catch: (cause) => new CheckFailed({ message: `Dialog identity RPC failed: ${String(cause)}` }),
  });

export default {
  name: "dialog-screen-identity",
  run: ({ bot, client }) => {
    return Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const show = (title: string, value: string) =>
        Effect.gen(function* () {
          yield* rcon(
            `dialog show ${BOT_NAME} ${encodeJson({
              type: "multi_action",
              title,
              pause: false,
              after_action: "none",
              inputs: [{ type: "text", key: "code", label: "Code", initial: value }],
              actions: [
                {
                  label: "Accept",
                  action: { type: "run_command", command: `trigger ${OBJECTIVE} set 22` },
                },
              ],
            })}`,
          );
          return yield* eventually(
            rpc(() => service.getDialog(scope)).pipe(
              Effect.flatMap((state) =>
                state.dialog?.title === title
                  ? Effect.succeed(state.dialog)
                  : Effect.fail(
                      new CheckFailed({ message: "The replacement dialog did not appear" }),
                    ),
              ),
            ),
            "5 seconds",
          );
        });
      const expectAborted = (call: () => Promise<unknown>) =>
        rpc(async () => {
          try {
            await call();
          } catch (error) {
            if (error instanceof ConnectError && error.code === Code.Aborted) return;
            throw error;
          }
          throw new Error("A stale dialog action was accepted");
        });
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`, { check: false });
      yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
      yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
      const first = yield* show("First identity QA", "first");
      const current = yield* show("Replacement identity QA", "second");
      yield* check(
        !!first.screenId && current.screenId !== first.screenId,
        "Replacement reused the old screen identity",
      );
      const stale = { ...scope, expectedScreenId: first.screenId };
      for (const action of [
        () => service.submitDialog({ ...stale, buttonIndex: 0, inputValues: { code: "stale" } }),
        () => service.clickDialogButton({ ...stale, buttonIndex: 0 }),
        () => service.closeDialog(stale),
      ]) {
        yield* expectAborted(action);
        const state = (yield* rpc(() => service.getDialog(scope))).dialog;
        yield* check(
          state?.screenId === current.screenId &&
            state.inputs.some(
              (input) => input.input.case === "text" && input.input.value.initial === "second",
            ),
          "A stale action changed the replacement dialog",
        );
        const score = yield* rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`);
        yield* check(score.includes("has 0 ["), "A stale action reached vanilla");
      }
      yield* rpc(() =>
        service.submitDialog({
          ...scope,
          expectedScreenId: current.screenId,
          buttonIndex: 0,
          inputValues: { code: "fresh" },
        }),
      );
      yield* eventually(
        rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
          Effect.flatMap((score) =>
            check(score.includes("has 22 ["), "A current action did not reach vanilla"),
          ),
        ),
        "3 seconds",
      );
      const updated = (yield* rpc(() => service.getDialog(scope))).dialog;
      yield* check(
        updated?.inputs.some(
          (input) => input.input.case === "text" && input.input.value.initial === "fresh",
        ) === true,
        "A current submission did not update its own inputs",
      );
      yield* rpc(() => service.closeDialog({ ...scope, expectedScreenId: current.screenId }));
      yield* expectAborted(() =>
        service.closeDialog({ ...scope, expectedScreenId: current.screenId }),
      );
    }).pipe(
      Effect.ensuring(
        rcon(`dialog clear ${BOT_NAME}`, { check: false }).pipe(
          Effect.ignore,
          Effect.andThen(
            rcon(`scoreboard objectives remove ${OBJECTIVE}`, { check: false }).pipe(Effect.ignore),
          ),
        ),
      ),
    );
  },
} satisfies E2ETest;
