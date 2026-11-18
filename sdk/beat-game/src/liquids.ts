import { Effect } from "effect";

import type { BeatGameDriver } from "./driver.js";
import { BeatGameDriverError } from "./errors.js";
import { distanceSquared } from "./geometry.js";
import type {
  BeatGameBlockObservation,
  BeatGameBlockPosition,
  BeatGameObservation,
  BeatGamePathPolicy,
  BeatGamePosition,
} from "./model.js";

export const LIQUID_INTERACTION_REACH = 4.5;
export const LIQUID_INTERACTION_STAND_RADIUS = 0.75;

const LIQUID_INTERACTION_FALLBACK_STAND_RADIUS = 1.1;
const LIQUID_INTERACTION_APPROACH_SEGMENT_LENGTH = 12;
const LIQUID_INTERACTION_APPROACH_SEGMENT_RADIUS = 2;
const LIQUID_INTERACTION_APPROACH_MINIMUM_PROGRESS = 2;
const LIQUID_INTERACTION_APPROACH_MAXIMUM_SEGMENTS = 8;
const LIQUID_SOURCE_CLUSTER_RADIUS = 8;
const PREFERRED_LIQUID_SOURCE_CLUSTER_SIZE = 9;
const LIQUID_INTERACTION_VOLUME_RADIUS = 4.9;
const LIQUID_INTERACTION_VOLUME_MAXIMUM_RESULTS = 500;
const LIQUID_SOURCE_ROOF_CLEARING_BLOCKS = 4;
const LIQUID_SOURCE_DIG_TOOL_IDS = [
  "minecraft:netherite_pickaxe",
  "minecraft:diamond_pickaxe",
  "minecraft:iron_pickaxe",
  "minecraft:stone_pickaxe",
  "minecraft:wooden_pickaxe",
  "minecraft:golden_pickaxe",
] as const;
const FLUID_BLOCK_IDS = new Set([
  "minecraft:water",
  "minecraft:bubble_column",
  "minecraft:kelp",
  "minecraft:kelp_plant",
  "minecraft:seagrass",
  "minecraft:tall_seagrass",
  "minecraft:lava",
]);

export interface ApproachLiquidSourceOptions {
  readonly path: BeatGamePathPolicy;
  readonly requireTargetableSource?: boolean;
}

/**
 * Finds a dry, supported stand that can reach one of the supplied liquid
 * sources. It first tries existing open space, then permits the pathfinder to
 * excavate a two-block-high approach without asking it to enter a fluid.
 */
export function approachLiquidSourceFromSide(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  sources: readonly BeatGameBlockObservation[],
  options: ApproachLiquidSourceOptions,
): Effect.Effect<BeatGameBlockObservation, BeatGameDriverError> {
  return Effect.gen(function* () {
    const nearbySources = [...sources].toSorted(
      (left, right) =>
        Math.min(PREFERRED_LIQUID_SOURCE_CLUSTER_SIZE, liquidSourceClusterSize(right, sources)) -
          Math.min(PREFERRED_LIQUID_SOURCE_CLUSTER_SIZE, liquidSourceClusterSize(left, sources)) ||
        Math.abs(observation.player.position.y - left.position.y) -
          Math.abs(observation.player.position.y - right.position.y) ||
        distanceSquared(observation.player.position, left.position) -
          distanceSquared(observation.player.position, right.position),
    );
    for (const source of nearbySources) {
      const targetable = yield* targetableLiquidSourceFromCurrentPosition(
        driver,
        source,
        nearbySources,
      );
      if (targetable !== undefined) {
        return targetable;
      }
    }
    const preparedSources = (yield* Effect.forEach(
      nearbySources,
      (source) =>
        queryLiquidInteractionVolume(driver, source.position).pipe(
          Effect.map((blocks) => {
            const candidates = liquidInteractionStandCandidates(source.position)
              .map((position) => ({
                position,
                sightlineObstructions: liquidSightlineObstructions(
                  blocks,
                  position,
                  source.position,
                ),
              }))
              .toSorted(
                (left, right) =>
                  left.sightlineObstructions.length - right.sightlineObstructions.length ||
                  distanceSquared(observation.player.position, left.position) -
                    distanceSquared(observation.player.position, right.position),
              );
            return { source, blocks, candidates };
          }),
        ),
      { concurrency: 1 },
    )).toSorted(
      (left, right) =>
        liquidSourceExposureCount(right.blocks, right.source.position) -
        liquidSourceExposureCount(left.blocks, left.source.position),
    );

    const attemptedDryStands = new Set<string>();
    for (const prepared of preparedSources) {
      for (const candidate of prepared.candidates) {
        const key = positionKey(candidate.position);
        if (
          attemptedDryStands.has(key) ||
          (options.requireTargetableSource === true &&
            candidate.sightlineObstructions.length > 0 &&
            !canClearLiquidSourceRoofSightline(
              prepared.source.position,
              candidate.sightlineObstructions,
            ))
        ) {
          continue;
        }
        const liveBlocks = yield* queryLiquidInteractionStandBlocks(
          driver,
          candidate.position,
          prepared.blocks,
        );
        if (!isSafeLiquidInteractionStand(liveBlocks, candidate.position)) {
          continue;
        }
        attemptedDryStands.add(key);
        const reached = yield* pathfindToLiquidInteractionStand(
          driver,
          candidate.position,
          options.path,
          false,
        );
        if (reached) {
          yield* driver.act({ type: "reset-movement" });
          if (options.requireTargetableSource !== true) {
            return prepared.source;
          }
          const targetable = yield* targetableLiquidSourceFromCurrentPosition(
            driver,
            prepared.source,
            nearbySources,
          );
          if (targetable !== undefined) {
            return targetable;
          }
          const exposed = yield* exposeLiquidSourceThroughRoof(
            driver,
            prepared.source,
            nearbySources,
          );
          if (exposed !== undefined) {
            return exposed;
          }
        }
      }
    }

    const attemptedExcavationStands = new Set<string>();
    for (const prepared of preparedSources) {
      for (const candidate of prepared.candidates) {
        const key = positionKey(candidate.position);
        if (
          attemptedExcavationStands.has(key) ||
          (options.requireTargetableSource === true &&
            !standExcavationClearsSightline(candidate.position, candidate.sightlineObstructions))
        ) {
          continue;
        }
        const liveBlocks = yield* queryLiquidInteractionStandBlocks(
          driver,
          candidate.position,
          prepared.blocks,
        );
        if (
          !isExcavatableLiquidInteractionStand(
            prepared.source.position,
            liveBlocks,
            candidate.position,
          )
        ) {
          continue;
        }
        attemptedExcavationStands.add(key);
        const reached = yield* pathfindToLiquidInteractionStand(
          driver,
          candidate.position,
          options.path,
          true,
        );
        if (reached) {
          yield* driver.act({ type: "reset-movement" });
          if (options.requireTargetableSource !== true) {
            return prepared.source;
          }
          const targetable = yield* targetableLiquidSourceFromCurrentPosition(
            driver,
            prepared.source,
            nearbySources,
          );
          if (targetable !== undefined) {
            return targetable;
          }
        }
      }
    }
    return yield* Effect.fail(
      new BeatGameDriverError({
        operation: "approach-liquid-source",
        code: "unreachable",
        retryable: true,
        message: `Could not reach, excavate, or target a safe side-on stand beside ${
          nearbySources.length
        } nearby liquid source${nearbySources.length === 1 ? "" : "s"}`,
      }),
    );
  });
}

function liquidSourceClusterSize(
  source: BeatGameBlockObservation,
  sources: readonly BeatGameBlockObservation[],
): number {
  return sources.filter(
    (candidate) =>
      candidate.position.dimension === source.position.dimension &&
      distanceSquared(candidate.position, source.position) <= LIQUID_SOURCE_CLUSTER_RADIUS ** 2,
  ).length;
}

function targetableLiquidSourceFromCurrentPosition(
  driver: BeatGameDriver,
  aimedSource: BeatGameBlockObservation,
  acceptableSources: readonly BeatGameBlockObservation[],
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const current = yield* driver.observe;
    const eyePosition = {
      ...current.player.position,
      y: current.player.position.y + 1.62,
    };
    const sourceCenter = blockCenter(aimedSource.position);
    const direction = {
      x: sourceCenter.x - eyePosition.x,
      y: sourceCenter.y - eyePosition.y,
      z: sourceCenter.z - eyePosition.z,
    };
    const sourceDistance = Math.sqrt(distanceSquared(eyePosition, sourceCenter));
    if (sourceDistance > LIQUID_INTERACTION_REACH) {
      return undefined;
    }
    const obstruction = (yield* driver.raycast({
      direction,
      maximumDistance: Math.min(LIQUID_INTERACTION_REACH, sourceDistance + 0.05),
      includeFluids: true,
    })).block;
    if (obstruction === undefined) {
      return undefined;
    }
    const targetedSource = acceptableSources.find((source) =>
      sameBlockPosition(obstruction.position, source.position),
    );
    if (targetedSource !== undefined) {
      return targetedSource;
    }
    return isFlowingFluid(obstruction) &&
      (yield* hasNoSolidLiquidSightlineObstruction(
        driver,
        direction,
        Math.min(LIQUID_INTERACTION_REACH, sourceDistance + 0.05),
        aimedSource.position,
      ))
      ? aimedSource
      : undefined;
  });
}

function hasNoSolidLiquidSightlineObstruction(
  driver: BeatGameDriver,
  direction: Readonly<{ x: number; y: number; z: number }>,
  maximumDistance: number,
  source: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return driver
    .raycast({
      direction,
      maximumDistance,
      includeFluids: false,
    })
    .pipe(
      Effect.map(({ block }) => block === undefined || sameBlockPosition(block.position, source)),
    );
}

function exposeLiquidSourceThroughRoof(
  driver: BeatGameDriver,
  aimedSource: BeatGameBlockObservation,
  acceptableSources: readonly BeatGameBlockObservation[],
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (
      let clearedBlocks = 0;
      clearedBlocks <= LIQUID_SOURCE_ROOF_CLEARING_BLOCKS;
      clearedBlocks += 1
    ) {
      const current = yield* driver.observe;
      const eyePosition = {
        ...current.player.position,
        y: current.player.position.y + 1.62,
      };
      const sourceCenter = blockCenter(aimedSource.position);
      const direction = {
        x: sourceCenter.x - eyePosition.x,
        y: sourceCenter.y - eyePosition.y,
        z: sourceCenter.z - eyePosition.z,
      };
      const sourceDistance = Math.sqrt(distanceSquared(eyePosition, sourceCenter));
      if (sourceDistance > LIQUID_INTERACTION_REACH) {
        return undefined;
      }
      const obstruction = (yield* driver.raycast({
        direction,
        maximumDistance: Math.min(LIQUID_INTERACTION_REACH, sourceDistance + 0.05),
        includeFluids: true,
      })).block;
      if (obstruction === undefined) {
        return undefined;
      }
      const targetable = acceptableSources.find((source) =>
        sameBlockPosition(obstruction.position, source.position),
      );
      if (targetable !== undefined) {
        return targetable;
      }
      if (
        clearedBlocks === LIQUID_SOURCE_ROOF_CLEARING_BLOCKS ||
        obstruction.position.y <= aimedSource.position.y ||
        !obstruction.diggable ||
        obstruction.blockId === "minecraft:obsidian" ||
        isGravityAffectedBlockId(obstruction.blockId) ||
        isPlayerStabilityBlock(current.player.position, obstruction.position)
      ) {
        return undefined;
      }
      const digTool = LIQUID_SOURCE_DIG_TOOL_IDS.find(
        (itemId) => (current.inventory.counts[itemId] ?? 0) > 0,
      );
      if (digTool !== undefined) {
        yield* driver.act({
          type: "select-item",
          selector: { itemIds: [digTool] },
        });
      }
      yield* driver.act({
        type: "dig-block",
        position: obstruction.position,
      });
    }
    return undefined;
  });
}

function canClearLiquidSourceRoofSightline(
  source: BeatGameBlockPosition,
  obstructions: readonly BeatGameBlockObservation[],
): boolean {
  return (
    obstructions.length <= LIQUID_SOURCE_ROOF_CLEARING_BLOCKS &&
    obstructions.every(
      (obstruction) =>
        obstruction.position.y > source.y &&
        obstruction.diggable &&
        obstruction.blockId !== "minecraft:obsidian" &&
        !isGravityAffectedBlockId(obstruction.blockId),
    )
  );
}

function isPlayerStabilityBlock(player: BeatGamePosition, block: BeatGameBlockPosition): boolean {
  if (player.dimension !== block.dimension) {
    return false;
  }
  const body = floorBlockPosition(player);
  return block.x === body.x && block.z === body.z && block.y >= body.y - 1 && block.y <= body.y + 1;
}

function queryLiquidInteractionVolume(
  driver: BeatGameDriver,
  source: BeatGameBlockPosition,
): Effect.Effect<ReadonlyMap<string, BeatGameBlockObservation>, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(source),
      radius: LIQUID_INTERACTION_VOLUME_RADIUS,
      selector: {},
      maximumResults: LIQUID_INTERACTION_VOLUME_MAXIMUM_RESULTS,
    })
    .pipe(
      Effect.map((blocks) => new Map(blocks.map((block) => [positionKey(block.position), block]))),
    );
}

function queryLiquidInteractionStandBlocks(
  driver: BeatGameDriver,
  candidate: BeatGamePosition,
  fallback: ReadonlyMap<string, BeatGameBlockObservation>,
): Effect.Effect<ReadonlyMap<string, BeatGameBlockObservation>, BeatGameDriverError> {
  const body = floorBlockPosition(candidate);
  const positions = [{ ...body, y: body.y - 1 }, body, { ...body, y: body.y + 1 }];
  return Effect.forEach(
    positions,
    (position) =>
      driver
        .queryBlocks({
          center: blockCenter(position),
          radius: 0.25,
          selector: {},
          maximumResults: 1,
        })
        .pipe(
          Effect.map((blocks) =>
            blocks.find((block) => sameBlockPosition(block.position, position)),
          ),
        ),
    { concurrency: "unbounded" },
  ).pipe(
    Effect.map((blocks) => {
      const refreshed = new Map(fallback);
      for (const block of blocks) {
        if (block !== undefined) {
          refreshed.set(positionKey(block.position), block);
        }
      }
      return refreshed;
    }),
  );
}

function pathfindToLiquidInteractionStand(
  driver: BeatGameDriver,
  candidate: BeatGamePosition,
  path: BeatGamePathPolicy,
  allowMining: boolean,
): Effect.Effect<boolean, BeatGameDriverError> {
  const policy = {
    ...path,
    allowMining,
    avoidFluids: true,
    maxFallDistance: Math.min(path.maxFallDistance, 1),
  };
  const attempt = (radius: number) =>
    driver.pathfind(candidate, radius, policy).pipe(Effect.as(true));
  return Effect.gen(function* () {
    let current = (yield* driver.observe).player.position;
    for (let segment = 0; segment < LIQUID_INTERACTION_APPROACH_MAXIMUM_SEGMENTS; segment += 1) {
      const remainingDistance = Math.sqrt(distanceSquared(current, candidate));
      if (remainingDistance <= LIQUID_INTERACTION_APPROACH_SEGMENT_LENGTH) {
        break;
      }
      const progress = LIQUID_INTERACTION_APPROACH_SEGMENT_LENGTH / remainingDistance;
      const waypoint = {
        x: current.x + (candidate.x - current.x) * progress,
        y: current.y + (candidate.y - current.y) * progress,
        z: current.z + (candidate.z - current.z) * progress,
        dimension: candidate.dimension,
      } satisfies BeatGamePosition;
      const reachedWaypoint = yield* driver
        .pathfind(waypoint, LIQUID_INTERACTION_APPROACH_SEGMENT_RADIUS, {
          ...policy,
          allowMining: path.allowMining,
        })
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        );
      if (!reachedWaypoint) {
        return false;
      }
      const latest = (yield* driver.observe).player.position;
      const latestDistance = Math.sqrt(distanceSquared(latest, candidate));
      if (
        latest.dimension !== candidate.dimension ||
        remainingDistance - latestDistance < LIQUID_INTERACTION_APPROACH_MINIMUM_PROGRESS
      ) {
        return false;
      }
      current = latest;
    }
    if (
      Math.sqrt(distanceSquared(current, candidate)) > LIQUID_INTERACTION_APPROACH_SEGMENT_LENGTH
    ) {
      return false;
    }
    return yield* attempt(LIQUID_INTERACTION_STAND_RADIUS).pipe(
      Effect.catchTag("BeatGameDriverError", () =>
        attempt(LIQUID_INTERACTION_FALLBACK_STAND_RADIUS),
      ),
      Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
    );
  });
}

function liquidInteractionStandCandidates(source: BeatGameBlockPosition): BeatGamePosition[] {
  const sourceCenter = blockCenter(source);
  const candidates: BeatGamePosition[] = [];
  const horizontalReach = Math.floor(LIQUID_INTERACTION_REACH);
  for (let xOffset = -horizontalReach; xOffset <= horizontalReach; xOffset++) {
    for (let zOffset = -horizontalReach; zOffset <= horizontalReach; zOffset++) {
      if (xOffset === 0 && zOffset === 0) {
        continue;
      }
      for (let yOffset = -1; yOffset <= 3; yOffset++) {
        const candidate = {
          x: source.x + xOffset + 0.5,
          y: source.y + yOffset,
          z: source.z + zOffset + 0.5,
          dimension: source.dimension,
        };
        const eyePosition = {
          ...candidate,
          y: candidate.y + 1.62,
        };
        if (Math.sqrt(distanceSquared(eyePosition, sourceCenter)) <= LIQUID_INTERACTION_REACH) {
          candidates.push(candidate);
        }
      }
    }
  }
  return candidates;
}

function liquidSourceExposureCount(
  blocks: ReadonlyMap<string, BeatGameBlockObservation>,
  source: BeatGameBlockPosition,
): number {
  return [
    { x: 1, y: 0, z: 0 },
    { x: -1, y: 0, z: 0 },
    { x: 0, y: 1, z: 0 },
    { x: 0, y: -1, z: 0 },
    { x: 0, y: 0, z: 1 },
    { x: 0, y: 0, z: -1 },
  ].filter((offset) => {
    const neighbor = blocks.get(
      positionKey({
        ...source,
        x: source.x + offset.x,
        y: source.y + offset.y,
        z: source.z + offset.z,
      }),
    );
    return neighbor?.replaceable === true && !isFluidBlock(neighbor.blockId);
  }).length;
}

function liquidSightlineObstructions(
  blocks: ReadonlyMap<string, BeatGameBlockObservation>,
  candidate: BeatGamePosition,
  source: BeatGameBlockPosition,
): readonly BeatGameBlockObservation[] {
  const eye = { ...candidate, y: candidate.y + 1.62 };
  const target = blockCenter(source);
  const length = Math.sqrt(distanceSquared(eye, target));
  const samples = Math.max(1, Math.ceil(length * 5));
  const visited = new Set<string>();
  const obstructions: BeatGameBlockObservation[] = [];
  for (let sample = 1; sample < samples; sample += 1) {
    const progress = sample / samples;
    const position = {
      x: Math.floor(eye.x + (target.x - eye.x) * progress),
      y: Math.floor(eye.y + (target.y - eye.y) * progress),
      z: Math.floor(eye.z + (target.z - eye.z) * progress),
      dimension: source.dimension,
    } satisfies BeatGameBlockPosition;
    if (sameBlockPosition(position, source)) {
      continue;
    }
    const key = positionKey(position);
    if (visited.has(key)) {
      continue;
    }
    visited.add(key);
    const block = blocks.get(key);
    if (block !== undefined && !block.replaceable && !isFluidBlock(block.blockId)) {
      obstructions.push(block);
    }
  }
  return obstructions;
}

function standExcavationClearsSightline(
  candidate: BeatGamePosition,
  obstructions: readonly BeatGameBlockObservation[],
): boolean {
  const body = floorBlockPosition(candidate);
  const head = { ...body, y: body.y + 1 };
  return obstructions.every(
    (block) => sameBlockPosition(block.position, body) || sameBlockPosition(block.position, head),
  );
}

function isSafeLiquidInteractionStand(
  blocks: ReadonlyMap<string, BeatGameBlockObservation>,
  candidate: BeatGamePosition,
): boolean {
  const body = floorBlockPosition(candidate);
  const feet = blocks.get(positionKey(body));
  const head = blocks.get(positionKey({ ...body, y: body.y + 1 }));
  const support = blocks.get(positionKey({ ...body, y: body.y - 1 }));
  return (
    feet?.replaceable === true &&
    head?.replaceable === true &&
    isStableSupport(support) &&
    ![feet, head, support].some((block) => block !== undefined && isFluidBlock(block.blockId))
  );
}

function isExcavatableLiquidInteractionStand(
  source: BeatGameBlockPosition,
  blocks: ReadonlyMap<string, BeatGameBlockObservation>,
  candidate: BeatGamePosition,
): boolean {
  const body = floorBlockPosition(candidate);
  const feet = blocks.get(positionKey(body));
  const head = blocks.get(positionKey({ ...body, y: body.y + 1 }));
  const support = blocks.get(positionKey({ ...body, y: body.y - 1 }));
  const horizontalDistance = Math.max(Math.abs(body.x - source.x), Math.abs(body.z - source.z));
  return (
    horizontalDistance >= 2 &&
    isStableSupport(support) &&
    [feet, head].every(
      (block) =>
        block !== undefined &&
        !isFluidBlock(block.blockId) &&
        (block.replaceable || (block.diggable && !isGravityAffectedBlockId(block.blockId))),
    )
  );
}

function isStableSupport(
  block: BeatGameBlockObservation | undefined,
): block is BeatGameBlockObservation {
  return (
    block !== undefined &&
    block.solid === true &&
    !block.replaceable &&
    !isGravityAffectedBlockId(block.blockId)
  );
}

function isGravityAffectedBlockId(blockId: string): boolean {
  return (
    blockId === "minecraft:sand" ||
    blockId === "minecraft:red_sand" ||
    blockId === "minecraft:gravel" ||
    blockId === "minecraft:dragon_egg" ||
    blockId.endsWith("_concrete_powder") ||
    blockId.endsWith("_anvil")
  );
}

function isFluidBlock(blockId: string): boolean {
  return FLUID_BLOCK_IDS.has(blockId);
}

function isFlowingFluid(block: BeatGameBlockObservation): boolean {
  return (
    isFluidBlock(block.blockId) &&
    block.properties.level !== undefined &&
    block.properties.level !== "0"
  );
}

function blockCenter(position: BeatGameBlockPosition): BeatGamePosition {
  return {
    ...position,
    x: position.x + 0.5,
    y: position.y + 0.5,
    z: position.z + 0.5,
  };
}

function floorBlockPosition(position: BeatGamePosition): BeatGameBlockPosition {
  return {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
}

function sameBlockPosition(left: BeatGameBlockPosition, right: BeatGameBlockPosition): boolean {
  return (
    left.x === right.x &&
    left.y === right.y &&
    left.z === right.z &&
    left.dimension === right.dimension
  );
}

function positionKey(position: BeatGameBlockPosition): string {
  return `${position.dimension}:${position.x}:${position.y}:${position.z}`;
}
