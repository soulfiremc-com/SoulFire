import {
  BlockFace,
  BotLifecycleKind,
  BotTaskConflictPolicy,
  BotTaskReconnectPolicy,
  goals,
  Hand,
  InventoryArea,
  PathfindSearchMode,
  QuerySort,
  type BlockSnapshot,
  type SchematicBlock,
  type SoulFireBot,
  type SoulFireBotControlLease,
} from "@soulfiremc/sdk";
import { Effect, Semaphore, Stream } from "effect";

import { BeatGameDriverError } from "./errors.js";
import type {
  BeatGameBlockObservation,
  BeatGameBlockPosition,
  BeatGameEntityObservation,
  BeatGameObservation,
  BeatGamePathPolicy,
  BeatGamePosition,
} from "./model.js";
import { BeatGamePathSearchMode } from "./model.js";

const CONTROL_LEASE_TTL_SECONDS = 90;
const CONTROL_LEASE_RENEWAL_INTERVAL_MS = 30_000;
/// The most blocks the server returns in one page.
const BLOCK_QUERY_PAGE_SIZE = 500;
const RPC_STATUS_CODE_NAMES = [
  "ok",
  "canceled",
  "unknown",
  "invalid_argument",
  "deadline_exceeded",
  "not_found",
  "already_exists",
  "permission_denied",
  "resource_exhausted",
  "failed_precondition",
  "aborted",
  "out_of_range",
  "unimplemented",
  "internal",
  "unavailable",
  "data_loss",
  "unauthenticated",
] as const;

export interface BeatGameItemSelector {
  readonly itemIds?: readonly string[];
  readonly tags?: readonly string[];
  readonly minimumCount?: number;
}

export interface BeatGameEntitySelector {
  readonly entityTypes?: readonly string[];
  readonly tags?: readonly string[];
  readonly categories?: readonly number[];
  readonly uuid?: string;
  readonly networkId?: number;
  readonly alive?: boolean;
  readonly requireLineOfSight?: boolean;
}

export interface BeatGameBlockSelector {
  readonly blockIds?: readonly string[];
  readonly tags?: readonly string[];
  readonly properties?: Readonly<Record<string, string>>;
  readonly solid?: boolean;
  readonly replaceable?: boolean;
  readonly interactive?: boolean;
  readonly diggable?: boolean;
  readonly requireLineOfSight?: boolean;
}

export interface BeatGameQueryBlocks {
  readonly center: BeatGamePosition;
  readonly radius: number;
  readonly selector: BeatGameBlockSelector;
  readonly maximumResults?: number;
}

export interface BeatGameQueryEntities {
  readonly origin?: BeatGamePosition;
  readonly radius: number;
  readonly selector: BeatGameEntitySelector;
  readonly maximumResults?: number;
}

export interface BeatGameRaycastQuery {
  readonly direction: Readonly<{ x: number; y: number; z: number }>;
  readonly maximumDistance: number;
  readonly includeFluids?: boolean;
}

export interface BeatGameRaycastObservation {
  readonly block?: BeatGameBlockObservation;
  readonly distance: number;
}

export interface BeatGameSurfaceColumn {
  readonly x: number;
  readonly z: number;
  readonly loaded: boolean;
  readonly surfaceY?: number;
  readonly blockId?: string;
  readonly biomeId?: string;
  readonly skyLight: number;
  readonly blockLight: number;
}

export interface BeatGameEnvironmentObservation {
  readonly gameTime?: bigint;
  /// Ticks of the Overworld day clock. Sleeping moves it on but not the
  /// game time, so it is what tells day from night.
  readonly dayTime?: bigint;
  readonly raining?: boolean;
}

const OVERWORLD_DAY_CLOCK_ID = "minecraft:overworld";

/// Reads the Overworld day clock from the clocks a bot session tracks.
export function overworldDayTime(
  clocks: ReadonlyMap<string, { readonly totalTicks: bigint }>,
): bigint | undefined {
  return (clocks.get(OVERWORLD_DAY_CLOCK_ID) ?? clocks.values().next().value)?.totalTicks;
}

export interface SoulFireBeatGameDriverOptions {
  readonly environment?: Effect.Effect<BeatGameEnvironmentObservation, BeatGameDriverError>;
}

export interface BeatGameBuildBlock {
  readonly offset: Readonly<{ x: number; y: number; z: number }>;
  readonly blockId: string;
  readonly properties?: Readonly<Record<string, string>>;
}

export interface BeatGameRecipe {
  readonly recipeId: string;
  readonly recipeType: string;
  readonly resultItemId: string;
  readonly resultCount: number;
  readonly ingredients: readonly {
    readonly itemIds: readonly string[];
    readonly tags: readonly string[];
    readonly count: number;
  }[];
}

export interface BeatGameCraftability {
  readonly canCraft: boolean;
  readonly maximumCraftCount: number;
  readonly requiredStation?: string;
  readonly missing: readonly {
    readonly itemIds: readonly string[];
    readonly tags: readonly string[];
    readonly available: number;
    readonly missing: number;
  }[];
}

export interface BeatGameContainerTransfer {
  readonly selector: BeatGameItemSelector;
  readonly count: number;
  readonly allowPartial?: boolean;
}

export interface BeatGameLoadoutRequirement {
  readonly selector: BeatGameItemSelector;
  readonly minimumCount: number;
  readonly targetCount: number;
  readonly maximumCount?: number;
}

export interface BeatGameTaskExecutionOptions {
  readonly idempotencyKey?: string;
  readonly deadline?: Date;
}

export type BeatGameTask =
  | {
      readonly type: "collect-blocks";
      readonly blockIds: readonly string[];
      readonly tags?: readonly string[];
      readonly count: number;
      readonly searchRadius: number;
      readonly avoidSubmergedTargets?: boolean;
      readonly requireLineOfSight?: boolean;
      readonly targetYRange?: Readonly<{
        minimum?: number;
        maximum?: number;
      }>;
    }
  | {
      readonly type: "excavate";
      readonly from: BeatGameBlockPosition;
      readonly to: BeatGameBlockPosition;
      readonly maximumBlocks?: number;
    }
  | {
      readonly type: "attack-entity";
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
  | {
      readonly type: "ranged-attack";
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
  | {
      readonly type: "attack-nearest";
      readonly selector: BeatGameEntitySelector;
      readonly radius: number;
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
  | {
      readonly type: "flee";
      readonly selector: BeatGameEntitySelector;
      readonly triggerRadius?: number;
      readonly safeDistance?: number;
      readonly safeSeconds?: number;
      readonly completeWhenSafe?: boolean;
      readonly maximumEscapes?: number;
    }
  | {
      readonly type: "guard";
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
  | {
      readonly type: "fish";
      readonly maximumCatches?: number;
      readonly maximumFailedCasts?: number;
    }
  | {
      readonly type: "farm";
      readonly cropIds?: readonly string[];
      readonly center?: BeatGameBlockPosition;
      readonly radius?: number;
      readonly maximumHarvests?: number;
    }
  | {
      readonly type: "breed";
      readonly selector?: BeatGameEntitySelector;
      readonly food?: BeatGameItemSelector;
      readonly maximumPairs?: number;
    }
  | {
      readonly type: "explore";
      readonly origin?: BeatGameBlockPosition;
      readonly radius: number;
      readonly maximumWaypoints?: number;
      readonly purpose?: string;
    }
  | {
      readonly type: "transfer-container";
      readonly direction: "deposit" | "withdraw";
      readonly container: BeatGameBlockPosition;
      readonly operations: readonly BeatGameContainerTransfer[];
    }
  | {
      readonly type: "maintain-loadout";
      readonly container: BeatGameBlockPosition;
      readonly requirements: readonly BeatGameLoadoutRequirement[];
    }
  | {
      readonly type: "auto-eat";
      readonly foodItemIds?: readonly string[];
      readonly foodLevel: number;
      readonly maximumMeals?: number;
      readonly completeWhenNoFood?: boolean;
      readonly restoreSelectedSlot?: boolean;
    }
  | {
      readonly type: "auto-respawn";
      readonly maximumRespawns?: number;
    }
  | {
      readonly type: "auto-totem";
    }
  | {
      readonly type: "auto-armor";
      readonly maximumEquips?: number;
      readonly completeWhenNoUpgrade?: boolean;
    }
  | {
      readonly type: "sleep";
      readonly bed?: BeatGameBlockPosition;
      readonly searchRadius?: number;
      readonly waitUntilPossible?: boolean;
      readonly retryIntervalTicks?: number;
    }
  | {
      readonly type: "build";
      readonly origin: BeatGameBlockPosition;
      readonly blocks: readonly BeatGameBuildBlock[];
      readonly partitionIndex?: number;
      readonly partitionCount?: number;
    }
  | {
      readonly type: "craft";
      readonly recipeId: string;
      readonly count: number;
      readonly station?: BeatGameBlockPosition;
    }
  | {
      readonly type: "smelt";
      readonly input: BeatGameItemSelector;
      readonly count: number;
      readonly fuel?: BeatGameItemSelector;
      readonly station?: BeatGameBlockPosition;
    }
  | {
      readonly type: "brew";
      readonly input: BeatGameItemSelector;
      readonly ingredient: BeatGameItemSelector;
      readonly count: number;
      readonly fuel?: BeatGameItemSelector;
      readonly station?: BeatGameBlockPosition;
      readonly expectedResult?: BeatGameItemSelector;
    }
  | {
      readonly type: "trade";
      readonly offerIndex: number;
      readonly count: number;
      readonly expectedResult?: BeatGameItemSelector;
    };

export type BeatGameBlockFace = "down" | "up" | "north" | "south" | "west" | "east";

export type BeatGameHand = "main" | "off";

export type BeatGamePrimitiveAction =
  | {
      readonly type: "look";
      readonly yaw: number;
      readonly pitch: number;
    }
  | {
      readonly type: "equip-item";
      readonly selector: BeatGameItemSelector;
      readonly equipmentSlot: "mainhand" | "offhand" | "head" | "chest" | "legs" | "feet";
    }
  | {
      readonly type: "select-item";
      readonly selector: BeatGameItemSelector;
    }
  | {
      readonly type: "toss-items";
      readonly selector: BeatGameItemSelector;
      readonly count: number;
    }
  | {
      readonly type: "use-item";
      readonly hand?: BeatGameHand;
      /// Ticks to keep trying while the item cannot be used yet.
      readonly retryTicks?: number;
    }
  | {
      readonly type: "release-item";
    }
  | {
      readonly type: "dig-block";
      readonly position: BeatGameBlockPosition;
    }
  | {
      readonly type: "place-block";
      readonly against: BeatGameBlockPosition;
      readonly face: BeatGameBlockFace;
      readonly hand?: BeatGameHand;
    }
  | {
      readonly type: "interact-block";
      readonly position: BeatGameBlockPosition;
      readonly face: BeatGameBlockFace;
      readonly hand?: BeatGameHand;
      readonly sneaking?: boolean;
    }
  | {
      readonly type: "attack-entity";
      readonly connectionEpoch: string;
      readonly networkId: number;
      readonly sprinting?: boolean;
    }
  | {
      readonly type: "interact-entity";
      readonly connectionEpoch: string;
      readonly networkId: number;
      readonly hand?: BeatGameHand;
      readonly sneaking?: boolean;
    }
  | {
      readonly type: "set-movement";
      readonly forward?: boolean;
      readonly backward?: boolean;
      readonly left?: boolean;
      readonly right?: boolean;
      readonly jump?: boolean;
      readonly sneak?: boolean;
      readonly sprint?: boolean;
    }
  | {
      readonly type: "respawn" | "reset-movement" | "close-container";
    };

export type BeatGameDriverEvent =
  | {
      readonly type: "bot-event";
      readonly observedAt: string;
      readonly payload: unknown;
    }
  | {
      readonly type: "bot-died";
      readonly observedAt: string;
      readonly message?: string;
    }
  | {
      readonly type: "bot-respawned";
      readonly observedAt: string;
    }
  | {
      readonly type: "bot-damaged";
      readonly observedAt: string;
      readonly amount: number;
      readonly health: number;
      // The damage type id, such as minecraft:freeze, when the server reported one.
      readonly damageType?: string;
    }
  | {
      readonly type: "game-event";
      readonly observedAt: string;
      readonly event: string;
      readonly parameter: number;
    };

export interface BeatGameDriver {
  readonly instanceId: string;
  readonly botId: string;
  readonly observe: Effect.Effect<BeatGameObservation, BeatGameDriverError>;
  readonly events: Stream.Stream<BeatGameDriverEvent, BeatGameDriverError>;
  readonly environment?: Effect.Effect<BeatGameEnvironmentObservation, BeatGameDriverError>;
  readonly queryBlocks: (
    query: BeatGameQueryBlocks,
  ) => Effect.Effect<readonly BeatGameBlockObservation[], BeatGameDriverError>;
  readonly queryEntities: (
    query: BeatGameQueryEntities,
  ) => Effect.Effect<readonly BeatGameEntityObservation[], BeatGameDriverError>;
  readonly raycast: (
    query: BeatGameRaycastQuery,
  ) => Effect.Effect<BeatGameRaycastObservation, BeatGameDriverError>;
  readonly sampleSurface: (
    center: BeatGamePosition,
    radius?: number,
    sampleStep?: number,
  ) => Effect.Effect<readonly BeatGameSurfaceColumn[], BeatGameDriverError>;
  readonly recipesFor: (
    resultItemId: string,
  ) => Effect.Effect<readonly BeatGameRecipe[], BeatGameDriverError>;
  readonly canCraft: (
    recipeId: string,
    count: number,
  ) => Effect.Effect<BeatGameCraftability, BeatGameDriverError>;
  readonly waitForChunks: (
    radiusChunks?: number,
    timeoutMs?: number,
  ) => Effect.Effect<void, BeatGameDriverError>;
  readonly pathfind: (
    position: BeatGamePosition,
    radius: number,
    policy: BeatGamePathPolicy,
  ) => Effect.Effect<void, BeatGameDriverError>;
  readonly pathfindXZ: (
    x: number,
    z: number,
    dimension: string,
    radius: number,
    policy: BeatGamePathPolicy,
  ) => Effect.Effect<void, BeatGameDriverError>;
  readonly runTask: (
    task: BeatGameTask,
    policy: BeatGamePathPolicy,
    execution?: BeatGameTaskExecutionOptions,
  ) => Effect.Effect<unknown, BeatGameDriverError>;
  readonly act: (action: BeatGamePrimitiveAction) => Effect.Effect<unknown, BeatGameDriverError>;
  readonly withControl: <A, E, R>(
    effect: Effect.Effect<A, E, R>,
  ) => Effect.Effect<A, E | BeatGameDriverError, R>;
}

const mapError = (operation: string) => (cause: unknown) => driverError(operation, cause);
const pathOptions = (policy: BeatGamePathPolicy) => ({
  allowMining: policy.allowMining,
  allowPlacing: policy.allowPlacing,
  avoidFluids: policy.avoidFluids ?? false,
  ...(policy.additionalPlaceItemIds === undefined || policy.additionalPlaceItemIds.length === 0
    ? {}
    : {
        additionalPlaceItemIds: [...policy.additionalPlaceItemIds],
      }),
  ...(policy.protectedBlocks === undefined || policy.protectedBlocks.length === 0
    ? {}
    : {
        protectedBlocks: policy.protectedBlocks.map(blockPosition),
      }),
  ...(policy.sprint === undefined ? {} : { sprint: policy.sprint }),
  ...(policy.minimumY === undefined ? {} : { minimumY: policy.minimumY }),
  ...(policy.maximumY === undefined ? {} : { maximumY: policy.maximumY }),
  searchMode: ((): PathfindSearchMode => {
    switch (policy.searchMode) {
      case BeatGamePathSearchMode.PRECISION: {
        return PathfindSearchMode.PRECISION;
      }
      case BeatGamePathSearchMode.URGENT: {
        return PathfindSearchMode.URGENT;
      }
      case BeatGamePathSearchMode.ESCAPE: {
        return PathfindSearchMode.ESCAPE;
      }
      case BeatGamePathSearchMode.NORMAL:
      case undefined: {
        return PathfindSearchMode.NORMAL;
      }
      default: {
        return PathfindSearchMode.NORMAL;
      }
    }
  })(),
  ...(policy.maximumQualityBound === undefined
    ? {}
    : { maximumQualityBound: policy.maximumQualityBound }),
  ...(policy.maximumExpandedStates === undefined
    ? {}
    : { maximumExpandedStates: policy.maximumExpandedStates }),
  maximumFallDistance: policy.maxFallDistance,
  ...(policy.maxParkourGap === undefined ? {} : { maximumParkourGap: policy.maxParkourGap }),
  smoothCamera: policy.smoothCamera ?? false,
  ...(policy.walkingPitch === undefined ? {} : { walkingPitch: policy.walkingPitch }),
  timeoutSeconds: Math.max(1, Math.ceil(policy.maxSearchTimeMs / 1000)),
  searchTimeoutSeconds: Math.max(1, Math.ceil(policy.maxSearchTimeMs / 1000)),
});

export function makeSoulFireBeatGameDriver(
  bot: SoulFireBot,
  options: SoulFireBeatGameDriverOptions = {},
): BeatGameDriver {
  const controlMutex = Semaphore.makeUnsafe(1);
  let controlLease: SoulFireBotControlLease | undefined;
  let controlUsers = 0;
  const acquireSharedControl = controlMutex.withPermits(1)(
    Effect.gen(function* () {
      controlLease ??= yield* bot.acquireControl(CONTROL_LEASE_TTL_SECONDS);
      controlUsers += 1;
      return controlLease;
    }),
  );
  const releaseSharedControl = (lease: SoulFireBotControlLease): Effect.Effect<void> =>
    controlMutex.withPermits(1)(
      Effect.gen(function* () {
        if (controlLease !== lease) {
          return;
        }
        controlUsers = Math.max(0, controlUsers - 1);
        if (controlUsers > 0) {
          return;
        }
        yield* lease.release().pipe(
          Effect.ensuring(
            Effect.sync(() => {
              if (controlLease === lease) {
                controlLease = undefined;
              }
            }),
          ),
          Effect.ignore,
        );
      }),
    );

  const observe = Effect.all(
    {
      player: bot.world.player(),
      inventory: bot.inventory.snapshot(),
    },
    { concurrency: 1 },
  ).pipe(
    Effect.mapBoth({
      onSuccess: ({ player, inventory }): BeatGameObservation => {
        const position = required(player.position, "player.position");
        const velocity = required(player.velocity, "player.velocity");
        const rotation = required(player.rotation, "player.rotation");
        const counts: Record<string, number> = {};
        const remainingDurability: Record<string, number> = {};
        const hotbar: Record<number, string> = {};
        let occupiedPlayerSlots = 0;
        const equipment = Object.fromEntries(
          Object.entries(player.equipment).map(([slot, item]) => [slot, item.itemId]),
        );
        const countItem = (item: {
          readonly itemId: string;
          readonly count: number;
          readonly damage?: number;
          readonly maxDamage?: number;
        }) => {
          counts[item.itemId] = (counts[item.itemId] ?? 0) + item.count;
          if ((item.maxDamage ?? 0) > 0) {
            remainingDurability[item.itemId] =
              (remainingDurability[item.itemId] ?? 0) +
              Math.max(0, (item.maxDamage ?? 0) - (item.damage ?? 0)) * item.count;
          }
        };
        for (const slot of inventory.slots) {
          if (
            slot.item === undefined ||
            (slot.area !== InventoryArea.MAIN && slot.area !== InventoryArea.HOTBAR)
          ) {
            continue;
          }
          countItem(slot.item);
          occupiedPlayerSlots += 1;
          if (slot.area === InventoryArea.HOTBAR) {
            hotbar[slot.slot] = slot.item.itemId;
          }
        }
        // Worn armor and the offhand item come from the player, not the
        // inventory slots: with a crafting table or furnace open, those slots
        // are missing from the inventory snapshot. A live bot read its shield
        // as gone after a craft and spent four minutes mining iron for a new
        // one. The main hand is a hotbar slot, counted above.
        for (const [slot, item] of Object.entries(player.equipment)) {
          if (slot !== "mainhand" && item.itemId.length > 0 && item.count > 0) {
            countItem(item);
          }
        }
        return {
          observedAt: new Date().toISOString(),
          player: {
            position: toPosition(position),
            rotation: { yaw: rotation.yaw, pitch: rotation.pitch },
            velocity: { x: velocity.x, y: velocity.y, z: velocity.z },
            onGround: player.onGround,
            equipment,
            health: player.health,
            maxHealth: player.maxHealth,
            food: player.food,
            air: player.air,
            maxAir: player.maxAir,
            fireTicks: player.fireTicks,
            dead: player.dead,
            sleeping: player.sleeping,
            usingItem: player.usingItem,
            connectionEpoch: player.connectionEpoch,
            revision: player.revision,
          },
          inventory: {
            revision: inventory.revision,
            selectedHotbarSlot: inventory.selectedHotbarSlot,
            emptyPlayerSlots: Math.max(0, 36 - occupiedPlayerSlots),
            pathBuildingBlockCount: inventory.pathBuildingBlockCount,
            counts,
            remainingDurability,
            hotbar,
          },
        };
      },
      onFailure: mapError("observe"),
    }),
  );

  const withSharedControl = <A, E, R>(
    effect: Effect.Effect<A, E, R>,
  ): Effect.Effect<A, E | BeatGameDriverError, R> =>
    Effect.acquireUseRelease(
      acquireSharedControl,
      (lease) => {
        const renew = Effect.sleep(CONTROL_LEASE_RENEWAL_INTERVAL_MS).pipe(
          Effect.andThen(lease.renew(CONTROL_LEASE_TTL_SECONDS)),
          Effect.forever,
        );
        return Effect.raceFirst(effect, renew);
      },
      releaseSharedControl,
    ).pipe(
      Effect.mapError((cause) =>
        cause instanceof BeatGameDriverError ? cause : driverError("control", cause),
      ),
    );

  const actPrimitive = (action: BeatGamePrimitiveAction) =>
    executePrimitive(bot, action).pipe(Effect.mapError(mapError(`act.${action.type}`)));

  // Routes plan from the middle of each block. A live bot stood at the edge
  // of its block with its head under the low ceiling of the next column, so
  // every jump onto a one-block step bumped the ceiling. The same step
  // stalled for twenty minutes. Off the middle, a stalled route steps to the
  // middle of the block and tries once more.
  const stepToBlockCenter: Effect.Effect<boolean, BeatGameDriverError> = Effect.gen(function* () {
    const startObservation = yield* observe;
    const start = blockCenterOffset(startObservation);
    if (Math.hypot(start.x, start.z) <= BLOCK_CENTER_TOLERANCE) {
      return false;
    }
    // The movement keys only steer the bot while it holds the control lease,
    // as every other scripted movement does. A live nudge without it
    // left the bot where it stood. A turn to face the middle did not take
    // either, so the keys are chosen from the way the bot already faces.
    yield* withSharedControl(
      Effect.gen(function* () {
        for (let poll = 0; poll < BLOCK_CENTER_STEP_POLLS; poll += 1) {
          const currentObservation = yield* observe;
          const current = blockCenterOffset(currentObservation);
          if (Math.hypot(current.x, current.z) <= BLOCK_CENTER_TOLERANCE) {
            return;
          }
          yield* actPrimitive({
            type: "set-movement",
            ...movementKeysToward(current, currentObservation.player.rotation.yaw),
          });
          yield* Effect.sleep(BLOCK_CENTER_STEP_POLL_MS);
        }
      }).pipe(Effect.ensuring(actPrimitive({ type: "reset-movement" }).pipe(Effect.ignore))),
    );
    return true;
  });

  const recenterAfterActionStall = (
    route: Effect.Effect<void, BeatGameDriverError>,
  ): Effect.Effect<void, BeatGameDriverError> =>
    route.pipe(
      Effect.catchIf(
        (error) => error.code !== undefined && ROUTE_STALL_FAILURE_CODES.has(error.code),
        (error) =>
          stepToBlockCenter.pipe(
            Effect.flatMap((stepped) => (stepped ? route : Effect.fail(error))),
          ),
      ),
    );

  const goToGoal = (
    goal: Parameters<typeof bot.tasks.goTo>[0],
    policy: BeatGamePathPolicy,
    operation: "pathfind" | "pathfindXZ",
  ): Effect.Effect<void, BeatGameDriverError> =>
    bot.tasks
      .goTo(goal, {
        conflictPolicy: BotTaskConflictPolicy.REPLACE,
        reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
        path: pathOptions(policy),
      })
      .pipe(
        Effect.flatMap((handle) =>
          handle
            .result()
            .pipe(
              Effect.onInterrupt(() =>
                handle.cancel("Beat-game pathfinding was interrupted").pipe(Effect.ignore),
              ),
            ),
        ),
        Effect.asVoid,
        Effect.mapError(mapError(operation)),
      );

  const runTask = (
    task: BeatGameTask,
    policy: BeatGamePathPolicy,
    execution: BeatGameTaskExecutionOptions = {},
  ): Effect.Effect<unknown, BeatGameDriverError> => {
    const path = pathOptions(policy);
    const taskStart = {
      conflictPolicy: BotTaskConflictPolicy.QUEUE,
      reconnectPolicy: BotTaskReconnectPolicy.PAUSE_AND_RESUME,
      ...(execution.idempotencyKey === undefined
        ? {}
        : { idempotencyKey: execution.idempotencyKey }),
      ...(execution.deadline === undefined ? {} : { deadline: execution.deadline }),
    };
    const started: Effect.Effect<
      {
        readonly result: () => Effect.Effect<unknown, unknown>;
        readonly cancel: (reason?: string) => Effect.Effect<unknown, unknown>;
      },
      unknown
    > = (() => {
      switch (task.type) {
        case "collect-blocks": {
          return bot.tasks.collectBlocks(task.blockIds, {
            ...taskStart,
            tags: task.tags ?? [],
            count: task.count,
            searchRadius: task.searchRadius,
            avoidSubmergedTargets: task.avoidSubmergedTargets ?? false,
            requireLineOfSight: task.requireLineOfSight ?? false,
            ...(task.targetYRange === undefined ? {} : { targetYRange: task.targetYRange }),
            path,
          });
        }
        case "excavate": {
          return bot.tasks.excavate(blockPosition(task.from), blockPosition(task.to), {
            ...taskStart,
            path,
            ...(task.maximumBlocks === undefined ? {} : { maximumBlocks: task.maximumBlocks }),
          });
        }
        case "attack-entity": {
          return bot.tasks.attackEntity(task.target, {
            ...taskStart,
            path,
            ...(task.attackRange === undefined ? {} : { attackRange: task.attackRange }),
            ...(task.sprinting === undefined ? {} : { sprinting: task.sprinting }),
            ...(task.maximumAttacks === undefined ? {} : { maximumAttacks: task.maximumAttacks }),
            ...(task.targetUnavailableTimeoutSeconds === undefined
              ? {}
              : {
                  targetUnavailableTimeoutSeconds: task.targetUnavailableTimeoutSeconds,
                }),
            ...(task.selectBestWeapon === undefined
              ? {}
              : { selectBestWeapon: task.selectBestWeapon }),
            ...(task.weapon === undefined ? {} : { weapon: itemSelector(task.weapon) }),
            ...(task.restoreSelectedSlot === undefined
              ? {}
              : { restoreSelectedSlot: task.restoreSelectedSlot }),
            ...(task.useOffhandShield === undefined
              ? {}
              : { useOffhandShield: task.useOffhandShield }),
          });
        }
        case "ranged-attack": {
          return bot.tasks.rangedAttack(task.target, {
            ...taskStart,
            path,
            ...(task.minimumRange === undefined ? {} : { minimumRange: task.minimumRange }),
            ...(task.maximumRange === undefined ? {} : { maximumRange: task.maximumRange }),
            ...(task.maximumShots === undefined ? {} : { maximumShots: task.maximumShots }),
            ...(task.targetUnavailableTimeoutSeconds === undefined
              ? {}
              : {
                  targetUnavailableTimeoutSeconds: task.targetUnavailableTimeoutSeconds,
                }),
            ...(task.weapon === undefined ? {} : { weapon: itemSelector(task.weapon) }),
            ...(task.bowDrawTicks === undefined ? {} : { bowDrawTicks: task.bowDrawTicks }),
            ...(task.leadTarget === undefined ? {} : { leadTarget: task.leadTarget }),
            ...(task.compensateGravity === undefined
              ? {}
              : { compensateGravity: task.compensateGravity }),
            ...(task.strafe === undefined ? {} : { strafe: task.strafe }),
            ...(task.restoreSelectedSlot === undefined
              ? {}
              : { restoreSelectedSlot: task.restoreSelectedSlot }),
          });
        }
        case "attack-nearest": {
          return bot.tasks.attackNearest(entitySelector(task.selector), {
            ...taskStart,
            path,
            radius: task.radius,
            ...(task.attackRange === undefined ? {} : { attackRange: task.attackRange }),
            ...(task.sprinting === undefined ? {} : { sprinting: task.sprinting }),
            ...(task.maximumAttacks === undefined ? {} : { maximumAttacks: task.maximumAttacks }),
            ...(task.maximumTargets === undefined ? {} : { maximumTargets: task.maximumTargets }),
            ...(task.noTargetTimeoutSeconds === undefined
              ? {}
              : { noTargetTimeoutSeconds: task.noTargetTimeoutSeconds }),
            ...(task.completeWhenNoTarget === undefined
              ? {}
              : { completeWhenNoTarget: task.completeWhenNoTarget }),
            ...(task.selectBestWeapon === undefined
              ? {}
              : { selectBestWeapon: task.selectBestWeapon }),
            ...(task.weapon === undefined ? {} : { weapon: itemSelector(task.weapon) }),
            ...(task.restoreSelectedSlot === undefined
              ? {}
              : { restoreSelectedSlot: task.restoreSelectedSlot }),
          });
        }
        case "flee": {
          return bot.tasks.flee(entitySelector(task.selector), {
            ...taskStart,
            path,
            ...(task.triggerRadius === undefined ? {} : { triggerRadius: task.triggerRadius }),
            ...(task.safeDistance === undefined ? {} : { safeDistance: task.safeDistance }),
            ...(task.safeSeconds === undefined ? {} : { safeSeconds: task.safeSeconds }),
            ...(task.completeWhenSafe === undefined
              ? {}
              : { completeWhenSafe: task.completeWhenSafe }),
            ...(task.maximumEscapes === undefined ? {} : { maximumEscapes: task.maximumEscapes }),
          });
        }
        case "guard": {
          return bot.tasks.guard(blockPosition(task.position), entitySelector(task.selector), {
            ...taskStart,
            path,
            ...(task.guardRadius === undefined ? {} : { guardRadius: task.guardRadius }),
            ...(task.maximumPursuitDistance === undefined
              ? {}
              : {
                  maximumPursuitDistance: task.maximumPursuitDistance,
                }),
            ...(task.returnRadius === undefined ? {} : { returnRadius: task.returnRadius }),
            ...(task.attackRange === undefined ? {} : { attackRange: task.attackRange }),
            ...(task.sprinting === undefined ? {} : { sprinting: task.sprinting }),
            ...(task.maximumAttacks === undefined ? {} : { maximumAttacks: task.maximumAttacks }),
            ...(task.maximumTargets === undefined ? {} : { maximumTargets: task.maximumTargets }),
            ...(task.completeWhenClear === undefined
              ? {}
              : { completeWhenClear: task.completeWhenClear }),
            ...(task.clearSeconds === undefined ? {} : { clearSeconds: task.clearSeconds }),
            ...(task.selectBestWeapon === undefined
              ? {}
              : { selectBestWeapon: task.selectBestWeapon }),
            ...(task.weapon === undefined ? {} : { weapon: itemSelector(task.weapon) }),
            ...(task.restoreSelectedSlot === undefined
              ? {}
              : { restoreSelectedSlot: task.restoreSelectedSlot }),
          });
        }
        case "fish": {
          return bot.tasks.fish({
            ...taskStart,
            ...(task.maximumCatches === undefined ? {} : { maximumCatches: task.maximumCatches }),
            ...(task.maximumFailedCasts === undefined
              ? {}
              : { maximumFailedCasts: task.maximumFailedCasts }),
          });
        }
        case "farm": {
          return bot.tasks.farm({
            ...taskStart,
            cropIds: task.cropIds ?? [],
            ...(task.center === undefined ? {} : { center: blockPosition(task.center) }),
            ...(task.radius === undefined ? {} : { radius: task.radius }),
            ...(task.maximumHarvests === undefined
              ? {}
              : { maximumHarvests: task.maximumHarvests }),
            path,
          });
        }
        case "breed": {
          return bot.tasks.breed({
            ...taskStart,
            ...(task.selector === undefined ? {} : { animals: entitySelector(task.selector) }),
            ...(task.food === undefined ? {} : { food: itemSelector(task.food) }),
            ...(task.maximumPairs === undefined ? {} : { maximumPairs: task.maximumPairs }),
            path,
          });
        }
        case "explore": {
          return bot.tasks.explore({
            ...taskStart,
            ...(task.origin === undefined ? {} : { origin: blockPosition(task.origin) }),
            radius: task.radius,
            ...(task.maximumWaypoints === undefined
              ? {}
              : { maximumWaypoints: task.maximumWaypoints }),
            ...(task.purpose === undefined ? {} : { purpose: task.purpose }),
            path,
          });
        }
        case "transfer-container": {
          const operations = task.operations.map((operation) => ({
            selector: itemSelector(operation.selector),
            count: operation.count,
            ...(operation.allowPartial === undefined
              ? {}
              : { allowPartial: operation.allowPartial }),
          }));
          return task.direction === "deposit"
            ? bot.tasks.stash(blockPosition(task.container), operations, {
                ...taskStart,
                path,
              })
            : bot.tasks.withdraw(blockPosition(task.container), operations, {
                ...taskStart,
                path,
              });
        }
        case "maintain-loadout": {
          return bot.tasks.maintainLoadout(
            blockPosition(task.container),
            task.requirements.map((requirement) => ({
              selector: itemSelector(requirement.selector),
              minimumCount: requirement.minimumCount,
              targetCount: requirement.targetCount,
              ...(requirement.maximumCount === undefined
                ? {}
                : { maximumCount: requirement.maximumCount }),
            })),
            { ...taskStart, path },
          );
        }
        case "auto-eat": {
          return bot.tasks.autoEat(task.foodItemIds ?? [], {
            ...taskStart,
            foodLevel: task.foodLevel,
            ...(task.maximumMeals === undefined ? {} : { maximumMeals: task.maximumMeals }),
            ...(task.completeWhenNoFood === undefined
              ? {}
              : { completeWhenNoFood: task.completeWhenNoFood }),
            ...(task.restoreSelectedSlot === undefined
              ? {}
              : { restoreSelectedSlot: task.restoreSelectedSlot }),
          });
        }
        case "auto-respawn": {
          return bot.tasks.autoRespawn({
            ...taskStart,
            ...(task.maximumRespawns === undefined
              ? {}
              : { maximumRespawns: task.maximumRespawns }),
          });
        }
        case "auto-totem": {
          return bot.tasks.autoTotem(taskStart);
        }
        case "auto-armor": {
          return bot.tasks.autoArmor({
            ...taskStart,
            ...(task.maximumEquips === undefined ? {} : { maximumEquips: task.maximumEquips }),
            ...(task.completeWhenNoUpgrade === undefined
              ? {}
              : { completeWhenNoUpgrade: task.completeWhenNoUpgrade }),
          });
        }
        case "sleep": {
          return bot.tasks.sleep({
            ...taskStart,
            path,
            ...(task.bed === undefined ? {} : { bed: blockPosition(task.bed) }),
            ...(task.searchRadius === undefined ? {} : { searchRadius: task.searchRadius }),
            ...(task.waitUntilPossible === undefined
              ? {}
              : { waitUntilPossible: task.waitUntilPossible }),
            ...(task.retryIntervalTicks === undefined
              ? {}
              : { retryIntervalTicks: task.retryIntervalTicks }),
          });
        }
        case "build": {
          return bot.tasks.build(
            blockPosition(task.origin),
            task.blocks.map((block): SchematicBlock => ({
              offset: { ...block.offset },
              blockId: block.blockId,
              ...(block.properties === undefined ? {} : { properties: block.properties }),
            })),
            {
              ...taskStart,
              path,
              ...(task.partitionIndex === undefined ? {} : { partitionIndex: task.partitionIndex }),
              ...(task.partitionCount === undefined ? {} : { partitionCount: task.partitionCount }),
            },
          );
        }
        case "craft": {
          return bot.tasks.craft(task.recipeId, task.count, {
            ...taskStart,
            ...(task.station === undefined ? {} : { station: blockPosition(task.station) }),
          });
        }
        case "smelt": {
          return bot.tasks.smelt(itemSelector(task.input), task.count, {
            ...taskStart,
            ...(task.fuel === undefined ? {} : { fuel: itemSelector(task.fuel) }),
            ...(task.station === undefined ? {} : { station: blockPosition(task.station) }),
          });
        }
        case "brew": {
          return bot.tasks.brew(
            itemSelector(task.input),
            itemSelector(task.ingredient),
            task.count,
            {
              ...taskStart,
              ...(task.fuel === undefined ? {} : { fuel: itemSelector(task.fuel) }),
              ...(task.station === undefined ? {} : { station: blockPosition(task.station) }),
              ...(task.expectedResult === undefined
                ? {}
                : { expectedResult: itemSelector(task.expectedResult) }),
            },
          );
        }
        case "trade": {
          return bot.tasks.villagerTrade(task.offerIndex, task.count, {
            ...taskStart,
            ...(task.expectedResult === undefined
              ? {}
              : { expectedResult: itemSelector(task.expectedResult) }),
          });
        }
        default: {
          return task satisfies never;
        }
      }
    })();
    return started.pipe(
      Effect.flatMap((handle) =>
        handle
          .result()
          .pipe(
            Effect.onInterrupt(() =>
              handle.cancel("Beat-game action was interrupted").pipe(Effect.ignore),
            ),
          ),
      ),
      Effect.mapError(mapError(`task.${task.type}`)),
    );
  };

  return {
    instanceId: bot.instanceId,
    botId: bot.id,
    observe,
    ...(options.environment === undefined ? {} : { environment: options.environment }),
    events: bot.events().pipe(
      Stream.map((payload): BeatGameDriverEvent => {
        const observedAt = new Date().toISOString();
        if (payload.event.case === "lifecycle") {
          if (payload.event.value.kind === BotLifecycleKind.BOT_LIFECYCLE_DIED) {
            return {
              type: "bot-died",
              observedAt,
              ...(payload.event.value.message === undefined
                ? {}
                : { message: payload.event.value.message }),
            };
          }
          if (payload.event.value.kind === BotLifecycleKind.BOT_LIFECYCLE_RESPAWNED) {
            return { type: "bot-respawned", observedAt };
          }
        }
        if (payload.event.case === "damage") {
          const { amount, health, damageType } = payload.event.value;
          return {
            type: "bot-damaged",
            observedAt,
            amount,
            health,
            ...(damageType === undefined ? {} : { damageType }),
          };
        }
        if (
          payload.event.case === "environment" &&
          payload.event.value.change.case === "gameEvent"
        ) {
          return {
            type: "game-event",
            observedAt,
            event: payload.event.value.change.value.event,
            parameter: payload.event.value.change.value.parameter,
          };
        }
        return {
          type: "bot-event",
          observedAt,
          payload,
        };
      }),
      Stream.mapError(mapError("events")),
    ),
    // The server returns at most 500 blocks a page. A live bot asked for
    // 4,096 blocks around it to find a bed spot, got the nearest 500, saw
    // no spot whose blocks were all known, and went into the End with no
    // respawn point.
    queryBlocks: (query) =>
      Effect.gen(function* () {
        const maximumResults = query.maximumResults ?? 128;
        const blocks: BeatGameBlockObservation[] = [];
        let pageToken = "";
        do {
          const page = yield* bot.world.queryBlocks({
            region: {
              region: {
                case: "sphere",
                value: {
                  center: query.center,
                  radius: query.radius,
                },
              },
            },
            selector: blockSelector(query.selector),
            sort: QuerySort.NEAREST,
            pageSize: Math.min(maximumResults - blocks.length, BLOCK_QUERY_PAGE_SIZE),
            pageToken,
          });
          blocks.push(...page.blocks.map((block) => toBeatGameBlockObservation(block)));
          pageToken = page.nextPageToken;
        } while (pageToken !== "" && blocks.length < maximumResults);
        return blocks;
      }).pipe(Effect.mapError(mapError("queryBlocks"))),
    queryEntities: (query) =>
      bot.world
        .queryEntities({
          ...(query.origin === undefined ? {} : { origin: query.origin }),
          radius: query.radius,
          selector: entitySelector(query.selector),
          sort: QuerySort.NEAREST,
          pageSize: query.maximumResults ?? 128,
        })
        .pipe(
          Effect.mapBoth({
            onSuccess: ({ entities }) =>
              entities.map((entity): BeatGameEntityObservation => {
                const reference = required(entity.reference, "entity.reference");
                const velocity = required(entity.velocity, "entity.velocity");
                const creeper =
                  entity.entityType === "minecraft:creeper"
                    ? creeperObservation(entity.metadata)
                    : undefined;
                const sheep =
                  entity.entityType === "minecraft:sheep"
                    ? sheepObservation(entity.metadata)
                    : undefined;
                return {
                  connectionEpoch: reference.connectionEpoch,
                  networkId: reference.networkId,
                  ...(reference.uuid === undefined ? {} : { uuid: reference.uuid }),
                  entityType: entity.entityType,
                  position: toPosition(required(entity.position, "entity.position")),
                  velocity: {
                    x: velocity.x,
                    y: velocity.y,
                    z: velocity.z,
                  },
                  alive: entity.alive,
                  ...(entity.health === undefined ? {} : { health: entity.health }),
                  ...(entity.item === undefined ? {} : { itemId: entity.item.itemId }),
                  ...(entity.target === undefined
                    ? {}
                    : {
                        target: {
                          connectionEpoch: entity.target.connectionEpoch,
                          networkId: entity.target.networkId,
                          ...(entity.target.uuid === undefined ? {} : { uuid: entity.target.uuid }),
                        },
                      }),
                  ...(entity.aggressive === undefined ? {} : { aggressive: entity.aggressive }),
                  ...(creeper === undefined ? {} : { creeper }),
                  ...(sheep === undefined ? {} : { sheep }),
                  ...(entity.rotation === undefined
                    ? {}
                    : { rotation: { yaw: entity.rotation.yaw, pitch: entity.rotation.pitch } }),
                  observedAt: new Date().toISOString(),
                };
              }),
            onFailure: mapError("queryEntities"),
          }),
        ),
    raycast: (query) =>
      bot.world
        .raycast({
          direction: query.direction,
          maximumDistance: query.maximumDistance,
          includeFluids: query.includeFluids ?? false,
          includeEntities: false,
        })
        .pipe(
          Effect.mapBoth({
            onSuccess: (response): BeatGameRaycastObservation => ({
              ...(response.block === undefined
                ? {}
                : { block: toBeatGameBlockObservation(response.block) }),
              distance: response.distance,
            }),
            onFailure: mapError("raycast"),
          }),
        ),
    sampleSurface: (center, radius = 8, sampleStep = 2) =>
      bot.camera
        .worldMap({
          centerX: Math.floor(center.x),
          centerZ: Math.floor(center.z),
          radius,
          sampleStep,
        })
        .pipe(
          Effect.mapBoth({
            onSuccess: ({ columns }) =>
              columns.map((column): BeatGameSurfaceColumn => ({
                x: column.x,
                z: column.z,
                loaded: column.loaded,
                ...(column.surfaceY === undefined ? {} : { surfaceY: column.surfaceY }),
                ...(column.blockId === undefined ? {} : { blockId: column.blockId }),
                ...(column.biomeId === undefined ? {} : { biomeId: column.biomeId }),
                skyLight: column.skyLight,
                blockLight: column.blockLight,
              })),
            onFailure: mapError("sampleSurface"),
          }),
        ),
    recipesFor: (resultItemId) =>
      bot.recipes
        .list({
          resultItemId,
          pageSize: 256,
        })
        .pipe(
          Effect.mapBoth({
            onSuccess: ({ recipes }) =>
              recipes.map((recipe): BeatGameRecipe => ({
                recipeId: recipe.recipeId,
                recipeType: recipe.recipeType,
                resultItemId: required(recipe.result, "recipe.result").itemId,
                resultCount: required(recipe.result, "recipe.result").count,
                ingredients: recipe.ingredients.map((ingredient) => ({
                  itemIds: ingredient.itemIds,
                  tags: ingredient.tags,
                  count: ingredient.count,
                })),
              })),
            onFailure: mapError("recipesFor"),
          }),
        ),
    canCraft: (recipeId, count) =>
      bot.recipes.canCraft({ recipeId, count }).pipe(
        Effect.mapBoth({
          onSuccess: (response): BeatGameCraftability => ({
            canCraft: response.canCraft,
            maximumCraftCount: response.maximumCraftCount,
            ...(response.requiredStation === undefined
              ? {}
              : { requiredStation: response.requiredStation }),
            missing: response.missing.map((missing) => {
              const ingredient = required(missing.ingredient, "missing.ingredient");
              return {
                itemIds: ingredient.itemIds,
                tags: ingredient.tags,
                available: missing.available,
                missing: missing.missing,
              };
            }),
          }),
          onFailure: mapError("canCraft"),
        }),
      ),
    waitForChunks: (radiusChunks = 0, timeoutMs = 60_000) =>
      bot
        .waitForChunks({
          radiusChunks,
          timeoutMs,
        })
        .pipe(Effect.asVoid, Effect.mapError(mapError("waitForChunks"))),
    pathfind: (position, radius, policy) =>
      recenterAfterActionStall(
        withQualityBoundFallback(policy, (attemptPolicy) =>
          goToGoal(goals.near(position, radius), attemptPolicy, "pathfind"),
        ),
      ),
    pathfindXZ: (x, z, dimension, radius, policy) =>
      recenterAfterActionStall(
        withQualityBoundFallback(policy, (attemptPolicy) =>
          goToGoal(goals.xz(x, z, { dimension, radius }), attemptPolicy, "pathfindXZ"),
        ),
      ),
    runTask,
    act: (action) =>
      executePrimitive(bot, action).pipe(Effect.mapError(mapError(`act.${action.type}`))),
    withControl: withSharedControl,
  };
}

function creeperObservation(
  metadata: Readonly<Record<string, unknown>> | undefined,
): BeatGameEntityObservation["creeper"] | undefined {
  const fuseProgress = finiteMetadataNumber(metadata, "creeper_fuse_progress");
  const swellDirection = finiteMetadataNumber(metadata, "creeper_swell_direction");
  const powered = metadata?.["creeper_powered"];
  const ignited = metadata?.["creeper_ignited"];
  return fuseProgress === undefined ||
    swellDirection === undefined ||
    typeof powered !== "boolean" ||
    typeof ignited !== "boolean"
    ? undefined
    : {
        fuseProgress: Math.max(0, fuseProgress),
        swellDirection,
        powered,
        ignited,
      };
}

function sheepObservation(
  metadata: Readonly<Record<string, unknown>> | undefined,
): BeatGameEntityObservation["sheep"] | undefined {
  const color = metadata?.["sheep_color"];
  const sheared = metadata?.["sheep_sheared"];
  return typeof color !== "string" || typeof sheared !== "boolean" ? undefined : { color, sheared };
}

function finiteMetadataNumber(
  metadata: Readonly<Record<string, unknown>> | undefined,
  key: string,
): number | undefined {
  const value = metadata?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function executePrimitive(
  bot: SoulFireBot,
  action: BeatGamePrimitiveAction,
): Effect.Effect<unknown, unknown> {
  switch (action.type) {
    case "look": {
      return bot.look(action.yaw, action.pitch);
    }
    case "equip-item": {
      return bot.inventory.equip({
        selector: itemSelector(action.selector),
        equipmentSlot: action.equipmentSlot,
      });
    }
    case "select-item": {
      return bot.inventory.selectHotbar({
        selection: {
          case: "selector",
          value: itemSelector(action.selector),
        },
      });
    }
    case "toss-items": {
      return bot.inventory.toss({
        selector: itemSelector(action.selector),
        count: action.count,
      });
    }
    case "use-item": {
      return bot.useItem({
        hand: hand(action.hand),
        ...(action.retryTicks === undefined ? {} : { retryTicks: action.retryTicks }),
      });
    }
    case "release-item": {
      return bot.releaseItem();
    }
    case "dig-block": {
      return bot.digBlock({ position: blockPosition(action.position) });
    }
    case "place-block": {
      return bot.placeBlock({
        against: blockPosition(action.against),
        face: blockFace(action.face),
        hand: hand(action.hand),
      });
    }
    case "interact-block": {
      return bot.interactBlock({
        position: blockPosition(action.position),
        face: blockFace(action.face),
        hand: hand(action.hand),
        sneaking: action.sneaking ?? false,
      });
    }
    case "attack-entity": {
      return bot.attackEntity({
        connectionEpoch: action.connectionEpoch,
        entityId: action.networkId,
        sprinting: action.sprinting ?? false,
      });
    }
    case "interact-entity": {
      return bot.interactEntity({
        connectionEpoch: action.connectionEpoch,
        entityId: action.networkId,
        hand: hand(action.hand),
        sneaking: action.sneaking ?? false,
      });
    }
    case "set-movement": {
      return bot.setMovement({
        ...(action.forward === undefined ? {} : { forward: action.forward }),
        ...(action.backward === undefined ? {} : { backward: action.backward }),
        ...(action.left === undefined ? {} : { left: action.left }),
        ...(action.right === undefined ? {} : { right: action.right }),
        ...(action.jump === undefined ? {} : { jump: action.jump }),
        ...(action.sneak === undefined ? {} : { sneak: action.sneak }),
        ...(action.sprint === undefined ? {} : { sprint: action.sprint }),
      });
    }
    case "respawn": {
      return bot.respawn();
    }
    case "reset-movement": {
      return bot.resetMovement();
    }
    case "close-container": {
      return bot.closeContainer();
    }
    default: {
      return action satisfies never;
    }
  }
}

function itemSelector(selector: BeatGameItemSelector) {
  return {
    itemIds: [...(selector.itemIds ?? [])],
    tags: [...(selector.tags ?? [])],
    ...(selector.minimumCount === undefined ? {} : { minimumCount: selector.minimumCount }),
  };
}

function entitySelector(selector: BeatGameEntitySelector) {
  return {
    entityTypes: [...(selector.entityTypes ?? [])],
    tags: [...(selector.tags ?? [])],
    categories: [...(selector.categories ?? [])],
    ...(selector.uuid === undefined ? {} : { uuid: selector.uuid }),
    ...(selector.networkId === undefined ? {} : { networkId: selector.networkId }),
    ...(selector.alive === undefined ? {} : { alive: selector.alive }),
    requireLineOfSight: selector.requireLineOfSight ?? false,
  };
}

function blockSelector(selector: BeatGameBlockSelector) {
  return {
    blockIds: [...(selector.blockIds ?? [])],
    tags: [...(selector.tags ?? [])],
    properties: { ...selector.properties },
    ...(selector.solid === undefined ? {} : { solid: selector.solid }),
    ...(selector.replaceable === undefined ? {} : { replaceable: selector.replaceable }),
    ...(selector.interactive === undefined ? {} : { interactive: selector.interactive }),
    ...(selector.diggable === undefined ? {} : { diggable: selector.diggable }),
    requireLineOfSight: selector.requireLineOfSight ?? false,
  };
}

function hand(value: BeatGameHand | undefined): Hand {
  return value === "off" ? Hand.OFF : Hand.MAIN;
}

function blockFace(value: BeatGameBlockFace): BlockFace {
  switch (value) {
    case "down": {
      return BlockFace.DOWN;
    }
    case "up": {
      return BlockFace.UP;
    }
    case "north": {
      return BlockFace.NORTH;
    }
    case "south": {
      return BlockFace.SOUTH;
    }
    case "west": {
      return BlockFace.WEST;
    }
    case "east": {
      return BlockFace.EAST;
    }
    default: {
      return value satisfies never;
    }
  }
}

function toPosition(
  value: Readonly<{ x: number; y: number; z: number; dimension: string }>,
): BeatGamePosition {
  return {
    x: value.x,
    y: value.y,
    z: value.z,
    dimension: value.dimension,
  };
}

function toBeatGameBlockObservation(block: BlockSnapshot): BeatGameBlockObservation {
  return {
    blockId: block.blockId,
    position: toBlockPosition(required(block.position, "block.position")),
    properties: block.properties,
    hardness: block.hardness,
    diggable: block.diggable,
    replaceable: block.replaceable,
    solid: block.solid,
    interactive: block.interactive,
    effectiveToolTags: block.effectiveToolTags,
    observedAt: new Date().toISOString(),
  };
}

function toBlockPosition(
  value: Readonly<{ x: number; y: number; z: number; dimension: string }>,
): BeatGameBlockPosition {
  return blockPosition(value);
}

function blockPosition(
  value: Readonly<{ x: number; y: number; z: number; dimension: string }>,
): BeatGameBlockPosition {
  return {
    x: Math.floor(value.x),
    y: Math.floor(value.y),
    z: Math.floor(value.z),
    dimension: value.dimension,
  };
}

function required<T>(value: T | undefined, name: string): T {
  if (value === undefined) {
    throw new Error(`SoulFire omitted required ${name}`);
  }
  return value;
}

// A route that only misses the requested quality bound still exists, and a
// search that runs out of states may finish with a looser bound. A live
// stronghold search ended the whole run after five quality misses on
// SoulFire's default bound of 1.2, and another ended it at the state limit
// on its way down. The route is tried once more in urgent mode with a
// looser bound, and if that fails too the failure stays retryable: another
// start point or a later attempt may find the way.
const QUALITY_BOUND_FALLBACK = 3;
// SoulFire reports a search that ran out of states as "path_search_limit".
const ROUTE_SEARCH_LIMIT_CODES: ReadonlySet<string> = new Set([
  "path_quality_bound_not_met",
  "path_search_limit",
  "search_limit",
]);

/// A route that keeps failing on the same move, or keeps ending partial
/// routes without getting anywhere.
const ROUTE_STALL_FAILURE_CODES: ReadonlySet<string> = new Set([
  "path_action_stalled",
  "path_stalled",
]);

/// The movement keys that walk the bot along `offset` from the way it
/// faces, in eight directions as SoulFire's own path movement picks them.
function movementKeysToward(
  offset: { readonly x: number; readonly z: number },
  yaw: number,
): { forward: boolean; backward: boolean; left: boolean; right: boolean } {
  const targetYaw = (Math.atan2(offset.z, offset.x) * 180) / Math.PI - 90;
  const relativeYaw = ((((targetYaw - yaw + 180) % 360) + 360) % 360) - 180;
  const direction = ((Math.round(relativeYaw / 45) % 8) + 8) % 8;
  return {
    forward: direction === 0 || direction === 1 || direction === 7,
    backward: direction === 3 || direction === 4 || direction === 5,
    left: direction === 5 || direction === 6 || direction === 7,
    right: direction === 1 || direction === 2 || direction === 3,
  };
}

function blockCenterOffset(observation: BeatGameObservation): { x: number; z: number } {
  const { x, z } = observation.player.position;
  return { x: Math.floor(x) + 0.5 - x, z: Math.floor(z) + 0.5 - z };
}
const BLOCK_CENTER_TOLERANCE = 0.2;
const BLOCK_CENTER_STEP_POLL_MS = 50;
const BLOCK_CENTER_STEP_POLLS = 40;

function withQualityBoundFallback(
  policy: BeatGamePathPolicy,
  attempt: (policy: BeatGamePathPolicy) => Effect.Effect<void, BeatGameDriverError>,
): Effect.Effect<void, BeatGameDriverError> {
  return attempt(policy).pipe(
    Effect.catchIf(
      (error) =>
        isSearchLimit(error) &&
        (policy.searchMode !== BeatGamePathSearchMode.URGENT ||
          (policy.maximumQualityBound ?? 0) < QUALITY_BOUND_FALLBACK),
      () =>
        attempt({
          ...policy,
          searchMode: BeatGamePathSearchMode.URGENT,
          maximumQualityBound: Math.max(policy.maximumQualityBound ?? 0, QUALITY_BOUND_FALLBACK),
        }),
    ),
    Effect.mapError((error) =>
      isSearchLimit(error) && !error.retryable
        ? new BeatGameDriverError({
            operation: error.operation,
            ...(error.code === undefined ? {} : { code: error.code }),
            retryable: true,
            message: error.message,
            ...(error.cause === undefined ? {} : { cause: error.cause }),
          })
        : error,
    ),
  );
}

function isSearchLimit(error: BeatGameDriverError): boolean {
  return error.code !== undefined && ROUTE_SEARCH_LIMIT_CODES.has(error.code);
}

function driverError(operation: string, cause: unknown): BeatGameDriverError {
  const code = taskFailureCode(cause);
  return new BeatGameDriverError({
    operation,
    ...(code === undefined ? {} : { code }),
    retryable: isRetryable(cause),
    message: cause instanceof Error ? cause.message : `SoulFire ${operation} failed`,
    cause,
  });
}

function taskFailureCode(cause: unknown): string | undefined {
  if (typeof cause !== "object" || cause === null) {
    return undefined;
  }
  if (
    "task" in cause &&
    typeof cause.task === "object" &&
    cause.task !== null &&
    "failure" in cause.task &&
    typeof cause.task.failure === "object" &&
    cause.task.failure !== null &&
    "code" in cause.task.failure &&
    typeof cause.task.failure.code === "string" &&
    cause.task.failure.code.length > 0
  ) {
    return cause.task.failure.code;
  }
  if ("code" in cause) {
    const code = failureCode(cause.code);
    if (code !== undefined) {
      return code;
    }
  }
  // A failed bot action carries its status only in its message, which starts
  // with the status name, such as "OUT_OF_RANGE: Target block is outside the
  // bot's interaction reach".
  if (
    "result" in cause &&
    typeof cause.result === "object" &&
    cause.result !== null &&
    "error" in cause.result &&
    typeof cause.result.error === "string"
  ) {
    const status = /^([A-Z][A-Z_]*):/u.exec(cause.result.error)?.[1];
    if (status !== undefined) {
      return status.toLowerCase();
    }
  }
  return "cause" in cause && cause.cause !== cause ? taskFailureCode(cause.cause) : undefined;
}

function failureCode(value: unknown): string | undefined {
  if (typeof value === "string" && value.length > 0) {
    return value;
  }
  if (!Number.isSafeInteger(value) || Number(value) <= 0) {
    return undefined;
  }
  return RPC_STATUS_CODE_NAMES[Number(value)];
}

// A placement that is not ready depends on where the bot stands and what it
// can see, both of which change as it moves. Two live runs exited when a
// placement face was occluded from the bot's eye.
const PLACEMENT_NOT_READY_PREFIX = "FAILED_PRECONDITION: Block placement is not ready";
// SoulFire reconnects a dropped bot on its own. A live run ended when its
// Minecraft server restarted, though the bot would have come back.
const BOT_OFFLINE_MESSAGE = "Bot is not online";
// A dropped connection to SoulFire passes. A live run ended on one reset
// entity query while the server kept running.
const TRANSPORT_FAILURE_PATTERN = /Transport error|ECONNRESET|ECONNREFUSED|EPIPE|socket hang up/u;
// A bot that rejoins after a server restart has no level for a moment. A
// live run ended on one such observation while the server came back.
const LEVEL_NOT_LOADED_MESSAGE = "Bot level is not loaded";

function isRetryable(cause: unknown): boolean {
  if (typeof cause !== "object" || cause === null) {
    return false;
  }
  if ("retryable" in cause && cause.retryable === true) {
    return true;
  }
  if (
    cause instanceof Error &&
    (cause.message.startsWith(PLACEMENT_NOT_READY_PREFIX) ||
      cause.message.includes(BOT_OFFLINE_MESSAGE) ||
      cause.message.includes(LEVEL_NOT_LOADED_MESSAGE) ||
      TRANSPORT_FAILURE_PATTERN.test(cause.message))
  ) {
    return true;
  }
  if (
    "task" in cause &&
    typeof cause.task === "object" &&
    cause.task !== null &&
    "failure" in cause.task &&
    typeof cause.task.failure === "object" &&
    cause.task.failure !== null &&
    "retryable" in cause.task.failure
  ) {
    return cause.task.failure.retryable === true;
  }
  return "cause" in cause && cause.cause !== cause && isRetryable(cause.cause);
}
