import { Effect } from "effect";
import { describe, expect, it } from "vitest";

import { BeatGameDriverError } from "../src/errors.js";
import { approachLiquidSourceFromSide } from "../src/liquids.js";
import { defaultBeatGameStrategy, type BeatGameBlockPosition } from "../src/model.js";
import { blockObservation, FakeBeatGameDriver, observation } from "./fixtures.js";

const standVolume = (stand: BeatGameBlockPosition, obstruction?: BeatGameBlockPosition) => [
  blockObservation(stand, {
    blockId: "minecraft:air",
    replaceable: true,
  }),
  blockObservation(
    { ...stand, y: stand.y + 1 },
    {
      blockId: "minecraft:air",
      replaceable: true,
    },
  ),
  blockObservation({ ...stand, y: stand.y - 1 }),
  ...(obstruction === undefined
    ? []
    : [blockObservation(obstruction, { blockId: "minecraft:stone" })]),
];
const openStandVolume = (stand: BeatGameBlockPosition) => [
  blockObservation(stand, {
    blockId: "minecraft:air",
    replaceable: true,
  }),
  blockObservation(
    { ...stand, y: stand.y + 1 },
    {
      blockId: "minecraft:air",
      replaceable: true,
    },
  ),
  blockObservation({ ...stand, y: stand.y - 1 }),
];
const discoveredVolume = (stand: BeatGameBlockPosition) => [
  blockObservation(stand, {
    blockId: "minecraft:air",
    replaceable: true,
  }),
  blockObservation(
    { ...stand, y: stand.y + 1 },
    {
      blockId: "minecraft:air",
      replaceable: true,
    },
  ),
  blockObservation({ ...stand, y: stand.y - 1 }),
];

describe("lava interaction positioning", () => {
  it("uses a visible source beyond four blocks without replanning", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 3,
        y: 62,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    driver.currentObservation = observation({
      position: {
        x: 0.5,
        y: 64,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.raycastResolver = () => ({ block: source, distance: 3.6 });

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.paths).toHaveLength(0);
    expect(driver.raycasts).toHaveLength(1);
  });

  it("ignores flowing fluid when vanilla can still target its source", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 3,
        y: 62,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:water",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const flowingWater = blockObservation(
      {
        x: 1,
        y: 63,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:water",
        properties: { level: "1" },
        replaceable: true,
      },
    );
    driver.currentObservation = observation({
      position: {
        x: 0.5,
        y: 64,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.raycastResolver = ({ includeFluids }) =>
      includeFluids ? { block: flowingWater, distance: 1 } : { distance: 3.6 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.paths).toHaveLength(0);
    expect(driver.raycasts.map(({ includeFluids }) => includeFluids)).toEqual([true, false]);
  });

  it("skips stands whose sampled sightline is already obstructed", async () => {
    const driver = new FakeBeatGameDriver();
    const blockedSource = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const clearSource = blockObservation(
      {
        x: 8,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const blockedStand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const clearStand = {
      x: 6,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const blockedStandVolume = standVolume(blockedStand, {
      x: 1,
      y: -51,
      z: 0,
      dimension: blockedStand.dimension,
    });
    const clearStandVolume = standVolume(clearStand);
    driver.currentObservation = observation({
      position: {
        x: 4.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length === 0 && radius === 0.25) {
        return [...blockedStandVolume, ...clearStandVolume].filter(
          (block) =>
            block.position.x === Math.floor(center.x) &&
            block.position.y === Math.floor(center.y) &&
            block.position.z === Math.floor(center.z),
        );
      }
      return radius === 4.9 && Object.keys(selector).length === 0
        ? Math.floor(center.x) === blockedSource.position.x
          ? blockedStandVolume
          : clearStandVolume
        : [];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.raycastResolver = () =>
      driver.currentObservation.player.position.x >= 6
        ? { block: clearSource, distance: 3 }
        : { distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(
        driver,
        driver.currentObservation,
        [blockedSource, clearSource],
        {
          path: defaultBeatGameStrategy.path,
          requireTargetableSource: true,
        },
      ),
    );

    expect(selected.position).toEqual(clearSource.position);
    expect(driver.paths).toEqual([
      {
        position: {
          x: clearStand.x + 0.5,
          y: clearStand.y,
          z: clearStand.z + 0.5,
          dimension: clearStand.dimension,
        },
        radius: 0.75,
        policy: expect.objectContaining({
          allowMining: false,
          avoidFluids: true,
          maxFallDistance: 1,
        }),
      },
    ]);
  });

  it("accepts an intervening source exposed from a dense pool", async () => {
    const driver = new FakeBeatGameDriver();
    const aimedSource = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const exposedSource = blockObservation(
      {
        x: 1,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const stand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const standBlocks = [
      blockObservation(stand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...stand, y: stand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation({ ...stand, y: stand.y - 1 }),
    ];
    driver.currentObservation = observation({
      position: {
        x: 8.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length !== 0) {
        return [];
      }
      if (radius === 4.9) {
        return Math.floor(center.x) === aimedSource.position.x ? standBlocks : [];
      }
      return radius === 0.25
        ? standBlocks.filter(
            (block) =>
              block.position.x === Math.floor(center.x) &&
              block.position.y === Math.floor(center.y) &&
              block.position.z === Math.floor(center.z),
          )
        : [];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.raycastResolver = () =>
      driver.paths.length === 0 ? { distance: 6 } : { block: exposedSource, distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(
        driver,
        driver.currentObservation,
        [aimedSource, exposedSource],
        {
          path: defaultBeatGameStrategy.path,
          requireTargetableSource: true,
        },
      ),
    );

    expect(selected.position).toEqual(exposedSource.position);
    expect(driver.paths).toHaveLength(1);
  });

  it("tries an exposed pool boundary before a buried source", async () => {
    const driver = new FakeBeatGameDriver();
    const buriedSource = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const exposedSource = blockObservation(
      {
        x: 8,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const buriedStand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const exposedStand = {
      x: 6,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const buriedVolume = openStandVolume(buriedStand);
    const exposedVolume = [
      ...openStandVolume(exposedStand),
      blockObservation(
        { ...exposedSource.position, y: -51 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
    ];
    driver.currentObservation = observation({
      position: {
        x: 4.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length !== 0) {
        return [];
      }
      const volume =
        Math.floor(center.x) === buriedSource.position.x ? buriedVolume : exposedVolume;
      if (radius === 4.9) {
        return volume;
      }
      return radius === 0.25
        ? [...buriedVolume, ...exposedVolume].filter(
            (block) =>
              block.position.x === Math.floor(center.x) &&
              block.position.y === Math.floor(center.y) &&
              block.position.z === Math.floor(center.z),
          )
        : [];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.raycastResolver = () =>
      driver.currentObservation.player.position.x >= 6
        ? { block: exposedSource, distance: 3 }
        : { distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(
        driver,
        driver.currentObservation,
        [buriedSource, exposedSource],
        {
          path: defaultBeatGameStrategy.path,
          requireTargetableSource: true,
        },
      ),
    );

    expect(selected.position).toEqual(exposedSource.position);
    expect(driver.paths).toEqual([
      expect.objectContaining({
        position: {
          ...exposedStand,
          x: exposedStand.x + 0.5,
          z: exposedStand.z + 0.5,
        },
      }),
    ]);
  });

  it("opens a buried source through its roof from a dry stand", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const roof = blockObservation({
      ...source.position,
      y: source.position.y + 1,
    });
    const stand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const standBlocks = [
      blockObservation(stand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...stand, y: stand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation({ ...stand, y: stand.y - 1 }),
      roof,
    ];
    driver.currentObservation = observation({
      counts: { "minecraft:stone_pickaxe": 1 },
      position: {
        x: 8.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) =>
      Object.keys(selector).length === 0 && radius === 4.9
        ? standBlocks
        : Object.keys(selector).length === 0 && radius === 0.25
          ? standBlocks.filter(
              (block) =>
                block.position.x === Math.floor(center.x) &&
                block.position.y === Math.floor(center.y) &&
                block.position.z === Math.floor(center.z),
            )
          : [];
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position,
        });
      });
    let roofCleared = false;
    driver.raycastResolver = () => ({
      block: roofCleared ? source : roof,
      distance: 2,
    });
    driver.actionObserver = (action) => {
      if (
        action.type === "dig-block" &&
        action.position.x === roof.position.x &&
        action.position.y === roof.position.y &&
        action.position.z === roof.position.z
      ) {
        roofCleared = true;
      }
    };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.actions).toContainEqual({
      type: "dig-block",
      position: roof.position,
    });
  });

  it("excavates a sealed stand when that clears its lava sightline", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const stand = {
      x: 2,
      y: -52,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    driver.currentObservation = observation({
      position: {
        x: 12.5,
        y: -52,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    const standBlocks = [
      blockObservation(stand),
      blockObservation({ ...stand, y: stand.y + 1 }),
      blockObservation({ ...stand, y: stand.y - 1 }),
    ];
    driver.blockQueryResolver = ({ radius, selector }) =>
      radius === 4.9 && Object.keys(selector).length === 0
        ? standBlocks
        : radius === 0.25 && Object.keys(selector).length === 0
          ? standBlocks
          : [];
    driver.pathResolver = (position, radius, policy) => {
      driver.paths.push({ position, radius, policy });
      if (!policy.allowMining) {
        return Effect.fail(
          new BeatGameDriverError({
            operation: "pathfind",
            code: "unreachable",
            retryable: true,
            message: "The sealed stand has no open route",
          }),
        );
      }
      driver.currentObservation = observation({ position });
      return Effect.void;
    };
    driver.raycastResolver = () =>
      driver.paths.length > 0 ? { block: source, distance: 2 } : { distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.paths.at(-1)).toEqual({
      position: {
        x: stand.x + 0.5,
        y: stand.y,
        z: stand.z + 0.5,
        dimension: stand.dimension,
      },
      radius: 0.75,
      policy: expect.objectContaining({
        allowMining: true,
        avoidFluids: true,
        maxFallDistance: 1,
      }),
    });
  });

  it("skips a stand that fills with lava after candidate discovery", async () => {
    const driver = new FakeBeatGameDriver();
    const firstSource = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const secondSource = blockObservation(
      {
        x: 8,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const floodedStand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const safeStand = {
      x: 6,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const floodedStandVolume = discoveredVolume(floodedStand);
    const safeStandVolume = discoveredVolume(safeStand);
    driver.currentObservation = observation({
      position: {
        x: 4.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length !== 0) {
        return [];
      }
      if (radius === 4.9) {
        return Math.floor(center.x) === firstSource.position.x
          ? floodedStandVolume
          : safeStandVolume;
      }
      if (radius !== 0.25) {
        return [];
      }
      const position = {
        x: Math.floor(center.x),
        y: Math.floor(center.y),
        z: Math.floor(center.z),
      };
      if (
        position.x === floodedStand.x &&
        position.y === floodedStand.y &&
        position.z === floodedStand.z
      ) {
        return [
          blockObservation(floodedStand, {
            blockId: "minecraft:lava",
            properties: { level: "1" },
            replaceable: true,
          }),
        ];
      }
      return [...floodedStandVolume, ...safeStandVolume].filter(
        (block) =>
          block.position.x === position.x &&
          block.position.y === position.y &&
          block.position.z === position.z,
      );
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.raycastResolver = () =>
      driver.currentObservation.player.position.x >= 6
        ? { block: secondSource, distance: 3 }
        : { distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [firstSource, secondSource], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(secondSource.position);
    expect(driver.paths).toEqual([
      {
        position: {
          x: safeStand.x + 0.5,
          y: safeStand.y,
          z: safeStand.z + 0.5,
          dimension: safeStand.dimension,
        },
        radius: 0.75,
        policy: expect.objectContaining({
          allowMining: false,
          avoidFluids: true,
          maxFallDistance: 1,
        }),
      },
    ]);
  });

  it("accepts a safe adjacent arrival when the exact stand is unreachable", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 0,
        y: -52,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const stand = {
      x: 2,
      y: -50,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const standBlocks = [
      blockObservation(stand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...stand, y: stand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation({ ...stand, y: stand.y - 1 }),
    ];
    driver.currentObservation = observation({
      position: {
        x: 4.5,
        y: -50,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ radius, selector }) =>
      Object.keys(selector).length === 0 && (radius === 4.9 || radius === 0.25) ? standBlocks : [];
    driver.pathResolver = (position, radius, policy) => {
      driver.paths.push({ position, radius, policy });
      if (radius === 0.75) {
        return Effect.fail(
          new BeatGameDriverError({
            operation: "pathfind",
            code: "unreachable",
            retryable: true,
            message: "The precise stand center is unreachable",
          }),
        );
      }
      driver.currentObservation = observation({ position });
      return Effect.void;
    };
    driver.raycastResolver = () =>
      driver.paths.length > 0 ? { block: source, distance: 2 } : { distance: 2 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.paths.map(({ radius }) => radius)).toEqual([0.75, 1.1]);
  });

  it("approaches a distant source through bounded local path goals", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 40,
        y: 62,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const stand = {
      x: 38,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const standBlocks = [
      blockObservation(stand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...stand, y: stand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation({ ...stand, y: stand.y - 1 }),
    ];
    driver.currentObservation = observation({
      counts: { "minecraft:stone_pickaxe": 1 },
      position: {
        x: 0.5,
        y: 64,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length !== 0) {
        return [];
      }
      if (radius === 4.9) {
        return standBlocks;
      }
      return radius === 0.25
        ? standBlocks.filter(
            (block) =>
              block.position.x === Math.floor(center.x) &&
              block.position.y === Math.floor(center.y) &&
              block.position.z === Math.floor(center.z),
          )
        : [];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({
          counts: driver.currentObservation.inventory.counts,
          position,
        });
      });
    driver.raycastResolver = () =>
      driver.currentObservation.player.position.x >= stand.x
        ? { block: source, distance: 3 }
        : { distance: 6 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    expect(driver.paths).toHaveLength(4);
    expect(driver.paths.slice(0, 3)).toEqual([
      expect.objectContaining({
        position: expect.objectContaining({ x: 12.5 }),
        radius: 2,
        policy: expect.objectContaining({
          allowMining: defaultBeatGameStrategy.path.allowMining,
          avoidFluids: true,
          maxFallDistance: 1,
        }),
      }),
      expect.objectContaining({
        position: expect.objectContaining({ x: 24.5 }),
        radius: 2,
      }),
      expect.objectContaining({
        position: expect.objectContaining({ x: 36.5 }),
        radius: 2,
      }),
    ]);
    expect(driver.paths.at(-1)).toEqual({
      position: {
        x: stand.x + 0.5,
        y: stand.y,
        z: stand.z + 0.5,
        dimension: stand.dimension,
      },
      radius: 0.75,
      policy: expect.objectContaining({
        allowMining: false,
        avoidFluids: true,
        maxFallDistance: 1,
      }),
    });
  });

  it("walks the approach legs without mining when it carries no pickaxe", async () => {
    const driver = new FakeBeatGameDriver();
    const source = blockObservation(
      {
        x: 40,
        y: 62,
        z: 0,
        dimension: "minecraft:overworld",
      },
      {
        blockId: "minecraft:lava",
        properties: { level: "0" },
        replaceable: true,
      },
    );
    const stand = {
      x: 38,
      y: 64,
      z: 0,
      dimension: "minecraft:overworld",
    } as const;
    const standBlocks = [
      blockObservation(stand, {
        blockId: "minecraft:air",
        replaceable: true,
      }),
      blockObservation(
        { ...stand, y: stand.y + 1 },
        {
          blockId: "minecraft:air",
          replaceable: true,
        },
      ),
      blockObservation({ ...stand, y: stand.y - 1 }),
    ];
    driver.currentObservation = observation({
      position: {
        x: 0.5,
        y: 64,
        z: 0.5,
        dimension: "minecraft:overworld",
      },
    });
    driver.blockQueryResolver = ({ center, radius, selector }) => {
      if (Object.keys(selector).length !== 0) {
        return [];
      }
      if (radius === 4.9) {
        return standBlocks;
      }
      return radius === 0.25
        ? standBlocks.filter(
            (block) =>
              block.position.x === Math.floor(center.x) &&
              block.position.y === Math.floor(center.y) &&
              block.position.z === Math.floor(center.z),
          )
        : [];
    };
    driver.pathResolver = (position, radius, policy) =>
      Effect.sync(() => {
        driver.paths.push({ position, radius, policy });
        driver.currentObservation = observation({ position });
      });
    driver.raycastResolver = () =>
      driver.currentObservation.player.position.x >= stand.x
        ? { block: source, distance: 3 }
        : { distance: 6 };

    const selected = await Effect.runPromise(
      approachLiquidSourceFromSide(driver, driver.currentObservation, [source], {
        path: defaultBeatGameStrategy.path,
        requireTargetableSource: true,
      }),
    );

    expect(selected.position).toEqual(source.position);
    // Stone takes seconds per block by hand, so no leg may plan to dig.
    expect(driver.paths.slice(0, 3)).toEqual(
      Array.from({ length: 3 }, () =>
        expect.objectContaining({ policy: expect.objectContaining({ allowMining: false }) }),
      ),
    );
  });
});
