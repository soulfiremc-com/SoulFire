import { InventoryArea } from "@soulfiremc/sdk/generated/soulfire/inventory_pb";
import { GameMode } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import {
  BotProtocolService,
  PacketDirection,
  type RawPacketEvent,
} from "@soulfiremc/sdk/generated/soulfire/protocol_pb";
import { Effect, Result } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const cases = [
  { source: 64, destination: 0, count: 64, packets: 2 },
  { source: 64, destination: 48, count: 16, packets: 3 },
  { source: 64, destination: 0, count: 32, packets: 2 },
  { source: 64, destination: 0, count: 1, packets: 3 },
  { source: 16, destination: 0, count: 16, packets: 2 },
  { source: 64, destination: 0, count: 63, packets: 3 },
  { source: 64, destination: 0, count: 31, packets: 3 },
  { source: 64, destination: 0, count: 17, packets: 17 },
  { source: 5, destination: 0, count: 3, packets: 2 },
  { source: 5, destination: 0, count: 2, packets: 3 },
];

function clickedSlot(event: RawPacketEvent): number {
  const bytes = event.encodedPacket;
  let offset = 0;
  // Native packet ID, container ID and state ID precede the short slot index.
  for (let field = 0; field < 3; field++) {
    while (bytes[offset++]! & 0x80) {}
  }
  if (event.encodedPacketTruncated || offset + 2 > bytes.length) {
    throw new CheckFailed({ message: "The click packet header is incomplete" });
  }
  return (bytes[offset]! << 8) | bytes[offset + 1]!;
}

export default {
  name: "inventory-move-packets",
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
            yield* rcon("data merge block 4263 64 8 {Items:[]}", { check: false });
            yield* rcon("setblock 4263 64 8 air", { check: false });
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* rcon("fill 4260 63 4 4268 63 12 air");
            if (forced) yield* rcon("forceload remove 4256 0");
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4260 63 4 4268 63 12 stone");
        yield* placeBot(bot, { x: 4264.5, y: 64, z: 8.5 });
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        const run = Effect.runPromiseWith(yield* Effect.context());
        yield* Effect.tryPromise({
          try: async () => {
            const events: RawPacketEvent[] = [];
            const abort = new AbortController();
            let streamError: unknown;
            let markerSlot = 44;
            const stream = (async () => {
              try {
                for await (const event of client.service(BotProtocolService).watchPackets(
                  {
                    instanceId: bot.instanceId,
                    botId: bot.id,
                    directions: [PacketDirection.SERVERBOUND],
                    names: ["minecraft:container_click"],
                    includeEncodedPacket: true,
                  },
                  { signal: abort.signal },
                ))
                  events.push(event);
              } catch (cause) {
                if (!abort.signal.aborted) streamError = cause;
              }
            })();
            const pause = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
            const marker = async () => {
              const start = events.length;
              await run(bot.clickInventory(markerSlot));
              const until = Date.now() + 3000;
              while (Date.now() < until) {
                if (streamError) throw streamError;
                const event = events
                  .slice(start)
                  .find((entry) => clickedSlot(entry) === markerSlot);
                if (event) return event.sequence;
                await pause(20);
              }
              throw new CheckFailed({
                message: "The click stream did not deliver the empty-slot marker",
              });
            };
            const measure = async (action: () => Promise<unknown>, limit: number) => {
              const first = await marker();
              await action();
              await run(
                check(
                  !(await run(bot.inventoryState())).carriedItem,
                  "Moving items left a carried stack",
                ),
              );
              await pause(200);
              const last = await marker();
              const delivered = events.filter(
                (event) => event.sequence > first && event.sequence <= last,
              );
              const accounted = delivered.reduce(
                (total, event) => total + 1n + event.droppedBefore,
                0n,
              );
              await run(
                check(
                  accounted === last - first,
                  "Packet loss accounting did not cover the measured move",
                ),
              );
              await run(
                check(
                  last - first - 1n <= BigInt(limit),
                  "Moving items sent too many inventory clicks",
                ),
              );
            };
            try {
              await pause(200);
              await marker();
              for (const spec of cases) {
                await run(rcon(`clear ${BOT_NAME}`, { check: false }));
                await run(
                  rcon(`item replace entity ${BOT_NAME} inventory.0 with diamond ${spec.source}`),
                );
                if (spec.destination)
                  await run(
                    rcon(
                      `item replace entity ${BOT_NAME} inventory.1 with diamond ${spec.destination}`,
                    ),
                  );
                await run(
                  eventually(
                    bot
                      .inventoryState()
                      .pipe(
                        Effect.tap((state) =>
                          check(
                            state.slots.some(
                              (slot) => slot.slot === 9 && slot.count === spec.source,
                            ) &&
                              (state.slots.find((slot) => slot.slot === 10)?.count ?? 0) ===
                                spec.destination,
                            "The move fixture did not arrive",
                          ),
                        ),
                      ),
                    "5 seconds",
                  ),
                );
                await measure(
                  () =>
                    run(
                      bot.inventory.move({ sourceSlot: 9, destinationSlot: 10, count: spec.count }),
                    ),
                  spec.packets,
                );
                for (const [slot, count] of [
                  [9, spec.source - spec.count],
                  [10, spec.destination + spec.count],
                ]) {
                  const data = await run(
                    rcon(`data get entity ${BOT_NAME} Inventory[{Slot:${slot}b}].count`, {
                      check: false,
                    }),
                  );
                  await run(
                    check(
                      count === 0 ? data.includes("Found no elements") : data.endsWith(` ${count}`),
                      "Vanilla retained the wrong item count after moving",
                    ),
                  );
                }
              }
              await run(rcon(`clear ${BOT_NAME}`, { check: false }));
              await run(rcon(`item replace entity ${BOT_NAME} inventory.0 with iron_ingot 48`));
              await run(
                rcon(
                  'setblock 4263 64 8 furnace{Items:[{Slot:2b,id:"minecraft:iron_ingot",count:64}]}',
                ),
              );
              await run(bot.inventory.open({ x: 4263, y: 64, z: 8 }));
              await run(
                eventually(
                  bot
                    .inventoryState()
                    .pipe(
                      Effect.tap((state) =>
                        check(
                          state.layout?.containerType === "furnace" &&
                            state.slots.some((slot) => slot.slot === 2 && slot.count === 64),
                          "The output fixture did not arrive",
                        ),
                      ),
                    ),
                  "5 seconds",
                ),
              );
              markerSlot = 38;
              await measure(async () => {
                const result = await run(
                  Effect.result(
                    bot.inventory.move({ sourceSlot: 2, destinationSlot: 4, count: 32 }),
                  ),
                );
                await run(check(Result.isFailure(result), "A partial output move was accepted"));
              }, 0);
              await measure(
                () =>
                  run(
                    bot.inventory.transfer({
                      selector: { itemIds: ["minecraft:iron_ingot"] },
                      count: 64,
                      from: InventoryArea.CONTAINER,
                      to: InventoryArea.PLAYER,
                    }),
                  ),
                4, // Three placement clicks and one authoritative menu resync.
              );
              const furnace = await run(rcon("data get block 4263 64 8 Items"));
              const total = await run(rcon(`clear ${BOT_NAME} iron_ingot 0`));
              await run(
                check(
                  furnace.endsWith("[]") && total.includes("Found 112 "),
                  "Splitting an output changed vanilla item totals",
                ),
              );
              await run(bot.closeContainer());
              await run(rcon(`gamemode creative ${BOT_NAME}`));
              await run(
                eventually(
                  bot
                    .info()
                    .pipe(
                      Effect.tap((info) =>
                        check(
                          info.liveState?.gameMode === GameMode.CREATIVE,
                          "The creative game mode did not arrive",
                        ),
                      ),
                    ),
                  "5 seconds",
                ),
              );
              await run(bot.openInventory());
              const creative = await run(
                eventually(
                  bot
                    .inventoryState()
                    .pipe(
                      Effect.tap((state) =>
                        check(
                          state.layout?.containerType === "creative",
                          "The creative inventory did not open",
                        ),
                      ),
                    ),
                  "5 seconds",
                ),
              );
              const inventoryTab = creative.layout!.buttons.find(
                (button) => button.iconItemId === "minecraft:chest",
              );
              await run(check(!!inventoryTab, "The creative inventory tab is missing"));
              await run(bot.clickContainerButton(inventoryTab!.buttonId));
              for (const count of [64, 32, 63]) {
                await run(rcon(`clear ${BOT_NAME}`, { check: false }));
                await run(rcon(`item replace entity ${BOT_NAME} inventory.0 with diamond 64`));
                await run(
                  eventually(
                    bot.inventoryState().pipe(
                      Effect.tap((state) =>
                        check(
                          state.slots.some(
                            (slot) =>
                              slot.slot === 9 &&
                              slot.itemId === "minecraft:diamond" &&
                              slot.count === 64,
                          ),
                          "The creative move fixture did not arrive",
                        ),
                      ),
                    ),
                    "5 seconds",
                  ),
                );
                await run(bot.inventory.move({ sourceSlot: 9, destinationSlot: 10, count }));
                await run(
                  check(
                    !(await run(bot.inventoryState())).carriedItem,
                    "Creative movement left an item on the cursor",
                  ),
                );
                await run(
                  eventually(
                    rcon(`data get entity ${BOT_NAME} Inventory[{Slot:10b}].count`).pipe(
                      Effect.tap((data) =>
                        check(
                          data.endsWith(` ${count}`),
                          "The creative move did not reach vanilla",
                        ),
                      ),
                    ),
                    "5 seconds",
                  ),
                );
                const remaining = await run(
                  rcon(`data get entity ${BOT_NAME} Inventory[{Slot:9b}].count`, { check: false }),
                );
                await run(
                  check(
                    count === 64
                      ? remaining.includes("Found no elements")
                      : remaining.endsWith(` ${64 - count}`),
                    "The creative move changed the source count",
                  ),
                );
              }
            } finally {
              abort.abort();
              await stream;
            }
          },
          catch: (cause) => new CheckFailed({ message: String(cause) }),
        });
      }),
    ),
} satisfies E2ETest;
