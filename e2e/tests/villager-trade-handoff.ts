import { goals } from "@soulfiremc/sdk";
import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import {
  BotTaskConflictPolicy,
  BotTaskPriority,
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
} from "../harness.ts";

const TAG = "sf_e2e_trade_handoff";
const URGENT = {
  priority: BotTaskPriority.HIGH,
  conflictPolicy: BotTaskConflictPolicy.SUSPEND_LOWER_PRIORITY,
};
const count = (item: string) =>
  rcon(`clear ${BOT_NAME} ${item} 0`, { check: false }).pipe(
    Effect.map((text) => Number(text.match(/Found (\d+) matching/u)?.[1] ?? 0)),
  );

export default {
  name: "villager-trade-handoff",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        for (const x of [4080, 4096]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            forced.push(x);
          }
        }
        yield* rcon("forceload add 4080 0 4111 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon(`kill @e[type=villager,tag=${TAG}]`, { check: false });
            yield* rcon("data modify block 4087 64 10 Items set value []", { check: false });
            yield* rcon("fill 4086 63 7 4103 66 11 air", { check: false });
            for (const x of forced) yield* rcon(`forceload remove ${x} 0 ${x + 15} 15`);
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4086 63 7 4103 63 11 stone");
        for (const mode of ["keep-open", "close", "suspended", "queued"] as const) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* bot.closeContainer();
              yield* rcon(`gamemode creative ${BOT_NAME}`);
              yield* placeBot(bot, { x: 4088.5, y: 64, z: 8.5 }, 90);
              yield* rcon(`kill @e[type=villager,tag=${TAG}]`, { check: false });
              yield* rcon("setblock 4087 64 10 air", { check: false });
              yield* rcon("setblock 4087 64 10 furnace");
              yield* rcon(
                `summon villager 4087.5 64 8.5 {Tags:["${TAG}"],NoAI:1b,Invulnerable:1b,VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:apple",count:1},maxUses:999},{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:bread",count:1},maxUses:999}]}}`,
              );
              for (const item of ["emerald 4", "iron_ore", "coal"]) {
                yield* rcon(`give ${BOT_NAME} ${item}`);
              }
              const target = yield* eventually(
                bot.world
                  .queryEntities({
                    radius: 4,
                    selector: { entityTypes: ["minecraft:villager"], alive: true },
                  })
                  .pipe(
                    Effect.map((response) => response.entities[0]?.reference),
                    Effect.filterOrFail(
                      (value) => value !== undefined,
                      () => new CheckFailed({ message: "The merchant did not load" }),
                    ),
                  ),
                "5 seconds",
              );
              yield* bot.interactEntity({
                entityId: target.networkId,
                connectionEpoch: target.connectionEpoch,
                hand: Hand.MAIN,
              });
              const merchant = yield* eventually(
                bot.inventoryState().pipe(
                  Effect.filterOrFail(
                    (state) =>
                      state.layout?.containerType === "merchant" &&
                      state.layout.buttons.length === 2,
                    () => new CheckFailed({ message: "The merchant menu did not open" }),
                  ),
                ),
                "5 seconds",
              );
              if (mode === "keep-open" || mode === "close") {
                const trade = yield* bot.tasks.villagerTrade(1, 2, {
                  closeWhenDone: mode === "close",
                });
                yield* Effect.addFinalizer(() => trade.cancel("test cleanup").pipe(Effect.ignore));
                const result = yield* trade.result({ call: { timeoutMs: 10_000 } });
                yield* check(
                  result.completedTrades === 2 &&
                    result.outputs.length === 2 &&
                    result.outputs.every(
                      (item) => item.itemId === "minecraft:bread" && item.count === 1,
                    ),
                  "An active trade returned incorrect results",
                );
                const state = yield* bot.inventoryState();
                yield* check(
                  mode === "close"
                    ? state.layout?.containerType === "inventory"
                    : state.menuId === merchant.menuId,
                  "An active trade did not respect its close option",
                );
                if (mode === "keep-open") {
                  const selection = state.layout?.buttons.filter((button) => button.selected);
                  yield* check(
                    selection?.length === 1 && selection[0]?.buttonId === 1,
                    "A trade task left the native screen on a different offer",
                  );
                }
              } else if (mode === "suspended") {
                const trade = yield* bot.tasks.villagerTrade(1, 4, { closeWhenDone: true });
                yield* Effect.addFinalizer(() => trade.cancel("test cleanup").pipe(Effect.ignore));
                yield* eventually(
                  bot
                    .inventoryState()
                    .pipe(
                      Effect.flatMap((state) =>
                        check(
                          !state.carriedItem &&
                            state.slots.some(
                              (slot) => slot.slot === 2 && slot.itemId === "minecraft:bread",
                            ),
                          "The trade did not prepare its result",
                        ),
                      ),
                    ),
                  "5 seconds",
                  "10 millis",
                );
                const smelt = yield* bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, 1, {
                  station: { x: 4087, y: 64, z: 10 },
                  fuel: { itemIds: ["minecraft:coal"] },
                  ...URGENT,
                });
                yield* Effect.addFinalizer(() => smelt.cancel("test cleanup").pipe(Effect.ignore));
                yield* eventually(
                  trade
                    .refresh()
                    .pipe(
                      Effect.flatMap((task) =>
                        check(
                          task.status === BotTaskStatus.SUSPENDED,
                          "Trading did not yield to smelting",
                        ),
                      ),
                    ),
                  "3 seconds",
                  "10 millis",
                );
                const furnace = yield* eventually(
                  bot.inventoryState().pipe(
                    Effect.filterOrFail(
                      (state) => state.layout?.containerType === "furnace",
                      () => new CheckFailed({ message: "The urgent furnace did not open" }),
                    ),
                  ),
                  "5 seconds",
                );
                yield* trade.cancel("Cancel suspended trade");
                yield* check(
                  (yield* trade.wait({ call: { timeoutMs: 5000 } })).status ===
                    BotTaskStatus.CANCELLED,
                  "Trade cancellation did not finish",
                );
                const unchanged = yield* bot.inventoryState();
                yield* check(
                  unchanged.menuId === furnace.menuId &&
                    unchanged.layout?.containerType === "furnace",
                  "Cancelling suspended trading closed the urgent furnace",
                );
                yield* smelt.result({ call: { timeoutMs: 20_000 } });
                yield* check(
                  (yield* count("iron_ingot")) === 1,
                  "Vanilla did not retain the urgent ingot",
                );
              } else {
                const route = yield* bot.tasks.goTo(goals.block({ x: 4100, y: 64, z: 8 }), URGENT);
                yield* Effect.addFinalizer(() => route.cancel("test cleanup").pipe(Effect.ignore));
                yield* eventually(
                  route
                    .refresh()
                    .pipe(
                      Effect.flatMap((task) =>
                        check(
                          task.status === BotTaskStatus.RUNNING,
                          "Navigation did not take control",
                        ),
                      ),
                    ),
                  "3 seconds",
                  "10 millis",
                );
                const trade = yield* bot.tasks.villagerTrade(1, 4, {
                  closeWhenDone: true,
                  conflictPolicy: BotTaskConflictPolicy.QUEUE,
                });
                yield* Effect.addFinalizer(() => trade.cancel("test cleanup").pipe(Effect.ignore));
                yield* eventually(
                  trade
                    .refresh()
                    .pipe(
                      Effect.flatMap((task) =>
                        check(
                          task.status === BotTaskStatus.WAITING_FOR_RESOURCES,
                          "Trading did not queue",
                        ),
                      ),
                    ),
                  "3 seconds",
                  "10 millis",
                );
                yield* trade.cancel("Cancel queued trade");
                yield* check(
                  (yield* trade.wait({ call: { timeoutMs: 5000 } })).status ===
                    BotTaskStatus.CANCELLED,
                  "Queued cancellation did not finish",
                );
                yield* check(
                  (yield* bot.inventoryState()).menuId === merchant.menuId,
                  "Queued trading closed a menu it never controlled",
                );
                yield* route.result({ call: { timeoutMs: 15_000 } });
              }
              // Return any remaining merchant payment before counting vanilla items.
              yield* bot.closeContainer();
              const offer = yield* rcon(
                `data get entity @e[type=villager,tag=${TAG},limit=1] Offers.Recipes[1]`,
              );
              yield* check(offer.includes("minecraft:bread"), "The vanilla offer disappeared");
              const uses = Number(offer.match(/(?:^|[,{}]\s*)uses: (\d+)/u)?.[1] ?? 0);
              if (mode === "keep-open" || mode === "close")
                yield* check(uses === 2, "Vanilla completed the wrong trade count");
              if (mode === "queued") yield* check(uses === 0, "Queued trading performed a trade");
              yield* eventually(
                Effect.gen(function* () {
                  yield* check(
                    (yield* count("bread")) === uses && (yield* count("emerald")) === 4 - uses,
                    "Trade cancellation lost or duplicated payment or output",
                  );
                }),
                "3 seconds",
              );
            }),
          );
        }
      }),
    ),
} satisfies E2ETest;
