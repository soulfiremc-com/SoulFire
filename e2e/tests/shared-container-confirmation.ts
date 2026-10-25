import { fromBinary } from "@bufbuild/protobuf";
import { Code, ConnectError } from "@connectrpc/connect";
import { InventoryArea, InventoryService } from "@soulfiremc/sdk/generated/soulfire/inventory_pb";
import {
  BotTaskStatus,
  ContainerTransferCompletionReason,
  ContainerTransferTaskResultSchema,
  MaintainLoadoutTaskResultSchema,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { CheckFailed, check, type E2ETest, eventually, rcon } from "../harness.ts";

const NAMES = ["FleetE2EA", "FleetE2EB"];
const CHEST = { x: 4263, y: 64, z: 8 };
const selector = { itemIds: ["minecraft:diamond"] };
const materialCount = (nbt: string, item = "minecraft:diamond") =>
  [...nbt.matchAll(/\{[^{}]+\}/g)]
    .filter(([entry]) => entry.includes(`id: "${item}"`))
    .reduce((sum, [entry]) => sum + Number(entry.match(/count: (\d+)/)?.[1] ?? 0), 0);

type TransferResult = {
  index: number;
  completed: boolean;
  transferred?: number;
  finalCount?: number;
  satisfied?: boolean;
};

export default {
  name: "shared-container-confirmation",
  run: ({ client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const online = yield* rcon("list");
        yield* check(
          NAMES.every((name) => !online.includes(name)),
          "Fixture bots already online",
        );
        const instance = yield* Effect.acquireRelease(
          client.getOrCreateInstance("e2e-shared-container-confirm", {
            server: `127.0.0.1:${process.env.SOULFIRE_E2E_MINECRAFT_PORT ?? 25566}`,
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
          instance.getOrCreateBot(name, { readyTimeoutMs: 60000 }),
        );
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            for (const [index, bot] of bots.entries()) {
              const name = NAMES[index]!;
              yield* bot.closeContainer().pipe(Effect.ignore);
              yield* Effect.sleep("200 millis");
              yield* rcon(`gamemode creative ${name}`);
              yield* rcon(`clear ${name}`, { check: false });
              yield* rcon(`effect clear ${name}`, { check: false });
              yield* bot.selectHotbar(0);
              yield* rcon(`tp ${name} 0.5 64 0.5`);
              yield* eventually(
                bot.world
                  .player()
                  .pipe(
                    Effect.tap((player) =>
                      check(
                        player.position?.x === 0.5 &&
                          player.position.y === 64 &&
                          player.position.z === 0.5,
                        "Cleanup position did not arrive",
                      ),
                    ),
                  ),
                "10 seconds",
                "100 millis",
              );
              yield* bot.stop();
            }
            yield* rcon("data modify block 4263 64 8 Items set value []", { check: false });
            yield* rcon("setblock 4263 64 8 air", { check: false });
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        yield* rcon("setblock 4263 64 8 chest");
        for (const [index, bot] of bots.entries()) {
          yield* rcon(`gamemode survival ${NAMES[index]}`);
          yield* rcon(`tp ${NAMES[index]} 4264.5 64 ${index ? 9.5 : 7.5}`);
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.tap((player) =>
                  check(
                    player.position?.x === 4264.5 && player.health === 20,
                    "Fixture bot did not arrive",
                  ),
                ),
              ),
            "10 seconds",
            "100 millis",
          );
          yield* bot.waitForChunks({ radiusChunks: 2, timeoutMs: 10000 });
        }
        const service = client.service(InventoryService);
        const run = Effect.runPromiseWith(yield* Effect.context());
        for (const mode of ["inventory", "task", "loadout"] as const) {
          // Two independent controls, repeated full/partial withdrawals, then two
          // depositors competing for the only available stack in a full chest.
          for (let round = 0; round < 18; round++) {
            const deposit = round >= 14;
            const amount = round >= 10 && round < 14 ? 32 : 64;
            for (const bot of bots) yield* bot.closeContainer();
            yield* Effect.sleep("200 millis");
            for (const name of NAMES) {
              yield* rcon(`clear ${name}`, { check: false });
              if (deposit) yield* rcon(`give ${name} diamond 64`);
            }
            const items = deposit
              ? Array.from(
                  { length: 26 },
                  (_, slot) => `{Slot:${slot + 1}b,id:"stone",count:64}`,
                ).join(",")
              : '{Slot:0b,id:"minecraft:diamond",count:64}';
            yield* rcon(`data merge block 4263 64 8 {Items:[${items}]}`);
            for (const bot of bots) {
              yield* eventually(
                bot
                  .inventoryState()
                  .pipe(
                    Effect.tap((state) =>
                      check(
                        state.slots
                          .filter((slot) => slot.itemId === "minecraft:diamond")
                          .reduce((sum, slot) => sum + slot.count, 0) === (deposit ? 64 : 0),
                        "The player's starting inventory did not arrive",
                      ),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
            }
            const containers =
              mode === "inventory"
                ? yield* Effect.forEach(bots, (bot) => bot.inventory.open(CHEST))
                : [];
            for (const container of containers) {
              yield* eventually(
                container
                  .refresh()
                  .pipe(
                    Effect.tap((state) =>
                      check(
                        state.slots.filter(
                          (slot) => slot.area === InventoryArea.CONTAINER && !!slot.item,
                        ).length === (deposit ? 26 : 1),
                        "Shared contents did not arrive",
                      ),
                    ),
                  ),
                "5 seconds",
                "50 millis",
              );
            }
            const actors = round < 2 ? [round] : [0, 1];
            const results = yield* Effect.forEach(
              actors,
              (index) =>
                Effect.tryPromise({
                  try: async (): Promise<TransferResult> => {
                    try {
                      if (mode !== "inventory") {
                        const bot = bots[index]!;
                        const options = { closeContainer: round % 2 === 0 };
                        const task =
                          mode === "task"
                            ? await run(
                                deposit
                                  ? bot.tasks.stash(CHEST, [{ selector, count: amount }], options)
                                  : bot.tasks.withdraw(
                                      CHEST,
                                      [{ selector, count: amount }],
                                      options,
                                    ),
                              )
                            : await run(
                                bot.tasks.maintainLoadout(
                                  CHEST,
                                  [
                                    {
                                      selector,
                                      minimumCount: deposit ? 0 : amount,
                                      targetCount: deposit ? 0 : amount,
                                      maximumCount: deposit ? 1 : amount,
                                    },
                                  ],
                                  {
                                    ...options,
                                    maximumRebalances: 1,
                                    completeWhenSatisfied: true,
                                  },
                                ),
                              );
                        const state = await run(task.wait({ call: { timeoutMs: 15000 } }));
                        if (state.status === BotTaskStatus.FAILED) {
                          if ((await run(bot.inventory.snapshot())).containerId > 0)
                            throw new Error("A failed shared task left its container open");
                          return { index, completed: false };
                        }
                        if (state.status !== BotTaskStatus.COMPLETED || !state.result)
                          throw new Error("Shared task did not complete or fail");
                        if (mode === "task") {
                          const result = fromBinary(
                            ContainerTransferTaskResultSchema,
                            state.result.value,
                          );
                          const container = await run(bot.inventory.snapshot());
                          if (container.containerId > 0 === options.closeContainer)
                            throw new Error("Task did not honor its close-container policy");
                          return { index, completed: true, transferred: result.totalTransferred };
                        }
                        const result = fromBinary(
                          MaintainLoadoutTaskResultSchema,
                          state.result.value,
                        );
                        return {
                          index,
                          completed: true,
                          transferred: deposit ? result.totalDeposited : result.totalWithdrawn,
                          finalCount: result.requirements[0]!.finalCount,
                          satisfied: result.requirements[0]!.satisfied,
                        };
                      }
                      const result = await service.transferItems({
                        scope: { instanceId: instance.id, botId: bots[index]!.id },
                        menuId: containers[index]!.snapshot.menuId,
                        selector,
                        count: amount,
                        from: deposit ? InventoryArea.PLAYER : InventoryArea.CONTAINER,
                        to: deposit ? InventoryArea.CONTAINER : InventoryArea.PLAYER,
                      });
                      const reported = result.container?.slots
                        .filter(
                          (slot) =>
                            [InventoryArea.MAIN, InventoryArea.HOTBAR].includes(slot.area) &&
                            slot.item?.itemId === "minecraft:diamond",
                        )
                        .reduce((sum, slot) => sum + slot.item!.count, 0);
                      if (reported === undefined) throw new Error("Missing confirmed inventory");
                      return {
                        index,
                        completed: true,
                        transferred: deposit ? 64 - reported : reported,
                      };
                    } catch (cause) {
                      if (
                        cause instanceof ConnectError &&
                        [Code.Aborted, Code.FailedPrecondition, Code.ResourceExhausted].includes(
                          cause.code,
                        )
                      )
                        return { index, completed: false };
                      throw cause;
                    }
                  },
                  catch: (cause) => new CheckFailed({ message: String(cause) }),
                }),
              { concurrency: 2 },
            );
            for (const bot of bots) yield* bot.closeContainer();
            yield* Effect.sleep("300 millis");
            let retained = materialCount(yield* rcon("data get block 4263 64 8 Items"));
            let completed = 0;
            for (const [index, name] of NAMES.entries()) {
              const inventory = materialCount(yield* rcon(`data get entity ${name} Inventory`));
              retained += inventory;
              const result = results.find((value) => value.index === index);
              if (result?.completed) {
                const actual = deposit ? 64 - inventory : inventory;
                if (actual > 0) completed++;
                yield* check(
                  result.transferred === actual,
                  `Shared ${mode} reported an unconfirmed transfer (round ${round})`,
                );
                if (mode !== "loadout")
                  yield* check(
                    actual === amount,
                    "An exact transfer completed with a partial result",
                  );
                if (result.finalCount !== undefined)
                  yield* check(
                    result.finalCount === inventory,
                    "Loadout final count disagrees with vanilla",
                  );
                if (result.satisfied)
                  yield* check(
                    deposit ? inventory <= 1 : inventory >= amount,
                    "Loadout reported satisfaction without the required items",
                  );
              }
              yield* check(
                !(yield* bots[index]!.inventory.snapshot()).carried,
                "A shared transfer retained a cursor stack after closing",
              );
            }
            yield* check(completed > 0, "Neither shared actor completed");
            yield* check(
              retained === (deposit ? 128 : 64),
              "Shared transfers lost or duplicated items",
            );
          }
        }

        for (const bot of bots) yield* bot.closeContainer();
        yield* Effect.sleep("200 millis");
        for (const name of NAMES) yield* rcon(`clear ${name}`, { check: false });
        yield* rcon(
          'data merge block 4263 64 8 {Items:[{Slot:0b,id:"diamond",count:32},{Slot:1b,id:"bread",count:16}]}',
        );
        const bot = bots[0]!;
        const bread = { itemIds: ["minecraft:bread"] };
        const exact = yield* bot.tasks.withdraw(CHEST, [
          { selector, count: 32 },
          { selector: bread, count: 20 },
        ]);
        yield* check(
          (yield* exact.wait()).status === BotTaskStatus.FAILED,
          "An unsatisfied exact batch did not fail",
        );
        const untouched = yield* rcon(`data get entity ${NAMES[0]} Inventory`);
        const contents = yield* rcon("data get block 4263 64 8 Items");
        yield* check(
          materialCount(untouched) === 0 &&
            materialCount(untouched, "minecraft:bread") === 0 &&
            materialCount(contents) === 32 &&
            materialCount(contents, "minecraft:bread") === 16,
          "An exact batch changed inventory before its preflight failed",
        );

        const partial = yield* bot.tasks.withdraw(CHEST, [
          { selector, count: 32 },
          { selector: bread, count: 20, allowPartial: true },
        ]);
        const partialState = yield* partial.wait();
        yield* check(
          partialState.status === BotTaskStatus.COMPLETED && !!partialState.result,
          "A partial batch failed",
        );
        const partialResult = fromBinary(
          ContainerTransferTaskResultSchema,
          partialState.result!.value,
        );
        const collected = yield* rcon(`data get entity ${NAMES[0]} Inventory`);
        yield* check(
          partialResult.reason === ContainerTransferCompletionReason.PARTIAL &&
            partialResult.totalTransferred === 48 &&
            partialResult.outcomes[0]?.transferred === 32 &&
            partialResult.outcomes[1]?.transferred === 16 &&
            materialCount(collected) === 32 &&
            materialCount(collected, "minecraft:bread") === 16,
          "A partial batch reported unconfirmed counts",
        );

        yield* rcon(`clear ${NAMES[0]}`, { check: false });
        yield* rcon(`give ${NAMES[0]} bread 16`);
        yield* rcon('data merge block 4263 64 8 {Items:[{Slot:0b,id:"diamond",count:32}]}');
        yield* eventually(
          bot
            .inventoryState()
            .pipe(
              Effect.tap((state) =>
                check(
                  !state.slots.some((slot) => slot.itemId === "minecraft:diamond") &&
                    state.slots.some(
                      (slot) => slot.itemId === "minecraft:bread" && slot.count === 16,
                    ),
                  "The mixed-loadout starting inventory did not arrive",
                ),
              ),
            ),
          "5 seconds",
          "50 millis",
        );
        const mixed = yield* bot.tasks.maintainLoadout(
          CHEST,
          [
            { selector, minimumCount: 32, targetCount: 32, maximumCount: 32 },
            { selector: bread, minimumCount: 0, targetCount: 0, maximumCount: 1 },
          ],
          { completeWhenSatisfied: true, maximumRebalances: 1 },
        );
        const mixedState = yield* mixed.wait();
        yield* check(
          mixedState.status === BotTaskStatus.COMPLETED && !!mixedState.result,
          "A mixed loadout rebalance failed",
        );
        const mixedResult = fromBinary(MaintainLoadoutTaskResultSchema, mixedState.result!.value);
        const balanced = yield* rcon(`data get entity ${NAMES[0]} Inventory`);
        const deposited = yield* rcon("data get block 4263 64 8 Items");
        yield* check(
          mixedResult.totalWithdrawn === 32 &&
            mixedResult.totalDeposited === 16 &&
            mixedResult.requirements.every((requirement) => requirement.satisfied) &&
            mixedResult.requirements[0]?.finalCount === 32 &&
            mixedResult.requirements[1]?.finalCount === 0 &&
            materialCount(balanced) === 32 &&
            materialCount(balanced, "minecraft:bread") === 0 &&
            materialCount(deposited) === 0 &&
            materialCount(deposited, "minecraft:bread") === 16,
          "A mixed rebalance did not confirm both directions",
        );
      }),
    ),
} satisfies E2ETest;
