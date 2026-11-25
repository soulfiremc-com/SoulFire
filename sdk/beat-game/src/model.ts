import type { BeatGameCoordinator } from "./coordinator.js";
import type { BeatGameStrategyHooks } from "./policy.js";
import type { BeatGameCheckpointStore } from "./stores.js";

export const BEAT_GAME_CHECKPOINT_SCHEMA_VERSION = 3 as const;

export const BeatGamePhase = {
  PREPARE_OVERWORLD: "PREPARE_OVERWORLD",
  ENTER_NETHER: "ENTER_NETHER",
  COLLECT_NETHER_RESOURCES: "COLLECT_NETHER_RESOURCES",
  RETURN_TO_OVERWORLD: "RETURN_TO_OVERWORLD",
  LOCATE_STRONGHOLD: "LOCATE_STRONGHOLD",
  ACTIVATE_END_PORTAL: "ACTIVATE_END_PORTAL",
  FIGHT_ENDER_DRAGON: "FIGHT_ENDER_DRAGON",
  EXIT_END: "EXIT_END",
  COMPLETE: "COMPLETE",
} as const;

export type BeatGamePhase = (typeof BeatGamePhase)[keyof typeof BeatGamePhase];

export const BeatGameRunStatus = {
  CREATED: "CREATED",
  RUNNING: "RUNNING",
  PAUSED: "PAUSED",
  RECOVERING: "RECOVERING",
  STOPPED: "STOPPED",
  COMPLETED: "COMPLETED",
  FAILED: "FAILED",
} as const;

export type BeatGameRunStatus = (typeof BeatGameRunStatus)[keyof typeof BeatGameRunStatus];

export const BeatGameTeamRole = {
  LEAD: "LEAD",
  PORTAL_ENGINEER: "PORTAL_ENGINEER",
  NETHER_RUNNER: "NETHER_RUNNER",
  STRONGHOLD_SCOUT: "STRONGHOLD_SCOUT",
  END_SUPPORT: "END_SUPPORT",
} as const;

export type BeatGameTeamRole = (typeof BeatGameTeamRole)[keyof typeof BeatGameTeamRole];

export const BeatGameObjective = {
  BOOTSTRAP: "BOOTSTRAP",
  NETHER_ENTRY: "NETHER_ENTRY",
  NETHER_RESOURCES: "NETHER_RESOURCES",
  STRONGHOLD: "STRONGHOLD",
  END_ASSAULT: "END_ASSAULT",
  COMPLETE: "COMPLETE",
} as const;

export type BeatGameObjective = (typeof BeatGameObjective)[keyof typeof BeatGameObjective];

export const PortalStrategy = {
  AUTO: "AUTO",
  OBSIDIAN: "OBSIDIAN",
  CAST: "CAST",
} as const;

export type PortalStrategy = (typeof PortalStrategy)[keyof typeof PortalStrategy];

// How much risk the runner accepts to save time. SAFE hides through every
// night it is not fully equipped for and walks back for every corpse. FAST
// travels at night when armed and fed, retreats only when badly hurt, and
// gives up on a distant corpse after one failed preparation.
export const BeatGameRiskProfile = {
  SAFE: "SAFE",
  FAST: "FAST",
} as const;

export type BeatGameRiskProfile = (typeof BeatGameRiskProfile)[keyof typeof BeatGameRiskProfile];

export const BeatGamePathSearchMode = {
  PRECISION: "PRECISION",
  NORMAL: "NORMAL",
  URGENT: "URGENT",
  ESCAPE: "ESCAPE",
} as const;

export type BeatGamePathSearchMode =
  (typeof BeatGamePathSearchMode)[keyof typeof BeatGamePathSearchMode];

export const BeatGameDurableSkillKind = {
  PORTAL_CONSTRUCTION: "PORTAL_CONSTRUCTION",
  LIQUID_HANDLING: "LIQUID_HANDLING",
  PROTECTED_STRUCTURE: "PROTECTED_STRUCTURE",
  STRONGHOLD_TRIANGULATION: "STRONGHOLD_TRIANGULATION",
  NETHER_CORRIDOR: "NETHER_CORRIDOR",
  BLAZE_COMBAT: "BLAZE_COMBAT",
  PEARL_ACQUISITION: "PEARL_ACQUISITION",
  END_PORTAL_ENTRY: "END_PORTAL_ENTRY",
  DRAGON_COMBAT: "DRAGON_COMBAT",
  DEATH_RECOVERY: "DEATH_RECOVERY",
} as const;

export type BeatGameDurableSkillKind =
  (typeof BeatGameDurableSkillKind)[keyof typeof BeatGameDurableSkillKind];

export const BeatGameDurableSkillStatus = {
  ACTIVE: "ACTIVE",
  SUSPENDED: "SUSPENDED",
  COMPLETED: "COMPLETED",
  ABANDONED: "ABANDONED",
} as const;

export type BeatGameDurableSkillStatus =
  (typeof BeatGameDurableSkillStatus)[keyof typeof BeatGameDurableSkillStatus];

export interface BeatGamePosition {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly dimension: string;
}

export interface BeatGameBlockPosition {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly dimension: string;
}

export interface BeatGameRotation {
  readonly yaw: number;
  readonly pitch: number;
}

export interface BeatGamePathPolicy {
  readonly allowMining: boolean;
  readonly allowPlacing: boolean;
  readonly maxFallDistance: number;
  readonly maxSearchTimeMs: number;
  readonly avoidFluids?: boolean;
  readonly additionalPlaceItemIds?: readonly string[];
  readonly protectedBlocks?: readonly BeatGameBlockPosition[];
  readonly sprint?: boolean;
  readonly minimumY?: number;
  readonly maximumY?: number;
  readonly searchMode?: BeatGamePathSearchMode;
  readonly maximumQualityBound?: number;
  readonly maximumExpandedStates?: number;
  readonly maxParkourGap?: number;
  readonly smoothCamera?: boolean;
  /// Pitch in degrees, down positive, that the bot holds while it walks.
  readonly walkingPitch?: number;
}

export interface BeatGameStrategy {
  readonly portalStrategy: PortalStrategy;
  readonly riskProfile: BeatGameRiskProfile;
  readonly targetFoodCount: number;
  readonly targetLogCount: number;
  readonly targetCobblestoneCount: number;
  readonly targetIronCount: number;
  readonly targetGoldCount: number;
  readonly targetBlazeRodCount: number;
  readonly targetEnderPearlCount: number;
  readonly targetEyeCount: number;
  readonly targetObsidianCount: number;
  readonly minimumHealth: number;
  readonly eatBelowFood: number;
  readonly blockSearchRadius: number;
  readonly entitySearchRadius: number;
  readonly explorationRadius: number;
  readonly maximumActionRetries: number;
  readonly actionTimeoutMs: number;
  readonly observationPollMs: number;
  readonly claimTtlMs: number;
  readonly maximumConcurrentEndEntries: number;
  readonly path: BeatGamePathPolicy;
}

export type BeatGameStrategyOptions = Partial<Omit<BeatGameStrategy, "path">> & {
  readonly path?: Partial<BeatGamePathPolicy>;
};

export const defaultBeatGameStrategy: BeatGameStrategy = {
  portalStrategy: PortalStrategy.AUTO,
  riskProfile: BeatGameRiskProfile.SAFE,
  targetFoodCount: 16,
  targetLogCount: 8,
  targetCobblestoneCount: 20,
  targetIronCount: 7,
  targetGoldCount: 24,
  targetBlazeRodCount: 7,
  targetEnderPearlCount: 14,
  targetEyeCount: 12,
  targetObsidianCount: 10,
  minimumHealth: 18,
  eatBelowFood: 14,
  blockSearchRadius: 48,
  entitySearchRadius: 48,
  explorationRadius: 192,
  maximumActionRetries: 5,
  actionTimeoutMs: 120_000,
  observationPollMs: 500,
  claimTtlMs: 30_000,
  maximumConcurrentEndEntries: 1,
  path: {
    allowMining: true,
    allowPlacing: true,
    maxFallDistance: 3,
    maxSearchTimeMs: 30_000,
    searchMode: BeatGamePathSearchMode.NORMAL,
    maximumExpandedStates: 50_000,
    maxParkourGap: 0,
    // A level view meets the eyes of an enderman standing a block lower, and
    // it attacks. In a real game one did so from 18 to 40 blocks away, while
    // a view thirty degrees down left it calm. Live bots walking the uneven
    // End island were attacked by endermen on every visit.
    walkingPitch: 30,
  },
};

// Trades safety margins for speed. Food targets cover the trip with a little
// spare, and the bot retreats only when it is badly hurt.
export const fastBeatGameStrategy: BeatGameStrategy = {
  ...defaultBeatGameStrategy,
  riskProfile: BeatGameRiskProfile.FAST,
  targetFoodCount: 8,
  targetEnderPearlCount: 12,
  minimumHealth: 12,
};

export interface BeatGameItemRequirement {
  readonly key: string;
  readonly itemIds: readonly string[];
  readonly tags: readonly string[];
  readonly targetCount: number;
  readonly currentCount: number;
  readonly priority: number;
  readonly satisfied: boolean;
}

export interface BeatGameInventory {
  readonly revision: bigint;
  readonly selectedHotbarSlot: number;
  readonly emptyPlayerSlots?: number;
  readonly pathBuildingBlockCount?: number;
  readonly counts: Readonly<Record<string, number>>;
  readonly remainingDurability?: Readonly<Record<string, number>>;
  readonly hotbar: Readonly<Record<number, string>>;
}

export interface BeatGamePlayerObservation {
  readonly position: BeatGamePosition;
  readonly rotation: BeatGameRotation;
  readonly velocity: Readonly<{ x: number; y: number; z: number }>;
  readonly onGround: boolean;
  readonly equipment: Readonly<Record<string, string>>;
  readonly health: number;
  readonly maxHealth: number;
  readonly food: number;
  readonly air: number;
  readonly maxAir: number;
  readonly fireTicks: number;
  readonly dead: boolean;
  readonly sleeping: boolean;
  readonly usingItem: boolean;
  readonly connectionEpoch: string;
  readonly revision: bigint;
}

export interface BeatGameEntityObservation {
  readonly connectionEpoch: string;
  readonly networkId: number;
  readonly uuid?: string;
  readonly entityType: string;
  readonly position: BeatGamePosition;
  readonly velocity: Readonly<{ x: number; y: number; z: number }>;
  readonly alive: boolean;
  readonly health?: number;
  readonly itemId?: string;
  readonly target?: BeatGameEntityReference;
  readonly aggressive?: boolean;
  readonly creeper?: BeatGameCreeperObservation;
  readonly sheep?: BeatGameSheepObservation;
  readonly rotation?: Readonly<{ yaw: number; pitch: number }>;
  readonly observedAt: string;
}

export interface BeatGameSheepObservation {
  // The dye color name, such as "white", which names the wool it drops.
  readonly color: string;
  readonly sheared: boolean;
}

export interface BeatGameCreeperObservation {
  readonly fuseProgress: number;
  readonly swellDirection: number;
  readonly powered: boolean;
  readonly ignited: boolean;
}

export interface BeatGameEntityReference {
  readonly connectionEpoch: string;
  readonly networkId: number;
  readonly uuid?: string;
}

export interface BeatGameBlockObservation {
  readonly blockId: string;
  readonly position: BeatGameBlockPosition;
  readonly properties: Readonly<Record<string, string>>;
  readonly hardness?: number;
  readonly diggable: boolean;
  readonly replaceable: boolean;
  readonly solid?: boolean;
  readonly interactive: boolean;
  readonly effectiveToolTags?: readonly string[];
  readonly observedAt: string;
}

export interface BeatGameObservation {
  readonly observedAt: string;
  readonly player: BeatGamePlayerObservation;
  readonly inventory: BeatGameInventory;
}

export interface BeatGameEyeSample {
  readonly origin: BeatGamePosition;
  readonly direction: Readonly<{ x: number; z: number }>;
  readonly observedAt: string;
  readonly confidence: number;
}

export interface BeatGameMemoryEntry<T> {
  readonly key: string;
  readonly value: T;
  readonly observedAt: string;
  readonly expiresAt?: string;
  readonly confidence: number;
}

export interface BeatGameDeathPosition extends BeatGamePosition {
  readonly inventoryCounts?: Readonly<Record<string, number>>;
  readonly itemExpiresAt?: string;
}

export interface BeatGameExplorationFrontier {
  readonly progressVersion?: 2;
  readonly origin: BeatGamePosition;
  readonly nextIndex: number;
  readonly target?: BeatGamePosition;
  readonly targetAttempts?: number;
  readonly lastPosition?: BeatGamePosition;
  readonly totalAdvances?: number;
  /// Advances since the spiral last started from its current origin.
  readonly originAdvances?: number;
}

export interface BeatGamePortalWorkspace {
  readonly workspaceId: string;
  readonly origin: BeatGameBlockPosition;
  readonly axis: "x" | "z";
  readonly method: "OBSIDIAN" | "CAST";
  readonly status: "RESERVED" | "BUILDING" | "IGNITED" | "ENTERING" | "ENTERED" | "ABANDONED";
  readonly targetFrame: readonly BeatGameBlockPosition[];
  readonly observedFrame: readonly BeatGameBlockPosition[];
  readonly interior: readonly BeatGameBlockPosition[];
  readonly protectedBlocks: readonly BeatGameBlockPosition[];
  readonly candidateLavaSources: readonly BeatGameBlockPosition[];
  readonly rejectedLavaSources: readonly BeatGameBlockPosition[];
  readonly waterSource?: BeatGameBlockPosition;
  readonly waterFlow: readonly BeatGameBlockPosition[];
  readonly bucketState: "UNKNOWN" | "EMPTY" | "WATER" | "LAVA";
  readonly ignitionState: "NOT_ATTEMPTED" | "IGNITED";
  readonly interiorState: "UNKNOWN" | "CLEAR" | "PORTAL";
  readonly entryAttempts: number;
  readonly observedAt: string;
  readonly updatedAt: string;
  readonly abandonedReason?: string;
}

export interface BeatGameDurableSkillState {
  readonly skillId: string;
  readonly kind: BeatGameDurableSkillKind;
  readonly phase: BeatGamePhase;
  readonly action: string;
  readonly status: BeatGameDurableSkillStatus;
  readonly substep: string;
  readonly targets: readonly BeatGamePosition[];
  readonly protectedBlocks: readonly BeatGameBlockPosition[];
  readonly protectedItemIds: readonly string[];
  readonly completedWorldChanges: readonly BeatGameBlockPosition[];
  readonly requiredResources: Readonly<Record<string, number>>;
  readonly retries: Readonly<Record<string, number>>;
  readonly completionEvidence?: string;
  readonly portalWorkspace?: BeatGamePortalWorkspace;
  readonly startedAt: string;
  readonly updatedAt: string;
}

export interface BeatGameWorldPlace {
  readonly key: string;
  readonly kind:
    | "FORTRESS"
    | "BASTION"
    | "BLAZE_SPAWNER"
    | "SAFE_CORRIDOR"
    | "SHELTER"
    | "END_ENTRY"
    | "END_FIGHT"
    | "WARPED_FOREST";
  readonly position: BeatGamePosition;
  readonly observedAt: string;
  readonly confidence: number;
  readonly invalidationReason?: string;
}

export interface BeatGameWorldMemory {
  readonly blocks: readonly BeatGameMemoryEntry<BeatGameBlockObservation>[];
  readonly entities: readonly BeatGameMemoryEntry<BeatGameEntityObservation>[];
  readonly containers: readonly BeatGameMemoryEntry<BeatGameBlockObservation>[];
  readonly portals: readonly BeatGameMemoryEntry<BeatGameBlockObservation>[];
  readonly portalWorkspaces: readonly BeatGamePortalWorkspace[];
  readonly places: readonly BeatGameWorldPlace[];
  readonly skillHistory: readonly BeatGameDurableSkillState[];
  readonly unreachable: readonly BeatGameMemoryEntry<BeatGamePosition>[];
  readonly eyeSamples: readonly BeatGameEyeSample[];
  readonly deathPositions: readonly BeatGameMemoryEntry<BeatGameDeathPosition>[];
  readonly explorationFrontiers?: Readonly<Record<string, BeatGameExplorationFrontier>>;
  readonly deathRecoveryFailures?: Readonly<Record<string, number>>;
  /// Set once the bot carried its whole inventory through a death, which
  /// means the world keeps inventories and no death ever drops items.
  readonly keepsInventoryOnDeath?: boolean;
  readonly latestDeath?: BeatGameMemoryEntry<BeatGamePosition>;
  readonly strongholdEstimate?: BeatGamePosition;
  /// Empty frames the bot saw in the stronghold portal room, which is how
  /// many eyes it needs to open the portal.
  readonly emptyPortalFrames?: number;
  /// Center of the End portal frames the bot found in the stronghold.
  readonly portalRoomCenter?: BeatGamePosition;
  /// The bed near the portal room the bot leaves placed as its respawn point.
  readonly respawnBed?: BeatGamePosition;
}

export const emptyBeatGameWorldMemory = (): BeatGameWorldMemory => ({
  blocks: [],
  entities: [],
  containers: [],
  portals: [],
  portalWorkspaces: [],
  places: [],
  skillHistory: [],
  unreachable: [],
  eyeSamples: [],
  deathPositions: [],
  explorationFrontiers: {},
});

export interface BeatGameClaim {
  readonly key: string;
  readonly runId: string;
  readonly botId: string;
  readonly purpose: string;
  readonly createdAt: string;
  readonly expiresAt: string;
  readonly fencingToken: number;
}

export interface BeatGameTeamMember {
  readonly instanceId: string;
  readonly botId: string;
  readonly role: BeatGameTeamRole;
  readonly phase: BeatGamePhase;
  readonly status: BeatGameRunStatus;
  readonly updatedAt: string;
}

export type BeatGameDiscoveryKind =
  | "resource"
  | "structure"
  | "portal"
  | "stronghold"
  | "eye-sample"
  | "death";

export interface BeatGameTeamDiscovery {
  readonly key: string;
  readonly kind: BeatGameDiscoveryKind;
  readonly botId: string;
  readonly position: BeatGamePosition;
  readonly observedAt: string;
  readonly expiresAt?: string;
  readonly confidence: number;
  readonly metadata?: Readonly<Record<string, string | number | boolean>>;
}

export interface BeatGameTeamSnapshot {
  readonly teamId: string;
  readonly revision: number;
  readonly objective: BeatGameObjective;
  readonly leaderBotId?: string;
  readonly leaderFencingToken: number;
  readonly members: readonly BeatGameTeamMember[];
  readonly claims: readonly BeatGameClaim[];
  readonly discoveries: readonly BeatGameTeamDiscovery[];
  readonly sharedRequirements: Readonly<Record<string, number>>;
  readonly updatedAt: string;
}

export interface BeatGamePlannerState {
  readonly phase: BeatGamePhase;
  readonly status: BeatGameRunStatus;
  readonly objective: string;
  readonly requirements: readonly BeatGameItemRequirement[];
  readonly currentAction?: string;
  readonly currentActionId?: string;
  readonly retryCount: number;
  readonly completedActions: readonly string[];
  readonly startedAt: string;
  readonly updatedAt: string;
}

export interface BeatGameStableActionResult {
  readonly action: string;
  readonly phase: BeatGamePhase;
  readonly completedAt: string;
  readonly evidence: "TASK_RESULT" | "OBSERVED_STATE" | "OBSERVATION_AFTER_UNCERTAIN_RESULT";
  readonly connectionEpoch: string;
  readonly playerRevision: string;
  readonly inventoryRevision: string;
}

export interface BeatGameCheckpoint {
  readonly schemaVersion: typeof BEAT_GAME_CHECKPOINT_SCHEMA_VERSION;
  readonly runId: string;
  readonly teamId: string;
  readonly instanceId: string;
  readonly botId: string;
  readonly role: BeatGameTeamRole;
  readonly revision: number;
  readonly connectionEpoch: string;
  readonly planner: BeatGamePlannerState;
  readonly memory: BeatGameWorldMemory;
  readonly activeSkill?: BeatGameDurableSkillState;
  readonly lastStableAction?: BeatGameStableActionResult;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface BeatGameSnapshot {
  readonly checkpoint: BeatGameCheckpoint;
  readonly observation?: BeatGameObservation;
  readonly team?: BeatGameTeamSnapshot;
}

export interface BeatGameResult {
  readonly runId: string;
  readonly teamId: string;
  readonly instanceId: string;
  readonly botId: string;
  readonly completedAt: string;
  readonly durationMs: number;
  readonly finalCheckpoint: BeatGameCheckpoint;
}

interface BeatGameEventBase {
  readonly sequence: bigint;
  readonly timestamp: string;
  readonly runId: string;
  readonly instanceId: string;
  readonly botId: string;
  readonly phase: BeatGamePhase;
}

export type BeatGameEvent =
  | (BeatGameEventBase & {
      readonly type: "run-started" | "run-paused" | "run-resumed" | "run-stopped" | "run-completed";
    })
  | (BeatGameEventBase & {
      readonly type: "phase-changed";
      readonly previous: BeatGamePhase;
      readonly current: BeatGamePhase;
    })
  | (BeatGameEventBase & {
      readonly type: "objective-changed";
      readonly objective: string;
    })
  | (BeatGameEventBase & {
      readonly type: "requirement-discovered" | "requirement-updated" | "requirement-satisfied";
      readonly requirement: BeatGameItemRequirement;
    })
  | (BeatGameEventBase & {
      readonly type: "requirement-claimed";
      readonly requirement: BeatGameItemRequirement;
      readonly claim: BeatGameClaim;
    })
  | (BeatGameEventBase & {
      readonly type:
        | "action-started"
        | "action-retried"
        | "action-progressed"
        | "action-succeeded"
        | "action-failed";
      readonly action: string;
      readonly attempt: number;
      readonly detail?: string;
    })
  | (BeatGameEventBase & {
      readonly type: "checkpoint-saved" | "checkpoint-restored";
      readonly revision: number;
    })
  | (BeatGameEventBase & {
      readonly type: "bot-disconnected" | "bot-recovered" | "death-observed" | "items-recovered";
      readonly detail?: string;
    })
  | (BeatGameEventBase & {
      readonly type: "team-role-changed";
      readonly role: BeatGameTeamRole;
    })
  | (BeatGameEventBase & {
      readonly type: "team-claim-changed";
      readonly claim: BeatGameClaim;
      readonly released: boolean;
    })
  | (BeatGameEventBase & {
      readonly type: "observation-recorded";
      readonly observedAt: string;
      readonly connectionEpoch: string;
      readonly playerRevision: string;
      readonly inventoryRevision: string;
    })
  | (BeatGameEventBase & {
      readonly type: "diagnostic";
      readonly message: string;
      readonly data?: Readonly<Record<string, unknown>>;
    });

export interface BeatGameTeamOptions {
  readonly teamId?: string;
  readonly role?: BeatGameTeamRole;
}

export interface BeatGameOptions {
  readonly runId?: string;
  readonly strategy?: BeatGameStrategyOptions;
  readonly checkpointStore?: BeatGameCheckpointStore;
  readonly coordinator?: BeatGameCoordinator;
  readonly team?: BeatGameTeamOptions;
  readonly hooks?: BeatGameStrategyHooks;
}

export interface BeatGameTeamRunOptions {
  readonly teamId?: string;
  readonly strategy?: BeatGameStrategyOptions;
  readonly checkpointStore?: BeatGameCheckpointStore;
  readonly coordinator?: BeatGameCoordinator;
  readonly hooks?: BeatGameStrategyHooks;
}

export interface EyeTriangulation {
  readonly position: BeatGamePosition;
  readonly confidence: number;
  readonly baseline: number;
  readonly angleDegrees: number;
}
