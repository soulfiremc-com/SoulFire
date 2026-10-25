import { goals } from "@soulfiremc/sdk";
import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

const TAG = "sf_e2e_moving_entity_goal";

export default {
  name: "moving-entity-goals",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds", "100 millis");
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 4260 63 4 4278 63 12 air");
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272, 4496]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon("fill 4260 63 4 4278 63 12 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* rcon(
          `summon cow 4275.5 64 8.5 {Tags:["${TAG}"],NoAI:1b,NoGravity:1b,Invulnerable:1b,Silent:1b,DeathLootTable:"minecraft:empty"}`,
        );
        const observe = () =>
          bot.world
            .queryEntities({
              radius: 32,
              selector: { entityTypes: ["minecraft:cow"], alive: true },
            })
            .pipe(
              Effect.map((result) => result.entities[0]?.reference),
              Effect.filterOrFail(
                (reference) => reference !== undefined,
                () => new CheckFailed({ message: "The path target is not observable" }),
              ),
            );
        const target = yield* eventually(observe(), "10 seconds", "100 millis");
        for (const name of [
          "stationary",
          "moved",
          "composite",
          "composite-unobservable",
          "away",
          "unobservable",
          "away-unobservable",
        ]) {
          const away = name.startsWith("away");
          const startX = away ? 4267.5 : 4262.5;
          const targetX = away ? 4265.5 : 4270.5;
          yield* rcon(`gamemode creative ${BOT_NAME}`);
          yield* placeBot(bot, { x: startX, y: 64, z: 8.5 }, -90);
          yield* rcon(`gamemode survival ${BOT_NAME}`);
          yield* rcon(`tp @e[tag=${TAG}] ${targetX} 64 8.5`);
          yield* eventually(
            bot.world
              .entity({ entity: target })
              .pipe(
                Effect.tap((snapshot) =>
                  check(
                    Math.abs((snapshot.entity?.position?.x ?? 0) - targetX) < 0.1,
                    "The client has not observed the target teleport",
                  ),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          const entityGoal = away ? goals.awayFromEntity(target, 8) : goals.entity(target, 1);
          const goal = name.startsWith("composite")
            ? goals.any([entityGoal, goals.block({ x: 4277, y: 64, z: 8 })])
            : entityGoal;
          const task = yield* bot.tasks.goTo(goal);
          yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
          const unavailable = name.includes("unobservable");
          if (name !== "stationary") {
            yield* eventually(
              serverPosition.pipe(
                Effect.tap((position) =>
                  check(position.x > startX + 0.4, "The bot has not started moving"),
                ),
              ),
              "5 seconds",
              "50 millis",
            );
            yield* rcon(
              `tp @e[tag=${TAG}] ${unavailable ? 4500.5 : away ? 4274.5 : 4276.5} 64 8.5`,
            );
          }
          const terminal = yield* task.wait({ call: { timeoutMs: 10000 } });
          if (unavailable && !name.startsWith("composite")) {
            yield* check(
              terminal.status === BotTaskStatus.FAILED && terminal.failure?.code === "not_found",
              `${name} completed without an observable target: ${terminal.status}`,
            );
          } else {
            yield* check(
              terminal.status === BotTaskStatus.COMPLETED,
              `${name} failed: ${terminal.failure?.message ?? terminal.status}`,
            );
            const position = yield* serverPosition;
            const finalTargetX =
              name === "stationary" ? targetX : unavailable ? 4277.5 : away ? 4274.5 : 4276.5;
            const distance = Math.hypot(position.x - finalTargetX, position.z - 8.5);
            // Movement actions use block-node goals and allow a small landing tolerance.
            yield* check(
              away ? distance >= 7.5 : distance <= (unavailable ? 0.75 : 1.5),
              `${name} reported success ${distance} blocks from the live target`,
            );
          }
        }
      }),
    ),
} satisfies E2ETest;
