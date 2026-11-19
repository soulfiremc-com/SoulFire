import { Effect } from "effect";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  BeatGamePhase,
  BeatGameRunStatus,
  BeatGameTeamRole,
  InMemoryBeatGameCoordinator,
} from "../src/index.js";

afterEach(() => {
  vi.useRealTimers();
});

describe("in-memory beat-game coordination", () => {
  it("assigns deterministic roles and elects a fenced leader", async () => {
    const coordinator = new InMemoryBeatGameCoordinator();
    await Effect.runPromise(
      coordinator.register({
        teamId: "team",
        instanceId: "instance",
        botId: "bot-b",
      }),
    );
    await Effect.runPromise(
      coordinator.register({
        teamId: "team",
        instanceId: "instance",
        botId: "bot-a",
        requestedRole: BeatGameTeamRole.NETHER_RUNNER,
      }),
    );

    const first = await Effect.runPromise(coordinator.snapshot("team"));
    expect(first.leaderBotId).toBe("bot-a");
    expect(first.leaderFencingToken).toBe(1);
    expect(first.members.map(({ botId, role }) => ({ botId, role }))).toEqual([
      { botId: "bot-a", role: BeatGameTeamRole.NETHER_RUNNER },
      { botId: "bot-b", role: BeatGameTeamRole.LEAD },
    ]);

    await Effect.runPromise(coordinator.unregister("team", "bot-a"));
    const second = await Effect.runPromise(coordinator.snapshot("team"));
    expect(second.leaderBotId).toBe("bot-b");
    expect(second.leaderFencingToken).toBe(2);
  });

  it("aggregates each member's outstanding requirements", async () => {
    const coordinator = new InMemoryBeatGameCoordinator();
    for (const botId of ["bot-a", "bot-b"]) {
      await Effect.runPromise(
        coordinator.register({
          teamId: "team",
          instanceId: "instance",
          botId,
        }),
      );
    }

    await Effect.runPromise(coordinator.publishRequirement("team", "bot-a", "blaze-rods", 4));
    await Effect.runPromise(coordinator.publishRequirement("team", "bot-b", "blaze-rods", 3));
    await Effect.runPromise(coordinator.publishRequirement("team", "bot-b", "food", 8));

    expect((await Effect.runPromise(coordinator.snapshot("team"))).sharedRequirements).toEqual({
      "blaze-rods": 7,
      food: 8,
    });
  });

  it("expires claims and issues a newer fencing token", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    const coordinator = new InMemoryBeatGameCoordinator();
    for (const botId of ["bot-a", "bot-b"]) {
      await Effect.runPromise(
        coordinator.register({
          teamId: "team",
          instanceId: "instance",
          botId,
        }),
      );
    }

    const first = await Effect.runPromise(
      coordinator.claim({
        teamId: "team",
        runId: "run-a",
        botId: "bot-a",
        key: "fortress:1",
        purpose: "search",
        ttlMs: 1000,
      }),
    );
    expect(first).toBeDefined();
    expect(
      await Effect.runPromise(
        coordinator.claim({
          teamId: "team",
          runId: "run-b",
          botId: "bot-b",
          key: "fortress:1",
          purpose: "search",
          ttlMs: 1000,
        }),
      ),
    ).toBeUndefined();

    vi.setSystemTime(new Date("2026-01-01T00:00:02.000Z"));
    const second = await Effect.runPromise(
      coordinator.claim({
        teamId: "team",
        runId: "run-b",
        botId: "bot-b",
        key: "fortress:1",
        purpose: "search",
        ttlMs: 1000,
      }),
    );
    expect(second?.fencingToken).toBeGreaterThan(first?.fencingToken ?? 0);
  });

  it("shares discoveries until their explicit expiry", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    const coordinator = new InMemoryBeatGameCoordinator();
    await Effect.runPromise(
      coordinator.register({
        teamId: "team",
        instanceId: "instance",
        botId: "bot-a",
      }),
    );
    await Effect.runPromise(
      coordinator.publishDiscovery("team", {
        key: "portal:overworld:1",
        kind: "portal",
        botId: "bot-a",
        position: {
          x: 10,
          y: 64,
          z: 20,
          dimension: "minecraft:overworld",
        },
        observedAt: "2026-01-01T00:00:00.000Z",
        expiresAt: "2026-01-01T00:01:00.000Z",
        confidence: 0.9,
      }),
    );

    expect((await Effect.runPromise(coordinator.snapshot("team"))).discoveries).toHaveLength(1);

    vi.setSystemTime(new Date("2026-01-01T00:02:00.000Z"));
    expect((await Effect.runPromise(coordinator.snapshot("team"))).discoveries).toEqual([]);
  });

  it("derives the shared objective from the furthest active phase", async () => {
    const coordinator = new InMemoryBeatGameCoordinator();
    for (const botId of ["bot-a", "bot-b"]) {
      await Effect.runPromise(
        coordinator.register({
          teamId: "team",
          instanceId: "instance",
          botId,
        }),
      );
    }
    await Effect.runPromise(
      coordinator.updateMember(
        "team",
        "bot-b",
        BeatGamePhase.LOCATE_STRONGHOLD,
        BeatGameRunStatus.RUNNING,
      ),
    );

    expect((await Effect.runPromise(coordinator.snapshot("team"))).objective).toBe("STRONGHOLD");
  });
});
