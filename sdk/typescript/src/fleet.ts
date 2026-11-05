import type { DescMessage, MessageInitShape, MessageShape } from "@bufbuild/protobuf";
import type { Value } from "@bufbuild/protobuf/wkt";
import type { CallOptions } from "@connectrpc/connect";
import { Effect, Result, Stream } from "effect";
import { operationError, type SoulFireOperationError } from "./errors.js";

import type { SoulFireInstance } from "./client.js";
import type { CapabilitySet } from "./connection.js";
import type {
  BotConnectionPhase,
  BotDesiredState,
  BotListEntry,
  BotRuntimeState,
  BotStatus,
} from "./generated/soulfire/bot_pb.js";
import type {
  MinecraftAccountProto,
  MinecraftAccountProto_AccountTypeProto,
} from "./generated/soulfire/common_pb.js";
import type { BotTask, BotTaskEvent } from "./generated/soulfire/task_pb.js";
import type { SoulFireTask, TaskStartOptions } from "./tasks.js";

export interface FleetPoint {
  x: number;
  y: number;
  z: number;
  dimension?: string;
}

export interface FleetMetadataSelector {
  namespace: string;
  key: string;
  exists?: boolean;
  equals?: unknown;
}

export interface FleetSelector {
  botIds?: readonly string[];
  accountNames?: readonly string[];
  accountTypes?: readonly MinecraftAccountProto_AccountTypeProto[];
  online?: boolean;
  desiredStates?: readonly BotDesiredState[];
  runtimeStates?: readonly BotRuntimeState[];
  connectionPhases?: readonly BotConnectionPhase[];
  dimensions?: readonly string[];
  minimumHealth?: number;
  maximumHealth?: number;
  minimumFoodLevel?: number;
  maximumPingMs?: number;
  near?: FleetPoint & { radius: number };
  metadata?: readonly FleetMetadataSelector[];
  requiredCapabilities?: readonly string[];
  predicate?: (bot: FleetBot) => boolean | Effect.Effect<boolean, SoulFireOperationError>;
  orderBy?:
    | "configured"
    | "name"
    | "health"
    | "distance"
    | "random"
    | ((left: FleetBot, right: FleetBot) => number);
  limit?: number;
}

export interface FleetBot {
  readonly id: string;
  readonly entry: Readonly<BotListEntry>;
  readonly account?: Readonly<MinecraftAccountProto>;
  readonly metadata: Readonly<Record<string, Readonly<Record<string, unknown>>>>;
}

export interface FleetDistributionOptions {
  strategy?: "round-robin" | "contiguous";
  maximumItemsPerBot?: number;
  requireAll?: boolean;
  call?: CallOptions;
}

export interface FleetAssignment<Item> {
  readonly bot: FleetBot;
  readonly items: readonly Item[];
}

export interface FleetTaskStartFailure {
  readonly bot: FleetBot;
  readonly error: unknown;
}

export interface FleetTaskMember<Result extends DescMessage | undefined = undefined> {
  readonly bot: FleetBot;
  readonly task: SoulFireTask<Result>;
}

export interface FleetTaskEvent {
  readonly bot: FleetBot;
  readonly event: BotTaskEvent;
}

export type FleetTaskResultValue<Result extends DescMessage | undefined> =
  Result extends DescMessage ? MessageShape<Result> : BotTask;

export type FleetTaskOutcome<Result extends DescMessage | undefined> =
  | {
      readonly status: "fulfilled";
      readonly bot: FleetBot;
      readonly value: FleetTaskResultValue<Result>;
    }
  | {
      readonly status: "rejected";
      readonly bot: FleetBot;
      readonly error: unknown;
    };

export interface FleetTaskReport<Result extends DescMessage | undefined> {
  readonly outcomes: readonly FleetTaskOutcome<Result>[];
  readonly fulfilled: readonly Extract<FleetTaskOutcome<Result>, { status: "fulfilled" }>[];
  readonly rejected: readonly Extract<FleetTaskOutcome<Result>, { status: "rejected" }>[];
}

export interface FleetTaskStartOptions extends TaskStartOptions {
  concurrency?: number;
}

export class FleetTaskGroupError<Result extends DescMessage | undefined> extends Error {
  public constructor(public readonly report: FleetTaskReport<Result>) {
    super(`${report.rejected.length} of ${report.outcomes.length} fleet tasks failed`);
    this.name = "FleetTaskGroupError";
  }
}

export class SoulFireFleetTaskGroup<Result extends DescMessage | undefined = undefined> {
  public constructor(
    private readonly members: readonly FleetTaskMember<Result>[],
    public readonly startFailures: readonly FleetTaskStartFailure[],
  ) {}

  public get size(): number {
    return this.members.length + this.startFailures.length;
  }
  public get botIds(): readonly string[] {
    return this.members.map(({ bot }) => bot.id);
  }
  public get taskIds(): readonly string[] {
    return this.members.map(({ task }) => task.id);
  }
  public task(botId: string): SoulFireTask<Result> | undefined {
    return this.members.find(({ bot }) => bot.id === botId)?.task;
  }

  public events(options?: {
    afterRevision?: bigint;
    call?: CallOptions;
  }): Stream.Stream<FleetTaskEvent, SoulFireOperationError> {
    return Stream.mergeAll(
      this.members.map(({ bot, task }) =>
        task.events(options).pipe(Stream.map((event) => ({ bot, event }))),
      ),
      { concurrency: "unbounded", bufferSize: 1 },
    );
  }

  public results(options?: { call?: CallOptions }): Effect.Effect<FleetTaskReport<Result>> {
    return Effect.forEach(
      this.members,
      ({ bot, task }) =>
        task.result(options).pipe(
          Effect.match({
            onSuccess: (value): FleetTaskOutcome<Result> => ({
              status: "fulfilled",
              bot,
              value,
            }),
            onFailure: (error): FleetTaskOutcome<Result> => ({
              status: "rejected",
              bot,
              error,
            }),
          }),
        ),
      { concurrency: "unbounded" },
    ).pipe(
      Effect.map((outcomes) =>
        taskReport([
          ...this.startFailures.map(({ bot, error }): FleetTaskOutcome<Result> => ({
            status: "rejected",
            bot,
            error,
          })),
          ...outcomes,
        ]),
      ),
    );
  }

  public requireResults(options?: {
    call?: CallOptions;
  }): Effect.Effect<
    readonly Extract<FleetTaskOutcome<Result>, { status: "fulfilled" }>[],
    FleetTaskGroupError<Result>
  > {
    return this.results(options).pipe(
      Effect.flatMap((report) =>
        report.rejected.length > 0
          ? Effect.fail(new FleetTaskGroupError(report))
          : Effect.succeed(report.fulfilled),
      ),
    );
  }

  public cancel(
    reason = "",
    options?: { call?: CallOptions; concurrency?: number },
  ): Effect.Effect<FleetTaskReport<undefined>, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const concurrency = yield* Effect.try({
        try: () =>
          Math.max(1, normalizeNonNegativeInteger(options?.concurrency ?? 8, "concurrency")),
        catch: (cause) => operationError("fleet.cancel", cause),
      });
      const outcomes = yield* Effect.forEach(
        this.members,
        ({ bot, task }) =>
          task.cancel(reason, options?.call).pipe(
            Effect.match({
              onSuccess: (value): FleetTaskOutcome<undefined> => ({
                status: "fulfilled",
                bot,
                value,
              }),
              onFailure: (error): FleetTaskOutcome<undefined> => ({
                status: "rejected",
                bot,
                error,
              }),
            }),
          ),
        { concurrency },
      );
      return taskReport(outcomes);
    });
  }
}

export class SoulFireFleet {
  public constructor(
    private readonly instance: SoulFireInstance,
    private readonly capabilities?: CapabilitySet,
  ) {}

  public select(
    selector: FleetSelector = {},
    options?: CallOptions,
  ): Effect.Effect<readonly FleetBot[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      yield* Effect.try({
        try: () => {
          for (const capability of selector.requiredCapabilities ?? []) {
            if (this.capabilities === undefined)
              throw new Error(
                "Fleet capability selection requires a negotiated Soulfire connection",
              );
            this.capabilities.require(capability);
          }
        },
        catch: (cause) => operationError("fleet.select", cause),
      });
      const [entries, info] = yield* Effect.all(
        [this.instance.bots(options), this.instance.info(options)],
        { concurrency: 2 },
      );
      const accounts = new Map(
        (info.config?.accounts ?? []).map((account) => [account.profileId, account]),
      );
      let bots = entries
        .map((entry): FleetBot => {
          const account = accounts.get(entry.profileId);
          return {
            id: entry.profileId,
            entry,
            ...(account === undefined ? {} : { account }),
            metadata: metadataRecord(account?.persistentMetadata ?? []),
          };
        })
        .filter((bot) => matchesSelector(bot, selector));
      if (selector.predicate !== undefined) {
        const predicate = selector.predicate;
        bots = yield* Effect.filter(
          bots,
          (bot) => {
            const decision = predicate(bot);
            return Effect.isEffect(decision) ? decision : Effect.succeed(decision);
          },
          { concurrency: "unbounded" },
        );
      }
      return yield* Effect.try({
        try: () => {
          orderBots(bots, selector);
          return selector.limit === undefined
            ? bots
            : bots.slice(0, normalizeNonNegativeInteger(selector.limit, "limit"));
        },
        catch: (cause) => operationError("fleet.select", cause),
      });
    });
  }

  public start(
    selector: FleetSelector = {},
    options?: CallOptions,
  ): Effect.Effect<BotStatus[], SoulFireOperationError> {
    return this.select(selector, options).pipe(
      Effect.flatMap((bots) => this.instance.start({ botIds: bots.map(({ id }) => id) }, options)),
    );
  }
  public stop(
    selector: FleetSelector = {},
    options?: CallOptions,
  ): Effect.Effect<BotStatus[], SoulFireOperationError> {
    return this.select(selector, options).pipe(
      Effect.flatMap((bots) => this.instance.stop({ botIds: bots.map(({ id }) => id) }, options)),
    );
  }
  public restart(
    selector: FleetSelector = {},
    options?: CallOptions,
  ): Effect.Effect<BotStatus[], SoulFireOperationError> {
    return this.select(selector, options).pipe(
      Effect.flatMap((bots) =>
        this.instance.restart({ botIds: bots.map(({ id }) => id) }, options),
      ),
    );
  }

  public startTasks<Input extends DescMessage, Result extends DescMessage | undefined = undefined>(
    selector: FleetSelector,
    inputSchema: Input,
    input:
      | MessageInitShape<Input>
      | ((
          bot: FleetBot,
          index: number,
          total: number,
        ) =>
          | MessageInitShape<Input>
          | Effect.Effect<MessageInitShape<Input>, SoulFireOperationError>),
    resultSchema?: Result,
    options: FleetTaskStartOptions = {},
  ): Effect.Effect<SoulFireFleetTaskGroup<Result>, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const { concurrency: requestedConcurrency = 8, ...taskOptions } = options;
      const concurrency = yield* Effect.try({
        try: () => Math.max(1, normalizeNonNegativeInteger(requestedConcurrency, "concurrency")),
        catch: (cause) => operationError("fleet.startTasks", cause),
      });
      const bots = yield* this.select(selector, taskOptions.call);
      const started: FleetTaskMember<Result>[] = [];
      const outcomes = yield* Effect.forEach(
        bots,
        (bot, index) =>
          Effect.uninterruptibleMask((restore) =>
            Effect.gen({ self: this }, function* () {
              const value = typeof input === "function" ? input(bot, index, bots.length) : input;
              const taskInput = Effect.isEffect(value) ? yield* restore(value) : value;
              const task = yield* this.instance
                .bot(bot.id)
                .tasks.start(inputSchema, taskInput, resultSchema, taskOptions);
              const member = { bot, task };
              started.push(member);
              return member;
            }),
          ).pipe(Effect.result),
        { concurrency },
      ).pipe(
        Effect.onError(() =>
          Effect.forEach(
            started,
            ({ task }) => task.cancel("fleet task start aborted").pipe(Effect.ignore),
            { concurrency, discard: true },
          ),
        ),
      );
      const members: FleetTaskMember<Result>[] = [];
      const failures: FleetTaskStartFailure[] = [];
      outcomes.forEach((outcome, index) => {
        if (Result.isSuccess(outcome)) members.push(outcome.success);
        else failures.push({ bot: bots[index]!, error: outcome.failure });
      });
      return new SoulFireFleetTaskGroup(members, failures);
    });
  }
  public distribute<Item>(
    items: readonly Item[],
    selector: FleetSelector = {},
    options: FleetDistributionOptions = {},
  ): Effect.Effect<readonly FleetAssignment<Item>[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const bots = yield* this.select(selector, options.call);
      return yield* Effect.try({
        try: () => {
          if (items.length > 0 && bots.length === 0) {
            throw new Error("No bots matched the fleet selector");
          }
          const maximumItems =
            options.maximumItemsPerBot === undefined
              ? Number.POSITIVE_INFINITY
              : normalizeNonNegativeInteger(options.maximumItemsPerBot, "maximumItemsPerBot");
          const buckets = bots.map(() => [] as Item[]);

          if ((options.strategy ?? "round-robin") === "contiguous") {
            let offset = 0;
            for (let index = 0; index < bots.length; index++) {
              const remainingBots = bots.length - index;
              const remainingItems = items.length - offset;
              const size = Math.min(maximumItems, Math.ceil(remainingItems / remainingBots));
              buckets[index]!.push(...items.slice(offset, offset + size));
              offset += size;
            }
          } else {
            let botIndex = 0;
            for (const item of items) {
              while (botIndex < bots.length && buckets[botIndex]!.length >= maximumItems) {
                botIndex++;
              }
              if (botIndex >= bots.length) {
                break;
              }
              buckets[botIndex]!.push(item);
              botIndex = (botIndex + 1) % bots.length;
            }
          }

          const assigned = buckets.reduce((total, bucket) => total + bucket.length, 0);
          if ((options.requireAll ?? true) && assigned !== items.length) {
            throw new RangeError(
              `Fleet capacity ${assigned} is smaller than ${items.length} items`,
            );
          }
          return bots.map((bot, index) => ({
            bot,
            items: buckets[index]!,
          }));
        },
        catch: (cause) => operationError("fleet.distribute", cause),
      });
    });
  }
}

function matchesSelector(bot: FleetBot, selector: FleetSelector): boolean {
  const { entry, account } = bot;
  const live = entry.liveState;
  if (!includes(selector.botIds, bot.id)) {
    return false;
  }
  if (
    selector.accountNames !== undefined &&
    !selector.accountNames.some(
      (name) =>
        name.localeCompare(entry.accountName ?? account?.lastKnownName ?? "", undefined, {
          sensitivity: "accent",
        }) === 0,
    )
  ) {
    return false;
  }
  if (
    selector.accountTypes !== undefined &&
    (account === undefined || !selector.accountTypes.includes(account.type))
  ) {
    return false;
  }
  if (selector.online !== undefined && entry.isOnline !== selector.online) {
    return false;
  }
  if (!includes(selector.desiredStates, entry.status?.desiredState)) {
    return false;
  }
  if (!includes(selector.runtimeStates, entry.status?.runtimeState)) {
    return false;
  }
  if (!includes(selector.connectionPhases, entry.connectionPhase)) {
    return false;
  }
  if (!includes(selector.dimensions, live?.dimension)) {
    return false;
  }
  if (
    selector.minimumHealth !== undefined &&
    (live === undefined || live.health < selector.minimumHealth)
  ) {
    return false;
  }
  if (
    selector.maximumHealth !== undefined &&
    (live === undefined || live.health > selector.maximumHealth)
  ) {
    return false;
  }
  if (
    selector.minimumFoodLevel !== undefined &&
    (live === undefined || live.foodLevel < selector.minimumFoodLevel)
  ) {
    return false;
  }
  if (
    selector.maximumPingMs !== undefined &&
    (entry.pingMs === undefined || entry.pingMs > selector.maximumPingMs)
  ) {
    return false;
  }
  if (selector.near !== undefined) {
    if (
      live === undefined ||
      (selector.near.dimension !== undefined && live.dimension !== selector.near.dimension) ||
      distanceSquared(live, selector.near) > selector.near.radius * selector.near.radius
    ) {
      return false;
    }
  }
  return (selector.metadata ?? []).every((condition) => matchesMetadata(bot, condition));
}

function matchesMetadata(bot: FleetBot, condition: FleetMetadataSelector): boolean {
  const namespace = bot.metadata[condition.namespace];
  const present = namespace !== undefined && Object.hasOwn(namespace, condition.key);
  if (condition.exists !== undefined && present !== condition.exists) {
    return false;
  }
  if ("equals" in condition) {
    return present && deepEqual(namespace?.[condition.key], condition.equals);
  }
  return condition.exists === false || present;
}

function orderBots(bots: FleetBot[], selector: FleetSelector): void {
  const order = selector.orderBy ?? "configured";
  if (order === "configured") {
    return;
  }
  if (order === "random") {
    for (let index = bots.length - 1; index > 0; index--) {
      const other = Math.floor(Math.random() * (index + 1));
      [bots[index], bots[other]] = [bots[other]!, bots[index]!];
    }
    return;
  }
  if (typeof order === "function") {
    bots.sort(order);
    return;
  }
  bots.sort((left, right) => {
    switch (order) {
      case "name":
        return (left.entry.accountName ?? left.account?.lastKnownName ?? "").localeCompare(
          right.entry.accountName ?? right.account?.lastKnownName ?? "",
        );
      case "health":
        return (
          (right.entry.liveState?.health ?? Number.NEGATIVE_INFINITY) -
          (left.entry.liveState?.health ?? Number.NEGATIVE_INFINITY)
        );
      case "distance":
        if (selector.near === undefined) {
          throw new TypeError("orderBy distance requires a near selector");
        }
        return (
          distanceSquared(left.entry.liveState, selector.near) -
          distanceSquared(right.entry.liveState, selector.near)
        );
    }
  });
}

function metadataRecord(
  namespaces: readonly MinecraftAccountProto["persistentMetadata"][number][],
): Readonly<Record<string, Readonly<Record<string, unknown>>>> {
  return Object.fromEntries(
    namespaces.map((namespace) => [
      namespace.namespace,
      Object.fromEntries(
        namespace.entries.map((entry) => [entry.key, valueToUnknown(entry.value)]),
      ),
    ]),
  );
}

function valueToUnknown(value: Value | undefined): unknown {
  if (value === undefined) {
    return undefined;
  }
  switch (value.kind.case) {
    case "nullValue":
      return null;
    case "numberValue":
    case "stringValue":
    case "boolValue":
      return value.kind.value;
    case "structValue":
      return Object.fromEntries(
        Object.entries(value.kind.value.fields).map(([key, child]) => [key, valueToUnknown(child)]),
      );
    case "listValue":
      return value.kind.value.values.map(valueToUnknown);
    case undefined:
      return undefined;
  }
}

function deepEqual(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) {
    return true;
  }
  if (Array.isArray(left) && Array.isArray(right)) {
    return (
      left.length === right.length && left.every((value, index) => deepEqual(value, right[index]))
    );
  }
  if (
    typeof left === "object" &&
    left !== null &&
    typeof right === "object" &&
    right !== null &&
    !Array.isArray(left) &&
    !Array.isArray(right)
  ) {
    const leftEntries = Object.entries(left);
    const rightRecord = right as Record<string, unknown>;
    return (
      leftEntries.length === Object.keys(rightRecord).length &&
      leftEntries.every(
        ([key, value]) => Object.hasOwn(rightRecord, key) && deepEqual(value, rightRecord[key]),
      )
    );
  }
  return false;
}

function includes<T>(values: readonly T[] | undefined, value: T | undefined): boolean {
  return values === undefined || (value !== undefined && values.includes(value));
}

function distanceSquared(
  point: { x: number; y: number; z: number } | undefined,
  target: FleetPoint,
): number {
  if (point === undefined) {
    return Number.POSITIVE_INFINITY;
  }
  const dx = point.x - target.x;
  const dy = point.y - target.y;
  const dz = point.z - target.z;
  return dx * dx + dy * dy + dz * dz;
}

function normalizeNonNegativeInteger(value: number, name: string): number {
  if (!Number.isFinite(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative finite number`);
  }
  return Math.floor(value);
}

function taskReport<Result extends DescMessage | undefined>(
  outcomes: readonly FleetTaskOutcome<Result>[],
): FleetTaskReport<Result> {
  return {
    outcomes,
    fulfilled: outcomes.filter(
      (outcome): outcome is Extract<FleetTaskOutcome<Result>, { status: "fulfilled" }> =>
        outcome.status === "fulfilled",
    ),
    rejected: outcomes.filter(
      (outcome): outcome is Extract<FleetTaskOutcome<Result>, { status: "rejected" }> =>
        outcome.status === "rejected",
    ),
  };
}
