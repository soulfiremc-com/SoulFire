import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  encodeJson,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

const TAG = "sf_e2e_combat_lifecycle";

export default {
  name: "combat-target-lifecycle",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3200 0 3263 15");
        yield* rcon("forceload add 3600 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3200 63 0 3263 63 15 air", { check: false });
            yield* rcon("forceload remove 3200 0 3263 15", { check: false });
            yield* rcon("forceload remove 3600 0", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3200 63 0 3263 63 15 stone");
        yield* placeBot(bot, { x: 3201.5, y: 64, z: 8.5 }, -90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(`summon cow 3212.5 64 8.5 {Tags:["${TAG}"],NoAI:1b,NoGravity:1b}`);
        const target = yield* eventually(
          bot.world
            .queryEntities({
              radius: 16,
              selector: { entityTypes: ["minecraft:cow"], alive: true },
            })
            .pipe(
              Effect.map((result) => result.entities[0]?.reference),
              Effect.filterOrFail(
                (reference) => reference !== undefined,
                () => new CheckFailed({ message: "The cow never became observable" }),
              ),
            ),
          "5 seconds",
        );
        const task = yield* bot.tasks.attackEntity(target, {
          selectBestWeapon: false,
          restoreSelectedSlot: false,
          targetUnavailableTimeoutSeconds: 10,
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        yield* eventually(
          serverPosition.pipe(
            Effect.flatMap((p) => check(p.x > 3202.3, "The bot never began chasing the cow")),
          ),
          "5 seconds",
        );
        yield* rcon(`tp @e[tag=${TAG}] 3600.5 64 8.5`);
        yield* eventually(
          bot.world
            .queryEntities({ radius: 24, selector: { networkId: target.networkId } })
            .pipe(
              Effect.flatMap((result) =>
                check(result.entities.length === 0, "The target is still observable"),
              ),
            ),
          "5 seconds",
        );
        // Let existing jump momentum settle before measuring continued input.
        yield* Effect.sleep("800 millis");
        const before = yield* serverPosition;
        yield* Effect.sleep("1000 millis");
        const after = yield* serverPosition;
        yield* check(
          Math.hypot(after.x - before.x, after.z - before.z) < 0.05,
          `The bot kept moving while its target was unavailable: ${encodeJson({ before, after })}`,
        );
        yield* check(
          (yield* task.refresh()).status === BotTaskStatus.RUNNING,
          "The task did not wait for its unavailable target",
        );
        yield* rcon(`tp @e[tag=${TAG}] ${after.x + 10} 64 ${after.z}`);
        yield* eventually(
          serverPosition.pipe(
            Effect.flatMap((p) =>
              check(p.x > after.x + 0.8, "The bot did not resume chasing its returning target"),
            ),
          ),
          "5 seconds",
        );
        yield* task.cancel("verify pursuit cancellation");
        yield* check(
          (yield* task.wait({ call: { timeoutMs: 5000 } })).status === BotTaskStatus.CANCELLED,
          "The pursuit task did not cancel",
        );
        yield* Effect.sleep("800 millis");
        const stopped = yield* serverPosition;
        yield* Effect.sleep("700 millis");
        const final = yield* serverPosition;
        yield* check(
          Math.hypot(final.x - stopped.x, final.z - stopped.z) < 0.05,
          "The cancelled task kept moving the bot",
        );
      }),
    ),
} satisfies E2ETest;
