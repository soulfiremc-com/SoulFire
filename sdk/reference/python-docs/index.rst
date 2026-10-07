SoulFire Python SDK
===================

Use this reference to connect to SoulFire, control bots, and run server tasks.
It documents the SDK source and generated protocol types for the version in the page title.
For a first bot, start with the `Python tutorial <https://soulfiremc.com/docs/sdk/python>`_.

Choose an API
-------------

.. list-table::
   :header-rows: 1
   :widths: 35 65

   * - Goal
     - Start here
   * - Connect or install SoulFire
     - :class:`soulfire.SoulFire`
   * - Provision accounts in an instance
     - :class:`soulfire.SoulFireInstance`
   * - Start a bot, read state, or perform actions
     - :class:`soulfire.SoulFireBot`
   * - Run and inspect server jobs
     - :class:`soulfire.SoulFireTasks`, :class:`soulfire.SoulFireTask`
   * - Observe events and maintain live state
     - :class:`soulfire.BotSession`
   * - Query or change inventory
     - :class:`soulfire.SoulFireInventory`
   * - Discover and execute routes
     - :class:`soulfire.SoulFirePathfinder`

Operations return lazy Effects or Streams.
The lifecycle and task pages explain readiness, scope cleanup, failure handling, and cancellation ownership.
Complete protobuf and RPC documentation remains available in the package reference.

.. toctree::
   :maxdepth: 1
   :caption: SDK concepts

   lifecycle
   tasks

.. toctree::
   :maxdepth: 1
   :caption: API reference

   Client and setup <api/soulfire/SoulFire>
   Instances <api/soulfire/SoulFireInstance>
   Bots <api/soulfire/bot/SoulFireBot>
   Task execution <api/soulfire/tasks/SoulFireTasks>
   Task handles <api/soulfire/tasks/SoulFireTask>
   All modules, RPCs, and protocol types <api/index>

.. toctree::
   :caption: Guides

   Python tutorial <https://soulfiremc.com/docs/sdk/python>
   SDK recipes <https://soulfiremc.com/docs/sdk/recipes>
   TypeScript API <https://ts.soulfiremc.com/>
