# Soulfire Beat-Game TypeScript SDK Plan

Status: Local implementation complete; live-fixture release gates await the two open infrastructure decisions
Audience: Soulfire, TypeScript SDK, SoulFireClient, and documentation maintainers
Document type: Architecture explanation and implementation roadmap

## Decision

Move Soulfire's entire first-party game-beating system out of the Java server and SoulFireClient. Reimplement it as an Effect-first TypeScript application layer built exclusively on the official Soulfire SDK.

Soulfire should behave like a remotely controlled Minecraft client:

- Soulfire observes the game.
- Soulfire performs direct Minecraft actions.
- Soulfire provides general-purpose Mineflayer-parity tasks.
- Soulfire arbitrates access when multiple controllers want the same bot.
- Soulfire plans and executes paths.
- The TypeScript runner decides how those capabilities serve the beat-game plan.

The existing Mineflayer-parity tasks remain first-party Soulfire capabilities. They provide reusable operations such as collecting blocks, attacking entities, crafting, smelting, farming, and automatic equipment management. The beat-game runner should use them where they fit. What moves to TypeScript is the game-specific strategy that chooses goals, connects those operations, builds portals, locates strongholds, recovers runs, and coordinates a team.

Throwing an ender pearl illustrates the boundary. The SDK selects the pearl, rotates the bot, calls the ordinary item-use primitive, and observes the projectile and player movement. Soulfire does not need a `ThrowEnderPearl` RPC or a pearl-specific task.

This plan preserves the recommendation in `SOULFIRE_SDK_IMPROVEMENT_PLAN.md` that reusable continuous behaviors may run close to the game loop. It draws the boundary above those behaviors: Soulfire may execute a general task, but it must not own the beat-game objective graph or phase planner.

## Locked implementation decisions

The following decisions are approved and must not be reopened during implementation:

1. Preserve every existing first-party Mineflayer-parity task provider. The TypeScript beat-game runner should call these providers whenever they already model a reusable operation. Removing the native beat-game planner must not remove, duplicate, or weaken generic tasks.
2. Ship the runner as the separate first-party `@soulfiremc/beat-game` package. Keep `@soulfiremc/sdk` focused on protocol, primitives, observations, pathfinding, generic tasks, and plugin integration.
3. Include multi-bot coordination in the initial release. Ship deterministic role assignment, shared requirements, expiring claims, aggregate progress, checkpoint fencing, and interfaces for application-provided durable coordination backends.
4. Remove the Java `PluginAutomationExtension` API with the native planner. Plugins must use planner-independent RPCs, events, tasks, permissions, and generated SDK packages.
5. Remove SoulFireClient's native automation UI completely. Do not ship a replacement page, disabled controls, compatibility screen, or placeholder in this migration.
6. Require checkpointing for every run. Use an automatically created in-memory checkpoint store by default, provide a local JSON store for restart recovery, and allow applications to supply durable stores.

These decisions constrain the migration. They do not authorize deleting pathfinding, direct action primitives, generic task providers, plugin RPC infrastructure, plugin event streams, or provider-independent plugin tasks.

## Goals

- Remove the native Java beat-game planner completely.
- Remove the native beat-game controls from SoulFireClient.
- Make TypeScript the canonical home of first-party beat-game strategy.
- Keep Soulfire core free of beat-game-specific policy.
- Make the beat-game implementation a demanding real-world consumer of the public SDK.
- Preserve and exercise every general Mineflayer-parity task provider.
- Expose reusable TypeScript behaviors instead of one opaque `beatGame()` black box.
- Preserve generic plugin-defined RPCs, events, and durable tasks.
- Support single-bot and multi-bot runs.
- Survive SDK process restarts, Soulfire reconnects, bot deaths, and partial actions.
- Prove the design against real Soulfire and Minecraft processes.
- Document the architecture and APIs in this repository and `./soulfiremc.com`.

## Non-goals

- Do not add a Mineflayer API bridge.
- Do not reproduce the removed Java automation service under a new protocol name.
- Do not add portal, stronghold, dragon, blaze, barter, or resource-planning RPCs.
- Do not move the planner into SoulFireClient.
- Do not make SoulFireClient a required runtime for the TypeScript runner.
- Do not require raw packet construction for normal gameplay workflows.
- Do not keep deprecated forwarding services, disabled settings pages, dummy commands, or other migration shims.
- Do not promise a first-party Python beat-game runner in this migration. Python keeps the same complete primitive surface and can gain a native runner later.

## Architectural model

### Soulfire is the puppet

Soulfire owns mechanics that require authoritative access to the connected Minecraft client:

- protocol translation across supported Minecraft versions;
- connection, authentication, and session lifecycle;
- player, world, block, entity, inventory, container, registry, and packet observations;
- ordered event streams;
- direct movement input and rotation;
- item use and release;
- block digging, placement, and interaction;
- entity attack and interaction;
- inventory and container actions;
- equipment and hotbar actions;
- chat, respawn, sleep, vehicles, and other direct Minecraft inputs;
- control leases and resource arbitration;
- path planning and path execution;
- reusable Mineflayer-parity task execution;
- plugin RPC hosting;
- provider-independent plugin task hosting.

Soulfire reports what happened. It does not decide what the event means for a game plan.

### TypeScript pulls the strings

The TypeScript SDK owns:

- goals and phase transitions;
- requirement graphs;
- resource selection and acquisition;
- crafting, smelting, brewing, and trading requirements and sequencing;
- beat-game target selection and combat policy;
- food, armor, shield, and totem policy;
- portal construction and casting;
- stronghold triangulation;
- End portal activation;
- dragon-fight strategy;
- world memory used by the run;
- retry, timeout, and replanning decisions;
- death and dropped-item recovery;
- checkpoints and restart recovery;
- multi-bot roles, claims, quotas, and coordination;
- progress events and user-facing run state.

### Reusable tasks are actuators, not the planner

General tasks remain in Soulfire when they are useful outside one game plan and benefit from tight game-loop execution. Examples include following an entity, collecting blocks, attacking, crafting, smelting, fishing, farming, building a supplied schematic, and maintaining equipment.

The TypeScript runner may compose these tasks with direct primitives. It still owns target selectors, requirement priorities, phase transitions, portal and stronghold strategy, retry policy, and team objectives.

### Pathfinding remains server-side

The SDK tells Soulfire where to go and with which path constraints. Soulfire resolves and executes the route.

Allowed pathfinding inputs include:

- a position goal;
- an XZ goal;
- a block goal;
- an entity goal;
- a placement or interaction position;
- generic traversal constraints such as mining and block placement.

Pathfinding may react locally to invalidated nodes and ordinary route execution problems. It must not select a gameplay target, decide which resource to collect, choose a portal site, or advance a beat-game phase.

## Boundary examples

| Workflow | TypeScript decides | Soulfire performs or observes |
| --- | --- | --- |
| Collect a log | Required count, selector, priority, retries | Run the generic collect task and report progress and inventory changes |
| Fight a blaze | Target selector, required drops, safety policy | Run generic attack or pathfinding tasks and report entity, damage, and inventory events |
| Build a portal | Site, frame layout, material strategy, ignition plan | Block queries, pathfinding, hotbar selection, placement, interaction, block updates |
| Cast a portal | Lava placement order, water flow plan, recovery | Bucket use, placement, block states, inventory changes |
| Throw an eye | When and where to throw, sample interpretation | Look, use item, eye entity spawn and movement |
| Fill End frames | Frame order, missing-eye count, retry policy | Find blocks, pathfind, select eye, interact, observe portal blocks |
| Craft an item | Recipe choice, dependency expansion, quantity | Run the generic craft task and report output and inventory changes |
| Enter a portal | Destination objective and readiness | Pathfind into portal, dimension-change event |

## Protocol plan

### Remove from the core protocol

- `automation.proto`.
- `AutomationService`.
- All native beat, acquire, pause, resume, stop, preset, role, memory, claim, and coordination RPCs.
- Automation-specific settings messages.
- Automation-specific audit log values.
- Automation-specific capabilities.
- Automation-specific control resources.

Removed protobuf numbers and names should be reserved where necessary.

### Keep in the core protocol

- Live bot snapshots and events.
- World, block, entity, player, inventory, container, recipe, registry, and protocol queries.
- Direct action RPCs.
- Manual movement and rotation.
- Control leases.
- Path planning and execution.
- Pathfinding progress and cancellation.
- Existing first-party Mineflayer-parity task schemas and providers.
- Generic task lifecycle, progress, cancellation, reconnect, and arbitration.
- Generic plugin RPC discovery and invocation.
- Generic plugin event streams.
- Provider-independent plugin task lifecycle APIs.

First-party parity tasks and plugin tasks remain. The migration removes only the private beat-game planner and its automation-specific service surface.

### Primitive gaps to audit

The TypeScript implementation must not fall back to raw packets for ordinary survival play. Before deleting the Java behavior code, verify or add generic primitives for:

1. Ordered inventory and container revisions after every menu action.
2. Complete menu layout metadata for player crafting, crafting tables, furnaces, brewing stands, merchants, and other menus used by the runner.
3. Recipe placement as a direct vanilla menu action.
4. Merchant offer selection as a direct vanilla menu action.
5. Container property changes such as furnace progress, fuel, and brewing time.
6. Entity spawn, movement, metadata, damage, removal, and item-pickup events.
7. Block-state and dimension-change events.
8. Item-use start, release, cooldown, and hand-state observations.
9. Health, hunger, effect, death, respawn, and sleep updates.
10. Raycast, visibility, reachability, and interaction-face queries.
11. Path execution terminal reasons and interruption.
12. Connection epochs on observations that reference connection-scoped entity IDs.

A missing primitive should describe a direct Minecraft input or observation. It must not encode game strategy.

### Latency rule

Start with normal unary actions plus state streams. Measure the real runner before adding batching.

If network latency prevents reliable timing, add only a bounded, generic primitive mechanism, such as scheduling a short input sequence against a known connection epoch. Do not add named gameplay macros. Every scheduled step must still be a direct action, have a strict duration limit, support cancellation, and report its result.

## Java removal scope

Delete the native beat-game implementation and all integration points:

- `AutomationController`
- `AutomationTeamCoordinator`
- `AutomationWorldMemory`
- `AutomationRequirements`
- `AutomationRecipes`
- `AutomationInventory`
- `AutomationControlSupport`
- `AutomationServiceImpl`
- `AutomationSettings`
- the automation command
- automation tick hooks
- automation settings registration
- automation-specific MCP tools
- automation capabilities and audit mappings
- automation tests
- automation-specific plugin extension APIs

Retain:

- the generic task manager;
- every existing first-party Mineflayer-parity task provider;
- their request, result, progress, lifecycle, reconnect, and cancellation APIs;
- plugin task registration and lifecycle;
- pathfinding providers and direct path execution;
- generic control arbitration shared by primitives, pathfinding, and plugin tasks.

The TypeScript beat-game package should use these tasks where they already express a suitable reusable operation. It should use lower-level primitives for game-specific actions such as throwing an eye, constructing a portal, filling portal frames, and interpreting world observations.

The final Java tree must not contain commented implementations, unused automation types, hidden settings, or compatibility adapters.

## TypeScript package design

### Package exports

The runner ships as a separate first-party package in the same repository:

```text
@soulfiremc/sdk
@soulfiremc/beat-game
```

`@soulfiremc/beat-game` depends on the public `@soulfiremc/sdk` package. It does not add game-specific code to the base SDK bundle.

Effect is the sole high-level API. An async host converts a complete scoped workflow with `Effect.runPromise`. The planner and its resource ownership remain in Effect.

### Public SDK boundary

The beat-game package may import only public SDK types and services. It must not import:

- generated Connect service clients directly;
- private client implementation modules;
- Java classes;
- removed automation protocol types;
- test-only server hooks.

This constraint turns the runner into a continuous conformance test for the official SDK.

### Proposed Effect API

Names can be refined during implementation, but the public concepts should look like this:

```ts
export interface BeatGameOptions {
  readonly strategy?: Partial<BeatGameStrategy>
  readonly checkpointStore?: BeatGameCheckpointStore
  readonly coordinator?: BeatGameCoordinator
  readonly runId?: string
}

export interface BeatGameRun {
  readonly id: string
  readonly snapshots: Stream.Stream<BeatGameSnapshot, BeatGameError>
  readonly events: Stream.Stream<BeatGameEvent, BeatGameError>
  readonly awaitCompletion: Effect.Effect<BeatGameResult, BeatGameError>
  readonly pause: Effect.Effect<void, BeatGameError>
  readonly resume: Effect.Effect<void, BeatGameError>
  readonly stop: Effect.Effect<void, BeatGameError>
}

export const beatGame: (
  bot: SoulFireBot,
  options?: BeatGameOptions,
) => Effect.Effect<BeatGameRun, BeatGameError, Scope.Scope>
```

### Async-host integration

Run connection setup and the beat-game run inside one `Effect.scoped`
workflow. Convert that workflow once at the host boundary:

```ts
const result = await Effect.runPromise(program, { signal: request.signal });
```

The SDK README contains a complete async-host example. Resource ownership
stays inside Effect until the workflow succeeds, fails, or is interrupted.

### Reusable behavior programs

Export the useful parts of the runner independently:

- `acquire`
- `collectBlocks`
- `excavate`
- `attackEntity`
- `attackNearest`
- `rangedAttack`
- `flee`
- `guard`
- `eatWhenNeeded`
- `respawnAndRecover`
- `equipBestArmor`
- `keepTotemEquipped`
- `fish`
- `farm`
- `breed`
- `explore`
- `transferContainerItems`
- `maintainLoadout`
- `craft`
- `smelt`
- `brew`
- `trade`
- `buildStructure`
- `buildNetherPortal`
- `castNetherPortal`
- `enterPortal`
- `throwEyeOfEnder`
- `triangulateStronghold`
- `activateEndPortal`
- `fightEnderDragon`

These are TypeScript programs composed from public SDK calls. Their names must not become Soulfire core RPCs.

### Driver boundary

Separate strategy from the real bot adapter:

```ts
export interface BeatGameDriver {
  readonly observe: Effect.Effect<BeatGameObservation, BeatGameDriverError>
  readonly events: Stream.Stream<BeatGameObservationEvent, BeatGameDriverError>
  readonly pathfind: (
    goal: PathfindGoal,
    options?: PathfindOptions,
  ) => Effect.Effect<PathfindResult, BeatGameDriverError>
  readonly act: (
    action: BeatGamePrimitiveAction,
  ) => Effect.Effect<BeatGameActionResult, BeatGameDriverError>
}
```

Every run has a checkpoint store. When the caller does not provide one, the package creates an in-memory store. The option remains optional only because the default is automatic, not because checkpointing can be disabled.

The production driver adapts `SoulFireBot`. Deterministic tests use an in-memory driver.

`BeatGamePrimitiveAction` may contain direct actions such as look, use item, interact block, place block, dig block, click slot, attack entity, or reset movement. It must never contain `buildPortal`, `locateStronghold`, `acquireBlazeRods`, or another strategic command.

The production driver also exposes the public SDK's generic task handles. Planner programs should prefer an existing parity task when it already models the reusable operation and fall back to direct primitives when the workflow is game-specific or needs additional observation between individual inputs.

## Planner design

### Explicit state machine

Use a serializable state machine:

1. `PREPARE_OVERWORLD`
2. `ENTER_NETHER`
3. `COLLECT_NETHER_RESOURCES`
4. `RETURN_TO_OVERWORLD`
5. `LOCATE_STRONGHOLD`
6. `ACTIVATE_END_PORTAL`
7. `FIGHT_ENDER_DRAGON`
8. `COMPLETE`

Every transition must be based on a fresh observation. The runner must never advance solely because the previous action returned successfully.

Each planner decision records:

- phase;
- objective;
- unsatisfied requirements;
- selected action;
- observations used as evidence;
- retry count;
- team claim, if any;
- checkpoint revision.

### Requirements

Model requirements as data with alternatives and dependencies:

- tools and tool tiers;
- blocks and building materials;
- food reserves;
- armor and weapons;
- water and lava buckets;
- obsidian and ignition;
- gold for bartering;
- blaze rods and powder;
- ender pearls and eyes;
- ranged equipment;
- configured End-fight resources.

The planner chooses acquisition strategies from current inventory, world observations, known recipes, configured risk, and team state. It should not assume one fixed speedrun route.

### World memory

World memory belongs to the TypeScript run:

- discovered resources and structures;
- inspected containers;
- portal locations;
- stronghold samples and estimates;
- death positions;
- dropped items;
- recent entity sightings;
- temporarily unreachable targets;
- team discoveries and claims.

Every entry carries:

- dimension;
- position;
- observation time;
- connection epoch when relevant;
- confidence;
- expiry or revalidation policy.

Stale observations must be revalidated before irreversible actions.

### Action lifecycle

Every action follows one shape:

1. Read the latest observation.
2. Validate preconditions.
3. Acquire the required control lease.
4. Pathfind into a valid interaction position when necessary.
5. Run a reusable task or perform one or more primitive actions.
6. Observe the resulting state.
7. Classify the outcome as success, retry, or replan.
8. Release the lease.
9. Save a checkpoint after a stable state change.

Cancellation must reset held movement, stop pathfinding, release leases, and close menus opened by the workflow when appropriate.

## Workflow design

### Resource collection

- Query matching blocks, entities, containers, or drops.
- Rank candidates in TypeScript.
- Claim a target in team mode.
- Pathfind into range.
- Select equipment using inventory observations and rankings.
- Perform the direct action.
- Confirm success from block, entity, or inventory changes.
- Expire or release the claim.
- Mark repeated failures temporarily unreachable.

### Crafting

- Resolve recipes through recipe observations.
- Expand missing dependencies in TypeScript.
- Open the required menu.
- Place the recipe through a vanilla recipe-placement action or explicit slot clicks.
- Observe the new container revision.
- Collect output.
- Confirm the inventory result.

The existing generic `CraftTask` remains in Soulfire core and is the preferred execution path. The beat-game package owns recipe choice, dependency expansion, and sequencing.

### Smelting, brewing, and trading

- Open the relevant block or entity menu.
- Observe its typed layout.
- Select a recipe or offer where vanilla has a direct selection action.
- Move inputs and fuel with generic inventory actions.
- Observe progress properties and slot changes.
- Collect output.
- Recover safely from menu closure or revision conflicts.

### Portal construction

Support two TypeScript strategies:

- build a frame from collected obsidian;
- cast a frame with lava and water.

The SDK chooses a site, validates the local block volume, pathfinds to placement positions, selects items, performs block or bucket interactions, ignites the frame, confirms portal blocks, and enters the portal through pathfinding.

No portal blueprint, portal-site selector, or portal action state machine remains in Java.

### Ender pearl use

- Select a pearl.
- Choose a look direction.
- Call the normal item-use primitive.
- Observe the pearl entity and player movement.
- Determine success or failure from observations.

This workflow exists to prove that the SDK can derive behavior from primitives without a named server action.

### Stronghold location

- Select an eye of ender.
- Record the player position and rotation.
- Use the item.
- Observe the eye entity spawn and movement.
- Derive a direction sample.
- Repeat from a useful baseline.
- Triangulate an estimated intersection.
- Pathfind to the estimate.
- Search for portal-frame blocks.

### Combat

- Observe candidates continuously.
- Select targets in TypeScript.
- Use entity-goal pathfinding for pursuit.
- Select equipment from inventory observations.
- Rotate and attack through direct actions.
- Observe movement, damage, metadata, removal, and drops.
- Apply food, retreat, and recovery policy in TypeScript.

### End fight

- Confirm the End dimension.
- Observe crystals and the dragon.
- Choose reachable crystal targets.
- Use generic ranged or melee tasks, with primitives where the fight needs finer control.
- Track dragon movement and phase metadata.
- Attack during configured windows.
- Apply healing, retreat, and recovery policy.
- Confirm completion through stable vanilla observations.

## Multi-bot coordination

Multi-bot coordination is required for the first TypeScript release. Coordination runs outside Soulfire and may span multiple Soulfire instances.

Provide:

- deterministic default role assignment;
- user-assigned roles;
- shared requirement counts;
- expiring target claims;
- shared structure and portal observations;
- controlled portal and End-entry quotas;
- per-bot and aggregate progress;
- leader election or fencing for process recovery;
- compare-and-set checkpoint writes.

Define interfaces for coordination and storage. Ship an in-memory coordinator for local runs and allow applications to provide Redis, Postgres, or another durable backend.

Soulfire core must remain unaware of beat-game roles and objectives.

## Durability and recovery

The runner must recover from:

- TypeScript process restarts;
- temporary network loss;
- Soulfire process restarts;
- bot disconnects and reconnects;
- changed connection epochs;
- death and respawn;
- dimension transitions;
- stale entity IDs;
- invalid inventory revisions;
- closed menus;
- partial portal construction;
- lost action responses;
- duplicate action attempts.

### Checkpoints

Checkpoint records need:

- schema version;
- run ID;
- bot and instance IDs;
- phase and objective;
- requirement state;
- world memory;
- team state references;
- last stable action result;
- connection epoch;
- monotonic revision;
- creation and update timestamps.

Use compare-and-set writes so two resumed workers cannot silently control the same run.

Every run uses a checkpoint store. Ship an in-memory store as the automatic default and a local JSON file store for restart recovery. Keep the interface open for application-provided durable stores.

### Idempotency

Use protocol idempotency keys when available.

Minecraft actions are often not naturally idempotent. Before retrying a timed-out action, observe the world or inventory and decide whether it already succeeded. Never treat a missing response as proof of failure.

## Control and concurrency

The TypeScript runtime uses scoped control leases:

- movement;
- rotation;
- main hand;
- off hand;
- inventory;
- container;
- vehicle;
- camera when needed.

Pathfinding and direct actions must participate in the same arbitration system. Interruption releases all resources through Effect scopes.

Parallel safety monitors, such as food, health, and threat observation, may run concurrently. Only the selected action program may mutate a leased resource.

## Error model

Use tagged Effect errors:

- `BeatGameProtocolError`
- `BeatGameObservationError`
- `BeatGameActionError`
- `BeatGamePathfindingError`
- `BeatGameRequirementError`
- `BeatGameCheckpointError`
- `BeatGameCoordinationError`
- `BeatGameCancelled`

Errors include:

- run ID;
- instance and bot IDs;
- phase;
- current action;
- retryability;
- underlying cause when available.

Async hosts receive the Effect runtime result at their execution boundary.

## Events and observability

Expose a typed, ordered event stream:

- run started, paused, resumed, stopped, and completed;
- checkpoint restored and saved;
- phase and objective changed;
- requirement discovered, claimed, and satisfied;
- action started, retried, succeeded, and failed;
- bot disconnected and recovered;
- death and dropped-item recovery;
- team role and claim changes;
- diagnostic observations.

Events include a monotonic sequence and timestamp. Consumers can render them in a terminal, web application, test reporter, or telemetry system.

Do not replace the removed automation protocol with a server-side beat-game event stream.

## Plugin integration

The removal of native automation must not weaken plugin extensibility.

Remove the Java `PluginAutomationExtension` API because it extends the native planner being deleted. Plugins should integrate through the planner-independent RPC, event, task, permission, and generated SDK mechanisms.

Keep and verify:

- typed plugin RPC registration;
- permission metadata;
- runtime service discovery;
- generated TypeScript packages;
- reflective calls for unknown plugin services;
- plugin event streams;
- plugin-defined durable tasks;
- audit records for plugin RPC calls.

The beat-game runner may accept strategy extensions that call plugin features through the normal official SDK. A plugin can intentionally provide server-side domain behavior, but it remains plugin-owned and opt-in. Soulfire core does not depend on it.

## SoulFireClient migration

Remove:

- the automation route;
- automation summaries and fleet spotlights;
- automation-specific navigation;
- automation demo data;
- automation translations;
- native start, pause, resume, stop, preset, memory, role, and claim controls;
- generated `AutomationService` usage.

Keep generic views for:

- bots and live state;
- control leases;
- plugin tasks;
- plugin events;
- metrics and logs;
- server and instance management.

Do not replace the deleted automation UI with a disabled card or placeholder.

Do not replace the removed screen in this migration. If a future SoulFireClient page observes an external TypeScript run, it must do so through a separately designed generic integration. It must not recreate the planner in React or restore a native automation service.

## Python SDK impact

Remove handwritten and generated Python bindings for the deleted automation service.

Keep:

- complete observation APIs;
- primitive actions;
- inventory and container control;
- world and registry queries;
- pathfinding;
- control leases;
- plugin RPCs, events, and tasks.

The first-party runner is TypeScript-first. A future Python runner should implement the same behavioral contract using modern Python rather than moving policy back into Soulfire.

## Test strategy

### Deterministic planner tests

Use an in-memory driver to cover:

- observation-based phase transitions;
- requirement expansion and alternatives;
- retry and replanning;
- inventory revision conflicts;
- eye-of-ender direction samples;
- partial portal recovery;
- death recovery;
- checkpoint serialization;
- process resume;
- claim expiry and reassignment;
- cancellation and lease release.

Test decisions and state transitions. Do not add tests that only assert display strings.

### Public-boundary test

Add an import-graph test that fails if the beat-game package imports:

- generated service clients;
- removed automation types;
- server internals;
- private SDK implementation modules outside the approved adapter.

### Protocol contract tests

Run the production `SoulFireBot` driver against mock Connect services and verify:

- exact task and primitive requests;
- control leases;
- connection epochs;
- event ordering;
- cancellation;
- pathfinding progress;
- inventory revisions;
- reconnect behavior.

These tests verify the adapter, not planner strategy.

### Process-level smoke test

Create a disposable environment containing:

1. A deterministic offline Minecraft server and fixture world.
2. A real Soulfire server process.
3. A real bot connection.
4. The packed official TypeScript SDK.
5. The beat-game runner in a separate Node process.

The CI smoke scenario should prove that TypeScript can:

- observe the bot;
- acquire control;
- collect a staged resource;
- craft through the generic craft task;
- build and ignite a portal fixture;
- use an item and observe the spawned entity;
- enter another dimension;
- attack a staged entity;
- restore a checkpoint after restarting the TypeScript process.

The smoke test must consume the packed SDK artifact, not source-only test helpers. It must fail if Java automation is required.

### Full fixed-seed run

Run a complete survival game on a schedule:

- fixed Minecraft version and seed;
- clean-world start;
- single-bot completion;
- multi-bot completion;
- TypeScript process restart during a long action;
- captured logs, checkpoints, event traces, and world artifacts on failure.

Track success rate and duration across repeated runs. One successful run is not sufficient release evidence.

### Boundary tests in Java

Add tests that verify:

- every Mineflayer-parity provider remains registered;
- no beat-game-specific provider or planner is registered;
- no automation service is present in the gRPC descriptor;
- no automation settings page or command is registered;
- plugin tasks and RPCs still register and execute;
- primitive actions and pathfinding still arbitrate through control leases.

## Documentation plan

Update repository documentation and `./soulfiremc.com`.

### Explanation

- Soulfire's puppet architecture.
- Why gameplay strategy runs in TypeScript.
- The boundary between a primitive, a reusable parity task, pathfinding, and beat-game policy.
- Effect-first runtime and structured concurrency.

### Tutorials

- Run the beat-game program with one bot.
- Add local checkpoint persistence.
- Run a coordinated bot team.

### How-to guides

- Compose a custom acquisition strategy.
- Replace portal construction policy.
- Add a safety monitor.
- Resume a failed run.
- Call a plugin-defined RPC from a strategy.
- Deploy the runner as a long-lived worker.

### Reference

- Beat-game public types.
- Events and errors.
- Checkpoint schema.
- Strategy configuration.
- Coordinator and store interfaces.
- Primitive requirements by workflow.

## Migration order

### Phase 1: Agree on the boundary

- Review and approve this document.
- Decide the minimum smoke-test scenario.
- Record accepted decisions in the decision log.

No implementation begins before this phase is approved.

### Phase 2: Audit and complete SDK capabilities

- Map every Java automation action to an existing parity task or public SDK primitive.
- Identify missing protocol observations and actions.
- Implement only generic gaps.
- Add protocol contract tests.
- Add capability negotiation for any new primitives.

### Phase 3: Build the TypeScript behavior layer

- Implement the Effect-first driver.
- Wrap and compose existing Mineflayer-parity tasks.
- Implement only the missing game-specific behavior programs from primitives.
- Add a scoped async-host example.
- Add deterministic tests.

### Phase 4: Build the planner

- Implement phases and requirements.
- Implement world memory.
- Implement checkpoints and recovery.
- Implement portal, stronghold, and End workflows.
- Implement single-bot completion.
- Implement multi-bot coordination.

### Phase 5: Prove the architecture

- Add the real process-level smoke environment.
- Run it against the packed SDK.
- Add scheduled fixed-seed completion tests.
- Add restart and failure injection.

### Phase 6: Remove native policy

- Delete the Java automation system.
- Preserve and verify every first-party Mineflayer-parity task provider.
- Remove the planner-specific `PluginAutomationExtension` API.
- Delete automation protocol definitions and generated bindings.
- Remove SoulFireClient automation UI.
- Remove stale settings, commands, capabilities, tests, docs, and audit values.

Do not publish an SDK version that points to removed server APIs. The removal and SDK replacement must land as one release boundary.

### Phase 7: Publish documentation

- Update repository docs.
- Add the full documentation set to `./soulfiremc.com`.
- Publish migration notes for removed native APIs.
- Link supported Minecraft versions and smoke evidence from release notes.

## Release acceptance criteria

The migration is complete only when:

- no native beat-game controller runs in Soulfire;
- no automation service, settings page, command, capability, audit value, or generated binding remains;
- every existing Mineflayer-parity task provider remains available and covered by tests;
- plugin-defined RPCs, events, and tasks still work;
- the beat-game package imports only public SDK APIs;
- the TypeScript runner uses generic task APIs where appropriate and primitives for game-specific workflows;
- portal construction, item throwing, stronghold search, phase planning, recovery, and coordination live in TypeScript;
- multi-bot coordination works in the first release;
- every run has an active checkpoint store;
- Effect is the sole high-level API and scoped host interoperability tests pass;
- deterministic planner tests pass;
- protocol contract tests pass;
- the real process-level smoke test passes;
- scheduled fixed-seed runs complete from a clean world;
- SoulFireClient contains no native automation UI or dead placeholders;
- repository and website documentation match the released architecture.

## Risks and mitigations

### Remote latency harms action timing

Use state streams, keep action calls small, colocate the TypeScript worker with Soulfire for demanding runs, and measure before adding bounded generic input scheduling.

### The SDK lacks enough observations

Treat the runner as the protocol audit. Add direct observations instead of strategic RPCs.

### Duplicate TypeScript implementations drift

Keep one Effect implementation. Use standard runtime and stream adapters at host boundaries.

### Restart recovery repeats destructive actions

Observe before retrying, use idempotency keys where possible, and checkpoint only stable state.

### Multi-bot workers race

Use expiring claims, compare-and-set checkpoints, fencing tokens, and explicit control leases.

### The runner becomes one monolith

Export reusable behaviors, keep strategy data-driven, and separate planner, driver, memory, execution, and coordination modules.

### Plugin extensibility regresses

Keep plugin RPC and task tests in the removal change. Treat plugin invocation as a release gate.

## Open decisions

These points require external fixture ownership before the live release gates
can run:

1. Which Minecraft version and fixture world should the process-level smoke
   test target first?
2. Which fixed seed and server implementation should scheduled full runs use?

The implementation does not guess these release-infrastructure choices. The
packed live-smoke worker and workflow are ready to run once maintainers provide
the environment, bot accounts, version, fixture, and seed.

## Decision log

| Decision | Status | Rationale |
| --- | --- | --- |
| Game strategy lives in TypeScript | Accepted | Soulfire should be a general remote client, not a game-policy engine |
| Soulfire retains pathfinding | Accepted | Path execution needs tight access to world state and the game tick |
| Portal construction lives in TypeScript | Accepted | It composes placement, interaction, observation, and pathfinding |
| Item throwing uses normal item actions | Accepted | A named throw RPC would encode unnecessary item-specific policy |
| Native Java beat-game system is removed | Accepted | Two planners would drift and preserve the wrong ownership boundary |
| Native SoulFireClient automation UI is removed | Accepted | SoulFireClient should not host or depend on the planner |
| Plugin-defined RPCs and tasks remain | Accepted | Extensibility is independent of first-party game policy |
| Effect is the canonical TypeScript API | Accepted | It provides scopes, interruption, typed errors, streams, and structured concurrency |
| Mineflayer-parity task providers remain | Locked | They are reusable SDK capabilities, not the beat-game planner |
| The runner uses parity tasks | Locked | Existing generic tasks should be composed instead of reimplemented |
| Package shape | Locked | Ship `@soulfiremc/beat-game` as a separate first-party package |
| Multi-bot coordination ships initially | Locked | Removing the Java coordinator must not regress team runs |
| `PluginAutomationExtension` is removed | Locked | Plugins use planner-independent RPCs, events, and tasks |
| SoulFireClient automation UI is removed | Locked | No replacement screen ships in this migration |
| Checkpointing is always active | Locked | Use an in-memory default, include a JSON store, and allow durable stores |
| Pathfinding task shape | Accepted | Use durable `GoTo` and `FollowEntity` tasks for restart-aware application work; keep `BotLiveService.GoTo` as the direct streaming primitive |
| Initial coordination backend | Accepted | Ship interfaces and the in-memory coordinator; applications provide Redis, Postgres, or another multi-process backend |
| Primitive menu audit | Accepted | The current semantic inventory, recipe, menu-layout, container revision, and generic task APIs cover the runner; no game-policy RPC was added |
| Bounded input scheduling | Deferred | Add it only if measured live latency shows that unary primitives and state streams are insufficient |
| Public behavior exports | Accepted | Export the complete reusable behavior list in this plan from `@soulfiremc/beat-game` |
| Smoke Minecraft version and fixture | Open | Select based on deterministic CI support |
| Fixed seed and server implementation | Open | Select based on repeatability, supported protocol coverage, and maintained CI images |

## Architectural guardrail

When considering a future Soulfire RPC, ask:

> Is this a direct Minecraft action or observation, a reusable general-purpose task, a pathfinding operation, or a decision in the beat-game plan?

Direct actions, observations, reusable Mineflayer-parity tasks, and pathfinding belong in Soulfire. Beat-game objectives, phase transitions, portal and stronghold strategy, recovery, and team coordination belong in `@soulfiremc/beat-game`.
