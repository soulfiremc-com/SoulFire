import { Code } from "@connectrpc/connect";
import { create } from "@bufbuild/protobuf";
import { MinecraftAccountProtoSchema } from "@soulfiremc/sdk/generated/soulfire/common_pb";
import { Effect, Result } from "effect";
import { randomUuid, CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

export default {
  name: "account-replacement",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const source = (yield* client.instance(bot.instanceId).info()).config;
        if (!source)
          return yield* new CheckFailed({ message: "The source profile has no configuration" });
        const suffix = randomUuid().slice(0, 8);
        const instance = yield* Effect.acquireRelease(
          client.getOrCreateInstance(`e2e-replace-${suffix}`),
          (created) => created.delete().pipe(Effect.ignore),
        );
        for (const namespace of source.settings) {
          for (const entry of namespace.entries) {
            yield* instance.setConfigEntry({
              namespace: namespace.namespace,
              key: entry.key,
              value: entry.value,
            });
          }
        }
        const keeperName = `Keep_${suffix}`;
        const removedName = `Drop_${suffix}`;
        const keeper = yield* instance.getOrCreateBot(keeperName, { start: false });
        const removed = yield* instance.getOrCreateBot(removedName, { start: false });
        for (const target of [keeper, removed]) {
          yield* target.start();
          yield* target.connect({ readyTimeoutMs: 30_000 });
        }
        const accounts = (yield* instance.info()).config?.accounts ?? [];
        const retained = accounts.find((account) => account.profileId === keeper.id);
        if (!retained)
          return yield* new CheckFailed({ message: "The retained account was not stored" });
        const epoch = keeper.state.epoch;
        const invalid = create(MinecraftAccountProtoSchema, {
          profileId: removed.id,
          lastKnownName: removedName,
        });
        const rejected = yield* Effect.result(instance.replaceAccounts([retained, invalid]));
        yield* check(
          Result.isFailure(rejected) &&
            rejected.failure._tag === "SoulFireRpcError" &&
            rejected.failure.code === Code.InvalidArgument,
          "The invalid replacement was not rejected before removing accounts",
        );
        yield* check(
          (yield* instance.info()).config?.accounts.length === 2,
          "A rejected replacement changed the stored accounts",
        );
        const before = yield* rcon("list");
        yield* check(
          before.includes(keeperName) && before.includes(removedName),
          "A rejected replacement disconnected an existing bot",
        );

        yield* instance.replaceAccounts([retained]);
        yield* eventually(
          rcon("list").pipe(
            Effect.flatMap((players) =>
              check(
                players.includes(keeperName) && !players.includes(removedName),
                "Replacement did not stop only the removed account's bot",
              ),
            ),
          ),
          "10 seconds",
        );
        yield* check(
          (yield* keeper.info()).liveState !== undefined && keeper.state.epoch === epoch,
          "Replacing another account restarted the retained bot",
        );
        yield* check(
          (yield* instance.info()).config?.accounts.length === 1,
          "Replacement did not persist exactly the retained account",
        );

        yield* instance.replaceAccounts([]);
        yield* eventually(
          rcon("list").pipe(
            Effect.flatMap((players) =>
              check(
                !players.includes(keeperName) && !players.includes(removedName),
                "Empty replacement left a bot connected to vanilla",
              ),
            ),
          ),
          "10 seconds",
        );
        yield* check(
          (yield* instance.info()).config?.accounts.length === 0,
          "Empty replacement did not clear the account list",
        );
        yield* check(
          (yield* bot.info()).liveState !== undefined,
          "Replacement in another profile disconnected the harness bot",
        );
        return undefined;
      }),
    ),
} satisfies E2ETest;
