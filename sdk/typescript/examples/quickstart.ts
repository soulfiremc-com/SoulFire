import { NodeRuntime } from "@effect/platform-node";
import { Effect } from "effect";
import { SoulFire } from "../src/node.js";

const program = Effect.scoped(
  Effect.gen(function* () {
    const bot = yield* SoulFire.createBot({
      server: "localhost:25565",
      username: "Builder",
    });
    yield* bot.chat.send("Hello from Soulfire");
    yield* Effect.logInfo(`Health: ${bot.state.player?.health}`);
  }),
);

NodeRuntime.runMain(program);
