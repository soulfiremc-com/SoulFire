# SoulFire TypeScript SDK

`@soulfiremc/sdk` is the Effect-first SDK for SoulFire. It provides typed
operations, streams, scopes, task handles, capability negotiation, and plugin
RPC discovery over gRPC-Web.

Every capability is also available from `@soulfiremc/sdk/promise` for
applications that use Promises and async iterables.

## Install

```bash
bun add @soulfiremc/sdk effect @effect/platform
```

The SDK requires a compatible Effect runtime. Use
`@soulfiremc/sdk/node`, `@soulfiremc/sdk/browser`, or
`@soulfiremc/sdk/bun` to use the matching live HTTP layer from SoulFire. The
universal entry point also accepts any
`@effect/platform/HttpClient`, which keeps tests, workers, Deno, and custom
transport policies portable.

## Install a managed local server on Node.js

JVM download and process management are available only from the explicit Node
entry points. They require Node.js 22 or newer.

```ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk/node";

await Effect.runPromise(
  Effect.scoped(
    Effect.gen(function* () {
      const soulfire = yield* SoulFire.install();
      yield* soulfire
        .instance(instanceId)
        .bot(botId)
        .chat
        .send("Local server ready");
    }),
  ),
);
```

Promise applications import `SoulFire` from
`@soulfiremc/sdk/node/promise`. The universal exports never import Node.js
modules, so browser, worker, Bun, and Deno bundles do not pull in process or
filesystem code.

## Connect with Effect

Connections are scoped resources. Closing the scope closes the client,
subscriptions, and any managed local server.

```ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk";

const program = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect({
      baseUrl: "https://soulfire.example.com",
      token: process.env.SOULFIRE_TOKEN,
    });

    const bot = soulfire.instance("instance-uuid").bot("bot-uuid");
    yield* bot.start();
    yield* bot.chat.send("Hello from SoulFire");
  }),
);

await Effect.runPromise(program);
```

The connection handshake verifies the core API version, required capabilities,
and required plugins before returning a ready client.

## Provide SoulFire as a layer

Use `SoulFire.layer` to provide one shared SoulFire client to application
services:

```ts
import { Effect } from "effect";
import { SoulFire, SoulFireService } from "@soulfiremc/sdk";

const program = Effect.gen(function* () {
  const soulfire = yield* SoulFireService;
  yield* soulfire
    .instance("instance-uuid")
    .bot("bot-uuid")
    .chat
    .send("Ready");
});

await Effect.runPromise(
  program.pipe(
    Effect.provide(SoulFire.layer({
      baseUrl: "https://soulfire.example.com",
      token: process.env.SOULFIRE_TOKEN,
    })),
  ),
);
```

`SoulFire.layerWithHttpClient` accepts an `@effect/platform/HttpClient`. This
is the preferred portability boundary when an application already uses Effect
Platform. The Node entry uses `NodeHttpClient.layerUndici`; browser and Bun
entries use `FetchHttpClient.layer`.

The lower-level `makeEffectHttpClientFetch` adapter is exported from
`@soulfiremc/sdk/platform`.

## Use streams

Server streams become Effect `Stream` values. Stream interruption cancels the
underlying gRPC-Web call.

```ts
import { Effect, Stream } from "effect";
import { SoulFire } from "@soulfiremc/sdk";

const program = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect(options);
    const bot = soulfire.instance(instanceId).bot(botId);

    yield* bot.events().pipe(
      Stream.runForEach((event) => Effect.logInfo(event.event.case)),
    );
  }),
);
```

Bot event streams stay attached to the configured bot across Minecraft
reconnects. Stateful consumers can use `bot.observe()` to merge snapshots and
deltas into a session.

## Run the beat-game application

Game-specific progression lives in the separate Effect-first
`@soulfiremc/beat-game` package. The package consumes only this SDK's public
bot observations, actions, pathfinding, tasks, control leases, and plugin APIs.

```ts
import { beatGame } from "@soulfiremc/beat-game";

const bot = soulfire.instance(instanceId).bot(botId);
const run = yield* beatGame(bot);
const result = yield* run.awaitCompletion;
```

The Promise entry point is `@soulfiremc/beat-game/promise`. See the
[beat-game package guide](../beat-game/README.md) for checkpoint persistence,
team runs, strategy hooks, events, and reusable behavior programs.

Use `toReadableStream(events)` from `@soulfiremc/sdk/promise` when a Web
`ReadableStream` fits the surrounding runtime better than `for await`.
Backpressure advances the server iterator one item at a time, and cancelling
the stream closes the underlying subscription.

## Orchestrate a fleet

`instance.fleet` applies one reusable selector to lifecycle changes, work
distribution, and typed durable tasks:

```ts
const builders = {
  online: true,
  dimensions: ["minecraft:overworld"],
  minimumHealth: 12,
  metadata: [{
    namespace: "fleet",
    key: "role",
    equals: "builder",
  }],
};

const assignments = yield* instance.fleet.distribute(
  schematicSections,
  builders,
);

const group = yield* instance.fleet.startTasks(
  builders,
  BuildTaskSchema,
  (bot, index, total) => ({
    ...buildInputFor(assignments[index]!.items),
    partitionIndex: index,
    partitionCount: total,
  }),
  BuildTaskResultSchema,
  { concurrency: 4 },
);

yield* group.events().pipe(
  Stream.runForEach(({ bot, event }) =>
    Effect.logInfo(`${bot.id}: ${event.task?.summary ?? "task update"}`)
  ),
);

const report = yield* group.results();
```

Selectors can combine bot IDs, account names and types, controller state,
connection phase, persistent account metadata, dimension, position, health,
food, ping, negotiated capabilities, and a custom predicate. Group starts and
cancellation have bounded concurrency. Results preserve each bot identity and
aggregate failures instead of losing successful work.

## Capture cameras and world maps

`bot.camera` exposes the software renderer as a complete SDK surface. Capture
the bot's current view or place a free camera at an explicit position and
rotation:

```ts
const image = yield* bot.camera.capture({
  width: 1280,
  height: 720,
  cameraX: 120.5,
  cameraY: 80,
  cameraZ: -32.5,
  yRot: 180,
  xRot: -20,
  fov: 80,
  maxDistance: 256,
  includeHud: false,
  includeHands: false,
  includeDebugTrace: true,
});

const png = decodeCameraImage(image);
```

`bot.camera.frames()` is an Effect `Stream` in the default package and an
`AsyncIterable` in `@soulfiremc/sdk/promise`. Each frame reports how many
scheduled frames were dropped while the transport was backpressured.

```ts
yield* bot.camera.frames({
  width: 854,
  height: 480,
  intervalMs: 250,
}).pipe(
  Stream.runForEach((frame) =>
    persistFrame(decodeCameraImage(frame.render!))
  ),
);

const map = yield* bot.camera.worldMap({
  radius: 128,
  sampleStep: 2,
  includeEntities: true,
});
```

World maps contain one surface sample per grid column, including loaded state,
height, block, biome, and light data, plus optional entity overlays.

## Run durable tasks

Continuous work belongs on the SoulFire server. A task remains observable by
ID, reports progress, participates in resource arbitration, and can be
cancelled explicitly.

```ts
const task = yield* bot.tasks.autoEat(
  ["minecraft:bread", "minecraft:cooked_beef"],
  {
    foodLevel: 14,
    maximumMeals: 1,
  },
);

const result = yield* task.result();
yield* Effect.logInfo(`Ate ${result.mealsEaten} meal`);
```

The task API currently includes durable pathfinding, block collection, cuboid
excavation, transformed schematic construction, crafting, smelting, batched
potion brewing, exact-count villager trading, entity following, managed melee
and ranged combat, position guarding, entity protection, bed discovery and
sleeping, server-timed fishing, mature crop harvesting and replanting,
verified animal feeding for breeding, automatic eating, automatic respawning,
automatic armor, automatic totems, and coordinated frontier exploration. Bots
can also navigate to block containers for transactional stash and withdrawal
batches and ongoing loadout maintenance. `run*` variants attach task ownership
to a stream so interruption cancels the remote task.

Managed combat can acquire targets on the server and select the strongest
allowed weapon:

```ts
const hunt = yield* bot.tasks.attackNearest(
  { entityTypes: ["minecraft:zombie"] },
  {
    radius: 48,
    maximumTargets: 3,
    weapon: { tags: ["minecraft:swords"] },
  },
);

const archer = yield* bot.tasks.rangedAttack(target, {
  minimumRange: 10,
  maximumRange: 32,
  maximumShots: 6,
  weapon: { itemIds: ["minecraft:bow"] },
  leadTarget: true,
  compensateGravity: true,
  strafe: true,
});

const escape = yield* bot.tasks.flee(
  { categories: [EntityCategory.HOSTILE] },
  { triggerRadius: 8, safeDistance: 20 },
);

const defense = yield* bot.tasks.guard(
  { x: 120, y: 64, z: -32 },
  { categories: [EntityCategory.HOSTILE] },
  {
    guardRadius: 16,
    maximumPursuitDistance: 24,
  },
);

const escort = yield* bot.tasks.protect(
  teammate,
  { categories: [EntityCategory.HOSTILE] },
);

const rest = yield* bot.tasks.sleep({
  searchRadius: 24,
  waitUntilPossible: true,
});

const fishing = yield* bot.tasks.fish({
  maximumCatches: 3,
});

const harvest = yield* bot.tasks.farm({
  cropIds: ["minecraft:wheat", "minecraft:carrots"],
  center: farmCenter,
  radius: 16,
  maximumHarvests: 24,
  replant: true,
});

const breeding = yield* bot.tasks.breed({
  animals: { entityTypes: ["minecraft:cow"] },
  food: { itemIds: ["minecraft:wheat"] },
  maximumPairs: 2,
});

const scouting = yield* bot.tasks.explore({
  origin: basePosition,
  radius: 512,
  waypointSpacing: 64,
  maximumWaypoints: 8,
  returnToOrigin: true,
  purpose: "village-scouting",
});

const supplies = yield* bot.tasks.withdraw(
  storageChest,
  [
    {
      selector: { itemIds: ["minecraft:bread"] },
      count: 16,
    },
    {
      selector: { tags: ["minecraft:coals"] },
      count: 8,
      allowPartial: true,
    },
  ],
);
```

Recipes combine discovery with execution:

```ts
const recipes = yield* bot.recipes.list({
  resultItemId: "minecraft:iron_ingot",
});

const smelting = yield* bot.recipes.smelt(
  { itemIds: ["minecraft:raw_iron"] },
  8,
  {
    fuel: { tags: ["minecraft:coals"] },
    station: furnacePosition,
  },
);

yield* smelting.result();

const brewing = yield* bot.recipes.brew(
  { fingerprint: waterPotionFingerprint },
  { itemIds: ["minecraft:nether_wart"] },
  3,
  {
    expectedResult: { fingerprint: awkwardPotionFingerprint },
    station: brewingStandPosition,
  },
);

yield* brewing.result();

// Open a villager menu through an entity interaction first.
const offers = yield* bot.recipes.listVillagerTrades();
const trading = yield* bot.recipes.villagerTrade(offers.offers[0]!.offerIndex, 3, {
  expectedResult: {
    itemIds: [offers.offers[0]!.result!.itemId],
  },
});
yield* trading.result();
```

Inventory recommendations use server-side Minecraft data and expose their
score factors:

```ts
const tool = yield* bot.inventory.bestTool(blockPosition, {
  preferHotbar: true,
  preferHighDurability: true,
  preferredEnchantmentIds: ["minecraft:fortune"],
});
```

## Compose behaviors

Effect-first combinators compose durable tasks without moving game-tick work
into the SDK process:

```ts
const workflow = cleanup(
  sequence(
    collectBlocks({
      blockIds: [],
      tags: ["minecraft:logs"],
      count: 16,
    }),
    retry(buildShelter, {
      attempts: 3,
      delayMs: 500,
      backoff: 2,
    }),
  ),
  releaseTemporaryClaims,
);

const results = yield* workflow.run(bot);
```

The SDK includes `sequence`, `parallel`, `race`, `repeat`, `retry`, `timeout`,
`until`, `conditional`, `fallback`, `cleanup`, and `scopedLease`. Effect uses
fiber interruption. The Promise entry point exposes the same concepts with
linked `AbortSignal` cancellation.

## Call plugin APIs

SoulFire plugins can register typed unary and server-streaming RPCs. The SDK
handshake exposes the installed plugin catalog and downloadable protobuf
descriptors.

Use a companion module when one is available:

```ts
const plugin = yield* soulfire.plugins.require(examplePlugin);
const reply = yield* plugin.echo(instanceId, "hello");
```

Use `soulfire.plugins.reflective(pluginId)` to inspect and call an installed
plugin when no companion package is installed.

Plugins can also publish permission-scoped protobuf events. Typed consumers get
the decoded message and the delivery envelope through an Effect stream:

```ts
yield* soulfire.plugins
  .typedEvents("example", TickSchema, { instanceId })
  .pipe(
    Stream.runForEach(({ event, value }) =>
      value === undefined
        ? Effect.logInfo(`Ready at sequence ${event.sequence}`)
        : Effect.logInfo(`Tick ${value.sequence}`)
    ),
  );
```

The first envelope reports whether the stream resumed after `afterSequence`.
`droppedBefore` reports backpressure loss. The Promise facade exposes the same
operation as an `AsyncIterable`.

Plugin authors can generate a complete companion package from a running server:

```bash
bunx soulfire-sdk generate \
  --server https://soulfire.example.com \
  --plugin example \
  --language typescript \
  --output packages/soulfire-example
```

Set `SOULFIRE_TOKEN` for authenticated servers. An offline plugin build can use
`--descriptor plugin-api.binpb` instead. The generator verifies live descriptor
hashes and creates pinned protobuf bindings, compatibility metadata, and
ergonomic clients. TypeScript output is Effect-first and exposes the same module
to the Promise facade. Python output requires CPython 3.14 and includes both
async and sync clients.

The generator requires Node.js 22 or newer. Its pinned Buf binary and remote
plugin versions make repeated generation deterministic.

## Use the Promise facade

The Promise facade is backed by the same Effect operations and managed
runtime:

```ts
import { SoulFire } from "@soulfiremc/sdk/promise";

await using soulfire = await SoulFire.connect({
  baseUrl: "https://soulfire.example.com",
  token: process.env.SOULFIRE_TOKEN,
});

const bot = soulfire.instance("instance-uuid").bot("bot-uuid");
await bot.chat.send("Hello from a Promise application");

for await (const event of bot.events()) {
  console.log(event);
}
```

Promise streams stay lazy and preserve backpressure. Pass an `AbortSignal`
through call options to cancel an operation or stream.

## Administer SoulFire

`soulfire.admin` wraps the existing administrative services in the same
Effect-first object model:

```ts
const server = yield* soulfire.admin.serverInfo();
const users = yield* soulfire.admin.users();
const metrics = yield* soulfire.admin.instanceMetrics(instance.id);
const audits = yield* soulfire.admin.auditLog(instance.id);

yield* soulfire.admin.logs({
  scope: {
    scope: {
      case: "instance",
      value: { instanceId: instance.id },
    },
  },
}).pipe(
  Stream.runForEach((entry) =>
    Effect.logInfo(entry.message?.message ?? "")
  ),
);
```

The admin API covers self-service tokens and profile changes, server settings,
users and session revocation, scoped logs, server and instance metrics,
commands and completion, permission-scoped downloads, plugin metrics, audit
logs, and the complete script lifecycle. The generated clients remain
available through `soulfire.service()` for uncommon fields.

## Use the raw Minecraft protocol

`bot.protocol` exposes the active native Minecraft packet registry as an
advanced, version-dependent API:

```ts
import { PacketDirection } from "@soulfiremc/sdk";

const info = yield* bot.protocol.info();
const schemas = yield* bot.protocol.schemas(PacketDirection.SERVERBOUND);

yield* bot.protocol.packets({
  directions: [PacketDirection.CLIENTBOUND],
  names: ["minecraft:game_event"],
}).pipe(Stream.runDrain);
```

Raw sends accept one complete unframed native packet, including its packet ID.
They require the admin-only `RAW_PROTOCOL` permission and support an
`expectedName` guard. Packet observation is filtered and backpressure-aware.
The server reports the native packet version separately from the remote
ViaVersion protocol.

## Use generated protocol definitions

Generated protobuf services remain available as an advanced escape hatch:

```ts
import {
  InstanceService,
} from "@soulfiremc/sdk/generated/soulfire/instance_pb";

const instances = soulfire.service(InstanceService);
```

Prefer the high-level object model for application code. Generated services
expose the wire contract directly and do not add SDK validation or task
semantics.
