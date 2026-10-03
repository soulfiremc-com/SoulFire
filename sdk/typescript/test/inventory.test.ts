import { create } from "@bufbuild/protobuf";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect } from "effect";
import { describe, expect, it } from "vitest";

import {
  ContainerSnapshotSchema,
  GetContainerSnapshotResponseSchema,
  InventoryArea,
  InventoryItemRecommendationSchema,
  InventoryMutationResponseSchema,
  InventoryRecommendationKind,
  InventoryService,
  RankInventoryItemsResponseSchema,
  type CountItemsRequest,
  type RankInventoryItemsRequest,
  type TransferItemsRequest,
} from "../src/generated/soulfire/inventory_pb.js";
import {
  SoulFireContainerClosedError,
  SoulFireInventory,
} from "../src/inventory.js";

describe("SoulFireContainer", () => {
  it("chains revision-safe deposit and withdraw operations", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transfers: TransferItemsRequest[] = [];
          const transport = createRouterTransport(({ service }) => {
            service(InventoryService, {
              openBlockContainer() {
                return response(42, 10n);
              },
              transferItems(request) {
                transfers.push(request);
                return response(42, request.expectedRevision + 1n);
              },
              closeSemanticContainer() {
                return response(0, 13n);
              },
            });
          });
          const inventory = new SoulFireInventory(
            "instance-id",
            "bot-id",
            createClient(InventoryService, transport),
            (options) => options,
          );
          const container = yield* inventory.open({ x: 1, y: 64, z: 2 });
          yield* container.deposit({ itemIds: ["minecraft:cobblestone"] }, 32);
          yield* container.withdraw({ itemIds: ["minecraft:bread"] }, 4);
          yield* container.close();
          expect(transfers).toHaveLength(2);
          expect(transfers[0]).toMatchObject({
            expectedRevision: 10n,
            from: InventoryArea.PLAYER,
            to: InventoryArea.CONTAINER,
          });
          expect(transfers[1]).toMatchObject({
            expectedRevision: 11n,
            from: InventoryArea.CONTAINER,
            to: InventoryArea.PLAYER,
          });
          expect(container.closed).toBe(true);
        }),
      ),
    ));

  it("binds deposit and withdraw to the open menu, not its revision", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const transfers: TransferItemsRequest[] = [];
        const transport = createRouterTransport(({ service }) => {
          service(InventoryService, {
            openBlockContainer() {
              return response(42, 10n, 7n);
            },
            transferItems(request) {
              transfers.push(request);
              return response(42, 20n, 7n);
            },
          });
        });
        const inventory = new SoulFireInventory(
          "instance-id",
          "bot-id",
          createClient(InventoryService, transport),
          (options) => options,
        );
        const container = yield* inventory.open({ x: 1, y: 64, z: 2 });
        yield* container.deposit({ itemIds: ["minecraft:cobblestone"] }, 32);
        yield* container.withdraw({ itemIds: ["minecraft:bread"] }, 4);
        expect(transfers.map((t) => [t.menuId, t.expectedRevision])).toEqual([
          [7n, 0n],
          [7n, 0n],
        ]);
      }),
    ));

  it("treats another menu with the same container id as closed", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const transport = createRouterTransport(({ service }) => {
          service(InventoryService, {
            openBlockContainer() {
              return response(1, 10n, 7n);
            },
            getContainerSnapshot() {
              return create(GetContainerSnapshotResponseSchema, {
                container: create(ContainerSnapshotSchema, {
                  containerId: 1,
                  revision: 10n,
                  menuId: 8n,
                }),
              });
            },
          });
        });
        const inventory = new SoulFireInventory(
          "instance-id",
          "bot-id",
          createClient(InventoryService, transport),
          (options) => options,
        );
        const container = yield* inventory.open({ x: 1, y: 64, z: 2 });
        const error = yield* Effect.flip(container.refresh());
        expect(error.cause).toBeInstanceOf(SoulFireContainerClosedError);
        expect(container.closed).toBe(true);
      }),
    ));

  it("requests an explainable best tool for the exact target block", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let ranked: RankInventoryItemsRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(InventoryService, {
              rankInventoryItems(request) {
                ranked = request;
                return create(RankInventoryItemsResponseSchema, {
                  recommendations: [
                    create(InventoryItemRecommendationSchema, {
                      score: 2000,
                    }),
                  ],
                  revision: 12n,
                });
              },
            });
          });
          const inventory = new SoulFireInventory(
            "instance-id",
            "bot-id",
            createClient(InventoryService, transport),
            (options) => options,
          );
          const recommendation = yield* inventory.bestTool(
            { x: 1, y: 64, z: 2, dimension: "minecraft:overworld" },
            {
              preferHotbar: true,
              preferHighDurability: true,
              preferredEnchantmentIds: ["minecraft:fortune"],
              excludedEnchantmentIds: ["minecraft:vanishing_curse"],
            },
          );
          expect(recommendation?.score).toBe(2000);
          expect(ranked).toMatchObject({
            kind: InventoryRecommendationKind.TOOL,
            limit: 1,
            targetBlock: {
              x: 1,
              y: 64,
              z: 2,
              dimension: "minecraft:overworld",
            },
            preferHotbar: true,
            preferHighDurability: true,
            preferredEnchantmentIds: ["minecraft:fortune"],
            excludedEnchantmentIds: ["minecraft:vanishing_curse"],
            scope: {
              instanceId: "instance-id",
              botId: "bot-id",
            },
          });
        }),
      ),
    ));
});

function response(containerId: number, revision: bigint, menuId = 0n) {
  return create(InventoryMutationResponseSchema, {
    container: create(ContainerSnapshotSchema, {
      containerId,
      revision,
      menuId,
    }),
  });
}


it("normalizes item IDs and tags and rejects malformed selectors before sending", async () => {
  const requests: CountItemsRequest[] = [];
  const transport = createRouterTransport(({ service }) => {
    service(InventoryService, {
      countItems(request) {
        requests.push(request);
        return { count: 8n };
      },
    });
  });
  const inventory = new SoulFireInventory(
    "instance", "bot", createClient(InventoryService, transport), (options) => options,
  );
  await Effect.runPromise(Effect.gen(function* () {
    expect(yield* inventory.count("oak_log")).toBe(8n);
    expect(yield* inventory.count("#logs")).toBe(8n);
    const error = yield* inventory.count("#").pipe(Effect.flip);
    expect(error._tag).toBe("SoulFireValidationError");
  }));
  expect(requests).toHaveLength(2);
  expect(requests[0]?.selector?.itemIds).toEqual(["minecraft:oak_log"]);
  expect(requests[1]?.selector?.tags).toEqual(["minecraft:logs"]);
});
