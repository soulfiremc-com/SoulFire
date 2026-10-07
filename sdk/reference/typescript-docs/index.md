Use this reference to connect to SoulFire, control bots, and run server tasks.
It documents the SDK source and generated protocol types for the version in the page title.
For a first bot, start with the [TypeScript tutorial](https://soulfiremc.com/docs/sdk/typescript).

## Choose an API

| Goal | Start here |
| --- | --- |
| Connect or install SoulFire | {@link index.SoulFire | SoulFire}, {@link index.SoulFireClient | SoulFireClient} |
| Provision accounts in an instance | {@link index.SoulFireInstance | SoulFireInstance} |
| Start a bot, read state, or perform actions | {@link index.SoulFireBot | SoulFireBot} |
| Run and inspect server jobs | {@link index.SoulFireTasks | SoulFireTasks}, {@link index.SoulFireTask | SoulFireTask} |
| Observe events and maintain live state | {@link index.BotSession | BotSession} |
| Query or change inventory | {@link index.SoulFireInventory | SoulFireInventory} |
| Discover and execute routes | {@link index.SoulFirePathfinder | SoulFirePathfinder} |

SDK operations return lazy Effects or Streams.
Read [connection and bot lifecycle](lifecycle.md) for setup, readiness, cleanup, and timeouts.
Read [task execution](tasks.md) for results, progress, and cancellation ownership.

## Package entry points

- {@link node} and {@link bun} provide runtime transports, managed installation, and `createBot`.
- {@link browser} connects browsers to an existing SoulFire server.
- {@link index} exposes the universal SDK object model.
- {@link platform} provides Effect HTTP transport integration.
- {@link plugin-generator} generates typed companion SDKs for plugin APIs.

Modules under `generated/` expose protobuf services and wire types.
Use them when the application needs fields outside the high-level helpers.
They remain fully documented alongside the public SDK.

The [SDK guides](https://soulfiremc.com/docs/sdk) explain workflows and game concepts.
The [recipes](https://soulfiremc.com/docs/sdk/recipes) contain complete programs.
The [Python reference](https://py.soulfiremc.com/) covers the same protocol with Python conventions.
