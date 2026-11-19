import { Effect } from "effect";

import { BeatGameCoordinationError } from "./errors.js";
import {
  BeatGameObjective,
  BeatGamePhase,
  BeatGameRunStatus,
  BeatGameTeamRole,
  type BeatGameClaim,
  type BeatGameTeamMember,
  type BeatGameTeamDiscovery,
  type BeatGameTeamSnapshot,
} from "./model.js";

export interface BeatGameMemberRegistration {
  readonly teamId: string;
  readonly instanceId: string;
  readonly botId: string;
  readonly requestedRole?: BeatGameTeamRole;
}

export interface BeatGameClaimRequest {
  readonly teamId: string;
  readonly runId: string;
  readonly botId: string;
  readonly key: string;
  readonly purpose: string;
  readonly ttlMs: number;
}

export interface BeatGameCoordinator {
  readonly register: (
    member: BeatGameMemberRegistration,
  ) => Effect.Effect<BeatGameTeamMember, BeatGameCoordinationError>;
  readonly unregister: (
    teamId: string,
    botId: string,
  ) => Effect.Effect<void, BeatGameCoordinationError>;
  readonly updateMember: (
    teamId: string,
    botId: string,
    phase: BeatGamePhase,
    status: BeatGameRunStatus,
  ) => Effect.Effect<BeatGameTeamMember, BeatGameCoordinationError>;
  readonly claim: (
    request: BeatGameClaimRequest,
  ) => Effect.Effect<BeatGameClaim | undefined, BeatGameCoordinationError>;
  readonly release: (
    teamId: string,
    key: string,
    botId: string,
  ) => Effect.Effect<boolean, BeatGameCoordinationError>;
  readonly publishRequirement: (
    teamId: string,
    botId: string,
    key: string,
    count: number,
  ) => Effect.Effect<void, BeatGameCoordinationError>;
  readonly publishDiscovery: (
    teamId: string,
    discovery: BeatGameTeamDiscovery,
  ) => Effect.Effect<void, BeatGameCoordinationError>;
  readonly forgetDiscovery: (
    teamId: string,
    key: string,
  ) => Effect.Effect<boolean, BeatGameCoordinationError>;
  readonly snapshot: (
    teamId: string,
  ) => Effect.Effect<BeatGameTeamSnapshot, BeatGameCoordinationError>;
  readonly reset: (teamId: string) => Effect.Effect<void, BeatGameCoordinationError>;
}

interface InternalMember {
  readonly instanceId: string;
  readonly botId: string;
  readonly requestedRole?: BeatGameTeamRole;
  phase: BeatGamePhase;
  status: BeatGameRunStatus;
  updatedAt: string;
}

interface InternalTeam {
  revision: number;
  fencingToken: number;
  leaderFencingToken: number;
  leaderBotId: string | undefined;
  readonly members: Map<string, InternalMember>;
  readonly claims: Map<string, BeatGameClaim>;
  readonly discoveries: Map<string, BeatGameTeamDiscovery>;
  readonly memberRequirements: Map<string, Map<string, number>>;
}

const ROLE_ORDER: readonly BeatGameTeamRole[] = [
  BeatGameTeamRole.LEAD,
  BeatGameTeamRole.PORTAL_ENGINEER,
  BeatGameTeamRole.NETHER_RUNNER,
  BeatGameTeamRole.STRONGHOLD_SCOUT,
  BeatGameTeamRole.END_SUPPORT,
];

const PHASE_ORDER: Readonly<Record<BeatGamePhase, number>> = {
  [BeatGamePhase.PREPARE_OVERWORLD]: 0,
  [BeatGamePhase.ENTER_NETHER]: 1,
  [BeatGamePhase.COLLECT_NETHER_RESOURCES]: 2,
  [BeatGamePhase.RETURN_TO_OVERWORLD]: 3,
  [BeatGamePhase.LOCATE_STRONGHOLD]: 4,
  [BeatGamePhase.ACTIVATE_END_PORTAL]: 5,
  [BeatGamePhase.FIGHT_ENDER_DRAGON]: 6,
  [BeatGamePhase.EXIT_END]: 7,
  [BeatGamePhase.COMPLETE]: 8,
};

export class InMemoryBeatGameCoordinator implements BeatGameCoordinator {
  readonly #teams = new Map<string, InternalTeam>();

  public readonly register = (
    registration: BeatGameMemberRegistration,
  ): Effect.Effect<BeatGameTeamMember, BeatGameCoordinationError> =>
    coordinationEffect("register", () => {
      const team = this.#team(registration.teamId);
      const now = new Date().toISOString();
      const existing = team.members.get(registration.botId);
      team.members.set(registration.botId, {
        instanceId: registration.instanceId,
        botId: registration.botId,
        ...(registration.requestedRole === undefined
          ? {}
          : { requestedRole: registration.requestedRole }),
        phase: existing?.phase ?? BeatGamePhase.PREPARE_OVERWORLD,
        status: existing?.status ?? BeatGameRunStatus.CREATED,
        updatedAt: now,
      });
      team.revision += 1;
      return this.#memberSnapshot(team, registration.botId);
    });

  public readonly unregister = (
    teamId: string,
    botId: string,
  ): Effect.Effect<void, BeatGameCoordinationError> =>
    coordinationEffect("unregister", () => {
      const team = this.#teams.get(teamId);
      if (team === undefined) {
        return;
      }
      if (team.members.delete(botId)) {
        team.memberRequirements.delete(botId);
        for (const [key, claim] of team.claims) {
          if (claim.botId === botId) {
            team.claims.delete(key);
          }
        }
        this.#electLeader(team);
        team.revision += 1;
      }
    });

  public readonly updateMember = (
    teamId: string,
    botId: string,
    phase: BeatGamePhase,
    status: BeatGameRunStatus,
  ): Effect.Effect<BeatGameTeamMember, BeatGameCoordinationError> =>
    coordinationEffect("updateMember", () => {
      const team = this.#requiredTeam(teamId);
      const member = team.members.get(botId);
      if (member === undefined) {
        throw new Error(`Bot ${botId} is not registered with team ${teamId}`);
      }
      member.phase = phase;
      member.status = status;
      member.updatedAt = new Date().toISOString();
      team.revision += 1;
      return this.#memberSnapshot(team, botId);
    });

  public readonly claim = (
    request: BeatGameClaimRequest,
  ): Effect.Effect<BeatGameClaim | undefined, BeatGameCoordinationError> =>
    coordinationEffect("claim", () => {
      const team = this.#requiredTeam(request.teamId);
      this.#purgeExpiredClaims(team);
      const existing = team.claims.get(request.key);
      if (existing !== undefined && existing.botId !== request.botId) {
        return undefined;
      }
      const now = Date.now();
      const claim: BeatGameClaim = {
        key: request.key,
        runId: request.runId,
        botId: request.botId,
        purpose: request.purpose,
        createdAt: new Date(now).toISOString(),
        expiresAt: new Date(now + positiveInteger(request.ttlMs, "ttlMs")).toISOString(),
        fencingToken: ++team.fencingToken,
      };
      team.claims.set(request.key, claim);
      team.revision += 1;
      return claim;
    });

  public readonly release = (
    teamId: string,
    key: string,
    botId: string,
  ): Effect.Effect<boolean, BeatGameCoordinationError> =>
    coordinationEffect("release", () => {
      const team = this.#requiredTeam(teamId);
      const current = team.claims.get(key);
      if (current === undefined || current.botId !== botId) {
        return false;
      }
      team.claims.delete(key);
      team.revision += 1;
      return true;
    });

  public readonly publishRequirement = (
    teamId: string,
    botId: string,
    key: string,
    count: number,
  ): Effect.Effect<void, BeatGameCoordinationError> =>
    coordinationEffect("publishRequirement", () => {
      const team = this.#requiredTeam(teamId);
      if (!team.members.has(botId)) {
        throw new Error(`Bot ${botId} is not registered with team ${teamId}`);
      }
      const normalized = nonNegativeInteger(count, "count");
      const requirements = team.memberRequirements.get(botId) ?? new Map<string, number>();
      if (normalized === 0) {
        requirements.delete(key);
      } else {
        requirements.set(key, normalized);
      }
      if (requirements.size === 0) {
        team.memberRequirements.delete(botId);
      } else {
        team.memberRequirements.set(botId, requirements);
      }
      team.revision += 1;
    });

  public readonly publishDiscovery = (
    teamId: string,
    discovery: BeatGameTeamDiscovery,
  ): Effect.Effect<void, BeatGameCoordinationError> =>
    coordinationEffect("publishDiscovery", () => {
      const team = this.#requiredTeam(teamId);
      if (!team.members.has(discovery.botId)) {
        throw new Error(`Bot ${discovery.botId} is not registered with team ${teamId}`);
      }
      validateDiscovery(discovery);
      const existing = team.discoveries.get(discovery.key);
      if (
        existing !== undefined &&
        Date.parse(existing.observedAt) > Date.parse(discovery.observedAt)
      ) {
        return;
      }
      team.discoveries.set(discovery.key, structuredClone(discovery));
      team.revision += 1;
    });

  public readonly forgetDiscovery = (
    teamId: string,
    key: string,
  ): Effect.Effect<boolean, BeatGameCoordinationError> =>
    coordinationEffect("forgetDiscovery", () => {
      const team = this.#requiredTeam(teamId);
      const removed = team.discoveries.delete(key);
      if (removed) {
        team.revision += 1;
      }
      return removed;
    });

  public readonly snapshot = (
    teamId: string,
  ): Effect.Effect<BeatGameTeamSnapshot, BeatGameCoordinationError> =>
    coordinationEffect("snapshot", () => {
      const team = this.#requiredTeam(teamId);
      this.#purgeExpiredClaims(team);
      this.#purgeExpiredDiscoveries(team);
      const roles = assignedRoles(team);
      this.#electLeader(team);
      const members = [...team.members.values()]
        .toSorted((left, right) => left.botId.localeCompare(right.botId))
        .map((member): BeatGameTeamMember => ({
          instanceId: member.instanceId,
          botId: member.botId,
          role: roles.get(member.botId) ?? BeatGameTeamRole.END_SUPPORT,
          phase: member.phase,
          status: member.status,
          updatedAt: member.updatedAt,
        }));
      return {
        teamId,
        revision: team.revision,
        objective: objectiveFor(members),
        ...(team.leaderBotId === undefined ? {} : { leaderBotId: team.leaderBotId }),
        leaderFencingToken: team.leaderFencingToken,
        members,
        claims: [...team.claims.values()].toSorted((left, right) =>
          left.key.localeCompare(right.key),
        ),
        discoveries: [...team.discoveries.values()]
          .toSorted((left, right) => left.key.localeCompare(right.key))
          .map((discovery) => structuredClone(discovery)),
        sharedRequirements: Object.fromEntries(
          [...aggregateRequirements(team)].toSorted(([left], [right]) => left.localeCompare(right)),
        ),
        updatedAt: new Date().toISOString(),
      };
    });

  public readonly reset = (teamId: string): Effect.Effect<void, BeatGameCoordinationError> =>
    coordinationEffect("reset", () => {
      this.#teams.delete(teamId);
    });

  #team(teamId: string): InternalTeam {
    const current = this.#teams.get(teamId);
    if (current !== undefined) {
      return current;
    }
    const created: InternalTeam = {
      revision: 0,
      fencingToken: 0,
      leaderFencingToken: 0,
      leaderBotId: undefined,
      members: new Map(),
      claims: new Map(),
      discoveries: new Map(),
      memberRequirements: new Map(),
    };
    this.#teams.set(teamId, created);
    return created;
  }

  #requiredTeam(teamId: string): InternalTeam {
    const team = this.#teams.get(teamId);
    if (team === undefined) {
      throw new Error(`Beat-game team ${teamId} does not exist`);
    }
    return team;
  }

  #memberSnapshot(team: InternalTeam, botId: string): BeatGameTeamMember {
    const member = team.members.get(botId);
    if (member === undefined) {
      throw new Error(`Beat-game member ${botId} does not exist`);
    }
    return {
      instanceId: member.instanceId,
      botId: member.botId,
      role: assignedRoles(team).get(botId) ?? BeatGameTeamRole.END_SUPPORT,
      phase: member.phase,
      status: member.status,
      updatedAt: member.updatedAt,
    };
  }

  #purgeExpiredClaims(team: InternalTeam): void {
    const now = Date.now();
    let changed = false;
    for (const [key, claim] of team.claims) {
      if (Date.parse(claim.expiresAt) <= now) {
        team.claims.delete(key);
        changed = true;
      }
    }
    if (changed) {
      team.revision += 1;
    }
  }

  #purgeExpiredDiscoveries(team: InternalTeam): void {
    const now = Date.now();
    let changed = false;
    for (const [key, discovery] of team.discoveries) {
      if (discovery.expiresAt !== undefined && Date.parse(discovery.expiresAt) <= now) {
        team.discoveries.delete(key);
        changed = true;
      }
    }
    if (changed) {
      team.revision += 1;
    }
  }

  #electLeader(team: InternalTeam): void {
    const elected = [...team.members.values()]
      .filter(
        ({ status }) =>
          status !== BeatGameRunStatus.COMPLETED &&
          status !== BeatGameRunStatus.FAILED &&
          status !== BeatGameRunStatus.STOPPED,
      )
      .map(({ botId }) => botId)
      .toSorted((left, right) => left.localeCompare(right))[0];
    if (team.leaderBotId === elected) {
      return;
    }
    team.leaderBotId = elected;
    team.leaderFencingToken += 1;
    team.revision += 1;
  }
}

function aggregateRequirements(team: InternalTeam): Map<string, number> {
  const totals = new Map<string, number>();
  for (const requirements of team.memberRequirements.values()) {
    for (const [key, count] of requirements) {
      totals.set(key, (totals.get(key) ?? 0) + count);
    }
  }
  return totals;
}

function assignedRoles(team: InternalTeam): Map<string, BeatGameTeamRole> {
  const result = new Map<string, BeatGameTeamRole>();
  const members = [...team.members.values()].toSorted((left, right) =>
    left.botId.localeCompare(right.botId),
  );
  const reserved = new Set<BeatGameTeamRole>();
  for (const member of members) {
    if (member.requestedRole !== undefined && !reserved.has(member.requestedRole)) {
      result.set(member.botId, member.requestedRole);
      reserved.add(member.requestedRole);
    }
  }
  const available = ROLE_ORDER.filter((role) => !reserved.has(role));
  let cursor = 0;
  for (const member of members) {
    if (result.has(member.botId)) {
      continue;
    }
    result.set(
      member.botId,
      available[cursor % Math.max(1, available.length)] ?? BeatGameTeamRole.END_SUPPORT,
    );
    cursor += 1;
  }
  return result;
}

function objectiveFor(members: readonly BeatGameTeamMember[]): BeatGameObjective {
  const maximum = members.reduce(
    (current, member) => Math.max(current, PHASE_ORDER[member.phase]),
    0,
  );
  if (maximum >= PHASE_ORDER[BeatGamePhase.COMPLETE]) {
    return BeatGameObjective.COMPLETE;
  }
  if (maximum >= PHASE_ORDER[BeatGamePhase.FIGHT_ENDER_DRAGON]) {
    return BeatGameObjective.END_ASSAULT;
  }
  if (maximum >= PHASE_ORDER[BeatGamePhase.LOCATE_STRONGHOLD]) {
    return BeatGameObjective.STRONGHOLD;
  }
  if (maximum >= PHASE_ORDER[BeatGamePhase.COLLECT_NETHER_RESOURCES]) {
    return BeatGameObjective.NETHER_RESOURCES;
  }
  if (maximum >= PHASE_ORDER[BeatGamePhase.ENTER_NETHER]) {
    return BeatGameObjective.NETHER_ENTRY;
  }
  return BeatGameObjective.BOOTSTRAP;
}

function positiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive safe integer`);
  }
  return value;
}

function nonNegativeInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative safe integer`);
  }
  return value;
}

function validateDiscovery(discovery: BeatGameTeamDiscovery): void {
  if (discovery.key.length === 0) {
    throw new TypeError("discovery.key must not be empty");
  }
  if (
    !Number.isFinite(discovery.confidence) ||
    discovery.confidence < 0 ||
    discovery.confidence > 1
  ) {
    throw new RangeError("discovery.confidence must be between 0 and 1");
  }
  if (!Number.isFinite(Date.parse(discovery.observedAt))) {
    throw new TypeError("discovery.observedAt must be an ISO timestamp");
  }
  if (discovery.expiresAt !== undefined && !Number.isFinite(Date.parse(discovery.expiresAt))) {
    throw new TypeError("discovery.expiresAt must be an ISO timestamp");
  }
}

function coordinationEffect<T>(
  operation: string,
  evaluate: () => T,
): Effect.Effect<T, BeatGameCoordinationError> {
  return Effect.try({
    try: evaluate,
    catch: (cause) =>
      new BeatGameCoordinationError({
        runId: "",
        instanceId: "",
        botId: "",
        phase: BeatGamePhase.PREPARE_OVERWORLD,
        action: operation,
        retryable: false,
        message:
          cause instanceof Error ? cause.message : `Beat-game coordination ${operation} failed`,
        cause,
      }),
  });
}
