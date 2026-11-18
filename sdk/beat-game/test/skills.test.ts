import { Effect } from "effect";
import { describe, expect, it } from "vitest";

import {
  abandonActiveDurableSkill,
  advanceDurableSkill,
  BeatGameDurableSkillKind,
  BeatGameDurableSkillStatus,
  BeatGamePhase,
  completeDurableSkill,
  durableSkillKindForDecision,
  InMemoryBeatGameCheckpointStore,
  latestResumablePortalWorkspace,
  startDurableSkillIfIdle,
  startOrResumeDurableSkill,
  suspendDurableSkill,
  type BeatGameDurableSkillState,
  type BeatGamePlannerDecision,
  type BeatGamePortalWorkspace,
} from "../src/index.js";
import { checkpoint, required } from "./fixtures.js";

const now = "2026-08-28T12:00:00.000Z";

describe("durable beat-game skills", () => {
  it.each([
    [{ type: "recover-death", action: "recover-death" }, BeatGameDurableSkillKind.DEATH_RECOVERY],
    [
      {
        type: "build-and-enter-nether",
        action: "build-and-enter-nether",
      },
      BeatGameDurableSkillKind.PORTAL_CONSTRUCTION,
    ],
    [{ type: "throw-eye", action: "throw-eye" }, BeatGameDurableSkillKind.STRONGHOLD_TRIANGULATION],
    [
      { type: "activate-end-portal", action: "activate-end-portal" },
      BeatGameDurableSkillKind.END_PORTAL_ENTRY,
    ],
    [
      { type: "fight-ender-dragon", action: "fight-ender-dragon" },
      BeatGameDurableSkillKind.DRAGON_COMBAT,
    ],
  ] satisfies readonly (readonly [
    Exclude<BeatGamePlannerDecision, { readonly type: "advance-phase" }>,
    string,
  ])[])("maps %o to %s", (decision, expected) => {
    expect(durableSkillKindForDecision(decision)).toBe(expected);
  });

  it("preserves one portal workspace through interruption and restart", async () => {
    const initial = checkpoint(BeatGamePhase.ENTER_NETHER);
    const decision = {
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    } as const;
    const started = startOrResumeDurableSkill(initial, decision, now, () => "portal-skill");
    const workspace = portalWorkspace();
    const reserved = advanceDurableSkill(started, "construct-frame", now, {
      portalWorkspace: workspace,
      targets: [workspace.origin],
      protectedBlocks: workspace.protectedBlocks,
    });
    const suspended = suspendDurableSkill(reserved, "nearby hostile", "2026-08-28T12:00:01.000Z");
    const store = new InMemoryBeatGameCheckpointStore();
    const saved = await Effect.runPromise(store.save(suspended, undefined));
    const restored = required(
      await Effect.runPromise(store.load(saved.runId)),
      "the restored checkpoint",
    );

    expect(restored.activeSkill).toMatchObject({
      skillId: "portal-skill",
      status: BeatGameDurableSkillStatus.SUSPENDED,
      substep: "construct-frame",
      retries: { "nearby hostile": 1 },
    });
    expect(latestResumablePortalWorkspace(restored)).toEqual(workspace);

    const resumed = startOrResumeDurableSkill(
      restored,
      decision,
      "2026-08-28T12:00:02.000Z",
      () => "must-not-replace",
    );
    expect(resumed.activeSkill?.skillId).toBe("portal-skill");
    expect(resumed.activeSkill?.status).toBe(BeatGameDurableSkillStatus.ACTIVE);
  });

  it("keeps an abandoned skill's portal site resumable unless the site is abandoned too", () => {
    const decision = {
      type: "build-and-enter-nether",
      action: "build-and-enter-nether",
    } as const;
    const started = startOrResumeDurableSkill(
      checkpoint(BeatGamePhase.ENTER_NETHER),
      decision,
      now,
      () => "portal-skill",
    );
    const workspace = portalWorkspace();
    const building = advanceDurableSkill(started, "construct-frame", now, {
      portalWorkspace: workspace,
    });

    const kept = abandonActiveDurableSkill(building, "inventory lost", now);
    expect(latestResumablePortalWorkspace(kept)).toEqual(workspace);

    const abandoned = abandonActiveDurableSkill(building, "lava unreachable", now, {
      abandonPortalWorkspace: true,
    });
    expect(abandoned.activeSkill).toBeUndefined();
    expect(latestResumablePortalWorkspace(abandoned)).toBeUndefined();
    expect(abandoned.memory.portalWorkspaces).toEqual([
      { ...workspace, status: "ABANDONED", abandonedReason: "lava unreachable", updatedAt: now },
    ]);
  });

  it("archives completion evidence and clears only the active handle", () => {
    const initial = checkpoint(BeatGamePhase.FIGHT_ENDER_DRAGON);
    const started = startOrResumeDurableSkill(
      initial,
      { type: "fight-ender-dragon", action: "fight-ender-dragon" },
      now,
      () => "dragon-skill",
    );
    const completed = completeDurableSkill(
      started,
      "fight-ender-dragon",
      "exit portal observed after dragon death",
      "2026-08-28T12:00:03.000Z",
    );

    expect(completed.activeSkill).toBeUndefined();
    expect(completed.memory.skillHistory).toContainEqual(
      expect.objectContaining({
        skillId: "dragon-skill",
        status: BeatGameDurableSkillStatus.COMPLETED,
        completionEvidence: "exit portal observed after dragon death",
      }),
    );
  });

  it("does not replace a suspended progression skill with an auxiliary skill", () => {
    const initial = checkpoint(BeatGamePhase.ENTER_NETHER);
    const portal = suspendDurableSkill(
      startOrResumeDurableSkill(
        initial,
        {
          type: "build-and-enter-nether",
          action: "build-and-enter-nether",
        },
        now,
        () => "portal-skill",
      ),
      "night fell",
      now,
    );

    const sheltered = startDurableSkillIfIdle(
      portal,
      BeatGameDurableSkillKind.PROTECTED_STRUCTURE,
      "survive:night-shelter",
      now,
      () => "shelter-skill",
    );

    expect(sheltered.activeSkill).toEqual(portal.activeSkill);
  });

  it.each(Object.values(BeatGameDurableSkillKind))(
    "round-trips %s state through the checkpoint store",
    async (kind) => {
      const initial = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
      const activeSkill: BeatGameDurableSkillState = {
        skillId: `skill-${kind}`,
        kind,
        phase: initial.planner.phase,
        action: `exercise-${kind}`,
        status: BeatGameDurableSkillStatus.ACTIVE,
        substep: "observe",
        targets: [
          initial.memory.eyeSamples[0]?.origin ?? {
            x: 0,
            y: 64,
            z: 0,
            dimension: "minecraft:overworld",
          },
        ],
        protectedBlocks: [],
        protectedItemIds: [],
        completedWorldChanges: [],
        requiredResources: {},
        retries: {},
        startedAt: now,
        updatedAt: now,
      };
      const store = new InMemoryBeatGameCheckpointStore();
      const saved = await Effect.runPromise(
        store.save(
          {
            ...initial,
            activeSkill,
          },
          undefined,
        ),
      );

      expect((await Effect.runPromise(store.load(saved.runId)))?.activeSkill).toEqual(activeSkill);
    },
  );
});

function portalWorkspace(): BeatGamePortalWorkspace {
  const origin = {
    x: 4,
    y: 64,
    z: 8,
    dimension: "minecraft:overworld",
  };
  return {
    workspaceId: "portal-skill:portal",
    origin,
    axis: "x",
    method: "OBSIDIAN",
    status: "BUILDING",
    targetFrame: [origin],
    observedFrame: [],
    interior: [{ ...origin, x: origin.x + 1, y: origin.y + 1 }],
    protectedBlocks: [origin],
    candidateLavaSources: [],
    rejectedLavaSources: [],
    waterFlow: [],
    bucketState: "UNKNOWN",
    ignitionState: "NOT_ATTEMPTED",
    interiorState: "CLEAR",
    entryAttempts: 0,
    observedAt: now,
    updatedAt: now,
  };
}
