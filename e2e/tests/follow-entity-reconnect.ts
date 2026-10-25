import { goals } from "@soulfiremc/sdk";
import {
  BotTaskConflictPolicy,
  BotTaskReconnectPolicy,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
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

const TAG = "sf_e2e_follow_reconnect";

export default {
  name: "follow-entity-reconnect",
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
                () => new CheckFailed({ message: "The follow target is not observable" }),
              ),
            );
        const target = yield* eventually(observe(), "10 seconds", "100 millis");
        const near = (x: number) =>
          eventually(
            serverPosition.pipe(
              Effect.tap((position) =>
                check(
                  Math.hypot(position.x - x, position.z - 8.5) <= 3,
                  `The follower did not reach ${x}: ${JSON.stringify(position)}`,
                ),
              ),
            ),
            "20 seconds",
            "100 millis",
          );
        const task = yield* bot.tasks.followEntity(target.networkId, 2, {
          targetUnavailableTimeoutSeconds: 5,
          reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        yield* near(4275.5);
        let epoch = (yield* bot.world.player()).connectionEpoch;
        for (let cycle = 0; cycle < 3; cycle++) {
          yield* bot.stop();
          yield* eventually(
            task
              .refresh()
              .pipe(
                Effect.tap((task) =>
                  check(task.status === BotTaskStatus.RECOVERING, "The follow task did not pause"),
                ),
              ),
            "10 seconds",
            "50 millis",
          );
          const x = cycle % 2 === 0 ? 4263.5 : 4275.5;
          // Keep the target genuinely outside tracking range while login completes.
          yield* rcon(`tp @e[tag=${TAG}] 4500.5 64 8.5`);
          yield* bot.start();
          const player = yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((player) =>
                  check(
                    player.connectionEpoch !== epoch,
                    "The follower still has its old connection",
                  ),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          epoch = player.connectionEpoch;
          yield* Effect.sleep("1 second");
          yield* eventually(
            task
              .refresh()
              .pipe(
                Effect.tap((task) =>
                  check(
                    task.status === BotTaskStatus.RUNNING,
                    `The follow task did not recover: ${task.status}, ${task.failure?.code ?? ""}`,
                  ),
                ),
              ),
            "10 seconds",
            "50 millis",
          );
          yield* rcon(`tp @e[tag=${TAG}] ${x} 64 8.5`);
          const observed = yield* eventually(observe(), "5 seconds", "50 millis");
          yield* check(
            observed.uuid === target.uuid && observed.networkId === target.networkId,
            "Recovery observed a different target",
          );
          yield* near(x);
        }
        yield* task.cancel("Verify recovered task cancellation");
        yield* check(
          (yield* task.wait({ call: { timeoutMs: 5000 } })).status === BotTaskStatus.CANCELLED,
          "The recovered follow task did not cancel",
        );
        const position = yield* serverPosition;
        const sentinel = yield* bot.tasks.goTo(
          goals.block({
            x: Math.floor(position.x),
            y: 64,
            z: Math.floor(position.z),
          }),
          { conflictPolicy: BotTaskConflictPolicy.REJECT },
        );
        yield* sentinel.result({ call: { timeoutMs: 5000 } });
      }),
    ),
} satisfies E2ETest;
