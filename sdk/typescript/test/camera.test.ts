import { createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import {
  BotService,
  type BotWatchPovRequest,
  type BotWorldMapRequest,
} from "../src/generated/soulfire/bot_pb.js";
import { SoulFire } from "../src/index.js";

describe("SoulFireCamera", () => {
  it("captures complete camera options, streams frames, and samples maps", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let watchRequest: BotWatchPovRequest | undefined;
          let mapRequest: BotWorldMapRequest | undefined;
          const transport = createRouterTransport(({ service }) => {
            service(BotService, {
              renderBotPov(request) {
                return {
                  imageBase64: "UE5H",
                  imageMimeType: "image/png",
                  metadata: {
                    width: request.width,
                    height: request.height,
                    fov: request.fov ?? 70,
                    cameraX: request.cameraX ?? 0,
                    includedHud: request.includeHud ?? true,
                    includedHands: request.includeHands ?? true,
                  },
                };
              },
              async *watchBotPov(request) {
                watchRequest = request;
                yield {
                  sequence: 1n,
                  droppedBefore: 2n,
                  render: {
                    imageBase64: "UE5H",
                    imageMimeType: "image/png",
                  },
                };
              },
              getBotWorldMap(request) {
                mapRequest = request;
                return {
                  dimension: "minecraft:overworld",
                  centerX: request.centerX ?? 0,
                  centerZ: request.centerZ ?? 0,
                  radius: request.radius,
                  sampleStep: request.sampleStep,
                  minY: -64,
                  maxY: 320,
                  worldRevision: 42n,
                  columns: [{ x: 4, z: 8, loaded: true, surfaceY: 70 }],
                };
              },
            });
          });
          const soulfire = yield* SoulFire.unauthenticated({
            baseUrl: "https://soulfire.example.com",
            transport,
          });
          const camera = soulfire.instance("instance-id").bot("bot-id").camera;
          const capture = yield* camera.capture({
            width: 1280,
            height: 720,
            cameraX: 12.5,
            yRot: 90,
            includeHud: false,
            includeHands: false,
            includeDebugTrace: true,
          });
          const bytes = yield* camera.captureBytes({ width: 320, height: 180 });
          const collected = yield* Stream.runCollect(
            camera.frames({ intervalMs: 250, includeHud: false }),
          );
          const frames = Array.from(collected);
          const map = yield* camera.worldMap({
            centerX: 4,
            centerZ: 8,
            radius: 32,
            sampleStep: 2,
            includeEntities: true,
          });
          yield* soulfire.close();
          expect(capture.metadata).toMatchObject({
            width: 1280,
            height: 720,
            cameraX: 12.5,
            includedHud: false,
            includedHands: false,
          });
          expect(bytes).toEqual(Uint8Array.from([80, 78, 71]));
          expect(frames[0]).toMatchObject({ sequence: 1n, droppedBefore: 2n });
          expect(map.columns[0]).toMatchObject({ x: 4, z: 8, surfaceY: 70 });
          expect(watchRequest).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            intervalMs: 250,
            includeHud: false,
          });
          expect(mapRequest).toMatchObject({
            instanceId: "instance-id",
            botId: "bot-id",
            centerX: 4,
            centerZ: 8,
            radius: 32,
            sampleStep: 2,
            includeEntities: true,
          });
        }),
      ),
    ));
});
