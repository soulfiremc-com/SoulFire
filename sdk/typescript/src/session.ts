import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { Code, type CallOptions } from "@connectrpc/connect";
import { Cause, Deferred, Effect, Exit, Option, PubSub, Scope, Stream } from "effect";
import { operationError, SoulFireTimeoutError, type SoulFireOperationError } from "./errors.js";

import {
  BossBarEventKind,
  type BotEventFilterSchema,
  EntityEventKind,
  PlayerListEntrySnapshotSchema,
  PlayerListEventKind,
  ResourcePackEventKind,
  ScoreboardEventKind,
  WeatherEventKind,
  type BlockState,
  type BotBossBarEvent,
  type BotEnvironmentEvent,
  type BotEvent,
  type BotGameEvent,
  type BotResourcePackEvent,
  type BotScoreboardEvent,
  type ClockSnapshot,
  type NearbyEntity,
  type PlayerListEntrySnapshot,
  type WatchBotEventsRequestSchema,
} from "./generated/soulfire/bot_live_pb.js";
import {
  BotLiveStateSchema,
  type BotInventoryStateResponse,
  type BotLiveState,
  type BotStatus,
} from "./generated/soulfire/bot_pb.js";
import type {
  BlockSnapshot,
  EntitySnapshot,
  TextComponent,
} from "./generated/soulfire/domain_pb.js";

const DEFAULT_RECONNECT_DELAY_MS = 250;
const MAX_RECONNECT_DELAY_MS = 5_000;
const SUBSCRIBER_BUFFER_SIZE = 1_024;

export interface BotSessionState {
  /**
   * Like `blocks`, for updates that carried a full snapshot.
   */
  readonly blockSnapshots: ReadonlyMap<string, BlockSnapshot>;
  /**
   * Blocks seen changing since the session opened (block update events), keyed
   * `dimension:x:y:z`.
   */
  readonly blocks: ReadonlyMap<string, BlockState>;
  readonly bossBars: ReadonlyMap<string, BotBossBarState>;
  /**
   * Entities from entity events, by entity id; removed when they despawn.
   */
  readonly entities: ReadonlyMap<number, NearbyEntity>;
  readonly entitySnapshots: ReadonlyMap<number, EntitySnapshot>;
  readonly environment: BotEnvironmentState;
  readonly epoch?: string;
  readonly inventory?: BotInventoryStateResponse;
  readonly player?: BotLiveState;
  readonly playerList: ReadonlyMap<string, PlayerListEntrySnapshot>;
  readonly resourcePacks: ReadonlyMap<string, BotResourcePackEvent>;
  readonly scoreboard: BotScoreboardState;
  readonly sequence: bigint;
  readonly snapshotRevision: bigint;
  readonly status?: BotStatus;
}

export interface BotEnvironmentState {
  readonly clocks: ReadonlyMap<string, ClockSnapshot>;
  readonly gameTime?: bigint;
  readonly lastGameEvent?: BotGameEvent;
  readonly rainLevel?: number;
  readonly raining?: boolean;
  readonly thunderLevel?: number;
}

export interface BotBossBarState {
  readonly bossBarId: string;
  readonly color?: string;
  readonly createWorldFog?: boolean;
  readonly darkenScreen?: boolean;
  readonly name?: TextComponent;
  readonly overlay?: string;
  readonly playMusic?: boolean;
  readonly progress?: number;
}

export interface BotScoreboardObjective {
  readonly displayName?: TextComponent;
  readonly name: string;
  readonly renderType?: string;
}

export interface BotScoreboardScore {
  readonly displayName?: TextComponent;
  readonly objectiveName: string;
  readonly owner: string;
  readonly score: number;
}

export interface BotScoreboardTeam {
  readonly allowFriendlyFire?: boolean;
  readonly collisionRule?: string;
  readonly color?: string;
  readonly displayName?: TextComponent;
  readonly name: string;
  readonly nameTagVisibility?: string;
  readonly players: ReadonlySet<string>;
  readonly prefix?: TextComponent;
  readonly seeFriendlyInvisibles?: boolean;
  readonly suffix?: TextComponent;
}

export interface BotScoreboardState {
  readonly displaySlots: ReadonlyMap<string, string>;
  readonly objectives: ReadonlyMap<string, BotScoreboardObjective>;
  readonly scores: ReadonlyMap<string, BotScoreboardScore>;
  readonly teams: ReadonlyMap<string, BotScoreboardTeam>;
}

export interface BotSessionOptions {
  /**
   * Defaults to every category except sounds, particles and chunks.
   */
  readonly filter?: MessageInitShape<typeof BotEventFilterSchema>;
  /**
   * Defaults to 15, from 5 to 60.
   */
  readonly heartbeatIntervalSeconds?: number;
}

type StreamRequest = Omit<
  MessageInitShape<typeof WatchBotEventsRequestSchema>,
  "$typeName" | "botId" | "instanceId"
>;

export type BotEventStreamFactory = (
  request: StreamRequest,
  options: CallOptions,
) => Stream.Stream<BotEvent, SoulFireOperationError>;

/**
 * A bot's event stream and the state it adds up to. After an error it
 * reconnects and resumes where it left off. Its Effect scope closes it.
 */
export class BotSession {
  #state: BotSessionState = emptyBotSessionState();
  #failure: Cause.Cause<SoulFireOperationError> | undefined;

  private constructor(
    private readonly eventsHub: PubSub.PubSub<Exit.Exit<BotEvent, SoulFireOperationError>>,
    private readonly ready: Deferred.Deferred<void, SoulFireOperationError>,
    private readonly scope: Scope.Closeable,
  ) {}

  public static open(
    stream: BotEventStreamFactory,
    options: BotSessionOptions = {},
  ): Effect.Effect<BotSession, SoulFireOperationError, Scope.Scope> {
    return Effect.gen(function* () {
      const scope = yield* Scope.make();
      yield* Effect.addFinalizer(() => Scope.close(scope, Exit.void));
      const events =
        yield* PubSub.sliding<Exit.Exit<BotEvent, SoulFireOperationError>>(SUBSCRIBER_BUFFER_SIZE);
      const ready = yield* Deferred.make<void, SoulFireOperationError>();
      const session = new BotSession(events, ready, scope);
      yield* Scope.addFinalizer(scope, PubSub.shutdown(events));
      yield* session.consume(stream, options).pipe(
        Effect.onError((cause) => Deferred.failCause(ready, cause).pipe(Effect.asVoid)),
        Effect.forkIn(scope),
      );
      yield* Deferred.await(ready).pipe(
        Effect.onError((cause) => Scope.close(scope, Exit.failCause(cause))),
      );
      return session;
    });
  }

  public get state(): BotSessionState {
    return this.#state;
  }

  /** Subscribes lazily; slow readers lose the oldest buffered events. */
  public events(): Stream.Stream<BotEvent, SoulFireOperationError> {
    return Stream.suspend(() =>
      this.#failure === undefined
        ? Stream.fromPubSub(this.eventsHub).pipe(Stream.mapEffect((event) => event))
        : Stream.failCause(this.#failure),
    );
  }

  public waitFor(
    predicate: (event: BotEvent, state: BotSessionState) => boolean,
    options: { readonly timeoutMs?: number } = {},
  ): Effect.Effect<BotEvent, SoulFireOperationError> {
    const next = this.events().pipe(
      Stream.filter((event) => predicate(event, this.#state)),
      Stream.runHead,
      Effect.flatMap(
        Effect.fromOption(() =>
          operationError(
            "session.waitFor",
            new Error("Bot session closed before the expected event"),
          ),
        ),
      ),
    );
    return options.timeoutMs === undefined
      ? next
      : next.pipe(
          Effect.timeoutOrElse({
            duration: options.timeoutMs,
            orElse: () =>
              Effect.fail(
                new SoulFireTimeoutError({
                  operation: "session.waitFor",
                  message: "Timed out waiting for a bot event",
                }),
              ),
          }),
        );
  }

  /** Checks current state after subscribing, so a concurrent update cannot be missed. */
  public waitForState(
    predicate: (state: BotSessionState) => boolean,
  ): Effect.Effect<BotSessionState, SoulFireOperationError> {
    return Effect.scoped(
      Effect.gen({ self: this }, function* () {
        if (this.#failure !== undefined) return yield* Effect.failCause(this.#failure);
        const subscription = yield* PubSub.subscribe(this.eventsHub);
        while (!predicate(this.#state)) yield* yield* PubSub.take(subscription);
        return this.#state;
      }),
    );
  }

  public once(
    eventCase: BotEvent["event"]["case"],
    options?: { readonly timeoutMs?: number },
  ): Effect.Effect<BotEvent, SoulFireOperationError> {
    return this.waitFor((event) => event.event.case === eventCase, options);
  }

  public close(): Effect.Effect<void> {
    return Scope.close(this.scope, Exit.void);
  }

  private consume(stream: BotEventStreamFactory, options: BotSessionOptions): Effect.Effect<void> {
    return Effect.gen({ self: this }, function* () {
      // The stream callback below sets this flag while the loop waits on it.
      const progress = { receivedEvent: false };
      let delay = DEFAULT_RECONNECT_DELAY_MS;
      while (true) {
        const cursor =
          this.#state.epoch === undefined
            ? {}
            : {
                afterSequence: this.#state.sequence,
                streamEpoch: this.#state.epoch,
              };
        const exit = yield* stream(
          {
            ...cursor,
            filter: options.filter ?? defaultFilter(),
            heartbeatIntervalSeconds: options.heartbeatIntervalSeconds ?? 15,
          },
          {},
        ).pipe(
          // oxlint-disable-next-line eslint/no-loop-func -- the stream finishes inside this iteration, and the flags it sets drive the reconnect below
          Stream.runForEach((event) =>
            Effect.gen({ self: this }, function* () {
              this.#state = reduceBotSessionState(this.#state, event);
              progress.receivedEvent = true;
              delay = DEFAULT_RECONNECT_DELAY_MS;
              yield* Deferred.succeed(this.ready, undefined);
              yield* PubSub.publish(this.eventsHub, Exit.succeed(event));
            }),
          ),
          Effect.exit,
        );
        if (Exit.isFailure(exit)) {
          const failure = Cause.findErrorOption(exit.cause);
          if (
            !progress.receivedEvent ||
            Option.isNone(failure) ||
            failure.value._tag !== "SoulFireRpcError" ||
            // Closing the session interrupts this loop, so a cancellation seen here came from the
            // transport. Resuming keeps the state current instead of freezing it.
            !(failure.value.retryable || failure.value.code === Code.Canceled)
          ) {
            yield* Deferred.failCause(this.ready, exit.cause);
            this.#failure = exit.cause;
            yield* PubSub.publish(this.eventsHub, Exit.failCause(exit.cause));
            return;
          }
        }
        yield* Effect.sleep(delay);
        if (Exit.isFailure(exit)) delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
      }
    }).pipe(Effect.orDie);
  }
}

export function emptyBotSessionState(): BotSessionState {
  return {
    blockSnapshots: new Map(),
    blocks: new Map(),
    bossBars: new Map(),
    entities: new Map(),
    entitySnapshots: new Map(),
    environment: {
      clocks: new Map(),
    },
    playerList: new Map(),
    resourcePacks: new Map(),
    scoreboard: {
      displaySlots: new Map(),
      objectives: new Map(),
      scores: new Map(),
      teams: new Map(),
    },
    sequence: 0n,
    snapshotRevision: 0n,
  };
}

export function reduceBotSessionState(state: BotSessionState, event: BotEvent): BotSessionState {
  const envelope = event.envelope;
  const discontinuity =
    envelope !== undefined &&
    state.epoch !== undefined &&
    (envelope.streamEpoch !== state.epoch || envelope.sequence !== state.sequence + 1n);
  const current =
    discontinuity || event.event.case === "resyncRequired"
      ? {
          ...emptyBotSessionState(),
          ...(state.status === undefined ? {} : { status: state.status }),
        }
      : state;
  let player = current.player;
  let inventory = current.inventory;
  let status = current.status;
  let environment = current.environment;
  let scoreboard = current.scoreboard;
  const entities = new Map(current.entities);
  const entitySnapshots = new Map(current.entitySnapshots);
  const blocks = new Map(current.blocks);
  const blockSnapshots = new Map(current.blockSnapshots);
  const bossBars = new Map(current.bossBars);
  const playerList = new Map(current.playerList);
  const resourcePacks = new Map(current.resourcePacks);

  switch (event.event.case) {
    case "snapshot": {
      player = event.event.value;
      break;
    }
    case "stateDelta": {
      if (player !== undefined) {
        player = mergePlayerState(player, event.event.value);
      }
      break;
    }
    case "status": {
      status = event.event.value;
      break;
    }
    case "inventory": {
      inventory = event.event.value.state;
      break;
    }
    case "entityEvent": {
      const entity = event.event.value.entity;
      if (entity !== undefined) {
        if (event.event.value.kind === EntityEventKind.ENTITY_EVENT_DESPAWN) {
          entities.delete(entity.entityId);
          entitySnapshots.delete(entity.entityId);
        } else {
          entities.set(entity.entityId, entity);
          const snapshot = event.event.value.snapshot;
          if (snapshot !== undefined) {
            entitySnapshots.set(entity.entityId, snapshot);
          }
        }
      }
      break;
    }
    case "blockUpdate": {
      const update = event.event.value;
      if (update.position !== undefined) {
        const key = blockKey(update.position);
        blocks.set(key, {
          $typeName: "soulfire.v1.BlockState",
          position: update.position,
          blockId: update.newBlockId,
          properties: update.block?.properties ?? {},
        });
        if (update.block !== undefined) {
          blockSnapshots.set(key, update.block);
        }
      }
      break;
    }
    case "environment": {
      environment = reduceEnvironmentState(environment, event.event.value);
      break;
    }
    case "playerList": {
      reducePlayerListState(playerList, event.event.value);
      break;
    }
    case "bossBar": {
      reduceBossBarState(bossBars, event.event.value);
      break;
    }
    case "scoreboard": {
      scoreboard = reduceScoreboardState(scoreboard, event.event.value);
      break;
    }
    case "resourcePack": {
      reduceResourcePackState(resourcePacks, event.event.value);
      break;
    }
    case "chat":
    case "chunk":
    case "damage":
    case "heartbeat":
    case "lifecycle":
    case "particle":
    case "resyncRequired":
    case "sound":
    case "title":
    case undefined:
    default: {
      break;
    }
  }

  return {
    blockSnapshots,
    blocks,
    bossBars,
    entities,
    entitySnapshots,
    environment,
    playerList,
    resourcePacks,
    scoreboard,
    sequence: event.envelope?.sequence ?? current.sequence,
    snapshotRevision: event.envelope?.snapshotRevision ?? current.snapshotRevision,
    ...(event.envelope === undefined
      ? {}
      : {
          epoch: event.envelope.streamEpoch,
        }),
    ...(inventory === undefined ? {} : { inventory }),
    ...(player === undefined ? {} : { player }),
    ...(status === undefined ? {} : { status }),
  };
}

function reduceResourcePackState(
  resourcePacks: Map<string, BotResourcePackEvent>,
  event: BotResourcePackEvent,
): void {
  switch (event.kind) {
    case ResourcePackEventKind.RESOURCE_PACK_EVENT_OFFERED: {
      if (event.packId !== undefined) {
        resourcePacks.set(event.packId, event);
      }
      break;
    }
    case ResourcePackEventKind.RESOURCE_PACK_EVENT_REMOVED: {
      if (event.packId !== undefined) {
        resourcePacks.delete(event.packId);
      }
      break;
    }
    case ResourcePackEventKind.RESOURCE_PACK_EVENT_CLEARED: {
      resourcePacks.clear();
      break;
    }
    case ResourcePackEventKind.RESOURCE_PACK_EVENT_UNSPECIFIED:
    default: {
      break;
    }
  }
}

function reduceEnvironmentState(
  state: BotEnvironmentState,
  event: BotEnvironmentEvent,
): BotEnvironmentState {
  switch (event.change.case) {
    case "time": {
      const clocks = new Map(state.clocks);
      for (const clock of event.change.value.clocks) {
        clocks.set(clock.clockId, clock);
      }
      return {
        ...state,
        clocks,
        gameTime: event.change.value.gameTime,
      };
    }
    case "weather": {
      switch (event.change.value.kind) {
        case WeatherEventKind.WEATHER_EVENT_STARTED_RAINING: {
          return { ...state, raining: true };
        }
        case WeatherEventKind.WEATHER_EVENT_STOPPED_RAINING: {
          return { ...state, raining: false };
        }
        case WeatherEventKind.WEATHER_EVENT_RAIN_LEVEL_CHANGED: {
          return event.change.value.level === undefined
            ? state
            : { ...state, rainLevel: event.change.value.level };
        }
        case WeatherEventKind.WEATHER_EVENT_THUNDER_LEVEL_CHANGED: {
          return event.change.value.level === undefined
            ? state
            : { ...state, thunderLevel: event.change.value.level };
        }
        case WeatherEventKind.WEATHER_EVENT_UNSPECIFIED:
        default: {
          return state;
        }
      }
    }
    case "gameEvent": {
      return { ...state, lastGameEvent: event.change.value };
    }
    case undefined:
    default: {
      return state;
    }
  }
}

function reducePlayerListState(
  state: Map<string, PlayerListEntrySnapshot>,
  event: Extract<BotEvent["event"], { case: "playerList" }>["value"],
): void {
  if (event.kind === PlayerListEventKind.PLAYER_LIST_EVENT_REMOVE) {
    for (const profileId of event.removedProfileIds) {
      state.delete(profileId);
    }
    return;
  }
  for (const entry of event.entries) {
    const previous = state.get(entry.profileId);
    const changed = new Set(entry.changedFields);
    if (previous === undefined || changed.has("add_player")) {
      state.set(entry.profileId, entry);
      continue;
    }
    state.set(
      entry.profileId,
      create(PlayerListEntrySnapshotSchema, {
        ...previous,
        changedFields: entry.changedFields,
        ...(changed.has("update_display_name") ? { displayName: entry.displayName } : {}),
        ...(changed.has("update_game_mode") ? { gameMode: entry.gameMode } : {}),
        ...(changed.has("update_hat") ? { showHat: entry.showHat } : {}),
        ...(changed.has("update_latency") ? { latencyMs: entry.latencyMs } : {}),
        ...(changed.has("update_list_order") ? { listOrder: entry.listOrder } : {}),
        ...(changed.has("update_listed") ? { listed: entry.listed } : {}),
      }),
    );
  }
}

function reduceBossBarState(state: Map<string, BotBossBarState>, event: BotBossBarEvent): void {
  if (event.kind === BossBarEventKind.BOSS_BAR_EVENT_REMOVE) {
    state.delete(event.bossBarId);
    return;
  }
  const previous = state.get(event.bossBarId) ?? {
    bossBarId: event.bossBarId,
  };
  state.set(event.bossBarId, {
    ...previous,
    ...(event.color === undefined ? {} : { color: event.color }),
    ...(event.createWorldFog === undefined ? {} : { createWorldFog: event.createWorldFog }),
    ...(event.darkenScreen === undefined ? {} : { darkenScreen: event.darkenScreen }),
    ...(event.name === undefined ? {} : { name: event.name }),
    ...(event.overlay === undefined ? {} : { overlay: event.overlay }),
    ...(event.playMusic === undefined ? {} : { playMusic: event.playMusic }),
    ...(event.progress === undefined ? {} : { progress: event.progress }),
  });
}

function reduceScoreboardState(
  state: BotScoreboardState,
  event: BotScoreboardEvent,
): BotScoreboardState {
  const displaySlots = new Map(state.displaySlots);
  const objectives = new Map(state.objectives);
  const scores = new Map(state.scores);
  const teams = new Map(state.teams);
  const objectiveName = event.objectiveName;
  switch (event.kind) {
    case ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_ADD:
    case ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_UPDATE: {
      if (objectiveName !== undefined) {
        const previous = objectives.get(objectiveName);
        objectives.set(objectiveName, {
          name: objectiveName,
          ...(previous?.displayName === undefined ? {} : { displayName: previous.displayName }),
          ...(previous?.renderType === undefined ? {} : { renderType: previous.renderType }),
          ...(event.displayName === undefined ? {} : { displayName: event.displayName }),
          ...(event.renderType === undefined ? {} : { renderType: event.renderType }),
        });
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_OBJECTIVE_REMOVE: {
      if (objectiveName !== undefined) {
        objectives.delete(objectiveName);
        for (const [slot, displayedObjective] of displaySlots) {
          if (displayedObjective === objectiveName) {
            displaySlots.delete(slot);
          }
        }
        for (const [key, score] of scores) {
          if (score.objectiveName === objectiveName) {
            scores.delete(key);
          }
        }
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_DISPLAY_OBJECTIVE: {
      if (event.displaySlot !== undefined) {
        if (objectiveName === undefined || objectiveName.length === 0) {
          displaySlots.delete(event.displaySlot);
        } else {
          displaySlots.set(event.displaySlot, objectiveName);
        }
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_SCORE_SET: {
      if (objectiveName !== undefined && event.owner !== undefined && event.score !== undefined) {
        scores.set(scoreboardScoreKey(objectiveName, event.owner), {
          objectiveName,
          owner: event.owner,
          score: event.score,
          ...(event.displayName === undefined ? {} : { displayName: event.displayName }),
        });
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_SCORE_RESET: {
      if (event.owner !== undefined) {
        if (objectiveName !== undefined) {
          scores.delete(scoreboardScoreKey(objectiveName, event.owner));
        } else {
          for (const [key, score] of scores) {
            if (score.owner === event.owner) {
              scores.delete(key);
            }
          }
        }
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_REMOVE: {
      if (event.teamName !== undefined) {
        teams.delete(event.teamName);
      }
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_ADD:
    case ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_UPDATE:
    case ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_PLAYERS_ADD:
    case ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_PLAYERS_REMOVE: {
      reduceScoreboardTeam(teams, event);
      break;
    }
    case ScoreboardEventKind.SCOREBOARD_EVENT_UNSPECIFIED:
    default: {
      break;
    }
  }
  return { displaySlots, objectives, scores, teams };
}

function reduceScoreboardTeam(
  teams: Map<string, BotScoreboardTeam>,
  event: BotScoreboardEvent,
): void {
  if (event.teamName === undefined) {
    return;
  }
  const previous = teams.get(event.teamName);
  const players = new Set(previous?.players ?? []);
  if (
    event.kind === ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_ADD ||
    event.kind === ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_UPDATE
  ) {
    players.clear();
    for (const player of event.players) {
      players.add(player);
    }
  } else if (event.kind === ScoreboardEventKind.SCOREBOARD_EVENT_TEAM_PLAYERS_ADD) {
    for (const player of event.players) {
      players.add(player);
    }
  } else {
    for (const player of event.players) {
      players.delete(player);
    }
  }
  teams.set(event.teamName, {
    name: event.teamName,
    players,
    ...(previous?.allowFriendlyFire === undefined
      ? {}
      : { allowFriendlyFire: previous.allowFriendlyFire }),
    ...(previous?.collisionRule === undefined ? {} : { collisionRule: previous.collisionRule }),
    ...(previous?.color === undefined ? {} : { color: previous.color }),
    ...(previous?.displayName === undefined ? {} : { displayName: previous.displayName }),
    ...(previous?.nameTagVisibility === undefined
      ? {}
      : { nameTagVisibility: previous.nameTagVisibility }),
    ...(previous?.prefix === undefined ? {} : { prefix: previous.prefix }),
    ...(previous?.seeFriendlyInvisibles === undefined
      ? {}
      : { seeFriendlyInvisibles: previous.seeFriendlyInvisibles }),
    ...(previous?.suffix === undefined ? {} : { suffix: previous.suffix }),
    ...(event.allowFriendlyFire === undefined
      ? {}
      : { allowFriendlyFire: event.allowFriendlyFire }),
    ...(event.collisionRule === undefined ? {} : { collisionRule: event.collisionRule }),
    ...(event.color === undefined ? {} : { color: event.color }),
    ...(event.displayName === undefined ? {} : { displayName: event.displayName }),
    ...(event.nameTagVisibility === undefined
      ? {}
      : { nameTagVisibility: event.nameTagVisibility }),
    ...(event.prefix === undefined ? {} : { prefix: event.prefix }),
    ...(event.seeFriendlyInvisibles === undefined
      ? {}
      : { seeFriendlyInvisibles: event.seeFriendlyInvisibles }),
    ...(event.suffix === undefined ? {} : { suffix: event.suffix }),
  });
}

function scoreboardScoreKey(objectiveName: string, owner: string): string {
  return `${objectiveName}\u0000${owner}`;
}

function defaultFilter(): MessageInitShape<typeof BotEventFilterSchema> {
  return {
    includeBlockUpdates: true,
    includeBossBars: true,
    includeChat: true,
    includeDamage: true,
    includeEntityEvents: true,
    includeEnvironment: true,
    includeInventory: true,
    includeLifecycle: true,
    includePlayerList: true,
    includeResourcePacks: true,
    includeScoreboard: true,
    includeStateDeltas: true,
    includeTitles: true,
  };
}

function mergePlayerState(
  player: BotLiveState,
  delta: Extract<BotEvent["event"], { case: "stateDelta" }>["value"],
): BotLiveState {
  return create(BotLiveStateSchema, {
    ...player,
    ...(delta.x === undefined ? {} : { x: delta.x }),
    ...(delta.y === undefined ? {} : { y: delta.y }),
    ...(delta.z === undefined ? {} : { z: delta.z }),
    ...(delta.xRot === undefined ? {} : { xRot: delta.xRot }),
    ...(delta.yRot === undefined ? {} : { yRot: delta.yRot }),
    ...(delta.health === undefined ? {} : { health: delta.health }),
    ...(delta.maxHealth === undefined ? {} : { maxHealth: delta.maxHealth }),
    ...(delta.foodLevel === undefined ? {} : { foodLevel: delta.foodLevel }),
    ...(delta.saturationLevel === undefined ? {} : { saturationLevel: delta.saturationLevel }),
    ...(delta.selectedHotbarSlot === undefined
      ? {}
      : { selectedHotbarSlot: delta.selectedHotbarSlot }),
    ...(delta.dimension === undefined ? {} : { dimension: delta.dimension }),
    ...(delta.experienceLevel === undefined ? {} : { experienceLevel: delta.experienceLevel }),
    ...(delta.experienceProgress === undefined
      ? {}
      : { experienceProgress: delta.experienceProgress }),
    ...(delta.gameMode === undefined ? {} : { gameMode: delta.gameMode }),
  });
}

function blockKey(position: {
  readonly dimension: string;
  readonly x: number;
  readonly y: number;
  readonly z: number;
}): string {
  return `${position.dimension}:${position.x}:${position.y}:${position.z}`;
}
