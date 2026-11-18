import { Effect, Stream } from "effect";

import {
  BEAT_GAME_CHECKPOINT_SCHEMA_VERSION,
  type BeatGamePhase,
  BeatGameRunStatus,
  BeatGameTeamRole,
  emptyBeatGameWorldMemory,
  objectiveForPhase,
  type BeatGameBlockObservation,
  type BeatGameBlockPosition,
  type BeatGameCheckpoint,
  type BeatGameDriver,
  type BeatGameDriverError,
  type BeatGameEntityObservation,
  type BeatGameObservation,
  type BeatGamePathPolicy,
  type BeatGamePosition,
  type BeatGamePrimitiveAction,
  type BeatGameQueryBlocks,
  type BeatGameQueryEntities,
  type BeatGameRaycastObservation,
  type BeatGameRaycastQuery,
  type BeatGameRecipe,
  type BeatGameSurfaceColumn,
  type BeatGameEnvironmentObservation,
  type BeatGameTask,
  type BeatGameCraftability,
  type BeatGameTaskExecutionOptions,
  type BeatGameStrategyHooks,
} from "../src/index.js";

/** Returns `value`, or throws when something the test depends on is missing. */
export function required<T>(value: T | null | undefined, what: string): T {
  if (value === undefined || value === null) {
    throw new Error(`Expected ${what} to be present`);
  }
  return value;
}

export function blockObservation(
  position: BeatGameBlockPosition,
  overrides: Partial<Omit<BeatGameBlockObservation, "position">> = {},
): BeatGameBlockObservation {
  return {
    blockId: "minecraft:stone",
    position,
    properties: {},
    hardness: 1.5,
    diggable: true,
    replaceable: false,
    solid: true,
    interactive: false,
    effectiveToolTags: ["minecraft:mineable/pickaxe"],
    observedAt: "2026-01-01T00:00:00.000Z",
    ...overrides,
  };
}

export function observation(
  overrides: {
    readonly dimension?: string;
    readonly dead?: boolean;
    readonly observedAt?: string;
    readonly counts?: Readonly<Record<string, number>>;
    readonly remainingDurability?: Readonly<Record<string, number>>;
    readonly position?: Partial<BeatGamePosition>;
    readonly rotation?: Partial<BeatGameObservation["player"]["rotation"]>;
    readonly velocity?: Partial<BeatGameObservation["player"]["velocity"]>;
    readonly onGround?: boolean;
    readonly equipment?: Readonly<Record<string, string>>;
    readonly connectionEpoch?: string;
    readonly food?: number;
    readonly health?: number;
    readonly air?: number;
    readonly maxAir?: number;
    readonly fireTicks?: number;
    readonly emptyPlayerSlots?: number;
    readonly pathBuildingBlockCount?: number;
  } = {},
): BeatGameObservation {
  const dimension = overrides.dimension ?? "minecraft:overworld";
  return {
    observedAt: overrides.observedAt ?? "2026-01-01T00:00:00.000Z",
    player: {
      position: {
        x: overrides.position?.x ?? 0,
        y: overrides.position?.y ?? 64,
        z: overrides.position?.z ?? 0,
        dimension: overrides.position?.dimension ?? dimension,
      },
      rotation: {
        yaw: overrides.rotation?.yaw ?? 0,
        pitch: overrides.rotation?.pitch ?? 0,
      },
      velocity: {
        x: overrides.velocity?.x ?? 0,
        y: overrides.velocity?.y ?? 0,
        z: overrides.velocity?.z ?? 0,
      },
      onGround: overrides.onGround ?? true,
      equipment: overrides.equipment ?? {},
      health: overrides.health ?? 20,
      maxHealth: 20,
      food: overrides.food ?? 20,
      air: overrides.air ?? overrides.maxAir ?? 300,
      maxAir: overrides.maxAir ?? 300,
      fireTicks: overrides.fireTicks ?? 0,
      dead: overrides.dead ?? false,
      sleeping: false,
      usingItem: false,
      connectionEpoch: overrides.connectionEpoch ?? "epoch-1",
      revision: 1n,
    },
    inventory: {
      revision: 1n,
      selectedHotbarSlot: 0,
      ...(overrides.emptyPlayerSlots === undefined
        ? {}
        : { emptyPlayerSlots: overrides.emptyPlayerSlots }),
      ...(overrides.pathBuildingBlockCount === undefined
        ? {}
        : {
            pathBuildingBlockCount: overrides.pathBuildingBlockCount,
          }),
      counts: overrides.counts ?? {},
      ...(overrides.remainingDurability === undefined
        ? {}
        : { remainingDurability: overrides.remainingDurability }),
      hotbar: {},
    },
  };
}

export function checkpoint(
  phase: BeatGamePhase,
  overrides: Partial<BeatGameCheckpoint> = {},
): BeatGameCheckpoint {
  const now = "2026-01-01T00:00:00.000Z";
  return {
    schemaVersion: BEAT_GAME_CHECKPOINT_SCHEMA_VERSION,
    runId: "run-1",
    teamId: "team-1",
    instanceId: "instance-1",
    botId: "bot-1",
    role: BeatGameTeamRole.LEAD,
    revision: 1,
    connectionEpoch: "epoch-1",
    planner: {
      phase,
      status: BeatGameRunStatus.CREATED,
      objective: objectiveForPhase(phase),
      requirements: [],
      retryCount: 0,
      completedActions: [],
      startedAt: now,
      updatedAt: now,
    },
    memory: emptyBeatGameWorldMemory(),
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

export class FakeBeatGameDriver implements BeatGameDriver {
  public readonly instanceId: string;
  public readonly botId: string;
  public currentObservation: BeatGameObservation = observation();
  public blockResults: readonly BeatGameBlockObservation[] = [];
  public entityResults: readonly BeatGameEntityObservation[] = [];
  public readonly blockQueries: BeatGameQueryBlocks[] = [];
  public readonly entityQueries: BeatGameQueryEntities[] = [];
  public readonly raycasts: BeatGameRaycastQuery[] = [];
  public readonly surfaceQueries: {
    readonly center: BeatGamePosition;
    readonly radius: number;
    readonly sampleStep: number;
  }[] = [];
  public readonly tasks: BeatGameTask[] = [];
  public readonly taskExecutions: BeatGameTaskExecutionOptions[] = [];
  public readonly taskPolicies: BeatGamePathPolicy[] = [];
  public readonly actions: BeatGamePrimitiveAction[] = [];
  public readonly paths: {
    readonly position: BeatGamePosition;
    readonly radius: number;
    readonly policy: BeatGamePathPolicy;
  }[] = [];
  public readonly xzPaths: {
    readonly x: number;
    readonly z: number;
    readonly dimension: string;
    readonly radius: number;
    readonly policy: BeatGamePathPolicy;
  }[] = [];
  public surfaceColumns: readonly BeatGameSurfaceColumn[] = [];
  public currentEnvironment: BeatGameEnvironmentObservation = {
    gameTime: 6_000n,
    raining: false,
  };
  public environmentResolver: () => Effect.Effect<
    BeatGameEnvironmentObservation,
    BeatGameDriverError
  > = () => Effect.succeed(this.currentEnvironment);
  public activeControlScopes = 0;
  public maximumActiveControlScopes = 0;
  public recipeResolver: (resultItemId: string) => readonly BeatGameRecipe[] = () => [];
  public craftabilityResolver: (recipeId: string, count: number) => BeatGameCraftability = () => ({
    canCraft: false,
    maximumCraftCount: 0,
    missing: [],
  });
  public taskObserver: (task: BeatGameTask) => void = () => undefined;
  public actionObserver: (action: BeatGamePrimitiveAction) => void = () => undefined;
  public actionResolver: BeatGameDriver["act"] = (action) =>
    Effect.sync(() => {
      this.actionObserver(action);
      return {};
    });
  public taskResolver: (
    task: BeatGameTask,
    execution: BeatGameTaskExecutionOptions,
  ) => Effect.Effect<unknown, BeatGameDriverError> = (task) =>
    Effect.sync(() => {
      this.tasks.push(task);
      this.taskObserver(task);
      return {};
    });
  public blockQueryResolver: (query: BeatGameQueryBlocks) => readonly BeatGameBlockObservation[] =
    () => this.blockResults;
  public entityQueryResolver: (
    query: BeatGameQueryEntities,
  ) => readonly BeatGameEntityObservation[] = () => this.entityResults;
  public entityQueryFailureResolver: (
    query: BeatGameQueryEntities,
  ) => BeatGameDriverError | undefined = () => undefined;
  public raycastResolver: (query: BeatGameRaycastQuery) => BeatGameRaycastObservation = () => ({
    distance: 0,
  });
  public surfaceQueryResolver: (
    center: BeatGamePosition,
    radius: number,
    sampleStep: number,
  ) => readonly BeatGameSurfaceColumn[] = () => this.surfaceColumns;
  public observationResolver: () => Effect.Effect<BeatGameObservation, BeatGameDriverError> = () =>
    Effect.succeed(this.currentObservation);
  public pathResolver: BeatGameDriver["pathfind"] = (position, radius, policy) =>
    Effect.sync(() => {
      this.paths.push({ position, radius, policy });
    });
  public xzPathResolver: BeatGameDriver["pathfindXZ"] = (x, z, dimension, radius, policy) =>
    Effect.sync(() => {
      this.xzPaths.push({ x, z, dimension, radius, policy });
    });

  public constructor(instanceId = "instance-1", botId = "bot-1") {
    this.instanceId = instanceId;
    this.botId = botId;
  }

  public readonly observe = Effect.suspend(() => this.observationResolver());
  public readonly environment = Effect.suspend(() => this.environmentResolver());
  public events: BeatGameDriver["events"] = Stream.empty;

  public readonly queryBlocks: BeatGameDriver["queryBlocks"] = (query) =>
    Effect.sync(() => {
      this.blockQueries.push(query);
      return this.blockQueryResolver(query);
    });

  public readonly queryEntities: BeatGameDriver["queryEntities"] = (query) =>
    Effect.suspend(() => {
      this.entityQueries.push(query);
      const failure = this.entityQueryFailureResolver(query);
      return failure === undefined
        ? Effect.succeed(this.entityQueryResolver(query))
        : Effect.fail(failure);
    });

  public readonly raycast: BeatGameDriver["raycast"] = (query) =>
    Effect.sync(() => {
      this.raycasts.push(query);
      return this.raycastResolver(query);
    });

  public readonly sampleSurface: BeatGameDriver["sampleSurface"] = (
    center,
    radius = 8,
    sampleStep = 2,
  ) =>
    Effect.sync(() => {
      this.surfaceQueries.push({ center, radius, sampleStep });
      return this.surfaceQueryResolver(center, radius, sampleStep);
    });

  public readonly recipesFor: BeatGameDriver["recipesFor"] = (resultItemId) =>
    Effect.sync(() => this.recipeResolver(resultItemId));

  public readonly canCraft: BeatGameDriver["canCraft"] = (recipeId, count) =>
    Effect.sync(() => this.craftabilityResolver(recipeId, count));

  public readonly waitForChunks: BeatGameDriver["waitForChunks"] = () => Effect.void;

  public readonly pathfind: BeatGameDriver["pathfind"] = (position, radius, policy) =>
    this.pathResolver(position, radius, policy);

  public readonly pathfindXZ: BeatGameDriver["pathfindXZ"] = (x, z, dimension, radius, policy) =>
    this.xzPathResolver(x, z, dimension, radius, policy);

  public readonly runTask: BeatGameDriver["runTask"] = (task, policy, execution = {}) =>
    Effect.suspend(() => {
      this.taskExecutions.push(execution);
      this.taskPolicies.push(policy);
      return this.taskResolver(task, execution);
    });

  public readonly act: BeatGameDriver["act"] = (action) =>
    Effect.suspend(() => {
      this.actions.push(action);
      return this.actionResolver(action);
    });

  public readonly withControl: BeatGameDriver["withControl"] = (effect) =>
    Effect.acquireUseRelease(
      Effect.sync(() => {
        this.activeControlScopes += 1;
        this.maximumActiveControlScopes = Math.max(
          this.maximumActiveControlScopes,
          this.activeControlScopes,
        );
      }),
      () => effect,
      () =>
        Effect.sync(() => {
          this.activeControlScopes -= 1;
        }),
    );
}

export function installStaircaseMovementSimulation(
  driver: FakeBeatGameDriver,
  from: BeatGameBlockPosition,
): void {
  const resolveAction = driver.actionResolver;
  const resolveObservation = driver.observationResolver;
  const resolvePath = driver.pathResolver;
  let movingForward = false;
  driver.currentObservation = observation({
    counts: driver.currentObservation.inventory.counts,
    position: {
      x: from.x + 0.5,
      y: from.y,
      z: from.z + 0.5,
      dimension: from.dimension,
    },
  });
  driver.actionResolver = (action) =>
    resolveAction(action).pipe(
      Effect.tap(() =>
        Effect.sync(() => {
          const current = driver.currentObservation;
          if (action.type === "look") {
            driver.currentObservation = {
              ...current,
              player: {
                ...current.player,
                rotation: {
                  yaw: action.yaw,
                  pitch: action.pitch,
                },
              },
            };
            return;
          }
          if (action.type === "reset-movement") {
            movingForward = false;
            return;
          }
          if (action.type === "set-movement" && action.forward !== undefined) {
            movingForward = action.forward;
          }
        }),
      ),
    );
  driver.observationResolver = () =>
    resolveObservation().pipe(
      Effect.tap(() =>
        Effect.sync(() => {
          if (!movingForward) {
            return;
          }
          const current = driver.currentObservation;
          const radians = (current.player.rotation.yaw * Math.PI) / 180;
          const position = current.player.position;
          const x = position.x - Math.sin(radians) * 0.6;
          const z = position.z + Math.cos(radians) * 0.6;
          const enteredNextTread =
            Math.floor(x) !== Math.floor(position.x) || Math.floor(z) !== Math.floor(position.z);
          driver.currentObservation = {
            ...current,
            player: {
              ...current.player,
              position: {
                x,
                y: enteredNextTread ? position.y - 1 : position.y,
                z,
                dimension: position.dimension,
              },
            },
          };
        }),
      ),
      Effect.map(() => driver.currentObservation),
    );
  driver.pathResolver = (position, radius, policy) =>
    resolvePath(position, radius, policy).pipe(
      Effect.tap(() =>
        Effect.sync(() => {
          const current = driver.currentObservation;
          driver.currentObservation = {
            ...current,
            player: {
              ...current.player,
              position: {
                ...position,
                x: Number.isInteger(position.x) ? position.x + 0.5 : position.x,
                z: Number.isInteger(position.z) ? position.z + 0.5 : position.z,
              },
            },
          };
        }),
      ),
    );
}

export function postDragonHooks(driver: FakeBeatGameDriver): BeatGameStrategyHooks {
  const updateObservation = (
    counts: Readonly<Record<string, number>>,
    dimension = driver.currentObservation.player.position.dimension,
  ) => {
    driver.currentObservation = observation({
      counts,
      dimension,
      position: {
        ...driver.currentObservation.player.position,
        dimension,
      },
      connectionEpoch: driver.currentObservation.player.connectionEpoch,
    });
  };
  return {
    fightEnderDragon: () => Effect.succeed(true),
    satisfyRequirement: ({ requirement, observation: current }) =>
      Effect.sync(() => {
        const itemId = requirement.itemIds[0];
        if (itemId === undefined) {
          return;
        }
        updateObservation({
          ...current.inventory.counts,
          [itemId]: requirement.targetCount,
        });
      }),
    exitEnd: ({ observation: current }) =>
      Effect.sync(() => {
        updateObservation(current.inventory.counts, "minecraft:overworld");
      }),
  };
}
