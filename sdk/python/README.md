# SoulFire Python SDK

`soulfire` is the official Python SDK for SoulFire. Its high-level API uses
[effect-py](https://github.com/dev-ankit/effect-py) for lazy operations,
typed failures, services, and scoped resources. It requires CPython 3.14 or newer.

Python and TypeScript share the same protobuf protocol and SDK concepts.
Python uses snake case and `yield from`; TypeScript uses camel case and `yield*`.

## Install

```bash
python -m pip install soulfire
```

The distribution `effect-python==0.1.0a2` provides the `effect_py` module.
The SDK pins this alpha release so runtime changes can be tested together.

## Quickstart

Use managed installation for scripts. `SoulFire.create_bot` downloads SoulFire
and Java when needed, creates an instance and offline account, starts the bot,
and waits for its initial player snapshot.

```python
import asyncio

from effect_py import EffectGen, Scope, gen, run_async, scoped, sync
from soulfire import SoulFire, SoulFireOperationError


@gen
def program() -> EffectGen[None, SoulFireOperationError, Scope]:
    bot = yield from SoulFire.create_bot(
        server="localhost:25565",
        username="Builder",
    )
    yield from bot.chat.send("Hello from SoulFire")
    yield from sync(lambda: print(bot.state.player))


asyncio.run(run_async(scoped(program).or_die()))
```

Point `server` at a Minecraft server that accepts offline accounts. The default
`auth` is `"offline"`. No SoulFire URL, token, instance ID, or bot ID is needed.
Downloads and server data stay in `.soulfire` under the working directory.
Set `installation={"directory": "...", "version": "..."}` to choose a directory
and pin a release.

SDK methods return lazy `Effect[A, E, R]` operations. Execute the complete
workflow at the application boundary. Keep bot work inside the scope: it owns
subscriptions, transports, and the managed process. For an existing SoulFire
server, cleanup stops bots started by this scope and leaves previously running
bots running. Readiness has a 30-second deadline; set `ready_timeout` in seconds
to change it.

Use `run_async_exit` to inspect a workflow's result without converting expected
errors into exceptions. `or_die()` belongs at a boundary where no recovery
remains. RPCs, fibers, streams, and deadlines require the async runtime.

## Reuse named instances and bots

For several bots, install once and get resources by name:

```python
client = yield from SoulFire.install()
instance = yield from client.get_or_create_instance("farm", server="localhost:25565")
builder = yield from instance.get_or_create_bot("Builder")
miner = yield from instance.get_or_create_bot("Miner")
```

Instance names belong to the authenticated SoulFire user. Bot names belong to
one instance. Repeated calls reuse accounts and preserve their configuration.
Conflicting addresses, usernames, or authentication methods fail explicitly.

`create_bot` uses the Minecraft server address as the instance name and the
username as the bot name. Set `instance_name` or `name` to choose other names.
Use `get_or_create_bot("Builder", start=False)` to configure an account before
starting it, then use `yield from bot.connect()` when ready.

Microsoft accounts need a device-code sign-in on their first creation:

```python
bot = yield from instance.get_or_create_bot("main-account", auth="microsoft")
```

The default handler prints the sign-in URL and code. Later calls reuse the saved
account. Supply `on_device_code` to handle the code with an Effect callback.

## Collect blocks and select items

`collect` waits for completion and cancels unfinished server work if interrupted.
Use an item or block ID, or prefix a tag with `#`. IDs default to `minecraft`.

```python
yield from bot.collect("#logs", count=8)
logs = yield from bot.inventory.count("#logs")
```

Use `bot.tasks.collect_blocks` when you need a durable task handle. Structured
selectors remain available for advanced inventory queries.

## Connect to an existing SoulFire server

```python
client = yield from SoulFire.connect(
    "https://soulfire.example.com",
    token="your-api-token",
)
instance = yield from client.get_or_create_instance("farm", server="localhost:25565")
bot = yield from instance.get_or_create_bot("Builder")
```

The handshake validates API compatibility, capabilities, and required plugins.
Named provisioning requires the `instance.provisioning.v1` server capability.
Compose these operations inside the same scope as the bot's consumers.

## Migrate from the previous API

This is a breaking change. The separate high-level sync and async classes have
been replaced with one effect-based API. Generated ConnectRPC clients still
provide direct async and synchronous transport access.

| Previous API | Effect API |
| --- | --- |
| `AsyncSoulFire`, `AsyncSoulFireBot` | `SoulFire`, `SoulFireBot` |
| `async with AsyncSoulFire.connect(...)` | `yield from SoulFire.connect(...)` inside a scope |
| `await bot.chat.send(...)` | `yield from bot.chat.send(...)` |
| `async for event in bot.events()` | `bot.events().run_for_each(handler)` |
| `async_example_plugin` | `example_plugin` |
| Async behavior callbacks | Callbacks returning `Effect` |
| Context managers for leases and containers | Scoped acquisition |

Do not run each resource acquisition in a separate runtime call. Keep a
connection, session, lease, or container and its consumers in the same scope.

## Handle typed failures

RPC failures retain their ConnectRPC code, operation, request ID, and retryable
flag. Local validation, state, timeout, compatibility, and installation failures
have separate types. Action and task failures retain their server result.
Unexpected exceptions remain defects and pass through typed recovery.

```python
from effect_py import fail, succeed
from effect_py.errors import catch_tag
from soulfire import SoulFireRpcError


operation = bot.chat.send("Ready").pipe(
    catch_tag(SoulFireRpcError)(lambda error: succeed(None) if error.retryable else fail(error))
)
```

`try` and `except` around `yield from` do not handle the effect's failure
channel. Use `catch_tag`, `catch_all`, or `exit`. Register cleanup with a scope;
do not yield cleanup effects from a generator's `finally` block.

## Provide a connection as a service

A connection layer owns its transport and negotiates once when the layer is
built. Native layers memoize shared dependencies and support test replacements.

```python
from effect_py import EffectGen, gen, layer, service
from soulfire import SoulFireOperationError, SoulFireService, connection_layer


@gen
def announce() -> EffectGen[None, SoulFireOperationError, SoulFireService]:
    client = yield from service(SoulFireService)
    yield from client.instance("instance-uuid").bot("bot-uuid").chat.send("Ready")


application = announce.pipe(
    layer.provide(connection_layer("https://soulfire.example.com", token="token"))
)
```

Operations use named tracing spans. Supply an effect-py tracer through a layer
when the host needs tracing. The SDK does not run a second runtime internally.

## Compose and cancel concurrent work

Use SDK behavior combinators or the bounded effect helper:

```python
from soulfire.concurrency import parallel


operation = parallel(
    (instance.bot(bot_id).chat.send("Ready") for bot_id in bot_ids),
    concurrency=4,
)
```

The enclosing scope owns every worker. A failure interrupts sibling work and
waits for cleanup. Effects running in fibers inherit their parent's services.

An async host can run the complete workflow in an `asyncio.Task`. Cancelling
that task interrupts the effect and awaits its finalizers. Native schedules
provide retry policies and deadlines. Python durations use seconds; RPC
`timeout_ms` options retain milliseconds.

## Consume scoped streams

`Stream[A, E, R]` is the SDK's pull stream built on native effects and scopes.
Each subscription opens its own cursor. Terminal consumers close the cursor
after completion, failure, early termination, or interruption.

```python
frames = bot.camera.frames().take(10).run_collect()
packets = bot.protocol.packets().run_for_each(
    lambda packet: sync(lambda: print(packet.name, packet.network_id))
)
```

Streams support `map`, `map_effect`, `filter`, `tap`, `take`, and `merge`.
Consumers include `run_collect`, `run_fold`, `run_head`, `run_for_each`, and
`run_drain`. Use a bounded `take` before collecting a continuous stream.
`Stream.merge` applies backpressure and closes every producer when its consumer
stops. `open` exposes a scoped cursor for advanced consumers.

## Observe synchronized state

`bot.observe()` opens one reconnecting subscription and reduces snapshots and
deltas into session state. Use it inside the same scope as its consumers:

```python
session = yield from bot.observe()
print(session.state.player)
event = yield from session.once("state_delta", timeout=10)
```

After the first event, transient RPC failures resume with the stream epoch and
sequence. Consecutive failures use exponential delays, capped at five seconds.
Receiving an event resets the delay. Terminal failures reach current and later
subscribers. Interrupted waits release their subscriptions.

Raw events remain available through `bot.events()`.

## Run durable tasks

Continuous tasks execute on the SoulFire server:

```python
task = yield from bot.tasks.auto_eat(
    ["minecraft:bread", "minecraft:cooked_beef"],
    food_level=14,
    maximum_meals=1,
)
result = yield from task.result()
print(result.meals_eaten)
```

Task handles provide IDs, snapshots, events, cancellation, and typed results.
Starting a durable task leaves it running independently of its caller.
The `run_*` methods default to cancelling their server task with the call;
closing their stream ends that call.

Tasks cover pathfinding, collection, excavation, building, crafting, smelting,
brewing, trading, following, combat, guarding, sleeping, fishing, farming,
breeding, exploration, container transfers, and loadout maintenance.
Automatic tasks cover eating, respawning, armor, and totems.

Recipe discovery and production share one API:

```python
from soulfire import ItemSelector


recipes = yield from bot.recipes.list(result_item_id="minecraft:iron_ingot")
smelting = yield from bot.recipes.smelt(
    ItemSelector(item_ids=["minecraft:raw_iron"]),
    count=8,
    fuel=ItemSelector(tags=["minecraft:coals"]),
    station=furnace_position,
)
result = yield from smelting.result()
```

## Acquire leases and containers

`bot.acquire_control()` and `bot.inventory.open()` require `Scope`. Acquisition
registers release atomically. A scope releases them on success, failure, or
interruption. Manual `release()` and `close()` are idempotent.

```python
lease = yield from bot.acquire_control(ttl_seconds=30)
container = yield from bot.inventory.open(chest_position)
yield from container.withdraw(ItemSelector(item_ids=["minecraft:bread"]), 16)
```

Container transfers carry the open menu's id and fail once another menu has
replaced it. `refresh()` then raises a typed `SoulFireContainerClosedError`. Cleanup failures remain visible as defects.

## Compose behaviors

A behavior returns a native effect and preserves its error and service types.
`define_behavior` accepts a function returning an effect. Use `@fn` for a
named generator function:

```python
from effect_py import EffectGen, fn
from soulfire import SoulFireBot, SoulFireOperationError, define_behavior


@fn("announce")
def announce(bot: SoulFireBot) -> EffectGen[None, SoulFireOperationError]:
    yield from bot.chat.send("Ready")


behavior = define_behavior(announce)
```

Combinators include `sequence`, `parallel`, `race`, `repeat`, `retry`, `timeout`,
`until`, `conditional`, `fallback`, `cleanup`, and `scoped_lease`. `race` returns
the first success and awaits losing fibers. Retry and fallback handle typed
failures; defects and interruption pass through. Retry accepts `while_` to
restrict retries. Predicates can return a boolean or an effect.

`cleanup` runs its finalizer even on interruption. Its finalizer must have all
services provided and turns cleanup failures into defects. Use `scoped_lease`
when a behavior needs exclusive bot control.

## Orchestrate a fleet

`instance.fleet` shares selectors across lifecycle changes, work distribution,
and typed task groups:

```python
from soulfire import FleetSelector, FleetTaskStartOptions
from soulfire.task_pb2 import AutoRespawnTask, AutoRespawnTaskResult


workers = FleetSelector(online=True, minimum_health=12)
group = yield from instance.fleet.start_tasks(
    workers,
    AutoRespawnTask(maximum_respawns=1),
    AutoRespawnTaskResult,
    options=FleetTaskStartOptions(concurrency=4),
)
report = yield from group.results()
```

Selectors support bot and account identity, controller state, metadata,
dimension, position, health, food, ping, capabilities, and custom predicates.
Task starts and result collection have bounded concurrency. Reports retain
successful results and typed failures per bot. Defects and interruption still
stop the workflow. Interrupted starts cancel tasks already created by the group.

## Capture cameras and world maps

```python
from soulfire import CameraRenderOptions, WorldMapOptions


image = yield from bot.camera.capture_bytes(
    CameraRenderOptions(width=1280, height=720, include_hud=False)
)
world_map = yield from bot.camera.world_map(
    WorldMapOptions(radius=128, sample_step=2, include_entities=True)
)
```

Frame streams report lost frames through `dropped_before`. World maps expose
loaded state, surface height, block, biome, light, and optional entities.

## Call plugin APIs

Typed companion modules use the same effects and scoped streams:

```python
from soulfire import example_plugin


plugin = client.plugins.require(example_plugin)
reply = yield from plugin.echo(instance_id, "hello")
```

`client.plugins.typed_events(...)` returns a stream of typed event envelopes.
The catalog can also fetch descriptors and build reflective clients. Reflective
calls validate the service, method, payload, and descriptor before sending RPCs.
Generated companion packages expose one effect-based high-level API. Their
protobuf messages and generated transport clients remain available directly.

## Administer SoulFire and inspect packets

`client.admin` covers tokens, profiles, users, permissions, settings, logs,
metrics, commands, downloads, audit records, and script lifecycle operations.
Unary operations return effects; log and script subscriptions return streams.

`bot.protocol` provides packet schemas, filtered packet observation, and raw
packet sends. Raw sends require `RAW_PROTOCOL` permission. Supply
`expected_name` and use the native protocol reported by SoulFire.

## Install a local server

`SoulFire.install(...)` returns a scoped client. It owns the downloaded server,
process, transport, and handshake. Scope exit closes the client and process.
`restart_local_server()` and `stop_local_server()` are effects.

## Use generated protocol clients

Generated ConnectRPC clients remain an advanced transport boundary:

```python
from soulfire.instance_connect import InstanceServiceClient


instances = client.service(InstanceServiceClient)
```

Call generated async clients with `await`; generated `*ClientSync` classes
provide synchronous transport calls. Application code should normally compose
the high-level effects to retain validation, task semantics, and scope cleanup.

Python applications can build progression policies from these primitives.
SoulFire's first-party beat-game runner remains the separate TypeScript package
`@soulfiremc/beat-game`.
