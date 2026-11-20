import {
  BeatGameDurableSkillKind,
  BeatGameDurableSkillStatus,
  type BeatGameCheckpoint,
  type BeatGameDurableSkillState,
  type BeatGamePortalWorkspace,
} from "./model.js";
import type { BeatGamePlannerDecision } from "./planner.js";

const SKILL_HISTORY_LIMIT = 64;
const PORTAL_WORKSPACE_HISTORY_LIMIT = 16;

type ActionDecision = Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>;

export function durableSkillKindForDecision(
  decision: ActionDecision,
): BeatGameDurableSkillKind | undefined {
  switch (decision.type) {
    case "recover-death": {
      return BeatGameDurableSkillKind.DEATH_RECOVERY;
    }
    case "build-and-enter-nether":
    case "return-through-portal": {
      return BeatGameDurableSkillKind.PORTAL_CONSTRUCTION;
    }
    case "throw-eye":
    case "search-stronghold": {
      return BeatGameDurableSkillKind.STRONGHOLD_TRIANGULATION;
    }
    case "activate-end-portal": {
      return BeatGameDurableSkillKind.END_PORTAL_ENTRY;
    }
    case "fight-ender-dragon": {
      return BeatGameDurableSkillKind.DRAGON_COMBAT;
    }
    case "satisfy-requirement": {
      return durableRequirementSkill(decision.requirement.key);
    }
    case "eat":
    case "exit-end":
    case "prepare-equipment":
    case "retreat": {
      return undefined;
    }
    default: {
      return decision satisfies never;
    }
  }
}

export function startOrResumeDurableSkill(
  checkpoint: BeatGameCheckpoint,
  decision: ActionDecision,
  now: string,
  createId: () => string = () => crypto.randomUUID(),
): BeatGameCheckpoint {
  const kind = durableSkillKindForDecision(decision);
  if (kind === undefined) {
    return checkpoint;
  }
  const current = checkpoint.activeSkill;
  if (current?.kind === kind && current.action === decision.action) {
    return {
      ...checkpoint,
      activeSkill: {
        ...current,
        status: BeatGameDurableSkillStatus.ACTIVE,
        updatedAt: now,
      },
    };
  }
  const memory =
    current === undefined
      ? checkpoint.memory
      : {
          ...checkpoint.memory,
          skillHistory: appendSkillHistory(checkpoint.memory.skillHistory, {
            ...current,
            status: BeatGameDurableSkillStatus.ABANDONED,
            completionEvidence: `superseded by ${kind} while starting ${decision.action}`,
            updatedAt: now,
          }),
        };
  return {
    ...checkpoint,
    memory,
    activeSkill: createDurableSkill(
      createId(),
      kind,
      checkpoint.planner.phase,
      decision.action,
      now,
    ),
  };
}

export function startDurableSkillIfIdle(
  checkpoint: BeatGameCheckpoint,
  kind: BeatGameDurableSkillKind,
  action: string,
  now: string,
  createId: () => string = () => crypto.randomUUID(),
): BeatGameCheckpoint {
  const current = checkpoint.activeSkill;
  if (current !== undefined) {
    return current.kind === kind && current.action === action
      ? {
          ...checkpoint,
          activeSkill: {
            ...current,
            status: BeatGameDurableSkillStatus.ACTIVE,
            updatedAt: now,
          },
        }
      : checkpoint;
  }
  return {
    ...checkpoint,
    activeSkill: createDurableSkill(createId(), kind, checkpoint.planner.phase, action, now),
  };
}

export function suspendDurableSkill(
  checkpoint: BeatGameCheckpoint,
  reason: string,
  now: string,
): BeatGameCheckpoint {
  const skill = checkpoint.activeSkill;
  if (skill === undefined) {
    return checkpoint;
  }
  return {
    ...checkpoint,
    activeSkill: {
      ...skill,
      status: BeatGameDurableSkillStatus.SUSPENDED,
      retries: {
        ...skill.retries,
        [reason]: (skill.retries[reason] ?? 0) + 1,
      },
      updatedAt: now,
    },
  };
}

export function advanceDurableSkill(
  checkpoint: BeatGameCheckpoint,
  substep: string,
  now: string,
  update: Partial<
    Pick<
      BeatGameDurableSkillState,
      | "completedWorldChanges"
      | "completionEvidence"
      | "portalWorkspace"
      | "protectedBlocks"
      | "protectedItemIds"
      | "requiredResources"
      | "targets"
    >
  > = {},
): BeatGameCheckpoint {
  const skill = checkpoint.activeSkill;
  if (skill === undefined) {
    return checkpoint;
  }
  const activeSkill = {
    ...skill,
    ...update,
    status: BeatGameDurableSkillStatus.ACTIVE,
    substep,
    updatedAt: now,
  };
  return {
    ...checkpoint,
    activeSkill,
    memory:
      activeSkill.portalWorkspace === undefined
        ? checkpoint.memory
        : {
            ...checkpoint.memory,
            portalWorkspaces: upsertPortalWorkspace(
              checkpoint.memory.portalWorkspaces,
              activeSkill.portalWorkspace,
            ),
          },
  };
}

export function completeDurableSkill(
  checkpoint: BeatGameCheckpoint,
  action: string,
  evidence: string,
  now: string,
): BeatGameCheckpoint {
  const skill = checkpoint.activeSkill;
  if (skill === undefined || skill.action !== action) {
    return checkpoint;
  }
  const completed = {
    ...skill,
    status: BeatGameDurableSkillStatus.COMPLETED,
    substep: "completed",
    completionEvidence: evidence,
    updatedAt: now,
  } satisfies BeatGameDurableSkillState;
  const { activeSkill: _, ...withoutActiveSkill } = checkpoint;
  return {
    ...withoutActiveSkill,
    memory: {
      ...checkpoint.memory,
      skillHistory: appendSkillHistory(checkpoint.memory.skillHistory, completed),
      portalWorkspaces:
        completed.portalWorkspace === undefined
          ? checkpoint.memory.portalWorkspaces
          : upsertPortalWorkspace(checkpoint.memory.portalWorkspaces, completed.portalWorkspace),
    },
  };
}

// Ends the active skill. Its portal workspace stays resumable unless
// `abandonPortalWorkspace` is set, because a restart can still finish a
// half-built frame, but a site that failed every attempt must not be reused.
export function abandonActiveDurableSkill(
  checkpoint: BeatGameCheckpoint,
  evidence: string,
  now: string,
  options: Readonly<{ abandonPortalWorkspace?: boolean }> = {},
): BeatGameCheckpoint {
  const skill = checkpoint.activeSkill;
  if (skill === undefined) {
    return checkpoint;
  }
  const abandoned = {
    ...skill,
    status: BeatGameDurableSkillStatus.ABANDONED,
    completionEvidence: evidence,
    updatedAt: now,
    ...(options.abandonPortalWorkspace === true &&
    skill.portalWorkspace !== undefined &&
    !hasCompletePortalFrame(skill.portalWorkspace)
      ? {
          portalWorkspace: {
            ...skill.portalWorkspace,
            status: "ABANDONED" as const,
            abandonedReason: evidence,
            updatedAt: now,
          },
        }
      : {}),
  } satisfies BeatGameDurableSkillState;
  const { activeSkill: _, ...withoutActiveSkill } = checkpoint;
  return {
    ...withoutActiveSkill,
    memory: {
      ...checkpoint.memory,
      skillHistory: appendSkillHistory(checkpoint.memory.skillHistory, abandoned),
      portalWorkspaces:
        abandoned.portalWorkspace === undefined
          ? checkpoint.memory.portalWorkspaces
          : upsertPortalWorkspace(checkpoint.memory.portalWorkspaces, abandoned.portalWorkspace),
    },
  };
}

/// A finished frame only needs lighting. A live run abandoned a complete
/// cast frame after lava kept flowing into it and cast a new one beside it,
/// which cost another lava search, so failures there keep the site.
function hasCompletePortalFrame(workspace: BeatGamePortalWorkspace): boolean {
  return (
    workspace.targetFrame.length > 0 &&
    workspace.targetFrame.every((target) =>
      workspace.observedFrame.some(
        (observed) =>
          observed.x === target.x &&
          observed.y === target.y &&
          observed.z === target.z &&
          observed.dimension === target.dimension,
      ),
    )
  );
}

export function latestResumablePortalWorkspace(
  checkpoint: BeatGameCheckpoint,
): BeatGamePortalWorkspace | undefined {
  const active = checkpoint.activeSkill?.portalWorkspace;
  if (active !== undefined && active.status !== "ABANDONED") {
    return active;
  }
  // A finished frame beats a new one, even one abandoned before failures
  // kept complete frames: lighting it only needs flint and steel.
  const completeFrame = checkpoint.memory.portalWorkspaces.findLast(
    (workspace) => workspace.status !== "ENTERED" && hasCompletePortalFrame(workspace),
  );
  if (completeFrame !== undefined) {
    return completeFrame.status === "ABANDONED"
      ? { ...completeFrame, status: "BUILDING" }
      : completeFrame;
  }
  return checkpoint.memory.portalWorkspaces.findLast(
    (workspace) => workspace.status !== "ABANDONED" && workspace.status !== "ENTERED",
  );
}

function createDurableSkill(
  skillId: string,
  kind: BeatGameDurableSkillKind,
  phase: BeatGameCheckpoint["planner"]["phase"],
  action: string,
  now: string,
): BeatGameDurableSkillState {
  return {
    skillId,
    kind,
    phase,
    action,
    status: BeatGameDurableSkillStatus.ACTIVE,
    substep: "observe",
    targets: [],
    protectedBlocks: [],
    protectedItemIds: [],
    completedWorldChanges: [],
    requiredResources: {},
    retries: {},
    startedAt: now,
    updatedAt: now,
  };
}

function durableRequirementSkill(requirementKey: string): BeatGameDurableSkillKind | undefined {
  switch (requirementKey) {
    case "lava-bucket":
    case "water-bucket": {
      return BeatGameDurableSkillKind.LIQUID_HANDLING;
    }
    case "blaze-rods": {
      return BeatGameDurableSkillKind.BLAZE_COMBAT;
    }
    case "ender-pearls":
    case "gold": {
      return BeatGameDurableSkillKind.PEARL_ACQUISITION;
    }
    default: {
      return undefined;
    }
  }
}

function appendSkillHistory(
  history: readonly BeatGameDurableSkillState[],
  skill: BeatGameDurableSkillState,
): readonly BeatGameDurableSkillState[] {
  return [...history.filter(({ skillId }) => skillId !== skill.skillId), skill].slice(
    -SKILL_HISTORY_LIMIT,
  );
}

function upsertPortalWorkspace(
  workspaces: readonly BeatGamePortalWorkspace[],
  workspace: BeatGamePortalWorkspace,
): readonly BeatGamePortalWorkspace[] {
  return [
    ...workspaces.filter(({ workspaceId }) => workspaceId !== workspace.workspaceId),
    workspace,
  ].slice(-PORTAL_WORKSPACE_HISTORY_LIMIT);
}
