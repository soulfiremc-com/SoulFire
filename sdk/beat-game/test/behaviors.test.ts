import { Effect, Stream } from "effect";
import { assert, describe, expect, it } from "vitest";

import {
  activateEndPortal,
  attackNearest,
  BeatGameDriverError,
  buildNetherPortal,
  castNetherPortal,
  collectDragonEgg,
  collectBlocks,
  collectNearbyDrops,
  craftItem,
  createNetherPortalFrame,
  eatWhenNeeded,
  enterEndPortal,
  enterPortal,
  exitEnd,
  excavateStaircase,
  fightEnderDragon,
  respawnAndRecover,
  rotationToward,
  throwEyeOfEnder,
  type BeatGameBlockObservation,
  type BeatGameBlockPosition,
  type BeatGameEntityObservation,
} from "../src/index.js";
import {
  blockObservation,
  FakeBeatGameDriver,
  installStaircaseMovementSimulation,
  observation,
  required,
} from "./fixtures.js";
import { anyInstanceOf, arrayContaining, objectContaining, stringContaining } from "./matchers.js";

function feetCenter(position: BeatGameBlockPosition): BeatGameBlockPosition {
  return {
    ...position,
    x: position.x + 0.5,
    z: position.z + 0.5,
  };
}

const framePositionKey = (position: {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly dimension: string;
}) => `${position.dimension}:${position.x}:${position.y}:${position.z}`;

function installPortalWorld(
  driver: FakeBeatGameDriver,
  frame: ReturnType<typeof createNetherPortalFrame>,
  options: {
    readonly initialBlocks?: readonly ReturnType<typeof blockObservation>[];
    readonly rejectFirstPlacementAt?: ReturnType<typeof createNetherPortalFrame>["blocks"][number];
  } = {},
) {
  const blocks = new Map(
    (options.initialBlocks ?? []).map((block) => [framePositionKey(block.position), block]),
  );
  const placementAttempts = new Map<string, number>();
  let portalActive = false;
  let rejectedPlacement = false;

  driver.blockQueryResolver = ({ center, selector }) => {
    if (selector.blockIds?.includes("minecraft:obsidian") === true) {
      return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
    }
    if (selector.blockIds?.includes("minecraft:nether_portal") === true) {
      const interior = frame.interior[0];
      return portalActive && interior !== undefined
        ? [
            blockObservation(interior, {
              blockId: "minecraft:nether_portal",
              diggable: false,
              replaceable: false,
            }),
          ]
        : [];
    }
    const position = {
      x: Math.floor(center.x),
      y: Math.floor(center.y),
      z: Math.floor(center.z),
      dimension: center.dimension,
    };
    return [
      blocks.get(framePositionKey(position)) ??
        blockObservation(position, {
          blockId: "minecraft:air",
          replaceable: true,
        }),
    ];
  };
  driver.actionObserver = (action) => {
    if (action.type === "dig-block") {
      blocks.delete(framePositionKey(action.position));
      return;
    }
    if (action.type === "interact-block") {
      portalActive = true;
      return;
    }
    if (action.type !== "place-block") {
      return;
    }
    const offset = {
      down: { x: 0, y: -1, z: 0 },
      up: { x: 0, y: 1, z: 0 },
      north: { x: 0, y: 0, z: -1 },
      south: { x: 0, y: 0, z: 1 },
      west: { x: -1, y: 0, z: 0 },
      east: { x: 1, y: 0, z: 0 },
    }[action.face];
    const target = {
      x: action.against.x + offset.x,
      y: action.against.y + offset.y,
      z: action.against.z + offset.z,
      dimension: action.against.dimension,
    };
    const targetKey = framePositionKey(target);
    placementAttempts.set(targetKey, (placementAttempts.get(targetKey) ?? 0) + 1);
    if (
      !rejectedPlacement &&
      options.rejectFirstPlacementAt !== undefined &&
      targetKey === framePositionKey(options.rejectFirstPlacementAt)
    ) {
      rejectedPlacement = true;
      return;
    }
    const selection = driver.actions.findLast((candidate) => candidate.type === "select-item");
    const itemId = selection?.type === "select-item" ? selection.selector.itemIds?.[0] : undefined;
    if (itemId === undefined) {
      throw new Error("Portal placement did not select a block");
    }
    blocks.set(targetKey, blockObservation(target, { blockId: itemId }));
  };

  return { blocks, key: framePositionKey, placementAttempts };
}

function queriedBlockPosition(
  center: Readonly<{
    x: number;
    y: number;
    z: number;
    dimension: string;
  }>,
) {
  return {
    x: Math.floor(center.x),
    y: Math.floor(center.y),
    z: Math.floor(center.z),
    dimension: center.dimension,
  };
}

const recipe = (
  recipeId: string,
  resultItemId: string,
  resultCount: number,
  ingredients: readonly {
    readonly itemIds: readonly string[];
    readonly count: number;
  }[],
) => ({
  recipeId,
  recipeType: "crafting",
  resultItemId,
  resultCount,
  ingredients: ingredients.map((ingredient) => ({
    ...ingredient,
    tags: [],
  })),
});
const blockKey = (position: BeatGameBlockPosition) =>
  `${position.dimension}:${position.x}:${position.y}:${position.z}`;

describe("beat-game behavior programs", () => {
  it("bounds recovery eating and completes when supplies run out", async () => {
    const driver = new FakeBeatGameDriver();

    await Effect.runPromise(
      eatWhenNeeded(driver, {
        foodLevel: 18,
        maximumMeals: 8,
        completeWhenNoFood: true,
        restoreSelectedSlot: true,
      }),
    );

    expect(driver.tasks).toEqual([
      {
        type: "auto-eat",
        foodItemIds: [],
        foodLevel: 18,
        maximumMeals: 8,
        completeWhenNoFood: true,
        restoreSelectedSlot: true,
      },
    ]);
  });

  it("returns to the death site before searching for dropped items", async () => {
    const driver = new FakeBeatGameDriver();
    const deathPosition = {
      x: 96,
      y: 63,
      z: -48,
      dimension: "minecraft:overworld",
    };
    const dropPosition = {
      x: 97,
      y: 63,
      z: -48,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({
      dead: true,
      health: 0,
      position: deathPosition,
    });
    driver.taskObserver = (task) => {
      if (task.type === "auto-respawn") {
        driver.currentObservation = observation({ position: deathPosition });
      }
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 24,
      entityType: "minecraft:item",
      itemId: "minecraft:iron_ingot",
      position: dropPosition,
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      health: 5,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.entityQueryResolver = () => (driver.paths.length === 1 ? [drop] : []);

    await Effect.runPromise(
      respawnAndRecover(driver, {
        deathPosition,
      }),
    );

    expect(driver.tasks).toContainEqual({
      type: "auto-respawn",
      maximumRespawns: 1,
    });
    expect(driver.paths.map(({ position }) => position)).toEqual([deathPosition, dropPosition]);
    expect(driver.entityQueries).toContainEqual({
      origin: deathPosition,
      radius: 24,
      selector: {
        entityTypes: ["minecraft:item"],
        alive: true,
      },
      maximumResults: 64,
    });
  });

  it("waits for the living player state before resuming after respawn", async () => {
    const driver = new FakeBeatGameDriver();
    let respawnRequested = false;
    let observationsAfterRequest = 0;
    driver.currentObservation = observation({ dead: true, health: 0 });
    driver.taskObserver = (task) => {
      if (task.type === "auto-respawn") {
        respawnRequested = true;
      }
    };
    driver.observationResolver = () => {
      if (!respawnRequested) {
        return Effect.succeed(driver.currentObservation);
      }
      observationsAfterRequest += 1;
      return Effect.succeed(
        observation({
          dead: observationsAfterRequest < 3,
          health: observationsAfterRequest < 3 ? 0 : 20,
        }),
      );
    };

    await Effect.runPromise(respawnAndRecover(driver));

    expect(driver.tasks).toContainEqual({
      type: "auto-respawn",
      maximumRespawns: 1,
    });
    expect(observationsAfterRequest).toBe(3);
  });

  it("stages long corpse travel before approaching its exact height", async () => {
    const driver = new FakeBeatGameDriver();
    const start = {
      x: 0,
      y: 70,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const deathPosition = {
      x: 100,
      y: 20,
      z: 0,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({ position: start });
    driver.xzPathResolver = (x, z, dimension, radius, policy) =>
      Effect.sync(() => {
        driver.xzPaths.push({ x, z, dimension, radius, policy });
        driver.currentObservation = observation({
          position: {
            x,
            y: driver.currentObservation.player.position.y,
            z,
            dimension,
          },
        });
      });

    await Effect.runPromise(
      respawnAndRecover(driver, {
        deathPosition,
        path: {
          allowMining: true,
          allowPlacing: true,
          maxFallDistance: 1,
          searchMode: "URGENT",
          maximumQualityBound: 1.5,
        },
      }),
    );

    expect(driver.xzPaths).toHaveLength(3);
    expect(driver.xzPaths.map(({ x, z }) => ({ x, z }))).toEqual([
      { x: 32, z: 0 },
      { x: 64, z: 0 },
      { x: 88, z: 0 },
    ]);
    expect(driver.xzPaths).toEqual(
      driver.xzPaths.map(() =>
        objectContaining({
          radius: 3,
          policy: objectContaining({
            allowMining: false,
            allowPlacing: false,
            maxFallDistance: 1,
            searchMode: "URGENT",
            maximumQualityBound: 1.5,
          }),
        }),
      ),
    );
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: deathPosition,
        radius: 2,
        policy: objectContaining({
          allowMining: true,
          allowPlacing: true,
          maxFallDistance: 1,
          searchMode: "URGENT",
          maximumQualityBound: 1.5,
        }),
      }),
    );
  });

  it("continues when a death position is no longer reachable", async () => {
    const driver = new FakeBeatGameDriver();
    const deathPosition = {
      x: 24,
      y: 63,
      z: -48,
      dimension: "minecraft:overworld",
    };
    driver.pathResolver = () =>
      Effect.fail(
        new BeatGameDriverError({
          operation: "pathfind",
          code: "unreachable",
          retryable: false,
          message: "No route found to the goal",
        }),
      );

    await expect(
      Effect.runPromise(
        respawnAndRecover(driver, {
          deathPosition,
        }),
      ),
    ).resolves.toBeUndefined();

    expect(driver.entityQueries).toHaveLength(0);
  });

  it("retries corpse recovery through fluids after dry pathfinding fails", async () => {
    const driver = new FakeBeatGameDriver();
    const deathPosition = {
      x: 96,
      y: 63,
      z: -48,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({
      position: { ...deathPosition, x: deathPosition.x - 6 },
    });
    const submergedDrop = {
      connectionEpoch: "epoch-1",
      networkId: 24,
      entityType: "minecraft:item",
      itemId: "minecraft:iron_ingot",
      position: {
        ...deathPosition,
        x: deathPosition.x + 4,
        y: deathPosition.y - 1,
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      health: 5,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.entityQueryResolver = () => (driver.paths.length === 2 ? [submergedDrop] : []);
    driver.pathResolver = (position, radius, policy) => {
      const recordPath = Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
      });
      return policy.avoidFluids
        ? recordPath.pipe(
            Effect.andThen(
              Effect.fail(
                new BeatGameDriverError({
                  operation: "pathfind",
                  code: "unreachable",
                  retryable: false,
                  message: "No dry route found to the goal",
                }),
              ),
            ),
          )
        : recordPath;
    };

    await Effect.runPromise(
      respawnAndRecover(driver, {
        deathPosition,
        path: { avoidFluids: true },
        retryThroughFluids: true,
      }),
    );

    expect(driver.paths.slice(0, 2)).toEqual([
      objectContaining({
        position: deathPosition,
        policy: objectContaining({ avoidFluids: true }),
      }),
      objectContaining({
        position: deathPosition,
        policy: objectContaining({ avoidFluids: false }),
      }),
    ]);
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: submergedDrop.position,
        policy: objectContaining({ avoidFluids: false }),
      }),
    );
    expect(driver.entityQueries).toContainEqual(
      objectContaining({
        radius: 24,
        selector: {
          entityTypes: ["minecraft:item"],
          alive: true,
        },
      }),
    );
  });

  it("sweeps nearby item entities into pickup range", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({ position });
    const drops = [
      {
        connectionEpoch: "epoch-1",
        networkId: 10,
        entityType: "minecraft:item",
        itemId: "minecraft:mutton",
        position: {
          x: 6,
          y: 64,
          z: -2,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        observedAt: "2026-01-01T00:00:00.000Z",
      },
      {
        connectionEpoch: "epoch-1",
        networkId: 11,
        entityType: "minecraft:sheep",
        position: {
          x: 7,
          y: 64,
          z: -2,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        observedAt: "2026-01-01T00:00:00.000Z",
      },
    ];
    driver.entityQueryResolver = () => (driver.paths.length === 0 ? drops : []);

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        settleDelayMs: 0,
      }),
    );

    expect(driver.entityQueries).toEqual(
      arrayContaining([
        {
          origin: position,
          radius: 8,
          selector: {
            entityTypes: ["minecraft:item"],
            alive: true,
          },
          maximumResults: 16,
        },
      ]),
    );
    expect(driver.paths).toEqual([
      objectContaining({
        position: drops[0]?.position,
        radius: 1.25,
        policy: objectContaining({
          allowPlacing: false,
          maxSearchTimeMs: 5000,
        }),
      }),
    ]);
    expect(driver.maximumActiveControlScopes).toBe(1);
  });

  it("walks directly over a close same-level drop", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 10,
      entityType: "minecraft:item",
      itemId: "minecraft:beef",
      position: {
        x: 5.5,
        y: 64,
        z: -2,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () =>
      driver.actions.some((action) => action.type === "set-movement" && action.forward === true)
        ? []
        : [drop];

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        settleDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([]);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("keeps steering toward a sinking submerged drop", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4.5,
      y: 64,
      z: -2.5,
      dimension: "minecraft:overworld",
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 14,
      entityType: "minecraft:item",
      itemId: "minecraft:salmon",
      position: {
        x: 7.5,
        y: 58,
        z: -2.5,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: -0.1, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    let trackedDrop: BeatGameEntityObservation = drop;
    let submergedPickupPolls = 0;
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = (query) => {
      const movementStarted = driver.actions.some(
        (action) => action.type === "set-movement" && action.forward === true,
      );
      if (!movementStarted || query.selector.networkId === undefined) {
        return [trackedDrop];
      }
      submergedPickupPolls += 1;
      if (submergedPickupPolls >= 4) {
        return [];
      }
      trackedDrop = {
        ...trackedDrop,
        position: {
          ...trackedDrop.position,
          x: trackedDrop.position.x + 0.2,
          y: trackedDrop.position.y - 0.5,
        },
      };
      return [trackedDrop];
    };
    driver.blockQueryResolver = () => [
      blockObservation(
        {
          x: 7,
          y: 58,
          z: -3,
          dimension: "minecraft:overworld",
        },
        { blockId: "minecraft:water" },
      ),
    ];
    driver.xzPathResolver = (x, z, dimension, radius, policy) =>
      Effect.sync(() => {
        driver.xzPaths.push({ x, z, dimension, radius, policy });
        driver.currentObservation = observation({
          position: { x, y: 64, z, dimension },
        });
      });

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:salmon"],
        settleDelayMs: 0,
        path: { avoidFluids: false },
      }),
    );

    const steering = driver.actions.filter((action) => action.type === "look");
    expect(steering).toHaveLength(3);
    expect(steering.every((action) => action.pitch > 0)).toBe(true);
    expect(steering[2]?.yaw ?? 0).not.toBe(steering[0]?.yaw ?? 0);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
      jump: false,
      sneak: false,
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("refreshes and prioritizes nearby item entities after each attempt", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const nearDrop = {
      connectionEpoch: "epoch-1",
      networkId: 10,
      entityType: "minecraft:item",
      itemId: "minecraft:beef",
      position: {
        x: 5,
        y: 62.1,
        z: -2,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    const farDrop = {
      ...nearDrop,
      networkId: 11,
      position: {
        ...nearDrop.position,
        x: 9,
      },
    };
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () =>
      driver.paths.length + driver.xzPaths.length === 0
        ? [farDrop, nearDrop]
        : driver.paths.length + driver.xzPaths.length === 1
          ? [farDrop]
          : [];

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        settleDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([]);
    expect(driver.xzPaths).toEqual([
      objectContaining({
        x: nearDrop.position.x,
        z: nearDrop.position.z,
        dimension: nearDrop.position.dimension,
        radius: 1.25,
      }),
      objectContaining({
        x: farDrop.position.x,
        z: farDrop.position.z,
        dimension: farDrop.position.dimension,
        radius: 1.25,
      }),
    ]);
  });

  it("uses a horizontal goal for a vertically offset drop in water", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 59,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 12,
      entityType: "minecraft:item",
      itemId: "minecraft:salmon",
      position: {
        x: 7,
        y: 62,
        z: -4,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () => (driver.xzPaths.length === 0 ? [drop] : []);

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:salmon"],
        settleDelayMs: 0,
        path: { avoidFluids: false, sprint: true },
      }),
    );

    expect(driver.paths).toHaveLength(0);
    expect(driver.xzPaths).toEqual([
      objectContaining({
        x: drop.position.x,
        z: drop.position.z,
        dimension: drop.position.dimension,
        radius: 1.25,
        policy: objectContaining({
          avoidFluids: false,
          sprint: true,
        }),
      }),
    ]);
  });

  it("dives from shore to collect a submerged drop", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4.5,
      y: 64,
      z: -2.5,
      dimension: "minecraft:overworld",
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 13,
      entityType: "minecraft:item",
      itemId: "minecraft:cod",
      position: {
        x: 7.2,
        y: 62.4,
        z: -2.2,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () =>
      driver.actions.some((action) => action.type === "set-movement" && action.forward === true)
        ? []
        : [drop];
    driver.blockQueryResolver = ({ center }) =>
      Math.floor(center.x) === 7 && Math.floor(center.y) === 62 && Math.floor(center.z) === -3
        ? [
            blockObservation(
              {
                x: 7,
                y: 62,
                z: -3,
                dimension: "minecraft:overworld",
              },
              { blockId: "minecraft:water" },
            ),
          ]
        : [];
    driver.xzPathResolver = (x, z, dimension, radius, policy) =>
      Effect.sync(() => {
        driver.xzPaths.push({ x, z, dimension, radius, policy });
        driver.currentObservation = observation({
          position: { x, y: 64, z, dimension },
        });
      });

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:cod"],
        settleDelayMs: 0,
        path: { avoidFluids: false },
      }),
    );

    expect(driver.xzPaths).toEqual([
      objectContaining({
        x: 7.5,
        z: -2.5,
        dimension: "minecraft:overworld",
        radius: 0.75,
        policy: objectContaining({
          allowPlacing: false,
          avoidFluids: false,
        }),
      }),
    ]);
    const dive = driver.actions.find((action) => action.type === "look");
    expect(dive).toEqual(objectContaining({ type: "look" }));
    expect(dive?.type === "look" ? dive.pitch : 0).toBeGreaterThan(0);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
      jump: false,
      sneak: false,
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("clears a shallow cover block to collect a submerged drop", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4.5,
      y: 63,
      z: -2.5,
      dimension: "minecraft:overworld",
    };
    const drop = {
      connectionEpoch: "epoch-1",
      networkId: 15,
      entityType: "minecraft:item",
      itemId: "minecraft:salmon",
      position: {
        x: 4.5,
        y: 61.75,
        z: -2.5,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    const cover = {
      x: 4,
      y: 62,
      z: -3,
      dimension: "minecraft:overworld",
    } as const;
    let coverCleared = false;
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () =>
      driver.actions.some((action) => action.type === "set-movement" && action.forward === true)
        ? []
        : [drop];
    driver.blockQueryResolver = ({ center }) => {
      const blockPosition = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      if (blockPosition.y === 61) {
        return [
          blockObservation(blockPosition, {
            blockId: "minecraft:water",
            replaceable: true,
            solid: false,
          }),
        ];
      }
      if (blockPosition.y === cover.y) {
        return [
          blockObservation(
            blockPosition,
            coverCleared
              ? {
                  blockId: "minecraft:water",
                  replaceable: true,
                  solid: false,
                }
              : {
                  blockId: "minecraft:ice",
                  replaceable: false,
                  solid: false,
                },
          ),
        ];
      }
      return [
        blockObservation(blockPosition, {
          blockId: "minecraft:air",
          replaceable: true,
          solid: false,
        }),
      ];
    };
    driver.actionObserver = (action) => {
      if (action.type === "dig-block") {
        coverCleared = true;
      }
    };

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:salmon"],
        settleDelayMs: 0,
        path: { allowMining: true, avoidFluids: false },
      }),
    );

    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: cover,
    });
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
      jump: false,
      sneak: false,
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("limits a drop sweep to requested resource items", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const cobblestonePosition = {
      x: 6,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({ position });
    const drops = [
      {
        connectionEpoch: "epoch-1",
        networkId: 10,
        entityType: "minecraft:item",
        itemId: "minecraft:cobblestone",
        position: cobblestonePosition,
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        observedAt: "2026-01-01T00:00:00.000Z",
      },
      {
        connectionEpoch: "epoch-1",
        networkId: 11,
        entityType: "minecraft:item",
        itemId: "minecraft:dirt",
        position: {
          x: 7,
          y: 64,
          z: -2,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        observedAt: "2026-01-01T00:00:00.000Z",
      },
    ];
    driver.entityQueryResolver = () => (driver.paths.length === 0 ? drops : []);

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:cobblestone"],
        settleDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([
      objectContaining({
        position: cobblestonePosition,
        radius: 1.25,
        policy: objectContaining({
          allowPlacing: false,
        }),
      }),
    ]);
  });

  it("does not chase disposable drops down a deep ledge", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 70,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const surfaceDrop = {
      connectionEpoch: "epoch-1",
      networkId: 10,
      entityType: "minecraft:item",
      itemId: "minecraft:dirt",
      position: {
        x: 6,
        y: 69,
        z: -2,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    const deepDrop = {
      ...surfaceDrop,
      networkId: 11,
      position: {
        ...surfaceDrop.position,
        y: 62,
      },
    };
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () => (driver.paths.length === 0 ? [deepDrop, surfaceDrop] : []);

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:dirt"],
        maximumVerticalDistance: 3,
        settleDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          ...surfaceDrop.position,
          y: position.y,
        },
        radius: 1.25,
      }),
    ]);
  });

  it("skips submerged drops when the pickup path avoids fluids", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const submergedDrop = {
      connectionEpoch: "epoch-1",
      networkId: 10,
      entityType: "minecraft:item",
      itemId: "minecraft:dirt",
      position: {
        x: 5.5,
        y: 63.25,
        z: -2.5,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    const dryDrop = {
      ...submergedDrop,
      networkId: 11,
      position: {
        ...submergedDrop.position,
        x: 7.5,
      },
    };
    driver.currentObservation = observation({ position });
    driver.entityQueryResolver = () => (driver.paths.length === 0 ? [submergedDrop, dryDrop] : []);
    driver.blockQueryResolver = (query) =>
      query.center.x === 5.5
        ? [
            blockObservation(
              {
                x: 5,
                y: 63,
                z: -3,
                dimension: "minecraft:overworld",
              },
              { blockId: "minecraft:water" },
            ),
          ]
        : [];

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:dirt"],
        settleDelayMs: 0,
        path: { avoidFluids: true },
      }),
    );

    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          ...dryDrop.position,
          y: position.y,
        },
        radius: 1.25,
        policy: objectContaining({
          avoidFluids: true,
        }),
      }),
    ]);
  });

  it("does not retry an unreachable drop during a pickup sweep", async () => {
    const driver = new FakeBeatGameDriver();
    const position = {
      x: 4,
      y: 64,
      z: -2,
      dimension: "minecraft:overworld",
    };
    const unreachableDrop = {
      connectionEpoch: "epoch-1",
      networkId: 10,
      entityType: "minecraft:item",
      itemId: "minecraft:oak_log",
      position: {
        x: 6.5,
        y: 63.25,
        z: -2.5,
        dimension: "minecraft:overworld",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      observedAt: "2026-01-01T00:00:00.000Z",
    } as const;
    driver.currentObservation = observation({ position });
    driver.entityResults = [unreachableDrop];
    driver.pathResolver = (target, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position: target, radius, policy });
      }).pipe(
        Effect.andThen(
          Effect.fail(
            new BeatGameDriverError({
              operation: "pathfind",
              retryable: true,
              message: "No safe route to drop",
            }),
          ),
        ),
      );

    await Effect.runPromise(
      collectNearbyDrops(driver, {
        itemIds: ["minecraft:oak_log"],
        settleDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([
      {
        position: {
          ...unreachableDrop.position,
          y: position.y,
        },
        radius: 1.25,
        policy: objectContaining({
          maxSearchTimeMs: 5000,
        }),
      },
    ]);
  });

  it("delegates reusable collection to the durable generic task", async () => {
    const driver = new FakeBeatGameDriver();

    await Effect.runPromise(
      collectBlocks(driver, {
        blockIds: ["minecraft:oak_log"],
        count: 4,
        searchRadius: 32,
        requireLineOfSight: true,
        targetYRange: { minimum: 60, maximum: 96 },
        path: { allowMining: false },
      }),
    );

    expect(driver.tasks).toEqual([
      {
        type: "collect-blocks",
        blockIds: ["minecraft:oak_log"],
        tags: [],
        count: 4,
        searchRadius: 32,
        avoidSubmergedTargets: false,
        requireLineOfSight: true,
        targetYRange: { minimum: 60, maximum: 96 },
      },
    ]);
    expect(driver.activeControlScopes).toBe(0);
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("carves and follows each level of a descending staircase", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 4,
      y: 10,
      z: 1,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 2,
      y: 6,
      z: 3,
      dimension: "minecraft:overworld",
    };

    installStaircaseMovementSimulation(driver, from);
    driver.currentObservation = {
      ...driver.currentObservation,
      player: {
        ...driver.currentObservation.player,
        velocity: {
          ...driver.currentObservation.player.velocity,
          y: -0.0784000015258789,
        },
      },
    };
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(
      driver.paths.map(({ position, radius }) => ({
        position,
        radius,
      })),
    ).toEqual([
      { position: feetCenter(from), radius: 0.5 },
      {
        position: feetCenter({ ...from, x: 3, y: 9 }),
        radius: 0.5,
      },
      {
        position: feetCenter({ ...from, x: 3, y: 8, z: 2 }),
        radius: 0.5,
      },
      {
        position: feetCenter({ ...from, x: 2, y: 7, z: 2 }),
        radius: 0.5,
      },
      { position: feetCenter(to), radius: 0.5 },
    ]);
    expect(driver.actions.filter(({ type }) => type === "dig-block")).toHaveLength(12);
    expect(driver.actions.filter(({ type }) => type === "select-item")).toHaveLength(4);
    expect(
      driver.paths
        .slice(1)
        .every(
          ({ policy }) =>
            !policy.allowMining && !policy.allowPlacing && policy.maxFallDistance === 1,
        ),
    ).toBe(true);
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: { ...from, x: 3, y: 11 },
    });
    expect(driver.actions[0]).toMatchObject({
      type: "select-item",
      selector: {
        itemIds: arrayContaining(["minecraft:diamond_pickaxe"]),
      },
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("stops a staircase before it opens into water", async () => {
    const driver = new FakeBeatGameDriver();
    // As in a live run whose staircase broke into a lake bed and flooded.
    const water = { x: 2, y: 15, z: 1, dimension: "minecraft:overworld" };
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      return [
        position.x === water.x && position.y === water.y && position.z === water.z
          ? blockObservation(position, { blockId: "minecraft:water", replaceable: true })
          : blockObservation(position),
      ];
    };
    const from = { x: 4, y: 14, z: 1, dimension: "minecraft:overworld" };
    const to = { x: 0, y: 10, z: 1, dimension: "minecraft:overworld" };
    installStaircaseMovementSimulation(driver, from);

    const error = await Effect.runPromise(Effect.flip(excavateStaircase(driver, { from, to })));

    expect(error).toMatchObject({ code: "fluid_exposed" });
    // Nothing under the water is dug, so it cannot pour into the staircase.
    expect(
      driver.actions.filter(
        (action) =>
          action.type === "dig-block" &&
          action.position.x === water.x &&
          action.position.y === water.y - 1 &&
          action.position.z === water.z,
      ),
    ).toEqual([]);
  });

  it("delegates staircase tread movement to constrained pathfinding", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 0,
      y: 2,
      z: 1,
      dimension: "minecraft:overworld",
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.paths).toEqual([
      objectContaining({
        position: feetCenter(from),
        radius: 0.5,
      }),
      objectContaining({
        position: feetCenter(to),
        radius: 0.5,
        policy: objectContaining({
          allowMining: false,
          allowPlacing: false,
          maxFallDistance: 1,
        }),
      }),
    ]);
    expect(driver.actions.some(({ type }) => type === "set-movement")).toBe(false);
    expect(driver.currentObservation.player.position).toMatchObject({
      y: 2,
      dimension: "minecraft:overworld",
    });
  });

  it("retries staircase excavation after its tool breaks", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 0,
      y: 2,
      z: 1,
      dimension: "minecraft:overworld",
    };
    driver.actionResolver = (action) =>
      action.type === "select-item"
        ? Effect.fail(
            new BeatGameDriverError({
              operation: "select-hotbar-item",
              code: "not_found",
              retryable: false,
              message: "No matching item is available",
            }),
          )
        : Effect.succeed({});
    installStaircaseMovementSimulation(driver, from);

    const result = await Effect.runPromise(
      excavateStaircase(driver, { from, to }).pipe(Effect.result),
    );

    expect(result).toMatchObject({
      _tag: "Failure",
      failure: {
        operation: "select-staircase-tool",
        code: "not_found",
        retryable: true,
      },
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("walks a prepared staircase step directly when pathfinding stalls", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };

    installStaircaseMovementSimulation(driver, from);
    const resolvePath = driver.pathResolver;
    let pathCount = 0;
    driver.pathResolver = (position, radius, policy) => {
      pathCount += 1;
      if (pathCount === 2) {
        return Effect.fail(
          new BeatGameDriverError({
            operation: "pathfind",
            code: "unreachable",
            retryable: true,
            message: "Pathfinding made no progress",
          }),
        );
      }
      return resolvePath(position, radius, policy);
    };

    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
      sneak: false,
    });
    expect(driver.currentObservation.player.position).toMatchObject({
      y: 2,
      dimension: "minecraft:overworld",
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("reopens a staircase tread that collapses during traversal", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    let treadDigs = 0;
    let collapsed = false;
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (collapsed && position.x === to.x && position.y === to.y && position.z === to.z) {
        return [
          blockObservation(position, {
            blockId: "minecraft:gravel",
          }),
        ];
      }
      return [blockObservation(position)];
    };
    driver.actionObserver = (action) => {
      if (
        action.type !== "dig-block" ||
        action.position.x !== to.x ||
        action.position.y !== to.y ||
        action.position.z !== to.z
      ) {
        return;
      }
      treadDigs += 1;
      if (treadDigs === 1) {
        collapsed = true;
        return;
      }
      collapsed = false;
      driver.currentObservation = {
        ...driver.currentObservation,
        player: {
          ...driver.currentObservation.player,
          position: feetCenter(to),
        },
      };
    };

    installStaircaseMovementSimulation(driver, from);
    const resolvePath = driver.pathResolver;
    let pathCount = 0;
    driver.pathResolver = (position, radius, policy) =>
      resolvePath(position, radius, policy).pipe(
        Effect.tap(() =>
          Effect.sync(() => {
            pathCount += 1;
            if (pathCount !== 2) {
              return;
            }
            driver.currentObservation = {
              ...driver.currentObservation,
              player: {
                ...driver.currentObservation.player,
                position: {
                  ...feetCenter(to),
                  y: from.y,
                },
              },
            };
          }),
        ),
      );

    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(treadDigs).toBe(2);
    expect(driver.currentObservation.player.position).toMatchObject({
      x: 1.5,
      y: 2,
      z: 0.5,
    });
  });

  it("absorbs an adjacent staging result into the staircase endpoint", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const requestedTo = {
      x: 0,
      y: 2,
      z: 1,
      dimension: "minecraft:overworld",
    };

    installStaircaseMovementSimulation(driver, from);
    const resolvePath = driver.pathResolver;
    let pathCount = 0;
    driver.pathResolver = (position, radius, policy) =>
      resolvePath(position, radius, policy).pipe(
        Effect.tap(() =>
          Effect.sync(() => {
            pathCount += 1;
            if (pathCount !== 1) {
              return;
            }
            driver.currentObservation = observation({
              position: {
                x: from.x + 1.5,
                y: from.y,
                z: from.z + 0.5,
                dimension: from.dimension,
              },
            });
          }),
        ),
      );

    await Effect.runPromise(
      excavateStaircase(driver, {
        from,
        to: requestedTo,
      }),
    );

    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: {
        x: 1,
        y: 2,
        z: 1,
        dimension: "minecraft:overworld",
      },
    });
    expect(driver.paths.at(-1)).toMatchObject({
      position: requestedTo,
      radius: 2,
    });
  });

  it("continues from a settled fall instead of climbing to a stale start", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const plannedFrom = {
      x: 0,
      y: 12,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const settledFrom = {
      ...plannedFrom,
      y: 8,
    };
    const to = {
      ...plannedFrom,
      y: 6,
      z: 2,
    };

    installStaircaseMovementSimulation(driver, plannedFrom);
    const resolveObservation = driver.observationResolver;
    let observations = 0;
    driver.observationResolver = () =>
      resolveObservation().pipe(
        Effect.map((current) => {
          observations += 1;
          if (observations > 4) {
            return current;
          }
          const falling = observations === 1;
          const position = falling ? { ...settledFrom, y: 10 } : settledFrom;
          driver.currentObservation = {
            ...current,
            player: {
              ...current.player,
              position: {
                ...position,
                x: position.x + 0.5,
                z: position.z + 0.5,
              },
              velocity: {
                ...current.player.velocity,
                y: falling ? -1 : 0,
              },
            },
          };
          return driver.currentObservation;
        }),
      );

    await Effect.runPromise(
      excavateStaircase(driver, {
        from: plannedFrom,
        to,
      }),
    );

    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: plannedFrom,
      }),
    );
    expect(driver.paths[0]).toMatchObject({
      position: feetCenter({
        ...settledFrom,
        y: 7,
        z: 1,
      }),
      radius: 0.5,
    });
  });

  it("hands off to normal pathfinding after breaking into a lower room", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 5,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const firstStep = {
      ...from,
      y: 4,
      z: 1,
    };
    const to = {
      ...from,
      y: 3,
      z: 2,
    };

    installStaircaseMovementSimulation(driver, from);
    const resolvePath = driver.pathResolver;
    driver.pathResolver = (position, radius, policy) => {
      const centeredFirstStep = feetCenter(firstStep);
      if (
        position.x === centeredFirstStep.x &&
        position.y === centeredFirstStep.y &&
        position.z === centeredFirstStep.z &&
        !policy.allowMining
      ) {
        return Effect.sync(() => {
          driver.paths.push({ position, radius, policy });
          driver.currentObservation = {
            ...driver.currentObservation,
            player: {
              ...driver.currentObservation.player,
              position: {
                ...position,
                y: position.y - 3,
              },
            },
          };
        }).pipe(
          Effect.andThen(
            Effect.fail(
              new BeatGameDriverError({
                operation: "pathfind",
                retryable: true,
                message: "fell into an existing room",
              }),
            ),
          ),
        );
      }
      return resolvePath(position, radius, policy);
    };

    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.paths).toContainEqual(
      objectContaining({
        position: feetCenter(firstStep),
        radius: 0.5,
        policy: objectContaining({
          allowMining: false,
          allowPlacing: false,
        }),
      }),
    );
    expect(driver.paths.at(-1)).toMatchObject({
      position: to,
      radius: 4,
      policy: objectContaining({
        allowMining: true,
        allowPlacing: true,
      }),
    });
  });

  it("uses a broad detour when the staircase is deeper than its span", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ center }) => [blockObservation(queriedBlockPosition(center))];
    const from = {
      x: 0,
      y: 10,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 0,
      y: 4,
      z: 2,
      dimension: "minecraft:overworld",
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.paths).toHaveLength(7);
    expect(driver.paths[0]?.position).toEqual(feetCenter(from));
    expect(driver.paths.at(-1)?.position).toEqual(feetCenter(to));
    expect(
      driver.paths
        .slice(1)
        .every(
          ({ policy }) =>
            !policy.allowMining && !policy.allowPlacing && policy.maxFallDistance === 1,
        ),
    ).toBe(true);
  });

  it("rejects a missing staircase floor without a reachable lower anchor", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: 0 };
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 2 },
    });
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (
        (position.x === support.x && position.y === support.y && position.z === support.z) ||
        (position.x === lowerAnchor.x &&
          position.y === lowerAnchor.y &&
          position.z === lowerAnchor.z)
      ) {
        return [
          blockObservation(position, {
            blockId: "minecraft:air",
            diggable: true,
            replaceable: true,
            solid: false,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    const result = await Effect.runPromise(
      excavateStaircase(driver, { from, to }).pipe(Effect.result),
    );

    expect(result).toMatchObject({
      _tag: "Failure",
      failure: { code: "unsupported_opening", retryable: true },
    });
    expect(driver.actions).not.toContainEqual(
      objectContaining({
        type: "place-block",
      }),
    );
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: feetCenter(to),
      }),
    );
  });

  it("builds a missing staircase support from a lower anchor first", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: 0 };
    let supportPlaced = false;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 1 },
    });
    driver.actionObserver = (action) => {
      if (
        action.type === "place-block" &&
        action.against.x === lowerAnchor.x &&
        action.against.y === lowerAnchor.y &&
        action.against.z === lowerAnchor.z &&
        action.face === "up"
      ) {
        supportPlaced = true;
      }
    };
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (position.x === support.x && position.y === support.y && position.z === support.z) {
        if (selector.replaceable === false && !supportPlaced) {
          return [];
        }
        return [
          blockObservation(
            support,
            supportPlaced
              ? { blockId: "minecraft:cobblestone" }
              : {
                  blockId: "minecraft:air",
                  replaceable: true,
                },
          ),
        ];
      }
      if (position.x === to.x && position.y === to.y && position.z === to.z) {
        return [
          blockObservation(to, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: lowerAnchor,
      face: "up",
      hand: "main",
    });
    expect(driver.actions).not.toContainEqual({
      type: "place-block",
      against: to,
      face: "down",
      hand: "main",
    });
  });

  it("opens a staircase step before replacing its gravel support", async () => {
    const driver = new FakeBeatGameDriver();
    const from = { x: 0, y: 3, z: 0, dimension: "minecraft:overworld" };
    const to = { x: 1, y: 2, z: 0, dimension: "minecraft:overworld" };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: 0 };
    const dug = new Set<string>();
    let supportPlaced = false;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 4 },
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (blockKey(position) === blockKey(support)) {
        if (supportPlaced) {
          return [blockObservation(support, { blockId: "minecraft:cobblestone" })];
        }
        if (dug.has(blockKey(support))) {
          return selector.replaceable === false
            ? []
            : [blockObservation(support, { blockId: "minecraft:air", replaceable: true })];
        }
        return [blockObservation(support, { blockId: "minecraft:gravel" })];
      }
      if (dug.has(blockKey(position))) {
        return selector.replaceable === false
          ? []
          : [blockObservation(position, { blockId: "minecraft:air", replaceable: true })];
      }
      return [blockObservation(position)];
    };
    installStaircaseMovementSimulation(driver, from);
    const resolveAction = driver.actionResolver;
    driver.actionResolver = (action) => {
      if (action.type === "dig-block") {
        dug.add(blockKey(action.position));
      }
      // From the previous step, the undug step hides the anchor's top face.
      if (action.type === "place-block" && blockKey(action.against) === blockKey(lowerAnchor)) {
        if (!dug.has(blockKey(to))) {
          return Effect.fail(
            new BeatGameDriverError({
              operation: "place-block",
              code: "FAILED_PRECONDITION",
              retryable: false,
              message: "Block placement is not ready (FACE_OCCLUDED)",
            }),
          );
        }
        supportPlaced = true;
      }
      return resolveAction(action);
    };

    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(supportPlaced).toBe(true);
  });

  it("does not enter replaceable fluid in a staircase", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (position.y < to.y) {
        return [blockObservation(position)];
      }
      return [
        blockObservation(position, {
          blockId: position.y === to.y + 2 ? "minecraft:cave_air" : "minecraft:water",
          replaceable: true,
        }),
      ];
    };

    installStaircaseMovementSimulation(driver, from);
    const result = await Effect.runPromise(
      excavateStaircase(driver, { from, to }).pipe(Effect.result),
    );

    expect(driver.actions).not.toContainEqual(
      objectContaining({
        type: "dig-block",
      }),
    );
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: feetCenter(to),
      }),
    );
    expect(result).toMatchObject({
      _tag: "Failure",
      failure: {
        code: "fluid_exposed",
        retryable: true,
      },
    });
  });

  it("stops before entering fluid exposed while digging", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const ceiling = { ...to, y: to.y + 2 };
    let ceilingOpened = false;
    driver.actionObserver = (action) => {
      if (
        action.type === "dig-block" &&
        action.position.x === ceiling.x &&
        action.position.y === ceiling.y &&
        action.position.z === ceiling.z
      ) {
        ceilingOpened = true;
      }
    };
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (
        ceilingOpened &&
        position.x === ceiling.x &&
        position.y === ceiling.y &&
        position.z === ceiling.z
      ) {
        return [
          blockObservation(position, {
            blockId: "minecraft:water",
            diggable: false,
            replaceable: true,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    const result = await Effect.runPromise(
      excavateStaircase(driver, { from, to }).pipe(Effect.result),
    );

    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: ceiling,
    });
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: feetCenter(to),
      }),
    );
    expect(driver.currentObservation.player.position).toMatchObject({
      x: from.x + 0.5,
      y: from.y,
      z: from.z + 0.5,
    });
    expect(result).toMatchObject({
      _tag: "Failure",
      failure: {
        code: "fluid_exposed",
        retryable: true,
      },
    });
  });

  it("stops at the current safe step before an unsupported opening", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 4,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const firstStep = { ...from, x: 1, y: 3 };
    const to = { ...from, x: 2, y: 2 };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: 0 };
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 2 },
    });
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (
        (position.x === support.x && position.y === support.y && position.z === support.z) ||
        (position.x === lowerAnchor.x &&
          position.y === lowerAnchor.y &&
          position.z === lowerAnchor.z)
      ) {
        return [
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
            solid: false,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    const result = await Effect.runPromise(
      excavateStaircase(driver, { from, to }).pipe(Effect.result),
    );

    expect(result).toMatchObject({
      _tag: "Failure",
      failure: { code: "unsupported_opening", retryable: true },
    });
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: feetCenter(firstStep),
      }),
    );
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: feetCenter(to),
      }),
    );
    expect(driver.actions).not.toContainEqual(
      objectContaining({
        type: "place-block",
      }),
    );
  });

  it("hands an open structure interior back to ordinary pathfinding", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 2 },
    });
    driver.blockQueryResolver = ({ center }) => {
      const position = queriedBlockPosition(center);
      if (
        (position.x === to.x && position.y === to.y && position.z === to.z) ||
        (position.x === support.x && position.y === support.y && position.z === support.z)
      ) {
        return [
          blockObservation(position, {
            blockId: "minecraft:cave_air",
            replaceable: true,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(
      excavateStaircase(driver, {
        from,
        to,
        openSpaceHandoffRadius: 1,
      }),
    );

    expect(driver.paths).toContainEqual(
      objectContaining({
        position: to,
        radius: 1,
        policy: objectContaining({
          allowMining: true,
          allowPlacing: true,
        }),
      }),
    );
    expect(driver.actions).not.toContainEqual(
      objectContaining({
        type: "place-block",
      }),
    );
  });

  it("continues excavating through open space far above the destination", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 12,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 10,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const firstStep = { ...from, x: 1, y: 11 };
    const firstSupport = { ...firstStep, y: 10 };
    const lowerAnchor = { ...firstSupport, y: 9 };
    let supportPlaced = false;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 2 },
    });
    driver.actionObserver = (action) => {
      if (
        action.type === "place-block" &&
        action.against.x === lowerAnchor.x &&
        action.against.y === lowerAnchor.y &&
        action.against.z === lowerAnchor.z &&
        action.face === "up"
      ) {
        supportPlaced = true;
      }
    };
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (position.x === firstStep.x && position.y === firstStep.y && position.z === firstStep.z) {
        return [
          blockObservation(position, {
            blockId: "minecraft:cave_air",
            replaceable: true,
          }),
        ];
      }
      if (
        position.x === firstSupport.x &&
        position.y === firstSupport.y &&
        position.z === firstSupport.z
      ) {
        if (selector.replaceable === false && !supportPlaced) {
          return [];
        }
        return [
          blockObservation(
            position,
            supportPlaced
              ? { blockId: "minecraft:cobblestone" }
              : {
                  blockId: "minecraft:cave_air",
                  replaceable: true,
                },
          ),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(
      excavateStaircase(driver, {
        from,
        to,
        openSpaceHandoffRadius: 1,
      }),
    );

    expect(supportPlaced).toBe(true);
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        radius: 1,
      }),
    );
    expect(driver.paths.at(-1)?.position).toEqual(feetCenter(to));
  });

  it("reselects and retries a transient staircase support placement", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: support.y - 1 };
    let placementAttempts = 0;
    let supportPlaced = false;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 8 },
    });
    driver.actionResolver = (action) => {
      if (
        action.type !== "place-block" ||
        action.face !== "up" ||
        action.against.x !== lowerAnchor.x ||
        action.against.y !== lowerAnchor.y ||
        action.against.z !== lowerAnchor.z
      ) {
        return Effect.void;
      }
      placementAttempts += 1;
      if (placementAttempts === 1) {
        return Effect.fail(
          new BeatGameDriverError({
            operation: "place-block",
            retryable: true,
            message: "transient placement rejection",
          }),
        );
      }
      supportPlaced = true;
      return Effect.void;
    };
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (position.x === support.x && position.y === support.y - 1 && position.z === support.z) {
        return [blockObservation(position)];
      }
      if (position.x === support.x && position.y === support.y && position.z === support.z) {
        if (selector.replaceable === false && !supportPlaced) {
          return [];
        }
        return [
          blockObservation(
            support,
            supportPlaced
              ? { blockId: "minecraft:cobblestone" }
              : {
                  blockId: "minecraft:air",
                  replaceable: true,
                },
          ),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(placementAttempts).toBe(2);
    expect(
      driver.actions.filter(
        (action) =>
          action.type === "select-item" &&
          action.selector.itemIds?.includes("minecraft:cobblestone"),
      ),
    ).toHaveLength(2);
  });

  it("clears gravel restored during staircase support placement", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    const lowerAnchor = { ...support, y: support.y - 1 };
    let supportState: "air" | "cobblestone" | "gravel" = "gravel";
    let placementAttempts = 0;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 8 },
    });
    driver.actionResolver = (action) => {
      if (
        action.type === "dig-block" &&
        action.position.x === support.x &&
        action.position.y === support.y &&
        action.position.z === support.z
      ) {
        supportState = "air";
        return Effect.void;
      }
      if (
        action.type !== "place-block" ||
        action.face !== "up" ||
        action.against.x !== lowerAnchor.x ||
        action.against.y !== lowerAnchor.y ||
        action.against.z !== lowerAnchor.z
      ) {
        return Effect.void;
      }
      placementAttempts += 1;
      if (placementAttempts === 1) {
        supportState = "gravel";
        return Effect.fail(
          new BeatGameDriverError({
            operation: "place-block",
            retryable: true,
            message: "gravel fell back into the support position",
          }),
        );
      }
      supportState = "cobblestone";
      return Effect.void;
    };
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (position.x !== support.x || position.y !== support.y || position.z !== support.z) {
        return [blockObservation(position)];
      }
      if (
        (selector.replaceable === true && supportState !== "air") ||
        (selector.replaceable === false && supportState === "air")
      ) {
        return [];
      }
      return [
        blockObservation(support, {
          blockId: supportState === "air" ? "minecraft:air" : `minecraft:${supportState}`,
          replaceable: supportState === "air",
        }),
      ];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(placementAttempts).toBe(2);
    expect(
      driver.actions.filter(
        (action) =>
          action.type === "dig-block" &&
          action.position.x === support.x &&
          action.position.y === support.y &&
          action.position.z === support.z,
      ),
    ).toHaveLength(2);
    expect(supportState).toBe("cobblestone");
  });

  it("replaces a gravity-affected staircase floor before stepping on it", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 0,
      y: 3,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const to = {
      x: 1,
      y: 2,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const support = { ...to, y: 1 };
    let supportIsGravel = true;
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 1 },
    });
    driver.actionObserver = (action) => {
      if (action.type === "dig-block" && action.position.y === support.y) {
        supportIsGravel = false;
      }
    };
    driver.blockQueryResolver = ({ center, selector }) => {
      const position = queriedBlockPosition(center);
      if (position.x === support.x && position.y === support.y && position.z === support.z) {
        if (supportIsGravel) {
          return [
            blockObservation(support, {
              blockId: "minecraft:gravel",
            }),
          ];
        }
        return [
          blockObservation(support, {
            blockId: selector.replaceable === false ? "minecraft:cobblestone" : "minecraft:air",
            replaceable: selector.replaceable !== false,
          }),
        ];
      }
      return [blockObservation(position)];
    };

    installStaircaseMovementSimulation(driver, from);
    await Effect.runPromise(excavateStaircase(driver, { from, to }));

    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: support,
    });
    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: { ...support, y: support.y - 1 },
      face: "up",
      hand: "main",
    });
  });

  it("derives an eye sample from ordinary item use and entity observation", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      position: { x: 10, z: 20 },
    });
    driver.entityResults = [
      {
        connectionEpoch: "epoch-1",
        networkId: 42,
        entityType: "minecraft:eye_of_ender",
        position: {
          x: 20,
          y: 68,
          z: 30,
          dimension: "minecraft:overworld",
        },
        velocity: { x: 0.4, y: 0.2, z: 0.4 },
        alive: true,
        observedAt: "2026-01-01T00:00:01.000Z",
      },
    ];

    const sample = await Effect.runPromise(
      throwEyeOfEnder(driver, {
        observationDelayMs: 1,
      }),
    );

    expect(sample.origin.x).toBe(10);
    expect(sample.direction.x).toBeCloseTo(Math.SQRT1_2);
    expect(sample.direction.z).toBeCloseTo(Math.SQRT1_2);
    expect(driver.actions.map(({ type }) => type)).toEqual(["select-item", "look", "use-item"]);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("expands craftable recipe dependencies before the requested item", async () => {
    const driver = new FakeBeatGameDriver();
    let planksCrafted = false;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:stick") {
        return [
          {
            recipeId: "stick",
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: ["minecraft:oak_planks"],
                tags: [],
                count: 2,
              },
            ],
          },
        ];
      }
      if (resultItemId === "minecraft:oak_planks") {
        return [
          {
            recipeId: "planks",
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: ["minecraft:oak_log"],
                tags: [],
                count: 1,
              },
            ],
          },
        ];
      }
      return [];
    };
    driver.craftabilityResolver = (recipeId) => {
      if (recipeId === "stick" && !planksCrafted) {
        return {
          canCraft: false,
          maximumCraftCount: 0,
          missing: [
            {
              itemIds: ["minecraft:oak_planks"],
              tags: [],
              available: 0,
              missing: 2,
            },
          ],
        };
      }
      return {
        canCraft: true,
        maximumCraftCount: 64,
        missing: [],
      };
    };
    driver.taskObserver = (task) => {
      if (task.type === "craft" && task.recipeId === "planks") {
        planksCrafted = true;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:stick",
        count: 4,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      {
        type: "craft",
        recipeId: "planks",
        count: 1,
      },
      {
        type: "craft",
        recipeId: "stick",
        count: 1,
      },
    ]);
  });

  it("re-expands dependencies consumed by another recipe ingredient", async () => {
    const driver = new FakeBeatGameDriver();
    let logs = 2;
    let planks = 0;
    let sticks = 0;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:wooden_pickaxe") {
        return [
          recipe("pickaxe", resultItemId, 1, [
            { itemIds: ["minecraft:oak_planks"], count: 3 },
            { itemIds: ["minecraft:stick"], count: 2 },
          ]),
        ];
      }
      if (resultItemId === "minecraft:oak_planks") {
        return [recipe("planks", resultItemId, 4, [{ itemIds: ["minecraft:oak_log"], count: 1 }])];
      }
      if (resultItemId === "minecraft:stick") {
        return [
          recipe("sticks", resultItemId, 4, [{ itemIds: ["minecraft:oak_planks"], count: 2 }]),
        ];
      }
      return [];
    };
    driver.craftabilityResolver = (recipeId) => {
      if (recipeId === "planks") {
        return logs >= 1
          ? { canCraft: true, maximumCraftCount: logs, missing: [] }
          : {
              canCraft: false,
              maximumCraftCount: 0,
              missing: [
                {
                  itemIds: ["minecraft:oak_log"],
                  tags: [],
                  available: logs,
                  missing: 1,
                },
              ],
            };
      }
      if (recipeId === "sticks") {
        return planks >= 2
          ? {
              canCraft: true,
              maximumCraftCount: Math.floor(planks / 2),
              missing: [],
            }
          : {
              canCraft: false,
              maximumCraftCount: 0,
              missing: [
                {
                  itemIds: ["minecraft:oak_planks"],
                  tags: [],
                  available: planks,
                  missing: 2 - planks,
                },
              ],
            };
      }
      const missing = [
        ...(planks >= 3
          ? []
          : [
              {
                itemIds: ["minecraft:oak_planks"],
                tags: [],
                available: planks,
                missing: 3 - planks,
              },
            ]),
        ...(sticks >= 2
          ? []
          : [
              {
                itemIds: ["minecraft:stick"],
                tags: [],
                available: sticks,
                missing: 2 - sticks,
              },
            ]),
      ];
      return {
        canCraft: missing.length === 0,
        maximumCraftCount: missing.length === 0 ? 1 : 0,
        missing,
      };
    };
    driver.taskObserver = (task) => {
      if (task.type !== "craft") {
        return;
      }
      if (task.recipeId === "planks") {
        logs -= 1;
        planks += 4;
      } else if (task.recipeId === "sticks") {
        planks -= 2;
        sticks += 4;
      } else if (task.recipeId === "pickaxe") {
        planks -= 3;
        sticks -= 2;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:wooden_pickaxe",
        count: 1,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      { type: "craft", recipeId: "planks", count: 1 },
      { type: "craft", recipeId: "sticks", count: 1 },
      { type: "craft", recipeId: "planks", count: 1 },
      { type: "craft", recipeId: "pickaxe", count: 1 },
    ]);
  });

  it("combines alternative recipe ingredients from multiple item variants", async () => {
    const driver = new FakeBeatGameDriver();
    let birchLogs = 1;
    let oakLogs = 1;
    let birchPlanks = 0;
    let oakPlanks = 0;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:shield") {
        return [
          {
            recipeId: "shield",
            recipeType: "crafting",
            resultItemId,
            resultCount: 1,
            ingredients: [
              {
                itemIds: ["minecraft:birch_planks", "minecraft:oak_planks"],
                tags: ["minecraft:planks"],
                count: 6,
              },
            ],
          },
        ];
      }
      if (resultItemId === "minecraft:birch_planks" || resultItemId === "minecraft:oak_planks") {
        const logItemId =
          resultItemId === "minecraft:birch_planks" ? "minecraft:birch_log" : "minecraft:oak_log";
        return [
          {
            recipeId: resultItemId,
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: [logItemId],
                tags: [],
                count: 1,
              },
            ],
          },
        ];
      }
      return [];
    };
    driver.craftabilityResolver = (recipeId, operations) => {
      if (recipeId === "minecraft:birch_planks") {
        return birchLogs >= operations
          ? {
              canCraft: true,
              maximumCraftCount: birchLogs,
              missing: [],
            }
          : {
              canCraft: false,
              maximumCraftCount: birchLogs,
              missing: [
                {
                  itemIds: ["minecraft:birch_log"],
                  tags: [],
                  available: birchLogs,
                  missing: operations - birchLogs,
                },
              ],
            };
      }
      if (recipeId === "minecraft:oak_planks") {
        return oakLogs >= operations
          ? {
              canCraft: true,
              maximumCraftCount: oakLogs,
              missing: [],
            }
          : {
              canCraft: false,
              maximumCraftCount: oakLogs,
              missing: [
                {
                  itemIds: ["minecraft:oak_log"],
                  tags: [],
                  available: oakLogs,
                  missing: operations - oakLogs,
                },
              ],
            };
      }
      const planks = birchPlanks + oakPlanks;
      return planks >= 6
        ? { canCraft: true, maximumCraftCount: 1, missing: [] }
        : {
            canCraft: false,
            maximumCraftCount: 0,
            missing: [
              {
                itemIds: ["minecraft:birch_planks", "minecraft:oak_planks"],
                tags: ["minecraft:planks"],
                available: planks,
                missing: 6 - planks,
              },
            ],
          };
    };
    driver.taskObserver = (task) => {
      if (task.type !== "craft") {
        return;
      }
      if (task.recipeId === "minecraft:birch_planks") {
        birchLogs -= task.count;
        birchPlanks += task.count * 4;
      } else if (task.recipeId === "minecraft:oak_planks") {
        oakLogs -= task.count;
        oakPlanks += task.count * 4;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:shield",
        count: 1,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      {
        type: "craft",
        recipeId: "minecraft:birch_planks",
        count: 1,
      },
      {
        type: "craft",
        recipeId: "minecraft:oak_planks",
        count: 1,
      },
      { type: "craft", recipeId: "shield", count: 1 },
    ]);
  });

  it("classifies unavailable recipe ingredients as resource exhaustion", async () => {
    const driver = new FakeBeatGameDriver();
    driver.recipeResolver = (resultItemId) =>
      resultItemId === "minecraft:fishing_rod"
        ? [
            {
              recipeId: "minecraft:fishing_rod",
              recipeType: "crafting",
              resultItemId,
              resultCount: 1,
              ingredients: [],
            },
          ]
        : [];
    driver.craftabilityResolver = () => ({
      canCraft: false,
      maximumCraftCount: 0,
      missing: [
        {
          itemIds: ["minecraft:stick"],
          tags: [],
          available: 0,
          missing: 3,
        },
      ],
    });

    const result = await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:fishing_rod",
        count: 1,
      }).pipe(Effect.result),
    );

    assert(result._tag === "Failure");
    expect(result.failure.code).toBe("resource-exhausted");
  });

  it("unlocks the crafting table recipe by producing planks first", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      counts: { "minecraft:oak_log": 1 },
    });
    let planksCrafted = false;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:oak_planks") {
        return [
          {
            recipeId: "planks",
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: ["minecraft:oak_log"],
                tags: [],
                count: 1,
              },
            ],
          },
        ];
      }
      if (resultItemId === "minecraft:crafting_table" && planksCrafted) {
        return [
          {
            recipeId: "crafting-table",
            recipeType: "crafting",
            resultItemId,
            resultCount: 1,
            ingredients: [
              {
                itemIds: ["minecraft:oak_planks"],
                tags: [],
                count: 4,
              },
            ],
          },
        ];
      }
      return [];
    };
    driver.craftabilityResolver = () => ({
      canCraft: true,
      maximumCraftCount: 64,
      missing: [],
    });
    driver.taskObserver = (task) => {
      if (task.type === "craft" && task.recipeId === "planks") {
        planksCrafted = true;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:crafting_table",
        count: 1,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      {
        type: "craft",
        recipeId: "planks",
        count: 1,
      },
      {
        type: "craft",
        recipeId: "crafting-table",
        count: 1,
      },
    ]);
  });

  it("unlocks the wooden pickaxe recipe by producing sticks first", async () => {
    const driver = new FakeBeatGameDriver();
    let sticksCrafted = false;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:stick") {
        return [
          {
            recipeId: "stick",
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: ["minecraft:spruce_planks"],
                tags: [],
                count: 2,
              },
            ],
          },
        ];
      }
      if (resultItemId === "minecraft:wooden_pickaxe" && sticksCrafted) {
        return [
          {
            recipeId: "wooden-pickaxe",
            recipeType: "crafting",
            resultItemId,
            resultCount: 1,
            ingredients: [
              {
                itemIds: ["minecraft:spruce_planks"],
                tags: [],
                count: 3,
              },
              {
                itemIds: ["minecraft:stick"],
                tags: [],
                count: 2,
              },
            ],
          },
        ];
      }
      return [];
    };
    driver.craftabilityResolver = () => ({
      canCraft: true,
      maximumCraftCount: 64,
      missing: [],
    });
    driver.taskObserver = (task) => {
      if (task.type === "craft" && task.recipeId === "stick") {
        sticksCrafted = true;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:wooden_pickaxe",
        count: 1,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      {
        type: "craft",
        recipeId: "stick",
        count: 1,
      },
      {
        type: "craft",
        recipeId: "wooden-pickaxe",
        count: 1,
      },
    ]);
  });

  it("unlocks the wooden sword recipe by producing a stick first", async () => {
    const driver = new FakeBeatGameDriver();
    let sticksCrafted = false;
    driver.recipeResolver = (resultItemId) => {
      if (resultItemId === "minecraft:stick") {
        return [
          {
            recipeId: "stick",
            recipeType: "crafting",
            resultItemId,
            resultCount: 4,
            ingredients: [
              {
                itemIds: ["minecraft:oak_planks"],
                tags: [],
                count: 2,
              },
            ],
          },
        ];
      }
      if (resultItemId === "minecraft:wooden_sword" && sticksCrafted) {
        return [
          {
            recipeId: "wooden-sword",
            recipeType: "crafting",
            resultItemId,
            resultCount: 1,
            ingredients: [
              {
                itemIds: ["minecraft:oak_planks"],
                tags: [],
                count: 2,
              },
              {
                itemIds: ["minecraft:stick"],
                tags: [],
                count: 1,
              },
            ],
          },
        ];
      }
      return [];
    };
    driver.craftabilityResolver = () => ({
      canCraft: true,
      maximumCraftCount: 64,
      missing: [],
    });
    driver.taskObserver = (task) => {
      if (task.type === "craft" && task.recipeId === "stick") {
        sticksCrafted = true;
      }
    };

    await Effect.runPromise(
      craftItem(driver, {
        resultItemId: "minecraft:wooden_sword",
        count: 1,
      }),
    );

    expect(driver.tasks.filter(({ type }) => type === "craft")).toEqual([
      {
        type: "craft",
        recipeId: "stick",
        count: 1,
      },
      {
        type: "craft",
        recipeId: "wooden-sword",
        count: 1,
      },
    ]);
  });

  it("keeps portal geometry in TypeScript", () => {
    const frame = createNetherPortalFrame(
      {
        x: 0,
        y: 64,
        z: 0,
        dimension: "minecraft:overworld",
      },
      "z",
    );

    expect(frame.blocks.every(({ dimension }) => dimension === "minecraft:overworld")).toBe(true);
    expect(frame.blocks.some(({ z }) => z === 3)).toBe(true);
  });

  it("builds a minimal Nether frame from verified primitive placements", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const world = installPortalWorld(driver, frame);

    await Effect.runPromise(
      buildNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(driver.tasks).toHaveLength(0);
    expect(
      frame.blocks.every(
        (position) => world.blocks.get(world.key(position))?.blockId === "minecraft:obsidian",
      ),
    ).toBe(true);
    expect(driver.actions.filter(({ type }) => type === "place-block")).toHaveLength(13);
    expect(driver.paths.every(({ policy }) => !policy.allowMining && !policy.allowPlacing)).toBe(
      true,
    );
    expect(driver.paths.every(({ radius }) => radius === 1)).toBe(true);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("retries a rejected portal block placement at the same origin", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const initiallyRejected = frame.blocks[0];
    if (initiallyRejected === undefined) {
      throw new Error("Expected a portal frame block");
    }
    const world = installPortalWorld(driver, frame, {
      rejectFirstPlacementAt: initiallyRejected,
    });

    await Effect.runPromise(
      buildNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(world.placementAttempts.get(world.key(initiallyRejected))).toBe(2);
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: {
          x: 11,
          y: 65,
          z: 19,
          dimension: "minecraft:overworld",
        },
        radius: 1,
      }),
    );
  });

  it("retries a portal placement rejected by the primitive RPC", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const world = installPortalWorld(driver, frame);
    let rejected = false;
    driver.actionResolver = (action) => {
      if (action.type === "place-block" && !rejected) {
        rejected = true;
        return Effect.fail(
          new BeatGameDriverError({
            operation: "placeBlock",
            retryable: true,
            message: "The held item could not be used on the target block",
          }),
        );
      }
      return Effect.sync(() => {
        driver.actionObserver(action);
        return {};
      });
    };

    await Effect.runPromise(
      buildNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(rejected).toBe(true);
    expect(
      frame.blocks.every(
        (position) => world.blocks.get(world.key(position))?.blockId === "minecraft:obsidian",
      ),
    ).toBe(true);
  });

  it("clears build scaffolding from the portal before ignition", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const scaffold = frame.interior[0];
    if (scaffold === undefined) {
      throw new Error("Expected a portal interior");
    }
    installPortalWorld(driver, frame, {
      initialBlocks: [blockObservation(scaffold, { blockId: "minecraft:cobblestone" })],
    });

    await Effect.runPromise(buildNetherPortal(driver, { origin }));

    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: scaffold,
    });
    expect(driver.actions.findIndex(({ type }) => type === "dig-block")).toBeLessThan(
      driver.actions.findIndex(({ type }) => type === "interact-block"),
    );
  });

  it("cancels portal navigation when the dimension changes", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    let observations = 0;
    driver.observationResolver = () =>
      Effect.sync(() => {
        observations += 1;
        const dimension = observations > 1 ? "minecraft:the_nether" : "minecraft:overworld";
        return observation({
          dimension,
          position: {
            ...portal,
            x: portal.x + 1,
            z: portal.z - 2.5,
            dimension,
          },
        });
      });
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
      }).pipe(Effect.andThen(Effect.never));

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths).toHaveLength(1);
    expect(observations).toBeGreaterThan(1);
    expect(driver.actions).toContainEqual({ type: "reset-movement" });
  });

  it("leaves the destination portal on its supported side", async () => {
    const driver = new FakeBeatGameDriver();
    const sourcePortal = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const destinationPortal = {
      x: 10,
      y: 64,
      z: 0,
      dimension: "minecraft:the_nether",
    };
    let current = observation({
      position: { ...sourcePortal, x: 11, z: 17.5 },
    });
    const sourceBlocks = [sourcePortal.x, sourcePortal.x + 1].map((x) =>
      blockObservation(
        { ...sourcePortal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    const destinationBlocks = [destinationPortal.x, destinationPortal.x + 1].map((x) =>
      blockObservation(
        { ...destinationPortal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    const safeExit = {
      x: 11,
      y: 64,
      z: -1,
      dimension: "minecraft:the_nether",
    };
    const exitBlocks = [
      blockObservation(
        { ...safeExit, y: safeExit.y - 1 },
        {
          blockId: "minecraft:netherrack",
          replaceable: false,
          solid: true,
        },
      ),
      blockObservation(safeExit, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...safeExit, y: safeExit.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation(
        { ...safeExit, z: 1, y: safeExit.y - 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation(
        { ...safeExit, z: 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation(
        { ...safeExit, z: 1, y: safeExit.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
    ];
    driver.blockQueryResolver = ({ center, selector }) =>
      selector.blockIds?.includes("minecraft:nether_portal") === true
        ? center.dimension === "minecraft:the_nether"
          ? destinationBlocks
          : sourceBlocks
        : exitBlocks;
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        current = observation({ position });
      });
    driver.actionObserver = (action) => {
      if (action.type === "look") {
        current = observation({
          dimension: current.player.position.dimension,
          position: current.player.position,
          rotation: { yaw: action.yaw, pitch: action.pitch },
        });
      }
      if (action.type === "set-movement" && action.forward === true && action.sprint === false) {
        current = observation({
          dimension: "minecraft:the_nether",
          position: {
            ...destinationPortal,
            x: 11,
            z: 0.5,
            dimension: "minecraft:the_nether",
          },
        });
      }
      if (action.type === "set-movement" && action.forward === true && action.sprint === true) {
        current = observation({
          dimension: "minecraft:the_nether",
          position: {
            ...destinationPortal,
            x: 11,
            z: -1.2,
            dimension: "minecraft:the_nether",
          },
        });
      }
    };
    driver.observationResolver = () => Effect.succeed(current);

    await Effect.runPromise(enterPortal(driver, { portal: sourcePortal }));

    expect(current.player.position).toMatchObject({
      dimension: "minecraft:the_nether",
      z: -1.2,
    });
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: true,
    });
    const finalLook = driver.actions.findLast(({ type }) => type === "look");
    expect(finalLook).toMatchObject({ type: "look", yaw: 0 });
  });

  it("prefers observed portal geometry over a conflicting axis property", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:the_nether",
    };
    driver.currentObservation = observation({
      dimension: "minecraft:the_nether",
      position: { ...portal, x: 10.5, z: 17.5 },
    });
    driver.blockResults = [portal.x, portal.x + 1].map((x) => ({
      blockId: "minecraft:nether_portal",
      position: { ...portal, x },
      properties: { axis: "z" },
      diggable: false,
      replaceable: false,
      interactive: false,
      observedAt: "2026-01-01T00:00:01.000Z",
    }));
    driver.observationResolver = () =>
      Effect.sync(() => {
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        const dimension = moving ? "minecraft:overworld" : "minecraft:the_nether";
        return observation({
          dimension,
          position: moving
            ? {
                x: 11,
                y: 64,
                z: 20.5,
                dimension,
              }
            : { ...portal, x: 10.5, z: 17.5, dimension },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          x: 11,
          y: 64,
          z: 19,
          dimension: "minecraft:the_nether",
        },
        radius: 0,
      }),
    ]);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
    });
  });

  it("pathfinds back to a portal approach after a wide retreat", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 104,
      y: 50,
      z: 158,
      dimension: "minecraft:the_nether",
    };
    driver.currentObservation = observation({
      dimension: "minecraft:the_nether",
      position: { x: 101.2, y: 50, z: 159 },
    });
    driver.blockResults = [portal.z, portal.z + 1].map((z) =>
      blockObservation(
        { ...portal, z },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "z" },
          diggable: false,
        },
      ),
    );
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          dimension: position.dimension,
          position,
        });
      });
    driver.observationResolver = () =>
      Effect.sync(() => {
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        const dimension = moving ? "minecraft:overworld" : "minecraft:the_nether";
        return observation({
          dimension,
          position: {
            ...driver.currentObservation.player.position,
            dimension,
          },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          x: 103,
          y: 50,
          z: 159,
          dimension: "minecraft:the_nether",
        },
        radius: 0,
      }),
    ]);
  });

  it("excavates to a verified portal below an exhausted direct route", async () => {
    const driver = new FakeBeatGameDriver();
    const from = {
      x: 9,
      y: 50,
      z: -17,
      dimension: "minecraft:overworld",
    };
    const portal = {
      x: 0,
      y: 46,
      z: -22,
      dimension: "minecraft:overworld",
    };
    const portalBlocks = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    driver.blockQueryResolver = ({ center, selector }) =>
      selector.blockIds?.includes("minecraft:nether_portal") === true
        ? portalBlocks
        : [blockObservation(queriedBlockPosition(center))];
    installStaircaseMovementSimulation(driver, from);
    driver.xzPathResolver = (x, z, dimension, radius, policy) =>
      Effect.sync(() => {
        driver.xzPaths.push({ x, z, dimension, radius, policy });
        driver.currentObservation = observation({
          position: { x, y: 48, z, dimension },
        });
      });
    const resolvePath = driver.pathResolver;
    let exhaustedDirectRoute = false;
    driver.pathResolver = (position, radius, policy) => {
      if (!exhaustedDirectRoute && radius === 0) {
        exhaustedDirectRoute = true;
        driver.paths.push({ position, radius, policy });
        return Effect.fail(
          new BeatGameDriverError({
            operation: "pathfind",
            code: "path_search_limit",
            retryable: true,
            message: "Pathfinding reached its search limit",
          }),
        );
      }
      return resolvePath(position, radius, policy);
    };
    const resolveObservation = driver.observationResolver;
    let entered = false;
    driver.observationResolver = () =>
      resolveObservation().pipe(
        Effect.map((current) => {
          const movement = driver.actions.findLast(
            (action) => action.type === "set-movement" || action.type === "reset-movement",
          );
          if (
            exhaustedDirectRoute &&
            movement?.type === "set-movement" &&
            movement.forward === true
          ) {
            entered = true;
          }
          return entered
            ? {
                ...current,
                player: {
                  ...current.player,
                  position: {
                    ...current.player.position,
                    dimension: "minecraft:the_nether",
                  },
                },
              }
            : current;
        }),
      );

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths[0]).toMatchObject({
      position: {
        x: 1,
        y: 46,
        z: -21,
        dimension: "minecraft:overworld",
      },
      radius: 0,
    });
    expect(driver.paths[1]).toMatchObject({
      position: feetCenter({ ...from, x: 1, y: 48, z: -19 }),
      radius: 0.5,
    });
    expect(driver.paths.at(-1)).toMatchObject({
      position: feetCenter({ ...portal, x: 1, z: -21 }),
      radius: 0.5,
      policy: objectContaining({
        allowMining: false,
        allowPlacing: false,
      }),
    });
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: { ...portal, x: 1, y: 48, z: -21 },
    });
    expect(driver.xzPaths).toEqual([
      objectContaining({
        x: 1.5,
        z: -16.5,
        dimension: "minecraft:overworld",
        radius: 1.5,
        policy: objectContaining({
          allowPlacing: false,
          avoidFluids: true,
        }),
      }),
    ]);
    expect(entered).toBe(true);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("keeps clearing movement while portal contact charges", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 64,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const portalBlocks = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    let touchedPortal = false;
    let resetsAfterContact = 0;
    driver.currentObservation = observation({
      position: { x: 11, y: 64, z: 17.5 },
    });
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:nether_portal") === true ? portalBlocks : [];
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.actionObserver = (action) => {
      if (action.type === "set-movement" && action.forward === true) {
        touchedPortal = true;
      }
      if (touchedPortal && action.type === "reset-movement") {
        resetsAfterContact += 1;
      }
    };
    driver.observationResolver = () =>
      Effect.sync(() => {
        const dimension = resetsAfterContact >= 3 ? "minecraft:the_nether" : "minecraft:overworld";
        const look = driver.actions.findLast((action) => action.type === "look");
        return observation({
          dimension,
          position: touchedPortal
            ? { x: 11, y: 64, z: 21.2, dimension }
            : {
                ...driver.currentObservation.player.position,
                dimension,
              },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }).pipe(Effect.timeout("2 seconds")));

    expect(resetsAfterContact).toBeGreaterThanOrEqual(3);
    expect(driver.currentObservation.player.position).toMatchObject({
      x: 11,
      y: 64,
      z: 19,
    });
  });

  it("stops a portal approach before wrong-way movement can wander", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:overworld",
    };
    driver.currentObservation = observation({
      position: { ...portal, x: 11.5, z: 17.5 },
    });
    driver.blockResults = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    let movementPulses = 0;
    driver.actionObserver = (action) => {
      if (action.type === "set-movement" && action.forward === true) {
        movementPulses += 1;
      }
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.observationResolver = () =>
      Effect.sync(() => {
        if (movementPulses === 0) {
          return driver.currentObservation;
        }
        const look = driver.actions.findLast((action) => action.type === "look");
        return observation({
          position: {
            ...portal,
            x: 11 - movementPulses,
            z: 19.5,
          },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await expect(Effect.runPromise(enterPortal(driver, { portal }))).rejects.toMatchObject({
      message: stringContaining("Moved away from the Nether portal while approaching it"),
    });

    expect(movementPulses).toBeLessThan(8);
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("steps out after spawning inside a Nether portal", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:the_nether",
    };
    driver.blockResults = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    driver.observationResolver = () =>
      Effect.sync(() => {
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        return observation({
          dimension: moving ? "minecraft:overworld" : "minecraft:the_nether",
          position: {
            x: 11,
            y: 65,
            z: 20.5,
          },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths).toHaveLength(0);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: true,
    });
    expect(driver.actions.at(-1)).toEqual({ type: "reset-movement" });
  });

  it("places a raised portal approach before entering its path", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const support = {
      x: 11,
      y: 64,
      z: 21,
      dimension: "minecraft:overworld",
    };
    const base = { ...support, y: 63 };
    const portalBlocks = [portal.x, portal.x + 1].map((x) => ({
      blockId: "minecraft:nether_portal",
      position: { ...portal, x },
      properties: { axis: "x" },
      diggable: false,
      replaceable: false,
      interactive: false,
      observedAt: "2026-01-01T00:00:01.000Z",
    }));
    let supportPlaced = false;
    driver.currentObservation = observation({
      position: { x: 11, y: 64, z: 24 },
    });
    driver.blockQueryResolver = ({ selector }) => {
      if (selector.blockIds?.includes("minecraft:nether_portal") === true) {
        return portalBlocks;
      }
      if (selector.replaceable === false) {
        return supportPlaced
          ? [
              {
                blockId: "minecraft:cobblestone",
                position: support,
                properties: {},
                diggable: true,
                replaceable: false,
                interactive: false,
                observedAt: "2026-01-01T00:00:02.000Z",
              },
            ]
          : [];
      }
      return [
        {
          blockId: "minecraft:air",
          position: support,
          properties: {},
          diggable: true,
          replaceable: true,
          interactive: false,
          observedAt: "2026-01-01T00:00:01.000Z",
        },
        {
          blockId: "minecraft:stone",
          position: base,
          properties: {},
          diggable: true,
          replaceable: false,
          interactive: false,
          observedAt: "2026-01-01T00:00:01.000Z",
        },
      ];
    };
    driver.actionObserver = (action) => {
      if (action.type === "place-block") {
        supportPlaced = true;
      }
    };
    driver.observationResolver = () =>
      Effect.sync(() => {
        const latestPath = driver.paths.at(-1)?.position;
        const standingPosition =
          latestPath === undefined
            ? driver.currentObservation.player.position
            : {
                ...latestPath,
                x: latestPath.x + 0.5,
                z: latestPath.z + 0.5,
              };
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        const dimension = moving ? "minecraft:the_nether" : "minecraft:overworld";
        return observation({
          dimension,
          position: moving
            ? { ...portal, x: 11, z: 20.5, dimension }
            : { ...standingPosition, dimension },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: base,
      face: "up",
      hand: "main",
    });
    const placementIndex = driver.actions.findIndex(({ type }) => type === "place-block");
    expect(placementIndex).toBeGreaterThan(-1);
    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          ...portal,
          x: 11,
          z: 21,
        },
        radius: 0,
      }),
    ]);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("defers a distant portal support until it is within reach", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:overworld",
    };
    const support = {
      x: 11,
      y: 64,
      z: 21,
      dimension: "minecraft:overworld",
    };
    const base = { ...support, y: 63 };
    const portalBlocks = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    const operations: string[] = [];
    let supportPlaced = false;
    driver.currentObservation = observation({
      position: { x: 30, y: 64, z: 24 },
    });
    driver.blockQueryResolver = ({ selector }) => {
      if (selector.blockIds?.includes("minecraft:nether_portal") === true) {
        return portalBlocks;
      }
      if (selector.replaceable === false) {
        return supportPlaced
          ? [blockObservation(support, { blockId: "minecraft:cobblestone" })]
          : [];
      }
      return [
        blockObservation(support, {
          blockId: "minecraft:air",
          replaceable: true,
        }),
        blockObservation(base),
      ];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        operations.push("pathfind");
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.actionObserver = (action) => {
      if (action.type === "place-block") {
        operations.push("place-support");
        supportPlaced = true;
      }
    };
    let entered = false;
    driver.observationResolver = () =>
      Effect.sync(() => {
        const movement = driver.actions.findLast(
          (action) => action.type === "set-movement" || action.type === "reset-movement",
        );
        if (supportPlaced || (movement?.type === "set-movement" && movement.forward === true)) {
          entered = true;
        }
        const current = driver.currentObservation;
        const look = driver.actions.findLast((action) => action.type === "look");
        return {
          ...current,
          player: {
            ...current.player,
            position: {
              ...current.player.position,
              ...(entered ? { dimension: "minecraft:the_nether" } : {}),
            },
            rotation: {
              yaw: look?.type === "look" ? look.yaw : 0,
              pitch: look?.type === "look" ? look.pitch : 0,
            },
          },
        };
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(operations).toEqual(["pathfind", "place-support"]);
    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: base,
      face: "up",
      hand: "main",
    });
    expect(entered).toBe(true);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("bridges a short gap leading into a generated portal", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:the_nether",
    };
    const portalBlocks = [portal.x, portal.x + 1].map((x) =>
      blockObservation(
        { ...portal, x },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "x" },
          diggable: false,
        },
      ),
    );
    const solidSupports = new Set(["11,64,17"]);
    driver.currentObservation = observation({
      dimension: "minecraft:the_nether",
      position: { x: 11.5, y: 65, z: 17.5 },
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:nether_portal") === true) {
        return portalBlocks;
      }
      const supports = [...solidSupports].map((key) => {
        const [x = Number.NaN, y = Number.NaN, z = Number.NaN] = key.split(",").map(Number);
        return blockObservation(
          {
            x,
            y,
            z,
            dimension: "minecraft:the_nether",
          },
          { blockId: "minecraft:cobblestone" },
        );
      });
      if (selector.replaceable === false) {
        return supports;
      }
      const queriedPosition = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      const queriedKey = `${queriedPosition.x},${queriedPosition.y},${queriedPosition.z}`;
      return [
        ...(solidSupports.has(queriedKey)
          ? []
          : [
              blockObservation(queriedPosition, {
                blockId: "minecraft:air",
                replaceable: true,
              }),
            ]),
        ...supports,
      ];
    };
    driver.actionObserver = (action) => {
      if (action.type === "place-block" && action.face === "south") {
        solidSupports.add(`${action.against.x},${action.against.y},${action.against.z + 1}`);
      }
    };
    driver.observationResolver = () =>
      Effect.sync(() => {
        const latestPath =
          driver.paths.at(-1)?.position ?? driver.currentObservation.player.position;
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        const dimension = moving ? "minecraft:overworld" : "minecraft:the_nether";
        return observation({
          dimension,
          position: moving
            ? { ...portal, x: 11, z: 20.5, dimension }
            : { ...latestPath, dimension },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.actions.filter(({ type }) => type === "place-block")).toEqual([
      {
        type: "place-block",
        against: {
          x: 11,
          y: 64,
          z: 17,
          dimension: "minecraft:the_nether",
        },
        face: "south",
        hand: "main",
      },
      {
        type: "place-block",
        against: {
          x: 11,
          y: 64,
          z: 18,
          dimension: "minecraft:the_nether",
        },
        face: "south",
        hand: "main",
      },
    ]);
  });

  it("pathfinds onto a raised Nether portal approach before crossing", async () => {
    const driver = new FakeBeatGameDriver();
    const portal = {
      x: 10,
      y: 65,
      z: 20,
      dimension: "minecraft:the_nether",
    };
    const portalBlocks = [portal.z, portal.z + 1].map((z) =>
      blockObservation(
        { ...portal, z },
        {
          blockId: "minecraft:nether_portal",
          properties: { axis: "z" },
          diggable: false,
        },
      ),
    );
    driver.currentObservation = observation({
      dimension: "minecraft:the_nether",
      position: { x: 8.5, y: 64, z: 20.5 },
    });
    driver.blockQueryResolver = ({ center, selector }) =>
      selector.blockIds?.includes("minecraft:nether_portal") === true
        ? portalBlocks
        : [
            blockObservation({
              x: Math.floor(center.x),
              y: Math.floor(center.y),
              z: Math.floor(center.z),
              dimension: center.dimension,
            }),
          ];
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          dimension: position.dimension,
          position,
        });
      });
    driver.observationResolver = () =>
      Effect.sync(() => {
        const latestPath =
          driver.paths.at(-1)?.position ?? driver.currentObservation.player.position;
        const look = driver.actions.findLast((action) => action.type === "look");
        const moving = driver.actions.some(
          (action) => action.type === "set-movement" && action.forward === true,
        );
        const dimension = moving
          ? "minecraft:overworld"
          : driver.currentObservation.player.position.dimension;
        return observation({
          dimension,
          position: moving
            ? { ...portal, x: 10.5, z: 21, dimension }
            : { ...latestPath, dimension },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
      });

    await Effect.runPromise(enterPortal(driver, { portal }));

    expect(driver.paths).toEqual([
      objectContaining({
        position: {
          x: 9,
          y: 65,
          z: 21,
          dimension: "minecraft:the_nether",
        },
        radius: 0,
      }),
    ]);
  });

  it("pathfinds and verifies custom portal casting steps", async () => {
    const driver = new FakeBeatGameDriver();
    const target = {
      x: 0,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    };
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:obsidian") === true
        ? [
            {
              blockId: "minecraft:obsidian",
              position: target,
              properties: {},
              diggable: true,
              replaceable: false,
              interactive: false,
              observedAt: "2026-01-01T00:00:01.000Z",
            },
          ]
        : [];

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin: target,
        ignite: false,
        steps: [
          {
            itemIds: ["minecraft:lava_bucket"],
            action: {
              type: "interact-block",
              position: { ...target, y: 63 },
              face: "up",
            },
            expectedBlock: {
              position: target,
              blockIds: ["minecraft:obsidian"],
            },
            observationDelayMs: 1,
          },
        ],
      }),
    );

    expect(driver.paths).toHaveLength(1);
    expect(driver.actions.map(({ type }) => type)).toContain("interact-block");
    expect(driver.activeControlScopes).toBe(0);
  });

  it("clears casting cells and recovers lava placed in the wrong cell", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: -52,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = [...frame.blocks].toSorted(
      (left, right) => left.y - right.y || left.x - right.x || left.z - right.z,
    )[0];
    if (target === undefined) {
      throw new Error("Expected a portal frame target");
    }
    const water = { ...target, z: target.z - 1 };
    const misplacedLava = { ...target, x: target.x + 1, z: target.z + 1 };
    const interior = frame.interior[0];
    const replaceableInterior = frame.interior[1];
    if (interior === undefined || replaceableInterior === undefined) {
      throw new Error("Expected portal interior blocks");
    }
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 2,
    };
    const blocks = new Map<string, BeatGameBlockObservation>([
      ...frame.blocks.map((position): [string, BeatGameBlockObservation] => [
        framePositionKey(position),
        blockObservation(position, { blockId: "minecraft:obsidian" }),
      ]),
      [framePositionKey(target), blockObservation(target, { blockId: "minecraft:deepslate" })],
      [framePositionKey(water), blockObservation(water, { blockId: "minecraft:deepslate" })],
      [framePositionKey(interior), blockObservation(interior, { blockId: "minecraft:deepslate" })],
    ]);
    const liquidSightObstruction = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 1,
    };
    blocks.set(
      framePositionKey(liquidSightObstruction),
      blockObservation(liquidSightObstruction, {
        blockId: "minecraft:deepslate",
      }),
    );
    blocks.set(
      framePositionKey(replaceableInterior),
      blockObservation(replaceableInterior, {
        blockId: "minecraft:cave_air",
        replaceable: true,
      }),
    );
    for (const support of [
      { ...target, y: target.y - 1 },
      { ...water, y: water.y - 1 },
      { ...castingStand, y: castingStand.y - 1 },
    ]) {
      const isWaterSupport =
        framePositionKey(support) ===
        framePositionKey({
          ...water,
          y: water.y - 1,
        });
      blocks.set(
        framePositionKey(support),
        blockObservation(
          support,
          isWaterSupport
            ? {
                blockId: "minecraft:crafting_table",
                interactive: true,
              }
            : { blockId: "minecraft:deepslate" },
        ),
      );
    }
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:iron_pickaxe": 1,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    let conversionPending = false;
    let conversionQueries = 0;
    let misplacedLavaAvailable = false;
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        return misplacedLavaAvailable
          ? [required(blocks.get(framePositionKey(misplacedLava)), "the misplaced lava")]
          : [];
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      if (conversionPending && framePositionKey(position) === framePositionKey(target)) {
        conversionQueries += 1;
        if (conversionQueries >= 3) {
          conversionPending = false;
          blocks.set(
            framePositionKey(target),
            blockObservation(target, {
              blockId: "minecraft:obsidian",
            }),
          );
        }
      }
      return [
        blocks.get(framePositionKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    let selectedItemId = "";
    let pendingLavaTarget: typeof target | undefined;
    let misplacedLavaPlacements = 0;
    let rejectedLavaPlacement = false;
    let rejectedWaterPickup = false;
    const updateInventory = (update: (counts: Record<string, number>) => void) => {
      const counts = { ...driver.currentObservation.inventory.counts };
      update(counts);
      driver.currentObservation = observation({
        counts,
        position: driver.currentObservation.player.position,
        rotation: driver.currentObservation.player.rotation,
      });
    };
    driver.raycastResolver = ({ includeFluids }) => {
      if (blocks.has(framePositionKey(liquidSightObstruction))) {
        return {
          block: required(
            blocks.get(framePositionKey(liquidSightObstruction)),
            "the liquid sight obstruction",
          ),
          distance: 1,
        };
      }
      if (includeFluids !== true) {
        return { distance: 2 };
      }
      const liquid = misplacedLavaAvailable
        ? blocks.get(framePositionKey(misplacedLava))
        : blocks.get(framePositionKey(water));
      return liquid === undefined ? { distance: 2 } : { block: liquid, distance: 2 };
    };
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItemId = action.selector.itemIds?.[0] ?? "";
        return;
      }
      if (action.type === "look") {
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position: driver.currentObservation.player.position,
          rotation: {
            yaw: action.yaw,
            pitch: action.pitch,
          },
        });
        return;
      }
      if (action.type === "dig-block") {
        blocks.delete(framePositionKey(action.position));
        return;
      }
      if (action.type !== "use-item" && action.type !== "interact-block") {
        return;
      }
      if (selectedItemId === "minecraft:lava_bucket") {
        updateInventory((counts) => {
          delete counts["minecraft:lava_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        if (misplacedLavaPlacements === 0) {
          misplacedLavaPlacements += 1;
          misplacedLavaAvailable = true;
          blocks.set(
            framePositionKey(misplacedLava),
            blockObservation(misplacedLava, {
              blockId: "minecraft:lava",
              properties: { level: "0" },
              replaceable: true,
            }),
          );
          return;
        }
        pendingLavaTarget = target;
        blocks.set(
          framePositionKey(target),
          blockObservation(target, {
            blockId: "minecraft:lava",
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:water_bucket" && pendingLavaTarget !== undefined) {
        if (action.type === "interact-block" && action.sneaking !== true) {
          return;
        }
        updateInventory((counts) => {
          delete counts["minecraft:water_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        conversionPending = true;
        blocks.set(
          framePositionKey(water),
          blockObservation(water, {
            blockId: "minecraft:water",
            properties: { level: "0" },
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:bucket") {
        if (misplacedLavaAvailable) {
          updateInventory((counts) => {
            counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
            counts["minecraft:lava_bucket"] = 1;
          });
          misplacedLavaAvailable = false;
          blocks.delete(framePositionKey(misplacedLava));
          return;
        }
        updateInventory((counts) => {
          counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
          counts["minecraft:water_bucket"] = 1;
        });
        blocks.delete(framePositionKey(water));
      }
    };
    driver.actionResolver = (action) => {
      if (
        action.type === "use-item" &&
        selectedItemId === "minecraft:lava_bucket" &&
        !rejectedLavaPlacement
      ) {
        rejectedLavaPlacement = true;
        return Effect.fail(
          new BeatGameDriverError({
            operation: "act",
            code: "failed_precondition",
            retryable: false,
            message: "The held item could not be used",
          }),
        );
      }
      if (
        action.type === "use-item" &&
        selectedItemId === "minecraft:bucket" &&
        !misplacedLavaAvailable &&
        !rejectedWaterPickup
      ) {
        rejectedWaterPickup = true;
        return Effect.fail(
          new BeatGameDriverError({
            operation: "act",
            code: "failed_precondition",
            retryable: false,
            message: "The held item could not be used",
          }),
        );
      }
      return Effect.sync(() => {
        driver.actionObserver(action);
        return {};
      });
    };

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    const liquidPlacementIndex = driver.actions.findIndex((action) => action.type === "use-item");
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: target,
    });
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: water,
    });
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: interior,
    });
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: liquidSightObstruction,
    });
    expect(driver.actions).not.toContainEqual({
      type: "dig-block",
      position: replaceableInterior,
    });
    const interiorDigIndex = driver.actions.findIndex(
      (action) =>
        action.type === "dig-block" &&
        framePositionKey(action.position) === framePositionKey(interior),
    );
    const sightlineDigIndex = driver.actions.findIndex(
      (action) =>
        action.type === "dig-block" &&
        framePositionKey(action.position) === framePositionKey(liquidSightObstruction),
    );
    const interiorToolSelectionIndex = driver.actions.findIndex(
      (action) =>
        action.type === "select-item" &&
        action.selector.itemIds?.includes("minecraft:iron_pickaxe") === true,
    );
    expect(interiorToolSelectionIndex).toBeGreaterThanOrEqual(0);
    expect(interiorToolSelectionIndex).toBeLessThan(interiorDigIndex);
    expect(sightlineDigIndex).toBeGreaterThanOrEqual(0);
    expect(sightlineDigIndex).toBeLessThan(liquidPlacementIndex);
    expect(
      driver.actions.findIndex(
        (action) =>
          action.type === "dig-block" &&
          (framePositionKey(action.position) === framePositionKey(target) ||
            framePositionKey(action.position) === framePositionKey(water)),
      ),
    ).toBeLessThan(liquidPlacementIndex);
    expect(driver.paths).toContainEqual({
      position: castingStand,
      radius: 0,
      policy: objectContaining({
        allowMining: false,
        allowPlacing: false,
        avoidFluids: true,
        maxFallDistance: 1,
      }),
    });
    const lavaSelectionIndex = driver.actions.findIndex(
      (action) =>
        action.type === "select-item" &&
        action.selector.itemIds?.includes("minecraft:lava_bucket") === true,
    );
    const lavaLook = driver.actions
      .slice(lavaSelectionIndex + 1)
      .find((action) => action.type === "look");
    const expectedLavaRotation = rotationToward(
      {
        ...origin,
        y: origin.y + 1.62,
      },
      {
        ...target,
        x: target.x + 0.5,
        y: target.y + 1 / 64,
        z: target.z + 0.5,
      },
    );
    expect(lavaLook).toEqual({
      type: "look",
      yaw: expectedLavaRotation.yaw,
      pitch: expectedLavaRotation.pitch,
    });
    expect(water.z).toBeGreaterThan(castingStand.z);
    expect(water.z).toBeLessThan(target.z);
    expect(blocks.get(framePositionKey(target))?.blockId).toBe("minecraft:obsidian");
    expect(blocks.has(framePositionKey(misplacedLava))).toBe(false);
    expect(misplacedLavaPlacements).toBe(1);
    expect(conversionQueries).toBe(3);
    expect(driver.actions.filter(({ type }) => type === "use-item")).toHaveLength(3);
    expect(driver.actions.filter(({ type }) => type === "interact-block")).toHaveLength(3);
    expect(driver.actions).toContainEqual({
      type: "interact-block",
      position: { ...target, y: target.y - 1 },
      face: "up",
      hand: "main",
      sneaking: true,
    });
    expect(
      driver.actions.every(
        (action) => action.type !== "interact-block" || action.sneaking === true,
      ),
    ).toBe(true);
    expect(rejectedLavaPlacement).toBe(false);
    expect(rejectedWaterPickup).toBe(true);
    expect(driver.raycasts.some(({ includeFluids }) => includeFluids === true)).toBe(true);
    expect(driver.paths).not.toContainEqual(
      objectContaining({
        position: water,
        radius: 1.25,
      }),
    );
    expect(driver.tasks).toHaveLength(0);
    expect(driver.activeControlScopes).toBe(0);
  });

  it("scoops lava left in a casting cell before digging beside it", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: -52,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = [...frame.blocks].toSorted(
      (left, right) => left.y - right.y || left.x - right.x || left.z - right.z,
    )[0];
    if (target === undefined) {
      throw new Error("Expected a portal frame target");
    }
    const water = { ...target, z: target.z - 1 };
    const misplacedLava = { ...target, x: target.x + 1, z: target.z + 1 };
    const interior = frame.interior[0];
    const replaceableInterior = frame.interior[1];
    if (interior === undefined || replaceableInterior === undefined) {
      throw new Error("Expected portal interior blocks");
    }
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 2,
    };
    const blocks = new Map<string, BeatGameBlockObservation>([
      ...frame.blocks.map((position): [string, BeatGameBlockObservation] => [
        framePositionKey(position),
        blockObservation(position, { blockId: "minecraft:obsidian" }),
      ]),
      // A failed cast left its lava source in the casting cell.
      [
        framePositionKey(target),
        blockObservation(target, {
          blockId: "minecraft:lava",
          properties: { level: "0" },
          replaceable: true,
        }),
      ],
    ]);
    let strayLava = true;
    let dugBesideStrayLava = false;
    blocks.set(
      framePositionKey(water),
      blockObservation(water, { blockId: "minecraft:deepslate" }),
    );
    blocks.set(
      framePositionKey(interior),
      blockObservation(interior, { blockId: "minecraft:deepslate" }),
    );
    const liquidSightObstruction = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 1,
    };

    blocks.set(
      framePositionKey(replaceableInterior),
      blockObservation(replaceableInterior, {
        blockId: "minecraft:cave_air",
        replaceable: true,
      }),
    );
    for (const support of [
      { ...target, y: target.y - 1 },
      { ...water, y: water.y - 1 },
      { ...castingStand, y: castingStand.y - 1 },
    ]) {
      const isWaterSupport =
        framePositionKey(support) ===
        framePositionKey({
          ...water,
          y: water.y - 1,
        });
      blocks.set(
        framePositionKey(support),
        blockObservation(
          support,
          isWaterSupport
            ? {
                blockId: "minecraft:crafting_table",
                interactive: true,
              }
            : { blockId: "minecraft:deepslate" },
        ),
      );
    }
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:iron_pickaxe": 1,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    let conversionPending = false;
    let conversionQueries = 0;
    let misplacedLavaAvailable = false;
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true && strayLava) {
        return [required(blocks.get(framePositionKey(target)), "the stray lava")];
      }
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        return misplacedLavaAvailable
          ? [required(blocks.get(framePositionKey(misplacedLava)), "the misplaced lava")]
          : [];
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      if (conversionPending && framePositionKey(position) === framePositionKey(target)) {
        conversionQueries += 1;
        if (conversionQueries >= 3) {
          conversionPending = false;
          blocks.set(
            framePositionKey(target),
            blockObservation(target, {
              blockId: "minecraft:obsidian",
            }),
          );
        }
      }
      return [
        blocks.get(framePositionKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    let selectedItemId = "";
    let pendingLavaTarget: typeof target | undefined;
    let misplacedLavaPlacements = 0;
    let rejectedLavaPlacement = false;
    let rejectedWaterPickup = false;
    const updateInventory = (update: (counts: Record<string, number>) => void) => {
      const counts = { ...driver.currentObservation.inventory.counts };
      update(counts);
      driver.currentObservation = observation({
        counts,
        position: driver.currentObservation.player.position,
        rotation: driver.currentObservation.player.rotation,
      });
    };
    driver.raycastResolver = ({ includeFluids }) => {
      if (blocks.has(framePositionKey(liquidSightObstruction))) {
        return {
          block: required(
            blocks.get(framePositionKey(liquidSightObstruction)),
            "the liquid sight obstruction",
          ),
          distance: 1,
        };
      }
      if (includeFluids !== true) {
        return { distance: 2 };
      }
      if (strayLava) {
        return {
          block: required(blocks.get(framePositionKey(target)), "the stray lava"),
          distance: 2,
        };
      }
      const liquid = misplacedLavaAvailable
        ? blocks.get(framePositionKey(misplacedLava))
        : blocks.get(framePositionKey(water));
      return liquid === undefined ? { distance: 2 } : { block: liquid, distance: 2 };
    };
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItemId = action.selector.itemIds?.[0] ?? "";
        return;
      }
      if (action.type === "look") {
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position: driver.currentObservation.player.position,
          rotation: {
            yaw: action.yaw,
            pitch: action.pitch,
          },
        });
        return;
      }
      if (action.type === "dig-block") {
        const offset =
          Math.abs(action.position.x - target.x) +
          Math.abs(action.position.y - target.y) +
          Math.abs(action.position.z - target.z);
        if (strayLava && offset <= 2) {
          dugBesideStrayLava = true;
        }
        blocks.delete(framePositionKey(action.position));
        return;
      }
      if (action.type !== "use-item" && action.type !== "interact-block") {
        return;
      }
      if (selectedItemId === "minecraft:lava_bucket") {
        updateInventory((counts) => {
          delete counts["minecraft:lava_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        if (misplacedLavaPlacements === 0) {
          misplacedLavaPlacements += 1;
          misplacedLavaAvailable = true;
          blocks.set(
            framePositionKey(misplacedLava),
            blockObservation(misplacedLava, {
              blockId: "minecraft:lava",
              properties: { level: "0" },
              replaceable: true,
            }),
          );
          return;
        }
        pendingLavaTarget = target;
        blocks.set(
          framePositionKey(target),
          blockObservation(target, {
            blockId: "minecraft:lava",
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:water_bucket" && pendingLavaTarget !== undefined) {
        if (action.type === "interact-block" && action.sneaking !== true) {
          return;
        }
        updateInventory((counts) => {
          delete counts["minecraft:water_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        conversionPending = true;
        blocks.set(
          framePositionKey(water),
          blockObservation(water, {
            blockId: "minecraft:water",
            properties: { level: "0" },
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:bucket" && strayLava) {
        updateInventory((counts) => {
          counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
          counts["minecraft:lava_bucket"] = (counts["minecraft:lava_bucket"] ?? 0) + 1;
        });
        strayLava = false;
        blocks.delete(framePositionKey(target));
        return;
      }
      if (selectedItemId === "minecraft:bucket") {
        if (misplacedLavaAvailable) {
          updateInventory((counts) => {
            counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
            counts["minecraft:lava_bucket"] = 1;
          });
          misplacedLavaAvailable = false;
          blocks.delete(framePositionKey(misplacedLava));
          return;
        }
        updateInventory((counts) => {
          counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
          counts["minecraft:water_bucket"] = 1;
        });
        blocks.delete(framePositionKey(water));
      }
    };
    driver.actionResolver = (action) => {
      if (
        action.type === "use-item" &&
        selectedItemId === "minecraft:lava_bucket" &&
        !rejectedLavaPlacement
      ) {
        rejectedLavaPlacement = true;
        return Effect.fail(
          new BeatGameDriverError({
            operation: "act",
            code: "failed_precondition",
            retryable: false,
            message: "The held item could not be used",
          }),
        );
      }
      if (
        action.type === "use-item" &&
        selectedItemId === "minecraft:bucket" &&
        !strayLava &&
        !misplacedLavaAvailable &&
        !rejectedWaterPickup
      ) {
        rejectedWaterPickup = true;
        return Effect.fail(
          new BeatGameDriverError({
            operation: "act",
            code: "failed_precondition",
            retryable: false,
            message: "The held item could not be used",
          }),
        );
      }
      return Effect.sync(() => {
        driver.actionObserver(action);
        return {};
      });
    };

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(strayLava).toBe(false);
    expect(dugBesideStrayLava).toBe(false);
  });

  it("refuses an undiggable portal interior without starting a dig", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: -60,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = frame.blocks[0];
    const interior = frame.interior[0];
    if (target === undefined || interior === undefined) {
      throw new Error("Expected portal frame geometry");
    }
    const existingFrame = frame.blocks
      .filter((position) => blockKey(position) !== blockKey(target))
      .map((position) =>
        blockObservation(position, {
          blockId: "minecraft:obsidian",
        }),
      );
    driver.currentObservation = observation({
      counts: {
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return existingFrame;
      }
      const position = queriedBlockPosition(center);
      return [
        blockKey(position) === blockKey(interior)
          ? blockObservation(position, {
              blockId: "minecraft:bedrock",
              diggable: false,
            })
          : blockObservation(position, {
              blockId: "minecraft:air",
              replaceable: true,
            }),
      ];
    };

    await expect(
      Effect.runPromise(
        castNetherPortal(driver, {
          origin,
          ignite: false,
        }),
      ),
    ).rejects.toThrow("Portal interior is not diggable");

    expect(driver.actions).not.toContainEqual({
      type: "dig-block",
      position: interior,
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("retries a stale portal lava source after repositioning", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const targets = [...frame.blocks]
      .toSorted((left, right) => left.y - right.y || left.x - right.x || left.z - right.z)
      .slice(0, 2);
    const [firstTarget, secondTarget] = targets;
    if (firstTarget === undefined || secondTarget === undefined) {
      throw new Error("Expected two portal casting targets");
    }
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 2,
    };
    const source = blockObservation(
      {
        x: 8,
        y: 63,
        z: 8,
        dimension: origin.dimension,
      },
      {
        blockId: "minecraft:lava",
        replaceable: true,
      },
    );
    const safeStand = {
      x: 7,
      y: 64,
      z: 8,
      dimension: origin.dimension,
    } as const;
    const targetKeys = new Set(targets.map((block) => blockKey(block)));
    const blocks = new Map(
      frame.blocks
        .filter((position) => !targetKeys.has(blockKey(position)))
        .map((position) => [
          blockKey(position),
          blockObservation(position, { blockId: "minecraft:obsidian" }),
        ]),
    );
    for (const support of [
      { ...castingStand, y: castingStand.y - 1 },
      ...targets.flatMap((target) => [
        { ...target, y: target.y - 1 },
        { ...target, y: target.y - 1, z: target.z - 1 },
      ]),
    ]) {
      blocks.set(
        blockKey(support),
        blockObservation(support, {
          blockId: "minecraft:cobblestone",
        }),
      );
    }
    for (const block of [
      blockObservation(safeStand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...safeStand, y: safeStand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation(
        { ...safeStand, y: safeStand.y - 1 },
        {
          blockId: "minecraft:stone",
        },
      ),
    ]) {
      blocks.set(blockKey(block.position), block);
    }
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:cobblestone": 16,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    let lavaQueryCount = 0;
    let sourceVerificationCount = 0;
    let sourceCollected = false;
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        lavaQueryCount += 1;
        if (
          radius === 0.25 &&
          blockKey(queriedBlockPosition(center)) === blockKey(source.position)
        ) {
          sourceVerificationCount += 1;
          return sourceVerificationCount === 1 ? [] : [source];
        }
        return sourceCollected ? [] : [source];
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
      }
      if (radius === 4.9 && Object.keys(selector).length === 0) {
        return [
          blockObservation(safeStand, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
          blockObservation(
            { ...safeStand, y: safeStand.y + 1 },
            {
              blockId: "minecraft:air",
              replaceable: true,
            },
          ),
          blockObservation(
            { ...safeStand, y: safeStand.y - 1 },
            {
              blockId: "minecraft:stone",
            },
          ),
        ];
      }
      const position = queriedBlockPosition(center);
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position,
          rotation: driver.currentObservation.player.rotation,
        });
      });
    driver.raycastResolver = ({ direction, includeFluids, maximumDistance }) => {
      const player = driver.currentObservation.player.position;
      const targetPosition = {
        x: Math.floor(player.x + direction.x),
        y: Math.floor(player.y + 1.62 + direction.y),
        z: Math.floor(player.z + direction.z),
        dimension: player.dimension,
      };
      const target =
        blockKey(targetPosition) === blockKey(source.position)
          ? source
          : blocks.get(blockKey(targetPosition));
      if (
        includeFluids === true &&
        target !== undefined &&
        (target.blockId === "minecraft:lava" || target.blockId === "minecraft:water")
      ) {
        return { block: target, distance: maximumDistance };
      }
      return blockKey(targetPosition) === blockKey(source.position) &&
        maximumDistance > 4 &&
        player.x !== safeStand.x + 0.5
        ? {
            block: blockObservation(
              {
                x: 4,
                y: 64,
                z: 4,
                dimension: origin.dimension,
              },
              { blockId: "minecraft:stone" },
            ),
            distance: 4,
          }
        : { distance: maximumDistance };
    };
    let selectedItemId = "";
    let activeTarget: BeatGameBlockPosition | undefined;
    const updateInventory = (update: (counts: Record<string, number>) => void) => {
      const counts = { ...driver.currentObservation.inventory.counts };
      update(counts);
      driver.currentObservation = observation({
        counts,
        position: driver.currentObservation.player.position,
        rotation: driver.currentObservation.player.rotation,
      });
    };
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItemId = action.selector.itemIds?.[0] ?? "";
        return;
      }
      if (action.type === "look") {
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position: driver.currentObservation.player.position,
          rotation: {
            yaw: action.yaw,
            pitch: action.pitch,
          },
        });
        return;
      }
      if (action.type !== "use-item" && action.type !== "interact-block") {
        return;
      }
      if (selectedItemId === "minecraft:lava_bucket") {
        activeTarget = targets.find(
          (target) => blocks.get(blockKey(target))?.blockId !== "minecraft:obsidian",
        );
        if (activeTarget === undefined) {
          throw new Error("Expected another portal casting target");
        }
        updateInventory((counts) => {
          delete counts["minecraft:lava_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        blocks.set(
          blockKey(activeTarget),
          blockObservation(activeTarget, {
            blockId: "minecraft:lava",
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:water_bucket") {
        if (activeTarget === undefined) {
          throw new Error("Expected an active portal casting target");
        }
        const activeWater = { ...activeTarget, z: activeTarget.z - 1 };
        updateInventory((counts) => {
          delete counts["minecraft:water_bucket"];
          counts["minecraft:bucket"] = (counts["minecraft:bucket"] ?? 0) + 1;
        });
        blocks.set(
          blockKey(activeTarget),
          blockObservation(activeTarget, {
            blockId: "minecraft:obsidian",
          }),
        );
        blocks.set(
          blockKey(activeWater),
          blockObservation(activeWater, {
            blockId: "minecraft:water",
            properties: { level: "0" },
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:bucket") {
        const activeWater =
          activeTarget === undefined ? undefined : { ...activeTarget, z: activeTarget.z - 1 };
        const hasPlacedWater = activeWater !== undefined && blocks.has(blockKey(activeWater));
        updateInventory((counts) => {
          counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
          if (hasPlacedWater) {
            counts["minecraft:water_bucket"] = 1;
          } else {
            counts["minecraft:lava_bucket"] = 1;
            sourceCollected = true;
          }
        });
        if (hasPlacedWater) {
          blocks.delete(blockKey(activeWater));
        }
      }
    };

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(blocks.get(blockKey(firstTarget))?.blockId).toBe("minecraft:obsidian");
    expect(blocks.get(blockKey(secondTarget))?.blockId).toBe("minecraft:obsidian");
    expect(lavaQueryCount).toBeGreaterThanOrEqual(5);
    expect(sourceVerificationCount).toBe(2);
    expect(sourceCollected).toBe(true);
    const firstBucketUseIndex = driver.actions.findIndex((action) => action.type === "use-item");
    expect(firstBucketUseIndex).toBeGreaterThan(0);
    expect(driver.actions.slice(0, firstBucketUseIndex)).toContainEqual({
      type: "reset-movement",
    });
    expect(driver.paths).toContainEqual({
      position: {
        x: safeStand.x + 0.5,
        y: safeStand.y,
        z: safeStand.z + 0.5,
        dimension: safeStand.dimension,
      },
      radius: 0.75,
      policy: objectContaining({
        allowMining: false,
        avoidFluids: true,
        maxFallDistance: 1,
      }),
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("raises the casting stand above supports for upper portal rows", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = [...frame.blocks].toSorted(
      (left, right) => right.y - left.y || left.x - right.x || left.z - right.z,
    )[0];
    if (target === undefined) {
      throw new Error("Expected an upper portal frame target");
    }
    const water = { ...target, z: target.z - 1 };
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: target.y - 1,
      z: origin.z - 2,
    };
    const blocks = new Map(
      frame.blocks
        .filter((position) => blockKey(position) !== blockKey(target))
        .map((position) => [
          blockKey(position),
          blockObservation(position, { blockId: "minecraft:obsidian" }),
        ]),
    );
    for (const support of [
      { ...target, y: target.y - 1 },
      { ...water, y: water.y - 1 },
      { ...castingStand, y: castingStand.y - 1 },
    ]) {
      blocks.set(
        blockKey(support),
        blockObservation(support, {
          blockId: "minecraft:cobblestone",
        }),
      );
    }
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
      }
      if (radius === 3 && Object.keys(selector).length === 0) {
        return [...blocks.values()];
      }
      const position = queriedBlockPosition(center);
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    driver.pathResolver = (position, radius, policy) => {
      const record = Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position,
          rotation: driver.currentObservation.player.rotation,
        });
      });
      return position.x === castingStand.x &&
        position.y === castingStand.y &&
        position.z === castingStand.z &&
        radius === 0 &&
        !policy.allowMining
        ? record.pipe(
            Effect.andThen(
              Effect.fail(
                new BeatGameDriverError({
                  operation: "pathfind",
                  code: "task_failed",
                  retryable: false,
                  message: "No route found to the goal!",
                }),
              ),
            ),
          )
        : record;
    };
    const flowingWater = blockObservation(
      {
        ...water,
        z: water.z - 1,
      },
      {
        blockId: "minecraft:water",
        properties: { level: "1" },
        replaceable: true,
      },
    );
    driver.raycastResolver = ({ includeFluids }) => {
      const source = blocks.get(blockKey(water));
      return includeFluids === true && source !== undefined
        ? { block: flowingWater, distance: 1 }
        : { distance: 2 };
    };
    let selectedItemId = "";
    const updateInventory = (update: (counts: Record<string, number>) => void) => {
      const counts = { ...driver.currentObservation.inventory.counts };
      update(counts);
      driver.currentObservation = observation({
        counts,
        position: driver.currentObservation.player.position,
        rotation: driver.currentObservation.player.rotation,
      });
    };
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItemId = action.selector.itemIds?.[0] ?? "";
        return;
      }
      if (action.type === "look") {
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position: driver.currentObservation.player.position,
          rotation: { yaw: action.yaw, pitch: action.pitch },
        });
        return;
      }
      if (action.type === "interact-block") {
        if (selectedItemId === "minecraft:lava_bucket") {
          blocks.set(
            blockKey(target),
            blockObservation(target, {
              blockId: "minecraft:lava",
              replaceable: true,
            }),
          );
          updateInventory((counts) => {
            delete counts["minecraft:lava_bucket"];
            counts["minecraft:bucket"] = 2;
          });
        } else if (selectedItemId === "minecraft:water_bucket") {
          blocks.set(
            blockKey(target),
            blockObservation(target, {
              blockId: "minecraft:obsidian",
            }),
          );
          blocks.set(
            blockKey(water),
            blockObservation(water, {
              blockId: "minecraft:water",
              properties: { level: "0" },
              replaceable: true,
            }),
          );
          updateInventory((counts) => {
            delete counts["minecraft:water_bucket"];
            counts["minecraft:bucket"] = 3;
          });
        }
        return;
      }
      if (action.type === "use-item" && selectedItemId === "minecraft:bucket") {
        blocks.delete(blockKey(water));
        updateInventory((counts) => {
          counts["minecraft:bucket"] = Math.max(0, (counts["minecraft:bucket"] ?? 0) - 1);
          counts["minecraft:water_bucket"] = 1;
        });
      }
    };

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    expect(driver.paths).toContainEqual(
      objectContaining({
        position: castingStand,
        radius: 0,
        policy: objectContaining({ allowPlacing: false }),
      }),
    );
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: castingStand,
        radius: 0,
        policy: objectContaining({ allowPlacing: true }),
      }),
    );
    expect(driver.paths).toContainEqual(
      objectContaining({
        position: castingStand,
        radius: 0,
        policy: objectContaining({
          allowMining: true,
          allowPlacing: true,
          protectedBlocks: arrayContaining([...frame.blocks]),
        }),
      }),
    );
    const lavaSelectionIndex = driver.actions.findIndex(
      (action) =>
        action.type === "select-item" &&
        action.selector.itemIds?.includes("minecraft:lava_bucket") === true,
    );
    const lavaLook = driver.actions
      .slice(lavaSelectionIndex + 1)
      .find((action) => action.type === "look");
    expect(lavaLook).toEqual(
      objectContaining({
        type: "look",
        pitch: anyInstanceOf(Number),
      }),
    );
    if (lavaLook?.type !== "look") {
      throw new Error("Expected a lava placement look action");
    }
    expect(lavaLook.pitch).toBeGreaterThan(0);
    expect(blocks.get(blockKey(target))?.blockId).toBe("minecraft:obsidian");
    expect(driver.raycasts).toEqual(
      arrayContaining([
        objectContaining({ includeFluids: true }),
        objectContaining({ includeFluids: false }),
      ]),
    );
    expect(driver.activeControlScopes).toBe(0);
  });

  it("does not mine cast obsidian to expose another lava source", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: -52,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = [...frame.blocks].toSorted(
      (left, right) => left.y - right.y || left.x - right.x || left.z - right.z,
    )[0];
    const obstruction = frame.blocks.find(
      (position) =>
        target !== undefined &&
        (position.x !== target.x || position.y !== target.y || position.z !== target.z),
    );
    if (target === undefined || obstruction === undefined) {
      throw new Error("Expected distinct portal frame blocks");
    }
    const blocks = new Map(
      frame.blocks
        .filter((position) => blockKey(position) !== blockKey(target))
        .map((position) => [
          blockKey(position),
          blockObservation(position, { blockId: "minecraft:obsidian" }),
        ]),
    );
    const water = { ...target, z: target.z - 1 };
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 2,
    };
    for (const support of [
      { ...target, y: target.y - 1 },
      { ...water, y: water.y - 1 },
      { ...castingStand, y: castingStand.y - 1 },
    ]) {
      blocks.set(
        blockKey(support),
        blockObservation(support, {
          blockId: "minecraft:deepslate",
        }),
      );
    }
    const source = blockObservation(
      {
        x: 8,
        y: -53,
        z: 8,
        dimension: origin.dimension,
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:cobblestone": 16,
        "minecraft:iron_pickaxe": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        return [source];
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()];
      }
      const position = queriedBlockPosition(center);
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    driver.raycastResolver = () => ({
      block: required(blocks.get(blockKey(obstruction)), "the obstruction"),
      distance: 1,
    });
    driver.actionObserver = (action) => {
      if (action.type !== "look") {
        return;
      }
      driver.currentObservation = observation({
        counts: driver.currentObservation.inventory.counts,
        position: driver.currentObservation.player.position,
        rotation: { yaw: action.yaw, pitch: action.pitch },
      });
    };

    await expect(
      Effect.runPromise(
        castNetherPortal(driver, {
          origin,
          ignite: false,
        }),
      ),
    ).rejects.toThrow("Could not reach, excavate, or target a safe side-on stand");

    expect(driver.actions).not.toContainEqual({
      type: "dig-block",
      position: obstruction,
    });
    expect(blocks.get(blockKey(obstruction))?.blockId).toBe("minecraft:obsidian");
    expect(driver.activeControlScopes).toBe(0);
  });

  it("scaffolds an isolated portal casting support from solid terrain", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const target = [...frame.blocks].toSorted(
      (left, right) => left.y - right.y || left.x - right.x || left.z - right.z,
    )[0];
    if (target === undefined) {
      throw new Error("Expected a portal frame target");
    }
    const water = { ...target, z: target.z - 1 };
    const castingStand = {
      ...origin,
      x: origin.x + 1,
      y: origin.y + 1,
      z: origin.z - 2,
    };
    const castingStandSupport = {
      ...castingStand,
      y: castingStand.y - 1,
    };
    const scaffoldBase = {
      ...castingStandSupport,
      y: castingStandSupport.y - 1,
    };
    const scaffoldAnchor = {
      ...scaffoldBase,
      y: scaffoldBase.y - 1,
    };
    const safeStand = {
      ...scaffoldBase,
      x: scaffoldBase.x + 1,
    };
    const safeStandHead = { ...safeStand, y: safeStand.y + 1 };
    const safeStandSupport = { ...safeStand, y: safeStand.y - 1 };
    const targetSupport = { ...target, y: target.y - 1 };
    const waterSupport = { ...water, y: water.y - 1 };
    const blocks = new Map(
      frame.blocks.map((position) => [
        blockKey(position),
        blockObservation(position, { blockId: "minecraft:obsidian" }),
      ]),
    );
    blocks.delete(blockKey(target));
    for (const position of [castingStandSupport, scaffoldBase, safeStand, safeStandHead]) {
      blocks.set(
        blockKey(position),
        blockObservation(position, {
          blockId: "minecraft:air",
          diggable: false,
          replaceable: true,
          solid: false,
        }),
      );
    }
    for (const position of [scaffoldAnchor, safeStandSupport, targetSupport, waterSupport]) {
      blocks.set(
        blockKey(position),
        blockObservation(position, {
          blockId: "minecraft:cobblestone",
        }),
      );
    }
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:cobblestone": 16,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: {
        x: scaffoldBase.x + 0.5,
        y: scaffoldBase.y,
        z: scaffoldBase.z + 0.5,
        dimension: scaffoldBase.dimension,
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        return [];
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [...blocks.values()].filter(({ blockId }) => blockId === "minecraft:obsidian");
      }
      if (radius > 1) {
        return [...blocks.values()];
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    driver.taskObserver = (task) => {
      if (task.type !== "build") {
        return;
      }
      task.blocks.forEach((block) => {
        const position = {
          x: task.origin.x + block.offset.x,
          y: task.origin.y + block.offset.y,
          z: task.origin.z + block.offset.z,
          dimension: task.origin.dimension,
        };
        const supported = [
          { ...position, y: position.y - 1 },
          { ...position, y: position.y + 1 },
          { ...position, x: position.x - 1 },
          { ...position, x: position.x + 1 },
          { ...position, z: position.z - 1 },
          { ...position, z: position.z + 1 },
        ].some((neighbor) => {
          const observed = blocks.get(blockKey(neighbor));
          return observed !== undefined && !observed.replaceable;
        });
        if (!supported) {
          return;
        }
        blocks.set(
          blockKey(position),
          blockObservation(position, {
            blockId: block.blockId,
          }),
        );
      });
    };
    let selectedItemId = "";
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItemId = action.selector.itemIds?.[0] ?? "";
        return;
      }
      if (action.type === "look") {
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position: driver.currentObservation.player.position,
          rotation: {
            yaw: action.yaw,
            pitch: action.pitch,
          },
        });
        return;
      }
      if (action.type !== "use-item" && action.type !== "interact-block") {
        return;
      }
      if (selectedItemId === "minecraft:lava_bucket") {
        blocks.set(
          blockKey(target),
          blockObservation(target, {
            blockId: "minecraft:lava",
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:water_bucket") {
        blocks.set(
          blockKey(target),
          blockObservation(target, {
            blockId: "minecraft:obsidian",
          }),
        );
        blocks.set(
          blockKey(water),
          blockObservation(water, {
            blockId: "minecraft:water",
            properties: { level: "0" },
            replaceable: true,
          }),
        );
        return;
      }
      if (selectedItemId === "minecraft:bucket") {
        blocks.delete(blockKey(water));
      }
    };

    await Effect.runPromise(
      castNetherPortal(driver, {
        origin,
        ignite: false,
      }),
    );

    const builds = driver.tasks.filter((task) => task.type === "build");
    expect(builds).toHaveLength(2);
    expect(builds[0]?.blocks).toHaveLength(1);
    expect(
      builds[1]?.blocks.map(({ offset }) => ({
        x: origin.x + offset.x,
        y: origin.y + offset.y,
        z: origin.z + offset.z,
        dimension: origin.dimension,
      })),
    ).toEqual([scaffoldBase, castingStandSupport]);
    expect(driver.paths).toContainEqual({
      position: {
        x: safeStand.x + 0.5,
        y: safeStand.y,
        z: safeStand.z + 0.5,
        dimension: safeStand.dimension,
      },
      radius: 0.75,
      policy: objectContaining({
        allowMining: false,
        allowPlacing: false,
        avoidFluids: true,
        maxFallDistance: 1,
      }),
    });
    expect(blocks.get(blockKey(target))?.blockId).toBe("minecraft:obsidian");
  });

  it("builds portal casting supports one layer at a time", async () => {
    const driver = new FakeBeatGameDriver();
    const origin = {
      x: 0,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    };
    const frame = createNetherPortalFrame(origin);
    const firstTarget = [...frame.blocks].toSorted(
      (left, right) => left.y - right.y || left.x - right.x || left.z - right.z,
    )[0];
    const nextLayerTarget = frame.blocks.find(({ y }) => y === origin.y + 1);
    if (firstTarget === undefined || nextLayerTarget === undefined) {
      throw new Error("Expected portal targets on multiple layers");
    }
    const blocks = new Map<string, ReturnType<typeof blockObservation>>();
    const lavaSources = Array.from({ length: frame.blocks.length - 1 }, (_, index) => {
      const position = {
        x: 16 + index,
        y: 63,
        z: 16,
        dimension: origin.dimension,
      };
      return blockObservation(position, {
        blockId: "minecraft:lava",
        replaceable: true,
      });
    });
    driver.currentObservation = observation({
      counts: {
        "minecraft:bucket": 1,
        "minecraft:cobblestone": 64,
        "minecraft:lava_bucket": 1,
        "minecraft:water_bucket": 1,
      },
      position: origin,
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:lava") === true) {
        return lavaSources;
      }
      if (selector.blockIds?.includes("minecraft:obsidian") === true) {
        return [];
      }
      const position = queriedBlockPosition(center);
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
          }),
      ];
    };
    driver.taskObserver = (task) => {
      if (task.type !== "build") {
        return;
      }
      for (const block of task.blocks) {
        const position = {
          x: task.origin.x + block.offset.x,
          y: task.origin.y + block.offset.y,
          z: task.origin.z + block.offset.z,
          dimension: task.origin.dimension,
        };
        blocks.set(
          blockKey(position),
          blockObservation(position, {
            blockId: block.blockId,
          }),
        );
      }
    };
    driver.actionObserver = (action) => {
      if (action.type !== "look") {
        return;
      }
      driver.currentObservation = observation({
        counts: driver.currentObservation.inventory.counts,
        position: driver.currentObservation.player.position,
        rotation: {
          yaw: action.yaw,
          pitch: action.pitch,
        },
      });
    };

    await expect(
      Effect.runPromise(
        castNetherPortal(driver, {
          origin,
          ignite: false,
        }),
      ),
    ).rejects.toThrow("Bucket placement missed");

    const builtPositions = driver.tasks
      .filter((task) => task.type === "build")
      .flatMap((task) =>
        task.blocks.map((block) => ({
          x: task.origin.x + block.offset.x,
          y: task.origin.y + block.offset.y,
          z: task.origin.z + block.offset.z,
          dimension: task.origin.dimension,
        })),
      );
    const futureWaterSupport = {
      ...nextLayerTarget,
      y: nextLayerTarget.y - 1,
      z: nextLayerTarget.z - 1,
    };
    expect(builtPositions).not.toContainEqual(futureWaterSupport);
    expect(builtPositions).toContainEqual({
      ...firstTarget,
      y: firstTarget.y - 1,
      z: firstTarget.z - 1,
    });
  });

  it("does not report End portal activation before portal blocks appear", async () => {
    const driver = new FakeBeatGameDriver();
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:end_portal_frame") === true
        ? [
            {
              blockId: "minecraft:end_portal_frame",
              position: {
                x: 1,
                y: 32,
                z: 1,
                dimension: "minecraft:overworld",
              },
              properties: { eye: "false" },
              diggable: false,
              replaceable: false,
              interactive: true,
              observedAt: "2026-01-01T00:00:01.000Z",
            },
          ]
        : [];

    const exit = await Effect.runPromiseExit(
      activateEndPortal(driver, {
        confirmationAttempts: 1,
        confirmationDelayMs: 0,
      }),
    );

    expect(exit._tag).toBe("Failure");
    expect(driver.actions).toContainEqual({
      type: "interact-block",
      position: {
        x: 1,
        y: 32,
        z: 1,
        dimension: "minecraft:overworld",
      },
      face: "up",
      hand: "main",
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("reselects an eye after every reachable portal-frame path and preserves the room", async () => {
    const driver = new FakeBeatGameDriver();
    const frames = [1, 2].map((x) => ({
      blockId: "minecraft:end_portal_frame",
      position: {
        x,
        y: 32,
        z: 1,
        dimension: "minecraft:overworld",
      },
      properties: { eye: "false" },
      diggable: false,
      replaceable: false,
      interactive: true,
      observedAt: "2026-01-01T00:00:01.000Z",
    }));
    const filled = new Set<number>();
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") === true) {
        return filled.size === frames.length
          ? [
              {
                ...required(frames[0], "the first portal frame"),
                blockId: "minecraft:end_portal",
                properties: {},
              },
            ]
          : [];
      }
      if (selector.blockIds?.includes("minecraft:end_portal_frame") !== true) {
        return [];
      }
      return frames.filter(
        ({ position }) =>
          !filled.has(position.x) && (radius > 0.5 || Math.floor(center.x) === position.x),
      );
    };
    driver.actionObserver = (action) => {
      if (action.type === "interact-block") {
        filled.add(action.position.x);
      }
    };

    const activated = await Effect.runPromise(
      activateEndPortal(driver, {
        confirmationAttempts: 1,
        confirmationDelayMs: 0,
      }),
    );

    expect(activated).toBe(2);
    expect(driver.actions.map(({ type }) => type)).toEqual([
      "select-item",
      "interact-block",
      "select-item",
      "interact-block",
    ]);
    expect(driver.paths).toHaveLength(2);
    expect(driver.paths.every(({ radius }) => radius === 3)).toBe(true);
    expect(driver.paths.every(({ policy }) => !policy.allowMining && !policy.allowPlacing)).toBe(
      true,
    );
    expect(driver.activeControlScopes).toBe(0);
  });

  it("fills portal frames already within reach without repositioning", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      position: {
        x: 2.5,
        y: 3,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    const frame = blockObservation(
      {
        x: 2,
        y: 2,
        z: 2,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:end_portal_frame",
        properties: { eye: "false" },
        diggable: false,
        interactive: true,
      },
    );
    let filled = false;
    driver.blockQueryResolver = ({ selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") === true) {
        return filled
          ? [
              blockObservation(frame.position, {
                blockId: "minecraft:end_portal",
              }),
            ]
          : [];
      }
      return selector.blockIds?.includes("minecraft:end_portal_frame") === true && !filled
        ? [frame]
        : [];
    };
    driver.actionObserver = (action) => {
      if (action.type === "interact-block") {
        filled = true;
      }
    };

    const activated = await Effect.runPromise(
      activateEndPortal(driver, {
        confirmationAttempts: 1,
        confirmationDelayMs: 0,
      }),
    );

    expect(activated).toBe(1);
    expect(driver.paths).toHaveLength(0);
    expect(driver.actions).toContainEqual({
      type: "interact-block",
      position: frame.position,
      face: "up",
      hand: "main",
    });
  });

  it("waits for the End dimension before completing portal entry", async () => {
    const driver = new FakeBeatGameDriver();
    const portals = Array.from({ length: 9 }, (_, index) => ({
      x: 1 + (index % 3),
      y: 31,
      z: 1 + Math.floor(index / 3),
      dimension: "minecraft:overworld",
    }));
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:end_portal") === true
        ? portals.map((portal) =>
            blockObservation(portal, {
              blockId: "minecraft:end_portal",
              replaceable: true,
            }),
          )
        : [];
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          position,
        });
      });
    driver.observationResolver = () =>
      Effect.sync(() => {
        if (
          driver.actions.some((action) => action.type === "set-movement" && action.forward === true)
        ) {
          return observation({
            dimension: "minecraft:the_end",
            position: {
              x: 100,
              y: 49,
              z: 0,
              dimension: "minecraft:the_end",
            },
          });
        }
        const look = driver.actions.findLast((action) => action.type === "look");
        driver.currentObservation = observation({
          position: {
            ...driver.currentObservation.player.position,
            dimension: "minecraft:overworld",
          },
          rotation: {
            yaw: look?.type === "look" ? look.yaw : 0,
            pitch: look?.type === "look" ? look.pitch : 0,
          },
        });
        return driver.currentObservation;
      });

    await Effect.runPromise(
      enterEndPortal(driver, {
        transitionTimeoutMs: 100,
      }),
    );

    expect(driver.paths).toHaveLength(0);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      jump: true,
      sprint: false,
    });
    expect(driver.actions).toContainEqual({ type: "reset-movement" });
    expect(driver.activeControlScopes).toBe(0);
    expect(driver.maximumActiveControlScopes).toBe(1);
  });

  it("builds a reachable End portal approach when the bot is below the rim", async () => {
    const driver = new FakeBeatGameDriver();
    const portals = Array.from({ length: 9 }, (_, index) => ({
      x: 1 + (index % 3),
      y: 31,
      z: 1 + Math.floor(index / 3),
      dimension: "minecraft:overworld",
    }));
    const placed = new Set<string>();
    driver.currentObservation = observation({
      counts: { "minecraft:cobblestone": 8 },
      position: {
        x: -4,
        y: 30,
        z: 2,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") === true) {
        return portals.map((portal) =>
          blockObservation(portal, {
            blockId: "minecraft:end_portal",
            replaceable: true,
          }),
        );
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: "minecraft:overworld",
      };
      const key = `${position.x}:${position.y}:${position.z}`;
      const solid = position.y <= 28 || placed.has(key);
      return [
        blockObservation(
          position,
          solid
            ? {}
            : {
                blockId: "minecraft:air",
                diggable: true,
                replaceable: true,
              },
        ),
      ];
    };
    driver.actionObserver = (action) => {
      if (action.type === "place-block" && action.face === "up") {
        placed.add(`${action.against.x}:${action.against.y + 1}:${action.against.z}`);
      }
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          counts: { "minecraft:cobblestone": 8 },
          position,
        });
      });
    driver.observationResolver = () =>
      Effect.sync(() => {
        if (
          driver.actions.some((action) => action.type === "set-movement" && action.forward === true)
        ) {
          return observation({
            dimension: "minecraft:the_end",
            position: {
              x: 100,
              y: 49,
              z: 0,
              dimension: "minecraft:the_end",
            },
          });
        }
        const look = driver.actions.findLast((action) => action.type === "look");
        return {
          ...driver.currentObservation,
          player: {
            ...driver.currentObservation.player,
            rotation: {
              yaw: look?.type === "look" ? look.yaw : 0,
              pitch: look?.type === "look" ? look.pitch : 0,
            },
          },
        };
      });

    await Effect.runPromise(
      enterEndPortal(driver, {
        transitionTimeoutMs: 100,
      }),
    );

    expect(driver.actions.filter(({ type }) => type === "place-block")).toHaveLength(2);
    expect(driver.paths.map(({ position }) => position)).toEqual([
      {
        x: -1,
        y: 31,
        z: 2,
        dimension: "minecraft:overworld",
      },
    ]);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      jump: true,
      sprint: false,
    });
    expect(driver.maximumActiveControlScopes).toBe(1);
  });

  it("waits for a dragon or a world-level defeat result", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
    });
    let resultQueries = 0;
    driver.blockQueryResolver = ({ selector }) => {
      if (selector.blockIds?.includes("minecraft:dragon_egg") !== true) {
        return [];
      }
      resultQueries += 1;
      return resultQueries < 2
        ? []
        : [
            blockObservation(
              {
                x: 0,
                y: 64,
                z: 0,
                dimension: "minecraft:the_end",
              },
              {
                blockId: "minecraft:dragon_egg",
              },
            ),
          ];
    };

    await Effect.runPromise(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 2,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(resultQueries).toBe(2);
    expect(driver.tasks).toHaveLength(0);
  });

  it("does not infer a dragon kill from one empty entity query", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
    });

    const exit = await Effect.runPromiseExit(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 1,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(exit._tag).toBe("Failure");
  });

  it("sets off beds under a perched dragon's head behind a wall", async () => {
    const driver = new FakeBeatGameDriver();
    let beds = 2;
    const end = (counts: Record<string, number>) =>
      observation({
        dimension: "minecraft:the_end",
        position: driver.currentObservation.player.position,
        counts,
      });
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
      position: { x: 0.5, y: 61, z: 12.5, dimension: "minecraft:the_end" },
      counts: { "minecraft:red_bed": beds, "minecraft:cobblestone": 8 },
    });
    // As measured in a real game: perched on the portal, facing the bot.
    driver.entityResults = [
      {
        connectionEpoch: "epoch-1",
        networkId: 42,
        entityType: "minecraft:ender_dragon",
        position: { x: 0.5, y: 64, z: 0.5, dimension: "minecraft:the_end" },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        health: 200,
        observedAt: "2026-01-01T00:00:01.000Z",
      },
    ];
    driver.entityQueryResolver = (query) =>
      query.selector.entityTypes?.includes("minecraft:ender_dragon") === true
        ? driver.entityResults
        : [];
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") === true) {
        return driver.entityResults.length === 0
          ? [
              blockObservation(
                { x: 0, y: 64, z: 0, dimension: "minecraft:the_end" },
                { blockId: "minecraft:end_portal" },
              ),
            ]
          : [];
      }
      if (selector.blockIds !== undefined) {
        return [];
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      return [
        blockObservation(
          position,
          position.y <= 60
            ? { blockId: "minecraft:end_stone" }
            : { blockId: "minecraft:air", diggable: false, replaceable: true, solid: false },
        ),
      ];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          dimension: "minecraft:the_end",
          position,
          counts: driver.currentObservation.inventory.counts,
        });
      });
    driver.actionObserver = (action) => {
      if (action.type === "interact-block") {
        beds -= 1;
        driver.currentObservation = end({ "minecraft:red_bed": beds, "minecraft:cobblestone": 7 });
        if (beds === 0) {
          driver.entityResults = [];
        }
      }
    };

    await Effect.runPromise(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 1,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(driver.paths[0]?.position).toMatchObject({ x: 0.5, y: 61, z: 10.5 });
    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: { x: 0, y: 60, z: 9, dimension: "minecraft:the_end" },
      face: "up",
      hand: "main",
    });
    expect(
      driver.actions.filter(
        (action) =>
          action.type === "place-block" &&
          action.against.z === 7 &&
          action.against.y === 60 &&
          action.face === "up",
      ),
    ).toHaveLength(2);
    expect(
      driver.actions.filter(
        (action) => action.type === "interact-block" && action.position.z === 7,
      ),
    ).toHaveLength(2);
    expect(driver.tasks.some((task) => task.type === "ranged-attack")).toBe(false);
  });

  it("walks onto the line a perched dragon's head points along", async () => {
    const driver = new FakeBeatGameDriver();
    let beds = 2;
    const end = (counts: Record<string, number>) =>
      observation({
        dimension: "minecraft:the_end",
        position: driver.currentObservation.player.position,
        counts,
      });
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
      position: { x: 0.5, y: 61, z: 12.5, dimension: "minecraft:the_end" },
      counts: { "minecraft:red_bed": beds, "minecraft:cobblestone": 8 },
    });
    // As measured in a real game: perched on the portal, facing the bot.
    driver.entityResults = [
      {
        connectionEpoch: "epoch-1",
        networkId: 42,
        entityType: "minecraft:ender_dragon",
        position: { x: 0.5, y: 64, z: 0.5, dimension: "minecraft:the_end" },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        health: 200,
        // A yaw of 90 points the dragon's head east, away from the bot.
        rotation: { yaw: 90, pitch: 0 },
        observedAt: "2026-01-01T00:00:01.000Z",
      },
    ];
    driver.entityQueryResolver = (query) =>
      query.selector.entityTypes?.includes("minecraft:ender_dragon") === true
        ? driver.entityResults
        : [];
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") === true) {
        return driver.entityResults.length === 0
          ? [
              blockObservation(
                { x: 0, y: 64, z: 0, dimension: "minecraft:the_end" },
                { blockId: "minecraft:end_portal" },
              ),
            ]
          : [];
      }
      if (selector.blockIds !== undefined) {
        return [];
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      return [
        blockObservation(
          position,
          position.y <= 60
            ? { blockId: "minecraft:end_stone" }
            : { blockId: "minecraft:air", diggable: false, replaceable: true, solid: false },
        ),
      ];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          dimension: "minecraft:the_end",
          position,
          counts: driver.currentObservation.inventory.counts,
        });
      });
    driver.actionObserver = (action) => {
      if (action.type === "interact-block") {
        beds -= 1;
        driver.currentObservation = end({ "minecraft:red_bed": beds, "minecraft:cobblestone": 7 });
        if (beds === 0) {
          driver.entityResults = [];
        }
      }
    };

    await Effect.runPromise(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 1,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(driver.paths[0]?.position).toMatchObject({ x: 10.5, y: 61, z: 0.5 });
    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: { x: 9, y: 60, z: 0, dimension: "minecraft:the_end" },
      face: "up",
      hand: "main",
    });
    expect(
      driver.actions.filter(
        (action) =>
          action.type === "interact-block" && action.position.x === 7 && action.position.z === 0,
      ),
    ).toHaveLength(2);
  });

  it("keeps ranged dragon attacks stationary near the void", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
    });
    driver.entityResults = [
      {
        connectionEpoch: "epoch-1",
        networkId: 42,
        entityType: "minecraft:ender_dragon",
        position: {
          x: 8,
          y: 67,
          z: 0,
          dimension: "minecraft:the_end",
        },
        velocity: { x: 0, y: 0, z: 0 },
        alive: true,
        health: 1,
        observedAt: "2026-01-01T00:00:01.000Z",
      },
    ];
    driver.taskObserver = (task) => {
      if (task.type === "ranged-attack") {
        driver.entityResults = [];
      }
    };
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:end_portal") === true
        ? [
            blockObservation(
              {
                x: 0,
                y: 64,
                z: 0,
                dimension: "minecraft:the_end",
              },
              {
                blockId: "minecraft:end_portal",
              },
            ),
          ]
        : [];

    await Effect.runPromise(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 1,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(driver.tasks).toContainEqual(
      objectContaining({
        type: "ranged-attack",
        strafe: false,
      }),
    );
  });

  it("waits for world confirmation while the defeated dragon still renders", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
    });
    const dyingDragon = {
      connectionEpoch: "epoch-1",
      networkId: 42,
      entityType: "minecraft:ender_dragon",
      position: {
        x: 8,
        y: 67,
        z: 0,
        dimension: "minecraft:the_end",
      },
      velocity: { x: 0, y: 0, z: 0 },
      alive: true,
      health: 1,
      observedAt: "2026-01-01T00:00:01.000Z",
    } as const;
    driver.entityQueryResolver = ({ selector }) =>
      selector.entityTypes?.includes("minecraft:ender_dragon") === true ? [dyingDragon] : [];
    let resultQueries = 0;
    driver.blockQueryResolver = ({ selector }) => {
      if (selector.blockIds?.includes("minecraft:end_portal") !== true) {
        return [];
      }
      resultQueries += 1;
      return resultQueries < 2
        ? []
        : [
            blockObservation(
              {
                x: 0,
                y: 64,
                z: 0,
                dimension: "minecraft:the_end",
              },
              {
                blockId: "minecraft:end_portal",
              },
            ),
          ];
    };

    await Effect.runPromise(
      fightEnderDragon(driver, {
        defeatConfirmationAttempts: 2,
        defeatConfirmationDelayMs: 0,
      }),
    );

    expect(resultQueries).toBe(2);
    expect(driver.tasks).toContainEqual(
      objectContaining({
        type: "attack-nearest",
        maximumTargets: 1,
      }),
    );
  });

  it("caps nearest-target attacks at the protocol radius limit", async () => {
    const driver = new FakeBeatGameDriver();

    await Effect.runPromise(
      attackNearest(driver, {
        selector: { entityTypes: ["minecraft:ender_dragon"] },
        radius: 256,
        maximumTargets: 1,
      }),
    );

    expect(driver.tasks).toContainEqual({
      type: "attack-nearest",
      selector: { entityTypes: ["minecraft:ender_dragon"] },
      radius: 128,
      maximumTargets: 1,
    });
  });

  it("teleports the dragon egg and drops it onto a torch", async () => {
    const driver = new FakeBeatGameDriver();
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
      counts: {
        "minecraft:cobblestone": 2,
        "minecraft:torch": 1,
      },
    });
    const initial = {
      x: 0,
      y: 65,
      z: 0,
      dimension: "minecraft:the_end",
    };
    const moved = {
      x: 5,
      y: 66,
      z: 3,
      dimension: "minecraft:the_end",
    };
    let teleported = false;
    let selectedItem = "minecraft:cobblestone";
    const blocks = new Map<string, ReturnType<typeof blockObservation>>([
      [blockKey({ ...moved, y: moved.y - 1 }), blockObservation({ ...moved, y: moved.y - 1 })],
    ]);
    driver.blockQueryResolver = ({ center, selector }) => {
      if (selector.blockIds?.includes("minecraft:dragon_egg") === true) {
        return [
          {
            blockId: "minecraft:dragon_egg",
            position: teleported ? moved : initial,
            properties: {},
            diggable: true,
            replaceable: false,
            interactive: false,
            observedAt: "2026-01-01T00:00:01.000Z",
          },
        ];
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
        dimension: center.dimension,
      };
      return [
        blocks.get(blockKey(position)) ??
          blockObservation(position, {
            blockId: "minecraft:air",
            replaceable: true,
            solid: false,
          }),
      ];
    };
    driver.actionObserver = (action) => {
      if (action.type === "select-item") {
        selectedItem = action.selector.itemIds?.[0] ?? selectedItem;
        return;
      }
      if (action.type === "place-block") {
        const offset = {
          down: { x: 0, y: -1, z: 0 },
          up: { x: 0, y: 1, z: 0 },
          north: { x: 0, y: 0, z: -1 },
          south: { x: 0, y: 0, z: 1 },
          west: { x: -1, y: 0, z: 0 },
          east: { x: 1, y: 0, z: 0 },
        }[action.face];
        const target = {
          x: action.against.x + offset.x,
          y: action.against.y + offset.y,
          z: action.against.z + offset.z,
          dimension: action.against.dimension,
        };
        blocks.set(
          blockKey(target),
          blockObservation(target, {
            blockId: selectedItem,
            solid: selectedItem !== "minecraft:torch",
          }),
        );
        return;
      }
      if (action.type !== "dig-block") {
        return;
      }
      blocks.delete(blockKey(action.position));
      if (action.position.y === initial.y) {
        teleported = true;
      }
      if (action.position.y === moved.y - 1) {
        driver.currentObservation = observation({
          dimension: "minecraft:the_end",
          counts: {
            "minecraft:cobblestone": 2,
            "minecraft:torch": 1,
            "minecraft:dragon_egg": 1,
          },
        });
      }
    };

    await Effect.runPromise(
      collectDragonEgg(driver, {
        confirmationAttempts: 2,
        confirmationDelayMs: 0,
      }),
    );

    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: { ...moved, y: moved.y - 3 },
      face: "up",
      hand: "main",
    });
    expect(driver.actions).toContainEqual({
      type: "place-block",
      against: { ...moved, y: moved.y - 1 },
      face: "down",
      hand: "main",
    });
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: { ...moved, y: moved.y - 1 },
    });
    expect(driver.activeControlScopes).toBe(0);
  });

  it("enters the End exit portal and performs the credits respawn", async () => {
    const driver = new FakeBeatGameDriver();
    const initialPosition = {
      x: 0.5,
      y: 64,
      z: -3.5,
      dimension: "minecraft:the_end",
    };
    driver.currentObservation = observation({
      dimension: "minecraft:the_end",
      position: initialPosition,
    });
    const portals = [-1, 0, 1].flatMap((x) =>
      [-1, 0, 1].map((z) => ({
        blockId: "minecraft:end_portal",
        position: {
          x,
          y: 63,
          z,
          dimension: "minecraft:the_end",
        },
        properties: {},
        diggable: false,
        replaceable: false,
        interactive: false,
        observedAt: "2026-01-01T00:00:01.000Z",
      })),
    );
    driver.blockQueryResolver = ({ selector }) =>
      selector.blockIds?.includes("minecraft:end_portal") === true ? portals : [];
    driver.events = Stream.fromEffect(
      Effect.sleep(10).pipe(
        Effect.as({
          type: "game-event" as const,
          observedAt: "2026-01-01T00:00:02.000Z",
          event: "win_game",
          parameter: 1,
        }),
      ),
    );
    driver.actionObserver = (action) => {
      if (action.type === "look") {
        driver.currentObservation = observation({
          dimension: "minecraft:the_end",
          position: driver.currentObservation.player.position,
          rotation: { yaw: action.yaw, pitch: action.pitch },
        });
      }
      if (action.type === "set-movement" && action.jump === true) {
        driver.currentObservation = observation({
          dimension: "minecraft:the_end",
          position: {
            x: 0.5,
            y: 64,
            z: -1.5,
            dimension: "minecraft:the_end",
          },
          rotation: driver.currentObservation.player.rotation,
        });
      }
      if (action.type === "respawn") {
        driver.currentObservation = observation({
          dimension: "minecraft:overworld",
        });
      }
    };

    await Effect.runPromise(
      exitEnd(driver, {
        confirmationAttempts: 2,
        confirmationDelayMs: 0,
      }),
    );

    expect(driver.paths).toEqual([]);
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      jump: true,
      sprint: false,
    });
    expect(driver.actions).toContainEqual({
      type: "set-movement",
      forward: true,
      sprint: false,
    });
    expect(driver.actions).toContainEqual({ type: "respawn" });
    expect(driver.activeControlScopes).toBe(0);
  });
});
