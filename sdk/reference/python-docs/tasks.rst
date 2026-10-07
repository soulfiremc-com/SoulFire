Task execution and ownership
============================

SoulFire tasks run on the server. Choose the API according to the workflow's ownership:

.. list-table::
   :header-rows: 1
   :widths: 30 35 35

   * - API
     - Result
     - Cancellation ownership
   * - :meth:`soulfire.SoulFireBot.collect`
     - Completed collection result
     - Cancels unfinished work on interruption
   * - :meth:`soulfire.SoulFireTasks.collect_blocks`
     - Handle after server acceptance
     - Explicit cancellation through ``task.cancel()``
   * - :meth:`soulfire.SoulFireTasks.run_collect_blocks`
     - Stream of task events
     - Cancels unfinished work when the stream closes by default

The same start-versus-stream distinction applies to navigation and other task families.
A start method does not wait for completion.
``run_*`` methods start work when their streams are consumed.

Wait for a typed result
-----------------------

This workflow uses managed installation and an offline-compatible Minecraft server:

.. literalinclude:: ../../python/examples/reference_lifecycle.py
   :language: python
   :pyobject: collect_with_task_handle

The workflow uses the imports and ``@gen`` decorator from the
`complete example <https://github.com/soulfiremc-com/SoulFire/blob/main/sdk/python/examples/reference_lifecycle.py>`_.
Run it with ``asyncio.run(run_async(scoped(collect_with_task_handle).or_die()))``.

:meth:`soulfire.SoulFireTask.result` waits for successful completion and decodes the expected protobuf result.
A failed, cancelled, or timed-out task fails with ``SoulFireTaskError`` in the Effect error channel.
The error contains the task snapshot, including status and failure details.
A missing or incompatible result also fails.

:meth:`soulfire.SoulFireTask.wait` returns the snapshot regardless of terminal status.
Use it when cancellation or failure is an expected outcome to inspect.
``task.snapshot`` and ``task.terminal`` read cached state.
``refresh()``, ``wait()``, and ``cancel()`` update it.
Direct event consumption does not update it.

Interrupting ``task.result()`` or ``task.events()`` closes the observer.
It does not itself cancel the server job.
Call ``task.cancel(reason)`` explicitly, or choose an API that owns cancellation.
Server disconnect, reconnect, deadline, and resource-conflict policies still control execution.

Observe progress with ownership
-------------------------------

.. literalinclude:: ../../python/examples/reference_lifecycle.py
   :language: python
   :pyobject: collect_with_progress

Run this workflow with ``asyncio.run(run_async(scoped(collect_with_progress).or_die()))``.
The stream ends when the task ends.
Inspect event snapshots for terminal status; stream completion alone does not establish task success.

The default ``run_*`` disconnect policy is ``CANCEL_WITH_CALL``.
Interrupting the workflow therefore cancels unfinished remote work.
The generic :meth:`soulfire.SoulFireTasks.run` method also accepts an explicit disconnect policy.

Bound work and avoid duplicate submissions
------------------------------------------

A task ``deadline`` bounds server execution and must be timezone-aware.
A request ``timeout_ms`` bounds the RPC in milliseconds.
Use a deadline when a durable job must finish within a fixed window.

``idempotency_key`` identifies one intended submission.
Reuse that key when retrying an uncertain submission.
Do not reuse it for a different job.
Error recovery must account for resource conflicts, reconnect policies, and completed side effects.

For collection with completion and cleanup, use :meth:`soulfire.SoulFireBot.collect`.
Use a handle when the application manages a job independently.
Use a ``run_*`` stream when a workflow needs progress and cancellation ownership.

See :doc:`lifecycle` and the `collection recipe <https://soulfiremc.com/docs/sdk/recipes/collect-blocks>`_.
