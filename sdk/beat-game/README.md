# `@soulfiremc/beat-game`

`@soulfiremc/beat-game` is SoulFire's Effect-first progression application. It
composes the official SDK's observations, direct actions, pathfinding,
control leases, generic tasks, and plugin APIs into checkpointed single-bot
and multi-bot runs.

SoulFire remains the remote Minecraft client. This package owns game policy.

## Install

```bash
bun add @soulfiremc/sdk @soulfiremc/beat-game effect
```

## Run one bot

```ts
import { SoulFire } from "@soulfiremc/sdk/node";
import { beatGame } from "@soulfiremc/beat-game";
import { Effect, Stream } from "effect";

const program = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect({
      baseUrl: "https://soulfire.example.com",
      token: process.env.SOULFIRE_TOKEN,
    });
    const bot = soulfire.instance(instanceId).bot(botId);
    const run = yield* beatGame(bot);

    yield* Effect.forkScoped(
      run.events.pipe(
        Stream.runForEach((event) => Effect.logInfo(event.type)),
      ),
    );

    return yield* run.awaitCompletion;
  }),
);

const result = await Effect.runPromise(program);
```

Every run uses a checkpoint store. When no store is supplied, the package
creates an isolated `InMemoryBeatGameCheckpointStore`.

## Persist a run locally

The JSON store is available from the Node-only entry point. It uses
compare-and-set revisions, per-run locks, atomic replacement, and file-system
sync before reporting a successful save.

```ts
import { JsonFileBeatGameCheckpointStore } from
  "@soulfiremc/beat-game/node";

const run = yield* beatGame(bot, {
  runId: "survival-01",
  checkpointStore: new JsonFileBeatGameCheckpointStore("./runs"),
});
```

Pass the same `runId`, bot, instance, team ID, and store after a process
restart. The runner validates and restores the checkpoint before resuming.
The checkpoint keeps the current action ID and last stable result. Durable
tasks receive deterministic idempotency keys and deadlines, so a restarted
worker can reattach instead of submitting the same task twice.

## Run a team

```ts
import { beatGameTeam } from "@soulfiremc/beat-game";

const team = yield* beatGameTeam(
  botIds.map((botId) => soulfire.instance(instanceId).bot(botId)),
  {
    teamId: "release-run",
    checkpointStore,
    coordinator,
  },
);

const results = yield* team.awaitCompletion;
```

The default coordinator assigns roles deterministically, aggregates
requirements, shares discoveries, elects a fenced leader, expires claims, and
limits concurrent End entry. Implement `BeatGameCoordinator` when runs need a
shared Redis, Postgres, or other multi-process backend.

## Customize policy

Hooks replace one policy action while keeping the normal timeout, retry,
claim, checkpoint, and control lifecycle.

```ts
const run = yield* beatGame(bot, {
  hooks: {
    fightEnderDragon: ({ driver, strategy }) =>
      customFight(driver, strategy).pipe(Effect.as(true)),
  },
});
```

A hook can call a typed plugin companion SDK. The plugin remains opt-in and
SoulFire core stays independent of the game plan.

```ts
const combatPlugin = yield* soulfire.plugins.require(combatPluginModule);

const run = yield* beatGame(bot, {
  hooks: {
    fightEnderDragon: ({ checkpoint }) =>
      combatPlugin.fightDragon(checkpoint.botId).pipe(Effect.as(true)),
  },
});
```

## Promise API

```ts
import { beatGame } from "@soulfiremc/beat-game/promise";

const run = await beatGame(bot, { checkpointStore });

for await (const event of run.events) {
  console.log(event.type);
}

const result = await run.awaitCompletion();
```

The Promise API wraps the same Effect runtime. It does not contain a second
planner.

## Reusable behavior exports

Behavior programs can be used without starting a full run:

- resource and world work: `acquire`, `collectBlocks`, `excavate`, `explore`,
  `fish`, `farm`, and `breed`;
- combat and safety: `attackEntity`, `attackNearest`, `rangedAttack`, `flee`,
  `guard`, `eatWhenNeeded`, `respawnAndRecover`, `equipBestArmor`, and
  `keepTotemEquipped`;
- inventory workflows: `craft`, `craftItem`, `smelt`, `brew`, `trade`,
  `transferContainerItems`, and `maintainLoadout`;
- progression primitives: `buildStructure`, `buildNetherPortal`,
  `castNetherPortal`, `enterPortal`, `throwEnderPearl`, `throwEyeOfEnder`,
  `triangulateStronghold`, `activateEndPortal`, and `fightEnderDragon`.

These functions use public SDK calls. Game-specific names do not correspond
to core SoulFire RPCs.

## Public modules

- `@soulfiremc/beat-game`: Effect runtime, models, behaviors, in-memory
  checkpoint store, coordinator, driver, errors, and planner functions.
- `@soulfiremc/beat-game/promise`: Promise lifecycle and async iterables.
- `@soulfiremc/beat-game/node`: crash-safe JSON checkpoint storage.

See [`docs/beat-game-architecture.md`](../../docs/beat-game-architecture.md)
for the server and application ownership boundary.
