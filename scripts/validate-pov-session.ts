// Opt-in live test, invoked by validate-vulkan-container.py --interactive.
import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import { createClient } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { create } from "@bufbuild/protobuf";
import { WorldService } from "../sdk/typescript/src/generated/soulfire/world_pb";
import { BotService } from "../sdk/typescript/src/generated/soulfire/bot_pb";
import {
  PovService,
  PovInputEventSchema,
  PovInputEvent_Kind as Kind,
  type PovFrame,
} from "../sdk/typescript/src/generated/soulfire/pov_pb";

const [url, instanceId, botId, otherBotId, output, mode] =
  process.argv.slice(2);
const token = process.env.SF_POV_TEST_TOKEN;
assert.ok(token);
const transport = createGrpcWebTransport({
  baseUrl: url,
  interceptors: [
    (next) => async (request) => {
      request.header.set("Authorization", `Bearer ${token}`);
      return next(request);
    },
  ],
});
const bots = createClient(BotService, transport);
const pov = createClient(PovService, transport);
const sessionId = crypto.randomUUID();
const abort = new AbortController();
let latest: PovFrame | undefined;
let frameCount = 0;
let streamError: unknown;
let sequence = 0n;
let captured = false;
let width = 640,
  height = 360;
let busy = false;
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(check: () => boolean, message: string) {
  const deadline = performance.now() + 15000;
  while (!check()) {
    if (streamError) throw streamError;
    assert.ok(performance.now() < deadline, message);
    await sleep(50);
  }
}
const watching = (async () => {
  try {
    for await (const frame of pov.watch(
      { instanceId, botId, sessionId, width, height },
      { signal: abort.signal },
    )) {
      latest = frame;
      frameCount++;
    }
  } catch (error) {
    if (!abort.signal.aborted) streamError = error;
  }
})();
async function input(
  events: Parameters<typeof create<typeof PovInputEventSchema>>[1][] = [],
) {
  while (busy) await sleep(5);
  busy = true;
  try {
    await pov.input({
      sessionId,
      sequence: ++sequence,
      captured,
      width,
      height,
      events: events.map((event) => create(PovInputEventSchema, event)),
    });
  } finally {
    busy = false;
  }
}
let heartbeat: ReturnType<typeof setInterval> | undefined;
try {
  await until(() => !!latest, "No first frame");
  heartbeat = setInterval(() => {
    if (!busy)
      void input().catch((error) => {
        streamError = error;
      });
  }, 500);
  if (mode === "world") {
    captured = true;
    await input([{ kind: Kind.SCROLL, y: 1 }]);
    await sleep(200);
    assert.equal(
      (await bots.getBotInfo({ instanceId, botId })).liveState!
        .selectedHotbarSlot,
      8,
      "Wheel must select the previous hotbar slot",
    );
    await input([
      { kind: Kind.KEY, code: 49, action: 1 },
      { kind: Kind.KEY, code: 49, action: 0 },
    ]);
    await sleep(100);
    await input([
      { kind: Kind.BUTTON, code: 2, action: 1 },
      { kind: Kind.BUTTON, code: 2, action: 0 },
    ]);
    await sleep(250);
    const selected = (await bots.getBotInfo({ instanceId, botId })).liveState!
      .selectedHotbarSlot;
    const inventory = await bots.getInventoryState({ instanceId, botId });
    assert.equal(
      inventory.slots.find((slot) => slot.slot === 36 + selected)?.itemId,
      "minecraft:chest",
      "Middle click must pick the target block",
    );
    await input([
      { kind: Kind.BUTTON, code: 1, action: 1 },
      { kind: Kind.BUTTON, code: 1, action: 0 },
    ]);
    await until(() => !!latest?.screenOpen, "Right click must open a chest");
    await writeFile(`${output}/interactive-chest.jpg`, latest!.image);
    await input([
      { kind: Kind.KEY, code: 256, action: 1 },
      { kind: Kind.KEY, code: 256, action: 0 },
    ]);
    await until(
      () => latest?.screenOpen === false,
      "Escape must close the chest",
    );
    await input([
      { kind: Kind.BUTTON, code: 0, action: 1 },
      { kind: Kind.BUTTON, code: 0, action: 0 },
    ]);
    await sleep(250);
    const world = createClient(WorldService, transport);
    const block = await world.getWorldBlock({
      instanceId,
      botId,
      position: { x: 0, y: -60, z: 4 },
    });
    assert.equal(
      block.block?.blockId,
      "minecraft:air",
      "Left click must break the target block",
    );
    console.log(
      JSON.stringify({
        middleClick: true,
        rightClick: true,
        leftClick: true,
        hotbarScroll: true,
      }),
    );
  } else {
    const before = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    const otherBefore = (
      await bots.getBotInfo({ instanceId, botId: otherBotId })
    ).liveState!;
    captured = true;
    await input([{ kind: Kind.KEY, code: 87, action: 1 }]);
    await sleep(1100);
    await input([{ kind: Kind.KEY, code: 87, action: 0 }]);
    const after = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    const otherAfter = (
      await bots.getBotInfo({ instanceId, botId: otherBotId })
    ).liveState!;
    assert.ok(
      Math.hypot(after.x - before.x, after.z - before.z) > 0.5,
      "W must move the controlled bot",
    );
    assert.ok(
      Math.hypot(otherAfter.x - otherBefore.x, otherAfter.z - otherBefore.z) <
        0.05,
      "Input must not move another bot",
    );
    await input([{ kind: Kind.MOVE, relative: true, x: 100, y: 30 }]);
    await sleep(250);
    const rotated = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    assert.notEqual(
      rotated.yRot,
      after.yRot,
      "Relative mouse movement must rotate the bot",
    );
    await input([
      { kind: Kind.KEY, code: 69, action: 1 },
      { kind: Kind.KEY, code: 69, action: 0 },
    ]);
    await until(() => !!latest?.screenOpen, "E must open the inventory");
    await writeFile(`${output}/interactive-inventory.jpg`, latest!.image);
    // At GUI scale 1, the first hotbar slot is at the inventory's (8, 142) offset.
    await input([
      { kind: Kind.MOVE, x: 248 / 640, y: 247 / 360 },
      { kind: Kind.BUTTON, code: 0, action: 1 },
      { kind: Kind.BUTTON, code: 0, action: 0 },
    ]);
    await sleep(200);
    assert.ok(
      (await bots.getInventoryState({ instanceId, botId })).carriedItem,
      "Native inventory clicks must pick up an item",
    );
    await input([
      { kind: Kind.BUTTON, code: 0, action: 1 },
      { kind: Kind.BUTTON, code: 0, action: 0 },
    ]);
    await input([
      { kind: Kind.KEY, code: 69, action: 1 },
      { kind: Kind.KEY, code: 69, action: 0 },
    ]);
    await until(
      () => latest?.screenOpen === false,
      "E must close the inventory",
    );
    const afterInventory = (await bots.getBotInfo({ instanceId, botId }))
      .liveState!;
    assert.equal(
      afterInventory.yRot,
      rotated.yRot,
      "Inventory interaction must not turn the camera",
    );
    assert.equal(
      afterInventory.xRot,
      rotated.xRot,
      "Inventory interaction must not tilt the camera",
    );
    width = 800;
    height = 450;
    await input();
    await until(
      () => latest?.width === 800 && latest?.height === 450,
      "Live resize must change the encoded frame dimensions",
    );
    await writeFile(`${output}/interactive-world.jpg`, latest!.image);
    // Losing capture while W is held must release it even without a keyup event.
    await input([{ kind: Kind.KEY, code: 87, action: 1 }]);
    captured = false;
    await input();
    await sleep(300);
    const stopped = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    await sleep(500);
    const still = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    assert.ok(
      Math.hypot(still.x - stopped.x, still.z - stopped.z) < 0.05,
      "Release must stop held movement",
    );
    assert.ok(frameCount > 10, "Continuous frame delivery");
    // Keep the stream alive beyond the HTTP server's normal request timeout.
    await sleep(11000);
    assert.equal(
      streamError,
      undefined,
      "An active session must not hit the ordinary RPC timeout",
    );
    await writeFile(`${output}/interactive-world-settled.jpg`, latest!.image);
    clearInterval(heartbeat);
    captured = true;
    await input([{ kind: Kind.KEY, code: 87, action: 1 }]);
    await until(
      () => !!streamError,
      "Missing heartbeats must terminate the stream",
    );
    await sleep(300);
    const timedOut = (await bots.getBotInfo({ instanceId, botId })).liveState!;
    await sleep(500);
    const afterTimeout = (await bots.getBotInfo({ instanceId, botId }))
      .liveState!;
    assert.ok(
      Math.hypot(afterTimeout.x - timedOut.x, afterTimeout.z - timedOut.z) <
        0.05,
      "Heartbeat timeout must release held movement",
    );
    console.log(
      JSON.stringify({
        frameCount,
        movement: true,
        isolatedBots: true,
        mouseLook: true,
        inventoryClick: true,
        liveResize: true,
        releaseHeldKeys: true,
        heartbeatTimeout: true,
        longSession: true,
      }),
    );
  }
} finally {
  clearInterval(heartbeat);
  abort.abort();
  await watching;
}
