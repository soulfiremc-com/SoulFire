"""Complete scoped workflows for the generated API reference.

Choose one workflow and run it at the application boundary, for example:
asyncio.run(run_async(scoped(collect_with_client).or_die()))
"""

import os
from datetime import UTC, datetime, timedelta

from effect_py import EffectGen, Scope, gen, sync

from soulfire import SoulFire, SoulFireOperationError


@gen
def collect_with_client() -> EffectGen[None, SoulFireOperationError, Scope]:
    # Use the SoulFire gRPC-Web address here, not the Minecraft address.
    client = yield from SoulFire.connect(
        "http://localhost:38765", token=os.environ.get("SOULFIRE_TOKEN")
    )
    instance = yield from client.get_or_create_instance(
        "reference-example", server="localhost:25565"
    )
    bot = yield from instance.get_or_create_bot("Builder", username="Builder", ready_timeout=30.0)
    result = yield from bot.collect("#minecraft:logs", count=16)
    yield from sync(lambda: print(result))


@gen
def collect_with_task_handle() -> EffectGen[None, SoulFireOperationError, Scope]:
    bot = yield from SoulFire.create_bot(server="localhost:25565", username="Builder")
    task = yield from bot.tasks.collect_blocks(
        tags=["minecraft:logs"],
        count=16,
        deadline=datetime.now(UTC) + timedelta(seconds=120),
    )
    yield from sync(lambda: print(f"Accepted task {task.id}"))
    result = yield from task.result()
    yield from sync(lambda: print(result))


@gen
def collect_with_progress() -> EffectGen[None, SoulFireOperationError, Scope]:
    bot = yield from SoulFire.create_bot(server="localhost:25565", username="Builder")
    yield from bot.tasks.run_collect_blocks(tags=["minecraft:logs"], count=16).run_for_each(
        lambda event: sync(lambda: print(event.task.summary))
    )
