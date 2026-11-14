import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import path from "node:path";
import { Code, ConnectError } from "@connectrpc/connect";
import type { SoulFireClient, SoulFireInstance } from "@soulfiremc/sdk";
import { ScriptService } from "@soulfiremc/sdk/generated/soulfire/script_pb";
import { Effect } from "effect";
import { CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

export default {
  name: "instance-deletion",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const directory = client.localServer?.runDirectory;
      if (!directory)
        return yield* new CheckFailed({
          message: "Deletion test requires the local Soulfire runtime",
        });
      const settings = (yield* client.instance(bot.instanceId).info()).config?.settings;
      if (!settings)
        return yield* new CheckFailed({ message: "The source instance has no settings" });
      for (let attempt = 0; attempt < 3; attempt++) {
        yield* Effect.scoped(
          Effect.gen(function* () {
            const suffix = randomUUID().slice(0, 8);
            const name = `Delete_${suffix}`;
            const instance = yield* Effect.acquireRelease(
              client.getOrCreateInstance(`e2e-delete-${suffix}`),
              (instance) => instance.delete().pipe(Effect.ignore),
            );
            for (const namespace of settings) {
              for (const entry of namespace.entries) {
                yield* instance.setConfigEntry({
                  namespace: namespace.namespace,
                  key: entry.key,
                  value: entry.value,
                });
              }
            }
            const target = yield* instance.getOrCreateBot(name, { start: false });
            yield* target.start();
            yield* target.connect({ readyTimeoutMs: 30_000 });
            const directories = ["client-data", "object-storage"].map((root) =>
              path.join(directory, root, `instance-${instance.id}`),
            );
            yield* check(
              directories.every(existsSync),
              "The connected instance did not create its storage",
            );
            // Exercise deletion after the one-second settings cache can expire.
            yield* Effect.sleep("1500 millis");
            yield* Effect.tryPromise({
              try: () => deleteInstanceWithScripts(client, instance),
              catch: (cause) =>
                new CheckFailed({ message: `Script cleanup failed: ${String(cause)}` }),
            });
            yield* check(
              !(yield* client.instances()).some((entry) => entry.id === instance.id),
              "Deleted profile remains in the database",
            );
            yield* check(
              directories.every((directory) => !existsSync(directory)),
              "Instance deletion left native client or object storage behind",
            );
            yield* eventually(
              rcon("list").pipe(
                Effect.flatMap((players) =>
                  check(!players.includes(name), "Deleted bot remains connected to vanilla"),
                ),
              ),
              "5 seconds",
            );
            yield* check(
              (yield* bot.info()).liveState !== undefined,
              "Deleting another instance disconnected the original bot",
            );
          }),
        );
      }
    }),
} satisfies E2ETest;

async function deleteInstanceWithScripts(client: SoulFireClient, instance: SoulFireInstance) {
  const scripts = client.service(ScriptService);
  const active = await scripts.createScript({
    instanceId: instance.id,
    name: "Active deletion fixture",
    paused: true,
  });
  const paused = await scripts.createScript({
    instanceId: instance.id,
    name: "Paused deletion fixture",
    paused: true,
  });
  if (!active.script || !paused.script) throw new Error("Deletion scripts were not created");
  const abort = new AbortController();
  const events = scripts
    .activateScript(
      { instanceId: instance.id, scriptId: active.script.id },
      { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10000)]) },
    )
    [Symbol.asyncIterator]();
  try {
    if ((await events.next()).value?.event.case !== "scriptStarted")
      throw new Error("The deletion script did not start");
    await Effect.runPromise(instance.delete());
    const stopped = await events.next();
    if (stopped.value?.event.case !== "scriptCompleted" || stopped.value.event.value.success)
      throw new Error("Instance deletion did not cancel its script");
    if (!(await events.next()).done) throw new Error("The deleted script's stream did not close");
    for (const scriptId of [active.script.id, paused.script.id]) {
      try {
        await scripts.getScript({ instanceId: instance.id, scriptId });
        throw new Error("Instance deletion left a script record behind");
      } catch (error) {
        if (!(error instanceof ConnectError) || error.code !== Code.NotFound) throw error;
      }
    }
  } finally {
    abort.abort();
    await events.return?.();
  }
}
