import { createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import {
  BotProtocolService,
  PacketDirection,
  type ListPacketSchemasRequest,
  type SendRawPacketRequest,
  type WatchPacketsRequest,
} from "../src/generated/soulfire/protocol_pb.js";
import { SoulFire } from "../src/index.js";

describe("SoulFireProtocol", () => {
  it("scopes packet discovery, observation, and sends to the selected bot", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let schemasRequest: ListPacketSchemasRequest | undefined;
          let watchRequest: WatchPacketsRequest | undefined;
          let sendRequest: SendRawPacketRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotProtocolService, {
              getProtocolInfo(_request) {
                return {
                  minecraftProtocolVersion: 772,
                  minecraftVersionName: "26.2",
                  protocolState: "play",
                  packetObservationSupported: true,
                  rawPacketSendingEnabled: true,
                  maximumPacketBytes: 1024,
                  maximumSendsPerSecond: 20,
                };
              },
              listPacketSchemas(request) {
                schemasRequest = request;
                return {
                  packets: [
                    {
                      direction: request.direction,
                      name: "minecraft:game_event",
                      networkId: 31,
                      protocolState: "play",
                    },
                  ],
                };
              },
              async *watchPackets(request) {
                watchRequest = request;
                yield {
                  sequence: 1n,
                  direction: PacketDirection.CLIENTBOUND,
                  name: "minecraft:game_event",
                  networkId: 31,
                  protocolState: "play",
                };
              },
              sendRawPacket(request) {
                sendRequest = request;
                return {
                  name: request.expectedName ?? "",
                  encodedBytes: request.encodedPacket.byteLength,
                };
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const protocol = soulfire.instance("instance-id").bot("bot-id").protocol;
          const info = yield* protocol.info();
          const schemas = yield* protocol.schemas(PacketDirection.CLIENTBOUND);
          const collected = yield* Stream.runCollect(
            protocol.packets({
              directions: [PacketDirection.CLIENTBOUND],
              names: ["minecraft:game_event"],
              includeEncodedPacket: true,
              maximumEncodedBytes: 128,
            }),
          );
          const events = Array.from(collected);
          const sent = yield* protocol.send(new Uint8Array([1, 2]), {
            expectedName: "minecraft:game_event",
          });
          yield* soulfire.close();
          expect(info.minecraftProtocolVersion).toBe(772);
          expect(schemas[0]?.networkId).toBe(31);
          expect(events[0]?.sequence).toBe(1n);
          expect(sent).toEqual({
            name: "minecraft:game_event",
            encodedBytes: 2,
          });
          expect(schemasRequest).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            direction: PacketDirection.CLIENTBOUND,
          });
          expect(watchRequest).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            maximumEncodedBytes: 128,
          });
          expect(sendRequest).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            expectedName: "minecraft:game_event",
          });
        }),
      ),
    ));
});
