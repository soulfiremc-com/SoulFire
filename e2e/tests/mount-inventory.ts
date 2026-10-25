import { Code, ConnectError } from "@connectrpc/connect";
import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { BotService, ClickType } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
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

const TAG = "sf_e2e_mount_inventory";
const ENTITY = `@e[tag=${TAG},limit=1]`;
const cases = [
  { kind: "horse", columns: 0, saddle: true, equipment: "iron_horse_armor" },
  { kind: "donkey", columns: 5, saddle: true },
  { kind: "llama", columns: 1, saddle: false, equipment: "red_carpet" },
  { kind: "llama", columns: 2, saddle: false, equipment: "red_carpet" },
  { kind: "llama", columns: 5, saddle: false, equipment: "red_carpet" },
];

export default {
  name: "mount-inventory",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const service = client.service(BotService);
        const scope = { instanceId: bot.instanceId, botId: bot.id };
        const forced = (yield* rcon("forceload query 4256 0", { check: false })).includes(
          "not marked",
        );
        yield* check(
          (yield* rcon(`execute if entity @e[tag=${TAG}]`, { check: false })) === "Test failed",
          "The mount fixture tag already exists",
        );
        const clearMount = () =>
          Effect.gen(function* () {
            yield* bot.closeContainer();
            yield* rcon(`ride ${BOT_NAME} dismount`, { check: false });
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`tag @e[type=item,x=4264,y=64,z=8,distance=..10] add ${TAG}`, {
              check: false,
            });
            yield* rcon(`kill @e[type=item,tag=${TAG}]`, { check: false });
          });
        yield* rcon("forceload add 4256 0");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* clearMount();
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        const itemSlot = (item: string) =>
          eventually(
            bot.inventoryState().pipe(
              Effect.flatMap((state) => {
                const slot = state.slots.find((entry) => entry.itemId === `minecraft:${item}`);
                return slot
                  ? Effect.succeed(slot.slot)
                  : Effect.fail(new CheckFailed({ message: "The fixture item did not arrive" }));
              }),
            ),
            "5 seconds",
            "50 millis",
          );
        const put = (item: string, destination: number, count = 1) =>
          Effect.gen(function* () {
            yield* rcon(`give ${BOT_NAME} ${item} ${count}`);
            yield* bot.clickInventory(yield* itemSlot(item));
            yield* bot.clickInventory(destination);
          });

        for (const spec of cases) {
          yield* clearMount();
          yield* placeBot(bot, { x: 4265.5, y: 64, z: 8.5 }, 90);
          yield* rcon(
            `summon ${spec.kind} 4264.5 64 8.5 {Tags:["${TAG}"],Tame:1b,NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,ChestedHorse:1b,Strength:${spec.columns}}`,
          );
          const entities = yield* eventually(
            bot.world
              .queryEntities({
                radius: 4,
                selector: { entityTypes: [`minecraft:${spec.kind}`], alive: true },
              })
              .pipe(
                Effect.tap((result) =>
                  check(result.entities.length === 1, "The mount did not load"),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          const entity = entities.entities[0]!.reference!;
          yield* bot.mount({
            entityId: entity.networkId,
            connectionEpoch: entity.connectionEpoch,
            hand: Hand.MAIN,
          });
          yield* bot.openInventory();
          const opened = yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(
                    state.layout?.containerType === "mount",
                    "The mounted inventory layout did not arrive",
                  ),
                ),
              ),
            "5 seconds",
            "50 millis",
          );
          const regions = opened.layout!.regions;
          const mountSlots = new Set(
            regions
              .filter((region) => !region.id.startsWith("player_"))
              .flatMap((region) =>
                Array.from({ length: region.slotCount }, (_, index) => region.startIndex + index),
              ),
          );
          yield* check(
            mountSlots.has(0) === spec.saddle && mountSlots.has(1) === !!spec.equipment,
            "The layout exposes inactive equipment or hides usable equipment",
          );
          const cargo = regions.find(
            (region) => region.startIndex === 2 && !region.id.startsWith("player_"),
          );
          yield* check(
            spec.columns === 0
              ? cargo === undefined
              : cargo?.slotCount === spec.columns * 3 && cargo.columns === spec.columns,
            "The cargo grid differs from the native mount menu",
          );
          for (const hidden of [0, 1].filter((slot) => !mountSlots.has(slot))) {
            const rejected = yield* Effect.tryPromise({
              try: async () => {
                try {
                  await service.clickInventorySlot({
                    ...scope,
                    expectedMenuId: opened.menuId,
                    slot: hidden,
                    clickType: ClickType.LEFT_CLICK,
                  });
                  return false;
                } catch (error) {
                  if (error instanceof ConnectError && error.code === Code.InvalidArgument)
                    return true;
                  throw error;
                }
              },
              catch: (cause) => new CheckFailed({ message: String(cause) }),
            });
            yield* check(rejected, "An inactive mount slot accepted a click");
          }
          if (spec.saddle) yield* put("saddle", 0);
          if (spec.equipment) yield* put(spec.equipment, 1);
          if (spec.columns) yield* put("diamond", 1 + spec.columns * 3, 7);
          yield* rcon(`give ${BOT_NAME} bread`);
          yield* bot.clickInventory(yield* itemSlot("bread"));
          yield* bot.closeContainer();
          yield* eventually(
            rcon(`data get entity ${BOT_NAME} Inventory`).pipe(
              Effect.tap((data) =>
                check(
                  data.includes("minecraft:bread"),
                  "Closing the mount menu lost the carried item",
                ),
              ),
            ),
            "5 seconds",
          );
          yield* eventually(
            rcon(`data get entity ${ENTITY}`).pipe(
              Effect.tap((data) =>
                check(
                  (!spec.saddle || data.includes("minecraft:saddle")) &&
                    (!spec.equipment || data.includes(`minecraft:${spec.equipment}`)) &&
                    (!spec.columns ||
                      (data.includes("minecraft:diamond") &&
                        data.includes(`Slot: ${spec.columns * 3 - 1}b`) &&
                        data.includes("count: 7"))),
                  "Vanilla did not retain the mounted equipment or last cargo stack",
                ),
              ),
            ),
            "5 seconds",
          );
          yield* bot.openInventory();
          const reopened = yield* eventually(
            bot
              .inventoryState()
              .pipe(
                Effect.tap((state) =>
                  check(state.layout?.containerType === "mount", "The mount menu did not reopen"),
                ),
              ),
            "5 seconds",
          );
          yield* check(
            reopened.menuId !== opened.menuId && !reopened.carriedItem,
            "Reopening reused a stale mount menu or cursor",
          );
        }
      }),
    ),
} satisfies E2ETest;
