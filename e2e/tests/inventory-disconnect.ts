import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { BotRuntimeState } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  check,
  type E2ETest,
  eventually,
  placeBot,
  present,
  rcon,
  required,
} from "../harness.ts";

const TABLE = { x: 4263, y: 64, z: 8 };
const TAG = "sf_e2e_disconnect_menu";
const DROPS = "@e[type=item,x=4264,y=64,z=8,distance=..8]";
const CASES = [
  { menu: "inventory", item: "oak_log", amount: 3, slot: 1 },
  { menu: "inventory", item: "diamond", amount: 3, stop: true },
  { menu: "crafting_table", item: "oak_planks", amount: 3, slot: 1 },
  { menu: "crafting_table", item: "diamond", amount: 3 },
  { menu: "merchant", item: "emerald", amount: 3, slot: 0 },
  { menu: "merchant", item: "diamond", amount: 3 },
  { menu: "furnace", item: "iron_ore", amount: 3, slot: 0, stored: true },
  { menu: "furnace", item: "diamond", amount: 3 },
  { menu: "anvil", item: "iron_sword", amount: 1, slot: 0 },
  { menu: "grindstone", item: "iron_sword", amount: 1, slot: 0 },
  { menu: "enchanting_table", item: "lapis_lazuli", amount: 3, slot: 1 },
  { menu: "loom", item: "red_dye", amount: 3, slot: 1 },
  { menu: "cartography_table", item: "paper", amount: 3, slot: 1 },
  { menu: "stonecutter", item: "stone", amount: 3, slot: 0 },
  { menu: "smithing_table", item: "netherite_upgrade_smithing_template", amount: 1, slot: 0 },
  { menu: "beacon", item: "emerald", amount: 1, slot: 0, dropped: true },
  { menu: "inventory", item: "diamond", amount: 3, full: true, dropped: true },
  { menu: "crafting_table", item: "diamond", amount: 3, full: true, dropped: true },
  { menu: "crafting_table", item: "oak_planks", amount: 3, slot: 1, full: true, dropped: true },
  { menu: "inventory", item: "diamond", amount: 3, creative: true, stop: true },
  { menu: "inventory", item: "diamond", amount: 3, creative: true, close: true },
  { menu: "inventory", item: "diamond", amount: 3, creative: true, merge: 61 },
  { menu: "inventory", item: "diamond", amount: 3, creative: true, full: true, dropped: true },
] satisfies {
  menu: string;
  item: string;
  amount: number;
  slot?: number;
  stop?: boolean;
  stored?: boolean;
  dropped?: boolean;
  full?: boolean;
  creative?: boolean;
  close?: boolean;
  merge?: number;
}[];

const menuType = (block: string) => {
  switch (block) {
    case "crafting_table":
      return "crafting";
    case "enchanting_table":
      return "enchanting";
    case "cartography_table":
      return "cartography";
    case "smithing_table":
      return "smithing";
    default:
      return block;
  }
};

export default {
  name: "inventory-disconnect",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const tasksBefore = new Set(
          (yield* bot.tasks.list({ includeTerminal: true })).map((task) => task.taskId),
        );
        const forced: number[] = [];
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            forced.push(x);
          }
        }
        yield* rcon("forceload add 4256 0 4287 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds");
            yield* bot.closeContainer();
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon(`tag ${DROPS} add ${TAG}`, { check: false });
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`data modify block ${TABLE.x} ${TABLE.y} ${TABLE.z} Items set value []`, {
              check: false,
            });
            yield* rcon("fill 4256 63 0 4287 66 15 air");
            for (const x of forced) {
              yield* rcon(`forceload remove ${x} 0 ${x + 15} 15`);
            }
          }).pipe(Effect.ignore),
        );
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        yield* rcon("fill 4256 63 0 4287 63 15 stone");
        for (const spec of CASES) {
          const { menu, item, amount } = spec;
          const expectedAmount = amount + ("merge" in spec ? spec.merge : 0);
          const slot = "slot" in spec ? spec.slot : undefined;
          yield* bot.closeContainer();
          yield* placeBot(bot, { x: 4264.5, y: 64, z: 8.5 });
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* rcon(`data modify block ${TABLE.x} ${TABLE.y} ${TABLE.z} Items set value []`, {
            check: false,
          });
          yield* rcon(`setblock ${TABLE.x} ${TABLE.y} ${TABLE.z} air`, { check: false });
          yield* rcon(`give ${BOT_NAME} ${item} ${amount}`);
          if (menu === "merchant") {
            yield* rcon(
              `summon villager 4263.5 64 8.5 {Tags:["${TAG}"],NoAI:1b,Invulnerable:1b,VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:bread",count:1},maxUses:999}]}}`,
            );
            const entities = yield* eventually(
              bot.world
                .queryEntities({
                  radius: 4,
                  selector: { entityTypes: ["minecraft:villager"], alive: true },
                })
                .pipe(
                  Effect.tap((result) =>
                    check(result.entities.length === 1, "The fixture merchant did not load"),
                  ),
                ),
              "5 seconds",
              "50 millis",
            );
            const reference = required(entities.entities[0]?.reference, "the entity reference");
            yield* bot.interactEntity({
              entityId: reference.networkId,
              connectionEpoch: reference.connectionEpoch,
              hand: Hand.MAIN,
            });
          } else if ("creative" in spec) {
            yield* bot.openInventory();
          } else if (menu !== "inventory") {
            if (menu === "beacon") {
              yield* rcon("fill 4262 63 7 4264 63 9 iron_block");
            }
            yield* rcon(`setblock ${TABLE.x} ${TABLE.y} ${TABLE.z} ${menu}`);
            yield* bot.inventory.open(TABLE);
          }
          const opened = yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    state.layout?.containerType ===
                      ("creative" in spec ? "creative" : menuType(menu)) &&
                      state.slots.some((stack) => stack.itemId === `minecraft:${item}`),
                    `${menu} did not open with the fixture item`,
                  ),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          const source = yield* present(
            opened.slots.find((stack) => stack.itemId === `minecraft:${item}`),
            `The ${item} stack is missing from the opened inventory`,
          );
          yield* bot.clickInventory(source.slot);
          if (slot !== undefined) yield* bot.clickInventory(slot);
          yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    slot === undefined
                      ? state.carriedItem?.count === amount
                      : !state.carriedItem &&
                          state.slots.some(
                            (stack) =>
                              stack.slot === slot &&
                              stack.itemId === `minecraft:${item}` &&
                              stack.count === amount,
                          ),
                    `${menu} did not retain the cursor or input stack`,
                  ),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          // Verify server state as well as predicted clicks before interrupting the connection.
          yield* eventually(
            rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
              Effect.flatMap((inventory) =>
                check(inventory.endsWith("[]"), "The input is still in server player inventory"),
              ),
            ),
            "3 seconds",
          );
          if ("full" in spec) {
            yield* rcon(`give ${BOT_NAME} stone 2304`);
            yield* rcon(`item replace entity ${BOT_NAME} weapon.offhand with stone 64`);
          }
          if ("merge" in spec) {
            yield* rcon(`give ${BOT_NAME} ${item} ${spec.merge}`);
            yield* eventually(
              bot.inventoryState().pipe(
                Effect.tap((state) =>
                  check(
                    state.slots.some(
                      (candidate) =>
                        candidate.itemId === `minecraft:${item}` && candidate.count === spec.merge,
                    ),
                    "The merge target did not reach the client",
                  ),
                ),
              ),
              "5 seconds",
              "50 millis",
            );
          }
          if ("close" in spec) yield* bot.closeContainer();
          const epoch = (yield* bot.world.player()).connectionEpoch;
          if ("stop" in spec) {
            yield* bot.stop();
            yield* eventually(
              bot
                .status()
                .pipe(
                  Effect.flatMap((status) =>
                    check(status.runtimeState === BotRuntimeState.STOPPED, "Still stopping"),
                  ),
                ),
              "15 seconds",
              "50 millis",
            );
            yield* bot.start();
          } else {
            yield* bot.restart();
          }
          yield* eventually(
            bot
              .status()
              .pipe(
                Effect.flatMap((status) =>
                  check(status.runtimeState === BotRuntimeState.RUNNING, "Not running yet"),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          yield* eventually(
            bot.world
              .player()
              .pipe(
                Effect.flatMap((player) =>
                  check(player.connectionEpoch !== epoch, "Still observing the old connection"),
                ),
              ),
            "30 seconds",
            "50 millis",
          );
          yield* eventually(
            Effect.gen(function* () {
              const inventory = yield* rcon(`clear ${BOT_NAME} ${item} 0`, { check: false });
              const held = Number(inventory.match(/Found (\d+) /u)?.[1] ?? 0);
              if ("stored" in spec) {
                const storage = yield* rcon(
                  `data get block ${TABLE.x} ${TABLE.y} ${TABLE.z} Items`,
                );
                yield* check(
                  held === 0 &&
                    storage.includes(`minecraft:${item}`) &&
                    storage.includes(`count: ${amount}`),
                  "Disconnect changed persistent furnace storage",
                );
              } else if ("dropped" in spec) {
                const drops = yield* rcon(
                  `execute as @e[type=item,x=4264,y=64,z=8,distance=..8,nbt={Item:{id:"minecraft:${item}"}}] run data get entity @s Item`,
                  { check: false },
                );
                const dropped = [...drops.matchAll(/count: (\d+)/gu)].reduce(
                  (sum, match) => sum + Number(match[1]),
                  0,
                );
                yield* check(
                  held + dropped === expectedAmount,
                  `${menu} lost or duplicated dropped ${item}`,
                );
              } else {
                yield* check(
                  held === expectedAmount,
                  `Inventory return mismatch: ${JSON.stringify(spec)}; held ${held}, expected ${expectedAmount}`,
                );
              }
            }),
            "3 seconds",
          );
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(`tag ${DROPS} add ${TAG}`, { check: false });
          yield* rcon(`kill @e[type=item,tag=${TAG}]`, { check: false });
        }
        yield* check(
          (yield* bot.tasks.list({ includeTerminal: true })).every((task) =>
            tasksBefore.has(task.taskId),
          ),
          "Raw menu clicks unexpectedly created durable tasks",
        );
      }),
    ),
} satisfies E2ETest;
