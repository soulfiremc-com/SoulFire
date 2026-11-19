import { randomUUID } from "node:crypto";
import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { BotTaskService, BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

export default {
  name: "auto-eat",
  run: ({ bot, client }) => {
    const plugin = (enabled: boolean) =>
      client.instance(bot.instanceId).setConfigEntry({
        namespace: "auto-eat",
        key: "enabled",
        value: { kind: { case: "boolValue", value: enabled } },
      });
    return Effect.gen(function* () {
      const runPromise = Effect.runPromiseWith(yield* Effect.context());
      const previousDifficulty = (yield* rcon("difficulty"))
        .match(/difficulty is (Peaceful|Easy|Normal|Hard)/u)?.[1]
        ?.toLowerCase();
      yield* check(!!previousDifficulty, "Cannot determine the difficulty to restore");
      const failures: string[] = [];
      yield* Effect.gen(function* () {
        yield* rcon("difficulty hard");
        yield* rcon("forceload add 864 0");
        yield* rcon("fill 864 63 0 868 63 4 stone");
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* placeBot(bot, { x: 866.5, y: 64, z: 2.5 });
        for (const [mode, item] of [
          ["sdk", "bread"],
          ["sdk", "dried_kelp"],
          ["sdk-handoff", "dried_kelp"],
          ["plugin", "bread"],
        ]) {
          yield* plugin(false);
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(`effect give ${BOT_NAME} instant_health 1 100 true`);
          yield* rcon(`effect give ${BOT_NAME} saturation 1 255 true`);
          yield* Effect.sleep("1100 millis");
          yield* rcon(`effect give ${BOT_NAME} hunger 10 255 true`);
          yield* eventually(
            bot
              .info()
              .pipe(
                Effect.flatMap((info) =>
                  check(
                    !!info.liveState && info.liveState.foodLevel <= 10,
                    "The hunger fixture never took effect",
                  ),
                ),
              ),
            "10 seconds",
            "100 millis",
          );
          yield* rcon(`effect clear ${BOT_NAME} hunger`);
          const giveFood = rcon(
            `item replace entity ${BOT_NAME} hotbar.0 with minecraft:${item} 3`,
          );
          yield* bot.selectHotbar(0);
          // Start the handoff task without food so it waits while its POV initializes.
          if (mode !== "sdk-handoff") {
            yield* giveFood;
            yield* eventually(
              bot.inventoryState().pipe(
                Effect.flatMap((state) =>
                  check(
                    state.slots.some(
                      (slot) =>
                        slot.slot === 36 && slot.itemId === `minecraft:${item}` && slot.count === 3,
                    ),
                    "The food fixture never reached the bot",
                  ),
                ),
              ),
              "5 seconds",
            );
          }
          if (mode !== "plugin") {
            const task = yield* bot.tasks.autoEat([], { checkIntervalTicks: 1, maximumMeals: 1 });
            if (mode === "sdk-handoff") {
              yield* Effect.tryPromise({
                try: async () => {
                  const abort = new AbortController();
                  const pov = client.service(PovService);
                  const sessionId = randomUUID();
                  let sequence = 0n;
                  let tookControl = false;
                  try {
                    for await (const frame of pov.watch(
                      {
                        instanceId: bot.instanceId,
                        botId: bot.id,
                        sessionId,
                        width: 640,
                        height: 480,
                        maxFps: 15,
                      },
                      { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10000)]) },
                    )) {
                      await runPromise(giveFood);
                      await runPromise(
                        eventually(
                          bot.inventoryState().pipe(
                            Effect.flatMap((state) =>
                              check(
                                state.slots.some((slot) => slot.slot === 36 && slot.count === 2),
                                "The meal never completed before control takeover",
                              ),
                            ),
                          ),
                          "5 seconds",
                          "20 millis",
                        ),
                      );
                      if (
                        (await client.service(BotTaskService).getBotTask({ taskId: task.id }))
                          .status !== BotTaskStatus.RUNNING
                      )
                        throw new Error("Control takeover missed the active meal");
                      await pov.input({
                        sessionId,
                        inputToken: frame.inputToken,
                        sequence: ++sequence,
                        width: 640,
                        height: 480,
                        captured: true,
                      });
                      tookControl = true;
                      await new Promise((resolve) => {
                        setTimeout(resolve, 300);
                      });
                      await pov.input({
                        sessionId,
                        inputToken: frame.inputToken,
                        sequence: ++sequence,
                        width: 640,
                        height: 480,
                        captured: false,
                      });
                      break;
                    }
                  } finally {
                    abort.abort();
                  }
                  if (!tookControl) throw new Error("No frame was available for control takeover");
                },
                catch: (cause) =>
                  new CheckFailed({ message: `POV control takeover failed: ${String(cause)}` }),
              });
            }
            const result = yield* task.result({ call: { timeoutMs: 10000 } });
            if (result.mealsEaten !== 1)
              failures.push(`${item} task reported ${result.mealsEaten} meals`);
          } else {
            yield* plugin(true);
            yield* Effect.result(
              eventually(
                bot
                  .info()
                  .pipe(
                    Effect.flatMap((info) =>
                      check(
                        (info.liveState?.foodLevel ?? 0) > 10,
                        "The automatic plugin never ate",
                      ),
                    ),
                  ),
                "6 seconds",
              ),
            );
            yield* plugin(false);
            yield* Effect.sleep("1200 millis");
          }
          const count = yield* rcon(`data get entity ${BOT_NAME} Inventory[{Slot:0b}].count`, {
            check: false,
          });
          if (!count.endsWith(": 2"))
            failures.push(`${mode}/${item} did not consume exactly one item: ${count}`);
        }
        yield* check(failures.length === 0, failures.join("; "));
      }).pipe(
        Effect.ensuring(
          Effect.gen(function* () {
            for (const cleanup of [
              plugin(false),
              rcon(`effect clear ${BOT_NAME}`, { check: false }),
              rcon(`clear ${BOT_NAME}`, { check: false }),
              rcon(`gamemode creative ${BOT_NAME}`),
              rcon("fill 864 63 0 868 63 4 air"),
              rcon("forceload remove 864 0"),
            ])
              yield* cleanup.pipe(Effect.ignore);
            yield* rcon(`difficulty ${previousDifficulty}`).pipe(Effect.orDie);
          }),
        ),
      );
    });
  },
} satisfies E2ETest;
