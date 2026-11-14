import { create, type MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect } from "effect";
import { operationError, type SoulFireOperationError } from "./errors.js";
import { rpc, withSignal } from "./transport.js";

import {
  PathfindGoalSchema,
  PathfindOptionsSchema,
  type PathfindGoal,
} from "./generated/soulfire/bot_live_pb.js";
import { BlockPositionSchema, WorldPositionSchema } from "./generated/soulfire/common_pb.js";
import type { EntityReference } from "./generated/soulfire/domain_pb.js";
import { PathfinderService, type PathPlan } from "./generated/soulfire/pathfinding_pb.js";
import type { FollowEntityTaskOptions, GoToTaskOptions, SoulFireTasks } from "./tasks.js";

export type BlockTarget = MessageInitShape<typeof BlockPositionSchema>;
export type WorldTarget = MessageInitShape<typeof WorldPositionSchema>;
export type EntityTarget = Pick<EntityReference, "connectionEpoch" | "networkId"> | number;

export interface PlanPathOptions {
  path?: MessageInitShape<typeof PathfindOptionsSchema>;
  /**
   * Adds a readable description to each step.
   */
  includeDescriptions?: boolean;
  call?: CallOptions;
}

export class SoulFirePathfinder {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof PathfinderService>,
    private readonly tasks: SoulFireTasks,
  ) {}

  /**
   * Plans a route to `goal` from the loaded world, without moving the bot.
   */

  public plan(
    goal: PathfindGoal,
    options: PlanPathOptions = {},
  ): Effect.Effect<PathPlan, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFirePathfinder.plan", (signal) =>
        this.client.planPath(
          {
            instanceId: this.instanceId,
            botId: this.botId,
            goal,
            ...(options.path === undefined ? {} : { options: options.path }),
            includeDescriptions: options.includeDescriptions ?? false,
          },
          withSignal(options.call, signal),
        ),
      );
      if (response.plan === undefined) {
        return yield* Effect.fail(
          operationError(
            "SoulFirePathfinder.plan",
            new Error("Soulfire did not return a path plan"),
          ),
        );
      }
      return response.plan;
    });
  }

  /**
   * Same as `tasks.goTo`.
   */
  public goTo(
    goal: PathfindGoal,
    options: GoToTaskOptions = {},
  ): ReturnType<SoulFireTasks["goTo"]> {
    return this.tasks.goTo(goal, options);
  }

  /**
   * Same as `tasks.runGoTo`.
   */
  public run(
    goal: PathfindGoal,
    options: GoToTaskOptions = {},
  ): ReturnType<SoulFireTasks["runGoTo"]> {
    return this.tasks.runGoTo(goal, options);
  }

  /**
   * Same as `tasks.followEntity`: `distance` defaults to 3.
   */
  public follow(
    target: EntityTarget,
    distance = 3,
    options: FollowEntityTaskOptions = {},
  ): ReturnType<SoulFireTasks["followEntity"]> {
    return this.tasks.followEntity(target, distance, options);
  }

  /**
   * Same as `tasks.runFollowEntity`: `distance` defaults to 3.
   */
  public runFollow(
    target: EntityTarget,
    distance = 3,
    options: FollowEntityTaskOptions = {},
  ): ReturnType<SoulFireTasks["runFollowEntity"]> {
    return this.tasks.runFollowEntity(target, distance, options);
  }
}

function blockTarget(position: BlockTarget) {
  return create(BlockPositionSchema, position);
}

function worldTarget(position: WorldTarget) {
  return create(WorldPositionSchema, position);
}

function entityTarget(target: EntityTarget): {
  entityId: number;
  connectionEpoch?: string;
} {
  return typeof target === "number"
    ? { entityId: target }
    : {
        entityId: target.networkId,
        ...(target.connectionEpoch.length === 0 ? {} : { connectionEpoch: target.connectionEpoch }),
      };
}

/**
 * Builders for `PathfindGoal`. A goal's `dimension` defaults to the bot's
 * current one; another dimension is refused.
 */
export interface PathGoals {
  /**
   * Within `radius` blocks (euclidean) of the block. `radius` defaults to 0:
   * stand on it.
   */
  block(position: BlockTarget, radius?: number): PathfindGoal;
  /**
   * Within `radius` blocks of a world position; 0 means that exact block.
   */
  near(position: WorldTarget, radius: number): PathfindGoal;
  /**
   * Within `radius` blocks of an entity, updated as it moves.
   */
  entity(target: EntityTarget, radius: number): PathfindGoal;
  /**
   * An X/Z column at any height. `radius` defaults to 0: that exact column.
   */
  xz(x: number, z: number, options?: { dimension?: string; radius?: number }): PathfindGoal;
  /**
   * A Y level, anywhere in X and Z.
   */
  y(y: number, dimension?: string): PathfindGoal;
  /**
   * Reaches the block and breaks it.
   */
  breakBlock(position: BlockTarget): PathfindGoal;
  /**
   * Reaches `position` and places the selected block there.
   */
  placeBlock(position: BlockTarget): PathfindGoal;
  /**
   * At least `radius` blocks away from a position.
   */
  awayFromPosition(position: WorldTarget, radius: number): PathfindGoal;
  /**
   * At least `radius` blocks away from an entity.
   */
  awayFromEntity(target: EntityTarget, radius: number): PathfindGoal;
  /**
   * Reached when any of `nested` is. At least one goal; nested at most 16
   * levels deep.
   */
  any(nested: readonly PathfindGoal[]): PathfindGoal;
}

export const goals: PathGoals = {
  block(position: BlockTarget, radius = 0): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "block",
        value: { position: blockTarget(position), radius },
      },
    });
  },

  near(position: WorldTarget, radius: number): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "near",
        value: { position: worldTarget(position), radius },
      },
    });
  },

  entity(target: EntityTarget, radius: number): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "entity",
        value: { ...entityTarget(target), radius },
      },
    });
  },

  xz(x: number, z: number, options: { dimension?: string; radius?: number } = {}): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "xz",
        value: {
          x,
          z,
          dimension: options.dimension ?? "",
          radius: options.radius ?? 0,
        },
      },
    });
  },

  y(y: number, dimension = ""): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: { case: "y", value: { y, dimension } },
    });
  },

  breakBlock(position: BlockTarget): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "breakBlock",
        value: { position: blockTarget(position) },
      },
    });
  },

  placeBlock(position: BlockTarget): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "placeBlock",
        value: { position: blockTarget(position) },
      },
    });
  },

  awayFromPosition(position: WorldTarget, radius: number): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "awayFromPosition",
        value: { position: worldTarget(position), radius },
      },
    });
  },

  awayFromEntity(target: EntityTarget, radius: number): PathfindGoal {
    return create(PathfindGoalSchema, {
      goal: {
        case: "awayFromEntity",
        value: { ...entityTarget(target), radius },
      },
    });
  },

  any(nested: readonly PathfindGoal[]): PathfindGoal {
    if (nested.length === 0) {
      throw new RangeError("A composite path goal needs at least one goal");
    }
    return create(PathfindGoalSchema, {
      goal: { case: "any", value: { goals: [...nested] } },
    });
  },
};
