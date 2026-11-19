import { BotService, DialogType } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Code, ConnectError } from "@connectrpc/connect";
import { Effect } from "effect";
import { BOT_NAME, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const URL = "https://example.invalid/soulfire-link-qa";

const rpc = <A>(call: () => Promise<A>) =>
  Effect.tryPromise({
    try: call,
    catch: (cause) => new CheckFailed({ message: `Link dialog RPC failed: ${String(cause)}` }),
  });

export default {
  name: "dialog-link-confirmation",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const service = client.service(BotService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      yield* rcon(
        `dialog show ${BOT_NAME} {type:"minecraft:notice",title:"Link QA",pause:false,after_action:"none",action:{label:"Open link",action:{type:"open_url",url:"${URL}"}}}`,
      );
      const parent = yield* eventually(
        rpc(() => service.getDialog(scope)).pipe(
          Effect.flatMap((state) =>
            state.dialog
              ? Effect.succeed(state.dialog)
              : Effect.fail(new CheckFailed({ message: "The link notice did not appear" })),
          ),
        ),
        "5 seconds",
      );
      const open = () =>
        rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 0 })).pipe(
          Effect.flatMap((result) =>
            check(
              result.success && result.openUrl === undefined,
              "The link opened before native confirmation",
            ),
          ),
          Effect.andThen(
            eventually(
              rpc(() => service.getDialog(scope)).pipe(
                Effect.flatMap((state) =>
                  state.dialog?.type === DialogType.CONFIRMATION &&
                  state.dialog.screenId !== parent.screenId
                    ? Effect.succeed(state.dialog)
                    : Effect.fail(
                        new CheckFailed({
                          message: "The native URL confirmation disappeared from the dialog API",
                        }),
                      ),
                ),
              ),
              "5 seconds",
            ),
          ),
        );
      const returned = () =>
        eventually(
          rpc(() => service.getDialog(scope)).pipe(
            Effect.flatMap((state) =>
              check(
                state.dialog?.screenId === parent.screenId,
                "The native callback did not return to the original dialog",
              ),
            ),
          ),
          "5 seconds",
        );

      const confirmation = yield* open();
      yield* check(
        confirmation.body.some(
          (element) =>
            element.element.case === "plainMessage" && element.element.value.contents.includes(URL),
        ),
        "The confirmation does not expose the link destination",
      );
      yield* rpc(async () => {
        try {
          await service.submitDialog({
            ...scope,
            buttonIndex: 0,
            inputValues: { unexpected: "value" },
          });
        } catch (error) {
          if (error instanceof ConnectError && error.code === Code.InvalidArgument) return;
          throw error;
        }
        throw new Error("A confirmation accepted inputs that it does not have");
      });
      const unchanged = yield* rpc(() => service.getDialog(scope));
      yield* check(
        unchanged.dialog?.screenId === confirmation.screenId,
        "Invalid input changed the native confirmation",
      );
      const copied = yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 1 }));
      yield* check(
        copied.success && copied.clipboard === URL && copied.openUrl === undefined,
        "Copy Link did not preserve the native clipboard action",
      );
      yield* returned();
      const replacement = yield* open();
      yield* check(
        replacement.screenId !== confirmation.screenId,
        "A replacement confirmation reused the previous screen identity",
      );
      yield* rpc(async () => {
        try {
          await service.closeDialog({ ...scope, expectedScreenId: confirmation.screenId });
        } catch (error) {
          if (error instanceof ConnectError && error.code === Code.Aborted) return;
          throw error;
        }
        throw new Error("An old confirmation dismissed its replacement");
      });
      yield* check(
        (yield* rpc(() => service.getDialog(scope))).dialog?.screenId === replacement.screenId,
        "Rejecting a stale confirmation changed the current screen",
      );
      const declined = yield* rpc(() => service.clickDialogButton({ ...scope, buttonIndex: 2 }));
      yield* check(declined.success && declined.openUrl === undefined, "Declining opened the link");
      yield* returned();
      yield* open();
      const accepted = yield* rpc(() => service.submitDialog({ ...scope, buttonIndex: 0 }));
      yield* check(
        accepted.success && accepted.openUrl === URL,
        "The native confirmation did not forward the accepted URL",
      );
      yield* returned();
      yield* open();
      const dismissed = yield* rpc(() => service.closeDialog(scope));
      yield* check(
        dismissed.success && dismissed.openUrl === undefined,
        "Dismissing the confirmation opened the link",
      );
      yield* returned();
    }).pipe(
      Effect.ensuring(rcon(`dialog clear ${BOT_NAME}`, { check: false }).pipe(Effect.ignore)),
    ),
} satisfies E2ETest;
