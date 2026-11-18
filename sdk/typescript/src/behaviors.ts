import { Data, Duration, Effect, Option, Schedule, Stream, type Cause } from "effect";

import type { SoulFireBot } from "./client.js";

import type { SoulFireOperationError } from "./errors.js";
import { type BlockFace, Hand } from "./generated/soulfire/bot_live_pb.js";
import type { BlockPosition } from "./generated/soulfire/common_pb.js";
import { BotTaskStatus, type BotTaskEvent } from "./generated/soulfire/task_pb.js";

interface SoulFireBehaviorErrorFields {
  readonly behavior: string;
  readonly message: string;
}

const SoulFireBehaviorErrorBase: new (args: SoulFireBehaviorErrorFields) => Cause.YieldableError & {
  readonly _tag: "SoulFireBehaviorError";
} & Readonly<SoulFireBehaviorErrorFields> = Data.TaggedError(
  "SoulFireBehaviorError",
)<SoulFireBehaviorErrorFields>;

export class SoulFireBehaviorError extends SoulFireBehaviorErrorBase {}

export type BehaviorFailure = SoulFireOperationError | SoulFireBehaviorError;

export interface BotBehavior<A = void, E = BehaviorFailure, R = never> {
  readonly run: (bot: SoulFireBot) => Effect.Effect<A, E, R>;
}

export type BehaviorResult<B> = B extends BotBehavior<infer A, infer _E, infer _R> ? A : never;
export type BehaviorError<B> = B extends BotBehavior<infer _A, infer E, infer _R> ? E : never;
export type BehaviorContext<B> = B extends BotBehavior<infer _A, infer _E, infer R> ? R : never;
export type BehaviorResults<B extends readonly BotBehavior<unknown, unknown, unknown>[]> = {
  readonly [K in keyof B]: BehaviorResult<B[K]>;
};

export function defineBehavior<A, E = never, R = never>(
  run: (bot: SoulFireBot) => Effect.Effect<A, E, R>,
): BotBehavior<A, E, R> {
  return { run };
}

export function runBehaviors<E, R>(
  bot: SoulFireBot,
  behaviors: readonly BotBehavior<unknown, E, R>[],
): Effect.Effect<void, E, R> {
  return Effect.forEach(behaviors, (behavior) => behavior.run(bot), {
    concurrency: 1,
    discard: true,
  });
}

export function sequence<const B extends readonly BotBehavior<unknown, unknown, unknown>[]>(
  ...behaviors: B
): BotBehavior<BehaviorResults<B>, BehaviorError<B[number]>, BehaviorContext<B[number]>>;
export function sequence(
  ...behaviors: readonly BotBehavior<unknown, unknown, unknown>[]
): BotBehavior<readonly unknown[], unknown, unknown> {
  return defineBehavior((bot) =>
    Effect.forEach(behaviors, (behavior) => behavior.run(bot), { concurrency: 1 }),
  );
}

export interface ParallelOptions {
  readonly concurrency?: number | "unbounded";
}
export function parallel<const B extends readonly BotBehavior<unknown, unknown, unknown>[]>(
  behaviors: B,
  options?: ParallelOptions,
): BotBehavior<BehaviorResults<B>, BehaviorError<B[number]>, BehaviorContext<B[number]>>;
export function parallel(
  behaviors: readonly BotBehavior<unknown, unknown, unknown>[],
  options: ParallelOptions = {},
): BotBehavior<readonly unknown[], unknown, unknown> {
  return defineBehavior((bot) =>
    Effect.forEach(behaviors, (behavior) => behavior.run(bot), {
      concurrency: options.concurrency ?? "unbounded",
    }),
  );
}

export function race<
  const B extends readonly [
    BotBehavior<unknown, unknown, unknown>,
    ...BotBehavior<unknown, unknown, unknown>[],
  ],
>(
  ...behaviors: B
): BotBehavior<BehaviorResult<B[number]>, BehaviorError<B[number]>, BehaviorContext<B[number]>>;
export function race(
  ...behaviors: readonly BotBehavior<unknown, unknown, unknown>[]
): BotBehavior<unknown, unknown, unknown> {
  return defineBehavior((bot) => Effect.raceAll(behaviors.map((behavior) => behavior.run(bot))));
}

export interface RepeatOptions {
  readonly times: number;
}
export function repeat<A, E, R>(
  behavior: BotBehavior<A, E, R>,
  options: RepeatOptions,
): BotBehavior<readonly A[], E, R> {
  const times = positiveInteger(options.times, "times");
  return defineBehavior((bot) =>
    Effect.forEach(Array.from({ length: times }), () => behavior.run(bot), { concurrency: 1 }),
  );
}

export interface RetryOptions<E = BehaviorFailure> {
  readonly attempts?: number;
  readonly delayMs?: number;
  readonly backoff?: number;
  readonly maximumDelayMs?: number;
  readonly while?: (error: E) => boolean;
}
export function retry<A, E, R>(
  behavior: BotBehavior<A, E, R>,
  options: RetryOptions<E> = {},
): BotBehavior<A, E, R> {
  const attempts = positiveInteger(options.attempts ?? 3, "attempts");
  const initialDelay = nonNegativeFinite(options.delayMs ?? 0, "delayMs");
  const backoff = positiveFinite(options.backoff ?? 1, "backoff");
  const maximumDelay = nonNegativeFinite(
    options.maximumDelayMs ?? Number.MAX_SAFE_INTEGER,
    "maximumDelayMs",
  );
  const schedule = Schedule.exponential(initialDelay, backoff).pipe(
    Schedule.modifyDelay(({ duration }) =>
      Effect.succeed(Duration.min(duration, Duration.millis(maximumDelay))),
    ),
    Schedule.upTo({ times: attempts - 1 }),
  );
  return defineBehavior((bot) =>
    Effect.suspend(() => behavior.run(bot)).pipe(
      Effect.retry({ schedule, while: options.while ?? (() => true) }),
    ),
  );
}

export function timeout<A, E, R>(
  behavior: BotBehavior<A, E, R>,
  durationMs: number,
): BotBehavior<A, E | SoulFireBehaviorError, R> {
  const duration = positiveFinite(durationMs, "durationMs");
  return defineBehavior((bot) =>
    behavior.run(bot).pipe(
      Effect.timeoutOrElse({
        duration,
        orElse: () =>
          Effect.fail(
            new SoulFireBehaviorError({
              behavior: "timeout",
              message: `Behavior exceeded ${duration} ms`,
            }),
          ),
      }),
    ),
  );
}

export interface UntilOptions {
  readonly maximumIterations?: number;
}
export type BehaviorPredicate<A, E = BehaviorFailure, R = never> = (
  result: A,
) => boolean | Effect.Effect<boolean, E, R>;
export function until<A, E, R, E2 = never, R2 = never>(
  behavior: BotBehavior<A, E, R>,
  predicate: BehaviorPredicate<A, E2, R2>,
  options: UntilOptions = {},
): BotBehavior<A, E | E2 | SoulFireBehaviorError, R | R2> {
  const maximumIterations =
    options.maximumIterations === undefined
      ? Number.MAX_SAFE_INTEGER
      : positiveInteger(options.maximumIterations, "maximumIterations");
  return defineBehavior((bot) =>
    Effect.gen(function* () {
      for (let iteration = 0; iteration < maximumIterations; iteration += 1) {
        const result = yield* behavior.run(bot);
        const decision = predicate(result);
        const done = yield* Effect.isEffect(decision) ? decision : Effect.succeed(decision);
        if (done) return result;
      }
      return yield* new SoulFireBehaviorError({
        behavior: "until",
        message: `Predicate remained false after ${maximumIterations} iterations`,
      });
    }),
  );
}

export type BotPredicate<E = BehaviorFailure, R = never> = (
  bot: SoulFireBot,
) => boolean | Effect.Effect<boolean, E, R>;
export function conditional<A, E, R, B = void, E2 = never, R2 = never, EP = never, RP = never>(
  predicate: BotPredicate<EP, RP>,
  whenTrue: BotBehavior<A, E, R>,
  whenFalse?: BotBehavior<B, E2, R2>,
): BotBehavior<A | B | undefined, E | E2 | EP, R | R2 | RP> {
  return defineBehavior((bot) =>
    Effect.gen(function* () {
      const decision = predicate(bot);
      const matches = yield* Effect.isEffect(decision) ? decision : Effect.succeed(decision);
      if (matches) return yield* whenTrue.run(bot);
      if (whenFalse !== undefined) return yield* whenFalse.run(bot);
      return undefined;
    }),
  );
}

export function fallback<A, E, R>(
  primary: BotBehavior<A, E, R>,
  ...alternatives: readonly BotBehavior<A, E, R>[]
): BotBehavior<A, E, R> {
  return defineBehavior((bot) =>
    alternatives.reduce(
      (current, alternative) => current.pipe(Effect.catch(() => alternative.run(bot))),
      primary.run(bot),
    ),
  );
}
export function cleanup<A, E, R, E2, R2>(
  behavior: BotBehavior<A, E, R>,
  finalizer: BotBehavior<unknown, E2, R2>,
): BotBehavior<A, E, R | R2> {
  return defineBehavior((bot) =>
    behavior.run(bot).pipe(Effect.ensuring(finalizer.run(bot).pipe(Effect.orDie))),
  );
}
export function scopedLease<A, E, R>(
  behavior: BotBehavior<A, E, R>,
  ttlSeconds = 30,
): BotBehavior<A, E | SoulFireOperationError, R> {
  const ttl = positiveInteger(ttlSeconds, "ttlSeconds");
  return defineBehavior((bot) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* bot.acquireControlScoped(ttl);
        return yield* behavior.run(bot);
      }),
    ),
  );
}

export interface CollectBlocksOptions {
  readonly blockIds: readonly string[];
  readonly tags?: readonly string[];
  readonly count?: number;
  readonly searchRadius?: number;
  readonly allowPlacing?: boolean;
  readonly requireLineOfSight?: boolean;
  readonly targetYRange?: Readonly<{
    minimum?: number;
    maximum?: number;
  }>;
}

export function collectBlocks(options: CollectBlocksOptions): BotBehavior<number> {
  return defineBehavior((bot) =>
    Effect.gen(function* () {
      const task = yield* bot.tasks.collectBlocks(options.blockIds, {
        tags: options.tags ?? [],
        count: options.count ?? 1,
        searchRadius: options.searchRadius ?? 32,
        requireLineOfSight: options.requireLineOfSight ?? false,
        ...(options.targetYRange === undefined ? {} : { targetYRange: options.targetYRange }),
        path: {
          allowMining: true,
          allowPlacing: options.allowPlacing ?? false,
        },
      });
      return (yield* task.result()).blocksBroken;
    }),
  );
}

export function followEntity(entityId: number, radius = 3): BotBehavior {
  return defineBehavior((bot) =>
    completeTask(
      bot.tasks.runFollowEntity(entityId, radius, {
        path: {
          allowMining: false,
          allowPlacing: false,
        },
      }),
    ),
  );
}

export interface AttackNearestOptions {
  readonly entityTypes: readonly string[];
  readonly radius?: number;
  readonly attackRange?: number;
  readonly sprinting?: boolean;
  readonly maximumAttacks?: number;
}

export function attackNearest(options: AttackNearestOptions): BotBehavior<boolean> {
  return defineBehavior((bot) =>
    Effect.gen(function* () {
      const response = yield* bot.listNearbyEntities({
        entityTypes: [...options.entityTypes],
        includePlayers: false,
        radius: options.radius ?? 32,
      });
      const target = response.entities[0];
      if (target === undefined) {
        return false;
      }
      yield* completeTask(
        bot.tasks.runAttackEntity(target.entityId, {
          attackRange: options.attackRange ?? 3,
          sprinting: options.sprinting ?? false,
          maximumAttacks: options.maximumAttacks ?? 0,
          path: {
            allowMining: false,
            allowPlacing: false,
          },
        }),
      );
      return true;
    }),
  );
}

export interface AutoEatOptions {
  readonly foodItemIds: readonly string[];
  readonly foodLevel?: number;
  readonly checkIntervalTicks?: number;
  readonly maximumMeals?: number;
  readonly completeWhenNoFood?: boolean;
  readonly restoreSelectedSlot?: boolean;
}

export function autoEat(options: AutoEatOptions): BotBehavior {
  return defineBehavior((bot) =>
    completeTask(
      bot.tasks.runAutoEat(options.foodItemIds, {
        foodLevel: options.foodLevel ?? 14,
        checkIntervalTicks: options.checkIntervalTicks ?? 20,
        maximumMeals: options.maximumMeals ?? 0,
        completeWhenNoFood: options.completeWhenNoFood ?? false,
        restoreSelectedSlot: options.restoreSelectedSlot ?? true,
      }),
      "autoEat",
    ),
  );
}

export interface AutoRespawnOptions {
  readonly respawnDelayTicks?: number;
  readonly maximumRespawns?: number;
}

export function autoRespawn(options: AutoRespawnOptions = {}): BotBehavior {
  return defineBehavior((bot) =>
    completeTask(
      bot.tasks.runAutoRespawn({
        respawnDelayTicks: options.respawnDelayTicks ?? 0,
        maximumRespawns: options.maximumRespawns ?? 0,
      }),
      "autoRespawn",
    ),
  );
}

export interface AutoTotemOptions {
  readonly checkIntervalTicks?: number;
  readonly maximumEquips?: number;
  readonly completeWhenNoTotem?: boolean;
  readonly replaceOccupiedOffhand?: boolean;
}

export function autoTotem(options: AutoTotemOptions = {}): BotBehavior {
  return defineBehavior((bot) =>
    completeTask(
      bot.tasks.runAutoTotem({
        checkIntervalTicks: options.checkIntervalTicks ?? 20,
        maximumEquips: options.maximumEquips ?? 0,
        completeWhenNoTotem: options.completeWhenNoTotem ?? false,
        replaceOccupiedOffhand: options.replaceOccupiedOffhand ?? false,
      }),
      "autoTotem",
    ),
  );
}

export interface AutoArmorOptions {
  readonly checkIntervalTicks?: number;
  readonly maximumEquips?: number;
  readonly completeWhenNoUpgrade?: boolean;
}

export function autoArmor(options: AutoArmorOptions = {}): BotBehavior {
  return defineBehavior((bot) =>
    completeTask(
      bot.tasks.runAutoArmor({
        checkIntervalTicks: options.checkIntervalTicks ?? 20,
        maximumEquips: options.maximumEquips ?? 0,
        completeWhenNoUpgrade: options.completeWhenNoUpgrade ?? false,
      }),
      "autoArmor",
    ),
  );
}

export interface BuildPlacement {
  readonly against: BlockPosition;
  readonly face: BlockFace;
  readonly hotbarSlot?: number;
}

export function build(placements: readonly BuildPlacement[]): BotBehavior<number> {
  return defineBehavior((bot) =>
    Effect.gen(function* () {
      let placed = 0;
      for (const placement of placements) {
        if (placement.hotbarSlot !== undefined) {
          yield* bot.selectHotbar(placement.hotbarSlot);
        }
        yield* bot.placeBlock({
          against: placement.against,
          face: placement.face,
          hand: Hand.MAIN,
        });
        placed += 1;
      }
      return placed;
    }),
  );
}

function completeTask(
  events: Stream.Stream<BotTaskEvent, SoulFireOperationError>,
  behavior = "task",
): Effect.Effect<void, SoulFireOperationError | SoulFireBehaviorError> {
  return Stream.runFold(
    events,
    () => Option.none<BotTaskEvent>(),
    (_, event) => Option.some(event),
  ).pipe(
    Effect.flatMap((last) => {
      const task = Option.getOrUndefined(last)?.task;
      if (task?.status === BotTaskStatus.COMPLETED) {
        return Effect.void;
      }
      return Effect.fail(
        new SoulFireBehaviorError({
          behavior,
          message: task?.failure?.message ?? `${behavior} ended without successful completion`,
        }),
      );
    }),
  );
}

function positiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive safe integer`);
  }
  return value;
}

function nonNegativeFinite(value: number, name: string): number {
  if (!Number.isFinite(value) || value < 0) {
    throw new RangeError(`${name} must be a finite non-negative number`);
  }
  return value;
}

function positiveFinite(value: number, name: string): number {
  if (!Number.isFinite(value) || value <= 0) {
    throw new RangeError(`${name} must be a finite positive number`);
  }
  return value;
}
