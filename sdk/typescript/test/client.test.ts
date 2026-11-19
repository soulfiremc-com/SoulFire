import { create } from "@bufbuild/protobuf";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it, vi } from "vitest";

import { SoulFire, SoulFireBot, SoulFireInstance } from "../src/client.js";
import {
  BlockFace,
  BotActionStatus,
  BotLiveService,
  Hand,
  ResourcePackResponse,
  type AttackEntityRequest,
  type InteractBlockRequest,
  type InteractEntityRequest,
  type MountEntityRequest,
  type RespondResourcePackRequest,
  type SendChatRequest,
  type SetCreativeSlotRequest,
  type SetFlyingRequest,
  type SleepRequest,
  type StartElytraFlightRequest,
  type UpdateSignRequest,
  type WaitForChunksRequest,
  type WakeRequest,
  type WatchBotEventsRequest,
  type WriteBookRequest,
} from "../src/generated/soulfire/bot_live_pb.js";
import {
  BotDesiredState,
  BotLiveStateSchema,
  BotRuntimeState,
  BotService,
  type BotContainerButtonClickRequest,
  type RestartBotsRequest,
  type SetBotsDesiredStateRequest,
} from "../src/generated/soulfire/bot_pb.js";
import {
  InstanceLiveService,
  type WatchInstanceEventsRequest,
} from "../src/generated/soulfire/instance_live_pb.js";
import { InstanceService } from "../src/generated/soulfire/instance_pb.js";

describe("SoulFireBot", () => {
  it("waits for a usable player snapshot instead of runtime startup", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = createRouterTransport(({ service }) => {
            service(BotService, {
              getBotInfo() {
                return {
                  status: {
                    profileId: "bot-id",
                    desiredState: BotDesiredState.RUNNING,
                    runtimeState: BotRuntimeState.RUNNING,
                  },
                };
              },
            });
            service(BotLiveService, {
              async *watchBotEvents() {
                yield {
                  event: {
                    case: "status",
                    value: {
                      profileId: "bot-id",
                      desiredState: BotDesiredState.RUNNING,
                      runtimeState: BotRuntimeState.RUNNING,
                    },
                  },
                };
                yield {
                  event: {
                    case: "snapshot",
                    value: create(BotLiveStateSchema),
                  },
                };
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          const status = yield* bot.waitForOnline();
          expect(status.runtimeState).toBe(BotRuntimeState.RUNNING);
        }),
      ),
    ));

  it("scopes event streams to the selected instance and bot", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let received: WatchBotEventsRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              async *watchBotEvents(request) {
                received = request;
                yield {};
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* Stream.runHead(bot.events());
          expect(received).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            filter: {
              includeChat: true,
              includeDamage: true,
              includeInventory: true,
              includeLifecycle: true,
              includeStateDeltas: true,
              includeTitles: true,
            },
          });
        }),
      ),
    ));

  it("scopes commands to the selected instance and bot", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let received: SendChatRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              sendChat(request) {
                received = request;
                return {
                  result: {
                    actionId: "action-id",
                    status: BotActionStatus.COMPLETED,
                  },
                };
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* bot.sendChat("hello");
          expect(received).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            message: "hello",
          });
        }),
      ),
    ));

  it("scopes block interaction, sleep, and wake actions", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let interaction: InteractBlockRequest | undefined;
          let sleep: SleepRequest | undefined;
          let wake: WakeRequest | undefined;
          const completed = {
            result: {
              actionId: "action-id",
              status: BotActionStatus.COMPLETED,
            },
          };
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              interactBlock(request) {
                interaction = request;
                return completed;
              },
              sleep(request) {
                sleep = request;
                return completed;
              },
              wake(request) {
                wake = request;
                return completed;
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* bot.interactBlock({
            position: { x: 1, y: 64, z: 2 },
            face: BlockFace.NORTH,
            hand: Hand.OFF,
            sneaking: true,
          });
          yield* bot.sleep({
            bed: { x: 3, y: 64, z: 4 },
            hand: Hand.MAIN,
          });
          yield* bot.wake();
          expect(interaction).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            position: { x: 1, y: 64, z: 2 },
            face: BlockFace.NORTH,
            hand: Hand.OFF,
            sneaking: true,
          });
          expect(sleep).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            bed: { x: 3, y: 64, z: 4 },
            hand: Hand.MAIN,
          });
          expect(wake).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
          });
        }),
      ),
    ));

  it("preserves connection epochs for direct entity actions", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let attack: AttackEntityRequest | undefined;
          let interaction: InteractEntityRequest | undefined;
          let mount: MountEntityRequest | undefined;
          const completed = {
            result: {
              actionId: "action-id",
              status: BotActionStatus.COMPLETED,
            },
          };
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              attackEntity(request) {
                attack = request;
                return completed;
              },
              interactEntity(request) {
                interaction = request;
                return completed;
              },
              mountEntity(request) {
                mount = request;
                return completed;
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* bot.attackEntity({
            entityId: 42,
            connectionEpoch: "00000000-0000-0000-0000-000000000042",
            sprinting: true,
          });
          yield* bot.interactEntity({
            entityId: 43,
            connectionEpoch: "00000000-0000-0000-0000-000000000043",
            hand: Hand.OFF,
            sneaking: true,
          });
          yield* bot.mount({
            entityId: 44,
            connectionEpoch: "00000000-0000-0000-0000-000000000044",
            hand: Hand.MAIN,
          });
          expect(attack).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            entityId: 42,
            connectionEpoch: "00000000-0000-0000-0000-000000000042",
            sprinting: true,
          });
          expect(interaction).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            entityId: 43,
            connectionEpoch: "00000000-0000-0000-0000-000000000043",
            hand: Hand.OFF,
            sneaking: true,
          });
          expect(mount).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            entityId: 44,
            connectionEpoch: "00000000-0000-0000-0000-000000000044",
            hand: Hand.MAIN,
          });
        }),
      ),
    ));

  it("attaches and clears an acquired control lease", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const actionTokens: (string | null)[] = [];
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              acquireBotControl() {
                return {
                  lease: {
                    token: "lease-token",
                  },
                };
              },
              releaseBotControl() {
                return {};
              },
              sendChat(_request, context) {
                actionTokens.push(context.requestHeader.get("X-SoulFire-Control-Token"));
                return {
                  result: {
                    actionId: "action-id",
                    status: BotActionStatus.COMPLETED,
                  },
                };
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          const lease = yield* bot.acquireControl();
          yield* bot.sendChat("leased");
          yield* lease.release();
          yield* bot.sendChat("unleased");
          expect(actionTokens).toEqual(["lease-token", null]);
        }),
      ),
    ));

  it("clicks container buttons with and without the control lease", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const clicks: BotContainerButtonClickRequest[] = [];
          const tokens: (string | null)[] = [];
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              acquireBotControl() {
                return { lease: { token: "lease-token" } };
              },
            });
            service(BotService, {
              clickContainerButton(request, context) {
                clicks.push(request);
                tokens.push(context.requestHeader.get("X-SoulFire-Control-Token"));
                return request.buttonId < 3
                  ? { success: true }
                  : {
                      success: false,
                      error: "Invalid button ID for this container type",
                    };
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* bot.clickContainerButton(1);
          yield* bot.acquireControl();
          yield* bot.clickContainerButton(2);
          const error = yield* Effect.flip(bot.clickContainerButton(3));
          expect(clicks).toMatchObject([
            { instanceId: "instance-id", botId: "bot-id", buttonId: 1 },
            { instanceId: "instance-id", botId: "bot-id", buttonId: 2 },
            { instanceId: "instance-id", botId: "bot-id", buttonId: 3 },
          ]);
          expect(tokens).toEqual([null, "lease-token", "lease-token"]);
          expect(error).toMatchObject({
            operation: "SoulFireBot.clickContainerButton",
            message: "Invalid button ID for this container type",
          });
        }),
      ),
    ));

  it("scopes rich player actions and preserves optional input", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let sign: UpdateSignRequest | undefined;
          let book: WriteBookRequest | undefined;
          let resourcePack: RespondResourcePackRequest | undefined;
          let flight: SetFlyingRequest | undefined;
          let elytra: StartElytraFlightRequest | undefined;
          let creativeSlot: SetCreativeSlotRequest | undefined;
          let chunkWait: WaitForChunksRequest | undefined;
          const completed = {
            result: {
              actionId: "action-id",
              status: BotActionStatus.COMPLETED,
            },
          };
          const transport = createRouterTransport(({ service }) => {
            service(BotLiveService, {
              updateSign(request) {
                sign = request;
                return completed;
              },
              writeBook(request) {
                book = request;
                return completed;
              },
              respondResourcePack(request) {
                resourcePack = request;
                return completed;
              },
              setFlying(request) {
                flight = request;
                return completed;
              },
              startElytraFlight(request) {
                elytra = request;
                return completed;
              },
              setCreativeSlot(request) {
                creativeSlot = request;
                return completed;
              },
              waitForChunks(request) {
                chunkWait = request;
                return {
                  centerChunkX: 2,
                  centerChunkZ: -3,
                  loadedChunks: 25,
                  requiredChunks: 25,
                  dimension: "minecraft:overworld",
                };
              },
            });
          });
          const bot = new SoulFireBot(
            "instance-id",
            "bot-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
          );
          yield* bot.updateSign({
            position: { dimension: "minecraft:overworld", x: 1, y: 64, z: 2 },
            frontText: true,
            lines: ["one", "two", "three", "four"],
          });
          yield* bot.writeBook({
            inventorySlot: 2,
            pages: ["first", "second"],
            title: "Field notes",
          });
          yield* bot.respondResourcePack({
            packId: "00000000-0000-0000-0000-000000000042",
            response: ResourcePackResponse.ACCEPTED,
          });
          yield* bot.setFlying({ flying: true });
          yield* bot.startElytraFlight();
          yield* bot.setCreativeSlot({
            slot: 36,
            item: { itemId: "minecraft:stone", count: 64 },
          });
          const chunks = yield* bot.waitForChunks({
            radiusChunks: 2,
            timeoutMs: 12_000,
          });
          expect(sign).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            position: { dimension: "minecraft:overworld", x: 1, y: 64, z: 2 },
            lines: ["one", "two", "three", "four"],
          });
          expect(book).toMatchObject({
            inventorySlot: 2,
            pages: ["first", "second"],
            title: "Field notes",
          });
          expect(resourcePack).toMatchObject({
            packId: "00000000-0000-0000-0000-000000000042",
            response: ResourcePackResponse.ACCEPTED,
          });
          expect(flight).toMatchObject({ flying: true });
          expect(elytra).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
          });
          expect(creativeSlot).toMatchObject({
            slot: 36,
            item: { itemId: "minecraft:stone", count: 64 },
          });
          expect(chunkWait).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            radiusChunks: 2,
            timeoutMs: 12_000,
          });
          expect(chunks.loadedChunks).toBe(25);
        }),
      ),
    ));
});

describe("SoulFireInstance", () => {
  it("scopes the multiplexed event stream and applies stateful defaults", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let received: WatchInstanceEventsRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(InstanceLiveService, {
              async *watchInstanceEvents(request) {
                received = request;
                yield {};
              },
            });
          });
          const instance = (yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          })).instance("instance-id");
          yield* Stream.runHead(instance.events());
          expect(received).toMatchObject({
            instanceId: "instance-id",
            filter: {
              botEvents: {
                includeBlockUpdates: true,
                includeBossBars: true,
                includeChat: true,
                includeEntityEvents: true,
                includeEnvironment: true,
                includeInventory: true,
                includeLifecycle: true,
                includePlayerList: true,
                includeResourcePacks: true,
                includeScoreboard: true,
                includeStateDeltas: true,
                includeTitles: true,
              },
            },
          });
        }),
      ),
    ));

  it("uses shuffle-accounts when selecting a count of stopped bots", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let received: SetBotsDesiredStateRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotService, {
              getBotList() {
                return {
                  bots: ["first", "second", "third"].map((profileId) => ({
                    profileId,
                    status: {
                      profileId,
                      desiredState: BotDesiredState.STOPPED,
                      runtimeState: BotRuntimeState.STOPPED,
                    },
                  })),
                };
              },
              setBotsDesiredState(request) {
                received = request;
                return { bots: [] };
              },
            });
            service(InstanceService, {
              getInstanceInfo() {
                return {
                  result: {
                    case: "info",
                    value: {
                      config: {
                        settings: [
                          {
                            namespace: "account",
                            entries: [
                              {
                                key: "shuffle-accounts",
                                value: {
                                  kind: { case: "boolValue", value: true },
                                },
                              },
                            ],
                          },
                        ],
                      },
                    },
                  },
                };
              },
            });
          });
          const random = vi.spyOn(Math, "random").mockReturnValue(0);
          const instance = new SoulFireInstance(
            "instance-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(InstanceService, transport),
          );
          try {
            yield* instance.start({ count: 1 });
          } finally {
            random.mockRestore();
          }
          expect(received).toMatchObject({
            instanceId: "instance-id",
            botIds: ["second"],
            desiredState: BotDesiredState.RUNNING,
          });
        }),
      ),
    ));

  it("restarts only bots that are already desired when no selection is given", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let received: RestartBotsRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotService, {
              getBotList() {
                return {
                  bots: [
                    {
                      profileId: "desired",
                      status: {
                        profileId: "desired",
                        desiredState: BotDesiredState.RUNNING,
                        runtimeState: BotRuntimeState.RUNNING,
                      },
                    },
                    {
                      profileId: "stopped",
                      status: {
                        profileId: "stopped",
                        desiredState: BotDesiredState.STOPPED,
                        runtimeState: BotRuntimeState.STOPPED,
                      },
                    },
                  ],
                };
              },
              restartBots(request) {
                received = request;
                return { bots: [] };
              },
            });
          });
          const instance = new SoulFireInstance(
            "instance-id",
            createClient(BotService, transport),
            createClient(BotLiveService, transport),
            createClient(InstanceService, transport),
          );
          yield* instance.restart();
          expect(received).toMatchObject({
            instanceId: "instance-id",
            botIds: ["desired"],
          });
        }),
      ),
    ));
});
