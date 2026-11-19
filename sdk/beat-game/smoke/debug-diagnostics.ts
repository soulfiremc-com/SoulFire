import type { BotEnvironmentState } from "@soulfiremc/sdk";

import type {
  BeatGameBlockObservation,
  BeatGameCheckpoint,
  BeatGameEntityObservation,
  BeatGameObservation,
  BeatGamePathPolicy,
  BeatGamePosition,
  BeatGameStrategy,
} from "../src/model.js";
import type { BeatGamePlannerDecision } from "../src/planner.js";
import type { BeatGameSurfaceColumn } from "../src/driver.js";

interface DebugVector {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

export type SmokePathGoal =
  | Readonly<{
      type: "position";
      position: BeatGamePosition;
      radius: number;
    }>
  | Readonly<{
      type: "xz";
      x: number;
      z: number;
      dimension: string;
      radius: number;
    }>;

export interface SmokeActivePathTrace {
  readonly pathId: string;
  readonly startedAt: string;
  readonly fiberId?: string;
  readonly owner?: unknown;
  readonly origin: BeatGamePosition;
  readonly goal: SmokePathGoal;
  readonly policy: BeatGamePathPolicy;
}

export interface SmokeSpatialDiagnosticsInput {
  readonly origin: BeatGamePosition;
  readonly originVelocity: DebugVector;
  readonly finalPosition: BeatGamePosition;
  readonly localBlockRadius: number;
  readonly entityRadius: number;
  readonly surfaceRadius: number;
  readonly startedAt: string;
  readonly completedAt: string;
  readonly blocks: readonly BeatGameBlockObservation[];
  readonly entities: readonly BeatGameEntityObservation[];
  readonly surface: readonly BeatGameSurfaceColumn[];
}

export interface SmokeDecisionDiagnosticsInput {
  readonly checkpoint: BeatGameCheckpoint;
  readonly observation: BeatGameObservation;
  readonly strategy: BeatGameStrategy;
  readonly nextIfReplanned: BeatGamePlannerDecision;
}

export interface SmokeStuckDiagnosticsInput {
  readonly capturedAt: string;
  readonly currentAction?: string | undefined;
  readonly currentActionStartedAt?: string | undefined;
  readonly activePath?:
    | Readonly<{
        readonly pathId: string;
        readonly elapsedMs: number;
        readonly displacementFromOrigin?: number | undefined;
        readonly distanceToGoal?: number | undefined;
      }>
    | undefined;
  readonly activity: readonly unknown[];
}

export interface SmokeStuckFinding {
  readonly code: string;
  readonly severity: "warning" | "error";
  readonly summary: string;
  readonly evidence: Readonly<Record<string, unknown>>;
}

const COLLECT_BLOCKS_PROGRESS_TYPE = "soulfire.v1.CollectBlocksTaskProgressDetail";
const REPEATED_COLLECTION_WINDOW_MS = 5 * 60_000;

const HOSTILE_ENTITY_TYPES = new Set([
  "minecraft:blaze",
  "minecraft:bogged",
  "minecraft:breeze",
  "minecraft:cave_spider",
  "minecraft:creeper",
  "minecraft:drowned",
  "minecraft:elder_guardian",
  "minecraft:endermite",
  "minecraft:evoker",
  "minecraft:ghast",
  "minecraft:guardian",
  "minecraft:hoglin",
  "minecraft:husk",
  "minecraft:magma_cube",
  "minecraft:phantom",
  "minecraft:piglin_brute",
  "minecraft:pillager",
  "minecraft:ravager",
  "minecraft:shulker",
  "minecraft:silverfish",
  "minecraft:skeleton",
  "minecraft:slime",
  "minecraft:spider",
  "minecraft:stray",
  "minecraft:vex",
  "minecraft:vindicator",
  "minecraft:witch",
  "minecraft:wither_skeleton",
  "minecraft:zoglin",
  "minecraft:zombie",
  "minecraft:zombie_villager",
]);

const FLUID_BLOCK_IDS = new Set(["minecraft:bubble_column", "minecraft:lava", "minecraft:water"]);

export function buildSmokeSpatialDiagnostics(input: SmokeSpatialDiagnosticsInput) {
  const blocks = input.blocks
    .map((block) => ({
      ...block,
      offset: offset(input.origin, block.position),
      distance: distance(input.origin, block.position),
    }))
    .toSorted(compareDistance);
  const entities = input.entities
    .map((entity) => {
      const relativePosition = offset(input.origin, entity.position);
      const relativeVelocity = {
        x: entity.velocity.x - input.originVelocity.x,
        y: entity.velocity.y - input.originVelocity.y,
        z: entity.velocity.z - input.originVelocity.z,
      };
      const entityDistance = vectorLength(relativePosition);
      const radialVelocity =
        entityDistance === 0 ? 0 : dot(relativePosition, relativeVelocity) / entityDistance;
      return {
        ...entity,
        category:
          entity.itemId !== undefined
            ? "item"
            : HOSTILE_ENTITY_TYPES.has(entity.entityType)
              ? "hostile"
              : "other",
        offset: relativePosition,
        distance: entityDistance,
        horizontalDistance: Math.hypot(relativePosition.x, relativePosition.z),
        relativeVelocity,
        closingSpeed: Math.max(0, -radialVelocity),
      } as const;
    })
    .toSorted(compareDistance);
  const loadedSurface = input.surface.filter((column) => column.loaded);
  const surfaceHeights = loadedSurface.flatMap((column) =>
    column.surfaceY === undefined ? [] : [column.surfaceY],
  );
  const durationMs = Math.max(0, Date.parse(input.completedAt) - Date.parse(input.startedAt));

  return {
    capture: {
      startedAt: input.startedAt,
      completedAt: input.completedAt,
      durationMs,
      origin: input.origin,
      finalPosition: input.finalPosition,
      displacement: distance(input.origin, input.finalPosition),
    },
    blocks: {
      radius: input.localBlockRadius,
      observed: blocks.length,
      air: blocks.filter((block) => block.blockId === "minecraft:air").length,
      fluids: blocks.filter((block) => FLUID_BLOCK_IDS.has(block.blockId)).length,
      solid: blocks.filter((block) => block.solid === true).length,
      byBlockId: countsBy(blocks, (block) => block.blockId),
      observations: blocks,
    },
    entities: {
      radius: input.entityRadius,
      observed: entities.length,
      hostile: entities.filter((entity) => entity.category === "hostile"),
      items: entities.filter((entity) => entity.category === "item"),
      other: entities.filter((entity) => entity.category === "other"),
      observations: entities,
    },
    surface: {
      radius: input.surfaceRadius,
      observed: input.surface.length,
      unloaded: input.surface.length - loadedSurface.length,
      minimumY: surfaceHeights.length === 0 ? undefined : Math.min(...surfaceHeights),
      maximumY: surfaceHeights.length === 0 ? undefined : Math.max(...surfaceHeights),
      byBlockId: countsBy(loadedSurface, (column) => column.blockId ?? "unknown"),
      columns: input.surface.map((column) => ({
        ...column,
        offset: {
          x: column.x - input.origin.x,
          z: column.z - input.origin.z,
        },
      })),
    },
  };
}

export function buildSmokeActivePathDiagnostics(
  trace: SmokeActivePathTrace,
  currentPosition: BeatGamePosition,
  capturedAt: string,
) {
  const sameDimension = trace.origin.dimension === currentPosition.dimension;
  const elapsedMs = Math.max(0, Date.parse(capturedAt) - Date.parse(trace.startedAt));
  const goalDimension =
    trace.goal.type === "position" ? trace.goal.position.dimension : trace.goal.dimension;
  const distanceToGoal =
    goalDimension !== currentPosition.dimension
      ? undefined
      : trace.goal.type === "position"
        ? Math.max(0, distance(currentPosition, trace.goal.position) - trace.goal.radius)
        : Math.max(
            0,
            Math.hypot(trace.goal.x - currentPosition.x, trace.goal.z - currentPosition.z) -
              trace.goal.radius,
          );

  return {
    ...trace,
    status: "active" as const,
    capturedAt,
    elapsedMs,
    currentPosition,
    displacementFromOrigin: sameDimension ? distance(trace.origin, currentPosition) : undefined,
    distanceToGoal,
  };
}

export function buildSmokeDecisionDiagnostics(input: SmokeDecisionDiagnosticsInput) {
  const { checkpoint, observation, strategy, nextIfReplanned } = input;
  const { planner } = checkpoint;
  const pendingRequirements = planner.requirements
    .filter(({ satisfied }) => !satisfied)
    .map((requirement) => ({
      key: requirement.key,
      priority: requirement.priority,
      currentCount: requirement.currentCount,
      targetCount: requirement.targetCount,
      missingCount: Math.max(0, requirement.targetCount - requirement.currentCount),
    }));
  const rememberedDeaths = [...checkpoint.memory.deathPositions]
    .toSorted((left, right) => Date.parse(right.observedAt) - Date.parse(left.observedAt))
    .map((entry) => ({
      key: entry.key,
      observedAt: entry.observedAt,
      position: {
        x: entry.value.x,
        y: entry.value.y,
        z: entry.value.z,
        dimension: entry.value.dimension,
      },
      inventoryCounts: entry.value.inventoryCounts ?? {},
      inventoryItemCount: Object.values<number>(entry.value.inventoryCounts ?? {}).reduce(
        (total, count) => total + Math.max(0, count),
        0,
      ),
    }));
  const rememberedRecoveryCandidate = rememberedDeaths.find(
    ({ inventoryItemCount }) => inventoryItemCount > 0,
  );
  const recoveryCandidate =
    rememberedRecoveryCandidate === undefined
      ? undefined
      : {
          ...rememberedRecoveryCandidate,
          status:
            planner.currentAction === "recover-death" ? ("active" as const) : ("deferred" as const),
          sameDimension:
            rememberedRecoveryCandidate.position.dimension ===
            observation.player.position.dimension,
          distance:
            rememberedRecoveryCandidate.position.dimension === observation.player.position.dimension
              ? distance(observation.player.position, rememberedRecoveryCandidate.position)
              : undefined,
          horizontalDistance:
            rememberedRecoveryCandidate.position.dimension === observation.player.position.dimension
              ? Math.hypot(
                  rememberedRecoveryCandidate.position.x - observation.player.position.x,
                  rememberedRecoveryCandidate.position.z - observation.player.position.z,
                )
              : undefined,
          verticalDistance:
            rememberedRecoveryCandidate.position.dimension === observation.player.position.dimension
              ? rememberedRecoveryCandidate.position.y - observation.player.position.y
              : undefined,
          reason: explainRecoveryDisposition(
            planner.currentAction,
            rememberedRecoveryCandidate.position,
            observation,
            strategy,
            nextIfReplanned,
          ),
        };
  const activeAction =
    planner.currentAction === undefined
      ? undefined
      : {
          action: planner.currentAction,
          ...(planner.currentActionId === undefined ? {} : { actionId: planner.currentActionId }),
          retryCount: planner.retryCount,
          reason: explainCurrentAction(
            planner.currentAction,
            checkpoint,
            observation,
            strategy,
            nextIfReplanned,
          ),
        };

  return {
    phase: {
      current: planner.phase,
      objective: planner.objective,
      status: planner.status,
    },
    activeAction,
    nextIfReplanned: {
      decision: summarizeDecision(nextIfReplanned),
      reason: explainDecision(nextIfReplanned, checkpoint, observation, strategy),
    },
    signals: {
      dead: observation.player.dead,
      health: observation.player.health,
      minimumHealth: strategy.minimumHealth,
      food: observation.player.food,
      eatBelowFood: strategy.eatBelowFood,
      air: observation.player.air,
      maxAir: observation.player.maxAir,
      dimension: observation.player.position.dimension,
      inventoryRevision: observation.inventory.revision,
      observationRevision: observation.player.revision,
    },
    blockers: {
      pendingRequirements,
      recoveryCandidate,
      rememberedDeaths,
    },
    progress: {
      lastStableAction: checkpoint.lastStableAction,
      recentlyCompletedActions: planner.completedActions.slice(-12),
    },
  };
}

function explainRecoveryDisposition(
  currentAction: string | undefined,
  deathPosition: BeatGamePosition,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
  nextIfReplanned: BeatGamePlannerDecision,
): string {
  if (currentAction === "recover-death") {
    return "Corpse recovery is the active planner action";
  }
  if (observation.player.dead) {
    return "Corpse recovery is waiting for the death and respawn flow";
  }
  if (deathPosition.dimension !== observation.player.position.dimension) {
    return `Corpse recovery is deferred because the player is in ${
      observation.player.position.dimension
    } and the corpse is in ${deathPosition.dimension}`;
  }
  if (observation.player.health < strategy.minimumHealth) {
    return `Corpse recovery is deferred while health ${
      observation.player.health
    } is below the safety threshold ${strategy.minimumHealth}`;
  }
  if (observation.player.food <= strategy.eatBelowFood) {
    return `Corpse recovery is deferred while food ${
      observation.player.food
    } is at or below the eating threshold ${strategy.eatBelowFood}`;
  }
  const nextAction = "action" in nextIfReplanned ? nextIfReplanned.action : nextIfReplanned.type;
  return currentAction === undefined
    ? `Corpse recovery is remembered but a fresh replan currently chooses ${nextAction}`
    : `Corpse recovery is remembered while ${currentAction} runs; a fresh replan currently chooses ${nextAction}`;
}

export function buildSmokeStuckDiagnostics(input: SmokeStuckDiagnosticsInput) {
  const capturedAtMs = Date.parse(input.capturedAt);
  const activity = input.activity
    .flatMap(parseSmokeProgressActivity)
    .filter((entry) => entry.observedAtMs <= capturedAtMs)
    .toSorted((left, right) => left.observedAtMs - right.observedAtMs);
  const findings: SmokeStuckFinding[] = [];
  const actionStarted = activity.findLast(
    (entry) => entry.kind === "action-started" && entry.action === input.currentAction,
  );
  const actionStartedAt = actionStarted?.observedAt ?? input.currentActionStartedAt;
  const actionStartedAtMs = actionStartedAt === undefined ? undefined : Date.parse(actionStartedAt);
  const actionAgeMs =
    actionStartedAtMs === undefined || !Number.isFinite(actionStartedAtMs)
      ? undefined
      : Math.max(0, capturedAtMs - actionStartedAtMs);
  const intentionalWait =
    input.currentAction === "survive:night-shelter"
      ? activity.findLast(
          (entry) =>
            entry.kind === "intentional-wait" &&
            (actionStartedAtMs === undefined || entry.observedAtMs >= actionStartedAtMs),
        )
      : undefined;

  if (
    input.activePath !== undefined &&
    input.activePath.elapsedMs >= 10_000 &&
    (input.activePath.displacementFromOrigin ?? 0) < 0.75
  ) {
    findings.push({
      code: "path-no-displacement",
      severity: "error",
      summary: "The active path has not produced meaningful movement",
      evidence: {
        pathId: input.activePath.pathId,
        elapsedMs: input.activePath.elapsedMs,
        displacementFromOrigin: input.activePath.displacementFromOrigin,
        distanceToGoal: input.activePath.distanceToGoal,
      },
    });
  }

  const latestTaskId = activity.findLast(
    (entry) =>
      entry.kind === "task-progress" &&
      (actionStartedAtMs === undefined || entry.observedAtMs >= actionStartedAtMs),
  )?.taskId;
  const latestTaskProgress =
    latestTaskId === undefined
      ? []
      : activity.filter(
          (entry) =>
            entry.kind === "task-progress" &&
            entry.taskId === latestTaskId &&
            (actionStartedAtMs === undefined || entry.observedAtMs >= actionStartedAtMs),
        );
  const firstTaskProgress = latestTaskProgress.at(0);
  const lastTaskProgress = latestTaskProgress.at(-1);
  if (
    firstTaskProgress !== undefined &&
    lastTaskProgress !== undefined &&
    latestTaskProgress.length >= 2 &&
    lastTaskProgress.observedAtMs - firstTaskProgress.observedAtMs >= 10_000 &&
    (lastTaskProgress.taskCurrent !== undefined || lastTaskProgress.taskTotal !== undefined) &&
    lastTaskProgress.taskCurrent === firstTaskProgress.taskCurrent &&
    lastTaskProgress.taskFraction === firstTaskProgress.taskFraction
  ) {
    findings.push({
      code: "task-progress-stalled",
      severity: "error",
      summary: "The current task keeps reporting the same progress",
      evidence: {
        taskId: latestTaskId,
        durationMs: lastTaskProgress.observedAtMs - firstTaskProgress.observedAtMs,
        samples: latestTaskProgress.length,
        current: lastTaskProgress.taskCurrent,
        total: lastTaskProgress.taskTotal,
        fraction: lastTaskProgress.taskFraction,
        message: lastTaskProgress.message,
      },
    });
  }

  const collectionAttempts = summarizeCollectionTaskAttempts(activity, capturedAtMs);
  const repeatedCollectionTarget = mostRepeatedCollectionTarget(collectionAttempts);
  if (repeatedCollectionTarget !== undefined) {
    findings.push({
      code: "repeated-collection-target",
      severity: "error",
      summary: "Multiple collection tasks keep retrying the same block without breaking it",
      evidence: {
        target: repeatedCollectionTarget.target,
        attempts: repeatedCollectionTarget.attempts,
        count: repeatedCollectionTarget.attempts.length,
        windowMs: REPEATED_COLLECTION_WINDOW_MS,
      },
    });
  }

  const actionFailures = activity.filter(
    (entry) =>
      entry.kind === "action-failed" &&
      entry.action === input.currentAction &&
      capturedAtMs - entry.observedAtMs <= 5 * 60_000,
  );
  const repeatedFailure = mostRepeatedDetail(actionFailures);
  if (
    intentionalWait === undefined &&
    repeatedFailure !== undefined &&
    repeatedFailure.count >= 3
  ) {
    findings.push({
      code: "repeated-replan-reason",
      severity: "warning",
      summary: "The active action is repeatedly replanning for the same reason",
      evidence: {
        action: input.currentAction,
        count: repeatedFailure.count,
        reason: repeatedFailure.detail,
        windowMs: 5 * 60_000,
      },
    });
  }

  const pathFailures = activity.filter(
    (entry) => entry.kind === "path-failed" && capturedAtMs - entry.observedAtMs <= 2 * 60_000,
  );
  if (pathFailures.length >= 3) {
    findings.push({
      code: "repeated-path-failure",
      severity: "warning",
      summary: "Several path attempts have failed in a short period",
      evidence: {
        count: pathFailures.length,
        windowMs: 2 * 60_000,
        latestCause: pathFailures.at(-1)?.detail,
      },
    });
  }

  const lastProgress = activity.findLast(
    (entry) =>
      entry.kind === "task-progress" ||
      entry.kind === "path-completed" ||
      entry.kind === "primitive-completed" ||
      entry.kind === "action-succeeded" ||
      entry.kind === "intentional-wait",
  );
  const lastProgressAgeMs =
    lastProgress === undefined ? undefined : Math.max(0, capturedAtMs - lastProgress.observedAtMs);
  const expectedQuietWindowMs =
    lastTaskProgress?.message === "Smelting"
      ? 4 * 60_000
      : input.currentAction === "survive:night-shelter"
        ? 10 * 60_000
        : 30_000;
  if (
    input.currentAction !== undefined &&
    actionAgeMs !== undefined &&
    actionAgeMs >= expectedQuietWindowMs &&
    (lastProgressAgeMs === undefined || lastProgressAgeMs >= expectedQuietWindowMs)
  ) {
    findings.push({
      code: "no-recent-progress",
      severity: "warning",
      summary: "The active action has no recent progress signal",
      evidence: {
        action: input.currentAction,
        actionAgeMs,
        lastProgressAgeMs,
        expectedQuietWindowMs,
      },
    });
  }

  const status = findings.some(({ severity }) => severity === "error")
    ? "stuck"
    : findings.length > 0
      ? "degraded"
      : input.currentAction === undefined
        ? "idle"
        : "progressing";

  return {
    status,
    capturedAt: input.capturedAt,
    action:
      input.currentAction === undefined
        ? undefined
        : {
            name: input.currentAction,
            startedAt: actionStartedAt,
            ageMs: actionAgeMs,
          },
    activePath: input.activePath,
    latestTask:
      lastTaskProgress === undefined
        ? undefined
        : {
            taskId: lastTaskProgress.taskId,
            observedAt: lastTaskProgress.observedAt,
            current: lastTaskProgress.taskCurrent,
            total: lastTaskProgress.taskTotal,
            fraction: lastTaskProgress.taskFraction,
            message: lastTaskProgress.message,
            ...(lastTaskProgress.taskDetailType !== COLLECT_BLOCKS_PROGRESS_TYPE
              ? {}
              : {
                  collection: {
                    phase: lastTaskProgress.taskPhase,
                    activeTargets: lastTaskProgress.taskActiveTargets,
                    rejectedTargets: lastTaskProgress.taskRejectedTargets,
                    failedApproachTargets: lastTaskProgress.taskFailedApproachTargets,
                    failedApproachPositions: lastTaskProgress.taskFailedApproachPositions,
                    completedBreaks: lastTaskProgress.taskCompletedBreaks,
                    consecutiveStalledPaths: lastTaskProgress.taskConsecutiveStalledPaths,
                    pathCurrentMovement: lastTaskProgress.taskPathCurrentMovement,
                    pathTotalMovements: lastTaskProgress.taskPathTotalMovements,
                  },
                }),
          },
    lastProgressAt: lastProgress?.observedAt,
    lastProgressAgeMs,
    expectedQuietWindowMs,
    intentionalWait:
      intentionalWait === undefined
        ? undefined
        : {
            observedAt: intentionalWait.observedAt,
            message: intentionalWait.message,
          },
    findings,
  } as const;
}

function summarizeDecision(decision: BeatGamePlannerDecision) {
  if (decision.type !== "satisfy-requirement") {
    return decision;
  }
  return {
    type: decision.type,
    action: decision.action,
    requirement: {
      key: decision.requirement.key,
      priority: decision.requirement.priority,
      currentCount: decision.requirement.currentCount,
      targetCount: decision.requirement.targetCount,
      missingCount: Math.max(
        0,
        decision.requirement.targetCount - decision.requirement.currentCount,
      ),
    },
  };
}

export function summarizeSmokeSpatialDiagnostics(
  diagnostics: ReturnType<typeof buildSmokeSpatialDiagnostics>,
) {
  return {
    capture: diagnostics.capture,
    blocks: {
      radius: diagnostics.blocks.radius,
      observed: diagnostics.blocks.observed,
      air: diagnostics.blocks.air,
      fluids: diagnostics.blocks.fluids,
      solid: diagnostics.blocks.solid,
      byBlockId: diagnostics.blocks.byBlockId,
      closest: diagnostics.blocks.observations
        .filter(({ blockId }) => blockId !== "minecraft:air")
        .slice(0, 24),
    },
    entities: {
      radius: diagnostics.entities.radius,
      observed: diagnostics.entities.observed,
      hostileCount: diagnostics.entities.hostile.length,
      itemCount: diagnostics.entities.items.length,
      otherCount: diagnostics.entities.other.length,
      closestHostile: diagnostics.entities.hostile.slice(0, 12),
      closestItems: diagnostics.entities.items.slice(0, 12),
      closestOther: diagnostics.entities.other.slice(0, 12),
    },
    surface: {
      radius: diagnostics.surface.radius,
      observed: diagnostics.surface.observed,
      unloaded: diagnostics.surface.unloaded,
      minimumY: diagnostics.surface.minimumY,
      maximumY: diagnostics.surface.maximumY,
      byBlockId: diagnostics.surface.byBlockId,
    },
  };
}

export function summarizeSmokeEnvironment(environment: BotEnvironmentState | undefined) {
  if (environment === undefined) {
    return undefined;
  }
  const gameTime = environment.gameTime;
  const dayTime =
    gameTime === undefined ? undefined : Number(((gameTime % 24_000n) + 24_000n) % 24_000n);
  const dayPhase =
    dayTime === undefined
      ? undefined
      : dayTime < 12_000
        ? ("day" as const)
        : dayTime < 13_000
          ? ("dusk" as const)
          : dayTime < 23_000
            ? ("night" as const)
            : ("dawn" as const);
  return {
    ...(gameTime === undefined ? {} : { gameTime }),
    ...(dayTime === undefined
      ? {}
      : {
          dayTime,
          dayPhase,
          isDay: dayPhase === "day",
          isNight: dayPhase === "night",
          isHostileNight: dayPhase === "night",
        }),
    ...(environment.raining === undefined ? {} : { raining: environment.raining }),
    ...(environment.rainLevel === undefined ? {} : { rainLevel: environment.rainLevel }),
    ...(environment.thunderLevel === undefined ? {} : { thunderLevel: environment.thunderLevel }),
    clocks: [...environment.clocks.values()].map((clock) => ({
      clockId: clock.clockId,
      totalTicks: clock.totalTicks,
      partialTick: clock.partialTick,
      rate: clock.rate,
    })),
  };
}

function explainCurrentAction(
  action: string,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
  nextIfReplanned: BeatGamePlannerDecision,
): string {
  if ("action" in nextIfReplanned && nextIfReplanned.action === action) {
    return explainDecision(nextIfReplanned, checkpoint, observation, strategy);
  }
  if (action === "recover-death") {
    const remembered = checkpoint.memory.deathPositions.length;
    return observation.player.dead
      ? "The player is dead and must respawn before recovery can continue"
      : `The action is processing the newest eligible corpse; ${remembered} death location${
          remembered === 1 ? " remains" : "s remain"
        } in checkpoint memory`;
  }
  if (action === "survive:night-shelter") {
    return "The bot is deliberately sheltered while hostile-night travel would be unsafe";
  }
  if (action.startsWith("satisfy:")) {
    const key = action.slice("satisfy:".length);
    const requirement = checkpoint.planner.requirements.find((candidate) => candidate.key === key);
    if (requirement !== undefined) {
      return requirementReason(requirement);
    }
  }
  return `The planner is still executing ${action}; a fresh replan would choose ${
    "action" in nextIfReplanned ? nextIfReplanned.action : nextIfReplanned.type
  }`;
}

function explainDecision(
  decision: BeatGamePlannerDecision,
  checkpoint: BeatGameCheckpoint,
  observation: BeatGameObservation,
  strategy: BeatGameStrategy,
): string {
  switch (decision.type) {
    case "advance-phase":
      return `The ${decision.from} objective is complete, so the planner can advance to ${decision.to}`;
    case "recover-death":
      return observation.player.dead
        ? "The player is dead and must respawn before continuing"
        : `The planner remembers ${checkpoint.memory.deathPositions.length} death location${
            checkpoint.memory.deathPositions.length === 1 ? "" : "s"
          } with potentially recoverable items`;
    case "eat":
      return `Food is ${observation.player.food}, at or below the configured eating threshold of ${strategy.eatBelowFood}, and edible food is available`;
    case "retreat":
      return `Health is ${observation.player.health}, below the configured safety threshold of ${strategy.minimumHealth}`;
    case "satisfy-requirement":
      return requirementReason(decision.requirement);
    case "prepare-equipment":
      return "The Overworld resource requirements are satisfied, but equipment preparation has not completed";
    case "build-and-enter-nether":
      return "The Nether entry requirements are satisfied and the player is still in the Overworld";
    case "return-through-portal":
      return "The Nether resource requirements are satisfied and the player must return to the Overworld";
    case "throw-eye":
      return "The stronghold is not estimated yet and the eye supply requirement is satisfied";
    case "search-stronghold":
      return "A stronghold estimate is available and the planner is ready to search it";
    case "activate-end-portal":
      return "The End portal requirements are satisfied and the player has not entered the End";
    case "fight-ender-dragon":
      return "The dragon-fight requirements are satisfied and the fight remains incomplete";
    case "exit-end":
      return "The dragon is defeated and the player remains in the End";
    default:
      return decision satisfies never;
  }
}

function requirementReason(
  requirement: BeatGameCheckpoint["planner"]["requirements"][number],
): string {
  const missing = Math.max(0, requirement.targetCount - requirement.currentCount);
  return `${requirement.key} is the highest-priority actionable requirement: ${missing} missing (${requirement.currentCount}/${requirement.targetCount}, priority ${requirement.priority})`;
}

interface SmokeProgressActivity {
  readonly observedAt: string;
  readonly observedAtMs: number;
  readonly kind:
    | "action-failed"
    | "action-started"
    | "action-succeeded"
    | "intentional-wait"
    | "path-completed"
    | "path-failed"
    | "primitive-completed"
    | "task-progress";
  readonly action?: string;
  readonly detail?: string;
  readonly taskId?: string;
  readonly taskCurrent?: string;
  readonly taskTotal?: string;
  readonly taskFraction?: number;
  readonly message?: string;
  readonly taskDetailType?: string;
  readonly taskPhase?: number;
  readonly taskActiveTargets?: readonly string[];
  readonly taskRejectedTargets?: readonly string[];
  readonly taskFailedApproachTargets?: readonly string[];
  readonly taskFailedApproachPositions?: readonly string[];
  readonly taskCompletedBreaks?: number;
  readonly taskConsecutiveStalledPaths?: number;
  readonly taskPathCurrentMovement?: number;
  readonly taskPathTotalMovements?: number;
}

interface SmokeCollectionTaskAttempt {
  readonly taskId: string;
  readonly observedAt: string;
  readonly targets: readonly string[];
  readonly failedApproachPositions: readonly string[];
  readonly completedBreaks: number;
  readonly current: number;
  readonly consecutiveStalledPaths: number;
}

function parseSmokeProgressActivity(input: unknown): readonly SmokeProgressActivity[] {
  if (!isRecord(input) || typeof input["observedAt"] !== "string") {
    return [];
  }
  const observedAtMs = Date.parse(input["observedAt"]);
  if (!Number.isFinite(observedAtMs) || typeof input["kind"] !== "string") {
    return [];
  }
  if (input["kind"] === "beat-game-event" && isRecord(input["event"])) {
    const event = input["event"];
    if (
      event["type"] === "diagnostic" &&
      typeof event["message"] === "string" &&
      (event["message"] === "Waiting in a sealed shelter until morning" ||
        event["message"] === "Waiting under existing cover until morning")
    ) {
      return [
        {
          observedAt: input["observedAt"],
          observedAtMs,
          kind: "intentional-wait",
          message: event["message"],
        },
      ];
    }
    if (
      event["type"] !== "action-started" &&
      event["type"] !== "action-succeeded" &&
      event["type"] !== "action-failed"
    ) {
      return [];
    }
    return [
      {
        observedAt: input["observedAt"],
        observedAtMs,
        kind: event["type"],
        ...(typeof event["action"] === "string" ? { action: event["action"] } : {}),
        ...(typeof event["detail"] === "string" ? { detail: event["detail"] } : {}),
      },
    ];
  }
  if (input["kind"] === "task-progress-observed" && isRecord(input["task"])) {
    const task = input["task"];
    const progress = isRecord(task["progress"]) ? task["progress"] : undefined;
    const detail = isRecord(progress?.["detail"]) ? progress["detail"] : undefined;
    const current = progressCount(progress?.["current"]);
    const total = progressCount(progress?.["total"]);
    const failedApproaches = Array.isArray(detail?.["failedApproaches"])
      ? detail["failedApproaches"].filter(isRecord)
      : [];
    return [
      {
        observedAt: input["observedAt"],
        observedAtMs,
        kind: "task-progress",
        ...(typeof task["taskId"] === "string" ? { taskId: task["taskId"] } : {}),
        ...(current === undefined ? {} : { taskCurrent: current }),
        ...(total === undefined ? {} : { taskTotal: total }),
        ...(typeof progress?.["fraction"] === "number"
          ? { taskFraction: progress["fraction"] }
          : {}),
        ...(typeof progress?.["message"] === "string" ? { message: progress["message"] } : {}),
        ...(typeof progress?.["detailType"] === "string"
          ? { taskDetailType: progress["detailType"] }
          : {}),
        ...(typeof detail?.["phase"] === "number" ? { taskPhase: detail["phase"] } : {}),
        ...(detail === undefined
          ? {}
          : {
              taskActiveTargets: blockPositionKeys(detail["activeTargets"]),
              taskRejectedTargets: blockPositionKeys(detail["rejectedTargets"]),
              taskFailedApproachTargets: blockPositionKeys(
                failedApproaches.map((approach) => approach["target"]),
              ),
              taskFailedApproachPositions: blockPositionKeys(
                failedApproaches.flatMap((approach): readonly unknown[] =>
                  Array.isArray(approach["playerPositions"]) ? approach["playerPositions"] : [],
                ),
              ),
              taskCompletedBreaks: Array.isArray(detail["completedBreaks"])
                ? detail["completedBreaks"].length
                : 0,
              ...(typeof detail["consecutiveStalledPaths"] === "number"
                ? {
                    taskConsecutiveStalledPaths: detail["consecutiveStalledPaths"],
                  }
                : {}),
              ...(typeof detail["pathCurrentMovement"] === "number"
                ? { taskPathCurrentMovement: detail["pathCurrentMovement"] }
                : {}),
              ...(typeof detail["pathTotalMovements"] === "number"
                ? { taskPathTotalMovements: detail["pathTotalMovements"] }
                : {}),
            }),
      },
    ];
  }
  if (input["kind"] === "pathfind-completed" || input["kind"] === "pathfind-xz-completed") {
    return [
      {
        observedAt: input["observedAt"],
        observedAtMs,
        kind: "path-completed",
      },
    ];
  }
  if (input["kind"] === "pathfind-failed" || input["kind"] === "pathfind-xz-failed") {
    return [
      {
        observedAt: input["observedAt"],
        observedAtMs,
        kind: "path-failed",
        ...(typeof input["cause"] === "string" ? { detail: input["cause"] } : {}),
      },
    ];
  }
  if (input["kind"] === "primitive-completed") {
    return [
      {
        observedAt: input["observedAt"],
        observedAtMs,
        kind: "primitive-completed",
      },
    ];
  }
  return [];
}

function progressCount(value: unknown): string | undefined {
  if (typeof value === "bigint") {
    return value.toString();
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return Math.max(0, Math.trunc(value)).toString();
  }
  return typeof value === "string" ? value : undefined;
}

function blockPositionKeys(input: unknown): readonly string[] {
  if (!Array.isArray(input)) {
    return [];
  }
  return input.flatMap((position) => {
    if (
      !isRecord(position) ||
      typeof position["x"] !== "number" ||
      typeof position["y"] !== "number" ||
      typeof position["z"] !== "number"
    ) {
      return [];
    }
    const dimension = typeof position["dimension"] === "string" ? position["dimension"] : "unknown";
    return [`${dimension}:${position["x"]},${position["y"]},${position["z"]}`];
  });
}

function summarizeCollectionTaskAttempts(
  activity: readonly SmokeProgressActivity[],
  capturedAtMs: number,
): readonly SmokeCollectionTaskAttempt[] {
  const attempts = new Map<
    string,
    {
      latest: SmokeProgressActivity;
      targets: Set<string>;
      failedApproachPositions: Set<string>;
      completedBreaks: number;
      current: number;
      consecutiveStalledPaths: number;
    }
  >();
  for (const entry of activity) {
    if (
      entry.kind !== "task-progress" ||
      entry.taskId === undefined ||
      entry.taskDetailType !== COLLECT_BLOCKS_PROGRESS_TYPE ||
      capturedAtMs - entry.observedAtMs > REPEATED_COLLECTION_WINDOW_MS
    ) {
      continue;
    }
    const attempt = attempts.get(entry.taskId) ?? {
      latest: entry,
      targets: new Set<string>(),
      failedApproachPositions: new Set<string>(),
      completedBreaks: 0,
      current: 0,
      consecutiveStalledPaths: 0,
    };
    attempt.latest = entry;
    for (const target of [
      ...(entry.taskActiveTargets ?? []),
      ...(entry.taskRejectedTargets ?? []),
      ...(entry.taskFailedApproachTargets ?? []),
    ]) {
      attempt.targets.add(target);
    }
    for (const position of entry.taskFailedApproachPositions ?? []) {
      attempt.failedApproachPositions.add(position);
    }
    attempt.completedBreaks = Math.max(attempt.completedBreaks, entry.taskCompletedBreaks ?? 0);
    attempt.current = Math.max(attempt.current, Number(entry.taskCurrent ?? 0));
    attempt.consecutiveStalledPaths = Math.max(
      attempt.consecutiveStalledPaths,
      entry.taskConsecutiveStalledPaths ?? 0,
    );
    attempts.set(entry.taskId, attempt);
  }
  return [...attempts].map(([taskId, attempt]) => ({
    taskId,
    observedAt: attempt.latest.observedAt,
    targets: [...attempt.targets].toSorted(),
    failedApproachPositions: [...attempt.failedApproachPositions].toSorted(),
    completedBreaks: attempt.completedBreaks,
    current: attempt.current,
    consecutiveStalledPaths: attempt.consecutiveStalledPaths,
  }));
}

function mostRepeatedCollectionTarget(attempts: readonly SmokeCollectionTaskAttempt[]):
  | Readonly<{
      target: string;
      attempts: readonly SmokeCollectionTaskAttempt[];
    }>
  | undefined {
  const attemptsByTarget = new Map<string, SmokeCollectionTaskAttempt[]>();
  for (const attempt of attempts) {
    if (attempt.completedBreaks > 0 || attempt.current > 0) {
      continue;
    }
    for (const target of attempt.targets) {
      const matching = attemptsByTarget.get(target) ?? [];
      matching.push(attempt);
      attemptsByTarget.set(target, matching);
    }
  }
  return [...attemptsByTarget]
    .filter(([, matching]) => matching.length >= 3)
    .map(([target, matching]) => ({ target, attempts: matching }))
    .toSorted(
      (left, right) =>
        right.attempts.length - left.attempts.length || left.target.localeCompare(right.target),
    )
    .at(0);
}

function mostRepeatedDetail(
  entries: readonly SmokeProgressActivity[],
): Readonly<{ detail: string; count: number }> | undefined {
  const counts = new Map<string, number>();
  for (const entry of entries) {
    if (entry.detail !== undefined) {
      counts.set(entry.detail, (counts.get(entry.detail) ?? 0) + 1);
    }
  }
  return [...counts]
    .map(([detail, count]) => ({ detail, count }))
    .toSorted((left, right) => right.count - left.count || left.detail.localeCompare(right.detail))
    .at(0);
}

function isRecord(input: unknown): input is Readonly<Record<string, unknown>> {
  return typeof input === "object" && input !== null && !Array.isArray(input);
}

function countsBy<T>(
  values: readonly T[],
  key: (value: T) => string,
): readonly Readonly<{ id: string; count: number }>[] {
  const counts = new Map<string, number>();
  for (const value of values) {
    const id = key(value);
    counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  return [...counts]
    .map(([id, count]) => ({ id, count }))
    .toSorted((left, right) => right.count - left.count || left.id.localeCompare(right.id));
}

function offset(
  origin: BeatGamePosition,
  position: Readonly<{ x: number; y: number; z: number }>,
): DebugVector {
  return {
    x: position.x - origin.x,
    y: position.y - origin.y,
    z: position.z - origin.z,
  };
}

function distance(
  left: Readonly<{ x: number; y: number; z: number }>,
  right: Readonly<{ x: number; y: number; z: number }>,
): number {
  return Math.hypot(right.x - left.x, right.y - left.y, right.z - left.z);
}

function vectorLength(vector: DebugVector): number {
  return Math.hypot(vector.x, vector.y, vector.z);
}

function dot(left: DebugVector, right: DebugVector): number {
  return left.x * right.x + left.y * right.y + left.z * right.z;
}

function compareDistance(
  left: Readonly<{ distance: number }>,
  right: Readonly<{ distance: number }>,
): number {
  return left.distance - right.distance;
}
