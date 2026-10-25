import { Code, ConnectError } from "@connectrpc/connect";
import { GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { InventoryService } from "@soulfiremc/sdk/generated/soulfire/inventory_pb";
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

interface EquipCase {
  item: string;
  count: number;
  offhand?: string;
  fromHead?: boolean;
  full?: boolean;
  creative?: boolean;
  target?: "head";
  error?: Code;
}

const boundHelmet = 'leather_helmet[enchantments={"minecraft:binding_curse":1}]';
const cases: readonly EquipCase[] = [
  { item: "shield", count: 1 },
  { item: "torch", count: 64 },
  { item: "totem_of_undying", count: 1 },
  { item: "diamond_helmet", count: 1 },
  { item: "torch", count: 64, offhand: "shield", full: true },
  { item: "totem_of_undying", count: 1, offhand: "torch 64", full: true },
  { item: "diamond_helmet", count: 1, fromHead: true, offhand: "torch 64" },
  {
    item: "diamond_helmet",
    count: 1,
    fromHead: true,
    offhand: "torch 64",
    full: true,
    error: Code.ResourceExhausted,
  },
  {
    item: boundHelmet,
    count: 1,
    fromHead: true,
    offhand: "torch 64",
    error: Code.FailedPrecondition,
  },
  { item: boundHelmet, count: 1, fromHead: true, offhand: "torch 64", creative: true },
  { item: "torch", count: 64, target: "head", error: Code.NotFound },
];

export default {
  name: "offhand-equip",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.closeContainer();
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        yield* placeBot(bot, { x: 4264.5, y: 64, z: 8.5 });
        const service = client.service(InventoryService);
        const scope = { instanceId: bot.instanceId, botId: bot.id };
        for (const spec of cases) {
          const itemId = `minecraft:${spec.item.split("[")[0]}`;
          yield* bot.closeContainer();
          yield* rcon(`clear ${BOT_NAME}`, { check: false });
          yield* rcon(`gamemode ${spec.creative ? "creative" : "survival"} ${BOT_NAME}`);
          if (spec.full) {
            for (let slot = 0; slot < 27; slot++)
              yield* rcon(
                `item replace entity ${BOT_NAME} inventory.${slot} with minecraft:stone 64`,
              );
            for (let slot = 0; slot < 9; slot++)
              yield* rcon(`item replace entity ${BOT_NAME} hotbar.${slot} with minecraft:stone 64`);
          }
          yield* rcon(
            `item replace entity ${BOT_NAME} ${spec.fromHead ? "armor.head" : "inventory.0"} with minecraft:${spec.item} ${spec.count}`,
          );
          if (spec.offhand)
            yield* rcon(
              `item replace entity ${BOT_NAME} weapon.offhand with minecraft:${spec.offhand}`,
            );
          const before = yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    state.slots.some(
                      (slot) =>
                        slot.slot === (spec.fromHead ? 5 : 9) &&
                        slot.itemId === itemId &&
                        slot.count === spec.count,
                    ) &&
                      state.slots.filter((slot) => slot.slot >= 9 && slot.slot <= 44).length ===
                        (spec.full ? 36 : spec.fromHead ? 0 : 1) &&
                      state.slots.some((slot) => slot.slot === 45) === !!spec.offhand,
                    "The offhand fixture did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          yield* eventually(
            bot
              .info()
              .pipe(
                Effect.tap((info) =>
                  check(
                    info.liveState?.gameMode ===
                      (spec.creative ? GameMode.CREATIVE : GameMode.SURVIVAL),
                    "The offhand game mode did not arrive",
                  ),
                ),
              ),
            "5 seconds",
          );
          const beforeInventory = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
          const beforeEquipment = yield* rcon(`data get entity ${BOT_NAME} equipment`, {
            check: false,
          });
          const result = yield* Effect.tryPromise({
            try: async () => {
              try {
                await service.equipItem({
                  scope,
                  selector: { itemIds: [itemId] },
                  equipmentSlot: spec.target ?? "offhand",
                });
                return undefined;
              } catch (cause) {
                if (cause instanceof ConnectError) return cause.code;
                throw cause;
              }
            },
            catch: (cause) => new CheckFailed({ message: String(cause) }),
          });
          yield* check(
            result === spec.error,
            "Offhand equip rejected a valid item or accepted an invalid request",
          );
          if (spec.error !== undefined) {
            yield* check(
              (yield* rcon(`data get entity ${BOT_NAME} Inventory`)) === beforeInventory &&
                (yield* rcon(`data get entity ${BOT_NAME} equipment`, { check: false })) ===
                  beforeEquipment,
              "Rejected equipment changed vanilla inventory or equipment",
            );
          } else {
            yield* eventually(
              rcon(`execute if items entity ${BOT_NAME} weapon.offhand ${itemId}`, {
                check: false,
              }).pipe(
                Effect.tap((data) =>
                  check(
                    data.startsWith("Test passed"),
                    "Vanilla did not receive the equipped offhand item",
                  ),
                ),
              ),
              "5 seconds",
            );
          }
          const after = yield* bot.inventoryState();
          yield* check(!after.carriedItem, "Offhand equip left an item on the cursor");
          if (spec.error !== undefined) {
            yield* check(
              after.slots.length === before.slots.length &&
                after.slots.every((slot) =>
                  before.slots.some(
                    (old) =>
                      old.slot === slot.slot &&
                      old.itemId === slot.itemId &&
                      old.count === slot.count,
                  ),
                ),
              "Rejected equipment changed predicted inventory",
            );
          }
          const counts = new Map<string, number>();
          for (const slot of before.slots)
            counts.set(slot.itemId, (counts.get(slot.itemId) ?? 0) + slot.count);
          for (const [item, count] of counts) {
            yield* check(
              after.slots
                .filter((slot) => slot.itemId === item)
                .reduce((total, slot) => total + slot.count, 0) === count,
              "Offhand equip changed predicted item totals",
            );
            yield* check(
              (yield* rcon(`clear ${BOT_NAME} ${item} 0`)).includes(`Found ${count} `),
              "Offhand equip lost or duplicated items on vanilla",
            );
          }
          yield* check(
            (yield* rcon("execute if entity @e[type=item,x=4264,y=64,z=8,distance=..8]", {
              check: false,
            })) === "Test failed",
            "Offhand equip dropped an item",
          );
        }
      }),
    ),
} satisfies E2ETest;
