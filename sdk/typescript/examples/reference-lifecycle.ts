import { Effect, Stream } from "effect";
import { SoulFire } from "../src/node.js";

// Use the SoulFire gRPC-Web address here, not the Minecraft address.
// #region collectWithClient
export const collectWithClient = Effect.scoped(
  Effect.gen(function* () {
    const client = yield* SoulFire.connect({
      baseUrl: "http://localhost:38765",
      token: () => process.env.SOULFIRE_TOKEN,
    });
    const instance = yield* client.getOrCreateInstance("reference-example", {
      server: "localhost:25565",
    });
    const bot = yield* instance.getOrCreateBot("Builder", {
      username: "Builder",
      readyTimeoutMs: 30_000,
    });
    const result = yield* bot.collect("#minecraft:logs", { count: 16 });
    yield* Effect.logInfo(result);
  }),
);
// #endregion collectWithClient

// #region collectWithTaskHandle
export const collectWithTaskHandle = Effect.scoped(
  Effect.gen(function* () {
    const bot = yield* SoulFire.createBot({
      server: "localhost:25565",
      username: "Builder",
    });
    const task = yield* bot.tasks.collectBlocks([], {
      tags: ["minecraft:logs"],
      count: 16,
      deadline: new Date(Date.now() + 120_000),
    });
    yield* Effect.logInfo(`Accepted task ${task.id}`);
    const result = yield* task.result();
    yield* Effect.logInfo(result);
  }),
);
// #endregion collectWithTaskHandle

// #region collectWithProgress
export const collectWithProgress = Effect.scoped(
  Effect.gen(function* () {
    const bot = yield* SoulFire.createBot({
      server: "localhost:25565",
      username: "Builder",
    });
    yield* bot.tasks.runCollectBlocks([], {
      tags: ["minecraft:logs"],
      count: 16,
    }).pipe(
      Stream.runForEach((event) => Effect.logInfo(event.task?.summary ?? "Task update")),
    );
  }),
);
// #endregion collectWithProgress

// Choose one workflow and run it at your application's boundary:
// await Effect.runPromise(collectWithClient);
