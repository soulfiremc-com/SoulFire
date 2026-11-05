# Soulfire SDK Improvement Plan

Status: Implemented locally; external release gates remain
Audience: Soulfire maintainers, SDK maintainers, plugin authors, and contributors
Document type: Explanation and implementation roadmap

## Implementation status

The protocol, server implementation, TypeScript SDK, Python SDK, plugin
extension platform, generated bindings, conformance tests, release automation,
Mineflayer parity catalog, and website documentation described by this plan
are implemented in the working release candidate.

Items that need publishing authority, provisioned Minecraft accounts and
servers, dedicated scale infrastructure, independent review, or a future
cross-runtime streaming transport are tracked in
[`TODO_LATER.md`](TODO_LATER.md). Those external gates do not replace local
implementation work.

## North star

Soulfire should become the default recommendation for developers who would otherwise use mineflayer.

A developer should be able to install the official Soulfire SDK, connect to one or many bots, and build anything from a chat bot to a survival agent without reaching for raw generated RPC clients or reimplementing basic Minecraft behavior.

Soulfire must also remain open-ended. A server plugin must be able to define new typed RPCs, events, tasks, permissions, and SDK features. Plugin authors should be able to publish a Soulfire plugin and companion TypeScript or Python package that feels like a native part of the official SDK.

The replacement promise is:

> Soulfire provides an idiomatic, typed, observable, extensible, and multi-bot Minecraft automation SDK with enough high-level capability for mineflayer users and enough low-level access for advanced protocol work.

This is broader than feature-for-feature API copying. Soulfire should preserve the useful ideas behind mineflayer while taking advantage of its own architecture:

- Bots run in a managed server process rather than inside each application.
- Bot intent can survive an SDK process restart.
- One application can control a fleet.
- Permissions and audit logs are built in.
- Long-running work can execute beside the Minecraft game loop.
- Java server plugins can add capabilities.
- TypeScript and Python applications can use the same protocol.
- SoulFireClient can provide a first-party control surface for the same APIs.

## Reader goal

By the end of this document, a maintainer should understand:

- What a complete Soulfire SDK needs to expose.
- How low-level actions differ from long-running tasks and team automation.
- How plugins define RPCs and make them callable from the SDK.
- How the TypeScript and Python SDKs remain idiomatic and aligned.
- How Soulfire reaches mineflayer feature parity without inheriting its architectural limitations.
- Which implementation phases should happen first.
- What must be true before the SDK is declared stable.

## Scope

This plan includes:

- Core protobuf APIs.
- TypeScript and Python SDK design.
- Live state and event infrastructure.
- Bot actions and long-running tasks.
- World, entity, inventory, recipe, pathfinding, combat, and behavior APIs.
- Plugin-defined RPCs, events, tasks, permissions, and SDK modules.
- SoulFireClient adoption of the official SDK.
- An Effect TypeScript SDK with interoperability at host and transport boundaries.
- A modern Python SDK that targets the newest stable Python and uses its latest useful language and concurrency APIs.
- Publishing the complete SDK documentation in the `soulfiremc.com` repository.
- A mineflayer migration and native parity strategy.
- Protocol compatibility, testing, documentation, and release policy.

This plan does not replace the survival automation roadmap. Beat-game planning, team roles, structure solving, and run strategy remain in [`docs/automation-roadmap.md`](docs/automation-roadmap.md). This document defines the general platform that those features and third-party automation should use.

## Success criteria

Soulfire can call the first official SDK release successful when:

- A new user can build a useful bot without importing a generated protobuf service.
- The official SDK covers the common mineflayer object model and action surface.
- Long-running behaviors execute reliably on the Soulfire server.
- TypeScript and Python expose the same capabilities with language-appropriate naming.
- Every core mineflayer method and event has a documented Soulfire mapping.
- Popular mineflayer plugin categories have a native equivalent or a documented migration.
- A Soulfire plugin can register typed unary and server-streaming RPCs.
- Plugin protobuf descriptors are discoverable at runtime.
- Plugin SDK bindings can be generated for TypeScript and Python.
- A plugin can publish a companion ergonomic SDK module.
- Unknown plugin services can still be called through a reflective SDK API.
- Plugin tasks use the same cancellation, progress, arbitration, permission, and audit infrastructure as core tasks.
- TypeScript exposes Effect-native errors, streams, scopes, layers, retries, and interruption as its canonical API.
- Async TypeScript applications run scoped SDK workflows through `Effect.runPromise`.
- Python uses the newest stable CPython minor selected for the SDK release and does not carry compatibility shims for older Python versions.
- Python exposes one effect-py API with shared domain models and scoped resources.
- SoulFireClient consumes the official protocol and SDK instead of maintaining a separate copy of the API.
- The SDK, protocol, and plugin authoring documentation is published and maintained on `soulfiremc.com`.
- Core and plugin APIs have automated compatibility checks.
- The SDK includes a supported low-level escape hatch for use cases that cannot fit a stable high-level API.

## Product principles

### The official SDK is the primary product surface

Generated RPC clients remain available, but most users should not need them. The high-level SDK should own:

- Domain models.
- Validation.
- State synchronization.
- Cancellation.
- Retry and reconnect behavior.
- Error normalization.
- Task handles.
- Resource cleanup.
- Language-specific ergonomics.

### Each SDK is native to its language

TypeScript and Python must share concepts and capabilities, but they should not imitate each other's syntax or runtime model.

The TypeScript SDK is Effect-first. Effect owns typed failures, structured concurrency, interruption, resource scopes, streams, retry schedules, dependency injection, and observability.

The Python SDK uses effect-py. Native effects own typed failures, service dependencies, scoped resources, fibers, schedules, and tracing. SDK pull streams acquire one cursor per subscription and close it through native scopes. Generated ConnectRPC clients remain the async and synchronous transport boundary.

Generated protobuf and transport clients remain implementation details in both SDKs.

### Remote work belongs near the game loop

Mineflayer executes in the same process as the bot. Soulfire SDK applications are remote. A high-level behavior built from dozens of network round trips will be slower and less reliable than the equivalent server-side task.

The SDK may compose atomic actions for short workflows, but continuous behaviors such as combat, following, auto-eating, collection, and construction should run on the server.

### Stable APIs use Minecraft concepts, not implementation details

Public domain models should use concepts such as:

- `Block`
- `Entity`
- `ItemStack`
- `Inventory`
- `Container`
- `Recipe`
- `PathGoal`
- `BotTask`

Generated protobuf messages and Minecraft packet names are advanced surfaces. They should not define the default user experience.

### Extensibility is part of compatibility

Mineflayer is useful because an application can load plugins and reach low-level behavior. Soulfire cannot be a credible replacement if server capabilities are closed.

Plugin-defined RPCs, tasks, events, permissions, and SDK modules are release requirements, not optional ecosystem work.

### Multi-bot support is native

Every API must consider:

- One bot.
- A selected group of bots.
- An entire instance.
- Fleet-wide streams.
- Shared control and leases.
- Resource limits and backpressure.

Multi-bot support should not be a loop around a single-bot API when the server can perform the operation more efficiently.

### Compatibility is explicit

The SDK should never silently assume a server supports a feature. It should negotiate:

- Soulfire server version.
- Core API version.
- Minecraft version.
- Connection protocol version.
- Installed plugins and plugin versions.
- Supported features and capability flags.

## Baseline at proposal time

Soulfire already had a useful foundation when this plan was written.

### Core protocol

The core API at the start of this work supported:

- Persistent start, stop, and restart intent.
- Bot status streams.
- Live snapshots and state deltas.
- Chat and lifecycle events.
- Block and entity observation events.
- Inventory and damage events.
- Block and entity queries.
- Chat, dig, place, use, attack, interact, swing, and respawn actions.
- Manual movement and rotation.
- Inventory clicks and generic container layouts.
- Pathfinding to block, position, entity, and XZ goals.
- Exclusive control leases.
- POV rendering.
- Instance-wide chat and lifecycle streams.
- Team automation, memory, coordination, settings, and controls.
- Scripts, commands, logs, metrics, authentication, accounts, and proxies.

### Official SDKs

The TypeScript and Python SDKs at the start of this work provided:

- Authentication and server installation.
- Instance and bot selection.
- Bot lifecycle operations.
- Bot status and event streams.
- Low-level world and interaction actions.
- Inventory clicking and movement helpers.
- Pathfinding.
- Control leases.
- Five client-side behaviors: collect blocks, follow entity, attack nearest, auto-eat, and direct block placement.
- Access to generated clients for every RPC.

### SoulFireClient

SoulFireClient demonstrated several important product surfaces:

- Bot state and inventory.
- POV rendering.
- Dialog interaction.
- Fleet state.
- Automation state, memory, coordination, and controls.
- Instance logs, metrics, scripts, settings, accounts, and proxies.

At that point it used its own generated protocol copy and polled several live
views. The release candidate now consumes the published SDK's protocol
exports. Full adoption of the high-level `BotSession` surface follows the
published 0.2 package and is tracked in [`TODO_LATER.md`](TODO_LATER.md).

### Plugin system

The plugin system at the start of this work supported:

- Internal and external plugins.
- Event listeners.
- Settings pages.
- Commands and other server extension hooks.
- Plugin metadata and runtime metrics.

RPC services were registered directly in `RPCServer`. Plugins did not yet have
a public RPC registry, descriptor catalog, dynamic permission system, or
companion SDK workflow.

## Target architecture

The platform should expose four explicit bot layers.

```text
Application
    |
Official TypeScript or Python SDK
    |
    +-- Observation layer
    +-- Atomic action layer
    +-- Long-running task layer
    +-- Team automation layer
    |
SoulFire server
    |
Minecraft client state and plugin capabilities
```

### Observation layer

The observation layer answers:

- What is the bot's current state?
- What blocks and entities are loaded?
- What changed?
- What did the server send?
- What capabilities are available?

It contains state snapshots, resumable event streams, world queries, registry data, and plugin events.

### Atomic action layer

The atomic action layer performs bounded work:

- Send a chat message.
- Look at a point.
- Click one inventory slot.
- Swing an arm.
- Interact with a block or entity.
- Start or stop using an item.

Atomic actions should finish quickly and return a typed result.

### Long-running task layer

The task layer owns work that has progress, retries, cancellation, resource conflicts, or a meaningful lifecycle:

- Go to a target.
- Follow an entity.
- Collect blocks or dropped items.
- Fight an entity.
- Eat when hungry.
- Equip the best tool.
- Craft or smelt a result.
- Transfer inventory.
- Build a structure.
- Farm an area.

Tasks execute on the Soulfire server and return task handles to SDK applications.

### Team automation layer

The automation layer owns planners and coordinated objectives:

- Acquire a requirement through multiple possible strategies.
- Beat Minecraft.
- Coordinate roles and resource quotas.
- Share claims and memory.
- Recover from stalls and deaths.

Automation may create core or plugin tasks, but it remains a separate concept. A generic `collect blocks` task should not require the beat-game automation controller.

## Official SDK object model

The SDK should converge on a coherent object hierarchy.

```text
SoulFire
├── server
├── auth
├── instances
├── plugins
└── protocol

SoulFireInstance
├── bots
├── fleet
├── events
├── automation
├── scripts
├── commands
├── logs
├── metrics
└── metadata

SoulFireBot
├── session
├── state
├── world
├── entities
├── chat
├── movement
├── pathfinder
├── inventory
├── equipment
├── containers
├── recipes
├── combat
├── tasks
├── automation
├── camera
└── protocol
```

The TypeScript and Python SDKs should share the same conceptual hierarchy. TypeScript uses camel case, `Effect`, `Stream`, `Scope`, and `Layer`. Python uses snake case, effect-py effects, scopes, layers, and SDK pull streams.

## Language-native SDK design

The SDK language design is a release-level architecture decision. It must be settled before the high-level surface grows, because errors, cancellation, streaming, resource ownership, plugin integration, and testing all depend on it.

### TypeScript is Effect-first

Decision: accepted for the first official release.

The canonical TypeScript API should use the latest stable Effect major available when the SDK dependency set is frozen.

This is a first-release requirement, not an optional integration. Core
operations, generated plugin clients, streams, resource ownership, retries,
interruption, and typed failures must all be designed in Effect first. Async hosts run that operation model through Effect runtime boundaries.

Core signatures should use:

- `Effect.Effect<Success, SoulFireError, Requirements>` for operations.
- `Stream.Stream<Event, SoulFireStreamError, Requirements>` for events, task progress, logs, and live state.
- `Scope` for connections, bot sessions, control leases, subscriptions, and local server processes.
- `Layer` and service tags for transports, authentication, configuration, plugin clients, tracing, and test implementations.
- `Schedule` for explicit retry, reconnect, polling, and backoff policies.
- `Schema` for runtime-validated SDK configuration, plugin metadata, reflective calls, and stable domain values where it improves safety.
- Tagged error types for expected failures.
- Defects only for invariant violations and programmer errors.

Effect should be a supported peer dependency of `@soulfiremc/sdk`. The package must pin and test a documented compatible range so applications do not receive multiple incompatible Effect runtimes.

The public API should support both direct acquisition and dependency injection:

```ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk";

const program = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect({
      baseUrl: "https://soulfire.example.com",
      token: process.env.SOULFIRE_TOKEN,
    });

    const bot = yield* soulfire.instance(instanceId).bot(botId);
    yield* bot.chat.send("Hello from Soulfire");
  }),
);

await Effect.runPromise(program);
```

Also provide `SoulFire.layer(options)` so applications can install Soulfire as a service, replace it in tests, and compose it with application layers.

#### Effect error model

Every expected failure should be present in the Effect error channel:

```ts
type SoulFireError =
  | SoulFireConnectionError
  | SoulFireAuthenticationError
  | SoulFirePermissionError
  | SoulFireCompatibilityError
  | BotOfflineError
  | TaskConflictError
  | PluginNotInstalledError;
```

Errors should be tagged, serializable where practical, and carry the server error code, request ID, operation, retryability, and relevant resource identifiers.

Methods should narrow their error unions. For example, a local selector should not claim it can fail with an authentication error after the authenticated client and session have already been acquired.

#### Effect streams and state

- Convert every server stream into an Effect `Stream`.
- Preserve typed stream failures instead of throwing from an async generator.
- Model resubscription and resume policies with explicit schedules.
- Keep backpressure visible and configurable.
- Use scoped stream acquisition so cancellation closes the underlying ConnectRPC call.
- Expose state snapshots through immutable values and state changes through streams.
- Make bot tasks interruptible without confusing SDK fiber interruption with server task cancellation.
- Require an explicit policy when interrupting an SDK fiber should also cancel its server task.

#### Effect service and plugin composition

Core and plugin services should compose through the same Effect mechanisms:

- Generated plugin modules expose Effect-returning methods and Effect streams.
- A plugin companion package may publish service tags and layers.
- Reflective plugin calls return typed Effect failures.
- Plugin connection and permission checks occur while building the plugin layer.
- Plugin scopes release subscriptions, leases, and server task ownership correctly.
- Test layers can replace core or plugin services without network access.

The plugin generator emits Effect operations and streams from the service description. Plugin authors maintain one client.

### General TypeScript compatibility

Effect is the sole supported high-level TypeScript API. Lifecycle management
and orchestration use Effect scopes, fibers, streams, and typed failures.
Promise conversion occurs at transport and host boundaries.

Use these package boundaries:

- `@soulfiremc/sdk` provides the runtime-neutral Effect API.
- `@soulfiremc/sdk/browser`, `@soulfiremc/sdk/node`, and
  `@soulfiremc/sdk/bun` provide platform layers and convenience constructors.
- `effect` v4 provides execution, host interoperability, and portable runtime service contracts.

An Effect application composes Soulfire with its layers and runtime. An async
application runs one scoped workflow with `Effect.runPromise`. It forwards
its `AbortSignal` to that runtime boundary.

```ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk";

await Effect.runPromise(
  Effect.scoped(
    Effect.gen(function* () {
      const soulfire = yield* SoulFire.connect(options);
      const bot = soulfire.instance(instanceId).bot(botId);
      yield* bot.chat.send("Hello from Soulfire");
    }),
  ),
  { signal },
);
```

Use `Stream.toAsyncIterable` or `Stream.toReadableStream` when the host needs
those interfaces. Consume adapters within the connection's lifetime.
Generated ConnectRPC clients remain available as a transport escape hatch.

All behavior combinators use the same Effect implementation. Stream cleanup
aborts pending transport reads before closing their iterators.

### Cross-runtime TypeScript support

Use Effect v4 service interfaces for HTTP, files, process execution, sockets, and other runtime capabilities. Applications provide matching layers from `@effect/platform-browser`, `@effect/platform-node`, or `@effect/platform-bun`. Deno and worker transports use `effect/http/FetchHttpClient` where ConnectRPC supports their Fetch implementation.

Keep the platform-specific packages out of the browser-safe core dependency graph. Provide explicit runtime entry points or layer constructors instead of detecting a runtime and importing its adapter dynamically. This keeps bundles predictable and lets applications replace transports in tests.

The Effect package roles should be:

- `effect` v4: operations, streams, scopes, schedules, schemas, portable runtime services, and Promise or async-iterable interop.
- `@effect/platform-node` supplies the Node.js HTTP layer. Browser, Bun,
  Deno, and worker transports use `effect/http/FetchHttpClient` while
  their stable Web Fetch implementations meet ConnectRPC's requirements.
  Other runtime services can come from `@effect/platform-browser` and
  `@effect/platform-bun` as the SDK adopts them.
- `@effect/opentelemetry`: optional tracing and metrics integration that consumes Soulfire spans and attributes.
- `@effect/vitest`: development-only helpers for deterministic Effect, stream, scope, and interruption tests.
- `effect/rpc`: an optional source of client API design ideas, not the Soulfire wire protocol.

The SDK should:

- Keep protobuf and ConnectRPC as the language-neutral wire contract.
- Express transport, fetch, file access, process control, and runtime concerns as replaceable services or layers.
- Adapt an `effect/http/HttpClient` to ConnectRPC without forcing a global `fetch`.
- Let ordinary applications supply a standard `fetch`, `AbortSignal`, `Promise`, `AsyncIterable`, or `ReadableStream` at the package boundary.
- Ship browser-safe exports that do not import Node.js modules.
- Load local-server installation code only from the Node.js-specific entry point.
- Test Node.js and browser runtimes as release requirements.
- Add Bun, Deno, and worker tests where the selected ConnectRPC transport supports them.
- Use stable Effect modules in the SDK when dependencies are frozen.
- Test that only one compatible Effect runtime is installed when the SDK is consumed through workspaces, npm, pnpm, Bun, and Deno.

`effect/rpc` should not replace Soulfire's protobuf protocol. It may inform internal ergonomics, but doing so must not weaken TypeScript and Python protocol parity, server reflection, or plugin descriptor interoperability.

### Modern Python baseline

Decision: accepted for the first official release.

The first official Python SDK requires CPython 3.14 or newer. CPython 3.14 is
the newest stable feature release as of this plan's July 2026 review.
Revalidate that baseline when the release dependency set is frozen and move it
to a newer stable CPython minor if one exists and the complete dependency
toolchain supports it.

For the first release, package metadata must declare
`requires-python = ">=3.14"`. Python 3.14 is the hard minimum unless a newer
stable CPython becomes the selected baseline before the release freeze. Do not
lower the minimum to accommodate older applications or lagging optional
dependencies. A required dependency that cannot support the chosen baseline
blocks the release.

This policy exists so the SDK can use current standard-library APIs directly instead of carrying compatibility branches, backports, or shims. The selected Python minimum remains stable for the lifetime of an SDK major. A later SDK major may advance it to the newest stable CPython available at that release.

Release policy:

- Block the stable SDK release until ConnectRPC, protobuf, build tooling, and test tooling support the selected Python version.
- Test the selected minimum and the latest Python patch release in CI.
- Test the next Python prerelease in a non-blocking job so upcoming incompatibilities are found early.
- Remove code paths for older Python versions rather than maintaining runtime version checks.
- Document the exact Python requirement in package metadata, installation docs, and release notes.
- Keep `requires-python`, Ruff's target, Pyright's target, generated code, CI images, and documentation on the same CPython minor.

#### Python client shape

The Python SDK uses one effect-py implementation. The selected dependency is
`effect-python==0.1.0a2`, imported as `effect_py`. It is an alpha release, so
runtime upgrades require SDK validation before changing the pin.

- `SoulFire` is the single high-level client.
- Unary operations return native `Effect[A, E, R]` values.
- `@fn` and `EffectGen` keep generator workflows strictly typed.
- Connections, sessions, leases, containers, and local servers use `Scope`.
- Layers provide shared connections and replaceable services.
- Streams open a scoped cursor for each subscriber.
- Native fibers inherit services and await interruption cleanup.
- Schedules provide typed retry and timeout behavior.
- Expected errors remain in `E`; unexpected exceptions remain defects.
- Generated ConnectRPC clients expose raw async and synchronous calls.

```python
from effect_py import gen, run_async, scoped, sync
from soulfire import SoulFire


@gen
def program():
    client = yield from SoulFire.connect("https://soulfire.example.com", token="token")
    bot = client.instance(instance_id).bot(bot_id)
    yield from bot.chat.send("Ready")
    yield from bot.events().run_for_each(lambda event: sync(lambda: print(event)))


await run_async(scoped(program).or_die())
```

The host runs a complete workflow at one runtime boundary. `run_sync` is for
synchronous effects; RPC operations require `run_async`. Do not recreate a
runtime for each operation or maintain duplicate high-level sync/async classes.

`effect-py` does not provide a native Stream implementation in this release.
The SDK's pull stream composes its native effects, scopes, and fibers. It
provides typed transformation, bounded merging, folds, and scoped consumers.

The transport and async runtime use asyncio. No Trio or AnyIO compatibility
is claimed. Python keeps its modern typing syntax and frozen domain dataclasses.

### Language design acceptance suite

Before freezing the APIs, implement the same representative programs in Effect TypeScript, an async TypeScript host, and effect-py Python:

1. Connect, negotiate capabilities, and close cleanly.
2. Build a chat bot with a cancellable event stream.
3. Observe synchronized bot state and resume after a disconnect.
4. Run concurrent work across multiple bots.
5. Start, observe, interrupt, resume, and cancel a server task.
6. Call a generated plugin RPC and consume a plugin event stream.
7. Call an unknown plugin reflectively.
8. Install and stop a local Soulfire server.

Treat these programs as compile-time and integration tests. The native language surfaces may look different, but their capabilities and protocol behavior must match.

Subsystem snippets in this roadmap illustrate domain operations. Use `yield*` inside `Effect.gen` for SDK calls. The SDK README contains checked examples of the current public API.

## Connection and capability negotiation

The SDK should perform an explicit handshake before returning a ready client.

The handshake returns:

- Soulfire version and commit.
- Core API compatibility version.
- Native Minecraft version.
- Supported connection protocol versions.
- Supported transports.
- Enabled core feature flags.
- Installed plugin catalog.
- Server limits.
- Authentication identity and permissions.

TypeScript:

```ts
const inspectServer = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect({
      baseUrl: "https://soulfire.example.com",
      token: process.env.SOULFIRE_TOKEN,
    });

    yield* Effect.logInfo("Connected to Soulfire", {
      version: soulfire.server.version,
      minecraftVersion: soulfire.server.minecraftVersion,
      tasks: soulfire.capabilities.supports("bot.tasks.v1"),
    });
  }),
);
```

Python:

```python
client = yield from SoulFire.connect(
    "https://soulfire.example.com",
    token=os.environ["SOULFIRE_TOKEN"],
)
print(client.server.version)
print(client.capabilities.supports("bot.tasks.v1"))
```

The SDK should fail early with a typed compatibility error when the server is too old, too new, or missing a required plugin.

## Stateful bot sessions

The SDK should maintain an optional synchronized bot session.

```ts
const session = await bot.observe({
  inventory: true,
  entities: { radius: 64 },
  blocks: { radius: 24 },
});

console.log(session.state.player.position);
console.log(session.state.player.health);
console.log(session.state.inventory);

for await (const message of session.chat.events()) {
  console.log(message.senderName, message.text);
}
```

`BotSession` should:

- Open one live event stream.
- Merge snapshots and deltas.
- Reconnect automatically.
- Resume from the last confirmed event when possible.
- Request a resync when continuity is lost.
- Keep read-only state maps for entities, players, blocks, inventory, effects, teams, boss bars, and scoreboards.
- Expose typed event filters.
- Support `waitFor` and `once` helpers.
- Close cleanly through native scopes and host cancellation at the runtime boundary.

### Event envelope

Every live event should carry:

- Bot ID.
- Connection or stream epoch.
- Monotonic sequence number.
- Observation timestamp.
- Optional causation ID.
- Optional task ID.
- Payload.

The protocol should support:

- Resume after a sequence number.
- Explicit snapshot revisions.
- Resync-required events.
- Backpressure limits.
- Server-side filters.
- Heartbeats.

### Instance-wide streams

The instance stream should be able to multiplex any bot event, not only chat and lifecycle.

It should support:

- Bot ID filters.
- Event category filters.
- One snapshot for fleet status.
- Per-bot sequence metadata.
- Permission filtering.
- Aggregated automation and plugin events.

This avoids opening hundreds of streams for a large fleet.

## Rich domain state

### Player state

Add:

- Position and rotation.
- Velocity.
- On-ground state.
- Pose.
- Health and maximum health.
- Food, saturation, and exhaustion.
- Air and maximum air.
- Fire and freezing state.
- Experience.
- Game mode and abilities.
- Selected hotbar slot.
- Held and offhand items.
- Armor and equipment.
- Status effects and attributes.
- Sleeping and item-use state.
- Vehicle.
- Spawn point.
- Death and last-damage information.

### Entity state

Add:

- Network ID.
- Stable entity UUID when available.
- Entity type.
- Position and velocity.
- Rotation and head rotation.
- Bounding box.
- Pose and on-ground state.
- Display and player name.
- Health and maximum health.
- Equipment.
- Effects and attributes.
- Vehicle and passengers.
- Owner, target, and tame state when relevant.
- Item stack for dropped-item entities.
- Age and metadata fields exposed through stable domain concepts.

Entity references must include a connection epoch because network entity IDs can be reused after reconnects.

### Item stacks

The current item model is too shallow for automation. Add:

- Item ID.
- Count.
- Maximum stack size.
- Damage and maximum durability.
- Custom name and lore.
- Enchantments.
- Food properties.
- Tool class and mining properties.
- Armor properties.
- Potion contents.
- Components or structured NBT.
- A stable fingerprint for equality and caching.

Presentation data such as rendered icons should remain optional. Semantic state streams should not carry large image payloads by default.

### Blocks and world state

Add:

- Block ID.
- State properties.
- Block entity data.
- Biome.
- Sky and block light.
- Hardness.
- Diggability.
- Required and effective tools.
- Collision and interaction shapes where practical.
- Fluid state.
- Replaceability.
- Loaded-chunk revision.

## World and entity queries

The protocol should use structured selectors rather than requiring a remote callback.

```ts
const logs = await bot.world.findBlocks({
  origin: bot.state.position,
  radius: 96,
  selector: {
    anyOf: [
      { tag: "minecraft:logs" },
      { blockId: "minecraft:bamboo" },
    ],
    properties: { axis: "y" },
    diggable: true,
  },
  limit: 32,
  sort: "nearest",
});
```

### Block selector capabilities

- Exact IDs.
- Tags.
- State properties.
- Solid, replaceable, interactive, or diggable state.
- Required tool.
- Biome.
- Light range.
- Distance and bounding region.
- Line of sight.
- Loaded-only behavior.
- Optional block entity fields.

### Entity selector capabilities

- Exact types.
- Entity tags and categories.
- UUID or network ID.
- Player name.
- Hostile, passive, player, projectile, vehicle, or dropped-item categories.
- Alive state.
- Health range.
- Distance.
- Line of sight.
- Custom name.
- Equipment.
- Effects.
- Ownership.

### Query execution

World queries should not scan every block in a large cube on the bot game thread. Soulfire should maintain indexed loaded-chunk data or use the world-memory layer for repeated searches.

Large responses need:

- Result limits.
- Stable sort order.
- Pagination or continuation tokens.
- Optional fields.
- Server-defined maximums surfaced through capabilities.

## Registry and Minecraft data

Create a dedicated registry API rather than attaching registry lookup to the visual script service.

The registry API should expose version-specific:

- Blocks and block states.
- Items.
- Entities.
- Biomes.
- Dimensions.
- Recipes.
- Tags.
- Enchantments.
- Effects.
- Attributes.
- Game events.
- Sound identifiers.
- Particles.
- Tool effectiveness.
- Food properties.
- Container types.
- Protocol feature flags.

SDK usage:

```ts
const registry = await bot.registry.get();

const diamondPickaxe = registry.items.get("minecraft:diamond_pickaxe");
const logs = registry.tags.blocks.get("minecraft:logs");
const recipes = registry.recipes.forResult("minecraft:chest");
```

Registry results should be cached by:

- Soulfire version.
- Minecraft version.
- Target protocol version.
- Registry hash.

## Chat and message APIs

The core protocol should distinguish:

- Public chat.
- Commands.
- Whispers.
- System messages.
- Action bar messages.
- Titles.
- Signed or verified player messages.

Add:

- `SendChat`
- `SendCommand`
- `Whisper`
- `TabComplete`
- `AcknowledgeMessage` if required by the target protocol

SDK helpers:

```ts
await bot.chat.send("Hello");
await bot.chat.command("list");
await bot.chat.whisper("Alex", "Ready");

const response = await bot.chat.waitFor({
  source: "player",
  senderName: "Alex",
  pattern: /^come(?: here)?$/,
  timeoutMs: 30_000,
});
```

Python offers equivalent effect-based helpers and scoped pull streams. Event handlers return effects and remain owned by the stream consumer.

## Player actions

The high-level SDK should cover:

- Look and look-at.
- Set and clear control states.
- Dig and cancel digging.
- Place a block or placeable entity.
- Activate a block.
- Activate an entity.
- Interact at a precise entity position.
- Attack.
- Swing.
- Use, hold, and release an item.
- Consume.
- Fish.
- Sleep and wake.
- Mount and dismount.
- Move a vehicle.
- Respawn.
- Update a sign.
- Write a book.
- Respond to a resource pack.
- Creative inventory and flight operations when supported.

Every operation should:

- Validate dimensions.
- Validate reach and line of sight where appropriate.
- Return a typed result.
- Expose a stable action ID.
- Support cancellation when the action has a meaningful duration.
- Use consistent gRPC status and SDK error semantics.

## Task architecture

### Core task service

Add:

```proto
service BotTaskService {
  rpc StartBotTask(StartBotTaskRequest) returns (BotTask);
  rpc GetBotTask(GetBotTaskRequest) returns (BotTask);
  rpc ListBotTasks(ListBotTasksRequest) returns (ListBotTasksResponse);
  rpc WatchBotTask(WatchBotTaskRequest) returns (stream BotTaskEvent);
  rpc WatchBotTasks(WatchBotTasksRequest) returns (stream BotTaskEvent);
  rpc CancelBotTask(CancelBotTaskRequest) returns (BotTask);
}
```

Every task includes:

- Task UUID.
- Instance ID and bot ID.
- Task type.
- Owner identity.
- Status.
- Progress.
- Human-readable summary.
- Structured failure.
- Created, started, updated, and completed timestamps.
- Deadline.
- Claimed resources.
- Parent task and child tasks.
- Causation ID.
- Optional idempotency key.
- Reconnect policy.
- Result payload.

### Task status

Use explicit states:

- Queued.
- Waiting for resources.
- Running.
- Suspended.
- Recovering.
- Completed.
- Cancelled.
- Failed.
- Timed out.

### Resource arbitration

One global control stack is not enough for composable automation. Tasks should claim resources:

- Movement.
- Rotation.
- Main hand.
- Off hand.
- Inventory.
- Container.
- Chat.
- Vehicle.
- Camera.
- Automation planner.

Each request chooses a conflict policy:

- Reject if busy.
- Queue.
- Replace conflicting work.
- Suspend and resume lower-priority work.

Independent resources may run concurrently. Sending chat should not cancel pathfinding. Looking at an entity should be able to cooperate with a combat task. Inventory mutation should not race with auto-eat.

### Task ownership and disconnects

Tasks should declare whether they:

- Cancel when the originating SDK call disconnects.
- Continue until explicitly cancelled.
- Pause while the bot is offline.
- Resume after the bot reconnects.
- Fail when the Minecraft connection changes.

The default depends on task type. An atomic task normally follows the call. A persistent automation task should survive it.

### Task SDK

```ts
const task = await bot.tasks.start({
  collectBlocks: {
    selector: { tags: ["minecraft:logs"] },
    count: 32,
  },
});

for await (const update of task.events()) {
  console.log(update.status, update.progress);
}

await task.cancel();
```

Python:

```python
task = yield from bot.tasks.collect_blocks(
    block_ids=(), tags=("minecraft:logs",),
    count=32,
)

yield from task.events().run_for_each(lambda update: sync(lambda: print(update)))
```

## Pathfinder v2

The pathfinder should become a first-class SDK subsystem.

### Goals

Support:

- Exact block position.
- Near position.
- XZ position.
- Y level.
- Follow entity.
- Reach interaction range of a block.
- Break block.
- Place block.
- Flee from a position or entity.
- Composite any.
- Composite all.
- Inverted goal.
- Safe exploration.
- Custom plugin-defined goal.

### Movement policy

Expose:

- Mining and placement permissions.
- Block break and placement costs.
- Tool and scaffold selection.
- Maximum fall distance.
- Maximum jump height.
- Sprinting.
- Parkour.
- Swimming.
- Door and gate interaction.
- Liquid avoidance.
- Blocks to avoid.
- Entities to avoid.
- Hazard penalties.
- Damage budget.
- Entity collision avoidance.
- Path recalculation interval.
- Search timeout and radius.
- Stuck timeout.
- Partial path behavior.

### Planning and debugging

Add a read-only `PlanPath` API that returns:

- Path nodes.
- Estimated cost.
- Estimated duration.
- Blocks to break.
- Blocks to place.
- Required tools and materials.
- Hazards.
- Partial-path reason.
- Planner trace when requested.

SDK:

```ts
const goal = goals.compositeAny([
  goals.near(villageCenter, 8),
  goals.follow(villager, 3),
]);

const plan = await bot.pathfinder.plan(goal, movements.safe());
const task = await bot.pathfinder.goto(goal, movements.safe());
```

### Correctness requirements

- Reject cross-dimension coordinates unless a portal-aware goal explicitly supports them.
- Associate cancellation with a task ID rather than cancelling every path for the bot.
- Recalculate dynamic goals without losing task identity.
- Emit typed failure reasons.
- Support server-side recovery from temporary obstructions.

## Inventory, equipment, and containers

Raw inventory clicks remain available, but most users should use semantic operations.

### Inventory API

Add:

- List and count items by selector.
- Find a slot.
- Move a stack.
- Split or merge stacks.
- Toss one item, a count, or a stack.
- Equip and unequip.
- Select hotbar by slot or item selector.
- Find the best tool, weapon, armor, food, or scaffold.
- Normalize a loadout.

### Container handles

```ts
const chest = await bot.containers.openBlock(chestPosition);

try {
  await chest.deposit({
    selector: { itemId: "minecraft:cobblestone" },
    count: 64,
  });

  await chest.withdraw({
    selector: { tag: "minecraft:foods" },
    count: 16,
  });
} finally {
  await chest.close();
}
```

Support:

- Chests and generic storage.
- Furnaces, smokers, and blast furnaces.
- Crafting tables.
- Brewing stands.
- Enchanting tables.
- Anvils and smithing tables.
- Villager trading.
- Beacons.
- Stonecutters.
- Looms.
- Lecterns and books.
- Custom server containers through generic layouts and plugin helpers.

### Transaction safety

Every container snapshot should include:

- Container ID.
- State or revision ID.
- Layout.
- Slots.
- Cursor stack.
- Properties.

Mutations should provide an expected revision. The server should return a stale-state error rather than performing a click sequence against an unexpected menu.

High-level transfer, deposit, withdraw, craft, smelt, and trade operations should execute atomically from the SDK user's perspective.

## Recipes and production

Add:

- Query recipes by result.
- Query recipes by ingredient.
- Check whether the bot can craft a recipe.
- Explain missing ingredients.
- Craft a recipe a specified number of times.
- Choose a crafting station.
- Smelt or cook items.
- Select fuel.
- Brew potions.
- Execute villager trades.
- Support recipe tags and substitutions.

The production API should use Minecraft's loaded recipe data instead of a small hardcoded recipe list.

```ts
const options = await bot.recipes.forResult("minecraft:iron_pickaxe");
const assessment = await bot.recipes.canCraft(options[0], { count: 1 });

if (!assessment.canCraft) {
  console.log(assessment.missingIngredients);
}

await bot.recipes.craft(options[0], { count: 1 });
```

## Equipment and tool selection

Provide first-party equivalents for mineflayer-tool and armor manager behavior.

Tool selection should consider:

- Whether the tool can harvest the block.
- Mining speed.
- Enchantments.
- Durability.
- Preservation policy.
- Silk Touch or Fortune preference.
- Hotbar movement cost.

Combat equipment should consider:

- Target type.
- Attack damage and speed.
- Enchantments.
- Shield availability.
- Armor protection.
- Durability.
- Ranged weapon and ammunition.

## Collection

The current collect helper is not sufficient as a final collection API. A complete task should:

- Search continuously rather than once.
- Select the best tool.
- Navigate to a reachable target.
- Mine the target.
- Observe dropped items.
- Collect the drops.
- Confirm inventory count.
- Retry or blacklist failed targets.
- Handle a full inventory.
- Optionally deposit items in configured containers.
- Support task queues.
- Support veins and connected blocks.
- Respect other bots' claims.

```ts
const task = await bot.tasks.collectBlocks({
  selector: { tags: ["minecraft:logs"] },
  count: 64,
  veinMode: "connected",
  depositWhenFull: {
    containers: [storageChest],
    keep: [{ tag: "minecraft:axes", count: 1 }],
  },
});
```

## Combat

Add managed combat tasks rather than requiring applications to send one attack at a time.

### Melee

- Chase and maintain range.
- Select a weapon.
- Respect attack cooldown.
- Sprint hit, critical hit, and knockback policies.
- Shield timing.
- Strafing and spacing.
- Retreat thresholds.
- Target loss and reacquisition.
- Friendly-fire rules.

### Ranged

- Projectile trajectory.
- Target leading.
- Draw and release timing.
- Line-of-sight validation.
- Ammunition selection.
- Crossbow loading.
- Range and safety policies.

### Combat APIs

```ts
const fight = await bot.combat.attack(target, {
  style: "melee",
  shield: "automatic",
  retreatBelowHealth: 8,
});

await fight.result();
```

Also add:

- Guard an area.
- Protect an entity.
- Flee from selected entities.
- Attack nearest matching entity.
- Stop combat.
- Combat event stream.

## Survival behaviors

First-party server tasks should include:

- Auto-eat.
- Auto-armor.
- Auto-totem.
- Auto-respawn.
- Follow.
- Guard and protect.
- Sleep.
- Fish.
- Farm and replant.
- Breed animals.
- Gather food.
- Stash and withdraw.
- Maintain a loadout.
- Explore.
- Flee and recover.

Behaviors should be independently usable. They must not require the beat-game automation controller.

## Construction

Implement a real building system before presenting `build` as a high-level SDK feature.

The builder should support:

- Inline block placements.
- Schematics.
- Relative placement.
- Rotation and mirroring.
- Material substitutions.
- Scaffold planning.
- Navigation between placements.
- Breaking incorrect blocks.
- Support-block cleanup.
- Placement ordering.
- Reach and line-of-sight checks.
- Progress reporting.
- Pause, resume, and cancellation.
- Multi-bot partitioning.

```ts
const task = await bot.tasks.build({
  schematic,
  origin,
  rotation: 90,
  substitutions: {
    "#minecraft:planks": ["minecraft:spruce_planks", "minecraft:oak_planks"],
  },
});
```

## Camera and visualization

Expose:

- POV screenshots.
- Configurable camera position and rotation.
- HUD and hand toggles.
- Render distance and FOV.
- Debug traces.
- Optional frame or viewport streams.
- World-map data suitable for viewers.

SoulFireClient and third-party dashboards should consume the same camera and world APIs.

## Automation and fleet APIs

The official SDK should wrap the existing automation API:

```ts
await instance.automation.startBeat({ botIds });
await instance.automation.acquire({
  botIds,
  item: "minecraft:bread",
  count: 64,
});
await instance.automation.pause({ botIds });

for await (const event of instance.automation.events()) {
  console.log(event);
}
```

Add automation streams for:

- Goal changes.
- Phase changes.
- Current action changes.
- Progress.
- Stalls and timeouts.
- Recovery attempts.
- Deaths and respawns.
- Claim creation, release, and expiry.
- Shared-memory updates.
- Planner explanations.
- Completion and failure reports.

Fleet APIs should support:

- Start, stop, and restart selections.
- Run a task on a selection.
- Observe all task results.
- Limit concurrency.
- Select bots by state, account metadata, dimension, position, health, or capability.
- Distribute work.
- Aggregate errors.
- Apply cancellation to the group.

## Server administration through the SDK

The SDK should provide high-level wrappers for existing server features:

- Client and server information.
- Instances.
- Accounts and authentication.
- Proxies.
- Settings.
- Users and permissions.
- Logs.
- Metrics.
- Scripts.
- Commands and completion.
- Downloads.
- Plugin metrics.
- Audit logs.

Generated clients remain available for uncommon fields and new methods.

## Plugin platform

### Why plugin RPCs are required

A closed SDK cannot replace an ecosystem-driven bot library. Server plugins need a supported way to add capabilities that SDK applications can discover and call.

Examples:

- A SkyBlock plugin exposes island state and island-building tasks.
- A captcha plugin exposes captcha images and solver status.
- A server-specific plugin exposes authentication, queue, shop, or teleport APIs.
- A custom planner exposes a new goal type.
- A logistics plugin exposes warehouses and delivery tasks.

### Explicit plugin lifecycle

Replace constructor-time registration with:

```java
public abstract class ExternalPlugin {
  public abstract PluginInfo pluginInfo();

  public void onLoad(PluginContext context) {}

  public void onEnable(ServerContext context) {}

  public void onDisable() {}
}
```

`onLoad` runs during a controlled registration phase. It may register:

- RPC services.
- Permissions.
- Task providers.
- Event types.
- Settings.
- Commands.
- Registry contributions.
- Automation strategies.
- MCP tools.

`onEnable` receives live server services. It starts runtime work.

`onDisable` cancels plugin resources and unregisters runtime listeners.

Initial implementation may require a restart to add or remove RPC services. Hot loading and unloading can follow after service lifecycle semantics are defined.

### Plugin context

```java
public interface PluginContext {
  PluginInfo pluginInfo();

  PluginRpcRegistry rpc();

  PluginPermissionRegistry permissions();

  BotTaskProviderRegistry tasks();

  PluginEventRegistry events();

  AutomationExtensionRegistry automation();

  SettingsPageRegistry settings();

  CommandRegistry commands();

  PluginSdkMetadataRegistry sdk();
}
```

Runtime RPC handlers receive a typed call context with:

- Authenticated user.
- Global, instance, and bot scope.
- Permission checks.
- Instance and bot resolvers.
- Game-thread scheduler.
- Task service.
- Audit logger.
- Cancellation signal.
- Request metadata.

Plugins should not need to parse authentication headers or recreate core permission checks.

## Plugin-defined RPCs

### Registration API

```java
public final class SkyBlockPlugin extends ExternalPlugin {
  @Override
  public void onLoad(PluginContext context) {
    context.permissions().register(
      PluginPermission.instance(
        "read_island",
        "Read island state",
        PermissionRisk.READ
      )
    );

    context.rpc().register(
      PluginRpcService.builder(new SkyBlockService(this))
        .permissionResolver(SkyBlockPermissions::permissionFor)
        .build()
    );
  }
}
```

The registry must:

- Reject duplicate service names.
- Reject core namespace use.
- Validate protobuf descriptors.
- Validate permission metadata.
- Collect services before the RPC server is built.
- Add services to reflection and API docs.
- Attach core authentication, metrics, audit, and rate-limit interceptors.
- Record the owning plugin.

### Proto naming

Plugins use:

```proto
package soulfire.plugin.skyblock.v1;
```

Rules:

- Package prefix: `soulfire.plugin`.
- Normalized plugin ID.
- Explicit API major version.
- Unique service and message names.
- Core messages may be imported from published Soulfire protocol modules.
- Plugins may not redefine core packages.

HTTP bindings should use:

```text
/v1/plugins/<plugin-id>/...
```

### Supported RPC shapes

The first stable plugin API must support:

- Unary RPCs.
- Server-streaming RPCs.

These work across browser gRPC-Web, TypeScript runtimes, and Python.

Client-streaming and bidirectional streaming require a transport decision. They can be added through native gRPC or a WebSocket/Connect transport later, without blocking the first plugin API.

## Plugin catalog and discovery

Add:

```proto
service PluginApiService {
  rpc ListPluginApis(ListPluginApisRequest) returns (ListPluginApisResponse);
  rpc GetPluginApi(GetPluginApiRequest) returns (GetPluginApiResponse);
  rpc GetPluginDescriptorSet(GetPluginDescriptorSetRequest)
      returns (GetPluginDescriptorSetResponse);
  rpc WatchPluginApis(WatchPluginApisRequest)
      returns (stream PluginApiEvent);
}
```

Each catalog entry includes:

- Plugin ID.
- Plugin version.
- Description and author.
- Required Soulfire version range.
- Plugin API major version.
- Descriptor set hash.
- Services and methods.
- Permissions.
- Event and task type URLs.
- Stability status.
- TypeScript package.
- Python package.
- Maven artifact when relevant.
- Documentation URL.
- Source URL.

The SDK caches descriptors by server identity, plugin ID, version, and hash.

## Plugin SDK consumption

### Type-safe companion module

Preferred:

```ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk";
import { skyBlockPlugin } from "@example/soulfire-skyblock";

const readIslandState = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect(options);
    const skyblock = yield* soulfire.plugins.require(
      skyBlockPlugin,
      "^2.0.0",
    );

    return yield* skyblock
      .instance(instanceId)
      .bot(botId)
      .getIslandState();
  }),
);
```

The companion package:

- Depends on the official SDK.
- Includes generated protobuf descriptors.
- Exposes ergonomic wrappers.
- Declares compatible plugin and Soulfire versions.
- Registers event and task decoders.
- Fails clearly when the server plugin is missing or incompatible.

### Generated service descriptor

Advanced users can call a generated service directly:

```ts
const getIslandState = Effect.gen(function* () {
  const client = yield* soulfire.plugins.service(
    "skyblock",
    SkyBlockService,
  );

  return yield* client.getIslandState({ instanceId, botId });
});
```

### Reflective invocation

Unknown plugins remain callable:

```ts
const callUnknownPlugin = Effect.gen(function* () {
  const plugin = yield* soulfire.plugins.get("skyblock");

  return yield* plugin.call(
    "soulfire.plugin.skyblock.v1.SkyBlockService",
    "GetIslandState",
    { instanceId, botId },
  );
});
```

The runtime must validate input and output with the plugin's descriptor set. It should return a reflective message wrapper with JSON conversion and field metadata.

### Code-generation CLI

Provide:

```bash
soulfire-sdk generate \
  --server https://soulfire.example.com \
  --plugin skyblock \
  --language typescript
```

Also support:

```bash
soulfire-sdk generate \
  --descriptor plugin-api.binpb \
  --language python
```

The CLI should:

- Download descriptors.
- Verify descriptor hashes.
- Generate protobuf clients.
- Generate plugin metadata.
- Create an ergonomic module scaffold.
- Add compatibility checks.
- Produce TypeScript or Python package configuration.

Plugin builds should be able to embed their descriptor set and generate companion packages without a running server.

## Plugin permissions

Fixed core permission enums cannot represent third-party APIs. Add dynamic permission descriptors.

```proto
message PluginPermission {
  string id = 1;
  string plugin_id = 2;
  PermissionScope scope = 3;
  PermissionRisk risk = 4;
  string display_name = 5;
  string description = 6;
}
```

Scopes:

- Global.
- Instance.
- Bot.
- Task.

Risk:

- Read.
- Control.
- Mutation.
- Destructive.

Requirements:

- Full IDs use `plugin.<plugin-id>.<permission>`.
- Core namespaces are reserved.
- Every plugin RPC declares at least one permission.
- Permission metadata appears in API docs and SoulFireClient.
- Role and user grants can include dynamic permission IDs.
- Removed plugins preserve grants as inactive records instead of silently reusing IDs.
- Audit logs include plugin, service, method, scope, targets, user, and outcome.
- MCP exposure is disabled by default.
- Destructive methods are marked explicitly.

## Plugin-defined tasks

Plugins should register task providers:

```java
context.tasks().register(
  "soulfire.plugin.skyblock.v1.BuildIslandTask",
  new BuildIslandTaskProvider()
);
```

Plugin tasks:

- Use a registered protobuf request type.
- Return a standard core task handle.
- Claim core resources.
- Emit typed plugin progress payloads.
- Participate in leases and permissions.
- Appear in fleet task streams.
- Appear in SoulFireClient.
- Produce audit events.
- Support standard cancellation.

SDK:

```ts
const task = await skyblock.bot(botId).buildIsland({
  template: "cobblestone-generator",
});

for await (const update of task.events()) {
  console.log(update.phase, update.blocksPlaced);
}
```

## Plugin events

Plugins register protobuf event types and publish them through:

- Bot event streams.
- Instance event streams.
- Task event streams.
- A plugin-specific stream when appropriate.

Every plugin event includes normal event-envelope metadata. Generated SDK modules register decoders so users receive typed values.

Reflective clients can still inspect unknown events through descriptor metadata.

## Plugin automation extensions

Plugins should be able to contribute:

- Requirement types.
- Acquisition sources.
- Recipe and substitution providers.
- Goal types.
- Planners and strategies.
- Structure hints.
- World-memory observations.
- Recovery handlers.
- Role definitions.
- Claim types.
- Automation telemetry.

Extensions need ownership, priority, compatibility, and failure isolation. A faulty plugin strategy must not crash the core coordinator.

## Plugin documentation, OpenAPI, and MCP

Registered plugin services should automatically appear in:

- gRPC reflection.
- Soulfire API docs.
- OpenAPI.
- Plugin catalog.

Plugins may opt individual safe methods into MCP:

```proto
option (soulfire.v1.api_method) = {
  display_name: "Get island state"
  permissions: "plugin.skyblock.read_island"
  scope: "instance.bot"
  expose_to_mcp: true
};
```

Mutating or destructive MCP tools require explicit action metadata and confirmation behavior.

## SDK extension modules

Server plugins and SDK modules are different concepts:

- A server plugin adds Soulfire server capability.
- An SDK module adds TypeScript or Python ergonomics.

SDK modules should be able to:

- Add typed service clients.
- Add behavior helpers.
- Add task factories.
- Register plugin event reducers.
- Register task result decoders.
- Validate plugin versions.
- Add interceptors and middleware.
- Provide state selectors.

TypeScript:

```ts
export const skyBlockPlugin = defineSoulFirePlugin({
  id: "skyblock",
  version: "2.0.0",
  install(context) {
    const service = context.service(SkyBlockService);

    return {
      instance(instanceId: string) {
        return new SkyBlockInstanceClient(
          context.soulfire,
          service,
          instanceId,
        );
      },
    };
  },
});
```

Python should support an equivalent typed module registration mechanism without requiring monkey patching.

## Transport strategy

The high-level SDK must not depend directly on one transport.

Transport interface:

- Unary calls.
- Server streams.
- Cancellation.
- Deadlines.
- Metadata.
- Authentication.
- Retry classification.

Initial implementations:

- gRPC-Web for browsers.
- gRPC-Web or Connect for JavaScript runtimes.
- gRPC-Web for current Python compatibility.

Future:

- Native gRPC for Node.js and Python.
- Client-streaming and bidirectional APIs.
- WebSocket or Connect streaming if browser use cases require it.

Plugin service clients should use the same transport abstraction as core clients.

## Low-level protocol escape hatch

Some mineflayer users depend on raw packet access. Soulfire needs an official answer so advanced applications do not hit an artificial ceiling.

Add an explicitly low-level `bot.protocol` namespace:

- Current Minecraft and negotiated protocol version.
- Feature support checks.
- Packet schema discovery.
- Filtered incoming packet stream.
- Filtered outgoing packet stream.
- Optional packet sending.
- Packet timing and sequence metadata.

Example:

```ts
for await (const packet of bot.protocol.packets({
  direction: "clientbound",
  names: ["custom_payload"],
})) {
  console.log(packet.name, packet.data);
}
```

Raw packet sending should:

- Require a dedicated dangerous permission.
- Be disabled by default.
- Validate against the negotiated packet schema.
- Enforce size and rate limits.
- Record audit events.
- Mark the API as version-dependent.

Stable applications should prefer domain APIs or a Soulfire server plugin.

## Mineflayer parity program

Maintain a machine-readable and human-readable parity matrix.

Every mineflayer API entry receives one status:

- Native Soulfire equivalent.
- Soulfire equivalent with different semantics.
- SDK convenience API needed.
- Core server task needed.
- Plugin API needed.
- Low-level protocol escape hatch.
- Intentionally unsupported, with rationale.

Keep the pinned machine-readable audit in
`docs/mineflayer-parity.json`. Validate it against the current official
Mineflayer API headings with `scripts/validate-mineflayer-parity.mjs`. Publish
the human-readable migration guide in `./soulfiremc.com` and update both
artifacts whenever the upstream API heading digest changes.

### Core state parity

Cover:

- Bot entity and player.
- Entities and players.
- World.
- Username and spawn point.
- Held item and item-use state.
- Game and dimension state.
- Weather.
- Chat settings.
- Experience.
- Health, food, saturation, and oxygen.
- Time and moon phase.
- Inventory.
- Dig target.
- Sleeping state.
- Scoreboards.
- Teams.
- Control state.

### Core event parity

Cover:

- Login, spawn, respawn, end, kick, and error.
- Chat, whisper, message, action bar, and title.
- Health, food, breath, experience, and death.
- Entity spawn, movement, update, hurt, death, equipment, effects, attach, and detach.
- Player join, update, and leave.
- Item drop and collection.
- Block updates, placement, digging progress, completion, and abort.
- Chunk load and unload.
- Movement and forced movement.
- Window open and close.
- Sleep and wake.
- Weather and time.
- Sound, notes, particles, and pistons.
- Scoreboards and teams.
- Boss bars.
- Held item changes.
- Physics ticks.

### World-query parity

Cover:

- Block at a position.
- Wait for chunks.
- Block and entity under cursor.
- Visibility checks.
- Find one or many blocks.
- Diggability.
- Nearest entity.
- Recipe lookup.

### Action parity

Cover:

- Chat, whisper, command completion, and chat patterns.
- Sleep and wake.
- Movement controls.
- Look and look-at.
- Explosion damage estimation.
- Equip and unequip.
- Toss items.
- Elytra flight.
- Dig and stop digging.
- Dig time.
- Place blocks and entities.
- Activate blocks and entities.
- Consume and fish.
- Use and release items.
- Attack and swing.
- Mount, dismount, and move vehicles.
- Select hotbar.
- Craft.
- Write books and signs.
- Open containers and specialized menus.
- Trade.
- Transfer inventory.
- Creative inventory and flight.
- Resource-pack response.

### Pathfinder plugin parity

Cover:

- `goto`.
- Static and dynamic goals.
- Goal replacement.
- Stop and status.
- Path preview.
- Best harvest tool.
- Search timeout and radius.
- Movement costs.
- Block and entity avoidance.
- Parkour, sprinting, swimming, doors, digging, and placement.
- Goal events and path reset reasons.

### Popular plugin parity

Provide first-party equivalents for:

- Pathfinder.
- Collect block.
- Tool selection.
- Armor manager.
- PVP and PVE.
- Auto-eat.
- Projectile aiming.
- Builder.
- State machines and behavior trees.
- Viewer.
- Web inventory.
- GUI interaction.
- TPS and diagnostics.
- Chat authentication.
- Damage attribution.

## Behavior composition

The SDK should support client-side composition for orchestration while server tasks perform time-sensitive work.

Combinators:

- Sequence.
- Parallel.
- Race.
- Repeat.
- Retry.
- Timeout.
- Until.
- Conditional.
- Fallback.
- Cleanup.
- Scoped lease.

```ts
await behaviors.sequence(
  bot.tasks.collectBlocks({ selector: { tags: ["minecraft:logs"] }, count: 16 }),
  bot.tasks.craft({ result: "minecraft:crafting_table", count: 1 }),
  behaviors.retry(
    bot.tasks.build({ schematic: shelter }),
    { attempts: 3 },
  ),
);
```

For complex persistent behavior, provide a server-side state-machine or behavior-tree API that uses standard tasks as nodes.

## Mineflayer migration

### Idiomatic SDK first

New applications should use the official Soulfire object model. Documentation and migration guidance should lead developers directly to the native SDK so they benefit from server-side tasks, synchronized state, fleet control, permissions, and plugin-defined capabilities.

### Migration resources

Publish:

- Mineflayer-to-Soulfire API mapping.
- Popular plugin replacement guide.
- Side-by-side examples.
- Automated codemods where practical.
- Public parity dashboard.
- Known semantic differences.
- Migration case studies.

The mapping explains conceptual replacements and directs developers to
Soulfire's native object model.

## SoulFireClient dogfooding

SoulFireClient should:

- Consume the published core protocol package.
- Consume the official TypeScript SDK.
- Stop carrying independent protobuf source copies.
- Use `BotSession` for bot detail views.
- Use instance streams for fleet views.
- Use automation event streams instead of polling.
- Use task handles for progress and cancellation.
- Render installed plugin APIs and permissions.
- Offer generic plugin RPC inspection for administrators.
- Allow plugins to contribute safe UI metadata in a later phase.

Dogfooding ensures that:

- Browser transport remains supported.
- SDK state synchronization is production-tested.
- Plugin discovery has a first-party consumer.
- Authentication and permission errors remain understandable.
- Public APIs are sufficient for real user interfaces.

## Protocol ownership and compatibility

### One source of truth

Core protobuf sources live in Soulfire and are published as a versioned protocol artifact.

Consumers should:

- Depend on a pinned Buf module or protocol package.
- Never copy protocol files manually.
- Generate language bindings from the published source.

Suggested packages:

- `@soulfiremc/protocol`
- `@soulfiremc/sdk` with the canonical Effect API
- `@soulfiremc/sdk-node`
- Python `soulfire`
- `soulfire-sdk` CLI

Separating protocol and local-server installation dependencies keeps browser bundles focused.

### Compatibility rules

- Run `buf breaking` against the latest stable release.
- Never reuse protobuf field numbers.
- Reserve removed fields and enum values.
- Add fields rather than changing meanings.
- Use explicit API major packages for breaking changes.
- Publish server and SDK compatibility ranges.
- Test older supported SDKs against newer servers.
- Test newer SDKs against the oldest supported server.
- Apply the same rules to plugin descriptors.

## Error model

Define typed SDK errors:

- `SoulFireConnectionError`
- `SoulFireAuthenticationError`
- `SoulFirePermissionError`
- `SoulFireCompatibilityError`
- `BotOfflineError`
- `BotNotSpawnedError`
- `UnsupportedFeatureError`
- `TargetNotLoadedError`
- `TargetOutOfRangeError`
- `TaskConflictError`
- `TaskCancelledError`
- `TaskTimeoutError`
- `LeaseUnavailableError`
- `StaleContainerError`
- `PluginNotInstalledError`
- `PluginVersionMismatchError`
- `PluginRpcError`

Errors should include:

- Stable code.
- Human-readable message.
- Request or action ID.
- Task ID when relevant.
- Instance and bot ID.
- Retry classification.
- Structured details.
- Original transport error.

## Idempotency and retries

Mutating APIs should accept an idempotency key when retrying could duplicate work:

- Start task.
- Send chat or command.
- Start automation.
- Transfer inventory.
- Craft.
- Plugin-defined mutations.

The SDK should automatically retry:

- Safe reads.
- Resumable streams.
- Explicitly idempotent writes.

It should not retry arbitrary mutations without an idempotency key.

## Performance and backpressure

The platform must remain efficient with large fleets.

Requirements:

- One instance stream instead of one stream per bot when appropriate.
- Server-side filtering.
- Event sequence and resync support.
- Bounded event buffers.
- Explicit dropped-event behavior.
- Indexed world queries.
- Optional fields for expensive data.
- No rendered icons in semantic inventory streams by default.
- Task progress rate limits.
- Plugin RPC quotas.
- Plugin event quotas.
- Per-user and per-instance concurrency limits.
- Metrics for stream lag, task queues, RPC latency, query cost, and plugin load.

## Security model

Security requirements:

- Separate read, lifecycle, action, task, automation, settings, and raw protocol permissions.
- Dynamic plugin permissions.
- Scope every operation to global, instance, bot, or task.
- Enforce control leases consistently.
- Validate dimensions, reach, state revisions, and target identity.
- Limit message sizes and stream counts.
- Rate-limit expensive world queries.
- Rate-limit raw packet access.
- Audit all control and destructive actions.
- Prevent plugin namespace spoofing.
- Isolate plugin failures.
- Expose plugin risk metadata to SoulFireClient and MCP.

## Testing strategy

### Protocol tests

- Buf lint and breaking checks.
- Descriptor registration tests.
- Plugin namespace validation.
- Permission annotation validation.
- Reflection tests.
- HTTP transcoding tests.
- Unknown-field compatibility tests.

### Server task tests

- Resource arbitration.
- Queue, reject, replace, suspend, and resume policies.
- Cancellation.
- Deadlines.
- Reconnect behavior.
- Parent and child tasks.
- Plugin task failure isolation.

### SDK conformance

Use a shared capability suite for TypeScript and Python:

- Connection and compatibility.
- Authentication.
- Lifecycle.
- State synchronization.
- Event resumption.
- Cancellation.
- Error mapping.
- Tasks.
- Inventory and containers.
- Pathfinding.
- Plugin discovery and invocation.

Each SDK runs the same scenarios against a real local Soulfire server.

### Minecraft integration tests

Create deterministic fixtures for:

- Chat and commands.
- Blocks and entities.
- Inventory and containers.
- Crafting and smelting.
- Pathfinding.
- Collection.
- Combat.
- Farming.
- Construction.
- Death and respawn.
- Reconnects.
- Multiple protocol versions.

### Scale tests

- Ten active behavior-heavy bots.
- Hundreds of observed bots.
- One fleet stream.
- Many plugin event sources.
- Concurrent world queries.
- Long-running tasks.
- Slow consumers and reconnects.

## Documentation plan

The `soulfiremc.com` repository is the canonical home for the public SDK documentation. In the shared projects workspace this repository is checked out at `./soulfiremc.com`, and its existing SDK section lives under `content/docs/(main)/sdk/`.

Expand that section using the Diátaxis structure below. Documentation work is part of each feature, not a release-end cleanup task. A public SDK capability is incomplete until its user-facing documentation is available on `soulfiremc.com`.

### Website integration

- Keep the SDK landing page and navigation in `content/docs/(main)/sdk/`.
- Publish TypeScript and Python guides beside shared conceptual documentation.
- Generate API reference pages from the released SDK and protocol sources where practical.
- Document core APIs and plugin-defined RPC authoring in the same documentation hierarchy.
- Add runnable examples that are checked against supported SDK releases.
- Validate internal links, code samples, navigation metadata, and production builds in CI.
- Version documentation when a stable SDK major requires materially different guidance.
- Link each SDK package release to its matching `soulfiremc.com` documentation.

### Tutorials

- Build your first Soulfire bot with Effect.
- Run a scoped Soulfire workflow from an async host.
- Build your first effect-py Python bot.
- Run a complete Python workflow from a synchronous script.
- Build a chat bot.
- Collect and craft an item.
- Build a structure.
- Control a fleet.
- Create a plugin RPC and call it from TypeScript.
- Create a plugin RPC and call it from Python.
- Migrate a basic mineflayer bot.

### How-to guides

- Provide Soulfire through an Effect `Layer`.
- Run Soulfire Effects from a Promise application.
- Convert Soulfire streams to async iterables and readable streams.
- Use scoped Python fibers for concurrent bot work.
- Resume event streams.
- Cancel and replace tasks.
- Coordinate multiple SDK controllers.
- Generate a plugin SDK.
- Publish a plugin companion package.
- Add a plugin permission.
- Debug a failed path.
- Handle reconnects.
- Use raw protocol access safely.

### Reference

- Complete TypeScript API.
- Complete Python API.
- Core protobuf API.
- Task types and status.
- Event types.
- Error codes.
- Plugin lifecycle.
- Plugin RPC annotations.
- Permission scopes and risks.
- Capability flags.
- Mineflayer parity matrix.

### Explanation

- Why the TypeScript SDK is Effect-first.
- Effect scopes, interruption, and server task cancellation.
- Promise interoperability through `ManagedRuntime`.
- The Python version and concurrency policy.
- Observation, action, task, and automation layers.
- Remote task execution.
- Resource arbitration.
- State synchronization.
- Plugin API versioning.
- Protocol compatibility.
- Security and audit model.
- Mineflayer migration semantics.

## Delivery phases

### Phase 0: API governance and protocol ownership

Deliver:

- Stable API compatibility version.
- Core capability handshake.
- Published protocol artifact.
- Buf breaking checks.
- Removal of copied protobuf sources from SoulFireClient.
- Shared TypeScript and Python capability checklist.
- Typed error design.
- Effect-first TypeScript architecture decision.
- Host and transport interoperability boundaries.
- Newest-stable-Python version policy.
- One effect-py Python client architecture.
- Language design acceptance programs.

Exit criteria:

- Core protocol has one source of truth.
- SDK and server compatibility is checked at connection time.
- A protocol change cannot merge without compatibility validation.
- Effect, async-host integration, and effect-py Python API shapes are approved before the high-level surface expands.

### Phase 1: Plugin RPC foundation

Deliver:

- Explicit plugin lifecycle.
- `PluginContext`.
- Plugin RPC registry.
- Dynamic permission descriptors.
- Plugin catalog.
- Descriptor-set download.
- Reflection, OpenAPI, docs, metrics, and audit integration.
- End-to-end example plugin with one unary and one server stream.

Exit criteria:

- An external plugin can register a typed RPC.
- TypeScript and Python can call it.
- Permission checks and audit events are automatic.

### Phase 2: SDK runtime foundation

Deliver:

- Transport abstraction.
- Async connection handshake.
- `BotSession`.
- Resumable event streams.
- Domain models.
- Automatic reconnect.
- Typed errors.
- Cancellation and cleanup.
- Effect services, layers, scopes, streams, schedules, and tagged errors.
- Async-host integration through one scoped Effect workflow.
- One modern effect-py Python client.
- TypeScript and Python parity tests.
- Effect and Promise interoperability tests.
- Strict Python type checking.

Exit criteria:

- A user can build a stateful event-driven bot without handling protobuf deltas.
- SoulFireClient uses the same session implementation.

### Phase 3: Task system and pathfinder

Deliver:

- `BotTaskService`.
- Task handles.
- Resource arbitration.
- Task persistence policies.
- Pathfinder v2 goals and movement policy.
- Path planning and debugging.
- Plugin task registration.

Exit criteria:

- Concurrent chat, movement, look, and inventory behavior no longer cancel unrelated work.
- Core and plugin tasks share one lifecycle.

### Phase 4: World, registry, inventory, and recipes

Deliver:

- Registry service.
- Rich item, block, and entity models.
- Structured world queries.
- Indexed search.
- Semantic inventory operations.
- Container revisions.
- Deposit and withdraw.
- Recipe lookup.
- Crafting, smelting, and trading.
- Tool and equipment selection.

Exit criteria:

- Common mineflayer inventory and production programs can be written using only high-level Soulfire APIs.

### Phase 5: First-party behavior suite

Deliver:

- Collect blocks and items.
- Auto-eat.
- Auto-armor and auto-totem.
- Follow, guard, and protect.
- Managed melee and ranged combat.
- Farming and replanting.
- Fishing and sleeping.
- Builder and excavation.
- Behavior combinators.

Exit criteria:

- First-party equivalents exist for the most widely used mineflayer plugins.
- Behaviors are observable, cancellable, and safe under reconnects.

### Phase 6: Plugin SDK ecosystem

Deliver:

- `soulfire-sdk` CLI.
- TypeScript plugin SDK generator.
- Python plugin SDK generator.
- Effect clients generated from one plugin operation model.
- Effect-based Python clients generated from one plugin operation model.
- Reflective invocation.
- SDK module registration.
- Plugin event and task decoders.
- Package templates.
- Example publication workflow.

Exit criteria:

- A plugin author can publish a server jar and companion SDK packages through a documented workflow.
- Applications can still inspect and call an unknown plugin without its companion package.

### Phase 7: Migration and low-level parity

Deliver:

- Maintained mineflayer parity matrix.
- Raw protocol observation.
- Permission-gated raw packet sending.
- Migration guides and codemods.
- Popular plugin replacement guides.
- Side-by-side examples.

Exit criteria:

- Existing mineflayer users have a clear adoption path.
- Advanced users have a supported escape hatch.

### Phase 8: Stable release hardening

Deliver:

- Real-server integration matrix.
- Scale and soak testing.
- Compatibility policy.
- Deprecation policy.
- Security review.
- Plugin isolation review.
- Effect dependency and platform compatibility matrix.
- Async-host cancellation and scoped cleanup tests.
- Newest-stable-Python dependency and runtime validation.
- Complete tutorial, how-to, reference, and explanation documentation published on `soulfiremc.com`.
- Generated SDK and protocol references integrated into the website build.
- Documentation code samples and links validated in CI.
- Public parity dashboard.
- Release candidates used by SoulFireClient.

Exit criteria:

- All definition-of-done requirements pass.
- No P0 parity or compatibility gaps remain.
- The official client runs on the release candidate SDK.

## Immediate implementation slices

The first concrete slices should be:

1. Implement the language design acceptance programs against small prototype APIs.
2. Select the stable Effect version and newest stable Python version after dependency smoke tests.
3. Publish one core protocol artifact and make SoulFireClient consume it.
4. Add a capability and compatibility handshake to `SoulFire.connect`.
5. Design `PluginContext`, `PluginRpcRegistry`, and dynamic permissions together.
6. Register an example plugin service before the RPC server is built.
7. Expose plugin descriptors through `PluginApiService`.
8. Generate and call the example plugin from Effect, an async TypeScript host, async Python, and sync Python.
9. Add the event envelope and resumable stream fields.
10. Build `BotSession` and migrate one SoulFireClient bot view to it.
11. Define `BotTaskService` and resource arbitration.
12. Move pathfinding onto the task system.

This order proves the extension architecture early and avoids building a large closed SDK that must later be redesigned for plugins.

## Risks and design decisions

### Dynamic services and plugin loading

Armeria builds the gRPC service at server startup. The first plugin RPC release should use a registration phase before startup. Hot service registration can wait.

### Browser transport limitations

gRPC-Web supports the required unary and server-streaming baseline. Client and bidirectional streams need a future transport extension.

### Dynamic protobuf complexity

Reflective invocation is valuable but more complex than generated packages. Type-safe generated SDK modules should ship first, followed by dynamic invocation.

### Effect release and platform stability

The SDK should use the latest stable Effect release when dependencies are frozen. Do not make a stable Soulfire SDK depend on a prerelease Effect major.

Keep the protobuf transport behind Soulfire Effect services and layers. Check Effect modules for stability, browser behavior, bundle impact, and compatibility with ConnectRPC before adoption.

The SDK keeps one Effect implementation. Async hosts use standard Effect runtime and stream adapters.

### Newest Python dependency support

Requiring the newest stable Python may expose lag in protobuf, ConnectRPC, packaging, or static-analysis tooling. Treat support from those dependencies as a release gate. Do not lower the Python requirement or add compatibility branches to work around an unready dependency.

Once the version is selected for a stable SDK major, keep that minimum stable until the next major so an ordinary minor or patch release does not unexpectedly drop working Python installations.

### API surface size

Mineflayer parity creates a large API. Keep it coherent through subsystem objects, shared selectors, shared task handles, and generated reference documentation.

### Plugin trust

Server plugins execute inside the Soulfire process and are trusted code. Permissions protect SDK callers from plugin operations, not the host from a malicious plugin. Process isolation is a separate future concern.

### Raw protocol stability

Raw packets vary by Minecraft version. Keep them in a clearly marked low-level namespace and provide schema/version metadata.

## Definition of done

Soulfire is ready to be recommended as a general mineflayer replacement when:

- The official SDK is the documented default for all core operations.
- Generated clients are an advanced fallback.
- Every mineflayer core method, property, and event has a published mapping.
- First-party pathfinder, collection, tools, armor, PVP, auto-eat, builder, state-machine, viewer, and GUI capabilities exist.
- TypeScript and Python expose equivalent feature sets.
- TypeScript is Effect-first across core services, plugins, streams, tasks, errors, resource scopes, and documentation.
- Async hosts run complete scoped workflows with `Effect.runPromise` and forward cancellation through `AbortSignal`.
- The Python package requires the newest stable CPython selected for the release and uses its useful modern APIs without older-version shims.
- The effect-py Python client passes the behavioral capability suite and strict type checking.
- `BotSession` provides reliable synchronized state.
- Instance streams scale to fleets.
- Long-running work uses server tasks.
- Task arbitration supports safe concurrency.
- World queries are indexed and structured.
- Inventory and containers have semantic, revision-safe operations.
- Recipe and production APIs are complete enough for survival automation.
- Plugins can define RPCs, tasks, events, permissions, and automation extensions.
- Plugin descriptors are discoverable.
- Plugin SDK bindings can be generated.
- Plugin companion packages feel native to the official SDK.
- Unknown plugin APIs can be called reflectively.
- Plugin services appear in reflection, docs, OpenAPI, metrics, and audit logs.
- SoulFireClient consumes the official protocol and SDK.
- A permission-gated raw protocol escape hatch exists.
- Compatibility, security, integration, and scale test suites pass.
- The documentation set covers learning, problem-solving, reference, and architecture.
- The complete documentation set is published, navigable, and validated on `soulfiremc.com`.

## Final direction

The first official Soulfire SDK should not be framed as a thin client for controlling an existing Soulfire installation.

It should be presented as the official programming environment for Minecraft automation:

- High level enough for a new developer.
- Complete enough for a mineflayer application.
- Reliable enough for unattended behavior.
- Scalable enough for fleets.
- Extensible enough for server-specific and third-party capabilities.
- Effect-native in TypeScript while remaining accessible to ordinary Promise applications.
- Native effect-py workflows on the selected modern Python baseline.
- Typed and discoverable across TypeScript and Python.
- Open-ended through plugin-defined RPCs, tasks, events, and low-level protocol access.

That is the standard the roadmap should optimize for.
