import { Code } from "@connectrpc/connect";
import { Effect, Result } from "effect";
import { check, type E2ETest, present } from "../harness.ts";

export default {
  name: "world-chunks",
  run: ({ bot }) =>
    Effect.gen(function* () {
      const nearby = yield* bot.waitForChunks({ radiusChunks: 0, timeoutMs: 5000 });
      yield* check(
        nearby.loadedChunks === 1 && nearby.requiredChunks === 1,
        "The player's own chunk was not ready",
      );
      // This fixture has render distance 6. A radius of 16 includes chunks
      // outside the client's cache, so the server cannot have sent them all.
      const distantWait = yield* Effect.result(
        bot.waitForChunks({ radiusChunks: 16, timeoutMs: 200 }),
      );
      const position = { x: 10_000_000, y: 64, z: 10_000_000 };
      const legacyBlock = yield* bot.getBlock({ position });
      const distantBlock = yield* Effect.result(bot.world.block({ position }));
      const query = yield* bot.world.queryBlocks({
        region: { region: { case: "box", value: { minimum: position, maximum: position } } },
        selector: { blockIds: ["minecraft:air"] },
      });
      yield* check(
        Result.isFailure(distantWait) &&
          distantWait.failure._tag === "SoulFireRpcError" &&
          distantWait.failure.code === Code.DeadlineExceeded,
        "Waiting for chunks outside the client cache reported them as loaded",
      );
      yield* check(
        Result.isFailure(distantBlock) &&
          distantBlock.failure._tag === "SoulFireRpcError" &&
          distantBlock.failure.code === Code.FailedPrecondition,
        "An unloaded block was returned as observed world data",
      );
      yield* check(
        !legacyBlock.loaded && !legacyBlock.block,
        "The block lookup reported an unloaded chunk as loaded",
      );
      yield* check(
        query.blocks.length === 0,
        "The loaded-only block query returned air from an unloaded chunk",
      );
      const playerPosition = yield* present(
        (yield* bot.world.player()).position,
        "The player position is missing",
      );
      const actual = yield* bot.world.block({
        position: {
          x: Math.floor(playerPosition.x),
          y: Math.floor(playerPosition.y),
          z: Math.floor(playerPosition.z),
        },
      });
      yield* check(actual.block !== undefined, "A loaded block became unavailable");
    }),
} satisfies E2ETest;
