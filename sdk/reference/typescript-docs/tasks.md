# Task execution and ownership

SoulFire tasks run on the server. The SDK offers three ways to use them:

| API | Result | Cancellation ownership |
| --- | --- | --- |
| `bot.collect(...)` | Completed collection result | Cancels unfinished work on interruption |
| `bot.tasks.collectBlocks(...)` | Handle after server acceptance | Explicit cancellation through `task.cancel()` |
| `bot.tasks.runCollectBlocks(...)` | Stream of task events | Cancels unfinished work when the stream closes by default |

The same start-versus-stream distinction applies to navigation and other task
families. A start method does not wait for completion. `run*` methods start work
when their streams are consumed.

## Wait for a typed result

Use these imports for the examples:

```ts
import { Effect, Stream } from "effect";
import { SoulFire } from "@soulfiremc/sdk/node";
```

{@includeCode ../../typescript/examples/reference-lifecycle.ts#collectWithTaskHandle}

This workflow uses managed installation. Run it with
`await Effect.runPromise(collectWithTaskHandle)` and an offline-compatible Minecraft server.

`task.result()` waits for successful completion and decodes the expected protobuf
result. A failed, cancelled, or timed-out task fails with `SoulFireTaskFailed`.
The error contains the task snapshot, including status and failure details.
A missing or incompatible result also fails.

`task.wait()` returns the snapshot regardless of terminal status. Use it when
cancellation or failure is an expected outcome you want to inspect.
`task.snapshot` and `task.terminal` read cached state. `refresh()`, `wait()`, and
`cancel()` update it; direct event consumption does not.

Interrupting `task.result()` or `task.events()` closes the observer. It does not
itself cancel the server job. Call `task.cancel(reason)` explicitly, or choose
an API that owns cancellation. Server disconnect, reconnect, deadline, and
resource-conflict policies still determine whether a job can continue.

## Observe progress with ownership

{@includeCode ../../typescript/examples/reference-lifecycle.ts#collectWithProgress}

Run this workflow with `await Effect.runPromise(collectWithProgress)`.
The stream ends when the task ends. Inspect event snapshots for terminal status;
a stream ending alone does not establish successful task completion.

The default `run*` disconnect policy is `CANCEL_WITH_CALL`. Interrupting the
workflow therefore cancels unfinished remote work. An explicit policy can select
different behavior on the generic `run` method.

## Bound work and avoid duplicate submissions

A task `deadline` bounds execution on the server. A call timeout bounds the RPC.
Use a deadline when a durable job must finish within a fixed window.

`idempotencyKey` identifies one intended submission. Reuse that key when retrying
an uncertain submission. Do not reuse it for a different job. Error recovery
must account for resource conflicts, reconnect policies, and whether retrying
can repeat completed work.

For the common collection case, `bot.collect` provides completion and cleanup
in one operation. Use a handle when the application needs to manage a job
independently. Use a `run*` stream when the workflow needs progress and cancellation ownership.

See [connection and bot lifecycle](lifecycle.md) and the
[collection recipe](https://soulfiremc.com/docs/sdk/recipes/collect-blocks).
