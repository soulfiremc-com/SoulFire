import {
  BotTaskConflictPolicy,
  BotTaskReconnectPolicy,
  InventoryArea,
  PathfindSearchMode,
  SoulFireActionError,
  SoulFireRpcError,
  SoulFireTaskError,
  SoulFireTaskFailed,
  type SoulFireBot,
} from "@soulfiremc/sdk";
import { create } from "@bufbuild/protobuf";
import { Code } from "@connectrpc/connect";
import {
  BotActionResultSchema,
  BotActionStatus,
} from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { BotTaskFailureSchema, BotTaskSchema } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect, Fiber, Stream } from "effect";
import { describe, expect, it } from "vitest";

import { defaultBeatGameStrategy, makeSoulFireBeatGameDriver } from "../src/index.js";
import { objectContaining } from "./matchers.js";

function effectBot(
  taskResult: Effect.Effect<unknown, unknown> = Effect.succeed({}),
  selectHotbarResult: Effect.Effect<unknown, unknown> = Effect.succeed({}),
  eventStream: Stream.Stream<unknown, unknown> = Stream.empty,
  entityResults: readonly unknown[] = [],
) {
  const calls: {
    attack?: Readonly<Record<string, unknown>>;
    collect?: readonly unknown[];
    explore?: Readonly<Record<string, unknown>>;
    flee?: readonly unknown[];
    sleep?: Readonly<Record<string, unknown>>;
    goTo?: readonly unknown[];
    armor?: Readonly<Record<string, unknown>>;
    eat?: readonly unknown[];
    equip?: Readonly<Record<string, unknown>>;
    cancellations: number;
    controlAcquisitions: number;
    controlReleases: number;
    controlRenewals: number;
  } = {
    cancellations: 0,
    controlAcquisitions: 0,
    controlReleases: 0,
    controlRenewals: 0,
  };
  const taskHandle = {
    result: () => taskResult,
    cancel: () =>
      Effect.sync(() => {
        calls.cancellations += 1;
        return {};
      }),
  };
  const bot = {
    instanceId: "instance-id",
    id: "bot-id",
    world: {
      player: () =>
        Effect.succeed({
          position: {
            x: 1,
            y: 64,
            z: 2,
            dimension: "minecraft:overworld",
          },
          velocity: { x: 0, y: 0, z: 0 },
          rotation: { yaw: 20, pitch: -10 },
          health: 18,
          maxHealth: 20,
          food: 17,
          dead: false,
          sleeping: false,
          usingItem: false,
          equipment: {
            offhand: {
              itemId: "minecraft:shield",
              count: 1,
            },
          },
          connectionEpoch: "connection-epoch",
          revision: 7n,
        }),
      queryBlocks: () => Effect.succeed({ blocks: [] }),
      queryEntities: () => Effect.succeed({ entities: entityResults }),
    },
    inventory: {
      snapshot: () =>
        Effect.succeed({
          revision: 9n,
          selectedHotbarSlot: 2,
          pathBuildingBlockCount: 37,
          slots: [
            {
              slot: 10,
              area: InventoryArea.MAIN,
              item: { itemId: "minecraft:spruce_log", count: 3 },
            },
            {
              slot: 36,
              area: InventoryArea.HOTBAR,
              item: { itemId: "minecraft:spruce_log", count: 2 },
            },
            {
              slot: 1,
              area: InventoryArea.CRAFTING,
              item: { itemId: "minecraft:spruce_log", count: 1 },
            },
            {
              slot: 0,
              area: InventoryArea.CONTAINER,
              item: { itemId: "minecraft:diamond", count: 64 },
            },
          ],
        }),
      selectHotbar: () => selectHotbarResult,
      equip: (request: Readonly<Record<string, unknown>>) => {
        calls.equip = request;
        return Effect.succeed({});
      },
    },
    recipes: {
      list: () => Effect.succeed({ recipes: [] }),
      canCraft: () =>
        Effect.succeed({
          canCraft: false,
          maximumCraftCount: 0,
          missing: [],
        }),
    },
    tasks: {
      collectBlocks: (...args: readonly unknown[]) => {
        calls.collect = args;
        return Effect.succeed(taskHandle);
      },
      goTo: (...args: readonly unknown[]) => {
        calls.goTo = args;
        return Effect.succeed(taskHandle);
      },
      autoArmor: (options: Readonly<Record<string, unknown>>) => {
        calls.armor = options;
        return Effect.succeed(taskHandle);
      },
      autoEat: (...args: readonly unknown[]) => {
        calls.eat = args;
        return Effect.succeed(taskHandle);
      },
      explore: (options: Readonly<Record<string, unknown>>) => {
        calls.explore = options;
        return Effect.succeed(taskHandle);
      },
      flee: (...args: readonly unknown[]) => {
        calls.flee = args;
        return Effect.succeed(taskHandle);
      },
      sleep: (options: Readonly<Record<string, unknown>>) => {
        calls.sleep = options;
        return Effect.succeed(taskHandle);
      },
    },
    events: () => eventStream,
    attackEntity: (request: Readonly<Record<string, unknown>>) => {
      calls.attack = request;
      return Effect.succeed({});
    },
    acquireControl: () =>
      Effect.sync(() => {
        calls.controlAcquisitions += 1;
        return {
          renew: () =>
            Effect.sync(() => {
              calls.controlRenewals += 1;
              return {};
            }),
          release: () =>
            Effect.sync(() => {
              calls.controlReleases += 1;
            }),
        };
      }),
  };
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- the fake implements only the bot methods the driver calls
  return { bot: bot as unknown as SoulFireBot, calls };
}

describe("production SoulFire beat-game driver", () => {
  it("shares one control lease across nested control scopes", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(driver.withControl(driver.withControl(Effect.void)));

    expect(calls.controlAcquisitions).toBe(1);
    expect(calls.controlReleases).toBe(1);
  });

  it("maps public snapshots into the stable planner observation", async () => {
    const { bot } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    const current = await Effect.runPromise(driver.observe);

    expect(current.player).toMatchObject({
      position: {
        x: 1,
        y: 64,
        z: 2,
        dimension: "minecraft:overworld",
      },
      health: 18,
      food: 17,
      equipment: { offhand: "minecraft:shield" },
      connectionEpoch: "connection-epoch",
      revision: 7n,
    });
    expect(current.inventory).toMatchObject({
      revision: 9n,
      selectedHotbarSlot: 2,
      emptyPlayerSlots: 34,
      pathBuildingBlockCount: 37,
      counts: { "minecraft:spruce_log": 5 },
      hotbar: { 36: "minecraft:spruce_log" },
    });
  });

  it("counts worn items while a crafting menu hides their slots", async () => {
    // As in the live run: with a crafting table open, the inventory snapshot
    // has no armor or offhand slots, but the player still wears them.
    const { bot } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    const current = await Effect.runPromise(driver.observe);

    expect(current.inventory.counts).toEqual({
      "minecraft:spruce_log": 5,
      "minecraft:shield": 1,
    });
  });

  it("maps sheep wool color and shearing metadata", async () => {
    const { bot } = effectBot(Effect.succeed({}), Effect.succeed({}), Stream.empty, [
      {
        reference: {
          connectionEpoch: "connection-epoch",
          networkId: 43,
        },
        entityType: "minecraft:sheep",
        position: {
          x: 2,
          y: 64,
          z: 2,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        metadata: {
          sheep_color: "light_blue",
          sheep_sheared: true,
        },
      },
    ]);
    const driver = makeSoulFireBeatGameDriver(bot);

    const entities = await Effect.runPromise(
      driver.queryEntities({
        radius: 8,
        selector: { entityTypes: ["minecraft:sheep"] },
      }),
    );

    expect(entities[0]?.sheep).toEqual({ color: "light_blue", sheared: true });
  });

  it("maps semantic creeper fuse metadata", async () => {
    const { bot } = effectBot(Effect.succeed({}), Effect.succeed({}), Stream.empty, [
      {
        reference: {
          connectionEpoch: "connection-epoch",
          networkId: 42,
        },
        entityType: "minecraft:creeper",
        position: {
          x: 2,
          y: 64,
          z: 2,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        aggressive: true,
        metadata: {
          creeper_fuse_progress: 0.6,
          creeper_swell_direction: 1,
          creeper_powered: false,
          creeper_ignited: false,
        },
      },
    ]);
    const driver = makeSoulFireBeatGameDriver(bot);

    const entities = await Effect.runPromise(
      driver.queryEntities({
        radius: 8,
        selector: { entityTypes: ["minecraft:creeper"] },
      }),
    );

    expect(entities).toHaveLength(1);
    expect(entities[0]).toMatchObject({
      entityType: "minecraft:creeper",
      aggressive: true,
      creeper: {
        fuseProgress: 0.6,
        swellDirection: 1,
        powered: false,
        ignited: false,
      },
    });
  });

  it("reports damage with its damage type", async () => {
    const { bot } = effectBot(
      Effect.succeed({}),
      Effect.succeed({}),
      Stream.make({
        event: {
          case: "damage",
          value: { previousHealth: 20, health: 19, amount: 1, damageType: "minecraft:freeze" },
        },
      }),
    );
    const driver = makeSoulFireBeatGameDriver(bot);

    const events = await Effect.runPromise(driver.events.pipe(Stream.take(1), Stream.runCollect));

    expect(Array.from(events)).toEqual([
      objectContaining({
        type: "bot-damaged",
        amount: 1,
        health: 19,
        damageType: "minecraft:freeze",
      }),
    ]);
  });

  it("normalizes the End credits game event", async () => {
    const { bot } = effectBot(
      Effect.succeed({}),
      Effect.succeed({}),
      Stream.make({
        event: {
          case: "environment",
          value: {
            change: {
              case: "gameEvent",
              value: { event: "win_game", parameter: 1 },
            },
          },
        },
      }),
    );
    const driver = makeSoulFireBeatGameDriver(bot);

    const events = await Effect.runPromise(driver.events.pipe(Stream.take(1), Stream.runCollect));

    expect(Array.from(events)).toEqual([
      objectContaining({
        type: "game-event",
        event: "win_game",
        parameter: 1,
      }),
    ]);
  });

  it("passes durable task inputs through the public task API", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "collect-blocks",
          blockIds: ["minecraft:oak_log"],
          tags: ["minecraft:logs"],
          count: 3,
          searchRadius: 40,
        },
        {
          ...defaultBeatGameStrategy.path,
          additionalPlaceItemIds: ["minecraft:oak_log"],
          protectedBlocks: [
            {
              x: 4,
              y: 65,
              z: -2,
              dimension: "minecraft:overworld",
            },
          ],
          sprint: false,
          minimumY: 63,
          maximumY: 96,
        },
        {
          idempotencyKey: "beat-game:test-action",
        },
      ),
    );

    expect(calls.collect).toEqual([
      ["minecraft:oak_log"],
      {
        avoidSubmergedTargets: false,
        requireLineOfSight: false,
        conflictPolicy: BotTaskConflictPolicy.QUEUE,
        tags: ["minecraft:logs"],
        count: 3,
        idempotencyKey: "beat-game:test-action",
        searchRadius: 40,
        reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
        path: {
          allowMining: true,
          allowPlacing: true,
          avoidFluids: false,
          additionalPlaceItemIds: ["minecraft:oak_log"],
          protectedBlocks: [
            {
              x: 4,
              y: 65,
              z: -2,
              dimension: "minecraft:overworld",
            },
          ],
          sprint: false,
          minimumY: 63,
          maximumY: 96,
          searchMode: PathfindSearchMode.NORMAL,
          maximumExpandedStates: 50_000,
          maximumFallDistance: 3,
          maximumParkourGap: 0,
          smoothCamera: false,
          timeoutSeconds: 30,
          searchTimeoutSeconds: 30,
        },
      },
    ]);
  });

  it("forwards bounded auto-eat completion controls", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "auto-eat",
          foodItemIds: ["minecraft:cooked_mutton"],
          foodLevel: 18,
          maximumMeals: 8,
          completeWhenNoFood: true,
          restoreSelectedSlot: false,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    expect(calls.eat).toEqual([
      ["minecraft:cooked_mutton"],
      objectContaining({
        foodLevel: 18,
        maximumMeals: 8,
        completeWhenNoFood: true,
        restoreSelectedSlot: false,
      }),
    ]);
  });

  it("preserves exact entity selectors for targeted evasion", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "flee",
          selector: {
            networkId: 42,
            uuid: "00000000-0000-0000-0000-000000000042",
            alive: true,
          },
          triggerRadius: 12,
          safeDistance: 16,
          completeWhenSafe: true,
          maximumEscapes: 1,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    expect(calls.flee).toEqual([
      {
        entityTypes: [],
        tags: [],
        categories: [],
        networkId: 42,
        uuid: "00000000-0000-0000-0000-000000000042",
        alive: true,
        requireLineOfSight: false,
      },
      objectContaining({
        triggerRadius: 12,
        safeDistance: 16,
        completeWhenSafe: true,
        maximumEscapes: 1,
      }),
    ]);
  });

  it("forwards a pinned bed and bounded sleep policy", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "sleep",
          bed: {
            x: 8,
            y: 64,
            z: -3,
            dimension: "minecraft:overworld",
          },
          searchRadius: 32,
          waitUntilPossible: false,
          retryIntervalTicks: 20,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    expect(calls.sleep).toEqual(
      objectContaining({
        bed: {
          x: 8,
          y: 64,
          z: -3,
          dimension: "minecraft:overworld",
        },
        searchRadius: 32,
        waitUntilPossible: false,
        retryIntervalTicks: 20,
      }),
    );
  });

  it("normalizes precise player coordinates for block-position tasks", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "explore",
          origin: {
            x: 8.5,
            y: 86,
            z: -3.5,
            dimension: "minecraft:overworld",
          },
          radius: 32,
          maximumWaypoints: 3,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    expect(calls.explore).toMatchObject({
      origin: {
        x: 8,
        y: 86,
        z: -4,
        dimension: "minecraft:overworld",
      },
      radius: 32,
      maximumWaypoints: 3,
    });
  });

  it("preserves durable task failure codes", async () => {
    const task = create(BotTaskSchema, {
      taskId: "missing-target",
      failure: create(BotTaskFailureSchema, {
        code: "not_found",
        message: "Target entity is not observable",
        retryable: false,
      }),
    });
    const cause = new SoulFireTaskError(task);
    const { bot } = effectBot(
      Effect.fail(
        new SoulFireTaskFailed({
          task,
          cause,
          message: cause.message,
        }),
      ),
    );
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.runTask(
          {
            type: "collect-blocks",
            blockIds: ["minecraft:oak_log"],
            count: 1,
            searchRadius: 16,
          },
          defaultBeatGameStrategy.path,
        ),
      ),
    );

    expect(error.code).toBe("not_found");
  });

  it("normalizes Connect RPC status codes for primitive failures", async () => {
    const cause = new SoulFireRpcError({
      operation: "inventory.selectHotbar",
      cause: new Error("No matching item is available"),
      code: Code.NotFound,
      retryable: false,
      message: "No matching item is available",
    });
    const { bot } = effectBot(Effect.succeed({}), Effect.fail(cause));
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:iron_pickaxe"] },
        }),
      ),
    );

    expect(error).toMatchObject({
      operation: "act.select-item",
      code: "not_found",
      retryable: false,
    });
  });

  it("retries while the bot is offline", async () => {
    // As in a live run whose Minecraft server restarted: SoulFire reconnects
    // the bot on its own, so the run should wait for it.
    const cause = new SoulFireRpcError({
      operation: "inventory.selectHotbar",
      cause: new Error("Bot is not online: 9e195199-b8fb-37bf-b25d-a1c04310b121"),
      code: Code.FailedPrecondition,
      retryable: false,
      message: "[failed_precondition] Bot is not online: 9e195199-b8fb-37bf-b25d-a1c04310b121",
    });
    const { bot } = effectBot(Effect.succeed({}), Effect.fail(cause));
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:iron_pickaxe"] },
        }),
      ),
    );

    expect(error).toMatchObject({ code: "failed_precondition", retryable: true });
  });

  it("retries a request whose connection was reset", async () => {
    // As in a live run: one entity query lost its connection, and the run ended.
    const cause = new SoulFireRpcError({
      operation: "world.queryEntities",
      cause: new Error(
        "Transport error (POST http://127.0.0.1:35719/soulfire.v1.WorldService/QueryEntities)",
        { cause: new Error("read ECONNRESET") },
      ),
      code: Code.Unknown,
      retryable: false,
      message:
        "[unknown] Transport error (POST http://127.0.0.1:35719/soulfire.v1.WorldService/QueryEntities)",
    });
    const { bot } = effectBot(Effect.succeed({}), Effect.fail(cause));
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:iron_pickaxe"] },
        }),
      ),
    );

    expect(error).toMatchObject({ retryable: true });
  });

  it("reads the status of a failed bot action from its message", async () => {
    // As in a live run whose dig target was out of reach.
    const cause = new SoulFireActionError(
      create(BotActionResultSchema, {
        actionId: "action-1",
        status: BotActionStatus.CANCELLED,
        error: "OUT_OF_RANGE: Target block is outside the bot's interaction reach",
      }),
    );
    const { bot } = effectBot(Effect.succeed({}), Effect.fail(cause));
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:iron_pickaxe"] },
        }),
      ),
    );

    expect(error).toMatchObject({ code: "out_of_range", retryable: false });
  });

  it("retries a placement whose face the bot cannot see yet", async () => {
    // As in two live runs that exited on an occluded placement face.
    const cause = new SoulFireActionError(
      create(BotActionResultSchema, {
        actionId: "action-1",
        status: BotActionStatus.CANCELLED,
        error:
          "FAILED_PRECONDITION: Block placement is not ready (FACE_OCCLUDED): No placement face is visible from the player's eye position",
      }),
    );
    const { bot } = effectBot(Effect.succeed({}), Effect.fail(cause));
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:cobblestone"] },
        }),
      ),
    );

    expect(error).toMatchObject({ code: "failed_precondition", retryable: true });
  });

  it("forwards the observation epoch with direct entity actions", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.act({
        type: "attack-entity",
        connectionEpoch: "00000000-0000-0000-0000-000000000042",
        networkId: 42,
        sprinting: true,
      }),
    );

    expect(calls.attack).toEqual({
      connectionEpoch: "00000000-0000-0000-0000-000000000042",
      entityId: 42,
      sprinting: true,
    });
  });

  it("equips an item into the requested equipment slot", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.act({
        type: "equip-item",
        selector: { itemIds: ["minecraft:shield"] },
        equipmentSlot: "offhand",
      }),
    );

    expect(calls.equip).toEqual({
      selector: {
        itemIds: ["minecraft:shield"],
        tags: [],
      },
      equipmentSlot: "offhand",
    });
  });

  it("forwards bounded auto-armor completion options", async () => {
    const { bot, calls } = effectBot();
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.runTask(
        {
          type: "auto-armor",
          maximumEquips: 4,
          completeWhenNoUpgrade: true,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    expect(calls.armor).toMatchObject({
      maximumEquips: 4,
      completeWhenNoUpgrade: true,
    });
  });

  it("cancels its durable server task when interrupted", async () => {
    const { bot, calls } = effectBot(Effect.never);
    const driver = makeSoulFireBeatGameDriver(bot);
    const fiber = Effect.runFork(
      driver.runTask(
        {
          type: "collect-blocks",
          blockIds: ["minecraft:oak_log"],
          count: 1,
          searchRadius: 16,
        },
        defaultBeatGameStrategy.path,
      ),
    );

    await Effect.runPromise(Fiber.interrupt(fiber));

    expect(calls.cancellations).toBe(1);
  });

  it("retries a route that only missed its quality bound with a looser one", async () => {
    // As in the live run: the route exists but misses SoulFire's default
    // bound of 1.2.
    const task = create(BotTaskSchema, {
      taskId: "loose-route",
      failure: create(BotTaskFailureSchema, {
        code: "path_quality_bound_not_met",
        message: "Pathfinding found a route with certified bound 1.38 but the request requires 1.2",
        retryable: false,
      }),
    });
    const cause = new SoulFireTaskError(task);
    let attempts = 0;
    const { bot, calls } = effectBot(
      Effect.suspend(() => {
        attempts += 1;
        return attempts === 1
          ? Effect.fail(new SoulFireTaskFailed({ task, cause, message: cause.message }))
          : Effect.succeed({});
      }),
    );
    const driver = makeSoulFireBeatGameDriver(bot);

    await Effect.runPromise(
      driver.pathfind(
        { x: 10, y: 64, z: 20, dimension: "minecraft:overworld" },
        2,
        defaultBeatGameStrategy.path,
      ),
    );

    expect(attempts).toBe(2);
    expect(calls.goTo?.[1]).toMatchObject({
      path: objectContaining({
        searchMode: PathfindSearchMode.URGENT,
        maximumQualityBound: 3,
      }),
    });
  });

  it("keeps a route that ran out of search states retryable", async () => {
    // As in the live run: a long walk down to the stronghold hit the state
    // limit, and the failure ended the whole run.
    const task = create(BotTaskSchema, {
      taskId: "deep-route",
      failure: create(BotTaskFailureSchema, {
        code: "path_search_limit",
        message: "Pathfinding reached its search limit after 50000 expanded states",
        retryable: false,
      }),
    });
    const cause = new SoulFireTaskError(task);
    let attempts = 0;
    const { bot, calls } = effectBot(
      Effect.suspend(() => {
        attempts += 1;
        return Effect.fail(new SoulFireTaskFailed({ task, cause, message: cause.message }));
      }),
    );
    const driver = makeSoulFireBeatGameDriver(bot);

    const error = await Effect.runPromise(
      Effect.flip(
        driver.pathfind(
          { x: 10, y: 29, z: 20, dimension: "minecraft:overworld" },
          16,
          defaultBeatGameStrategy.path,
        ),
      ),
    );

    // It tries once more with a looser search, then leaves the walk to a
    // later attempt instead of ending the run.
    expect(attempts).toBe(2);
    expect(calls.goTo?.[1]).toMatchObject({
      path: objectContaining({ searchMode: PathfindSearchMode.URGENT }),
    });
    expect(error.code).toBe("path_search_limit");
    expect(error.retryable).toBe(true);
  });

  it("cancels durable pathfinding when interrupted", async () => {
    const { bot, calls } = effectBot(Effect.never);
    const driver = makeSoulFireBeatGameDriver(bot);
    const fiber = Effect.runFork(
      driver.pathfind(
        {
          x: 10,
          y: 64,
          z: 20,
          dimension: "minecraft:overworld",
        },
        2,
        defaultBeatGameStrategy.path,
      ),
    );

    await Effect.runPromise(Fiber.interrupt(fiber));

    expect(calls.goTo?.[1]).toMatchObject({
      conflictPolicy: BotTaskConflictPolicy.REPLACE,
      reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
    });
    expect(calls.cancellations).toBe(1);
  });
});
