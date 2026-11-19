import { fromBinary } from "@bufbuild/protobuf";
import { BrewTaskResultSchema } from "@soulfiremc/sdk/generated/soulfire/recipe_pb";
import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Config, Effect } from "effect";
import { CheckFailed, check, type E2ETest, eventually, present, rcon } from "../harness.ts";

const NAMES = ["SharedBrewA", "SharedBrewB"];
const STATIONS = [3927, 3930] as const;

export default {
  name: "shared-brewing",
  run: ({ client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const port = yield* Config.String("SOULFIRE_E2E_MINECRAFT_PORT").pipe(
          Config.withDefault("25566"),
          Effect.orDie,
        );
        const instance = yield* Effect.acquireRelease(
          client.getOrCreateInstance("e2e-shared-brewing", {
            server: `127.0.0.1:${port}`,
          }),
          (owned) => owned.delete().pipe(Effect.ignore),
        );
        for (const namespace of [
          "auto-eat",
          "auto-armor",
          "auto-totem",
          "auto-jump",
          "anti-afk",
          "auto-respawn",
          "auto-reconnect",
        ]) {
          yield* instance.setConfigEntry({
            namespace,
            key: "enabled",
            value: { kind: { case: "boolValue", value: false } },
          });
        }
        const bots = yield* Effect.forEach(NAMES, (name) =>
          instance.getOrCreateBot(name, { readyTimeoutMs: 60_000 }),
        );
        yield* rcon("forceload add 3920 0 3935 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            for (const [i, bot] of bots.entries()) {
              yield* bot.closeContainer().pipe(Effect.ignore);
              yield* rcon(`gamemode creative ${NAMES[i]}`, { check: false });
              yield* rcon(`clear ${NAMES[i]}`, { check: false });
              yield* rcon(`tp ${NAMES[i]} 0.5 64 0.5`, { check: false });
            }
            for (const x of STATIONS) {
              yield* rcon(`data modify block ${x} 64 8 Items set value []`, { check: false });
            }
            yield* rcon("fill 3925 63 6 3932 66 10 air", { check: false });
            yield* rcon("forceload remove 3920 0 3935 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3925 63 6 3932 63 10 stone");

        // Separate stations establish that each actor can finish. Repeated shared
        // rounds exercise competing vanilla clicks, which local prediction hides.
        for (let round = 0; round < 5; round++) {
          for (const [i, bot] of bots.entries()) {
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${NAMES[i]}`);
            yield* rcon(`clear ${NAMES[i]}`, { check: false });
            const x = i ? 3929.5 : 3926.5;
            yield* rcon(`tp ${NAMES[i]} ${x} 64 8.5`);
            yield* eventually(
              bot.world
                .player()
                .pipe(
                  Effect.flatMap(({ position }) =>
                    check(
                      !!position &&
                        Math.abs(position.x - x) < 0.01 &&
                        Math.abs(position.z - 8.5) < 0.01,
                      `${NAMES[i]} did not reach the station`,
                    ),
                  ),
                ),
              "10 seconds",
            );
            yield* bot.waitForChunks({ radiusChunks: 2, timeoutMs: 10_000 });
          }
          for (const x of STATIONS) {
            yield* rcon(`setblock ${x} 64 8 air`, { check: false });
            yield* rcon(`setblock ${x} 64 8 brewing_stand`);
            yield* rcon(
              `data merge block ${x} 64 8 {Items:[{Slot:4b,id:"minecraft:blaze_powder",count:1}]}`,
            );
          }
          for (const name of NAMES) {
            yield* rcon(
              `give ${name} potion[minecraft:potion_contents={potion:"minecraft:water"}] 1`,
            );
            yield* rcon(`give ${name} nether_wart 1`);
          }
          yield* Effect.sleep("300 millis");
          yield* Effect.scoped(
            Effect.gen(function* () {
              const tasks = yield* Effect.forEach(
                bots,
                (bot, i) =>
                  bot.tasks.brew(
                    { itemIds: ["minecraft:potion"] },
                    { itemIds: ["minecraft:nether_wart"] },
                    1,
                    {
                      station: {
                        x: round === 0 && i === 1 ? STATIONS[1] : STATIONS[0],
                        y: 64,
                        z: 8,
                      },
                      fuel: { itemIds: ["minecraft:blaze_powder"] },
                    },
                  ),
                { concurrency: 2 },
              );
              yield* Effect.addFinalizer(() =>
                Effect.forEach(tasks, (task) => task.cancel("test cleanup").pipe(Effect.ignore)),
              );
              const states = yield* Effect.forEach(
                tasks,
                (task) => task.wait({ call: { timeoutMs: 40_000 } }),
                { concurrency: 2 },
              ).pipe(
                Effect.catch((error) =>
                  Effect.flatMap(
                    Effect.forEach(tasks, (task) => task.refresh()),
                    (current) =>
                      Effect.fail(
                        new CheckFailed({
                          message: `Round ${round} brewing did not finish (${error.message}): ${current
                            .map(
                              (state) =>
                                `${BotTaskStatus[state.status]} ${JSON.stringify(state.failure ?? null)}`,
                            )
                            .join(", ")}`,
                        }),
                      ),
                  ),
                ),
              );
              yield* Effect.sleep("300 millis");
              let completed = 0;
              let retainedBottles = 0;
              for (const [i, state] of states.entries()) {
                const inventory = yield* rcon(`data get entity ${NAMES[i]} Inventory`);
                retainedBottles += countBottles(inventory);
                if (state.status === BotTaskStatus.COMPLETED) {
                  completed++;
                  const taskResult = yield* present(
                    state.result,
                    "Completed brewing task has no result",
                  );
                  const result = fromBinary(BrewTaskResultSchema, taskResult.value);
                  const [output] = result.outputs;
                  yield* check(
                    result.outputs.length === 1 &&
                      output?.itemId === "minecraft:potion" &&
                      output.potion?.potionId === "minecraft:awkward" &&
                      output.count === 1,
                    "Completed brewing task reported the wrong output",
                  );
                  const potions = yield* rcon(
                    `clear ${NAMES[i]} potion[minecraft:potion_contents={potion:"minecraft:awkward"}] 0`,
                    { check: false },
                  );
                  yield* check(
                    potions.includes("Found 1 "),
                    `${NAMES[i]} reported collection without a vanilla inventory awkward potion (round ${round})`,
                  );
                } else {
                  yield* check(
                    state.status === BotTaskStatus.FAILED,
                    "Contested brewing did not terminate",
                  );
                }
              }
              for (const x of STATIONS) {
                const output = yield* rcon(`data get block ${x} 64 8 Items`);
                retainedBottles += countBottles(output);
              }
              yield* check(completed >= (round === 0 ? 2 : 1), "No actor finished brewing");
              yield* check(retainedBottles === 2, "Contested brewing lost or duplicated a bottle");
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;

function countBottles(nbt: string) {
  return [...nbt.matchAll(/id: "minecraft:potion"/gu)].length;
}
