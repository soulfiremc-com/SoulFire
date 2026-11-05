# Beat-game SDK architecture

Soulfire exposes a remote Minecraft client. It owns protocol translation,
observations, control leases, pathfinding, direct actions, and reusable
server-side tasks. It does not own a first-party progression planner.

The separate `@soulfiremc/beat-game` package turns those capabilities into a
checkpointed game plan. This keeps game policy replaceable and makes the
official SDK the same interface used by first-party applications.

## The ownership boundary

Soulfire owns work that needs immediate access to the Minecraft connection:

- ordered player, world, entity, inventory, and container state;
- path planning and execution;
- direct inputs such as looking, using an item, digging, and interacting;
- control arbitration across independent controllers;
- durable, general-purpose tasks such as collecting, crafting, building,
  smelting, attacking, and automatic equipment management;
- plugin RPCs, event streams, permissions, and plugin-defined tasks.

The TypeScript package owns decisions that only make sense for a game plan:

- phase transitions and resource requirements;
- portal strategy and stronghold triangulation;
- target selection, safety policy, and recovery;
- world memory, checkpoints, and action retry decisions;
- multi-bot roles, claims, shared discoveries, and End-entry quotas.

A reusable task is an actuator. For example, Soulfire can execute a generic
collect-blocks task close to the game loop. TypeScript decides which block to
collect, how many are needed, and the next action.

## Why Effect is canonical

The runner holds control leases, task handles, streams, timers, and child
operations at the same time. Effect scopes make their lifetime explicit.
Interruption cancels the active task, resets movement, and releases acquired
resources. Tagged errors preserve run, bot, phase, and action context.

The public API uses Effect. An async host runs one scoped workflow through
`Effect.runPromise` and supplies its `AbortSignal` at that boundary.

## Recovery model

Every run has a checkpoint store. The default in-memory store is useful for
short-lived programs. `JsonFileBeatGameCheckpointStore` provides crash-safe
local persistence, including compare-and-set revisions and per-run file
locking. Applications can implement `BeatGameCheckpointStore` for a database.

Before retrying an action whose response was lost, the runner observes the bot
again. If the world already proves that the action succeeded, it records the
stable result without repeating the input.

Multi-bot coordination follows the same rule. Claims expire, carry fencing
tokens, and belong to one bot. Shared discoveries are evidence, not commands,
and must be revalidated before irreversible work.

## Plugin integration

Removing the native planner does not remove plugin extensibility. Plugins can
still register typed RPCs, event types, permissions, and durable task
providers. A runner hook can call a generated plugin SDK or the reflective
plugin client, then continue through the normal checkpoint and retry
lifecycle.

The core server does not know which plugin calls support a strategy. That
choice stays in the application that runs `@soulfiremc/beat-game`.

## Related documentation

- [`sdk/beat-game/README.md`](../sdk/beat-game/README.md) covers installation,
  single-bot and team runs, persistence, hooks, and public modules.
- [`SOULFIRE_BEAT_GAME_SDK_PLAN.md`](../SOULFIRE_BEAT_GAME_SDK_PLAN.md) records
  the migration decisions and release acceptance criteria.
- [`sdk/typescript/README.md`](../sdk/typescript/README.md) documents the
  underlying official TypeScript SDK.
