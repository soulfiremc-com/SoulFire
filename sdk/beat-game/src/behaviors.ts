import { Effect, Option, Stream } from "effect";

import { BeatGameDriverError } from "./errors.js";
import {
  createNetherPortalFrame,
  directionFromRotation,
  distanceSquared,
  horizontalDirection,
  rotationToward,
  triangulateStronghold,
  type PortalFrame,
} from "./geometry.js";
import {
  defaultBeatGameStrategy,
  type BeatGameBlockObservation,
  type BeatGameBlockPosition,
  type BeatGameEntityObservation,
  type BeatGameEyeSample,
  type BeatGameItemRequirement,
  type BeatGameObservation,
  type BeatGamePathPolicy,
  type BeatGamePosition,
} from "./model.js";
import { approachLiquidSourceFromSide, LIQUID_INTERACTION_REACH } from "./liquids.js";
import type {
  BeatGameBlockFace,
  BeatGameBuildBlock,
  BeatGameContainerTransfer,
  BeatGameDriver,
  BeatGameEntitySelector,
  BeatGameItemSelector,
  BeatGameLoadoutRequirement,
  BeatGamePrimitiveAction,
  BeatGameQueryBlocks,
  BeatGameTask,
} from "./driver.js";

const NETHER_PORTAL_REENTRY_COOLDOWN_MS = 10_500;
const END_EXIT_PORTAL_ENTRY_TIMEOUT_MS = 1_500;
const MAXIMUM_ATTACK_NEAREST_RADIUS = 128;
const MAXIMUM_OPEN_SPACE_HANDOFF_DEPTH = 8;
// Item positions are continuous while path nodes are block-aligned. This
// radius covers the worst horizontal half-block offset plus one block of
// terrain height without turning a nearby pickup into an unreachable goal.
const DROP_PICKUP_PATH_RADIUS = 1.25;
const SUBMERGED_DROP_PICKUP_PATH_RADIUS = 0.75;
const DROP_PICKUP_MAXIMUM_ATTEMPTS = 3;
const DIRECT_DROP_PICKUP_MAXIMUM_HORIZONTAL_DISTANCE = 1.9;
const DIRECT_DROP_PICKUP_MAXIMUM_VERTICAL_DISTANCE = 0.75;
const SUBMERGED_DROP_PICKUP_MAXIMUM_HORIZONTAL_DISTANCE = 2.25;
const SUBMERGED_DROP_PICKUP_MAXIMUM_VERTICAL_DISTANCE = 8;
const DIRECT_DROP_PICKUP_POLL_INTERVAL_MS = 100;
const DIRECT_DROP_PICKUP_MAXIMUM_POLLS = 8;
const SUBMERGED_DROP_PICKUP_MAXIMUM_POLLS = 60;
const SUBMERGED_DROP_PICKUP_STEERING_INTERVAL_POLLS = 2;
const SUBMERGED_DROP_PICKUP_MINIMUM_AIR_RATIO = 0.35;
const PORTAL_CASTING_LAVA_SIGHT_CLEARING_BLOCKS = 4;
const PORTAL_CASTING_LAVA_COLLECTION_POLLS = 10;
const PORTAL_CASTING_LAVA_SOURCE_ATTEMPTS = 8;
const PORTAL_CASTING_WATER_RECOVERY_ATTEMPTS = 3;
const PORTAL_CASTING_BUCKET_FACE_AIM_HEIGHT = 1 / 64;
const PORTAL_CASTING_SUPPORT_SCAFFOLD_RADIUS = 4.9;
const PORTAL_CASTING_SUPPORT_SCAFFOLD_MAXIMUM_RESULTS = 1_000;
const PORTAL_STAIRCASE_MINIMUM_DEPTH = 2;
const PORTAL_STAIRCASE_MAXIMUM_DEPTH = 64;
const PORTAL_SUPPORT_INTERACTION_REACH = 4.5;
const PORTAL_STAIRCASE_ROUTE_FAILURE_CODES = new Set([
  "path_search_limit",
  "path_quality_bound_not_met",
  "path_no_route",
  "path_stalled",
  "path_action_stalled",
  // Retain compatibility with checkpoints and test drivers that predate the
  // structured path failure codes.
  "search_limit",
  "unreachable",
  "no_progress",
]);
const STAIRCASE_INITIAL_LANDING_ATTEMPTS = 4;
const STAIRCASE_COLLAPSE_RECOVERY_ATTEMPTS = 16;
const CORPSE_TRAVEL_SEGMENT_DISTANCE = 32;
const CORPSE_FINAL_APPROACH_HORIZONTAL_DISTANCE = 12;
const CORPSE_TRAVEL_GOAL_RADIUS = 3;
const MAXIMUM_CORPSE_TRAVEL_SEGMENTS = 32;
const RESPAWN_CONFIRMATION_MAXIMUM_POLLS = 80;
const RESPAWN_CONFIRMATION_POLL_INTERVAL_MS = 100;
const RESPAWN_CHUNK_RADIUS = 4;
const RESPAWN_CHUNK_TIMEOUT_MS = 60_000;
const AVOIDED_FLUID_BLOCK_IDS = [
  "minecraft:water",
  "minecraft:bubble_column",
  "minecraft:kelp",
  "minecraft:kelp_plant",
  "minecraft:seagrass",
  "minecraft:tall_seagrass",
  "minecraft:lava",
] as const;
const LOG_TO_PLANKS = [
  ["minecraft:oak_log", "minecraft:oak_planks"],
  ["minecraft:spruce_log", "minecraft:spruce_planks"],
  ["minecraft:birch_log", "minecraft:birch_planks"],
  ["minecraft:jungle_log", "minecraft:jungle_planks"],
  ["minecraft:acacia_log", "minecraft:acacia_planks"],
  ["minecraft:dark_oak_log", "minecraft:dark_oak_planks"],
  ["minecraft:mangrove_log", "minecraft:mangrove_planks"],
  ["minecraft:cherry_log", "minecraft:cherry_planks"],
  ["minecraft:pale_oak_log", "minecraft:pale_oak_planks"],
  ["minecraft:crimson_stem", "minecraft:crimson_planks"],
  ["minecraft:warped_stem", "minecraft:warped_planks"],
] as const;
const RECIPE_UNLOCK_PREREQUISITES: Readonly<
  Record<string, { readonly itemId: string; readonly count: number }>
> = {
  "minecraft:wooden_pickaxe": {
    itemId: "minecraft:stick",
    count: 2,
  },
  "minecraft:wooden_sword": {
    itemId: "minecraft:stick",
    count: 1,
  },
  "minecraft:ender_eye": {
    itemId: "minecraft:blaze_powder",
    count: 1,
  },
};

export interface BeatGameBehaviorOptions {
  readonly path?: Partial<BeatGamePathPolicy>;
}

export interface AcquireOptions extends BeatGameBehaviorOptions {
  readonly searchRadius?: number;
}

export function acquire(
  driver: BeatGameDriver,
  requirement: BeatGameItemRequirement,
  options: AcquireOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  const missing = Math.max(0, requirement.targetCount - requirement.currentCount);
  if (missing === 0) {
    return Effect.void;
  }
  return collectBlocks(driver, {
    blockIds: requirement.itemIds,
    tags: requirement.tags,
    count: missing,
    searchRadius: options.searchRadius ?? defaultBeatGameStrategy.blockSearchRadius,
    ...(options.path === undefined ? {} : { path: options.path }),
  });
}

export interface CollectBlocksOptions extends BeatGameBehaviorOptions {
  readonly blockIds: readonly string[];
  readonly tags?: readonly string[];
  readonly count: number;
  readonly searchRadius?: number;
  readonly avoidSubmergedTargets?: boolean;
  readonly requireLineOfSight?: boolean;
  readonly targetYRange?: Readonly<{
    minimum?: number;
    maximum?: number;
  }>;
}

export function collectBlocks(
  driver: BeatGameDriver,
  options: CollectBlocksOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "collect-blocks",
      blockIds: options.blockIds,
      tags: options.tags ?? [],
      count: positiveInteger(options.count, "count"),
      searchRadius: options.searchRadius ?? defaultBeatGameStrategy.blockSearchRadius,
      avoidSubmergedTargets: options.avoidSubmergedTargets ?? false,
      requireLineOfSight: options.requireLineOfSight ?? false,
      ...(options.targetYRange === undefined ? {} : { targetYRange: options.targetYRange }),
    },
    options.path,
  );
}

export interface CollectNearbyDropsOptions extends BeatGameBehaviorOptions {
  readonly itemIds?: readonly string[];
  readonly radius?: number;
  readonly maximumDrops?: number;
  readonly settleDelayMs?: number;
  readonly maximumVerticalDistance?: number;
}

export function collectNearbyDrops(
  driver: BeatGameDriver,
  options: CollectNearbyDropsOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const settleDelayMs = nonNegativeInteger(options.settleDelayMs ?? 250, "settleDelayMs");
      if (settleDelayMs > 0) {
        yield* Effect.sleep(settleDelayMs);
      }
      const radius = positiveFiniteNumber(options.radius ?? 8, "radius");
      const maximumDrops = positiveInteger(options.maximumDrops ?? 16, "maximumDrops");
      const requestedItemIds = options.itemIds === undefined ? undefined : new Set(options.itemIds);
      const maximumVerticalDistance =
        options.maximumVerticalDistance === undefined
          ? Number.POSITIVE_INFINITY
          : nonNegativeFiniteNumber(options.maximumVerticalDistance, "maximumVerticalDistance");
      const requestedPath = mergePathPolicy(options.path);
      const pickupPath = {
        ...requestedPath,
        // Drop collection is cleanup, so it must not consume the resources it
        // is trying to collect just to stand closer to an item entity.
        allowPlacing: false,
        maxSearchTimeMs: Math.min(requestedPath.maxSearchTimeMs, 5_000),
      };
      const attemptedDrops = new Set<string>();
      const pickupAttempts = new Map<string, number>();
      for (let pass = 0; pass < 2; pass += 1) {
        if (pass > 0) {
          yield* Effect.sleep(100);
        }
        for (let attempt = 0; attempt < maximumDrops; attempt += 1) {
          const observation = yield* driver.observe;
          const drops = yield* driver.queryEntities({
            origin: observation.player.position,
            radius,
            selector: {
              entityTypes: ["minecraft:item"],
              alive: true,
            },
            maximumResults: maximumDrops,
          });
          const candidates = drops
            .filter(
              (candidate) =>
                candidate.entityType === "minecraft:item" &&
                candidate.alive &&
                candidate.itemId !== undefined &&
                !attemptedDrops.has(entityObservationKey(candidate)) &&
                Math.abs(candidate.position.y - observation.player.position.y) <=
                  maximumVerticalDistance &&
                (requestedItemIds === undefined || requestedItemIds.has(candidate.itemId)),
            )
            .sort(
              (left, right) =>
                distanceSquared(observation.player.position, left.position) -
                distanceSquared(observation.player.position, right.position),
            );
          const drop = yield* firstSafePickupDrop(
            driver,
            candidates,
            attemptedDrops,
            requestedPath.avoidFluids === true,
          );
          if (drop === undefined) {
            break;
          }
          const dropKey = entityObservationKey(drop);
          const pickedUpDirectly = yield* tryDirectDropPickup(driver, observation, drop);
          if (pickedUpDirectly) {
            continue;
          }
          const dropInFluid =
            requestedPath.avoidFluids !== true && (yield* isDropInFluid(driver, drop));
          const verticalDistance = Math.abs(drop.position.y - observation.player.position.y);
          const submergedPickupPosition = {
            x: Math.floor(drop.position.x) + 0.5,
            y: drop.position.y,
            z: Math.floor(drop.position.z) + 0.5,
            dimension: drop.position.dimension,
          };
          const pickupRoute = dropInFluid
            ? driver.pathfindXZ(
                submergedPickupPosition.x,
                submergedPickupPosition.z,
                submergedPickupPosition.dimension,
                SUBMERGED_DROP_PICKUP_PATH_RADIUS,
                {
                  ...pickupPath,
                  avoidFluids: false,
                },
              )
            : requestedPath.avoidFluids === false && verticalDistance <= 1.75
              ? driver.pathfind(drop.position, DROP_PICKUP_PATH_RADIUS, pickupPath)
              : requestedPath.avoidFluids === false
                ? driver.pathfindXZ(
                    drop.position.x,
                    drop.position.z,
                    drop.position.dimension,
                    DROP_PICKUP_PATH_RADIUS,
                    pickupPath,
                  )
                : verticalDistance <= 1.75
                  ? driver.pathfind(
                      {
                        ...drop.position,
                        y: observation.player.position.y,
                      },
                      DROP_PICKUP_PATH_RADIUS,
                      pickupPath,
                    )
                  : driver.pathfindXZ(
                      drop.position.x,
                      drop.position.z,
                      drop.position.dimension,
                      DROP_PICKUP_PATH_RADIUS,
                      pickupPath,
                    );
          const reached = yield* pickupRoute.pipe(
            Effect.as(true),
            Effect.catch(() => Effect.succeed(false)),
          );
          if (!reached) {
            attemptedDrops.add(dropKey);
            continue;
          }
          const latestObservation = yield* driver.observe;
          const refreshedDrops = yield* driver.queryEntities({
            origin: latestObservation.player.position,
            radius,
            selector: {
              networkId: drop.networkId,
              alive: true,
            },
            maximumResults: 1,
          });
          const refreshedDrop = refreshedDrops.find(
            (candidate) => entityObservationKey(candidate) === dropKey,
          );
          if (refreshedDrop === undefined) {
            continue;
          }
          if (dropInFluid && pickupPath.allowMining) {
            yield* clearSubmergedDropCover(driver, latestObservation, refreshedDrop);
          }
          const pickupObservation = yield* driver.observe;
          if (yield* tryDirectDropPickup(driver, pickupObservation, refreshedDrop, dropInFluid)) {
            continue;
          }
          const pickupAttempt = (pickupAttempts.get(dropKey) ?? 0) + 1;
          pickupAttempts.set(dropKey, pickupAttempt);
          if (pickupAttempt >= DROP_PICKUP_MAXIMUM_ATTEMPTS) {
            attemptedDrops.add(dropKey);
          }
          yield* Effect.sleep(150);
        }
      }
    }),
  );
}

function clearSubmergedDropCover(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  drop: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const playerPosition = observation.player.position;
  if (
    playerPosition.dimension !== drop.position.dimension ||
    drop.position.y >= playerPosition.y - 0.25 ||
    Math.hypot(drop.position.x - playerPosition.x, drop.position.z - playerPosition.z) >
      SUBMERGED_DROP_PICKUP_MAXIMUM_HORIZONTAL_DISTANCE
  ) {
    return Effect.succeed(false);
  }

  const maximumY = Math.floor(playerPosition.y) - 1;
  const minimumY = Math.floor(drop.position.y) + 1;
  if (minimumY > maximumY) {
    return Effect.succeed(false);
  }

  return Effect.gen(function* () {
    for (let y = maximumY; y >= minimumY; y -= 1) {
      const position = {
        x: Math.floor(drop.position.x),
        y,
        z: Math.floor(drop.position.z),
        dimension: drop.position.dimension,
      };
      const block = yield* observeExactBlock(driver, position);
      if (block === undefined) {
        return false;
      }
      if (block.replaceable) {
        continue;
      }
      if (!block.diggable) {
        return false;
      }
      yield* driver.act({ type: "dig-block", position });
      const cleared = yield* waitForExactBlockState(
        driver,
        position,
        (current) => current === undefined || current.replaceable,
        10,
        50,
      );
      return cleared === undefined || cleared.replaceable;
    }
    return false;
  });
}

function tryDirectDropPickup(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  drop: BeatGameEntityObservation,
  allowVerticalMovement = false,
): Effect.Effect<boolean, BeatGameDriverError> {
  const playerPosition = observation.player.position;
  const horizontalDistance = Math.hypot(
    drop.position.x - playerPosition.x,
    drop.position.z - playerPosition.z,
  );
  const maximumHorizontalDistance = allowVerticalMovement
    ? SUBMERGED_DROP_PICKUP_MAXIMUM_HORIZONTAL_DISTANCE
    : DIRECT_DROP_PICKUP_MAXIMUM_HORIZONTAL_DISTANCE;
  const maximumVerticalDistance = allowVerticalMovement
    ? SUBMERGED_DROP_PICKUP_MAXIMUM_VERTICAL_DISTANCE
    : DIRECT_DROP_PICKUP_MAXIMUM_VERTICAL_DISTANCE;
  if (
    drop.position.dimension !== playerPosition.dimension ||
    horizontalDistance > maximumHorizontalDistance ||
    Math.abs(drop.position.y - playerPosition.y) > maximumVerticalDistance
  ) {
    return Effect.succeed(false);
  }

  return Effect.gen(function* () {
    yield* steerTowardDrop(driver, observation, drop, allowVerticalMovement);

    const maximumPolls = allowVerticalMovement
      ? SUBMERGED_DROP_PICKUP_MAXIMUM_POLLS
      : DIRECT_DROP_PICKUP_MAXIMUM_POLLS;
    for (let poll = 0; poll < maximumPolls; poll += 1) {
      yield* Effect.sleep(DIRECT_DROP_PICKUP_POLL_INTERVAL_MS);
      const current = yield* driver.observe;
      const nearbyItems = yield* driver.queryEntities({
        origin: current.player.position,
        radius: Math.max(maximumHorizontalDistance, maximumVerticalDistance) + 1,
        selector: {
          networkId: drop.networkId,
          alive: true,
        },
        maximumResults: 1,
      });
      const refreshedDrop = nearbyItems.find(
        (candidate) => entityObservationKey(candidate) === entityObservationKey(drop),
      );
      if (refreshedDrop === undefined) {
        return true;
      }
      if (!allowVerticalMovement) {
        continue;
      }
      const minimumSafeAir = Math.floor(
        current.player.maxAir * SUBMERGED_DROP_PICKUP_MINIMUM_AIR_RATIO,
      );
      if (current.player.air <= minimumSafeAir) {
        return false;
      }
      if (poll % SUBMERGED_DROP_PICKUP_STEERING_INTERVAL_POLLS === 0) {
        yield* steerTowardDrop(driver, current, refreshedDrop, true);
      }
    }
    return false;
  }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
}

function steerTowardDrop(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  drop: BeatGameEntityObservation,
  allowVerticalMovement: boolean,
): Effect.Effect<void, BeatGameDriverError> {
  const playerPosition = observation.player.position;
  const rotation = rotationToward(
    playerPosition,
    allowVerticalMovement ? drop.position : { ...drop.position, y: playerPosition.y },
  );
  return Effect.gen(function* () {
    yield* driver.act({
      type: "look",
      yaw: rotation.yaw,
      pitch: allowVerticalMovement ? rotation.pitch : 0,
    });
    yield* driver.act({
      type: "set-movement",
      forward: true,
      sprint: false,
      ...(allowVerticalMovement
        ? {
            jump: drop.position.y > playerPosition.y + 0.25,
            sneak: false,
          }
        : {}),
    });
  });
}

function firstSafePickupDrop(
  driver: BeatGameDriver,
  candidates: readonly BeatGameEntityObservation[],
  attemptedDrops: Set<string>,
  avoidFluids: boolean,
): Effect.Effect<BeatGameEntityObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (const candidate of candidates) {
      if (!avoidFluids || !(yield* isDropInFluid(driver, candidate))) {
        return candidate;
      }
      attemptedDrops.add(entityObservationKey(candidate));
    }
    return undefined;
  });
}

function isDropInFluid(
  driver: BeatGameDriver,
  drop: BeatGameEntityObservation,
): Effect.Effect<boolean, BeatGameDriverError> {
  const position = {
    x: Math.floor(drop.position.x),
    y: Math.floor(drop.position.y),
    z: Math.floor(drop.position.z),
    dimension: drop.position.dimension,
  };
  return driver
    .queryBlocks({
      center: blockCenter(position),
      radius: 0.25,
      selector: { blockIds: AVOIDED_FLUID_BLOCK_IDS },
      maximumResults: 1,
    })
    .pipe(
      Effect.map((blocks) =>
        blocks.some(
          (block) =>
            block.position.x === position.x &&
            block.position.y === position.y &&
            block.position.z === position.z &&
            block.position.dimension === position.dimension,
        ),
      ),
    );
}

function entityObservationKey(entity: BeatGameEntityObservation): string {
  return `${entity.connectionEpoch}:${entity.networkId}`;
}

export interface ExcavateOptions extends BeatGameBehaviorOptions {
  readonly from: BeatGameBlockPosition;
  readonly to: BeatGameBlockPosition;
  readonly maximumBlocks?: number;
}

export function excavate(
  driver: BeatGameDriver,
  options: ExcavateOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "excavate",
      from: options.from,
      to: options.to,
      ...(options.maximumBlocks === undefined ? {} : { maximumBlocks: options.maximumBlocks }),
    },
    options.path,
  );
}

export interface ExcavateStaircaseOptions extends BeatGameBehaviorOptions {
  readonly from: BeatGameBlockPosition;
  readonly to: BeatGameBlockPosition;
  readonly tool?: BeatGameItemSelector;
  /**
   * Hands control back to ordinary pathfinding when the staircase opens into
   * traversable space near the destination depth. If pathfinding cannot reach
   * the destination, excavation continues and bridges the opening instead.
   */
  readonly openSpaceHandoffRadius?: number;
}

/**
 * Carves and follows a two-block-high descending staircase.
 *
 * Each horizontal block advances exactly one block downward. Surplus depth is
 * routed through a rectangular detour so the staircase never becomes a
 * two-column shaft. Missing floor blocks are bridged before the bot opens the
 * next step when a reachable lower anchor exists. Unsupported cave openings
 * are reported to the caller so it can hand the opening to ordinary
 * pathfinding or select another staircase instead of attempting an invisible
 * underside placement.
 */
export function excavateStaircase(
  driver: BeatGameDriver,
  options: ExcavateStaircaseOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const approachObservation = yield* waitForVerticalSettlement(driver);
      const approachPosition = approachObservation.player.position;
      const stagingObservation =
        approachPosition.dimension === options.from.dimension &&
        approachPosition.y < options.from.y - 1
          ? approachObservation
          : yield* driver
              .pathfind(staircaseFeetCenter(options.from), 0.5, mergePathPolicy(options.path))
              .pipe(Effect.andThen(waitForVerticalSettlement(driver)));
      const stagingPosition = stagingObservation.player.position;
      if (stagingPosition.dimension !== options.from.dimension) {
        return yield* Effect.fail(
          behaviorError(driver, "Changed dimensions while approaching the staircase"),
        );
      }
      const actualFrom: BeatGameBlockPosition = {
        x: Math.floor(stagingPosition.x),
        y: Math.floor(stagingPosition.y + 0.01),
        z: Math.floor(stagingPosition.z),
        dimension: stagingPosition.dimension,
      };
      const staircaseTo = adjustedStaircaseDestination(actualFrom, options.to);
      const steps = staircaseSteps(actualFrom, staircaseTo);
      const tool = options.tool ?? {
        itemIds: [
          "minecraft:netherite_pickaxe",
          "minecraft:diamond_pickaxe",
          "minecraft:iron_pickaxe",
          "minecraft:stone_pickaxe",
          "minecraft:wooden_pickaxe",
          "minecraft:golden_pickaxe",
        ],
      };
      for (const step of steps) {
        if (
          options.openSpaceHandoffRadius !== undefined &&
          step.y - options.to.y <= MAXIMUM_OPEN_SPACE_HANDOFF_DEPTH &&
          (yield* isOpenStaircaseStep(driver, step))
        ) {
          const handoff = yield* driver
            .pathfind(
              options.to,
              positiveFiniteNumber(options.openSpaceHandoffRadius, "openSpaceHandoffRadius"),
              mergePathPolicy(options.path),
            )
            .pipe(Effect.result);
          if (handoff._tag === "Success") {
            return;
          }
        }
        yield* ensureStaircaseSupport(driver, step);
        yield* selectStaircaseTool(driver, tool);
        yield* digStaircaseBlockIfNeeded(driver, {
          ...step,
          y: step.y + 2,
        });
        yield* digStaircaseBlockIfNeeded(driver, {
          ...step,
          y: step.y + 1,
        });
        yield* digStaircaseBlockIfNeeded(driver, step);
        yield* refuseFloodedStaircaseStep(driver, step);
        const traversal = yield* walkStaircaseStep(driver, step, options.path).pipe(Effect.result);
        if (traversal._tag === "Failure") {
          const displaced = yield* driver.observe;
          if (
            displaced.player.position.dimension === step.dimension &&
            displaced.player.position.y < step.y - 1
          ) {
            yield* driver.pathfind(options.to, 4, mergePathPolicy(options.path));
            return;
          }
          return yield* Effect.fail(traversal.failure);
        }
      }
      if (!samePosition(staircaseTo, options.to)) {
        yield* driver.pathfind(options.to, 2, mergePathPolicy(options.path));
      }
    }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

/**
 * Selects a surface start whose horizontal distance and parity permit a
 * one-block-down-per-step staircase to end at {@link destination}.
 */
export function staircaseStartPosition(
  destination: BeatGameBlockPosition,
  current: BeatGameBlockPosition,
): BeatGameBlockPosition {
  if (destination.dimension !== current.dimension) {
    throw new RangeError("Staircase endpoints must be in the same dimension");
  }
  const depth = current.y - destination.y;
  if (depth < 1) {
    throw new RangeError("A staircase must descend at least one block");
  }
  let x = current.x;
  let z = current.z;
  let xDistance = Math.abs(destination.x - x);
  let zDistance = Math.abs(destination.z - z);
  let excessDistance = xDistance + zDistance - depth;
  if (excessDistance > 0) {
    const xReduction = Math.min(xDistance, excessDistance);
    x += Math.sign(destination.x - x) * xReduction;
    xDistance -= xReduction;
    excessDistance -= xReduction;
    const zReduction = Math.min(zDistance, excessDistance);
    z += Math.sign(destination.z - z) * zReduction;
    zDistance -= zReduction;
  }
  if ((depth - xDistance - zDistance) % 2 !== 0) {
    if (xDistance > 0) {
      x += Math.sign(destination.x - x);
    } else if (zDistance > 0) {
      z += Math.sign(destination.z - z);
    } else {
      x += 1;
    }
  }
  return {
    x,
    y: current.y,
    z,
    dimension: destination.dimension,
  };
}

function selectStaircaseTool(
  driver: BeatGameDriver,
  tool: BeatGameItemSelector,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.act({ type: "select-item", selector: tool }).pipe(
    Effect.mapError((cause) =>
      cause.code === "not_found"
        ? new BeatGameDriverError({
            operation: "select-staircase-tool",
            code: cause.code,
            retryable: true,
            message: "No usable staircase tool remains",
            cause,
          })
        : cause,
    ),
    Effect.asVoid,
  );
}

export interface AttackEntityOptions extends BeatGameBehaviorOptions {
  readonly target: Pick<BeatGameEntityObservation, "connectionEpoch" | "networkId">;
  readonly attackRange?: number;
  readonly sprinting?: boolean;
  readonly maximumAttacks?: number;
  readonly targetUnavailableTimeoutSeconds?: number;
  readonly selectBestWeapon?: boolean;
  readonly weapon?: BeatGameItemSelector;
  readonly restoreSelectedSlot?: boolean;
  readonly useOffhandShield?: boolean;
}

export function attackEntity(
  driver: BeatGameDriver,
  options: AttackEntityOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "attack-entity",
      target: options.target,
      ...(options.attackRange === undefined ? {} : { attackRange: options.attackRange }),
      ...(options.sprinting === undefined ? {} : { sprinting: options.sprinting }),
      ...(options.maximumAttacks === undefined ? {} : { maximumAttacks: options.maximumAttacks }),
      ...(options.targetUnavailableTimeoutSeconds === undefined
        ? {}
        : {
            targetUnavailableTimeoutSeconds: options.targetUnavailableTimeoutSeconds,
          }),
      ...(options.selectBestWeapon === undefined
        ? {}
        : { selectBestWeapon: options.selectBestWeapon }),
      ...(options.weapon === undefined ? {} : { weapon: options.weapon }),
      ...(options.restoreSelectedSlot === undefined
        ? {}
        : { restoreSelectedSlot: options.restoreSelectedSlot }),
      ...(options.useOffhandShield === undefined
        ? {}
        : { useOffhandShield: options.useOffhandShield }),
    },
    options.path,
  );
}

export interface AttackNearestOptions extends BeatGameBehaviorOptions {
  readonly selector: BeatGameEntitySelector;
  readonly radius?: number;
  readonly attackRange?: number;
  readonly sprinting?: boolean;
  readonly maximumAttacks?: number;
  readonly maximumTargets?: number;
  readonly noTargetTimeoutSeconds?: number;
  readonly completeWhenNoTarget?: boolean;
  readonly selectBestWeapon?: boolean;
  readonly weapon?: BeatGameItemSelector;
  readonly restoreSelectedSlot?: boolean;
}

export function attackNearest(
  driver: BeatGameDriver,
  options: AttackNearestOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "attack-nearest",
      selector: options.selector,
      radius: Math.min(
        positiveFiniteNumber(
          options.radius ?? defaultBeatGameStrategy.entitySearchRadius,
          "radius",
        ),
        MAXIMUM_ATTACK_NEAREST_RADIUS,
      ),
      ...(options.attackRange === undefined ? {} : { attackRange: options.attackRange }),
      ...(options.sprinting === undefined ? {} : { sprinting: options.sprinting }),
      ...(options.maximumAttacks === undefined ? {} : { maximumAttacks: options.maximumAttacks }),
      ...(options.maximumTargets === undefined ? {} : { maximumTargets: options.maximumTargets }),
      ...(options.noTargetTimeoutSeconds === undefined
        ? {}
        : { noTargetTimeoutSeconds: options.noTargetTimeoutSeconds }),
      ...(options.completeWhenNoTarget === undefined
        ? {}
        : { completeWhenNoTarget: options.completeWhenNoTarget }),
      ...(options.selectBestWeapon === undefined
        ? {}
        : { selectBestWeapon: options.selectBestWeapon }),
      ...(options.weapon === undefined ? {} : { weapon: options.weapon }),
      ...(options.restoreSelectedSlot === undefined
        ? {}
        : { restoreSelectedSlot: options.restoreSelectedSlot }),
    },
    options.path,
  );
}

export interface RangedAttackOptions extends BeatGameBehaviorOptions {
  readonly target: Pick<BeatGameEntityObservation, "connectionEpoch" | "networkId">;
  readonly minimumRange?: number;
  readonly maximumRange?: number;
  readonly maximumShots?: number;
  readonly targetUnavailableTimeoutSeconds?: number;
  readonly weapon?: BeatGameItemSelector;
  readonly bowDrawTicks?: number;
  readonly leadTarget?: boolean;
  readonly compensateGravity?: boolean;
  readonly strafe?: boolean;
  readonly restoreSelectedSlot?: boolean;
}

export function rangedAttack(
  driver: BeatGameDriver,
  options: RangedAttackOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "ranged-attack",
      target: options.target,
      ...(options.minimumRange === undefined ? {} : { minimumRange: options.minimumRange }),
      ...(options.maximumRange === undefined ? {} : { maximumRange: options.maximumRange }),
      ...(options.maximumShots === undefined ? {} : { maximumShots: options.maximumShots }),
      ...(options.targetUnavailableTimeoutSeconds === undefined
        ? {}
        : {
            targetUnavailableTimeoutSeconds: options.targetUnavailableTimeoutSeconds,
          }),
      ...(options.weapon === undefined ? {} : { weapon: options.weapon }),
      ...(options.bowDrawTicks === undefined ? {} : { bowDrawTicks: options.bowDrawTicks }),
      ...(options.leadTarget === undefined ? {} : { leadTarget: options.leadTarget }),
      ...(options.compensateGravity === undefined
        ? {}
        : { compensateGravity: options.compensateGravity }),
      ...(options.strafe === undefined ? {} : { strafe: options.strafe }),
      ...(options.restoreSelectedSlot === undefined
        ? {}
        : { restoreSelectedSlot: options.restoreSelectedSlot }),
    },
    options.path,
  );
}

export interface FleeOptions extends BeatGameBehaviorOptions {
  readonly selector: BeatGameEntitySelector;
  readonly triggerRadius?: number;
  readonly safeDistance?: number;
  readonly safeSeconds?: number;
  readonly completeWhenSafe?: boolean;
  readonly maximumEscapes?: number;
}

export function flee(
  driver: BeatGameDriver,
  options: FleeOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "flee",
      selector: options.selector,
      ...(options.triggerRadius === undefined ? {} : { triggerRadius: options.triggerRadius }),
      ...(options.safeDistance === undefined ? {} : { safeDistance: options.safeDistance }),
      ...(options.safeSeconds === undefined ? {} : { safeSeconds: options.safeSeconds }),
      ...(options.completeWhenSafe === undefined
        ? {}
        : { completeWhenSafe: options.completeWhenSafe }),
      ...(options.maximumEscapes === undefined ? {} : { maximumEscapes: options.maximumEscapes }),
    },
    options.path,
  );
}

export interface GuardOptions extends BeatGameBehaviorOptions {
  readonly position: BeatGameBlockPosition;
  readonly selector: BeatGameEntitySelector;
  readonly guardRadius?: number;
  readonly maximumPursuitDistance?: number;
  readonly returnRadius?: number;
  readonly attackRange?: number;
  readonly sprinting?: boolean;
  readonly maximumAttacks?: number;
  readonly maximumTargets?: number;
  readonly completeWhenClear?: boolean;
  readonly clearSeconds?: number;
  readonly selectBestWeapon?: boolean;
  readonly weapon?: BeatGameItemSelector;
  readonly restoreSelectedSlot?: boolean;
}

export function guard(
  driver: BeatGameDriver,
  options: GuardOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "guard",
      position: options.position,
      selector: options.selector,
      ...(options.guardRadius === undefined ? {} : { guardRadius: options.guardRadius }),
      ...(options.maximumPursuitDistance === undefined
        ? {}
        : { maximumPursuitDistance: options.maximumPursuitDistance }),
      ...(options.returnRadius === undefined ? {} : { returnRadius: options.returnRadius }),
      ...(options.attackRange === undefined ? {} : { attackRange: options.attackRange }),
      ...(options.sprinting === undefined ? {} : { sprinting: options.sprinting }),
      ...(options.maximumAttacks === undefined ? {} : { maximumAttacks: options.maximumAttacks }),
      ...(options.maximumTargets === undefined ? {} : { maximumTargets: options.maximumTargets }),
      ...(options.completeWhenClear === undefined
        ? {}
        : { completeWhenClear: options.completeWhenClear }),
      ...(options.clearSeconds === undefined ? {} : { clearSeconds: options.clearSeconds }),
      ...(options.selectBestWeapon === undefined
        ? {}
        : { selectBestWeapon: options.selectBestWeapon }),
      ...(options.weapon === undefined ? {} : { weapon: options.weapon }),
      ...(options.restoreSelectedSlot === undefined
        ? {}
        : { restoreSelectedSlot: options.restoreSelectedSlot }),
    },
    options.path,
  );
}

export interface EatWhenNeededOptions extends BeatGameBehaviorOptions {
  readonly foodItemIds?: readonly string[];
  readonly foodLevel?: number;
  readonly maximumMeals?: number;
  readonly completeWhenNoFood?: boolean;
  readonly restoreSelectedSlot?: boolean;
}

export function eatWhenNeeded(
  driver: BeatGameDriver,
  options: EatWhenNeededOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "auto-eat",
      foodItemIds: options.foodItemIds ?? [],
      foodLevel: options.foodLevel ?? defaultBeatGameStrategy.eatBelowFood,
      ...(options.maximumMeals === undefined ? {} : { maximumMeals: options.maximumMeals }),
      ...(options.completeWhenNoFood === undefined
        ? {}
        : { completeWhenNoFood: options.completeWhenNoFood }),
      ...(options.restoreSelectedSlot === undefined
        ? {}
        : { restoreSelectedSlot: options.restoreSelectedSlot }),
    },
    options.path,
  );
}

export interface RespawnAndRecoverOptions extends BeatGameBehaviorOptions {
  readonly deathPosition?: BeatGamePosition;
  readonly searchRadius?: number;
  readonly retryThroughFluids?: boolean;
}

export function respawnAndRecover(
  driver: BeatGameDriver,
  options: RespawnAndRecoverOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const before = yield* driver.observe;
    if (before.player.dead) {
      yield* runControlled(
        driver,
        {
          type: "auto-respawn",
          maximumRespawns: 1,
        },
        options.path,
      );
      yield* waitForRespawnConfirmation(driver);
      yield* driver.waitForChunks(RESPAWN_CHUNK_RADIUS, RESPAWN_CHUNK_TIMEOUT_MS);
    }
    if (options.deathPosition === undefined) {
      return;
    }
    const requestedPath = mergePathPolicy(options.path);
    const travelPath = {
      ...requestedPath,
      allowMining: false,
      allowPlacing: false,
    };
    let current = (yield* driver.observe).player.position;
    for (let segment = 0; segment < MAXIMUM_CORPSE_TRAVEL_SEGMENTS; segment += 1) {
      if (current.dimension !== options.deathPosition.dimension) {
        return;
      }
      const deltaX = options.deathPosition.x - current.x;
      const deltaZ = options.deathPosition.z - current.z;
      const horizontalDistance = Math.hypot(deltaX, deltaZ);
      if (horizontalDistance <= CORPSE_FINAL_APPROACH_HORIZONTAL_DISTANCE) {
        break;
      }
      const segmentDistance = Math.min(
        CORPSE_TRAVEL_SEGMENT_DISTANCE,
        horizontalDistance - CORPSE_FINAL_APPROACH_HORIZONTAL_DISTANCE,
      );
      const targetX = current.x + (deltaX / horizontalDistance) * segmentDistance;
      const targetZ = current.z + (deltaZ / horizontalDistance) * segmentDistance;
      let reachedSegment = yield* driver
        .pathfindXZ(targetX, targetZ, current.dimension, CORPSE_TRAVEL_GOAL_RADIUS, travelPath)
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        );
      if (!reachedSegment) {
        reachedSegment = yield* driver
          .pathfindXZ(targetX, targetZ, current.dimension, CORPSE_TRAVEL_GOAL_RADIUS, requestedPath)
          .pipe(
            Effect.as(true),
            Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
          );
      }
      if (!reachedSegment) {
        return;
      }
      const previousDistance = horizontalDistance;
      current = (yield* driver.observe).player.position;
      const remainingDistance = Math.hypot(
        options.deathPosition.x - current.x,
        options.deathPosition.z - current.z,
      );
      if (remainingDistance >= previousDistance - 1) {
        break;
      }
    }
    let reachedDeathPosition = yield* driver.pathfind(options.deathPosition, 2, requestedPath).pipe(
      Effect.as(true),
      Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
    );
    if (
      !reachedDeathPosition &&
      options.retryThroughFluids === true &&
      options.path?.avoidFluids === true
    ) {
      reachedDeathPosition = yield* driver
        .pathfind(options.deathPosition, 2, {
          ...mergePathPolicy(options.path),
          avoidFluids: false,
        })
        .pipe(
          Effect.as(true),
          Effect.catchTag("BeatGameDriverError", () => Effect.succeed(false)),
        );
    }
    if (!reachedDeathPosition) {
      return;
    }
    yield* collectNearbyDrops(driver, {
      radius: options.searchRadius ?? 24,
      maximumDrops: 64,
      settleDelayMs: 100,
      ...(options.path === undefined
        ? {}
        : {
            path:
              options.retryThroughFluids === true
                ? { ...options.path, avoidFluids: false }
                : options.path,
          }),
    });
  });
}

function waitForRespawnConfirmation(
  driver: BeatGameDriver,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  const poll = (
    attemptsRemaining: number,
  ): Effect.Effect<BeatGameObservation, BeatGameDriverError> =>
    driver.observe.pipe(
      Effect.flatMap((observation) => {
        if (!observation.player.dead) {
          return Effect.succeed(observation);
        }
        if (attemptsRemaining <= 1) {
          return Effect.fail(
            behaviorError(
              driver,
              "The player remained dead after the respawn request",
              "deadline_exceeded",
            ),
          );
        }
        return Effect.sleep(RESPAWN_CONFIRMATION_POLL_INTERVAL_MS).pipe(
          Effect.andThen(poll(attemptsRemaining - 1)),
        );
      }),
    );
  return poll(RESPAWN_CONFIRMATION_MAXIMUM_POLLS);
}

export function equipBestArmor(
  driver: BeatGameDriver,
  options: BeatGameBehaviorOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "auto-armor",
      completeWhenNoUpgrade: true,
    },
    options.path,
  );
}

export function keepTotemEquipped(
  driver: BeatGameDriver,
  options: BeatGameBehaviorOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(driver, { type: "auto-totem" }, options.path);
}

export interface FishOptions extends BeatGameBehaviorOptions {
  readonly maximumCatches?: number;
  readonly maximumFailedCasts?: number;
}

export function fish(
  driver: BeatGameDriver,
  options: FishOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "fish",
      ...(options.maximumCatches === undefined ? {} : { maximumCatches: options.maximumCatches }),
      ...(options.maximumFailedCasts === undefined
        ? {}
        : { maximumFailedCasts: options.maximumFailedCasts }),
    },
    options.path,
  );
}

export interface FarmOptions extends BeatGameBehaviorOptions {
  readonly cropIds?: readonly string[];
  readonly center?: BeatGameBlockPosition;
  readonly radius?: number;
  readonly maximumHarvests?: number;
}

export function farm(
  driver: BeatGameDriver,
  options: FarmOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "farm",
      cropIds: options.cropIds ?? [],
      ...(options.center === undefined ? {} : { center: options.center }),
      ...(options.radius === undefined ? {} : { radius: options.radius }),
      ...(options.maximumHarvests === undefined
        ? {}
        : { maximumHarvests: options.maximumHarvests }),
    },
    options.path,
  );
}

export interface BreedOptions extends BeatGameBehaviorOptions {
  readonly selector?: BeatGameEntitySelector;
  readonly food?: BeatGameItemSelector;
  readonly maximumPairs?: number;
}

export function breed(
  driver: BeatGameDriver,
  options: BreedOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "breed",
      ...(options.selector === undefined ? {} : { selector: options.selector }),
      ...(options.food === undefined ? {} : { food: options.food }),
      ...(options.maximumPairs === undefined ? {} : { maximumPairs: options.maximumPairs }),
    },
    options.path,
  );
}

export interface ExploreOptions extends BeatGameBehaviorOptions {
  readonly origin?: BeatGameBlockPosition;
  readonly radius?: number;
  readonly maximumWaypoints?: number;
  readonly purpose?: string;
}

export function explore(
  driver: BeatGameDriver,
  options: ExploreOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "explore",
      ...(options.origin === undefined ? {} : { origin: options.origin }),
      radius: options.radius ?? defaultBeatGameStrategy.explorationRadius,
      ...(options.maximumWaypoints === undefined
        ? {}
        : { maximumWaypoints: options.maximumWaypoints }),
      ...(options.purpose === undefined ? {} : { purpose: options.purpose }),
    },
    options.path,
  );
}

export interface TransferContainerItemsOptions extends BeatGameBehaviorOptions {
  readonly direction: "deposit" | "withdraw";
  readonly container: BeatGameBlockPosition;
  readonly operations: readonly BeatGameContainerTransfer[];
}

export function transferContainerItems(
  driver: BeatGameDriver,
  options: TransferContainerItemsOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "transfer-container",
      direction: options.direction,
      container: options.container,
      operations: options.operations,
    },
    options.path,
  );
}

export interface MaintainLoadoutOptions extends BeatGameBehaviorOptions {
  readonly container: BeatGameBlockPosition;
  readonly requirements: readonly BeatGameLoadoutRequirement[];
}

export function maintainLoadout(
  driver: BeatGameDriver,
  options: MaintainLoadoutOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "maintain-loadout",
      container: options.container,
      requirements: options.requirements,
    },
    options.path,
  );
}

export interface CraftOptions extends BeatGameBehaviorOptions {
  readonly recipeId: string;
  readonly count?: number;
  readonly station?: BeatGameBlockPosition;
}

export function craft(
  driver: BeatGameDriver,
  options: CraftOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "craft",
      recipeId: options.recipeId,
      count: positiveInteger(options.count ?? 1, "count"),
      ...(options.station === undefined ? {} : { station: options.station }),
    },
    options.path,
  );
}

export interface CraftItemOptions extends BeatGameBehaviorOptions {
  readonly resultItemId: string;
  readonly count?: number;
  readonly station?: BeatGameBlockPosition;
  readonly maximumDependencyDepth?: number;
}

export function craftItem(
  driver: BeatGameDriver,
  options: CraftItemOptions,
): Effect.Effect<void, BeatGameDriverError> {
  const requestedCount = positiveInteger(options.count ?? 1, "count");
  const maximumDepth = positiveInteger(
    options.maximumDependencyDepth ?? 12,
    "maximumDependencyDepth",
  );
  return craftItemDependencies(
    driver,
    options.resultItemId,
    requestedCount,
    options,
    [],
    maximumDepth,
  );
}

export interface SmeltOptions extends BeatGameBehaviorOptions {
  readonly input: BeatGameItemSelector;
  readonly count?: number;
  readonly fuel?: BeatGameItemSelector;
  readonly station?: BeatGameBlockPosition;
}

export function smelt(
  driver: BeatGameDriver,
  options: SmeltOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "smelt",
      input: options.input,
      count: positiveInteger(options.count ?? 1, "count"),
      ...(options.fuel === undefined ? {} : { fuel: options.fuel }),
      ...(options.station === undefined ? {} : { station: options.station }),
    },
    options.path,
  );
}

export interface BrewOptions extends BeatGameBehaviorOptions {
  readonly input: BeatGameItemSelector;
  readonly ingredient: BeatGameItemSelector;
  readonly count?: number;
  readonly fuel?: BeatGameItemSelector;
  readonly station?: BeatGameBlockPosition;
  readonly expectedResult?: BeatGameItemSelector;
}

export function brew(
  driver: BeatGameDriver,
  options: BrewOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "brew",
      input: options.input,
      ingredient: options.ingredient,
      count: positiveInteger(options.count ?? 1, "count"),
      ...(options.fuel === undefined ? {} : { fuel: options.fuel }),
      ...(options.station === undefined ? {} : { station: options.station }),
      ...(options.expectedResult === undefined ? {} : { expectedResult: options.expectedResult }),
    },
    options.path,
  );
}

export interface TradeOptions extends BeatGameBehaviorOptions {
  readonly offerIndex: number;
  readonly count?: number;
  readonly expectedResult?: BeatGameItemSelector;
}

export function trade(
  driver: BeatGameDriver,
  options: TradeOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "trade",
      offerIndex: nonNegativeInteger(options.offerIndex, "offerIndex"),
      count: positiveInteger(options.count ?? 1, "count"),
      ...(options.expectedResult === undefined ? {} : { expectedResult: options.expectedResult }),
    },
    options.path,
  );
}

export interface BuildStructureOptions extends BeatGameBehaviorOptions {
  readonly origin: BeatGameBlockPosition;
  readonly blocks: readonly BeatGameBuildBlock[];
  readonly partitionIndex?: number;
  readonly partitionCount?: number;
}

export function buildStructure(
  driver: BeatGameDriver,
  options: BuildStructureOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlled(
    driver,
    {
      type: "build",
      origin: options.origin,
      blocks: options.blocks,
      ...(options.partitionIndex === undefined ? {} : { partitionIndex: options.partitionIndex }),
      ...(options.partitionCount === undefined ? {} : { partitionCount: options.partitionCount }),
    },
    options.path,
  );
}

export interface BuildNetherPortalOptions extends BeatGameBehaviorOptions {
  readonly origin: BeatGameBlockPosition;
  readonly axis?: "x" | "z";
  readonly ignite?: boolean;
}

export function buildNetherPortal(
  driver: BeatGameDriver,
  options: BuildNetherPortalOptions,
): Effect.Effect<PortalFrame, BeatGameDriverError> {
  return Effect.gen(function* () {
    const frame = createNetherPortalFrame(options.origin, options.axis);
    yield* placeNetherPortalFrame(driver, frame, options.path);
    const observed = yield* driver.queryBlocks({
      center: portalFrameCenter(frame),
      radius: 5,
      selector: { blockIds: ["minecraft:obsidian"] },
      maximumResults: 32,
    });
    const missing = missingPortalBlocks(frame, observed);
    if (missing.length > 0) {
      return yield* Effect.fail(
        behaviorError(driver, `Portal construction left ${missing.length} obsidian blocks missing`),
      );
    }
    if (options.ignite ?? true) {
      yield* ignitePortal(driver, frame, true, options.path);
    }
    return frame;
  });
}

interface PortalFramePlacement {
  readonly target: BeatGameBlockPosition;
  readonly against: BeatGameBlockPosition;
  readonly face: BeatGameBlockFace;
  readonly itemId: "minecraft:cobblestone" | "minecraft:obsidian";
  readonly acceptsExistingSolid?: boolean;
}

function placeNetherPortalFrame(
  driver: BeatGameDriver,
  frame: PortalFrame,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  const program = Effect.gen(function* () {
    yield* driver.act({ type: "reset-movement" });
    yield* clearPortalInterior(driver, frame, path);
    const placements = portalFramePlacements(frame);
    const observation = yield* driver.observe;
    if (
      placements.some(
        (placement) =>
          !portalBlockWithinReach(observation, placement.target) ||
          !portalBlockWithinReach(observation, placement.against),
      )
    ) {
      yield* driver.pathfind(portalBuildRetreat(frame), 1, portalConstructionPathPolicy(path));
    }
    for (const placement of placements) {
      yield* ensurePortalFrameBlock(driver, frame, placement, path);
    }
  }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
  return driver.withControl(program);
}

function portalFramePlacements(frame: PortalFrame): readonly PortalFramePlacement[] {
  const at = (horizontal: number, vertical: number): BeatGameBlockPosition => ({
    x: frame.origin.x + (frame.axis === "x" ? horizontal : 0),
    y: frame.origin.y + vertical,
    z: frame.origin.z + (frame.axis === "z" ? horizontal : 0),
    dimension: frame.origin.dimension,
  });
  const alongFrame: BeatGameBlockFace = frame.axis === "x" ? "east" : "south";
  const support = (
    horizontal: number,
    vertical: number,
    against: BeatGameBlockPosition,
    face: BeatGameBlockFace,
  ): PortalFramePlacement => ({
    target: at(horizontal, vertical),
    against,
    face,
    itemId: "minecraft:cobblestone",
    acceptsExistingSolid: true,
  });
  const obsidian = (
    horizontal: number,
    vertical: number,
    against: BeatGameBlockPosition,
    face: BeatGameBlockFace,
  ): PortalFramePlacement => ({
    target: at(horizontal, vertical),
    against,
    face,
    itemId: "minecraft:obsidian",
  });

  return [
    support(0, 0, below(at(0, 0)), "up"),
    support(3, 0, below(at(3, 0)), "up"),
    obsidian(1, 0, below(at(1, 0)), "up"),
    obsidian(2, 0, below(at(2, 0)), "up"),
    obsidian(0, 1, at(0, 0), "up"),
    obsidian(3, 1, at(3, 0), "up"),
    obsidian(0, 2, at(0, 1), "up"),
    obsidian(3, 2, at(3, 1), "up"),
    obsidian(0, 3, at(0, 2), "up"),
    obsidian(3, 3, at(3, 2), "up"),
    support(0, 4, at(0, 3), "up"),
    obsidian(1, 4, at(0, 4), alongFrame),
    obsidian(2, 4, at(1, 4), alongFrame),
  ];
}

function ensurePortalFrameBlock(
  driver: BeatGameDriver,
  frame: PortalFrame,
  placement: PortalFramePlacement,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let observed = yield* observeExactBlock(driver, placement.target);
    if (
      observed?.blockId === placement.itemId ||
      (placement.acceptsExistingSolid === true && observed !== undefined && !observed.replaceable)
    ) {
      return;
    }
    const observation = yield* driver.observe;
    if (
      !portalBlockWithinReach(observation, placement.target) ||
      !portalBlockWithinReach(observation, placement.against)
    ) {
      yield* driver.pathfind(
        portalPlacementWorkPosition(frame, placement.target),
        1,
        portalConstructionPathPolicy(path),
      );
    }
    if (observed !== undefined && !observed.replaceable) {
      const tool = preferredPortalDigTool(observation);
      if (tool !== undefined) {
        yield* driver.act({
          type: "select-item",
          selector: { itemIds: [tool] },
        });
      }
      yield* driver.act({
        type: "dig-block",
        position: placement.target,
      });
      observed = yield* waitForExactBlockState(
        driver,
        placement.target,
        (block) => block === undefined || block.replaceable,
        10,
        50,
      );
      if (observed !== undefined && !observed.replaceable) {
        return yield* Effect.fail(
          behaviorError(
            driver,
            `Could not clear portal frame position ${positionKey(placement.target)}`,
          ),
        );
      }
    }

    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (attempt > 0) {
        const retryObservation = yield* driver.observe;
        if (!portalBlockWithinReach(retryObservation, placement.against)) {
          yield* driver.pathfind(portalBuildRetreat(frame), 1, portalConstructionPathPolicy(path));
        }
      }
      yield* driver.act({
        type: "select-item",
        selector: { itemIds: [placement.itemId] },
      });
      const placementResult = yield* driver
        .act({
          type: "place-block",
          against: placement.against,
          face: placement.face,
          hand: "main",
        })
        .pipe(Effect.result);
      if (placementResult._tag === "Failure") {
        yield* Effect.sleep(100);
        continue;
      }
      const placed = yield* waitForExactBlockState(
        driver,
        placement.target,
        (block) =>
          block?.blockId === placement.itemId ||
          (placement.acceptsExistingSolid === true && block !== undefined && !block.replaceable),
        10,
        50,
      );
      if (placed !== undefined && !placed.replaceable) {
        return;
      }
    }
    return yield* Effect.fail(
      behaviorError(
        driver,
        `Could not place ${placement.itemId} at ${positionKey(placement.target)}`,
      ),
    );
  });
}

function preferredPortalDigTool(observation: BeatGameObservation): string | undefined {
  return [
    "minecraft:netherite_pickaxe",
    "minecraft:diamond_pickaxe",
    "minecraft:iron_pickaxe",
    "minecraft:stone_pickaxe",
    "minecraft:golden_pickaxe",
    "minecraft:wooden_pickaxe",
  ].find((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
}

function portalPlacementWorkPosition(
  frame: PortalFrame,
  target: BeatGameBlockPosition,
): BeatGamePosition {
  return {
    x: frame.axis === "x" ? target.x : frame.origin.x - 1,
    y: frame.origin.y + 1,
    z: frame.axis === "z" ? target.z : frame.origin.z - 1,
    dimension: frame.origin.dimension,
  };
}

function portalBlockWithinReach(
  observation: BeatGameObservation,
  block: BeatGameBlockPosition,
): boolean {
  const player = observation.player.position;
  if (player.dimension !== block.dimension) {
    return false;
  }
  const x = player.x - (block.x + 0.5);
  const y = player.y + 1.62 - (block.y + 0.5);
  const z = player.z - (block.z + 0.5);
  return x * x + y * y + z * z <= 30.25;
}

function portalConstructionPathPolicy(
  override: Partial<BeatGamePathPolicy> | undefined,
): BeatGamePathPolicy {
  return {
    ...mergePathPolicy(override),
    allowMining: false,
    allowPlacing: false,
    searchMode: "PRECISION",
    maximumQualityBound: 1,
    maxParkourGap: 0,
    smoothCamera: false,
  };
}

function waitForExactBlockState(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
  predicate: (block: BeatGameBlockObservation | undefined) => boolean,
  attemptsRemaining: number,
  delayMs: number,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return observeExactBlock(driver, position).pipe(
    Effect.flatMap((block) => {
      if (predicate(block) || attemptsRemaining <= 1) {
        return Effect.succeed(block);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(
          waitForExactBlockState(driver, position, predicate, attemptsRemaining - 1, delayMs),
        ),
      );
    }),
  );
}

function observeExactBlock(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: {
        ...position,
        x: position.x + 0.5,
        y: position.y + 0.5,
        z: position.z + 0.5,
      },
      radius: 0.25,
      selector: {},
      maximumResults: 1,
    })
    .pipe(Effect.map((blocks) => blocks.find((block) => samePosition(block.position, position))));
}

function portalFrameCenter(frame: PortalFrame): BeatGamePosition {
  return {
    x: frame.origin.x + (frame.axis === "x" ? 1.5 : 0),
    y: frame.origin.y + 2,
    z: frame.origin.z + (frame.axis === "z" ? 1.5 : 0),
    dimension: frame.origin.dimension,
  };
}

function missingPortalBlocks(
  frame: PortalFrame,
  observed: readonly BeatGameBlockObservation[],
): readonly BeatGameBlockPosition[] {
  const occupied = new Set(observed.map(({ position }) => positionKey(position)));
  return frame.blocks.filter((position) => !occupied.has(positionKey(position)));
}

function portalBuildRetreat(frame: PortalFrame): BeatGamePosition {
  return {
    x: frame.origin.x + (frame.axis === "x" ? 1 : -1),
    y: frame.origin.y + 1,
    z: frame.origin.z + (frame.axis === "z" ? 1 : -1),
    dimension: frame.origin.dimension,
  };
}

export interface CastPortalStep {
  readonly itemIds: readonly string[];
  readonly action: BeatGamePrimitiveAction;
  readonly expectedBlock?: {
    readonly position: BeatGameBlockPosition;
    readonly blockIds: readonly string[];
  };
  readonly observationDelayMs?: number;
}

export interface CastNetherPortalOptions extends BeatGameBehaviorOptions {
  readonly origin: BeatGameBlockPosition;
  readonly axis?: "x" | "z";
  readonly steps?: readonly CastPortalStep[];
  readonly ignite?: boolean;
}

export function castNetherPortal(
  driver: BeatGameDriver,
  options: CastNetherPortalOptions,
): Effect.Effect<PortalFrame, BeatGameDriverError> {
  const frame = createNetherPortalFrame(options.origin, options.axis);
  if (options.steps === undefined) {
    return castNetherPortalFromLavaPool(driver, frame, options);
  }
  return driver.withControl(
    Effect.gen(function* () {
      for (const step of options.steps ?? []) {
        const target = primitiveActionPosition(step.action);
        if (target !== undefined) {
          yield* driver.pathfind(target, 3, mergePathPolicy(options.path));
        }
        yield* driver.act({
          type: "select-item",
          selector: { itemIds: step.itemIds },
        });
        yield* driver.act(step.action);
        if (step.expectedBlock !== undefined) {
          yield* Effect.sleep(step.observationDelayMs ?? 250);
          yield* requireObservedBlock(
            driver,
            step.expectedBlock.position,
            step.expectedBlock.blockIds,
            "cast portal step",
          );
        }
      }
      if (options.ignite ?? true) {
        yield* ignitePortal(driver, frame, false, options.path);
      }
      return frame;
    }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

export interface EnterPortalOptions extends BeatGameBehaviorOptions {
  readonly portal?: BeatGameBlockPosition;
  readonly searchOrigin?: BeatGamePosition;
  readonly searchRadius?: number;
}

export function enterPortal(
  driver: BeatGameDriver,
  options: EnterPortalOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* driver.waitForChunks(0, 60_000);
    const observation = yield* driver.observe;
    const origin = options.searchOrigin ?? observation.player.position;
    const target =
      options.portal ??
      (yield* driver.queryBlocks({
        center: origin,
        radius: options.searchRadius ?? 48,
        selector: { blockIds: ["minecraft:nether_portal"] },
        maximumResults: 1,
      }))[0]?.position;
    if (target === undefined) {
      return yield* Effect.fail(behaviorError(driver, "No Nether portal is observable"));
    }
    const observedPortals = yield* driver.queryBlocks({
      center: target,
      radius: 2,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 8,
    });
    const observedPortal = observedPortals[0];
    const axis = resolvePortalAxis(observedPortal?.properties.axis, observedPortals);
    const passage = portalPassage(target, axis, observedPortals);
    let entryOrigin = observation.player.position;
    if (hasPortalContact(observation.player.position, passage)) {
      entryOrigin = yield* leavePortalForReentry(
        driver,
        passage,
        observation.player.position,
        observation.player.rotation,
      );
      if (entryOrigin.dimension !== observation.player.position.dimension) {
        yield* completePortalTransition(driver, observation.player.position.dimension);
        return;
      }
      yield* Effect.sleep(NETHER_PORTAL_REENTRY_COOLDOWN_MS);
    }
    const approachTarget = portalApproachTarget(entryOrigin, target, axis, passage);
    const approachObservation = yield* driver.observe;
    if (canReachPortalApproachSupports(approachObservation.player.position, approachTarget)) {
      yield* ensurePortalApproachSupport(
        driver,
        approachObservation.player.position,
        approachTarget,
      );
    }
    const directEntry =
      isNetherDimension(approachObservation.player.position.dimension) &&
      distanceFromPortalPassage(approachObservation.player.position, passage) <= 2 &&
      Math.abs(approachObservation.player.position.y - approachTarget.y) <= 0.5;
    if (!directEntry) {
      const directApproach = yield* navigateUntilDimensionChange(
        driver,
        observation.player.position.dimension,
        driver.pathfind(approachTarget, 0, mergePathPolicy(options.path)),
      ).pipe(Effect.result);
      if (directApproach._tag === "Success" && directApproach.success) {
        yield* completePortalTransition(driver, observation.player.position.dimension);
        return;
      }
      if (directApproach._tag === "Failure") {
        const recoveryObservation = yield* driver.observe;
        if (
          !canRecoverPortalApproachWithStaircase(
            directApproach.failure,
            recoveryObservation.player.position,
            approachTarget,
            options.path,
          )
        ) {
          return yield* Effect.fail(directApproach.failure);
        }
        const destination = floorFeetPosition(approachTarget);
        const current = floorFeetPosition(recoveryObservation.player.position);
        const initialStart = staircaseStartPosition(destination, current);
        const changedDimension = yield* navigateUntilDimensionChange(
          driver,
          observation.player.position.dimension,
          Effect.gen(function* () {
            yield* driver.pathfindXZ(
              initialStart.x + 0.5,
              initialStart.z + 0.5,
              initialStart.dimension,
              1.5,
              {
                ...mergePathPolicy(options.path),
                allowPlacing: false,
                avoidFluids: true,
              },
            );
            const staged = yield* waitForVerticalSettlement(driver);
            const stagedPosition = floorFeetPosition(staged.player.position);
            if (!isPortalStaircaseDepthSupported(stagedPosition, destination)) {
              return yield* Effect.fail(directApproach.failure);
            }
            yield* excavateStaircase(driver, {
              from: staircaseStartPosition(destination, stagedPosition),
              to: destination,
              path: {
                ...options.path,
                avoidFluids: true,
              },
            });
          }),
        );
        if (changedDimension) {
          yield* completePortalTransition(driver, observation.player.position.dimension);
          return;
        }
      }
    }
    const crossingObservation = yield* driver.observe;
    if (crossingObservation.player.position.dimension !== observation.player.position.dimension) {
      yield* completePortalTransition(driver, observation.player.position.dimension);
      return;
    }
    yield* ensurePortalApproachSupport(driver, crossingObservation.player.position, approachTarget);
    yield* driver
      .withControl(
        Effect.gen(function* () {
          const changedBeforeContact = yield* walkToPortalContact(
            driver,
            passage,
            observation.player.position.dimension,
          );
          yield* driver.act({ type: "reset-movement" });
          if (changedBeforeContact) {
            return;
          }
          yield* holdPortalContactUntilDimensionChange(
            driver,
            passage,
            observation.player.position.dimension,
            180,
            250,
          ).pipe(
            Effect.timeoutOrElse({
              duration: 45_000,
              orElse: () =>
                Effect.fail(
                  behaviorError(
                    driver,
                    "The Nether portal did not change dimensions after contact",
                  ),
                ),
            }),
          );
        }),
      )
      .pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
    const enteredObservation = yield* driver.observe;
    if (enteredObservation.player.position.dimension === observation.player.position.dimension) {
      return yield* Effect.fail(
        behaviorError(driver, "The Nether portal did not change dimensions after entry"),
      );
    }
    yield* completePortalTransition(driver, observation.player.position.dimension);
  });
}

export interface ThrowItemOptions {
  readonly target?: BeatGamePosition;
  readonly yaw?: number;
  readonly pitch?: number;
}

export function throwEnderPearl(
  driver: BeatGameDriver,
  options: ThrowItemOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return throwSelectedItem(driver, ["minecraft:ender_pearl"], options);
}

export interface ThrowEyeOptions extends ThrowItemOptions {
  readonly observationRadius?: number;
  readonly observationDelayMs?: number;
}

export function throwEyeOfEnder(
  driver: BeatGameDriver,
  options: ThrowEyeOptions = {},
): Effect.Effect<BeatGameEyeSample, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const before = yield* driver.observe;
      const previous = yield* driver.queryEntities({
        origin: before.player.position,
        radius: options.observationRadius ?? 64,
        selector: { entityTypes: ["minecraft:eye_of_ender"] },
        maximumResults: 32,
      });
      const previousIds = new Set(previous.map(({ networkId }) => networkId));
      yield* driver.act({
        type: "select-item",
        selector: { itemIds: ["minecraft:ender_eye"] },
      });
      yield* lookForThrow(driver, before.player.position, options);
      yield* driver.act({ type: "use-item", hand: "main" });
      yield* Effect.sleep(options.observationDelayMs ?? 750);
      const observed = yield* driver.queryEntities({
        origin: before.player.position,
        radius: options.observationRadius ?? 64,
        selector: { entityTypes: ["minecraft:eye_of_ender"] },
        maximumResults: 32,
      });
      const eye = observed.find(({ networkId }) => !previousIds.has(networkId)) ?? observed[0];
      if (eye === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "The thrown eye of ender was not observed"),
        );
      }
      const direction =
        horizontalDirection(before.player.position, eye.position) ??
        horizontalDirection({ x: 0, z: 0 }, eye.velocity);
      if (direction === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "The observed eye of ender did not provide a direction"),
        );
      }
      return {
        origin: before.player.position,
        direction,
        observedAt: eye.observedAt,
        confidence: Math.hypot(eye.velocity.x, eye.velocity.z) > 0.01 ? 1 : 0.7,
      };
    }),
  );
}

export interface ActivateEndPortalOptions extends BeatGameBehaviorOptions {
  readonly center?: BeatGamePosition;
  readonly searchRadius?: number;
  readonly confirmationAttempts?: number;
  readonly confirmationDelayMs?: number;
}

export function activateEndPortal(
  driver: BeatGameDriver,
  options: ActivateEndPortalOptions = {},
): Effect.Effect<number, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const observation = yield* driver.observe;
      const center = options.center ?? observation.player.position;
      const searchRadius = options.searchRadius ?? 32;
      const frames = yield* driver.queryBlocks({
        center,
        radius: searchRadius,
        selector: {
          blockIds: ["minecraft:end_portal_frame"],
          properties: { eye: "false" },
        },
        maximumResults: 12,
      });
      let filledFrames = 0;
      for (const frame of frames) {
        const beforeFrame = yield* driver.observe;
        if (!portalBlockWithinReach(beforeFrame, frame.position)) {
          yield* driver.pathfind(
            {
              ...frame.position,
              y: frame.position.y + 1,
            },
            3,
            {
              ...mergePathPolicy(options.path),
              allowMining: false,
              allowPlacing: false,
            },
          );
        }
        const unfilledFrame = yield* driver.queryBlocks(
          exactBlockQuery(frame.position, {
            blockIds: ["minecraft:end_portal_frame"],
            properties: { eye: "false" },
          }),
        );
        if (!unfilledFrame.some(({ position }) => samePosition(position, frame.position))) {
          continue;
        }
        yield* driver.act({
          type: "select-item",
          selector: { itemIds: ["minecraft:ender_eye"] },
        });
        yield* driver.act({
          type: "interact-block",
          position: frame.position,
          face: "up",
          hand: "main",
        });
        filledFrames += 1;
      }
      const portal = yield* waitForBlock(
        driver,
        {
          center,
          radius: searchRadius,
          selector: { blockIds: ["minecraft:end_portal"] },
          maximumResults: 1,
        },
        positiveInteger(options.confirmationAttempts ?? 20, "confirmationAttempts"),
        nonNegativeInteger(options.confirmationDelayMs ?? 250, "confirmationDelayMs"),
      );
      if (portal === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "End portal blocks were not observable after filling the frames"),
        );
      }
      return filledFrames;
    }),
  );
}

export interface EnterEndPortalOptions extends BeatGameBehaviorOptions {
  readonly center?: BeatGamePosition;
  readonly searchRadius?: number;
  readonly transitionTimeoutMs?: number;
}

export function enterEndPortal(
  driver: BeatGameDriver,
  options: EnterEndPortalOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const observation = yield* driver.observe;
      const initialDimension = observation.player.position.dimension;
      if (isEndDimension(initialDimension)) {
        return;
      }
      const portals = yield* driver.queryBlocks({
        center: options.center ?? observation.player.position,
        radius: options.searchRadius ?? 32,
        selector: { blockIds: ["minecraft:end_portal"] },
        maximumResults: 16,
      });
      if (portals.length === 0) {
        return yield* Effect.fail(
          behaviorError(driver, "The active End portal was not observable"),
        );
      }
      const portalCenter = centerOfBlocks(portals);
      const rimTarget = nearestEndPortalRim(observation.player.position, portals);

      yield* Effect.gen(function* () {
        yield* ensureEndPortalRimReachable(
          driver,
          observation.player.position,
          portalCenter,
          rimTarget,
          options.path,
        );
        const changedOnApproach = yield* navigateUntilDimensionChange(
          driver,
          initialDimension,
          climbEndPortalRim(driver, rimTarget),
        );
        if (changedOnApproach) {
          return;
        }

        const entryObservation = yield* driver.observe;
        if (entryObservation.player.position.dimension !== initialDimension) {
          return;
        }
        const rotation = rotationToward(entryObservation.player.position, {
          x: portalCenter.x,
          y: entryObservation.player.position.y,
          z: portalCenter.z,
        });
        yield* driver.act({ type: "reset-movement" });
        yield* driver.act({
          type: "look",
          yaw: rotation.yaw,
          pitch: 0,
        });
        yield* waitForRotation(driver, rotation.yaw, 0, 40, 50);
        yield* driver.act({
          type: "set-movement",
          forward: true,
        });
        yield* waitForDimensionChange(driver, initialDimension, 50);
      }).pipe(
        Effect.timeoutOrElse({
          duration: positiveInteger(options.transitionTimeoutMs ?? 45_000, "transitionTimeoutMs"),
          orElse: () =>
            Effect.fail(
              behaviorError(driver, "The End portal did not change dimensions after entry"),
            ),
        }),
        Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
      );
      yield* driver.waitForChunks(0, 60_000);
    }),
  );
}

function climbEndPortalRim(
  driver: BeatGameDriver,
  rim: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    if (
      Math.floor(observation.player.position.x) === rim.x &&
      Math.floor(observation.player.position.z) === rim.z &&
      observation.player.position.y >= rim.y - 0.15
    ) {
      return;
    }
    const rotation = rotationToward(observation.player.position, {
      ...rim,
      x: rim.x + 0.5,
      z: rim.z + 0.5,
    });
    yield* driver.act({ type: "reset-movement" });
    yield* driver.act({
      type: "look",
      yaw: rotation.yaw,
      pitch: 0,
    });
    yield* waitForRotation(driver, rotation.yaw, 0, 40, 50);
    yield* driver.act({
      type: "set-movement",
      forward: true,
      jump: true,
      sprint: false,
    });
    yield* waitForEndPortalRim(driver, rim, 150, 10);
  }).pipe(
    Effect.timeoutOrElse({
      duration: 5_000,
      orElse: () => Effect.fail(behaviorError(driver, "Could not climb onto the End portal rim")),
    }),
    Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
  );
}

function waitForEndPortalRim(
  driver: BeatGameDriver,
  rim: BeatGameBlockPosition,
  attempts: number,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const position = observation.player.position;
      if (position.dimension !== rim.dimension) {
        return Effect.void;
      }
      if (
        Math.floor(position.x) === rim.x &&
        Math.floor(position.z) === rim.z &&
        position.y >= rim.y - 0.15
      ) {
        return Effect.void;
      }
      if (position.y < rim.y - 1.5) {
        return Effect.fail(behaviorError(driver, "Fell below the End portal rim"));
      }
      if (attempts <= 1) {
        return Effect.fail(behaviorError(driver, "Could not climb onto the End portal rim"));
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForEndPortalRim(driver, rim, attempts - 1, delayMs)),
      );
    }),
  );
}

function ensureEndPortalRimReachable(
  driver: BeatGameDriver,
  player: BeatGamePosition,
  portalCenter: Readonly<{ x: number; y: number; z: number }>,
  rim: BeatGameBlockPosition,
  path: Partial<BeatGamePathPolicy> | undefined,
): Effect.Effect<void, BeatGameDriverError> {
  const playerY = Math.floor(player.y);
  if (playerY >= rim.y - 1) {
    return Effect.void;
  }
  const direction = endPortalOutwardDirection(portalCenter, rim);
  const rise = rim.y - playerY;
  const retreat = {
    ...rim,
    x: rim.x + direction.x * (rise + 1),
    y: playerY,
    z: rim.z + direction.z * (rise + 1),
  };
  const pathPolicy = {
    ...mergePathPolicy(path),
    allowMining: false,
    allowPlacing: true,
  };

  return Effect.gen(function* () {
    const approach = Array.from({ length: Math.max(0, rise - 1) }, (_, index) => {
      const feetY = playerY + index + 1;
      const distanceFromRim = rim.y - feetY;
      const support = {
        ...rim,
        x: rim.x + direction.x * distanceFromRim,
        y: feetY - 1,
        z: rim.z + direction.z * distanceFromRim,
      };
      return {
        support,
        feet: { ...support, y: feetY },
      };
    });
    const firstSupport = approach[0]?.support;
    const currentObservation = yield* driver.observe;
    if (
      firstSupport !== undefined &&
      (!portalBlockWithinReach(currentObservation, firstSupport) ||
        !portalBlockWithinReach(currentObservation, { ...firstSupport, y: playerY - 1 }))
    ) {
      yield* driver.pathfind(retreat, 1, pathPolicy);
    }
    const material = staircaseSupportMaterial(yield* driver.observe);
    if (material === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "No solid block is available to build an End portal approach"),
      );
    }

    for (const step of approach) {
      yield* ensureEndPortalApproachColumn(driver, step.support, playerY - 2, material);
      yield* driver.pathfind(step.feet, 0, pathPolicy);
    }
  });
}

function endPortalOutwardDirection(
  portalCenter: Readonly<{ x: number; z: number }>,
  rim: BeatGameBlockPosition,
): Readonly<{ x: number; z: number }> {
  const xDistance = rim.x + 0.5 - portalCenter.x;
  const zDistance = rim.z + 0.5 - portalCenter.z;
  return Math.abs(xDistance) >= Math.abs(zDistance)
    ? { x: xDistance >= 0 ? 1 : -1, z: 0 }
    : { x: 0, z: zDistance >= 0 ? 1 : -1 };
}

function ensureEndPortalApproachColumn(
  driver: BeatGameDriver,
  tread: BeatGameBlockPosition,
  minimumAnchorY: number,
  material: string,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let anchor: BeatGameBlockObservation | undefined;
    for (let y = tread.y; y >= minimumAnchorY; y -= 1) {
      const block = yield* queryExactBlock(driver, { ...tread, y });
      if (block === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not observe the End portal approach"),
        );
      }
      if (!block.replaceable && !isGravityAffectedBlock(block.blockId)) {
        anchor = block;
        break;
      }
    }
    if (anchor === undefined) {
      return yield* Effect.fail(behaviorError(driver, "Could not anchor the End portal approach"));
    }

    for (let y = anchor.position.y + 1; y <= tread.y; y += 1) {
      const target = { ...tread, y };
      const existing = yield* queryExactBlock(driver, target);
      if (existing === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not observe an End portal approach block"),
        );
      }
      if (!existing.replaceable && !isGravityAffectedBlock(existing.blockId)) {
        continue;
      }
      if (isGravityAffectedBlock(existing.blockId)) {
        yield* driver.act({ type: "dig-block", position: target });
      }
      const against = { ...target, y: y - 1 };
      yield* placeStaircaseBlock(driver, material, against, "up", target);
      const placed = yield* waitForExactBlock(driver, target, { replaceable: false }, 10, 50);
      if (placed === undefined || isGravityAffectedBlock(placed.blockId)) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not build a stable End portal approach"),
        );
      }
    }
  });
}

function centerOfBlocks(
  blocks: readonly BeatGameBlockObservation[],
): Readonly<{ x: number; y: number; z: number }> {
  return {
    x: blocks.reduce((total, block) => total + block.position.x + 0.5, 0) / blocks.length,
    y: Math.min(...blocks.map(({ position }) => position.y)) + 0.5,
    z: blocks.reduce((total, block) => total + block.position.z + 0.5, 0) / blocks.length,
  };
}

function nearestEndPortalRim(
  position: BeatGamePosition,
  portals: readonly BeatGameBlockObservation[],
): BeatGameBlockPosition {
  const dimension = portals[0]?.position.dimension ?? position.dimension;
  const minimumX = Math.min(...portals.map(({ position }) => position.x));
  const maximumX = Math.max(...portals.map(({ position }) => position.x));
  const minimumY = Math.min(...portals.map(({ position }) => position.y));
  const minimumZ = Math.min(...portals.map(({ position }) => position.z));
  const maximumZ = Math.max(...portals.map(({ position }) => position.z));
  const centerX = Math.floor((minimumX + maximumX) / 2);
  const centerZ = Math.floor((minimumZ + maximumZ) / 2);
  const candidates: readonly BeatGameBlockPosition[] = [
    {
      x: minimumX - 1,
      y: minimumY + 1,
      z: centerZ,
      dimension,
    },
    {
      x: maximumX + 1,
      y: minimumY + 1,
      z: centerZ,
      dimension,
    },
    {
      x: centerX,
      y: minimumY + 1,
      z: minimumZ - 1,
      dimension,
    },
    {
      x: centerX,
      y: minimumY + 1,
      z: maximumZ + 1,
      dimension,
    },
  ];
  return candidates.reduce((nearest, candidate) =>
    distanceSquared(position, candidate) < distanceSquared(position, nearest) ? candidate : nearest,
  );
}

export interface FightEnderDragonOptions extends BeatGameBehaviorOptions {
  readonly searchRadius?: number;
  readonly maximumCrystalPasses?: number;
  readonly crystalRangedShotsPerPass?: number;
  readonly dragonRangedShotsPerPass?: number;
  readonly defeatConfirmationAttempts?: number;
  readonly defeatConfirmationDelayMs?: number;
}

export function fightEnderDragon(
  driver: BeatGameDriver,
  options: FightEnderDragonOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const searchRadius = options.searchRadius ?? 256;
    const maximumCrystalPasses = positiveInteger(
      options.maximumCrystalPasses ?? 6,
      "maximumCrystalPasses",
    );
    const crystalShots = positiveInteger(
      options.crystalRangedShotsPerPass ?? 3,
      "crystalRangedShotsPerPass",
    );
    const confirmationAttempts = positiveInteger(
      options.defeatConfirmationAttempts ?? 120,
      "defeatConfirmationAttempts",
    );
    const confirmationDelayMs = nonNegativeInteger(
      options.defeatConfirmationDelayMs ?? 250,
      "defeatConfirmationDelayMs",
    );
    const initialObservation = yield* driver.observe;
    if ((initialObservation.inventory.counts["minecraft:dragon_egg"] ?? 0) > 0) {
      return;
    }
    const encounter = yield* waitForDragonOrDefeatResult(
      driver,
      initialObservation.player.position,
      searchRadius,
      confirmationAttempts,
      confirmationDelayMs,
    );
    if (encounter.defeated) {
      return;
    }
    for (let pass = 0; pass < maximumCrystalPasses; pass += 1) {
      const observation = yield* driver.observe;
      const crystals = yield* queryEndEntities(
        driver,
        observation.player.position,
        "minecraft:end_crystal",
        searchRadius,
        32,
      );
      if (crystals.length === 0) {
        break;
      }
      for (const crystal of crystals) {
        const ranged = yield* rangedAttack(driver, {
          target: crystal,
          maximumShots: crystalShots,
          targetUnavailableTimeoutSeconds: 2,
          strafe: false,
          ...(options.path === undefined ? {} : { path: options.path }),
        }).pipe(Effect.result);
        if (ranged._tag === "Failure") {
          yield* attackEntity(driver, {
            target: crystal,
            maximumAttacks: 4,
            targetUnavailableTimeoutSeconds: 2,
            ...(options.path === undefined ? {} : { path: options.path }),
          }).pipe(Effect.ignore);
        }
      }
    }
    const afterCrystals = yield* driver.observe;
    const remainingCrystals = yield* queryEndEntities(
      driver,
      afterCrystals.player.position,
      "minecraft:end_crystal",
      searchRadius,
      1,
    );
    if (remainingCrystals.length > 0) {
      return yield* Effect.fail(
        behaviorError(driver, "End crystals remain after the configured attack passes"),
      );
    }
    const dragons = yield* queryEndEntities(
      driver,
      afterCrystals.player.position,
      "minecraft:ender_dragon",
      searchRadius,
      1,
    );
    const dragon = dragons[0];
    if (dragon === undefined) {
      yield* waitForDragonDefeatResult(
        driver,
        afterCrystals.player.position,
        searchRadius,
        confirmationAttempts,
        confirmationDelayMs,
      );
      return;
    }
    yield* rangedAttack(driver, {
      target: dragon,
      maximumShots: positiveInteger(
        options.dragonRangedShotsPerPass ?? 8,
        "dragonRangedShotsPerPass",
      ),
      targetUnavailableTimeoutSeconds: 3,
      strafe: false,
      ...(options.path === undefined ? {} : { path: options.path }),
    }).pipe(
      Effect.catch(() =>
        attackEntity(driver, {
          target: dragon,
          maximumAttacks: 16,
          targetUnavailableTimeoutSeconds: 3,
          ...(options.path === undefined ? {} : { path: options.path }),
        }),
      ),
    );
    yield* attackNearest(driver, {
      selector: {
        entityTypes: ["minecraft:ender_dragon"],
        alive: true,
      },
      radius: searchRadius,
      maximumTargets: 1,
      noTargetTimeoutSeconds: 3,
      completeWhenNoTarget: true,
      ...(options.path === undefined ? {} : { path: options.path }),
    });
    const finalObservation = yield* driver.observe;
    // The dragon remains observable during its ten-second death animation.
    // Confirm the exit portal or egg instead of starting another fight against
    // an entity which the server has already marked as defeated.
    yield* waitForDragonDefeatResult(
      driver,
      finalObservation.player.position,
      searchRadius,
      confirmationAttempts,
      confirmationDelayMs,
    );
  });
}

interface DragonEncounterObservation {
  readonly defeated: boolean;
}

function waitForDragonOrDefeatResult(
  driver: BeatGameDriver,
  center: BeatGamePosition,
  radius: number,
  attempts: number,
  delayMs: number,
): Effect.Effect<DragonEncounterObservation, BeatGameDriverError> {
  return queryEndEntities(driver, center, "minecraft:ender_dragon", radius, 1).pipe(
    Effect.flatMap((dragons) => {
      const dragon = dragons[0];
      if (dragon !== undefined) {
        return Effect.succeed({ defeated: false });
      }
      return queryDragonDefeatBlocks(driver, center, radius).pipe(
        Effect.flatMap((results) => {
          if (results.length > 0) {
            return Effect.succeed({ defeated: true });
          }
          if (attempts <= 1) {
            return Effect.fail(
              behaviorError(
                driver,
                "The Ender Dragon was not observable and its defeat was not confirmed",
              ),
            );
          }
          return Effect.sleep(delayMs).pipe(
            Effect.andThen(
              waitForDragonOrDefeatResult(driver, center, radius, attempts - 1, delayMs),
            ),
          );
        }),
      );
    }),
  );
}

function waitForDragonDefeatResult(
  driver: BeatGameDriver,
  center: BeatGamePosition,
  radius: number,
  attempts: number,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return queryDragonDefeatBlocks(driver, center, radius).pipe(
    Effect.flatMap((results) => {
      if (results.length > 0) {
        return Effect.void;
      }
      if (attempts <= 1) {
        return Effect.fail(
          behaviorError(driver, "The dragon disappeared before its egg or exit portal appeared"),
        );
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForDragonDefeatResult(driver, center, radius, attempts - 1, delayMs)),
      );
    }),
  );
}

function queryDragonDefeatBlocks(
  driver: BeatGameDriver,
  center: BeatGamePosition,
  radius: number,
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return driver.queryBlocks({
    center,
    radius,
    selector: {
      blockIds: ["minecraft:dragon_egg", "minecraft:end_portal"],
    },
    maximumResults: 1,
  });
}

export interface CollectDragonEggOptions extends BeatGameBehaviorOptions {
  readonly searchRadius?: number;
  readonly confirmationAttempts?: number;
  readonly confirmationDelayMs?: number;
}

export function collectDragonEgg(
  driver: BeatGameDriver,
  options: CollectDragonEggOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const attempts = positiveInteger(options.confirmationAttempts ?? 40, "confirmationAttempts");
      const delayMs = nonNegativeInteger(options.confirmationDelayMs ?? 250, "confirmationDelayMs");
      const initialObservation = yield* driver.observe;
      if ((initialObservation.inventory.counts["minecraft:dragon_egg"] ?? 0) > 0) {
        return;
      }
      const center = initialObservation.player.position;
      const searchRadius = options.searchRadius ?? 64;
      const initialEgg = (yield* queryDragonEggs(driver, center, searchRadius))[0];
      if (initialEgg === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "The dragon egg was not observable after the dragon fight"),
        );
      }

      yield* driver.pathfind(initialEgg.position, 3, mergePathPolicy(options.path));
      yield* driver
        .act({
          type: "dig-block",
          position: initialEgg.position,
        })
        .pipe(Effect.ignore);

      const movedEgg = yield* waitForMovedDragonEgg(
        driver,
        center,
        searchRadius,
        initialEgg.position,
        attempts,
        delayMs,
      );
      if (movedEgg === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "The dragon egg did not teleport away from the exit portal"),
        );
      }

      yield* driver.pathfind(movedEgg.position, 3, mergePathPolicy(options.path));
      const torchPosition = {
        ...movedEgg.position,
        y: movedEgg.position.y - 2,
      };
      const torchSupport = {
        ...movedEgg.position,
        y: movedEgg.position.y - 3,
      };
      const eggSupport = {
        ...movedEgg.position,
        y: movedEgg.position.y - 1,
      };
      yield* prepareDragonEggTorchSupport(driver, eggSupport, torchPosition, torchSupport);
      yield* driver.act({
        type: "select-item",
        selector: { itemIds: ["minecraft:torch"] },
      });
      yield* driver.act({
        type: "place-block",
        against: torchSupport,
        face: "up",
        hand: "main",
      });
      yield* driver.act({
        type: "dig-block",
        position: eggSupport,
      });
      yield* Effect.sleep(delayMs);
      yield* driver
        .pathfind(movedEgg.position, 1, mergePathPolicy(options.path))
        .pipe(Effect.ignore);

      const collected = yield* waitForInventoryItem(
        driver,
        "minecraft:dragon_egg",
        attempts,
        delayMs,
      );
      if (!collected) {
        return yield* Effect.fail(
          behaviorError(driver, "The dragon egg did not enter the bot inventory"),
        );
      }
    }),
  );
}

function prepareDragonEggTorchSupport(
  driver: BeatGameDriver,
  eggSupport: BeatGameBlockPosition,
  torchPosition: BeatGameBlockPosition,
  torchSupport: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observedEggSupport = yield* observeExactBlock(driver, eggSupport);
    if (
      observedEggSupport === undefined ||
      observedEggSupport.replaceable ||
      !observedEggSupport.diggable
    ) {
      return yield* Effect.fail(
        behaviorError(driver, "The dragon egg did not land on a removable support block"),
      );
    }

    const torchCell = yield* observeExactBlock(driver, torchPosition);
    if (torchCell !== undefined && !torchCell.replaceable) {
      if (!torchCell.diggable) {
        return yield* Effect.fail(
          behaviorError(driver, "The block two spaces below the dragon egg cannot be removed"),
        );
      }
      yield* driver.act({
        type: "dig-block",
        position: torchPosition,
      });
      const cleared = yield* waitForExactBlockState(
        driver,
        torchPosition,
        (block) => block?.replaceable === true,
        10,
        50,
      );
      if (cleared?.replaceable !== true) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not clear space for the dragon egg collection torch"),
        );
      }
    }

    let observedTorchSupport = yield* observeExactBlock(driver, torchSupport);
    if (!isReliableDragonEggTorchSupport(observedTorchSupport)) {
      if (observedTorchSupport !== undefined && !observedTorchSupport.replaceable) {
        if (!observedTorchSupport.diggable) {
          return yield* Effect.fail(
            behaviorError(
              driver,
              "The dragon egg torch support cannot hold a torch or be replaced",
            ),
          );
        }
        yield* driver.act({
          type: "dig-block",
          position: torchSupport,
        });
      }

      const observation = yield* driver.observe;
      const material = staircaseSupportMaterial(observation);
      if (material === undefined) {
        return yield* Effect.fail(
          behaviorError(
            driver,
            "No stable block item is available to support the dragon egg torch",
          ),
        );
      }
      yield* placeStaircaseBlock(driver, material, eggSupport, "down", torchPosition);
      yield* placeStaircaseBlock(driver, material, torchPosition, "down", torchSupport);
      yield* driver.act({
        type: "dig-block",
        position: torchPosition,
      });
      const cleared = yield* waitForExactBlockState(
        driver,
        torchPosition,
        (block) => block?.replaceable === true,
        10,
        50,
      );
      if (cleared?.replaceable !== true) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not reopen space above the dragon egg torch support"),
        );
      }
      observedTorchSupport = yield* observeExactBlock(driver, torchSupport);
    }

    if (!isReliableDragonEggTorchSupport(observedTorchSupport)) {
      return yield* Effect.fail(
        behaviorError(driver, "Could not construct a stable dragon egg torch support"),
      );
    }
  });
}

function isReliableDragonEggTorchSupport(block: BeatGameBlockObservation | undefined): boolean {
  return (
    block !== undefined &&
    !block.replaceable &&
    block.solid !== false &&
    !isGravityAffectedBlock(block.blockId)
  );
}

export interface ExitEndOptions extends BeatGameBehaviorOptions {
  readonly searchRadius?: number;
  readonly confirmationAttempts?: number;
  readonly confirmationDelayMs?: number;
}

export function exitEnd(
  driver: BeatGameDriver,
  options: ExitEndOptions = {},
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const observation = yield* driver.observe;
      const initialDimension = observation.player.position.dimension;
      if (!isEndDimension(initialDimension)) {
        return;
      }
      const portals = yield* driver.queryBlocks({
        center: observation.player.position,
        radius: options.searchRadius ?? 64,
        selector: { blockIds: ["minecraft:end_portal"] },
        maximumResults: 16,
      });
      const portal = portals[0];
      if (portal === undefined) {
        return yield* Effect.fail(behaviorError(driver, "The End exit portal was not observable"));
      }
      const portalCenter = centerOfBlocks(portals);
      const rimTarget = nearestEndPortalRim(observation.player.position, portals);

      const attempts = positiveInteger(options.confirmationAttempts ?? 40, "confirmationAttempts");
      const delayMs = nonNegativeInteger(options.confirmationDelayMs ?? 250, "confirmationDelayMs");
      const entryTransition = yield* Effect.gen(function* () {
        yield* ensureEndPortalRimReachable(
          driver,
          observation.player.position,
          portalCenter,
          rimTarget,
          options.path,
        );
        const changedOnApproach = yield* navigateUntilDimensionChange(
          driver,
          initialDimension,
          climbEndPortalRim(driver, rimTarget),
        );
        if (changedOnApproach) {
          return "dimension" as const;
        }

        const entryObservation = yield* driver.observe;
        if (entryObservation.player.position.dimension !== initialDimension) {
          return "dimension" as const;
        }
        const rotation = rotationToward(entryObservation.player.position, {
          x: portalCenter.x,
          y: entryObservation.player.position.y,
          z: portalCenter.z,
        });
        yield* driver.act({ type: "reset-movement" });
        yield* driver.act({
          type: "look",
          yaw: rotation.yaw,
          pitch: 0,
        });
        yield* waitForRotation(driver, rotation.yaw, 0, 40, 50);
        return yield* Effect.raceFirst(
          waitForGameEvent(driver, "win_game").pipe(Effect.as("credits" as const)),
          Effect.gen(function* () {
            yield* driver.act({
              type: "set-movement",
              forward: true,
              sprint: false,
            });
            return yield* Effect.raceFirst(
              waitForDimensionChange(driver, initialDimension, Math.max(10, delayMs)).pipe(
                Effect.as("dimension" as const),
              ),
              Effect.sleep(END_EXIT_PORTAL_ENTRY_TIMEOUT_MS).pipe(Effect.as("timeout" as const)),
            );
          }),
        );
      }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
      if (entryTransition === "dimension") {
        return;
      }

      yield* driver.act({ type: "respawn" });
      const exited = yield* waitForDimensionExit(driver, attempts, delayMs);
      if (!exited) {
        return yield* Effect.fail(
          behaviorError(driver, "The bot remained in the End after entering the exit portal"),
        );
      }
    }),
  );
}

function waitForGameEvent(
  driver: BeatGameDriver,
  eventName: string,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.events.pipe(
    Stream.filter((event) => event.type === "game-event" && event.event === eventName),
    Stream.runHead,
    Effect.flatMap((event) => (Option.isSome(event) ? Effect.void : Effect.never)),
  );
}

export { triangulateStronghold };

function queryDragonEggs(
  driver: BeatGameDriver,
  center: BeatGamePosition,
  radius: number,
): Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError> {
  return driver.queryBlocks({
    center,
    radius,
    selector: { blockIds: ["minecraft:dragon_egg"] },
    maximumResults: 4,
  });
}

function waitForMovedDragonEgg(
  driver: BeatGameDriver,
  center: BeatGamePosition,
  radius: number,
  initialPosition: BeatGameBlockPosition,
  attempts: number,
  delayMs: number,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return queryDragonEggs(driver, center, radius).pipe(
    Effect.flatMap((eggs) => {
      const moved = eggs.find(
        ({ position }) =>
          position.x !== initialPosition.x ||
          position.y !== initialPosition.y ||
          position.z !== initialPosition.z,
      );
      if (moved !== undefined || attempts <= 1) {
        return Effect.succeed(moved);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(
          waitForMovedDragonEgg(driver, center, radius, initialPosition, attempts - 1, delayMs),
        ),
      );
    }),
  );
}

function waitForInventoryItem(
  driver: BeatGameDriver,
  itemId: string,
  attempts: number,
  delayMs: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      if ((observation.inventory.counts[itemId] ?? 0) > 0) {
        return Effect.succeed(true);
      }
      if (attempts <= 1) {
        return Effect.succeed(false);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForInventoryItem(driver, itemId, attempts - 1, delayMs)),
      );
    }),
  );
}

function waitForDimensionExit(
  driver: BeatGameDriver,
  attempts: number,
  delayMs: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      if (!isEndDimension(observation.player.position.dimension)) {
        return Effect.succeed(true);
      }
      if (attempts <= 1) {
        return Effect.succeed(false);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForDimensionExit(driver, attempts - 1, delayMs)),
      );
    }),
  );
}

function waitForDimensionChange(
  driver: BeatGameDriver,
  initialDimension: string,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) =>
      observation.player.position.dimension !== initialDimension
        ? Effect.void
        : Effect.sleep(delayMs).pipe(
            Effect.andThen(waitForDimensionChange(driver, initialDimension, delayMs)),
          ),
    ),
  );
}

function navigateUntilDimensionChange(
  driver: BeatGameDriver,
  initialDimension: string,
  navigation: Effect.Effect<void, BeatGameDriverError>,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.raceFirst(
    navigation.pipe(Effect.as(false)),
    waitForDimensionChange(driver, initialDimension, 50).pipe(
      Effect.tap(() => driver.act({ type: "reset-movement" })),
      Effect.as(true),
    ),
  );
}

function waitForRotation(
  driver: BeatGameDriver,
  yaw: number,
  pitch: number,
  attempts: number,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const rotation = observation.player.rotation;
      if (
        Math.abs(wrappedDegrees(rotation.yaw - yaw)) <= 4 &&
        Math.abs(rotation.pitch - pitch) <= 4
      ) {
        return Effect.void;
      }
      if (attempts <= 1) {
        return Effect.fail(
          behaviorError(
            driver,
            `Could not finish facing yaw ${yaw.toFixed(1)}, pitch ${pitch.toFixed(1)}`,
          ),
        );
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForRotation(driver, yaw, pitch, attempts - 1, delayMs)),
      );
    }),
  );
}

function wrappedDegrees(value: number): number {
  return ((((value + 180) % 360) + 360) % 360) - 180;
}

function portalContactTarget(
  portal: BeatGameBlockPosition,
  passage: PortalPassage,
): BeatGamePosition {
  return {
    ...portal,
    x: passage.x,
    y: passage.y,
    z: passage.z,
  };
}

function walkToPortalContact(
  driver: BeatGameDriver,
  passage: PortalPassage,
  initialDimension: string,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const observation = yield* driver.observe;
      const position = observation.player.position;
      if (position.dimension !== initialDimension) {
        return true;
      }
      if (hasPortalEntryContact(position, passage)) {
        return false;
      }
      if (distanceFromPortalPassage(position, passage) > 3) {
        return yield* Effect.fail(
          behaviorError(driver, "Moved away from the Nether portal while approaching it"),
        );
      }

      const rotation = rotationToward(position, portalContactTarget(position, passage));
      yield* driver.act({ type: "reset-movement" });
      yield* driver.act({
        type: "look",
        yaw: rotation.yaw,
        pitch: 0,
      });
      yield* waitForRotation(driver, rotation.yaw, 0, 40, 50);
      yield* driver.act({
        type: "set-movement",
        forward: true,
        sprint: false,
      });
      yield* Effect.sleep(75);
    }
    return yield* Effect.fail(
      behaviorError(driver, "Could not make controlled contact with the Nether portal"),
    );
  });
}

function holdPortalContactUntilDimensionChange(
  driver: BeatGameDriver,
  passage: PortalPassage,
  initialDimension: string,
  attempts: number,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const position = observation.player.position;
    if (position.dimension !== initialDimension) {
      return;
    }
    if (attempts <= 0) {
      return yield* Effect.fail(
        behaviorError(driver, "The Nether portal did not change dimensions while contact was held"),
      );
    }
    if (distanceFromPortalPassage(position, passage) > 3) {
      return yield* Effect.fail(
        behaviorError(driver, "Moved away from the Nether portal while waiting for it to activate"),
      );
    }

    yield* driver.act({ type: "reset-movement" });
    if (!hasPortalEntryContact(position, passage)) {
      const changedDimension = yield* walkToPortalContact(driver, passage, initialDimension);
      if (changedDimension) {
        return;
      }
    }
    yield* Effect.sleep(delayMs);
    yield* holdPortalContactUntilDimensionChange(
      driver,
      passage,
      initialDimension,
      attempts - 1,
      delayMs,
    );
  });
}

function distanceFromPortalPassage(position: BeatGamePosition, passage: PortalPassage): number {
  if (passage.axis === "x") {
    return Math.hypot(
      Math.abs(position.z - passage.z),
      Math.max(0, Math.abs(position.x - passage.x) - passage.horizontalRadius),
    );
  }
  if (passage.axis === "z") {
    return Math.hypot(
      Math.abs(position.x - passage.x),
      Math.max(0, Math.abs(position.z - passage.z) - passage.horizontalRadius),
    );
  }
  return Math.hypot(position.x - passage.x, position.z - passage.z);
}

function leavePortalForReentry(
  driver: BeatGameDriver,
  passage: PortalPassage,
  position: BeatGamePosition,
  rotation: Readonly<{ yaw: number; pitch: number }>,
): Effect.Effect<BeatGamePosition, BeatGameDriverError> {
  const direction = directionFromRotation(rotation);
  const normal =
    passage.axis === "x"
      ? direction.z
      : passage.axis === "z"
        ? direction.x
        : Math.abs(direction.x) >= Math.abs(direction.z)
          ? direction.x
          : direction.z;
  const preferredSign = normal >= 0 ? 1 : -1;

  return Effect.gen(function* () {
    const targets = yield* rankedPortalRetreatTargets(driver, passage, position, preferredSign);
    let lastFailure: BeatGameDriverError | undefined;
    for (const target of targets) {
      const attempt = yield* driver.withControl(
        Effect.gen(function* () {
          const current = yield* driver.observe;
          if (current.player.position.dimension !== position.dimension) {
            return current.player.position;
          }
          const desiredRotation = rotationToward(current.player.position, target);
          yield* driver.act({ type: "reset-movement" });
          yield* driver.act({
            type: "look",
            yaw: desiredRotation.yaw,
            pitch: 0,
          });
          yield* waitForRotation(driver, desiredRotation.yaw, 0, 40, 50);
          yield* driver.act({
            type: "set-movement",
            forward: true,
            sprint: true,
          });
          return yield* waitForPortalExit(driver, passage, position.dimension, 60, 50);
        }).pipe(
          Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)),
          Effect.result,
        ),
      );
      if (attempt._tag === "Success") {
        return attempt.success;
      }
      lastFailure = attempt.failure;
    }
    return yield* Effect.fail(
      lastFailure ??
        behaviorError(driver, "Could not step out of the Nether portal before re-entry"),
    );
  });
}

function completePortalTransition(
  driver: BeatGameDriver,
  initialDimension: string,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* driver.waitForChunks(0, 60_000);
    const arrival = yield* driver.observe;
    if (arrival.player.position.dimension === initialDimension) {
      return yield* Effect.fail(
        behaviorError(driver, "The Nether portal transition did not reach another dimension"),
      );
    }
    const observedPortals = (yield* driver.queryBlocks({
      center: arrival.player.position,
      radius: 4,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: 32,
    })).filter(({ position }) => position.dimension === arrival.player.position.dimension);
    const nearestPortal = observedPortals.reduce<BeatGameBlockObservation | undefined>(
      (nearest, candidate) =>
        nearest === undefined ||
        distanceSquared(candidate.position, arrival.player.position) <
          distanceSquared(nearest.position, arrival.player.position)
          ? candidate
          : nearest,
      undefined,
    );
    if (nearestPortal === undefined) {
      return;
    }
    const axis = resolvePortalAxis(nearestPortal.properties.axis, observedPortals);
    const passage = portalPassage(nearestPortal.position, axis, observedPortals);
    if (!hasPortalContact(arrival.player.position, passage)) {
      return;
    }
    const exited = yield* leavePortalForReentry(
      driver,
      passage,
      arrival.player.position,
      arrival.player.rotation,
    );
    if (exited.dimension !== arrival.player.position.dimension) {
      return yield* Effect.fail(
        behaviorError(driver, "Stepping out of the destination portal crossed dimensions again"),
      );
    }
  });
}

function rankedPortalRetreatTargets(
  driver: BeatGameDriver,
  passage: PortalPassage,
  position: BeatGamePosition,
  preferredSign: number,
): Effect.Effect<readonly BeatGamePosition[], BeatGameDriverError> {
  const candidates = [preferredSign, -preferredSign].map((sign) => ({
    sign,
    target: portalRetreatTarget(position, passage, sign),
    exit: portalExitFeetPosition(position, passage, sign),
  }));
  return driver
    .queryBlocks({
      center: {
        x: passage.x,
        y: passage.y,
        z: passage.z,
        dimension: position.dimension,
      },
      radius: 3,
      selector: {},
      maximumResults: 256,
    })
    .pipe(
      Effect.map((blocks) => {
        const observed = new Map(blocks.map((block) => [positionKey(block.position), block]));
        return candidates
          .map((candidate, index) => ({
            ...candidate,
            index,
            safety: portalExitSafety(observed, candidate.exit),
          }))
          .sort((left, right) => right.safety - left.safety || left.index - right.index)
          .map(({ target }) => target);
      }),
    );
}

function portalExitFeetPosition(
  position: BeatGamePosition,
  passage: PortalPassage,
  sign: number,
): BeatGameBlockPosition {
  return {
    x: Math.floor(passage.axis === "z" ? passage.x + sign : passage.x),
    y: Math.floor(passage.y),
    z: Math.floor(passage.axis === "x" ? passage.z + sign : passage.z),
    dimension: position.dimension,
  };
}

function portalExitSafety(
  observed: ReadonlyMap<string, BeatGameBlockObservation>,
  feet: BeatGameBlockPosition,
): number {
  const support = observed.get(positionKey({ ...feet, y: feet.y - 1 }));
  const body = observed.get(positionKey(feet));
  const head = observed.get(positionKey({ ...feet, y: feet.y + 1 }));
  if (support === undefined || body === undefined || head === undefined) {
    return 1;
  }
  const stableSupport =
    support.solid === true ||
    (!support.replaceable &&
      support.blockId !== "minecraft:nether_portal" &&
      support.blockId !== "minecraft:fire" &&
      support.blockId !== "minecraft:lava");
  const clearBody = body.replaceable && body.blockId !== "minecraft:lava";
  const clearHead = head.replaceable && head.blockId !== "minecraft:lava";
  return stableSupport && clearBody && clearHead ? 2 : 0;
}

function portalRetreatTarget(
  position: BeatGamePosition,
  passage: PortalPassage,
  sign: number,
): BeatGamePosition {
  if (passage.axis === "x") {
    return {
      ...position,
      x: passage.x,
      y: passage.y,
      z: passage.z + sign * 3,
    };
  }
  if (passage.axis === "z") {
    return {
      ...position,
      x: passage.x + sign * 3,
      y: passage.y,
      z: passage.z,
    };
  }
  return {
    ...position,
    x: passage.x + sign * 3,
    y: passage.y,
    z: passage.z,
  };
}

function waitForPortalExit(
  driver: BeatGameDriver,
  passage: PortalPassage,
  initialDimension: string,
  attempts: number,
  delayMs: number,
): Effect.Effect<BeatGamePosition, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const position = observation.player.position;
      if (position.dimension !== initialDimension || !hasPortalContact(position, passage)) {
        return Effect.succeed(position);
      }
      if (attempts <= 1) {
        return Effect.fail(
          behaviorError(driver, "Could not step out of the Nether portal before re-entry"),
        );
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForPortalExit(driver, passage, initialDimension, attempts - 1, delayMs)),
      );
    }),
  );
}

function hasPortalContact(position: BeatGamePosition, passage: PortalPassage): boolean {
  // Positions describe the player's feet, not the center of their hitbox.
  // Include the player's 1.8-block height and 0.6-block width so standing
  // against the lower portal face counts as contact.
  const verticalContact = position.y >= passage.y - 1.81 && position.y <= passage.y + 3;
  const horizontalContact =
    passage.axis === "x"
      ? Math.abs(position.z - passage.z) < 0.8 &&
        Math.abs(position.x - passage.x) <= passage.horizontalRadius + 0.1
      : passage.axis === "z"
        ? Math.abs(position.x - passage.x) < 0.8 &&
          Math.abs(position.z - passage.z) <= passage.horizontalRadius + 0.1
        : distanceSquared(position, passage) <= 3;
  return verticalContact && horizontalContact;
}

function hasPortalEntryContact(position: BeatGamePosition, passage: PortalPassage): boolean {
  return hasPortalContact(position, passage);
}

function portalApproachTarget(
  player: BeatGamePosition,
  portal: BeatGameBlockPosition,
  axis: string | undefined,
  passage: PortalPassage,
): BeatGamePosition {
  if (axis === "x") {
    return {
      ...portal,
      x: passage.x,
      y: passage.y,
      z: portal.z + (player.z >= portal.z + 0.5 ? 1 : -1),
    };
  }
  if (axis === "z") {
    return {
      ...portal,
      y: passage.y,
      x: portal.x + (player.x >= portal.x + 0.5 ? 1 : -1),
      z: passage.z,
    };
  }
  return portal;
}

function canRecoverPortalApproachWithStaircase(
  error: BeatGameDriverError,
  current: BeatGamePosition,
  approach: BeatGamePosition,
  path: Partial<BeatGamePathPolicy> | undefined,
): boolean {
  if (current.dimension !== approach.dimension || mergePathPolicy(path).allowMining === false) {
    return false;
  }
  if (!isPortalStaircaseDepthSupported(floorFeetPosition(current), floorFeetPosition(approach))) {
    return false;
  }
  return error.retryable && PORTAL_STAIRCASE_ROUTE_FAILURE_CODES.has(error.code ?? "");
}

function isPortalStaircaseDepthSupported(
  current: BeatGameBlockPosition,
  destination: BeatGameBlockPosition,
): boolean {
  const depth = current.y - destination.y;
  return (
    current.dimension === destination.dimension &&
    depth >= PORTAL_STAIRCASE_MINIMUM_DEPTH &&
    depth <= PORTAL_STAIRCASE_MAXIMUM_DEPTH
  );
}

function floorFeetPosition(position: BeatGamePosition): BeatGameBlockPosition {
  return {
    x: Math.floor(position.x),
    y: Math.floor(position.y),
    z: Math.floor(position.z),
    dimension: position.dimension,
  };
}

function ensurePortalApproachSupport(
  driver: BeatGameDriver,
  origin: BeatGamePosition,
  approach: BeatGamePosition,
): Effect.Effect<void, BeatGameDriverError> {
  const finalSupport = {
    x: Math.floor(approach.x),
    y: Math.floor(approach.y) - 1,
    z: Math.floor(approach.z),
    dimension: approach.dimension,
  };
  const supports = portalApproachSupports(origin, finalSupport);
  return Effect.gen(function* () {
    for (const support of supports) {
      const surroundings = yield* driver.queryBlocks({
        center: support,
        radius: 2,
        selector: {},
        maximumResults: 64,
      });
      const supportBlock = surroundings.find(({ position }) => samePosition(position, support));
      if (supportBlock === undefined || !supportBlock.replaceable) {
        continue;
      }
      const anchor = portalSupportAnchor(support, surroundings);
      if (anchor === undefined) {
        continue;
      }

      yield* driver.withControl(
        Effect.gen(function* () {
          yield* driver.act({
            type: "select-item",
            selector: {
              itemIds: [
                "minecraft:cobblestone",
                "minecraft:cobbled_deepslate",
                "minecraft:dirt",
                "minecraft:netherrack",
                "minecraft:oak_log",
                "minecraft:spruce_log",
                "minecraft:birch_log",
                "minecraft:jungle_log",
                "minecraft:acacia_log",
                "minecraft:dark_oak_log",
                "minecraft:mangrove_log",
                "minecraft:cherry_log",
                "minecraft:pale_oak_log",
                "minecraft:crimson_stem",
                "minecraft:warped_stem",
              ],
            },
          });
          yield* driver.act({
            type: "place-block",
            against: anchor.position,
            face: anchor.face,
            hand: "main",
          });
        }),
      );
      yield* Effect.sleep(150);

      const placed = yield* driver.queryBlocks({
        center: support,
        radius: 1,
        selector: { replaceable: false },
        maximumResults: 16,
      });
      if (!placed.some(({ position }) => samePosition(position, support))) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not place a safe support beside the Nether portal"),
        );
      }
    }
  });
}

function canReachPortalApproachSupports(
  origin: BeatGamePosition,
  approach: BeatGamePosition,
): boolean {
  const finalSupport = {
    x: Math.floor(approach.x),
    y: Math.floor(approach.y) - 1,
    z: Math.floor(approach.z),
    dimension: approach.dimension,
  };
  const eye = { ...origin, y: origin.y + 1.62 };
  return portalApproachSupports(origin, finalSupport).every(
    (support) =>
      distanceSquared(eye, blockCenter(support)) <= PORTAL_SUPPORT_INTERACTION_REACH ** 2,
  );
}

function portalApproachSupports(
  origin: BeatGamePosition,
  finalSupport: BeatGameBlockPosition,
): readonly BeatGameBlockPosition[] {
  if (origin.dimension !== finalSupport.dimension || Math.floor(origin.y) - 1 !== finalSupport.y) {
    return [finalSupport];
  }
  let x = Math.floor(origin.x);
  let z = Math.floor(origin.z);
  const distance = Math.abs(finalSupport.x - x) + Math.abs(finalSupport.z - z);
  if (distance > 8) {
    return [finalSupport];
  }
  const supports: BeatGameBlockPosition[] = [];
  while (x !== finalSupport.x) {
    x += Math.sign(finalSupport.x - x);
    supports.push({ ...finalSupport, x, z });
  }
  while (z !== finalSupport.z) {
    z += Math.sign(finalSupport.z - z);
    supports.push({ ...finalSupport, x, z });
  }
  return supports.length === 0 ? [finalSupport] : supports;
}

function portalSupportAnchor(
  support: BeatGameBlockPosition,
  surroundings: readonly BeatGameBlockObservation[],
):
  | Readonly<{
      position: BeatGameBlockPosition;
      face: "up" | "north" | "south" | "east" | "west";
    }>
  | undefined {
  const base = { ...support, y: support.y - 1 };
  const baseBlock = surroundings.find(({ position }) => samePosition(position, base));
  if (baseBlock !== undefined && !baseBlock.replaceable) {
    return { position: base, face: "up" };
  }
  const neighbors = [
    { ...support, x: support.x - 1 },
    { ...support, x: support.x + 1 },
    { ...support, z: support.z - 1 },
    { ...support, z: support.z + 1 },
  ];
  const neighbor = neighbors.find((position) =>
    surroundings.some((block) => samePosition(block.position, position) && !block.replaceable),
  );
  return neighbor === undefined
    ? undefined
    : {
        position: neighbor,
        face: horizontalFace(neighbor, support),
      };
}

interface PortalPassage {
  readonly axis: "x" | "z" | undefined;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly horizontalRadius: number;
}

function resolvePortalAxis(
  declaredAxis: string | undefined,
  observedPortals: readonly BeatGameBlockObservation[],
): "x" | "z" | undefined {
  const positions = observedPortals.map(({ position }) => position);
  if (positions.length >= 2) {
    const xSpan = coordinateSpan(positions.map(({ x }) => x));
    const zSpan = coordinateSpan(positions.map(({ z }) => z));
    if (xSpan !== zSpan) {
      return xSpan > zSpan ? "x" : "z";
    }
  }
  const normalized = declaredAxis?.toLowerCase();
  return normalized === "x" || normalized === "z" ? normalized : undefined;
}

function coordinateSpan(values: readonly number[]): number {
  return Math.max(...values) - Math.min(...values);
}

function portalPassage(
  portal: BeatGameBlockPosition,
  axis: "x" | "z" | undefined,
  observedPortals: readonly BeatGameBlockObservation[],
): PortalPassage {
  const bottom = Math.min(...observedPortals.map(({ position }) => position.y), portal.y);
  if (axis === "x") {
    const horizontal = observedPortals
      .filter(({ position }) => position.dimension === portal.dimension && position.z === portal.z)
      .map(({ position }) => position.x);
    const span = blockSpan(horizontal, portal.x);
    return {
      axis,
      x: span.center,
      y: bottom,
      z: portal.z + 0.5,
      horizontalRadius: span.radius,
    };
  }
  if (axis === "z") {
    const horizontal = observedPortals
      .filter(({ position }) => position.dimension === portal.dimension && position.x === portal.x)
      .map(({ position }) => position.z);
    const span = blockSpan(horizontal, portal.z);
    return {
      axis,
      x: portal.x + 0.5,
      y: bottom,
      z: span.center,
      horizontalRadius: span.radius,
    };
  }
  return {
    axis,
    x: portal.x + 0.5,
    y: bottom,
    z: portal.z + 0.5,
    horizontalRadius: 1,
  };
}

function blockSpan(
  coordinates: readonly number[],
  fallback: number,
): Readonly<{ center: number; radius: number }> {
  const minimum = coordinates.length === 0 ? fallback : Math.min(...coordinates);
  const maximum = coordinates.length === 0 ? fallback : Math.max(...coordinates);
  return {
    center: (minimum + maximum + 1) / 2,
    radius: (maximum - minimum + 1) / 2 + 0.25,
  };
}

function staircaseSteps(
  from: BeatGameBlockPosition,
  to: BeatGameBlockPosition,
): readonly BeatGameBlockPosition[] {
  if (from.dimension !== to.dimension) {
    throw new RangeError("Staircase endpoints must be in the same dimension");
  }
  for (const [name, position] of [
    ["from", from],
    ["to", to],
  ] as const) {
    for (const coordinate of ["x", "y", "z"] as const) {
      if (!Number.isSafeInteger(position[coordinate])) {
        throw new RangeError(`${name}.${coordinate} must be a safe integer`);
      }
    }
  }
  const depth = from.y - to.y;
  if (depth < 1) {
    throw new RangeError("A staircase must descend at least one block");
  }
  const xDistance = Math.abs(to.x - from.x);
  const zDistance = Math.abs(to.z - from.z);
  const directDistance = xDistance + zDistance;
  if (directDistance > depth) {
    throw new RangeError("Staircase horizontal distance cannot exceed its vertical depth");
  }
  const detourSteps = depth - directDistance;
  if (detourSteps % 2 !== 0) {
    throw new RangeError("Staircase depth and horizontal distance must have matching parity");
  }

  const xDirection = Math.sign(to.x - from.x);
  const zDirection = Math.sign(to.z - from.z);
  const directSteps: Array<Readonly<{ x: number; z: number }>> = [];
  let completedX = 0;
  let completedZ = 0;
  for (let index = 0; index < directDistance; index += 1) {
    const advanceX =
      completedX < xDistance &&
      (completedZ >= zDistance ||
        completedX / Math.max(1, xDistance) <= completedZ / Math.max(1, zDistance));
    if (advanceX) {
      directSteps.push({ x: xDirection, z: 0 });
      completedX += 1;
    } else {
      directSteps.push({ x: 0, z: zDirection });
      completedZ += 1;
    }
  }

  const route: Array<Readonly<{ x: number; z: number }>> = [];
  if (detourSteps >= 4) {
    const halfPerimeter = detourSteps / 2;
    const firstLength = Math.floor(halfPerimeter / 2);
    const secondLength = halfPerimeter - firstLength;
    const dominantAxis = zDistance >= xDistance ? "z" : "x";
    const firstDirection =
      dominantAxis === "z"
        ? { x: xDirection === 0 ? 1 : -xDirection, z: 0 }
        : { x: 0, z: zDirection === 0 ? 1 : -zDirection };
    const secondDirection =
      dominantAxis === "z"
        ? { x: 0, z: zDirection === 0 ? 1 : -zDirection }
        : { x: xDirection === 0 ? 1 : -xDirection, z: 0 };
    appendHorizontalSteps(route, firstDirection, firstLength);
    appendHorizontalSteps(route, secondDirection, secondLength);
    appendHorizontalSteps(route, opposite(firstDirection), firstLength);
    appendHorizontalSteps(route, opposite(secondDirection), secondLength);
  } else if (detourSteps === 2) {
    const firstDirect = directSteps.shift();
    if (firstDirect === undefined) {
      route.push({ x: 1, z: 0 }, { x: -1, z: 0 });
    } else {
      const side = firstDirect.x === 0 ? { x: 1, z: 0 } : { x: 0, z: 1 };
      route.push(side, firstDirect, opposite(side));
    }
  }
  route.push(...directSteps);

  let x = from.x;
  let z = from.z;
  let y = from.y;
  const steps: BeatGameBlockPosition[] = [];
  for (const direction of route) {
    x += direction.x;
    z += direction.z;
    y -= 1;
    steps.push({ x, y, z, dimension: from.dimension });
  }
  return steps;
}

function adjustedStaircaseDestination(
  from: BeatGameBlockPosition,
  requested: BeatGameBlockPosition,
): BeatGameBlockPosition {
  const depth = from.y - requested.y;
  if (depth < 1 || from.dimension !== requested.dimension) {
    return requested;
  }
  let x = requested.x;
  let z = requested.z;
  let xDistance = Math.abs(x - from.x);
  let zDistance = Math.abs(z - from.z);
  let directDistance = xDistance + zDistance;
  let excess = Math.max(0, directDistance - depth);
  if (excess > 0) {
    const xAdjustment = Math.min(xDistance, excess);
    x += Math.sign(from.x - x) * xAdjustment;
    xDistance -= xAdjustment;
    excess -= xAdjustment;
    const zAdjustment = Math.min(zDistance, excess);
    z += Math.sign(from.z - z) * zAdjustment;
    zDistance -= zAdjustment;
  }
  directDistance = xDistance + zDistance;
  if ((depth - directDistance) % 2 !== 0) {
    if (xDistance > 0) {
      x += Math.sign(from.x - x);
    } else if (zDistance > 0) {
      z += Math.sign(from.z - z);
    } else {
      x += 1;
    }
  }
  return { ...requested, x, z };
}

function appendHorizontalSteps(
  route: Array<Readonly<{ x: number; z: number }>>,
  direction: Readonly<{ x: number; z: number }>,
  count: number,
): void {
  for (let index = 0; index < count; index += 1) {
    route.push(direction);
  }
}

function opposite(
  direction: Readonly<{ x: number; z: number }>,
): Readonly<{ x: number; z: number }> {
  return { x: -direction.x, z: -direction.z };
}

function ensureStaircaseSupport(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  const support = below(target);
  return Effect.gen(function* () {
    const observedSupport = yield* queryExactBlock(driver, support);
    if (observedSupport === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "Could not observe the next staircase support block"),
      );
    }
    if (
      observedSupport.solid === true &&
      !observedSupport.replaceable &&
      !isGravityAffectedBlock(observedSupport.blockId)
    ) {
      return false;
    }

    if (!observedSupport.replaceable) {
      if (!observedSupport.diggable) {
        return yield* Effect.fail(
          behaviorError(
            driver,
            "The next staircase support cannot be cleared or stood on",
            "unsupported_opening",
          ),
        );
      }
      yield* driver.act({ type: "dig-block", position: support });
      const clearedSupport = yield* waitForExactBlock(
        driver,
        support,
        { replaceable: true },
        10,
        50,
      );
      if (clearedSupport === undefined) {
        return yield* Effect.fail(
          behaviorError(driver, "Could not clear an unstable staircase support block"),
        );
      }
    }

    const observation = yield* driver.observe;
    const material = staircaseSupportMaterial(observation);
    if (material === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "No solid block is available to bridge the staircase"),
      );
    }

    const lowerAnchor = below(support);
    const observedLowerAnchor = yield* queryExactBlock(driver, lowerAnchor);
    if (observedLowerAnchor === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "Could not observe the lower staircase support anchor"),
      );
    }
    if (
      observedLowerAnchor.solid !== true ||
      observedLowerAnchor.replaceable ||
      isGravityAffectedBlock(observedLowerAnchor.blockId)
    ) {
      return yield* Effect.fail(
        behaviorError(
          driver,
          "The next staircase support has no stable reachable lower anchor",
          "unsupported_opening",
        ),
      );
    }

    yield* placeStaircaseBlock(driver, material, lowerAnchor, "up", support);
    const placed = yield* waitForExactBlock(driver, support, { replaceable: false }, 10, 50);
    if (placed === undefined || isGravityAffectedBlock(placed.blockId)) {
      return yield* Effect.fail(
        behaviorError(driver, "Could not place a safe staircase support block"),
      );
    }
    return true;
  });
}

function digStaircaseBlockIfNeeded(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const block = yield* queryExactBlock(driver, position);
    if (block === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "Could not observe a staircase block before digging"),
      );
    }
    if (block.replaceable) {
      return;
    }
    yield* driver.act({ type: "dig-block", position });
  });
}

function refuseFloodedStaircaseStep(
  driver: BeatGameDriver,
  step: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.sleep(100).pipe(
    Effect.andThen(
      Effect.all([
        queryExactBlock(driver, step),
        queryExactBlock(driver, { ...step, y: step.y + 1 }),
        queryExactBlock(driver, { ...step, y: step.y + 2 }),
      ]),
    ),
    Effect.flatMap((blocks) => {
      const fluid = blocks.find(
        (block) => block !== undefined && isAvoidedFluidBlockId(block.blockId),
      );
      return fluid === undefined
        ? Effect.void
        : Effect.fail(
            behaviorError(
              driver,
              `Refused to enter a staircase flooded by ${fluid.blockId} at ${positionKey(
                fluid.position,
              )}`,
              "fluid_exposed",
            ),
          );
    }),
  );
}

function isOpenStaircaseStep(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.all([queryExactBlock(driver, target), queryExactBlock(driver, below(target))]).pipe(
    Effect.map(
      ([targetBlock, supportBlock]) =>
        targetBlock?.replaceable === true && supportBlock?.replaceable === true,
    ),
  );
}

function walkStaircaseStep(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  path: Partial<BeatGamePathPolicy> | undefined,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const traversal = yield* driver
      .pathfind(staircaseFeetCenter(target), 0.5, staircaseStepPathPolicy(path))
      .pipe(Effect.result);
    if (traversal._tag === "Failure") {
      yield* walkPreparedStaircaseStepDirectly(driver, target);
    }
    yield* Effect.sleep(150);
    yield* settleOnStaircaseTread(driver, target);
    yield* driver.act({ type: "reset-movement" });
  }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
}

function walkPreparedStaircaseStepDirectly(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const current = observation.player.position;
    const currentBlock = {
      x: Math.floor(current.x),
      y: Math.floor(current.y + 0.01),
      z: Math.floor(current.z),
    };
    const horizontalDistance =
      Math.abs(target.x - currentBlock.x) + Math.abs(target.z - currentBlock.z);
    if (
      current.dimension !== target.dimension ||
      currentBlock.y !== target.y + 1 ||
      horizontalDistance !== 1
    ) {
      return yield* Effect.fail(
        behaviorError(driver, "Pathfinding failed outside a directly traversable staircase step"),
      );
    }

    const destination = staircaseFeetCenter(target);
    const yaw = rotationToward({ ...current, y: 0 }, { ...destination, y: 0 }).yaw;
    yield* driver.act({ type: "look", yaw, pitch: 0 });
    yield* driver.act({
      type: "set-movement",
      forward: true,
      sprint: false,
      sneak: false,
    });
    yield* settleOnStaircaseTread(driver, target);
  }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore)));
}

function settleOnStaircaseTread(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return waitForStaircaseLanding(driver, target, STAIRCASE_INITIAL_LANDING_ATTEMPTS, 50).pipe(
    Effect.result,
    Effect.flatMap((landing) =>
      landing._tag === "Success"
        ? Effect.void
        : clearBlockedStaircaseTreadFromAbove(driver, target).pipe(
            Effect.flatMap((recovered) =>
              recovered ? Effect.void : waitForStaircaseLanding(driver, target, 40, 50),
            ),
          ),
    ),
  );
}

/**
 * Sand and gravel above a freshly carved staircase can fall into its tread
 * while the player moves forward. Soulfire may then finish the short path on
 * top of that block instead of one level lower. Re-open the already-supported
 * tread until the player drops onto it.
 */
function clearBlockedStaircaseTreadFromAbove(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<boolean, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (let attempt = 0; attempt < STAIRCASE_COLLAPSE_RECOVERY_ATTEMPTS; attempt += 1) {
      const observation = yield* driver.observe;
      const position = observation.player.position;
      if (isPlayerOnStaircaseTread(position, target)) {
        return true;
      }
      const playerBlock = {
        x: Math.floor(position.x),
        y: Math.floor(position.y + 0.01),
        z: Math.floor(position.z),
      };
      if (
        position.dimension !== target.dimension ||
        playerBlock.x !== target.x ||
        playerBlock.y !== target.y + 1 ||
        playerBlock.z !== target.z
      ) {
        return false;
      }
      const tread = yield* queryExactBlock(driver, target);
      if (
        tread === undefined ||
        isAvoidedFluidBlockId(tread.blockId) ||
        (!tread.replaceable && !tread.diggable)
      ) {
        return false;
      }
      if (!tread.replaceable) {
        yield* driver.act({ type: "dig-block", position: target });
      }
      yield* Effect.sleep(100);
    }
    return false;
  });
}

function isAvoidedFluidBlockId(blockId: string): boolean {
  return AVOIDED_FLUID_BLOCK_IDS.includes(blockId as (typeof AVOIDED_FLUID_BLOCK_IDS)[number]);
}

function waitForStaircaseLanding(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
  attempts: number,
  delayMs: number,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const position = observation.player.position;
      if (isPlayerOnStaircaseTread(position, target)) {
        return Effect.void;
      }
      if (position.dimension !== target.dimension || position.y < target.y - 0.5) {
        return Effect.fail(
          behaviorError(driver, "Did not land safely on the next staircase tread"),
        );
      }
      if (attempts <= 1) {
        return Effect.fail(behaviorError(driver, "Did not settle on the next staircase tread"));
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForStaircaseLanding(driver, target, attempts - 1, delayMs)),
      );
    }),
  );
}

function isPlayerOnStaircaseTread(
  position: BeatGamePosition,
  target: BeatGameBlockPosition,
): boolean {
  return (
    position.dimension === target.dimension &&
    Math.floor(position.x) === target.x &&
    Math.floor(position.z) === target.z &&
    position.y >= target.y - 0.15 &&
    position.y <= target.y + 0.75
  );
}

function waitForVerticalSettlement(
  driver: BeatGameDriver,
  previousY?: number,
  stableObservations = 0,
  attempts = 40,
): Effect.Effect<BeatGameObservation, BeatGameDriverError> {
  return driver.observe.pipe(
    Effect.flatMap((observation) => {
      const position = observation.player.position;
      const yChanged = previousY !== undefined && Math.abs(position.y - previousY) > 0.05;
      const nextStableObservations = yChanged ? 0 : stableObservations + 1;
      if (nextStableObservations >= 2) {
        return Effect.succeed(observation);
      }
      if (attempts <= 1) {
        return Effect.fail(behaviorError(driver, "Did not settle before excavating the staircase"));
      }
      return Effect.sleep(50).pipe(
        Effect.andThen(
          waitForVerticalSettlement(driver, position.y, nextStableObservations, attempts - 1),
        ),
      );
    }),
  );
}

function placeStaircaseBlock(
  driver: BeatGameDriver,
  material: string,
  against: BeatGameBlockPosition,
  face: BeatGameBlockFace,
  expected: BeatGameBlockPosition,
  attemptsRemaining = 3,
): Effect.Effect<void, BeatGameDriverError> {
  return driver
    .act({
      type: "select-item",
      selector: { itemIds: [material] },
    })
    .pipe(
      Effect.andThen(
        driver.act({
          type: "place-block",
          against,
          face,
          hand: "main",
        }),
      ),
      Effect.catch((cause) =>
        waitForExactBlock(driver, expected, { replaceable: false }, 3, 50).pipe(
          Effect.flatMap((placed) => {
            if (placed !== undefined && !isGravityAffectedBlock(placed.blockId)) {
              return Effect.void;
            }
            if (attemptsRemaining <= 1) {
              return Effect.fail(cause);
            }
            const clearUnstableBlock =
              placed !== undefined && isGravityAffectedBlock(placed.blockId)
                ? driver
                    .act({
                      type: "dig-block",
                      position: expected,
                    })
                    .pipe(
                      Effect.andThen(Effect.sleep(150)),
                      Effect.andThen(
                        waitForExactBlock(driver, expected, { replaceable: true }, 10, 50),
                      ),
                      Effect.flatMap((cleared) =>
                        cleared === undefined
                          ? Effect.fail(
                              behaviorError(
                                driver,
                                "Could not clear an unstable staircase support block",
                              ),
                            )
                          : Effect.void,
                      ),
                    )
                : Effect.void;
            return clearUnstableBlock.pipe(
              Effect.andThen(Effect.sleep(100)),
              Effect.andThen(
                placeStaircaseBlock(
                  driver,
                  material,
                  against,
                  face,
                  expected,
                  attemptsRemaining - 1,
                ),
              ),
            );
          }),
        ),
      ),
    );
}

function staircaseSupportMaterial(observation: BeatGameObservation): string | undefined {
  return [
    "minecraft:cobblestone",
    "minecraft:cobbled_deepslate",
    "minecraft:dirt",
    "minecraft:netherrack",
    "minecraft:oak_log",
    "minecraft:spruce_log",
    "minecraft:birch_log",
    "minecraft:jungle_log",
    "minecraft:acacia_log",
    "minecraft:dark_oak_log",
    "minecraft:mangrove_log",
    "minecraft:cherry_log",
    "minecraft:pale_oak_log",
    "minecraft:crimson_stem",
    "minecraft:warped_stem",
  ].find((itemId) => (observation.inventory.counts[itemId] ?? 0) > 0);
}

function isGravityAffectedBlock(blockId: string): boolean {
  return (
    blockId === "minecraft:sand" ||
    blockId === "minecraft:red_sand" ||
    blockId === "minecraft:gravel" ||
    blockId === "minecraft:dragon_egg" ||
    blockId.endsWith("_concrete_powder") ||
    blockId.endsWith("_anvil")
  );
}

function queryExactBlock(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver
    .queryBlocks(exactBlockQuery(position, {}))
    .pipe(Effect.map((blocks) => blocks.find((block) => samePosition(block.position, position))));
}

function exactBlockQuery(
  position: BeatGameBlockPosition,
  selector: BeatGameQueryBlocks["selector"],
): BeatGameQueryBlocks {
  return {
    center: {
      x: position.x + 0.5,
      y: position.y + 0.5,
      z: position.z + 0.5,
      dimension: position.dimension,
    },
    radius: 0.5,
    selector,
    maximumResults: 1,
  };
}

function horizontalFace(
  from: BeatGameBlockPosition,
  to: BeatGameBlockPosition,
): "north" | "south" | "east" | "west" {
  const x = to.x - from.x;
  const z = to.z - from.z;
  if (x === 1 && z === 0) {
    return "east";
  }
  if (x === -1 && z === 0) {
    return "west";
  }
  if (x === 0 && z === 1) {
    return "south";
  }
  if (x === 0 && z === -1) {
    return "north";
  }
  throw new RangeError("Staircase steps must be horizontally adjacent");
}

function isEndDimension(dimension: string): boolean {
  return dimension === "minecraft:the_end" || dimension.endsWith(":the_end");
}

function isNetherDimension(dimension: string): boolean {
  return dimension === "minecraft:the_nether" || dimension.endsWith(":the_nether");
}

function runControlled(
  driver: BeatGameDriver,
  task: BeatGameTask,
  path: Partial<BeatGamePathPolicy> | undefined,
): Effect.Effect<void, BeatGameDriverError> {
  return runControlledResult(driver, task, path).pipe(Effect.asVoid);
}

function runControlledResult(
  driver: BeatGameDriver,
  task: BeatGameTask,
  path: Partial<BeatGamePathPolicy> | undefined,
): Effect.Effect<unknown, BeatGameDriverError> {
  return driver.withControl(
    driver
      .runTask(task, mergePathPolicy(path))
      .pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

function throwSelectedItem(
  driver: BeatGameDriver,
  itemIds: readonly string[],
  options: ThrowItemOptions,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      const observation = yield* driver.observe;
      yield* driver.act({
        type: "select-item",
        selector: { itemIds },
      });
      yield* lookForThrow(driver, observation.player.position, options);
      yield* driver.act({ type: "use-item", hand: "main" });
    }),
  );
}

function lookForThrow(
  driver: BeatGameDriver,
  origin: BeatGamePosition,
  options: ThrowItemOptions,
): Effect.Effect<unknown, BeatGameDriverError> {
  const rotation =
    options.target === undefined
      ? {
          yaw: options.yaw ?? 0,
          pitch: options.pitch ?? -20,
        }
      : rotationToward(origin, options.target);
  return driver.act({
    type: "look",
    yaw: options.yaw ?? rotation.yaw,
    pitch: options.pitch ?? rotation.pitch,
  });
}

function ignitePortal(
  driver: BeatGameDriver,
  frame: PortalFrame,
  acquireControl = true,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  const interior = frame.interior[0];
  if (interior === undefined) {
    return Effect.void;
  }
  const base =
    frame.blocks.find(
      (block) => block.y === frame.origin.y && (block.x === interior.x || block.z === interior.z),
    ) ?? frame.origin;
  const program = Effect.gen(function* () {
    yield* driver.act({
      type: "select-item",
      selector: {
        itemIds: ["minecraft:flint_and_steel", "minecraft:fire_charge"],
      },
    });
    yield* driver.pathfind(base, 3, mergePathPolicy(path));
    yield* driver.act({
      type: "interact-block",
      position: base,
      face: "up",
      hand: "main",
    });
    yield* Effect.sleep(250);
    const portals = yield* driver.queryBlocks({
      center: interior,
      radius: 3,
      selector: { blockIds: ["minecraft:nether_portal"] },
      maximumResults: frame.interior.length,
    });
    if (!portals.some(({ position }) => samePosition(position, interior))) {
      return yield* Effect.fail(
        behaviorError(driver, "The Nether portal did not activate after ignition"),
      );
    }
  });
  return acquireControl ? driver.withControl(program) : program;
}

function clearPortalInterior(
  driver: BeatGameDriver,
  frame: PortalFrame,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.forEach(
    [...frame.interior].sort((left, right) => right.y - left.y),
    (position) =>
      driver
        .queryBlocks({
          center: {
            ...position,
            x: position.x + 0.5,
            y: position.y + 0.5,
            z: position.z + 0.5,
          },
          radius: 0.25,
          selector: {},
          maximumResults: 1,
        })
        .pipe(
          Effect.flatMap((blocks) => {
            const block = blocks.find((candidate) => samePosition(candidate.position, position));
            if (
              block === undefined ||
              block.replaceable ||
              block.blockId === "minecraft:nether_portal"
            ) {
              return Effect.void;
            }
            if (!block.diggable) {
              return Effect.fail(
                behaviorError(
                  driver,
                  `Portal interior is not diggable at ${positionKey(position)}`,
                ),
              );
            }
            return Effect.gen(function* () {
              yield* driver.pathfind(position, 3, mergePathPolicy(path));
              const observation = yield* driver.observe;
              const tool = preferredPortalDigTool(observation);
              if (tool !== undefined) {
                yield* driver.act({
                  type: "select-item",
                  selector: { itemIds: [tool] },
                });
              }
              yield* driver.act({ type: "dig-block", position });
            });
          }),
        ),
    { discard: true },
  );
}

function castNetherPortalFromLavaPool(
  driver: BeatGameDriver,
  frame: PortalFrame,
  options: CastNetherPortalOptions,
): Effect.Effect<PortalFrame, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const existingObsidian = yield* driver.queryBlocks({
      center: frame.origin,
      radius: 8,
      selector: { blockIds: ["minecraft:obsidian"] },
      maximumResults: 64,
    });
    const existingKeys = new Set(existingObsidian.map(({ position }) => positionKey(position)));
    const targets = [...frame.blocks]
      .filter((position) => !existingKeys.has(positionKey(position)))
      .sort((left, right) => left.y - right.y || left.x - right.x || left.z - right.z);
    if (targets.length === 0) {
      if (options.ignite ?? true) {
        yield* ignitePortal(driver, frame, true, options.path);
      }
      return frame;
    }
    const castingStands = targets.map((target) => portalCastingStand(frame, target));
    yield* clearPortalInterior(driver, frame, options.path);
    yield* clearPortalCastingCells(
      driver,
      uniquePositions([
        ...targets.flatMap((target) => [target, castingWaterPosition(frame, target)]),
        ...castingStands.flatMap((stand) => [stand, { ...stand, y: stand.y + 1 }]),
      ]),
      options.path,
    );
    const filledLavaBuckets = observation.inventory.counts["minecraft:lava_bucket"] ?? 0;
    const requiredLavaSources = Math.max(0, targets.length - Math.min(1, filledLavaBuckets));
    const lavaSources = yield* driver.queryBlocks({
      center: observation.player.position,
      radius: defaultBeatGameStrategy.blockSearchRadius,
      selector: {
        blockIds: ["minecraft:lava"],
        properties: { level: "0" },
      },
      maximumResults: Math.max(1, requiredLavaSources),
    });
    if (lavaSources.length < requiredLavaSources) {
      return yield* Effect.fail(
        behaviorError(
          driver,
          `Portal casting needs ${requiredLavaSources} observable lava sources`,
        ),
      );
    }
    const frameKeys = new Set(frame.blocks.map(positionKey));
    const finalCastingStand = castingStands.at(-1);
    if (finalCastingStand === undefined) {
      return yield* Effect.fail(
        behaviorError(driver, "Portal casting has no stand for its remaining frame targets"),
      );
    }
    const finalCastingStandSupport = below(finalCastingStand);
    const temporarySupports: BeatGameBlockPosition[] = [];
    yield* driver.withControl(
      Effect.gen(function* () {
        for (const [index, target] of targets.entries()) {
          const current = yield* driver.observe;
          if ((current.inventory.counts["minecraft:lava_bucket"] ?? 0) === 0) {
            yield* collectPortalCastingLava(driver, targets.length - index, options.path);
          }
          const castingStand = portalCastingStand(frame, target);
          const targetSupport = below(target);
          const water = castingWaterPosition(frame, target);
          temporarySupports.push(
            ...(yield* buildTemporaryPortalCastingSupports(
              driver,
              frame,
              uniquePositions([
                ...(frameKeys.has(positionKey(targetSupport)) ? [] : [targetSupport]),
                below(water),
                below(castingStand),
              ]),
              options.path,
            )),
          );
          yield* reachPortalCastingStand(
            driver,
            castingStand,
            uniquePositions([...frame.blocks, targetSupport, below(water), below(castingStand)]),
            options.path,
          );
          // A placement-enabled route may scaffold through a cell which was
          // clear when the casting pass started. Revalidate the liquid cells
          // after movement so a path block cannot turn the bucket cast into a
          // side placement or a silent miss.
          yield* clearPortalCastingCells(driver, [target, water], options.path);
          yield* Effect.uninterruptible(
            Effect.gen(function* () {
              yield* driver.act({
                type: "select-item",
                selector: { itemIds: ["minecraft:lava_bucket"] },
              });
              yield* placeBucketOnTopOf(
                driver,
                below(target),
                ["minecraft:lava_bucket"],
                options.path,
              );
              yield* waitForExactBlockState(
                driver,
                target,
                (candidate) => candidate?.blockId === "minecraft:lava",
                10,
                50,
              ).pipe(
                Effect.flatMap((candidate) =>
                  candidate?.blockId === "minecraft:lava"
                    ? Effect.void
                    : Effect.fail(
                        behaviorError(driver, `Portal casting lava missed ${positionKey(target)}`),
                      ),
                ),
              );
              yield* driver
                .act({
                  type: "select-item",
                  selector: { itemIds: ["minecraft:water_bucket"] },
                })
                .pipe(
                  Effect.catchTag("BeatGameDriverError", (cause) =>
                    cause.code === "not_found"
                      ? recoverPortalCastingWaterBucket(driver, water, options.path).pipe(
                          Effect.andThen(
                            driver.act({
                              type: "select-item",
                              selector: { itemIds: ["minecraft:water_bucket"] },
                            }),
                          ),
                        )
                      : Effect.fail(cause),
                  ),
                );
              yield* placeBucketOnTopOf(
                driver,
                below(water),
                ["minecraft:water_bucket"],
                options.path,
              );
            }),
          );
          yield* waitForExactBlockState(
            driver,
            target,
            (candidate) => candidate?.blockId === "minecraft:obsidian",
            20,
            100,
          ).pipe(
            Effect.flatMap((candidate) =>
              candidate?.blockId === "minecraft:obsidian"
                ? Effect.void
                : Effect.fail(
                    behaviorError(
                      driver,
                      `cast portal block did not produce minecraft:obsidian at ${positionKey(
                        target,
                      )}`,
                    ),
                  ),
            ),
          );
          yield* recoverPortalCastingWaterBucket(driver, water, options.path);
        }
        for (const support of temporarySupports) {
          if (samePosition(support, finalCastingStandSupport)) {
            continue;
          }
          const reached = yield* driver
            .pathfind(support, 4, mergePathPolicy(options.path))
            .pipe(Effect.result);
          if (reached._tag === "Success") {
            // Temporary scaffold cleanup is not part of portal correctness.
            // Keep an unreachable support instead of failing a complete frame.
            yield* driver
              .act({
                type: "dig-block",
                position: support,
              })
              .pipe(Effect.ignore);
          }
        }
        if (options.ignite ?? true) {
          // A work-position route can bridge an otherwise unreachable ledge.
          // Remove any bridge block which landed inside the frame before
          // ignition.
          yield* clearPortalInterior(driver, frame, options.path);
          yield* ignitePortal(driver, frame, false, options.path);
        }
      }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
    );
    return frame;
  });
}

function reachPortalCastingStand(
  driver: BeatGameDriver,
  stand: BeatGameBlockPosition,
  protectedBlocks: readonly BeatGameBlockPosition[],
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  const mergedPath = mergePathPolicy(path);
  const strictPolicy = {
    ...mergedPath,
    allowMining: false,
    allowPlacing: false,
    avoidFluids: true,
    maxFallDistance: 1,
    protectedBlocks: uniquePositions([...(mergedPath.protectedBlocks ?? []), ...protectedBlocks]),
  } satisfies BeatGamePathPolicy;
  return driver.pathfind(stand, 0, strictPolicy).pipe(
    Effect.result,
    Effect.flatMap((strict) => {
      if (strict._tag === "Success") {
        return Effect.void;
      }
      return driver
        .pathfind(stand, 0, {
          ...strictPolicy,
          // Baritone handles this as a graph transition with a consumable
          // block. Soulfire's projected inventory gives ARA* the same option
          // without scripting another remote staircase.
          allowPlacing: true,
        })
        .pipe(
          Effect.result,
          Effect.flatMap((placing) =>
            placing._tag === "Success"
              ? Effect.void
              : driver.pathfind(stand, 0, {
                  ...strictPolicy,
                  // A one-block ascent can require clearing the source-column
                  // jump arc. Keep the portal and casting scaffold immutable
                  // while allowing ARA* to mine that local obstruction, as
                  // Baritone's MovementAscend does.
                  allowMining: true,
                  allowPlacing: true,
                }),
          ),
        );
    }),
  );
}

function collectPortalCastingLava(
  driver: BeatGameDriver,
  remainingTargets: number,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (let attempt = 0; attempt < PORTAL_CASTING_LAVA_SOURCE_ATTEMPTS; attempt += 1) {
      const observation = yield* driver.observe;
      const sources = yield* driver.queryBlocks({
        center: observation.player.position,
        radius: defaultBeatGameStrategy.blockSearchRadius,
        selector: {
          blockIds: ["minecraft:lava"],
          properties: { level: "0" },
        },
        maximumResults: Math.max(8, remainingTargets),
      });
      if (sources.length === 0) {
        return yield* Effect.fail(
          behaviorError(driver, "No live lava source remained for the next portal casting step"),
        );
      }
      const source = yield* approachLiquidSourceFromSide(driver, observation, sources, {
        path: mergePathPolicy(path),
        requireTargetableSource: true,
      });
      const collected = yield* collectApproachedPortalCastingLavaSource(driver, source).pipe(
        Effect.as(true),
        Effect.catchTag("BeatGameDriverError", (cause) =>
          cause.code === "source-changed" ? Effect.succeed(false) : Effect.fail(cause),
        ),
      );
      if (collected) {
        return;
      }
    }
    return yield* Effect.fail(
      behaviorError(
        driver,
        `Lava sources changed during ${PORTAL_CASTING_LAVA_SOURCE_ATTEMPTS} consecutive portal casting approaches`,
        "source-changed",
      ),
    );
  });
}

function collectApproachedPortalCastingLavaSource(
  driver: BeatGameDriver,
  source: BeatGameBlockObservation,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const obstruction = yield* portalCastingLavaSightline(driver, observation, source.position);
    if (obstruction !== undefined && !samePosition(obstruction.position, source.position)) {
      return yield* Effect.fail(
        behaviorError(
          driver,
          `Could not safely collect the portal casting lava source through ${
            obstruction.blockId
          } at ${positionKey(obstruction.position)}`,
        ),
      );
    }
    const sourceCenter = blockCenter(source.position);
    const liveSource = yield* driver.queryBlocks({
      center: sourceCenter,
      radius: 0.25,
      selector: {
        blockIds: ["minecraft:lava"],
        properties: { level: "0" },
      },
      maximumResults: 1,
    });
    if (!liveSource.some(({ position }) => samePosition(position, source.position))) {
      return yield* Effect.fail(
        behaviorError(
          driver,
          `The portal casting lava source at ${positionKey(
            source.position,
          )} changed while the bot approached it`,
          "source-changed",
        ),
      );
    }
    yield* driver.act({
      type: "select-item",
      selector: { itemIds: ["minecraft:bucket"] },
    });
    yield* useBucketToward(driver, source.position).pipe(
      Effect.mapError(
        (cause) =>
          new BeatGameDriverError({
            operation: "collect-portal-casting-lava",
            ...(cause.code === undefined ? {} : { code: cause.code }),
            retryable: true,
            message: `Could not collect the portal casting lava source at ${positionKey(
              source.position,
            )}: ${cause.message}`,
            cause,
          }),
      ),
    );
    for (let poll = 0; poll < PORTAL_CASTING_LAVA_COLLECTION_POLLS; poll += 1) {
      const current = yield* driver.observe;
      if ((current.inventory.counts["minecraft:lava_bucket"] ?? 0) > 0) {
        return;
      }
      yield* Effect.sleep(50);
    }
    return yield* Effect.fail(
      behaviorError(driver, `The lava source at ${positionKey(source.position)} was not collected`),
    );
  });
}

function portalCastingLavaSightline(
  driver: BeatGameDriver,
  observation: BeatGameObservation,
  source: BeatGameBlockPosition,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return Effect.gen(function* () {
    const sourceCenter = blockCenter(source);
    const eyePosition = {
      ...observation.player.position,
      y: observation.player.position.y + 1.62,
    };
    const direction = {
      x: sourceCenter.x - eyePosition.x,
      y: sourceCenter.y - eyePosition.y,
      z: sourceCenter.z - eyePosition.z,
    };
    const distance = Math.sqrt(distanceSquared(eyePosition, sourceCenter));
    const rotation = rotationToward(eyePosition, sourceCenter);
    yield* driver.act({
      type: "look",
      yaw: rotation.yaw,
      pitch: rotation.pitch,
    });
    yield* waitForRotation(driver, rotation.yaw, rotation.pitch, 40, 50);
    return (yield* driver.raycast({
      direction,
      maximumDistance: distance + 0.05,
      includeFluids: false,
    })).block;
  });
}

function isPortalCastingPlayerStabilityBlock(
  player: BeatGamePosition,
  block: BeatGameBlockPosition,
): boolean {
  if (player.dimension !== block.dimension) {
    return false;
  }
  return (
    block.x === Math.floor(player.x) &&
    block.z === Math.floor(player.z) &&
    block.y >= Math.floor(player.y) - 1 &&
    block.y <= Math.floor(player.y) + 1
  );
}

function recoverPortalCastingWaterBucket(
  driver: BeatGameDriver,
  expectedWater: BeatGameBlockPosition,
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    let lastSource: BeatGameBlockObservation | undefined;
    let lastFailure: BeatGameDriverError | undefined;
    for (let attempt = 0; attempt < PORTAL_CASTING_WATER_RECOVERY_ATTEMPTS; attempt += 1) {
      const current = yield* driver.observe;
      if ((current.inventory.counts["minecraft:water_bucket"] ?? 0) > 0) {
        return;
      }
      const nearby = yield* driver.queryBlocks({
        center: blockCenter(expectedWater),
        radius: 3,
        selector: {},
        maximumResults: 256,
      });
      const source = [...nearby]
        .filter(
          (block) =>
            (block.blockId === "minecraft:water" && block.properties.level === "0") ||
            block.properties.waterlogged === "true",
        )
        .sort(
          (left, right) =>
            distanceSquared(left.position, expectedWater) -
            distanceSquared(right.position, expectedWater),
        )[0];
      if (source === undefined) {
        yield* Effect.sleep(100);
        continue;
      }
      lastSource = source;
      const approached = yield* approachLiquidSourceFromSide(driver, current, [source], {
        path: {
          ...mergePathPolicy(path),
          allowPlacing: false,
          avoidFluids: true,
          maxFallDistance: 1,
        },
        requireTargetableSource: true,
      }).pipe(Effect.result);
      if (approached._tag === "Failure") {
        lastFailure = approached.failure;
        yield* Effect.sleep(100);
        continue;
      }
      const pickup = yield* driver
        .act({
          type: "select-item",
          selector: { itemIds: ["minecraft:bucket"] },
        })
        .pipe(Effect.andThen(useBucketToward(driver, source.position)), Effect.result);
      if (pickup._tag === "Failure") {
        lastFailure = pickup.failure;
        yield* Effect.sleep(100);
        continue;
      }
      for (let poll = 0; poll < PORTAL_CASTING_LAVA_COLLECTION_POLLS; poll += 1) {
        const observed = yield* driver.observe;
        if ((observed.inventory.counts["minecraft:water_bucket"] ?? 0) > 0) {
          return;
        }
        yield* Effect.sleep(50);
      }
    }
    return yield* Effect.fail(
      behaviorError(
        driver,
        lastSource === undefined
          ? "The portal casting water bucket is missing and no nearby source remains"
          : `The water source at ${positionKey(lastSource.position)} was not collected${
              lastFailure === undefined ? "" : `: ${lastFailure.message}`
            }`,
      ),
    );
  });
}

function buildTemporaryPortalCastingSupports(
  driver: BeatGameDriver,
  frame: PortalFrame,
  candidates: readonly BeatGameBlockPosition[],
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<readonly BeatGameBlockPosition[], BeatGameDriverError> {
  return Effect.gen(function* () {
    const missing: BeatGameBlockPosition[] = [];
    for (const support of candidates) {
      const observedSupport = yield* observeExactBlock(driver, support);
      if (!isReliablePortalCastingSupport(observedSupport)) {
        missing.push(support);
      }
    }
    const scaffold = yield* ensurePortalCastingSupports(driver, frame, missing, path);
    return uniquePositions([...missing, ...scaffold]);
  });
}

function ensurePortalCastingSupports(
  driver: BeatGameDriver,
  frame: PortalFrame,
  supports: readonly BeatGameBlockPosition[],
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<readonly BeatGameBlockPosition[], BeatGameDriverError> {
  return Effect.gen(function* () {
    let missing = [...supports];
    const scaffold: BeatGameBlockPosition[] = [];
    for (let attempt = 0; attempt < 3 && missing.length > 0; attempt += 1) {
      const placements =
        attempt === 0
          ? missing
          : uniquePositions(
              (yield* Effect.forEach(
                missing,
                (support) => planPortalCastingSupportScaffold(driver, frame, support),
                { concurrency: 1 },
              )).flat(),
            );
      if (attempt > 0) {
        scaffold.push(...placements);
      }
      yield* leavePortalCastingScaffoldCells(driver, placements, path);
      yield* buildStructure(driver, {
        origin: frame.origin,
        blocks: placements.map((position) => ({
          offset: {
            x: position.x - frame.origin.x,
            y: position.y - frame.origin.y,
            z: position.z - frame.origin.z,
          },
          blockId: "minecraft:cobblestone",
        })),
        ...(path === undefined ? {} : { path }),
      });
      const stillMissing: BeatGameBlockPosition[] = [];
      for (const support of missing) {
        const observedSupport = yield* observeExactBlock(driver, support);
        if (!isReliablePortalCastingSupport(observedSupport)) {
          stillMissing.push(support);
        }
      }
      missing = stillMissing;
    }
    const firstMissing = missing[0];
    if (firstMissing !== undefined) {
      return yield* Effect.fail(
        behaviorError(driver, `Portal casting support is missing at ${positionKey(firstMissing)}`),
      );
    }
    return uniquePositions(scaffold);
  });
}

function planPortalCastingSupportScaffold(
  driver: BeatGameDriver,
  frame: PortalFrame,
  target: BeatGameBlockPosition,
): Effect.Effect<readonly BeatGameBlockPosition[], BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: blockCenter(target),
      radius: PORTAL_CASTING_SUPPORT_SCAFFOLD_RADIUS,
      selector: {},
      maximumResults: PORTAL_CASTING_SUPPORT_SCAFFOLD_MAXIMUM_RESULTS,
    })
    .pipe(
      Effect.map((blocks) => {
        const observed = new Map(blocks.map((block) => [positionKey(block.position), block]));
        const forbidden = new Set([...frame.blocks, ...frame.interior].map(positionKey));
        forbidden.delete(positionKey(target));
        const queued = new Set([positionKey(target)]);
        const parents = new Map<string, BeatGameBlockPosition | undefined>([
          [positionKey(target), undefined],
        ]);
        const queue: BeatGameBlockPosition[] = [target];
        for (let index = 0; index < queue.length; index += 1) {
          const current = queue[index]!;
          for (const adjacent of portalCastingScaffoldNeighbors(current)) {
            const block = observed.get(positionKey(adjacent));
            if (isReliablePortalCastingSupport(block) && !isAvoidedFluidBlockId(block.blockId)) {
              const scaffold: BeatGameBlockPosition[] = [];
              let step: BeatGameBlockPosition | undefined = current;
              while (step !== undefined) {
                scaffold.push(step);
                step = parents.get(positionKey(step));
              }
              return scaffold;
            }
            const key = positionKey(adjacent);
            if (
              queued.has(key) ||
              forbidden.has(key) ||
              !portalCastingScaffoldPositionWithinBounds(target, adjacent) ||
              block?.replaceable !== true ||
              isAvoidedFluidBlockId(block.blockId)
            ) {
              continue;
            }
            queued.add(key);
            parents.set(key, current);
            queue.push(adjacent);
          }
        }
        return [target];
      }),
    );
}

function portalCastingScaffoldNeighbors(
  position: BeatGameBlockPosition,
): readonly BeatGameBlockPosition[] {
  return [
    { ...position, y: position.y - 1 },
    { ...position, x: position.x - 1 },
    { ...position, x: position.x + 1 },
    { ...position, z: position.z - 1 },
    { ...position, z: position.z + 1 },
    { ...position, y: position.y + 1 },
  ];
}

function portalCastingScaffoldPositionWithinBounds(
  origin: BeatGameBlockPosition,
  candidate: BeatGameBlockPosition,
): boolean {
  const radius = Math.floor(PORTAL_CASTING_SUPPORT_SCAFFOLD_RADIUS);
  return (
    candidate.dimension === origin.dimension &&
    Math.abs(candidate.x - origin.x) <= radius &&
    Math.abs(candidate.y - origin.y) <= radius &&
    Math.abs(candidate.z - origin.z) <= radius
  );
}

function leavePortalCastingScaffoldCells(
  driver: BeatGameDriver,
  scaffold: readonly BeatGameBlockPosition[],
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const playerFeet = {
      x: Math.floor(observation.player.position.x),
      y: Math.floor(observation.player.position.y),
      z: Math.floor(observation.player.position.z),
      dimension: observation.player.position.dimension,
    } satisfies BeatGameBlockPosition;
    const playerHead = { ...playerFeet, y: playerFeet.y + 1 };
    if (
      !scaffold.some(
        (position) => samePosition(position, playerFeet) || samePosition(position, playerHead),
      )
    ) {
      return;
    }
    const center = scaffold[0];
    if (center === undefined) {
      return;
    }
    const blocks = yield* driver.queryBlocks({
      center: blockCenter(center),
      radius: PORTAL_CASTING_SUPPORT_SCAFFOLD_RADIUS,
      selector: {},
      maximumResults: PORTAL_CASTING_SUPPORT_SCAFFOLD_MAXIMUM_RESULTS,
    });
    const observed = new Map(blocks.map((block) => [positionKey(block.position), block]));
    const scaffoldKeys = new Set(scaffold.map(positionKey));
    const candidates = blocks
      .filter((feet) => {
        const head = observed.get(
          positionKey({
            ...feet.position,
            y: feet.position.y + 1,
          }),
        );
        const support = observed.get(
          positionKey({
            ...feet.position,
            y: feet.position.y - 1,
          }),
        );
        return (
          feet.replaceable &&
          !isAvoidedFluidBlockId(feet.blockId) &&
          head?.replaceable === true &&
          !isAvoidedFluidBlockId(head.blockId) &&
          isReliablePortalCastingSupport(support) &&
          !scaffoldKeys.has(positionKey(feet.position)) &&
          !scaffoldKeys.has(positionKey(head.position))
        );
      })
      .map(({ position }) => ({
        x: position.x + 0.5,
        y: position.y,
        z: position.z + 0.5,
        dimension: position.dimension,
      }))
      .sort(
        (left, right) =>
          distanceSquared(left, observation.player.position) -
          distanceSquared(right, observation.player.position),
      )
      .slice(0, 16);
    for (const candidate of candidates) {
      const reached = yield* driver
        .pathfind(candidate, 0.75, {
          ...mergePathPolicy(path),
          allowMining: false,
          allowPlacing: false,
          avoidFluids: true,
          maxFallDistance: 1,
        })
        .pipe(Effect.result);
      if (reached._tag === "Success") {
        return;
      }
    }
    return yield* Effect.fail(
      behaviorError(driver, "Could not leave the temporary portal casting scaffold cells"),
    );
  });
}

function isReliablePortalCastingSupport(
  block: BeatGameBlockObservation | undefined,
): block is BeatGameBlockObservation {
  return block !== undefined && !block.replaceable && block.properties.waterlogged === undefined;
}

function useBucketToward(
  driver: BeatGameDriver,
  target: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    yield* driver.act({ type: "reset-movement" });
    const observation = yield* driver.observe;
    const targetCenter = blockCenter(target);
    const eyePosition = {
      ...observation.player.position,
      y: observation.player.position.y + 1.62,
    };
    const rotation = rotationToward(eyePosition, targetCenter);
    yield* driver.act({
      type: "look",
      yaw: rotation.yaw,
      pitch: rotation.pitch,
    });
    yield* waitForRotation(driver, rotation.yaw, rotation.pitch, 40, 50);
    const current = yield* driver.observe;
    const currentEyePosition = {
      ...current.player.position,
      y: current.player.position.y + 1.62,
    };
    const direction = {
      x: targetCenter.x - currentEyePosition.x,
      y: targetCenter.y - currentEyePosition.y,
      z: targetCenter.z - currentEyePosition.z,
    };
    const distance = Math.sqrt(distanceSquared(currentEyePosition, targetCenter));
    if (distance > LIQUID_INTERACTION_REACH) {
      return yield* Effect.fail(
        behaviorError(driver, `The liquid source at ${positionKey(target)} is out of reach`),
      );
    }
    const targeted = (yield* driver.raycast({
      direction,
      maximumDistance: Math.min(LIQUID_INTERACTION_REACH, distance + 0.05),
      includeFluids: true,
    })).block;
    const flowingFluidInFrontOfSource =
      targeted !== undefined &&
      isAvoidedFluidBlockId(targeted.blockId) &&
      targeted.properties.level !== undefined &&
      targeted.properties.level !== "0";
    const sourceOnlySightlineIsClear = flowingFluidInFrontOfSource
      ? (yield* driver.raycast({
          direction,
          maximumDistance: Math.min(LIQUID_INTERACTION_REACH, distance + 0.05),
          includeFluids: false,
        })).block
      : undefined;
    if (
      targeted === undefined ||
      (!samePosition(targeted.position, target) &&
        !(
          flowingFluidInFrontOfSource &&
          (sourceOnlySightlineIsClear === undefined ||
            samePosition(sourceOnlySightlineIsClear.position, target))
        ))
    ) {
      return yield* Effect.fail(
        behaviorError(driver, `The liquid source at ${positionKey(target)} is not targeted`),
      );
    }
    yield* driver.act({ type: "use-item", hand: "main" });
  });
}

function placeBucketOnTopOf(
  driver: BeatGameDriver,
  support: BeatGameBlockPosition,
  itemIds: readonly string[],
  path?: Partial<BeatGamePathPolicy>,
  attemptsRemaining = 3,
): Effect.Effect<void, BeatGameDriverError> {
  const target = { ...support, y: support.y + 1 };
  const expectedBlockId = itemIds.includes("minecraft:lava_bucket")
    ? "minecraft:lava"
    : "minecraft:water";
  const attempt = Effect.gen(function* () {
    yield* exposePortalBucketSupport(driver, support);
    yield* driver.act({
      type: "select-item",
      selector: { itemIds },
    });
    yield* driver.act({
      type: "interact-block",
      position: support,
      face: "up",
      hand: "main",
      // Bucket placement must bypass interactive supports. A crafting table,
      // furnace, or chest consumes a normal right click before the held
      // bucket gets its use turn. AltoClef and Baritone both sneak while
      // placing for the same reason.
      sneaking: true,
    });
  });
  return attempt.pipe(
    Effect.catch((cause) =>
      observeExactBlock(driver, target).pipe(
        Effect.flatMap((block) =>
          block?.blockId === expectedBlockId ||
          (expectedBlockId === "minecraft:water" && block?.properties.waterlogged === "true")
            ? Effect.void
            : retryPortalBucketPlacement(
                driver,
                support,
                itemIds,
                path,
                attemptsRemaining,
                cause,
                cause.code === "failed_precondition",
              ),
        ),
      ),
    ),
    Effect.andThen(
      waitForExactBlockState(
        driver,
        target,
        (block) =>
          block?.blockId === expectedBlockId ||
          (expectedBlockId === "minecraft:water" && block?.properties.waterlogged === "true"),
        5,
        50,
      ),
    ),
    Effect.flatMap((block) =>
      block?.blockId === expectedBlockId ||
      (expectedBlockId === "minecraft:water" && block?.properties.waterlogged === "true")
        ? Effect.void
        : retryPortalBucketPlacement(
            driver,
            support,
            itemIds,
            path,
            attemptsRemaining,
            behaviorError(driver, `Bucket placement missed ${positionKey(target)}`),
            true,
          ),
    ),
  );
}

function retryPortalBucketPlacement(
  driver: BeatGameDriver,
  support: BeatGameBlockPosition,
  itemIds: readonly string[],
  path: Partial<BeatGamePathPolicy> | undefined,
  attemptsRemaining: number,
  failure: BeatGameDriverError,
  retryWithFilledBucket: boolean,
): Effect.Effect<void, BeatGameDriverError> {
  if (attemptsRemaining <= 1) {
    return Effect.fail(failure);
  }
  const target = { ...support, y: support.y + 1 };
  return Effect.gen(function* () {
    const observation = yield* driver.observe;
    const hasFilledBucket = itemIds.some(
      (itemId) => (observation.inventory.counts[itemId] ?? 0) > 0,
    );
    if (!hasFilledBucket) {
      if (itemIds.includes("minecraft:lava_bucket")) {
        yield* collectPortalCastingLava(driver, 1, path);
      } else {
        yield* recoverPortalCastingWaterBucket(driver, target, path);
      }
    } else if (!retryWithFilledBucket) {
      return yield* Effect.fail(failure);
    }
    yield* Effect.sleep(100);
    yield* placeBucketOnTopOf(driver, support, itemIds, path, attemptsRemaining - 1);
  });
}

function exposePortalBucketSupport(
  driver: BeatGameDriver,
  support: BeatGameBlockPosition,
): Effect.Effect<void, BeatGameDriverError> {
  return Effect.gen(function* () {
    for (
      let clearedBlocks = 0;
      clearedBlocks <= PORTAL_CASTING_LAVA_SIGHT_CLEARING_BLOCKS;
      clearedBlocks += 1
    ) {
      const observation = yield* driver.observe;
      const eyePosition = {
        ...observation.player.position,
        y: observation.player.position.y + 1.62,
      };
      const supportTop = topFaceCenter(support);
      const direction = {
        x: supportTop.x - eyePosition.x,
        y: supportTop.y - eyePosition.y,
        z: supportTop.z - eyePosition.z,
      };
      const distance = Math.sqrt(
        direction.x * direction.x + direction.y * direction.y + direction.z * direction.z,
      );
      const rotation = rotationToward(eyePosition, supportTop);
      yield* driver.act({
        type: "look",
        yaw: rotation.yaw,
        pitch: rotation.pitch,
      });
      yield* waitForRotation(driver, rotation.yaw, rotation.pitch, 40, 50);
      const obstruction = (yield* driver.raycast({
        direction,
        maximumDistance: distance + 0.25,
        includeFluids: false,
      })).block;
      if (obstruction === undefined || samePosition(obstruction.position, support)) {
        return;
      }
      if (
        !obstruction.diggable ||
        obstruction.blockId === "minecraft:obsidian" ||
        isPortalCastingPlayerStabilityBlock(observation.player.position, obstruction.position) ||
        clearedBlocks === PORTAL_CASTING_LAVA_SIGHT_CLEARING_BLOCKS
      ) {
        return yield* Effect.fail(
          behaviorError(
            driver,
            `Could not expose portal bucket support through ${
              obstruction.blockId
            } at ${positionKey(obstruction.position)}`,
          ),
        );
      }
      const tool = preferredPortalDigTool(observation);
      if (tool !== undefined) {
        yield* driver.act({
          type: "select-item",
          selector: { itemIds: [tool] },
        });
      }
      yield* driver.act({
        type: "dig-block",
        position: obstruction.position,
      });
      const cleared = yield* waitForExactBlockState(
        driver,
        obstruction.position,
        (block) => block === undefined || block.replaceable,
        10,
        50,
      );
      if (cleared !== undefined && !cleared.replaceable) {
        return yield* Effect.fail(
          behaviorError(
            driver,
            `Could not clear portal bucket sightline at ${positionKey(obstruction.position)}`,
          ),
        );
      }
    }
  });
}

function blockCenter(position: BeatGameBlockPosition): BeatGamePosition {
  return {
    ...position,
    x: position.x + 0.5,
    y: position.y + 0.5,
    z: position.z + 0.5,
  };
}

function staircaseFeetCenter(position: BeatGameBlockPosition): BeatGamePosition {
  return {
    ...position,
    x: position.x + 0.5,
    z: position.z + 0.5,
  };
}

function topFaceCenter(position: BeatGameBlockPosition): BeatGamePosition {
  return {
    ...position,
    x: position.x + 0.5,
    // Aim just above the face. The vanilla item-use ray continues past this
    // point and crosses the top surface. A point inside the support can hit
    // its side instead when the player is standing at a shallow angle.
    y: position.y + 1 + PORTAL_CASTING_BUCKET_FACE_AIM_HEIGHT,
    z: position.z + 0.5,
  };
}

function portalCastingStand(
  frame: PortalFrame,
  target: BeatGameBlockPosition,
): BeatGameBlockPosition {
  return {
    x: frame.origin.x + (frame.axis === "x" ? 1 : -2),
    // Bucket items perform their own vanilla raycast after the SDK supplies
    // the requested block interaction. Keep the player's eyes above the
    // support top so the fallback ray hits UP instead of spilling the bucket
    // against the side of higher frame rows.
    y: Math.max(frame.origin.y + 1, target.y - 1),
    z: frame.origin.z + (frame.axis === "z" ? 1 : -2),
    dimension: frame.origin.dimension,
  };
}

function clearPortalCastingCells(
  driver: BeatGameDriver,
  positions: readonly BeatGameBlockPosition[],
  path?: Partial<BeatGamePathPolicy>,
): Effect.Effect<void, BeatGameDriverError> {
  return driver.withControl(
    Effect.gen(function* () {
      for (const position of positions) {
        let block = yield* observeExactBlock(driver, position);
        if (block === undefined || block.replaceable) {
          continue;
        }
        if (!block.diggable) {
          return yield* Effect.fail(
            behaviorError(
              driver,
              `Portal casting cell is not diggable at ${positionKey(position)}`,
            ),
          );
        }
        yield* driver.pathfind(position, 3, mergePathPolicy(path));
        const observation = yield* driver.observe;
        const tool = preferredPortalDigTool(observation);
        if (tool !== undefined) {
          yield* driver.act({
            type: "select-item",
            selector: { itemIds: [tool] },
          });
        }
        yield* driver.act({ type: "dig-block", position });
        block = yield* waitForExactBlockState(
          driver,
          position,
          (candidate) => candidate === undefined || candidate.replaceable,
          10,
          50,
        );
        if (block !== undefined && !block.replaceable) {
          return yield* Effect.fail(
            behaviorError(driver, `Could not clear portal casting cell ${positionKey(position)}`),
          );
        }
      }
    }).pipe(Effect.ensuring(driver.act({ type: "reset-movement" }).pipe(Effect.ignore))),
  );
}

function primitiveActionPosition(
  action: BeatGamePrimitiveAction,
): BeatGameBlockPosition | undefined {
  switch (action.type) {
    case "dig-block":
    case "interact-block":
      return action.position;
    case "place-block":
      return action.against;
    default:
      return undefined;
  }
}

function queryEndEntities(
  driver: BeatGameDriver,
  origin: BeatGamePosition,
  entityType: string,
  radius: number,
  maximumResults: number,
): Effect.Effect<readonly BeatGameEntityObservation[], BeatGameDriverError> {
  return driver.queryEntities({
    origin,
    radius,
    selector: {
      entityTypes: [entityType],
      alive: true,
    },
    maximumResults,
  });
}

function requireObservedBlock(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
  blockIds: readonly string[],
  operation: string,
): Effect.Effect<void, BeatGameDriverError> {
  return driver
    .queryBlocks({
      center: position,
      radius: 1,
      selector: { blockIds },
      maximumResults: 16,
    })
    .pipe(
      Effect.flatMap((blocks) =>
        blocks.some((block) => samePosition(block.position, position))
          ? Effect.void
          : Effect.fail(
              behaviorError(driver, `${operation} did not produce ${blockIds.join(" or ")}`),
            ),
      ),
    );
}

function waitForBlock(
  driver: BeatGameDriver,
  query: BeatGameQueryBlocks,
  attemptsRemaining: number,
  delayMs: number,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver.queryBlocks(query).pipe(
    Effect.flatMap((blocks) => {
      const block = blocks[0];
      if (block !== undefined || attemptsRemaining <= 1) {
        return Effect.succeed(block);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(waitForBlock(driver, query, attemptsRemaining - 1, delayMs)),
      );
    }),
  );
}

function waitForExactBlock(
  driver: BeatGameDriver,
  position: BeatGameBlockPosition,
  selector: BeatGameQueryBlocks["selector"],
  attemptsRemaining: number,
  delayMs: number,
): Effect.Effect<BeatGameBlockObservation | undefined, BeatGameDriverError> {
  return driver.queryBlocks(exactBlockQuery(position, selector)).pipe(
    Effect.flatMap((blocks) => {
      const block = blocks.find((candidate) => samePosition(candidate.position, position));
      if (block !== undefined || attemptsRemaining <= 1) {
        return Effect.succeed(block);
      }
      return Effect.sleep(delayMs).pipe(
        Effect.andThen(
          waitForExactBlock(driver, position, selector, attemptsRemaining - 1, delayMs),
        ),
      );
    }),
  );
}

function castingWaterPosition(
  frame: PortalFrame,
  target: BeatGameBlockPosition,
): BeatGameBlockPosition {
  return {
    ...target,
    x: target.x - (frame.axis === "z" ? 1 : 0),
    z: target.z - (frame.axis === "x" ? 1 : 0),
  };
}

function below(position: BeatGameBlockPosition): BeatGameBlockPosition {
  return { ...position, y: position.y - 1 };
}

function uniquePositions(
  positions: readonly BeatGameBlockPosition[],
): readonly BeatGameBlockPosition[] {
  return [...new Map(positions.map((position) => [positionKey(position), position])).values()];
}

function samePosition(left: BeatGamePosition, right: BeatGamePosition): boolean {
  return (
    left.dimension === right.dimension &&
    left.x === right.x &&
    left.y === right.y &&
    left.z === right.z
  );
}

function mergePathPolicy(override: Partial<BeatGamePathPolicy> | undefined): BeatGamePathPolicy {
  return {
    ...defaultBeatGameStrategy.path,
    ...override,
  };
}

function staircaseStepPathPolicy(
  override: Partial<BeatGamePathPolicy> | undefined,
): BeatGamePathPolicy {
  return {
    ...mergePathPolicy(override),
    allowMining: false,
    allowPlacing: false,
    maxFallDistance: 1,
    searchMode: "PRECISION",
    maximumQualityBound: 1,
    maxParkourGap: 0,
    smoothCamera: false,
  };
}

function positionKey(position: BeatGameBlockPosition): string {
  return `${position.dimension}:${position.x}:${position.y}:${position.z}`;
}

function positiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive safe integer`);
  }
  return value;
}

function positiveFiniteNumber(value: number, name: string): number {
  if (!Number.isFinite(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive finite number`);
  }
  return value;
}

function nonNegativeFiniteNumber(value: number, name: string): number {
  if (!Number.isFinite(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative finite number`);
  }
  return value;
}

function nonNegativeInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative safe integer`);
  }
  return value;
}

function behaviorError(
  driver: BeatGameDriver,
  message: string,
  code?: string,
): BeatGameDriverError {
  return new BeatGameDriverError({
    operation: "behavior",
    ...(code === undefined ? {} : { code }),
    retryable: true,
    message: `${driver.instanceId}/${driver.botId}: ${message}`,
  });
}

function craftItemDependencies(
  driver: BeatGameDriver,
  resultItemId: string,
  requestedCount: number,
  options: CraftItemOptions,
  ancestors: readonly string[],
  remainingDepth: number,
): Effect.Effect<void, BeatGameDriverError> {
  if (remainingDepth === 0) {
    return Effect.fail(
      behaviorError(
        driver,
        `Recipe expansion exceeded its depth limit while producing ${resultItemId}`,
      ),
    );
  }
  if (ancestors.includes(resultItemId)) {
    return Effect.fail(
      behaviorError(driver, `Recipe dependency cycle encountered while producing ${resultItemId}`),
    );
  }
  return Effect.gen(function* () {
    const recipes = yield* driver.recipesFor(resultItemId);
    if (recipes.length === 0) {
      const unlocked = yield* unlockRecipePrerequisite(
        driver,
        resultItemId,
        options,
        [...ancestors, resultItemId],
        remainingDepth - 1,
      );
      if (unlocked) {
        yield* Effect.sleep(100);
        return yield* craftItemDependencies(
          driver,
          resultItemId,
          requestedCount,
          options,
          ancestors,
          remainingDepth - 1,
        );
      }
    }
    let lastFailure: BeatGameDriverError | undefined;
    recipeLoop: for (const recipe of recipes) {
      if (recipe.resultCount <= 0) {
        continue;
      }
      const operations = Math.ceil(requestedCount / recipe.resultCount);
      let craftability = yield* driver.canCraft(recipe.recipeId, operations);
      if (!craftability.canCraft) {
        for (const missing of craftability.missing) {
          let resolved = false;
          let remainingMissing = missing.missing;
          for (const candidate of missing.itemIds) {
            for (let candidateCount = remainingMissing; candidateCount >= 1; candidateCount -= 1) {
              const result = yield* craftItemDependencies(
                driver,
                candidate,
                candidateCount,
                options,
                [...ancestors, resultItemId],
                remainingDepth - 1,
              ).pipe(Effect.result);
              if (result._tag === "Failure") {
                lastFailure = result.failure;
                continue;
              }
              craftability = yield* driver.canCraft(recipe.recipeId, operations);
              if (craftability.canCraft) {
                resolved = true;
                break;
              }
              const unresolved = craftability.missing.find((candidate) =>
                sameItemAlternatives(candidate.itemIds, missing.itemIds),
              );
              if (unresolved === undefined) {
                resolved = true;
                break;
              }
              remainingMissing = unresolved.missing;
              break;
            }
            if (resolved) {
              break;
            }
          }
          if (!resolved) {
            lastFailure = behaviorError(
              driver,
              `Missing ${missing.missing} of ${missing.itemIds.join(
                " or ",
              )} while producing ${resultItemId}`,
              "resource-exhausted",
            );
            continue recipeLoop;
          }
        }
        craftability = yield* driver.canCraft(recipe.recipeId, operations);
        if (!craftability.canCraft) {
          return yield* craftItemDependencies(
            driver,
            resultItemId,
            requestedCount,
            options,
            ancestors,
            remainingDepth - 1,
          );
        }
      }
      if (!craftability.canCraft) {
        continue;
      }
      if (craftability.requiredStation !== undefined && options.station === undefined) {
        lastFailure = behaviorError(
          driver,
          `${craftability.requiredStation} is required to produce ${resultItemId}`,
        );
        continue;
      }
      return yield* craft(driver, {
        recipeId: recipe.recipeId,
        count: operations,
        ...(options.station === undefined ? {} : { station: options.station }),
        ...(options.path === undefined ? {} : { path: options.path }),
      });
    }
    return yield* Effect.fail(
      lastFailure ??
        behaviorError(
          driver,
          `No known recipe can currently produce ${resultItemId}`,
          "resource-exhausted",
        ),
    );
  });
}

function sameItemAlternatives(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((itemId) => right.includes(itemId));
}

function unlockRecipePrerequisite(
  driver: BeatGameDriver,
  resultItemId: string,
  options: CraftItemOptions,
  ancestors: readonly string[],
  remainingDepth: number,
): Effect.Effect<boolean, BeatGameDriverError> {
  const prerequisite = RECIPE_UNLOCK_PREREQUISITES[resultItemId];
  if (prerequisite !== undefined) {
    return craftItemDependencies(
      driver,
      prerequisite.itemId,
      prerequisite.count,
      options,
      ancestors,
      remainingDepth,
    ).pipe(Effect.as(true));
  }
  if (resultItemId === "minecraft:crafting_table") {
    return driver.observe.pipe(
      Effect.flatMap((observation) => {
        const material = LOG_TO_PLANKS.find(
          ([log]) => (observation.inventory.counts[log] ?? 0) > 0,
        );
        if (material === undefined) {
          return Effect.succeed(false);
        }
        return craftItemDependencies(
          driver,
          material[1],
          4,
          options,
          ancestors,
          remainingDepth,
        ).pipe(Effect.as(true));
      }),
    );
  }
  return Effect.succeed(false);
}
