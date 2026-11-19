import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect } from "effect";
import { describe, expect, it } from "vitest";
import { SoulFire, SoulFireActionError } from "../src/index.js";
import { BotActionStatus, BotLiveService } from "../src/generated/soulfire/bot_live_pb.js";
import { InventoryService } from "../src/generated/soulfire/inventory_pb.js";
import { SoulFireInventory } from "../src/inventory.js";

describe("workflow resources", () => {
  it("closes containers and control leases when their workflow fails", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let released = 0;
          let closed = 0;
          const containerResponse = {
            container: { containerId: 42, revision: 1n },
          };
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              acquireBotControl() {
                return { lease: { token: "control-token" } };
              },
              releaseBotControl() {
                released += 1;
                return {};
              },
            });
            service(InventoryService, {
              openBlockContainer() {
                return containerResponse;
              },
              closeSemanticContainer() {
                closed += 1;
                return containerResponse;
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const bot = soulfire.instance("instance").bot("bot");
          const inventory = new SoulFireInventory(
            "instance",
            "bot",
            createClient(InventoryService, transport),
            (options) => options,
          );
          const failure = new Error("workflow failed");
          const result = yield* Effect.flip(
            Effect.scoped(
              Effect.gen(function* () {
                yield* bot.acquireControlScoped();
                const container = yield* inventory.openScoped({
                  x: 0,
                  y: 64,
                  z: 0,
                });
                expect(container.closed).toBe(false);
                return yield* Effect.fail(failure);
              }),
            ),
          );
          expect(result).toBe(failure);
          expect(closed).toBe(1);
          expect(released).toBe(1);
          const lease = yield* bot.acquireControl();
          yield* lease.release();
          expect(released).toBe(2);
        }),
      ),
    ));

  it("puts rejected action results in the typed failure channel", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              wake() {
                return {
                  result: {
                    status: BotActionStatus.FAILED,
                    actionId: "wake",
                    error: "could not wake",
                  },
                };
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const failure = yield* Effect.flip(soulfire.instance("instance").bot("bot").wake());
          expect(failure).toBeInstanceOf(SoulFireActionError);
        }),
      ),
    ));
});
