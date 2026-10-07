# Connection and bot lifecycle

Choose the entry point for your runtime:

| Import | Use |
| --- | --- |
| `@soulfiremc/sdk/node` | Node.js connections, managed installation, and `createBot` |
| `@soulfiremc/sdk/bun` | Bun connections, managed installation, and `createBot` |
| `@soulfiremc/sdk/browser` | Browser connections to an existing SoulFire server |
| `@soulfiremc/sdk` | Universal connection API with an existing transport or fetch implementation |

`SoulFire.connect` takes a SoulFire gRPC-Web URL, such as `http://localhost:38765`.
Bot provisioning takes a Minecraft address, such as `localhost:25565`.
These addresses point to different services.

## Choose a setup method

- `SoulFire.createBot` from `/node` or `/bun` installs a local server and returns a ready bot.
- `SoulFire.install` installs once so several bots can share one managed server.
- `SoulFire.connect` connects to an existing server and checks compatibility.
- `client.instance(id).bot(id)` creates handles for existing UUIDs without a request or readiness wait.

Named provisioning reuses instances and accounts. It reports conflicting
configuration rather than silently replacing it. Instances and accounts persist
when the connection scope closes.

## Keep the workflow inside its scope

Use these imports for the examples:

```ts
import { Effect, Stream } from "effect";
import { SoulFire } from "@soulfiremc/sdk/node";
```

SDK operations are lazy. Calling a method creates an Effect; `yield*` executes it
within a workflow. Run the complete workflow at the application boundary.

```ts
await Effect.runPromise(collectWithClient);
```

The following workflow connects, provisions a ready bot, and collects 16 log blocks:

{@includeCode ../../typescript/examples/reference-lifecycle.ts#collectWithClient}

The Minecraft server must accept offline accounts for this example. Set
`SOULFIRE_TOKEN` if the SoulFire server requires authentication.

## Understand readiness and cleanup

`bot.connect()` starts a stopped bot and waits for its initial player snapshot.
It also attaches the session that supplies `bot.state`. `readyTimeoutMs` defaults
to 30,000 milliseconds and must be positive and finite.

The scope closes observation and stops a bot only if the SDK started it.
A previously running bot stays running. Managed installation also stops its
local SoulFire process when the outer scope closes. Keep all bot work inside
that scope; returning a handle from a closed scope does not keep resources alive.

`bot.start()` changes desired state without a readiness wait or cleanup ownership.
`bot.waitForOnline()` waits for live state without attaching `bot.state`.
`bot.observe()` returns a separate session unless it can reuse one already attached.
Read that session's state when you open observation separately.

## Use the correct timeout

| Limit | Unit | Controls |
| --- | --- | --- |
| `readyTimeoutMs` | Milliseconds | Bot startup and initial player snapshot |
| `startupTimeoutMs` | Milliseconds | Managed SoulFire process startup |
| `defaultTimeoutMs` or `call.timeoutMs` | Milliseconds | RPC transport timeout |
| Task `deadline` | Absolute `Date` | Server task execution |

A shorter RPC timeout can fail before the readiness deadline. A task deadline
is independent of the client request timeout.

## Handle failures at the correct boundary

Expected SDK failures use the Effect error channel. Connection failures use
`SoulFireConnectionError`; operation errors include RPC, readiness, action,
and task failures. Use `Effect.catchTag` to recover from a specific error.
`Effect.runPromise` rejects when a workflow fails without recovery.

Do not retry every action automatically. Retrying a completed mutation can repeat
its side effects. For task submission retries, use a stable idempotency key for
the same job. A new intended job needs a new key.

For exclusive action control, use `bot.acquireControlScoped()`. The scope releases
the lease, but it does not renew it. Call `lease.renew()` before expiry for longer work.

Continue with [task execution](tasks.md), the
[TypeScript tutorial](https://soulfiremc.com/docs/sdk/typescript), or
[complete SDK recipes](https://soulfiremc.com/docs/sdk/recipes).
