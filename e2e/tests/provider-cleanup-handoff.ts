import {
  BotTaskConflictPolicy,
  BotTaskPriority,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "provider-cleanup-handoff",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon("fill 4260 63 4 4278 64 12 air");
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        const hooks = () =>
          bot.world.queryEntities({
            radius: 24,
            selector: { entityTypes: ["minecraft:fishing_bobber"] },
          });
        const observeHook = eventually(
          hooks().pipe(
            Effect.tap((result) => check(result.entities.length === 1, "No fishing line observed")),
            Effect.map((result) => result.entities[0]!.reference!.uuid),
          ),
          "5 seconds",
          "50 millis",
        );
        for (const provider of ["farm", "build"]) {
          for (const mode of ["queued-start", "resumed", "queued-cancel", "suspended-cancel"]) {
            yield* Effect.scoped(
              Effect.gen(function* () {
                yield* rcon("fill 4260 64 4 4278 64 12 air");
                yield* rcon("fill 4260 63 4 4278 63 12 stone");
                yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
                yield* bot.selectHotbar(0);
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with stick`);
                const material = provider === "farm" ? "wheat_seeds" : "stone";
                yield* rcon(`item replace entity ${BOT_NAME} hotbar.2 with ${material} 64`);
                yield* eventually(
                  bot.inventory
                    .count({ selector: { itemIds: [`minecraft:${material}`] } })
                    .pipe(
                      Effect.tap((count) =>
                        check(count === 64n, "The task material has not arrived"),
                      ),
                    ),
                  "5 seconds",
                  "50 millis",
                );
                const completes = mode === "queued-start" || mode === "resumed";
                if (provider === "farm" && completes) {
                  yield* rcon("setblock 4276 63 8 farmland[moisture=7]");
                  yield* rcon("setblock 4276 64 8 wheat[age=7]");
                }
                const startProvider = (conflictPolicy: BotTaskConflictPolicy) =>
                  Effect.gen(function* () {
                    if (provider === "farm") {
                      return yield* bot.tasks.farm({
                        center: { x: 4276, y: 64, z: 8 },
                        radius: 2,
                        maximumHarvests: 0,
                        completeWhenNoMatureCrops: false,
                        conflictPolicy,
                      });
                    }
                    return yield* bot.tasks.build(
                      { x: 4276, y: 64, z: 8 },
                      [{ offset: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone" }],
                      { conflictPolicy },
                    );
                  });
                const suspended = mode === "resumed" || mode === "suspended-cancel";
                const startFishing = () =>
                  bot.tasks.fish({
                    maximumCatches: 0,
                    completeWhenNoRod: false,
                    castTimeoutTicks: 20,
                    priority: suspended ? BotTaskPriority.HIGH : BotTaskPriority.NORMAL,
                    conflictPolicy: suspended
                      ? BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY
                      : BotTaskConflictPolicy.REJECT,
                  });
                let task: Effect.Success<ReturnType<typeof startProvider>>;
                let fishing: Effect.Success<ReturnType<typeof startFishing>>;
                if (suspended) {
                  task = yield* startProvider(BotTaskConflictPolicy.REJECT);
                  yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
                  fishing = yield* startFishing();
                  yield* Effect.addFinalizer(() =>
                    fishing.cancel("test cleanup").pipe(Effect.ignore),
                  );
                } else {
                  fishing = yield* startFishing();
                  yield* Effect.addFinalizer(() =>
                    fishing.cancel("test cleanup").pipe(Effect.ignore),
                  );
                  // A queued task should capture selection after its owner restores slot 0.
                  if (completes) {
                    yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with fishing_rod`);
                    yield* observeHook;
                  }
                  task = yield* startProvider(BotTaskConflictPolicy.QUEUE);
                  yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
                }
                yield* check(
                  (yield* task.refresh()).status ===
                    (suspended ? BotTaskStatus.SUSPENDED : BotTaskStatus.WAITING_FOR_RESOURCES),
                  `${provider}/${mode} did not enter the scheduling state under test`,
                );
                if (!completes || suspended) {
                  yield* rcon(`item replace entity ${BOT_NAME} hotbar.1 with fishing_rod`);
                }
                const hook = yield* observeHook;
                if (completes) {
                  yield* fishing.cancel("let the provider execute");
                  yield* fishing.wait({ call: { timeoutMs: 5000 } });
                  if (provider === "farm") {
                    yield* eventually(
                      rcon("execute if block 4276 64 8 wheat[age=0]", { check: false }).pipe(
                        Effect.tap((result) =>
                          check(result === "Test passed", "Crop not replanted"),
                        ),
                      ),
                      "15 seconds",
                      "50 millis",
                    );
                    yield* task.cancel("finish the unbounded farming cycle");
                  }
                  const terminal = yield* task.wait({ call: { timeoutMs: 15000 } });
                  yield* check(
                    terminal.status ===
                      (provider === "farm" ? BotTaskStatus.CANCELLED : BotTaskStatus.COMPLETED),
                    `${provider}/${mode} failed: ${terminal.failure?.message ?? terminal.status}`,
                  );
                  const expected = provider === "farm" ? "wheat[age=0]" : "stone";
                  yield* check(
                    (yield* rcon(`execute if block 4276 64 8 ${expected}`, { check: false })) ===
                      "Test passed",
                    `${provider}/${mode} did not finish its work on the vanilla server`,
                  );
                  yield* check(
                    (yield* bot.world.player()).selectedHotbarSlot === 0,
                    `${provider}/${mode} restored the slot captured before admission`,
                  );
                } else {
                  yield* task.cancel("cancel a provider without control");
                  yield* task.wait({ call: { timeoutMs: 5000 } });
                  yield* Effect.sleep("300 millis");
                  const observed = yield* rcon(`execute if entity ${hook}`, { check: false });
                  yield* check(
                    observed.startsWith("Test passed") &&
                      (yield* bot.world.player()).selectedHotbarSlot === 1 &&
                      (yield* fishing.refresh()).status === BotTaskStatus.RUNNING,
                    `${provider}/${mode} disturbed its current owner: ${observed}`,
                  );
                  yield* fishing.cancel("finish fishing");
                  yield* fishing.wait({ call: { timeoutMs: 5000 } });
                }
                yield* eventually(
                  hooks().pipe(
                    Effect.tap((result) => check(result.entities.length === 0, "Hook remains")),
                  ),
                  "5 seconds",
                  "50 millis",
                );
              }),
            );
          }
        }
      }),
    ),
} satisfies E2ETest;
