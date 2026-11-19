import { CommandService } from "@soulfiremc/sdk/generated/soulfire/command_pb";
import { Effect } from "effect";
import { randomUuid, CheckFailed, check, type E2ETest } from "../harness.ts";

export default {
  name: "instance-name-runtime",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const instance = client.instance(bot.instanceId);
      const originalName = (yield* instance.info()).friendlyName;
      const commands = client.service(CommandService);
      const suffix = randomUuid().slice(0, 8);
      yield* Effect.gen(function* () {
        let previous = originalName;
        for (const name of [`NameQA_${suffix}`, `Renamed_${suffix}`]) {
          const previousName = previous;
          yield* instance.updateMetadata({
            meta: { case: "friendlyName", value: ` \u00A0${name}\uFEFF ` },
          });
          const result = yield* Effect.tryPromise({
            try: () =>
              commands.tabCompleteCommand({
                scope: { scope: { case: "global", value: {} } },
                command: "instance ",
                cursor: 9,
              }),
            catch: (cause) =>
              new CheckFailed({ message: `Instance name completion failed: ${String(cause)}` }),
          });
          yield* check(
            result.suggestions.some((entry) => entry.suggestion === name) &&
              !result.suggestions.some((entry) => entry.suggestion === previousName),
            "Command completion did not immediately adopt the committed instance name",
          );
          yield* check(
            (yield* instance.info()).friendlyName === name,
            "The stored and runtime names disagree",
          );
          yield* check(
            (yield* bot.world.player()).position !== undefined,
            "The connected bot stopped responding after rename",
          );
          previous = name;
        }
      }).pipe(
        Effect.ensuring(
          instance
            .updateMetadata({ meta: { case: "friendlyName", value: originalName } })
            .pipe(Effect.ignore),
        ),
      );
    }),
} satisfies E2ETest;
