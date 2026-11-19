import { randomUUID } from "node:crypto";
import { BotService } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect, Result } from "effect";
import { BOT_NAME, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const OBJECTIVE = "sf_inputs";
const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({
    try: call,
    catch: (cause) => new CheckFailed({ message: `Dialog RPC failed: ${String(cause)}` }),
  });
const resetScore = Effect.gen(function* () {
  yield* rcon(`scoreboard players set ${BOT_NAME} ${OBJECTIVE} 0`);
  yield* rcon(`scoreboard players enable ${BOT_NAME} ${OBJECTIVE}`);
});
const expectScore = (value: number) =>
  eventually(
    rcon(`scoreboard players get ${BOT_NAME} ${OBJECTIVE}`).pipe(
      Effect.flatMap((score) =>
        check(
          score.endsWith(`data: ${value}`) || score.includes(`has ${value} [`),
          `The dialog action did not reach vanilla with value ${value}`,
        ),
      ),
    ),
    "5 seconds",
  );
const action = (label: string, command: string) => ({
  label,
  action: { type: "run_command", command },
});

export default {
  name: "dialog-actions",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const show = (dialog: object) =>
        rcon(`dialog show ${BOT_NAME} ${JSON.stringify(dialog)}`).pipe(
          Effect.andThen(
            eventually(
              rpc(() => service.getDialog(scope)).pipe(
                Effect.flatMap((state) => check(!!state.dialog, "The dialog never opened")),
              ),
              "5 seconds",
            ),
          ),
        );
      yield* rcon(`scoreboard objectives add ${OBJECTIVE} trigger`, { check: false });
      yield* resetScore;
      yield* show({
        type: "confirmation",
        title: "Confirmation QA",
        yes: action("Yes", `trigger ${OBJECTIVE} set 1`),
        no: action("No", `trigger ${OBJECTIVE} set 2`),
      });
      const clicked = yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 1 }));
      yield* check(clicked.success, "The confirmation action was rejected");
      yield* expectScore(2);

      const keys = ["amount", "enabled", "choice", "range"];
      yield* show({
        type: "multi_action",
        title: "Input QA",
        pause: false,
        after_action: "none",
        body: [{ type: "plain_message", contents: "Choose values." }],
        inputs: [
          { type: "text", key: "amount", label: "Amount", initial: "1", max_length: 8 },
          {
            type: "boolean",
            key: "enabled",
            label: "Enabled",
            initial: false,
            on_true: "23",
            on_false: "24",
          },
          {
            type: "single_option",
            key: "choice",
            label: "Choice",
            options: [
              { id: "30", display: "First", initial: true },
              { id: "31", display: "Second" },
            ],
          },
          {
            type: "number_range",
            key: "range",
            label: "Range",
            start: 0,
            end: 10,
            initial: 4,
            step: 2,
          },
        ],
        actions: keys.map((key) => ({
          label: key,
          action: {
            type: "dynamic/run_command",
            template: `trigger ${OBJECTIVE} set $(${key})`,
          },
        })),
      });
      const dialog = (yield* rpc(() => service.getDialog(scope))).dialog;
      yield* check(
        dialog?.inputs.length === keys.length &&
          dialog.actions.length === keys.length &&
          dialog.body.length === 1,
        "The prompt controls and actions are missing from its state",
      );
      const polled = (yield* rpc(() => service.getDialog(scope))).dialog;
      yield* check(
        !!dialog?.screenId && polled?.screenId === dialog.screenId,
        "Polling changed the native dialog identity",
      );
      yield* resetScore;
      const submitted = yield* rpc(() =>
        service.submitDialog({
          ...scope,
          inputValues: { amount: "17", enabled: "true", choice: "31", range: "6" },
        }),
      );
      yield* check(submitted.success, "The prompt input values were rejected");
      yield* expectScore(17);
      yield* rcon(`give ${BOT_NAME} stone`);
      yield* eventually(
        rpc(() => service.getInventoryState(scope)).pipe(
          Effect.flatMap((inventory) =>
            check(
              inventory.slots.some(
                (slot) => slot.itemId === "minecraft:stone" && !!slot.iconBase64?.length,
              ),
              "The inventory did not render its item icon",
            ),
          ),
        ),
        "5 seconds",
      );
      const afterIcon = (yield* rpc(() => service.getDialog(scope))).dialog;
      yield* check(
        afterIcon?.screenId === dialog?.screenId &&
          afterIcon?.inputs.some(
            (input) => input.input.case === "text" && input.input.value.initial === "17",
          ) === true,
        "Rendering an inventory icon reset the native dialog draft",
      );

      for (const [buttonIndex, value] of [
        [1, 23],
        [2, 31],
        [3, 6],
      ] as const) {
        yield* resetScore;
        const response = yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex }));
        yield* check(response.success, "The input-dependent action was rejected");
        yield* expectScore(value);
      }
      const invalid = yield* Effect.result(
        rpc(() => service.submitDialog({ ...scope, inputValues: { amount: "99", range: "NaN" } })),
      );
      yield* check(Result.isFailure(invalid), "The invalid range value was accepted");
      const unchanged = (yield* rpc(() => service.getDialog(scope))).dialog?.inputs.find(
        (input) => input.input.case === "text",
      );
      yield* check(
        unchanged?.input.case === "text" && unchanged.input.value.initial === "17",
        "Rejected input values changed another native field",
      );
      yield* resetScore;
      yield* rpc(() =>
        service.submitDialog({ ...scope, buttonIndex: 3, inputValues: { range: "8" } }),
      );
      yield* expectScore(8);
      yield* rpc(() => service.closeDialog(scope));

      yield* show({
        type: "notice",
        title: "Parent QA",
        pause: false,
        after_action: "none",
        action: {
          label: "Open child",
          action: {
            type: "show_dialog",
            dialog: { type: "notice", title: "Child QA" },
          },
        },
      });
      yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 0 }));
      yield* eventually(
        rpc(() => service.getDialog(scope)).pipe(
          Effect.flatMap((state) =>
            check(
              state.dialog?.title === "Child QA",
              "The nested dialog state still describes its parent",
            ),
          ),
        ),
        "5 seconds",
      );
      yield* rpc(() => service.closeDialog(scope));
      yield* eventually(
        rpc(() => service.getDialog(scope)).pipe(
          Effect.flatMap((state) =>
            check(!state.dialog, "Closing the replacement dialog left stale dialog state"),
          ),
        ),
        "5 seconds",
      );
      const clipboard = randomUUID();
      yield* show({
        type: "notice",
        title: "Clipboard QA",
        can_close_with_escape: false,
        action: { label: "Copy", action: { type: "copy_to_clipboard", value: clipboard } },
      });
      const close = yield* Effect.result(rpc(() => service.closeDialog(scope)));
      yield* check(Result.isFailure(close), "A dialog that disallows Escape was dismissed");
      const copied = yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 0 }));
      yield* check(
        copied.clipboard === clipboard,
        "The native clipboard action was not forwarded to the caller",
      );
      yield* rpc(() => service.closeDialog(scope));
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
