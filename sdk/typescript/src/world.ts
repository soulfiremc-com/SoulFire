import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect } from "effect";
import { operationError, type SoulFireOperationError } from "./errors.js";
import { rpc, withSignal } from "./transport.js";

import type {
  BlockSnapshot,
  EntitySnapshot,
  PlayerSnapshot,
} from "./generated/soulfire/domain_pb.js";
import {
  WorldService,
  type CanSeeBlockRequestSchema,
  type CanSeeBlockResponse,
  type EstimateDigTimeRequestSchema,
  type EstimateDigTimeResponse,
  type EstimateExplosionDamageRequestSchema,
  type EstimateExplosionDamageResponse,
  type GetWorldBlockRequestSchema,
  type GetWorldBlockResponse,
  type GetWorldEntityRequestSchema,
  type GetWorldEntityResponse,
  type QueryBlocksRequestSchema,
  type QueryBlocksResponse,
  type QueryEntitiesRequestSchema,
  type QueryEntitiesResponse,
  type RaycastRequestSchema,
  type RaycastResponse,
} from "./generated/soulfire/world_pb.js";

type BotScoped<T extends DescMessage> = Omit<
  MessageInitShape<T>,
  "$typeName" | "botId" | "instanceId"
>;

type PlayerRaycastRequest = Omit<BotScoped<typeof RaycastRequestSchema>, "direction" | "origin">;

export class SoulFireWorld {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof WorldService>,
  ) {}

  public player(options?: CallOptions): Effect.Effect<PlayerSnapshot, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireWorld.player", (signal) =>
        this.client.getPlayerSnapshot(this.scope(), withSignal(options, signal)),
      );
      if (response.player === undefined) {
        return yield* Effect.fail(
          operationError(
            "SoulFireWorld.player",
            new Error("Soulfire did not return a player snapshot"),
          ),
        );
      }
      return response.player;
    });
  }

  public block(
    request: BotScoped<typeof GetWorldBlockRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<GetWorldBlockResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.block", (signal) =>
      this.client.getWorldBlock({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * Blocks matching `selector` in `region`, in loaded chunks only. `region`
   * defaults to a sphere of radius 16 around the bot; a sphere's radius is at
   * most 128, and a box of more than 4,194,304 blocks is refused. Sorted
   * nearest to the region's center first unless `sort` says otherwise.
   * `pageSize` defaults to 100, at most 500; pass `nextPageToken` back as
   * `pageToken` for the next page.
   */
  public queryBlocks(
    request: BotScoped<typeof QueryBlocksRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<QueryBlocksResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.queryBlocks", (signal) =>
      this.client.queryBlocks({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  public entity(
    request: BotScoped<typeof GetWorldEntityRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<GetWorldEntityResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.entity", (signal) =>
      this.client.getWorldEntity({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * Entities within `radius` of `origin`, the bot itself excluded. `origin`
   * defaults to the bot's position, and `radius` to 32, at most 256. Sorted and
   * paged like `queryBlocks`.
   */
  public queryEntities(
    request: BotScoped<typeof QueryEntitiesRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<QueryEntitiesResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.queryEntities", (signal) =>
      this.client.queryEntities({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * The first thing a ray hits: a block, or an entity (with `includeEntities`)
   * if one is closer. `origin` defaults to the bot's eyes, `direction` to where
   * it looks, and `maximumDistance` to 6, at most 256.
   */
  public raycast(
    request: BotScoped<typeof RaycastRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<RaycastResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.raycast", (signal) =>
      this.client.raycast({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * `raycast` from the bot's eyes, along where it looks.
   */
  public raycastFromPlayer(
    request: PlayerRaycastRequest = {},
    options?: CallOptions,
  ): Effect.Effect<RaycastResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.raycastFromPlayer", (signal) =>
      this.client.raycast({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * The block the bot looks at, or undefined if none is within
   * `maximumDistance`. `maximumDistance` defaults to 256, which is also the
   * most.
   */
  public blockAtCursor(
    maximumDistance = 256,
    options?: CallOptions,
  ): Effect.Effect<BlockSnapshot | undefined, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* this.raycastFromPlayer(
        { maximumDistance, includeEntities: false },
        options,
      );
      return response.block;
    });
  }

  /**
   * The entity the bot looks at, or undefined if none is within
   * `maximumDistance` or a block is in the way. `maximumDistance` defaults to
   * 3.5.
   */
  public entityAtCursor(
    maximumDistance = 3.5,
    options?: CallOptions,
  ): Effect.Effect<EntitySnapshot | undefined, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* this.raycastFromPlayer(
        { maximumDistance, includeEntities: true },
        options,
      );
      return response.entity;
    });
  }

  /**
   * Damage `target` would take from an explosion of `power` at `center`, after
   * its armor, resistance, enchantments and absorption. The damage radius is
   * twice `power`; `power` must be above 0 and at most 128. Target and center
   * must be observable in the bot's dimension.
   */
  public estimateExplosionDamage(
    request: BotScoped<typeof EstimateExplosionDamageRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<EstimateExplosionDamageResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.estimateExplosionDamage", (signal) =>
      this.client.estimateExplosionDamage(
        { ...request, ...this.scope() },
        withSignal(options, signal),
      ),
    );
  }

  /**
   * Whether a ray from the bot's eyes to the center of the block reaches it.
   */
  public canSeeBlock(
    request: BotScoped<typeof CanSeeBlockRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<CanSeeBlockResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.canSeeBlock", (signal) =>
      this.client.canSeeBlock({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  /**
   * How long the bot would take to break the block with what it holds now,
   * counting its effects, attributes and surroundings.
   */
  public estimateDigTime(
    request: BotScoped<typeof EstimateDigTimeRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<EstimateDigTimeResponse, SoulFireOperationError> {
    return rpc("SoulFireWorld.estimateDigTime", (signal) =>
      this.client.estimateDigTime({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
}
