Connection and bot lifecycle
============================

Choose a setup method
---------------------

* :meth:`soulfire.SoulFire.create_bot` installs a local SoulFire server and returns a ready bot.
* :meth:`soulfire.SoulFire.install` installs once so several bots can share the managed server.
* :meth:`soulfire.SoulFire.connect` connects to an existing server and checks compatibility.
* :meth:`soulfire.SoulFire.instance` and :meth:`soulfire.SoulFireInstance.bot` create handles for existing UUIDs.

``connect`` takes a SoulFire gRPC-Web URL, such as ``http://localhost:38765``.
Bot provisioning takes a Minecraft address, such as ``localhost:25565``.
These addresses point to different services.
Named provisioning reuses instances and accounts. Conflicting configuration fails explicitly.
Instances and accounts persist when a connection scope closes.

Keep the workflow inside its scope
----------------------------------

SDK operations are lazy ``Effect[A, E, R]`` values:

* ``A`` is the successful result.
* ``E`` is the expected error channel.
* ``R`` lists required services, such as ``Scope``.

Inside an Effect workflow, ``yield from`` executes these operations.
Decorated implementations use ``EffectGen`` in source annotations.
The reference shows their caller-facing ``Effect`` return types.
Run the complete workflow at the application boundary with the async runtime.

.. literalinclude:: ../../python/examples/reference_lifecycle.py
   :language: python
   :start-at: import os
   :end-before: @gen

.. literalinclude:: ../../python/examples/reference_lifecycle.py
   :language: python
   :pyobject: collect_with_client

The `complete example <https://github.com/soulfiremc-com/SoulFire/blob/main/sdk/python/examples/reference_lifecycle.py>`_
contains the imports and decorated workflows. Run a workflow at the application boundary:

.. code-block:: python

   import asyncio
   from effect_py import run_async, scoped

   asyncio.run(run_async(scoped(collect_with_client).or_die()))

The Minecraft server must accept offline accounts for this example.
Set ``SOULFIRE_TOKEN`` if the SoulFire server requires authentication.
``or_die()`` converts expected errors into defects at this script boundary.
For application recovery, handle the Effect error channel or inspect ``run_async_exit`` instead.

Understand readiness and cleanup
--------------------------------

:meth:`soulfire.SoulFireBot.connect` starts a stopped bot and waits for its initial player snapshot.
It attaches the session that supplies :attr:`soulfire.SoulFireBot.state`.
``ready_timeout`` defaults to 30 seconds and must be positive and finite.

The scope closes observation and stops a bot only if the SDK started it.
A previously running bot stays running.
Managed installation also stops its local process when the outer scope closes.
All bot work must finish inside that scope.
A handle returned from a closed scope does not keep resources alive.

:meth:`soulfire.SoulFireBot.start` changes desired state without readiness or cleanup ownership.
:meth:`soulfire.SoulFireBot.wait_for_online` waits for live state without attaching ``bot.state``.
:meth:`soulfire.SoulFireBot.observe` returns a separate session unless it can reuse an attached session.
Read that session's state when you open observation separately.

Use the correct timeout
-----------------------

.. list-table::
   :header-rows: 1
   :widths: 30 20 50

   * - Limit
     - Unit
     - Controls
   * - ``ready_timeout``
     - Seconds
     - Bot startup and initial player snapshot
   * - ``startup_timeout``
     - Seconds
     - Managed SoulFire process startup
   * - ``timeout_ms``
     - Milliseconds
     - RPC transport timeout
   * - Task ``deadline``
     - Timezone-aware ``datetime``
     - Server task execution

A shorter RPC timeout can fail before the readiness deadline.
A task deadline is independent of the client request timeout.

Handle failures and control leases
----------------------------------

Expected SDK failures use the Effect error channel.
Use ``catch_tag`` to recover from a specific error.
Do not retry every action automatically: a repeated mutation can repeat completed side effects.
For task submission retries, use a stable idempotency key for the same job.
A different intended job needs a new key.

:meth:`soulfire.SoulFireBot.acquire_control` acquires exclusive action control with scoped release.
The scope releases the lease, but it does not renew it.
Call :meth:`soulfire.SoulFireBotControlLease.renew` before expiry for longer work.

Continue with :doc:`tasks`, the `Python tutorial <https://soulfiremc.com/docs/sdk/python>`_,
or `complete SDK recipes <https://soulfiremc.com/docs/sdk/recipes>`_.
