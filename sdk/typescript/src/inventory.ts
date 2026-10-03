import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect, type Scope } from "effect";
import { operationError, type SoulFireOperationError } from "./errors.js";
import { rpc, withSignal } from "./transport.js";
import { itemSelector } from "./selectors.js";

import { type BlockPositionSchema } from "./generated/soulfire/common_pb.js";
import {
  InventoryArea,
  InventoryRecommendationKind,
  InventoryService,
  type ContainerSnapshot,
  type CountItemsRequestSchema,
  type EquipItemRequestSchema,
  type FindInventorySlotsRequestSchema,
  type FindInventorySlotsResponse,
  type InventoryItemRecommendation,
  type InventoryMutationResponse,
  type MoveInventoryItemRequestSchema,
  type RankInventoryItemsRequestSchema,
  type RankInventoryItemsResponse,
  type SelectHotbarItemRequestSchema,
  type TossItemsRequestSchema,
  type TransferItemsRequestSchema,
  type UnequipItemRequestSchema,
} from "./generated/soulfire/inventory_pb.js";

type InventoryRequest<T extends DescMessage> = Omit<
  MessageInitShape<T>,
  "$typeName" | "scope"
>;

export interface ContainerMutationOptions {
  call?: CallOptions;
  idempotencyKey?: string;
}

export type InventoryRankOptions = Omit<
  InventoryRequest<typeof RankInventoryItemsRequestSchema>,
  "kind"
>;

export type InventoryRankingOptions = Omit<
  InventoryRankOptions,
  "equipmentSlot" | "targetBlock"
>;

/**
 * A block container opened with `inventory.open`. `await using` closes it.
 */
export class SoulFireContainer {
  #closed = false;

  public constructor(
    private readonly scope: { instanceId: string; botId: string },
    private readonly client: Client<typeof InventoryService>,
    private readonly actionOptions: (
      options?: CallOptions,
    ) => CallOptions | undefined,
    private current: ContainerSnapshot,
  ) {}

  public get snapshot(): Readonly<ContainerSnapshot> {
    return this.current;
  }

  public get closed(): boolean {
    return this.#closed;
  }

  /**
   * Throws `SoulFireContainerClosedError` if the container was closed in the
   * meantime.
   */

  public refresh(
    options?: CallOptions,
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      yield* Effect.try({
        try: () => this.requireOpen(),
        catch: (cause) => operationError("SoulFireContainer.refresh", cause),
      });
      const response = yield* rpc("SoulFireContainer.refresh", (signal) =>
        this.client.getContainerSnapshot(
          { scope: this.scope },
          withSignal(options, signal),
        ),
      );
      if (
        response.container === undefined ||
        response.container.containerId !== this.current.containerId ||
        response.container.menuId !== this.current.menuId
      ) {
        this.#closed = true;
        return yield* Effect.fail(
          operationError(
            "SoulFireContainer.refresh",
            new SoulFireContainerClosedError(this.current.containerId),
          ),
        );
      }
      this.current = response.container;
      return this.current;
    });
  }

  /**
   * Moves `count` items matching `selector` from the player's inventory into
   * the container. Fails if fewer are available, they don't fit, or another
   * menu replaced the container.
   */
  public deposit(
    selector: InventoryRequest<typeof TransferItemsRequestSchema>["selector"],
    count: number,
    options: ContainerMutationOptions = {},
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return this.transfer(
      selector,
      count,
      InventoryArea.PLAYER,
      InventoryArea.CONTAINER,
      options,
    );
  }

  /**
   * Moves `count` items matching `selector` from the container into the
   * player's inventory. Fails if fewer are available, they don't fit, or
   * another menu replaced the container.
   */
  public withdraw(
    selector: InventoryRequest<typeof TransferItemsRequestSchema>["selector"],
    count: number,
    options: ContainerMutationOptions = {},
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return this.transfer(
      selector,
      count,
      InventoryArea.CONTAINER,
      InventoryArea.PLAYER,
      options,
    );
  }

  public close(
    options: ContainerMutationOptions = {},
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      if (this.#closed) {
        return this.current;
      }
      const response = yield* rpc("SoulFireContainer.close", (signal) =>
        this.client.closeSemanticContainer(
          {
            scope: this.scope,
            containerId: this.current.containerId,
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      this.#closed = true;
      this.current = yield* Effect.try({
        try: () => requireContainer(response),
        catch: (cause) => operationError("SoulFireContainer.close", cause),
      });
      return this.current;
    });
  }

  private transfer(
    selector: InventoryRequest<typeof TransferItemsRequestSchema>["selector"],
    count: number,
    from: InventoryArea,
    to: InventoryArea,
    options: ContainerMutationOptions,
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      yield* Effect.try({
        try: () => this.requireOpen(),
        catch: (cause) => operationError("SoulFireContainer.transfer", cause),
      });
      const response = yield* rpc("SoulFireContainer.transfer", (signal) =>
        this.client.transferItems(
          {
            scope: this.scope,
            selector,
            count,
            from,
            to,
            // Servers that predate menu ids (0) check the revision instead.
            ...(this.current.menuId === 0n
              ? { expectedRevision: this.current.revision }
              : { menuId: this.current.menuId }),
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      this.current = yield* Effect.try({
        try: () => requireContainer(response),
        catch: (cause) => operationError("SoulFireContainer.transfer", cause),
      });
      return this.current;
    });
  }

  private requireOpen(): void {
    if (this.#closed) {
      throw new SoulFireContainerClosedError(this.current.containerId);
    }
  }
}

export class SoulFireContainerClosedError extends Error {
  public constructor(public readonly containerId: number) {
    super(`Container ${containerId} is already closed`);
    this.name = "SoulFireContainerClosedError";
  }
}

/**
 * The inventory and the menu the bot has open. `move`, `transfer`, `toss`,
 * `selectHotbar`, `equip` and `unequip` take an optional `expectedRevision`:
 * when not 0, they fail with ABORTED if the menu changed since that revision.
 */
export class SoulFireInventory {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof InventoryService>,
    private readonly actionOptions: (
      options?: CallOptions,
    ) => CallOptions | undefined,
  ) {}

  /**
   * The open menu (the player's inventory when no container is open), with
   * every slot.
   */
  /**
   * The open menu (the player's inventory when no container is open), with
   * every slot.
   */
  public snapshot(
    options?: CallOptions,
  ): Effect.Effect<ContainerSnapshot, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireInventory.snapshot", (signal) =>
        this.client.getContainerSnapshot(
          { scope: this.scope() },
          withSignal(options, signal),
        ),
      );
      if (response.container === undefined) {
        return yield* Effect.fail(
          operationError(
            "SoulFireInventory.snapshot",
            new Error("SoulFire did not return a container snapshot"),
          ),
        );
      }
      return response.container;
    });
  }

  /**
   * Items matching `selector` in `areas`. No `areas` means container, main,
   * hotbar, armor, offhand and crafting slots.
   */
  public count(
    request: string | InventoryRequest<typeof CountItemsRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<bigint, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const normalized = yield* Effect.try({
        try: () => typeof request === "string" ? { selector: itemSelector(request) } : request,
        catch: (cause) => operationError("SoulFireInventory.count", cause),
      });
      const response = yield* rpc("SoulFireInventory.count", (signal) =>
        this.client.countItems(
          { ...normalized, scope: this.scope() },
          withSignal(options, signal),
        ),
      );
      return response.count;
    });
  }

  /**
   * Slots holding items that match `selector`, in `areas` (the same default as
   * `count`).
   */
  public find(
    request: InventoryRequest<typeof FindInventorySlotsRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<FindInventorySlotsResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.find", (signal) =>
      this.client.findInventorySlots(
        { ...request, scope: this.scope() },
        withSignal(options, signal),
      ),
    );
  }

  /**
   * Candidates for `kind`, best first, with the factors behind each score.
   * `limit` defaults to 10 (at most 100); `areas` default to main, hotbar,
   * armor and offhand.
   */
  public rank(
    kind: InventoryRecommendationKind,
    options: InventoryRankOptions = {},
    call?: CallOptions,
  ): Effect.Effect<RankInventoryItemsResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.rank", (signal) =>
      this.client.rankInventoryItems(
        { ...options, kind, scope: this.scope() },
        withSignal(call, signal),
      ),
    );
  }

  /**
   * The best tool for breaking the block at `targetBlock`.
   */
  public bestTool(
    targetBlock: MessageInitShape<typeof BlockPositionSchema>,
    options: InventoryRankingOptions = {},
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return this.best(
      InventoryRecommendationKind.TOOL,
      { ...options, targetBlock },
      call,
    );
  }

  public bestWeapon(
    options: InventoryRankingOptions = {},
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return this.best(InventoryRecommendationKind.MELEE_WEAPON, options, call);
  }

  public bestArmor(
    equipmentSlot: "head" | "chest" | "legs" | "feet",
    options: InventoryRankingOptions = {},
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return this.best(
      InventoryRecommendationKind.ARMOR,
      { ...options, equipmentSlot },
      call,
    );
  }

  public bestFood(
    options: InventoryRankingOptions = {},
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return this.best(InventoryRecommendationKind.FOOD, options, call);
  }

  public bestScaffold(
    options: InventoryRankingOptions = {},
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return this.best(InventoryRecommendationKind.SCAFFOLD, options, call);
  }

  /**
   * Moves items from `sourceSlot` to `destinationSlot` of the open menu;
   * `count` defaults to the whole stack.
   */
  public move(
    request: InventoryRequest<typeof MoveInventoryItemRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.move", (signal) =>
      this.client.moveInventoryItem(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Moves `count` items matching `selector` from area `from` to area `to`
   * (`PLAYER` is main and hotbar). Fails if fewer are available or they don't
   * fit, or if `menuId` is set and that menu isn't open.
   */
  public transfer(
    request: InventoryRequest<typeof TransferItemsRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.transfer", (signal) =>
      this.client.transferItems(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Drops `count` items matching `selector` on the ground.
   */
  public toss(
    request: InventoryRequest<typeof TossItemsRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.toss", (signal) =>
      this.client.tossItems(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Selects `hotbarSlot` (0-8), or the item matching `selector`: a hotbar slot
   * that holds one, or else a matching item swapped into the selected slot.
   */
  public selectHotbar(
    request: InventoryRequest<typeof SelectHotbarItemRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.selectHotbar", (signal) =>
      this.client.selectHotbarItem(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Puts an item matching `selector` in `equipmentSlot`: mainhand, offhand,
   * head, chest, legs or feet. Closes an open container first, except for
   * mainhand (which selects it as `selectHotbar` does).
   */
  public equip(
    request: InventoryRequest<typeof EquipItemRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.equip", (signal) =>
      this.client.equipItem(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Moves the item in `equipmentSlot` (mainhand, offhand, head, chest, legs or
   * feet) to `destinationArea`: MAIN, HOTBAR or PLAYER. It defaults to PLAYER,
   * the first free main or hotbar slot.
   */
  public unequip(
    request: InventoryRequest<typeof UnequipItemRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<InventoryMutationResponse, SoulFireOperationError> {
    return rpc("SoulFireInventory.unequip", (signal) =>
      this.client.unequipItem(
        { ...request, scope: this.scope() },
        withSignal(this.actionOptions(options), signal),
      ),
    );
  }

  /**
   * Opens the container block at `position`. Times out after 10 s.
   */
  public open(
    position: MessageInitShape<typeof BlockPositionSchema>,
    options: ContainerMutationOptions = {},
  ): Effect.Effect<SoulFireContainer, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const scope = this.scope();
      const response = yield* rpc("SoulFireInventory.open", (signal) =>
        this.client.openBlockContainer(
          {
            scope,
            position,
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      return new SoulFireContainer(
        scope,
        this.client,
        this.actionOptions,
        yield* Effect.try({
          try: () => requireContainer(response),
          catch: (cause) => operationError("SoulFireInventory.open", cause),
        }),
      );
    });
  }

  private best(
    kind: InventoryRecommendationKind,
    options: Omit<
      InventoryRequest<typeof RankInventoryItemsRequestSchema>,
      "kind"
    >,
    call?: CallOptions,
  ): Effect.Effect<
    InventoryItemRecommendation | undefined,
    SoulFireOperationError
  > {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireInventory.best", (signal) =>
        this.client.rankInventoryItems(
          {
            ...options,
            kind,
            limit: 1,
            scope: this.scope(),
          },
          withSignal(call, signal),
        ),
      );
      return response.recommendations[0];
    });
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
  public openScoped(
    ...args: Parameters<SoulFireInventory["open"]>
  ): Effect.Effect<SoulFireContainer, SoulFireOperationError, Scope.Scope> {
    return Effect.acquireRelease(this.open(...args), (container) =>
      container.close().pipe(Effect.orDie),
    );
  }
}

function requireContainer(
  response: InventoryMutationResponse,
): ContainerSnapshot {
  if (response.container === undefined) {
    throw new Error("SoulFire did not return a container snapshot");
  }
  return response.container;
}
