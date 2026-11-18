import { Effect } from "effect";

import { BeatGameCheckpointError } from "./errors.js";
import {
  BEAT_GAME_CHECKPOINT_SCHEMA_VERSION,
  BeatGameDurableSkillKind,
  BeatGameDurableSkillStatus,
  BeatGamePhase,
  BeatGameRunStatus,
  BeatGameTeamRole,
  type BeatGameCheckpoint,
} from "./model.js";

export interface BeatGameCheckpointStore {
  readonly load: (
    runId: string,
  ) => Effect.Effect<BeatGameCheckpoint | undefined, BeatGameCheckpointError>;
  readonly save: (
    checkpoint: BeatGameCheckpoint,
    expectedRevision: number | undefined,
  ) => Effect.Effect<BeatGameCheckpoint, BeatGameCheckpointError>;
  readonly remove: (
    runId: string,
    expectedRevision?: number,
  ) => Effect.Effect<void, BeatGameCheckpointError>;
}

export class InMemoryBeatGameCheckpointStore implements BeatGameCheckpointStore {
  readonly #checkpoints = new Map<string, BeatGameCheckpoint>();

  public readonly load = (
    runId: string,
  ): Effect.Effect<BeatGameCheckpoint | undefined, BeatGameCheckpointError> =>
    Effect.sync(() => {
      const checkpoint = this.#checkpoints.get(runId);
      return checkpoint === undefined ? undefined : clone(checkpoint);
    });

  public readonly save = (
    checkpoint: BeatGameCheckpoint,
    expectedRevision: number | undefined,
  ): Effect.Effect<BeatGameCheckpoint, BeatGameCheckpointError> =>
    Effect.gen({ self: this }, function* () {
      yield* Effect.try({
        try: () => assertValidCheckpoint(checkpoint),
        catch: (cause) =>
          makeCheckpointError(
            checkpoint,
            checkpoint.runId,
            "Checkpoint validation failed",
            expectedRevision,
            undefined,
            cause,
          ),
      });
      const current = this.#checkpoints.get(checkpoint.runId);
      yield* validateCheckpointRevision(checkpoint, current, expectedRevision);
      const stored = clone(checkpoint);
      this.#checkpoints.set(checkpoint.runId, stored);
      return clone(stored);
    });

  public readonly remove = (
    runId: string,
    expectedRevision?: number,
  ): Effect.Effect<void, BeatGameCheckpointError> =>
    Effect.gen({ self: this }, function* () {
      const current = this.#checkpoints.get(runId);
      if (expectedRevision !== undefined && current?.revision !== expectedRevision) {
        return yield* Effect.fail(
          makeCheckpointError(
            current,
            runId,
            `Checkpoint revision changed before removal`,
            expectedRevision,
            current?.revision,
          ),
        );
      }
      this.#checkpoints.delete(runId);
    });
}

export function validateCheckpointRevision(
  checkpoint: BeatGameCheckpoint,
  current: BeatGameCheckpoint | undefined,
  expectedRevision: number | undefined,
): Effect.Effect<void, BeatGameCheckpointError> {
  if (current === undefined) {
    if (expectedRevision !== undefined) {
      return Effect.fail(
        makeCheckpointError(
          checkpoint,
          checkpoint.runId,
          "Checkpoint does not exist at the expected revision",
          expectedRevision,
          undefined,
        ),
      );
    }
    if (checkpoint.revision !== 1) {
      return Effect.fail(
        makeCheckpointError(
          checkpoint,
          checkpoint.runId,
          "A new checkpoint must start at revision 1",
          1,
          checkpoint.revision,
        ),
      );
    }
    return Effect.void;
  }
  if (expectedRevision !== current.revision) {
    return Effect.fail(
      makeCheckpointError(
        checkpoint,
        checkpoint.runId,
        "Checkpoint revision conflict",
        expectedRevision,
        current.revision,
      ),
    );
  }
  if (checkpoint.revision !== current.revision + 1) {
    return Effect.fail(
      makeCheckpointError(
        checkpoint,
        checkpoint.runId,
        "Checkpoint revision must increase by one",
        current.revision + 1,
        checkpoint.revision,
      ),
    );
  }
  return Effect.void;
}

export function makeCheckpointError(
  checkpoint: BeatGameCheckpoint | undefined,
  runId: string,
  message: string,
  expectedRevision?: number,
  actualRevision?: number,
  cause?: unknown,
): BeatGameCheckpointError {
  return new BeatGameCheckpointError({
    runId,
    instanceId: checkpoint?.instanceId ?? "",
    botId: checkpoint?.botId ?? "",
    phase: checkpoint?.planner.phase ?? BeatGamePhase.PREPARE_OVERWORLD,
    ...(checkpoint?.planner.currentAction === undefined
      ? {}
      : { action: checkpoint.planner.currentAction }),
    retryable: true,
    message,
    ...(expectedRevision === undefined ? {} : { expectedRevision }),
    ...(actualRevision === undefined ? {} : { actualRevision }),
    ...(cause === undefined ? {} : { cause }),
  });
}

export function assertValidCheckpoint(
  value: unknown,
  expectedRunId?: string,
): asserts value is BeatGameCheckpoint {
  const checkpoint = record(value, "checkpoint");
  if (checkpoint.schemaVersion !== BEAT_GAME_CHECKPOINT_SCHEMA_VERSION) {
    throw new TypeError("Unsupported beat-game checkpoint schema version");
  }
  const runId = nonEmptyString(checkpoint.runId, "checkpoint.runId");
  if (expectedRunId !== undefined && runId !== expectedRunId) {
    throw new TypeError(`Checkpoint run ID ${runId} does not match ${expectedRunId}`);
  }
  for (const key of ["teamId", "instanceId", "botId", "connectionEpoch"]) {
    nonEmptyString(checkpoint[key], `checkpoint.${key}`);
  }
  enumValue(checkpoint.role, Object.values(BeatGameTeamRole), "checkpoint.role");
  positiveInteger(checkpoint.revision, "checkpoint.revision");
  timestamp(checkpoint.createdAt, "checkpoint.createdAt");
  timestamp(checkpoint.updatedAt, "checkpoint.updatedAt");
  plannerState(checkpoint.planner);
  worldMemory(checkpoint.memory);
  if (checkpoint.activeSkill !== undefined) {
    durableSkill(checkpoint.activeSkill, "checkpoint.activeSkill");
  }
  if (checkpoint.lastStableAction !== undefined) {
    stableActionResult(checkpoint.lastStableAction);
  }
}

function stableActionResult(value: unknown): void {
  const result = record(value, "checkpoint.lastStableAction");
  nonEmptyString(result.action, "checkpoint.lastStableAction.action");
  enumValue(result.phase, Object.values(BeatGamePhase), "checkpoint.lastStableAction.phase");
  timestamp(result.completedAt, "checkpoint.lastStableAction.completedAt");
  enumValue(
    result.evidence,
    ["TASK_RESULT", "OBSERVED_STATE", "OBSERVATION_AFTER_UNCERTAIN_RESULT"],
    "checkpoint.lastStableAction.evidence",
  );
  nonEmptyString(result.connectionEpoch, "checkpoint.lastStableAction.connectionEpoch");
  unsignedIntegerString(result.playerRevision, "checkpoint.lastStableAction.playerRevision");
  unsignedIntegerString(result.inventoryRevision, "checkpoint.lastStableAction.inventoryRevision");
}

function plannerState(value: unknown): void {
  const planner = record(value, "checkpoint.planner");
  enumValue(planner.phase, Object.values(BeatGamePhase), "checkpoint.planner.phase");
  enumValue(planner.status, Object.values(BeatGameRunStatus), "checkpoint.planner.status");
  nonEmptyString(planner.objective, "checkpoint.planner.objective");
  nonNegativeInteger(planner.retryCount, "checkpoint.planner.retryCount");
  stringArray(planner.completedActions, "checkpoint.planner.completedActions");
  if (planner.currentAction !== undefined && typeof planner.currentAction !== "string") {
    throw new TypeError("checkpoint.planner.currentAction must be a string");
  }
  if (planner.currentActionId !== undefined) {
    nonEmptyString(planner.currentActionId, "checkpoint.planner.currentActionId");
    if (planner.currentAction === undefined) {
      throw new TypeError("checkpoint.planner.currentActionId requires currentAction");
    }
  }
  timestamp(planner.startedAt, "checkpoint.planner.startedAt");
  timestamp(planner.updatedAt, "checkpoint.planner.updatedAt");
  if (!Array.isArray(planner.requirements)) {
    throw new TypeError("checkpoint.planner.requirements must be an array");
  }
  planner.requirements.forEach((requirement, index) => {
    const item = record(requirement, `checkpoint.planner.requirements[${index}]`);
    const prefix = `checkpoint.planner.requirements[${index}]`;
    nonEmptyString(item.key, `${prefix}.key`);
    stringArray(item.itemIds, `${prefix}.itemIds`);
    stringArray(item.tags, `${prefix}.tags`);
    nonNegativeInteger(item.targetCount, `${prefix}.targetCount`);
    nonNegativeInteger(item.currentCount, `${prefix}.currentCount`);
    finiteNumber(item.priority, `${prefix}.priority`);
    booleanValue(item.satisfied, `${prefix}.satisfied`);
  });
}

function worldMemory(value: unknown): void {
  const memory = record(value, "checkpoint.memory");
  memoryEntries(memory.blocks, "checkpoint.memory.blocks", blockObservation);
  memoryEntries(memory.entities, "checkpoint.memory.entities", entityObservation);
  memoryEntries(memory.containers, "checkpoint.memory.containers", blockObservation);
  memoryEntries(memory.portals, "checkpoint.memory.portals", blockObservation);
  if (!Array.isArray(memory.portalWorkspaces)) {
    throw new TypeError("checkpoint.memory.portalWorkspaces must be an array");
  }
  memory.portalWorkspaces.forEach((workspace, index) =>
    portalWorkspace(workspace, `checkpoint.memory.portalWorkspaces[${index}]`),
  );
  if (!Array.isArray(memory.places)) {
    throw new TypeError("checkpoint.memory.places must be an array");
  }
  memory.places.forEach((place, index) => {
    const name = `checkpoint.memory.places[${index}]`;
    const item = record(place, name);
    nonEmptyString(item.key, `${name}.key`);
    enumValue(
      item.kind,
      [
        "FORTRESS",
        "BASTION",
        "BLAZE_SPAWNER",
        "SAFE_CORRIDOR",
        "SHELTER",
        "END_ENTRY",
        "END_FIGHT",
      ],
      `${name}.kind`,
    );
    position(item.position, `${name}.position`);
    timestamp(item.observedAt, `${name}.observedAt`);
    confidence(item.confidence, `${name}.confidence`);
    if (item.invalidationReason !== undefined) {
      nonEmptyString(item.invalidationReason, `${name}.invalidationReason`);
    }
  });
  if (!Array.isArray(memory.skillHistory)) {
    throw new TypeError("checkpoint.memory.skillHistory must be an array");
  }
  memory.skillHistory.forEach((skill, index) =>
    durableSkill(skill, `checkpoint.memory.skillHistory[${index}]`),
  );
  memoryEntries(memory.unreachable, "checkpoint.memory.unreachable", position);
  memoryEntries(memory.deathPositions, "checkpoint.memory.deathPositions", deathPosition);
  if (memory.explorationFrontiers !== undefined) {
    const frontiers = record(memory.explorationFrontiers, "checkpoint.memory.explorationFrontiers");
    for (const [key, value] of Object.entries(frontiers)) {
      nonEmptyString(key, "checkpoint.memory.explorationFrontiers key");
      const frontier = record(value, `checkpoint.memory.explorationFrontiers.${key}`);
      position(frontier.origin, `checkpoint.memory.explorationFrontiers.${key}.origin`);
      positiveInteger(
        frontier.nextIndex,
        `checkpoint.memory.explorationFrontiers.${key}.nextIndex`,
      );
      if (frontier.progressVersion !== undefined) {
        if (frontier.progressVersion !== 2) {
          throw new TypeError(
            `checkpoint.memory.explorationFrontiers.${key}.progressVersion must be 2`,
          );
        }
      }
      if (frontier.target !== undefined) {
        position(frontier.target, `checkpoint.memory.explorationFrontiers.${key}.target`);
      }
      if (frontier.targetAttempts !== undefined) {
        nonNegativeInteger(
          frontier.targetAttempts,
          `checkpoint.memory.explorationFrontiers.${key}.targetAttempts`,
        );
      }
      if (frontier.lastPosition !== undefined) {
        position(
          frontier.lastPosition,
          `checkpoint.memory.explorationFrontiers.${key}.lastPosition`,
        );
      }
      if (frontier.totalAdvances !== undefined) {
        nonNegativeInteger(
          frontier.totalAdvances,
          `checkpoint.memory.explorationFrontiers.${key}.totalAdvances`,
        );
      }
    }
  }
  if (memory.latestDeath !== undefined) {
    memoryEntry(memory.latestDeath, "checkpoint.memory.latestDeath", position);
  }
  if (!Array.isArray(memory.eyeSamples)) {
    throw new TypeError("checkpoint.memory.eyeSamples must be an array");
  }
  memory.eyeSamples.forEach((sample, index) => {
    const item = record(sample, `checkpoint.memory.eyeSamples[${index}]`);
    position(item.origin, `checkpoint.memory.eyeSamples[${index}].origin`);
    const direction = record(item.direction, `checkpoint.memory.eyeSamples[${index}].direction`);
    finiteNumber(direction.x, "eye sample direction.x");
    finiteNumber(direction.z, "eye sample direction.z");
    timestamp(item.observedAt, "eye sample observedAt");
    confidence(item.confidence, "eye sample confidence");
  });
  if (memory.strongholdEstimate !== undefined) {
    position(memory.strongholdEstimate, "checkpoint.memory.strongholdEstimate");
  }
}

function durableSkill(value: unknown, name: string): void {
  const skill = record(value, name);
  nonEmptyString(skill.skillId, `${name}.skillId`);
  enumValue(skill.kind, Object.values(BeatGameDurableSkillKind), `${name}.kind`);
  enumValue(skill.phase, Object.values(BeatGamePhase), `${name}.phase`);
  nonEmptyString(skill.action, `${name}.action`);
  enumValue(skill.status, Object.values(BeatGameDurableSkillStatus), `${name}.status`);
  nonEmptyString(skill.substep, `${name}.substep`);
  positionArray(skill.targets, `${name}.targets`);
  positionArray(skill.protectedBlocks, `${name}.protectedBlocks`);
  stringArray(skill.protectedItemIds, `${name}.protectedItemIds`);
  positionArray(skill.completedWorldChanges, `${name}.completedWorldChanges`);
  nonNegativeIntegerRecord(skill.requiredResources, `${name}.requiredResources`);
  nonNegativeIntegerRecord(skill.retries, `${name}.retries`);
  if (skill.completionEvidence !== undefined) {
    nonEmptyString(skill.completionEvidence, `${name}.completionEvidence`);
  }
  if (skill.portalWorkspace !== undefined) {
    portalWorkspace(skill.portalWorkspace, `${name}.portalWorkspace`);
  }
  timestamp(skill.startedAt, `${name}.startedAt`);
  timestamp(skill.updatedAt, `${name}.updatedAt`);
}

function portalWorkspace(value: unknown, name: string): void {
  const workspace = record(value, name);
  nonEmptyString(workspace.workspaceId, `${name}.workspaceId`);
  position(workspace.origin, `${name}.origin`);
  enumValue(workspace.axis, ["x", "z"], `${name}.axis`);
  enumValue(workspace.method, ["OBSIDIAN", "CAST"], `${name}.method`);
  enumValue(
    workspace.status,
    ["RESERVED", "BUILDING", "IGNITED", "ENTERING", "ENTERED", "ABANDONED"],
    `${name}.status`,
  );
  positionArray(workspace.targetFrame, `${name}.targetFrame`);
  positionArray(workspace.observedFrame, `${name}.observedFrame`);
  positionArray(workspace.interior, `${name}.interior`);
  positionArray(workspace.protectedBlocks, `${name}.protectedBlocks`);
  positionArray(workspace.candidateLavaSources, `${name}.candidateLavaSources`);
  positionArray(workspace.rejectedLavaSources, `${name}.rejectedLavaSources`);
  if (workspace.waterSource !== undefined) {
    position(workspace.waterSource, `${name}.waterSource`);
  }
  positionArray(workspace.waterFlow, `${name}.waterFlow`);
  enumValue(workspace.bucketState, ["UNKNOWN", "EMPTY", "WATER", "LAVA"], `${name}.bucketState`);
  enumValue(workspace.ignitionState, ["NOT_ATTEMPTED", "IGNITED"], `${name}.ignitionState`);
  enumValue(workspace.interiorState, ["UNKNOWN", "CLEAR", "PORTAL"], `${name}.interiorState`);
  nonNegativeInteger(workspace.entryAttempts, `${name}.entryAttempts`);
  timestamp(workspace.observedAt, `${name}.observedAt`);
  timestamp(workspace.updatedAt, `${name}.updatedAt`);
  if (workspace.abandonedReason !== undefined) {
    nonEmptyString(workspace.abandonedReason, `${name}.abandonedReason`);
  }
}

function positionArray(value: unknown, name: string): void {
  if (!Array.isArray(value)) {
    throw new TypeError(`${name} must be an array`);
  }
  value.forEach((item, index) => position(item, `${name}[${index}]`));
}

function nonNegativeIntegerRecord(value: unknown, name: string): void {
  const values = record(value, name);
  for (const [key, count] of Object.entries(values)) {
    nonEmptyString(key, `${name} key`);
    nonNegativeInteger(count, `${name}.${key}`);
  }
}

function deathPosition(value: unknown, path: string): void {
  position(value, path);
  const item = record(value, path);
  if (item.inventoryCounts !== undefined) {
    const counts = record(item.inventoryCounts, `${path}.inventoryCounts`);
    for (const [itemId, count] of Object.entries(counts)) {
      nonEmptyString(itemId, `${path}.inventoryCounts item ID`);
      nonNegativeInteger(count, `${path}.inventoryCounts.${itemId}`);
    }
  }
  if (item.itemExpiresAt !== undefined) {
    timestamp(item.itemExpiresAt, `${path}.itemExpiresAt`);
  }
}

function memoryEntries(
  value: unknown,
  name: string,
  validateValue: (value: unknown, name: string) => void,
): void {
  if (!Array.isArray(value)) {
    throw new TypeError(`${name} must be an array`);
  }
  value.forEach((entry, index) => memoryEntry(entry, `${name}[${index}]`, validateValue));
}

function memoryEntry(
  value: unknown,
  name: string,
  validateValue: (value: unknown, name: string) => void,
): void {
  const item = record(value, name);
  nonEmptyString(item.key, `${name}.key`);
  validateValue(item.value, `${name}.value`);
  timestamp(item.observedAt, `${name}.observedAt`);
  if (item.expiresAt !== undefined) {
    timestamp(item.expiresAt, `${name}.expiresAt`);
  }
  confidence(item.confidence, `${name}.confidence`);
}

function blockObservation(value: unknown, name: string): void {
  const block = record(value, name);
  nonEmptyString(block.blockId, `${name}.blockId`);
  position(block.position, `${name}.position`);
  const properties = record(block.properties, `${name}.properties`);
  for (const [key, property] of Object.entries(properties)) {
    if (typeof property !== "string") {
      throw new TypeError(`${name}.properties.${key} must be a string`);
    }
  }
  booleanValue(block.diggable, `${name}.diggable`);
  booleanValue(block.replaceable, `${name}.replaceable`);
  if (block.solid !== undefined) {
    booleanValue(block.solid, `${name}.solid`);
  }
  booleanValue(block.interactive, `${name}.interactive`);
  timestamp(block.observedAt, `${name}.observedAt`);
}

function entityObservation(value: unknown, name: string): void {
  const entity = record(value, name);
  nonEmptyString(entity.connectionEpoch, `${name}.connectionEpoch`);
  nonNegativeInteger(entity.networkId, `${name}.networkId`);
  nonEmptyString(entity.entityType, `${name}.entityType`);
  position(entity.position, `${name}.position`);
  vector(entity.velocity, `${name}.velocity`);
  booleanValue(entity.alive, `${name}.alive`);
  if (entity.uuid !== undefined) {
    nonEmptyString(entity.uuid, `${name}.uuid`);
  }
  if (entity.itemId !== undefined) {
    nonEmptyString(entity.itemId, `${name}.itemId`);
  }
  if (entity.health !== undefined) {
    finiteNumber(entity.health, `${name}.health`);
  }
  timestamp(entity.observedAt, `${name}.observedAt`);
}

function position(value: unknown, name: string): void {
  const point = record(value, name);
  vector(point, name);
  nonEmptyString(point.dimension, `${name}.dimension`);
}

function vector(value: unknown, name: string): void {
  const point = record(value, name);
  finiteNumber(point.x, `${name}.x`);
  finiteNumber(point.y, `${name}.y`);
  finiteNumber(point.z, `${name}.z`);
}

function record(value: unknown, name: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError(`${name} must be an object`);
  }
  return value as Record<string, unknown>;
}

function nonEmptyString(value: unknown, name: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be a non-empty string`);
  }
  return value;
}

function unsignedIntegerString(value: unknown, name: string): void {
  const source = nonEmptyString(value, name);
  if (!/^(?:0|[1-9][0-9]*)$/.test(source)) {
    throw new TypeError(`${name} must be an unsigned integer string`);
  }
}

function stringArray(value: unknown, name: string): void {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new TypeError(`${name} must be an array of strings`);
  }
}

function booleanValue(value: unknown, name: string): void {
  if (typeof value !== "boolean") {
    throw new TypeError(`${name} must be a boolean`);
  }
}

function finiteNumber(value: unknown, name: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new TypeError(`${name} must be a finite number`);
  }
  return value;
}

function positiveInteger(value: unknown, name: string): void {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) {
    throw new TypeError(`${name} must be a positive safe integer`);
  }
}

function nonNegativeInteger(value: unknown, name: string): void {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new TypeError(`${name} must be a non-negative safe integer`);
  }
}

function timestamp(value: unknown, name: string): void {
  const source = nonEmptyString(value, name);
  if (!Number.isFinite(Date.parse(source))) {
    throw new TypeError(`${name} must be an ISO-8601 timestamp`);
  }
}

function confidence(value: unknown, name: string): void {
  const score = finiteNumber(value, name);
  if (score < 0 || score > 1) {
    throw new TypeError(`${name} must be between 0 and 1`);
  }
}

function enumValue(value: unknown, values: readonly unknown[], name: string): void {
  if (!values.includes(value)) {
    throw new TypeError(`${name} is not a supported value`);
  }
}

function clone<T>(value: T): T {
  return structuredClone(value);
}
