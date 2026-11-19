import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { type SoulFireOperationError } from "./errors.js";
import { rpc, rpcStream, withSignal } from "./transport.js";

import {
  type BotService,
  type BotPovFrame,
  type BotRenderPovRequestSchema,
  type BotRenderPovResponse,
  type BotWatchPovRequestSchema,
  type BotWorldMapRequestSchema,
  type BotWorldMapResponse,
} from "./generated/soulfire/bot_pb.js";

type BotScoped<T extends DescMessage> = Omit<
  MessageInitShape<T>,
  "$typeName" | "instanceId" | "botId"
>;

export type CameraRenderOptions = BotScoped<typeof BotRenderPovRequestSchema>;

export type CameraStreamOptions = BotScoped<typeof BotWatchPovRequestSchema> & {
  call?: CallOptions;
};

export type WorldMapOptions = BotScoped<typeof BotWorldMapRequestSchema>;

/**
 * Captures POV images and map-ready world snapshots for one bot.
 */
export class SoulFireCamera {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof BotService>,
  ) {}

  /**
   * Renders what the bot sees as a PNG (base64 in `imageBase64`). The size
   * defaults to 854x480, at most 1920x1080. The camera defaults to the bot's
   * eyes, facing where it looks; `cameraX/Y/Z`, `yRot` and `xRot` move it.
   */

  public capture(
    options: CameraRenderOptions = {},
    call?: CallOptions,
  ): Effect.Effect<BotRenderPovResponse, SoulFireOperationError> {
    return rpc("SoulFireCamera.capture", (signal) =>
      this.client.renderBotPov({ ...options, ...this.scope() }, withSignal(call, signal)),
    );
  }

  /**
   * The `capture` image, decoded.
   */
  public captureBytes(
    options: CameraRenderOptions = {},
    call?: CallOptions,
  ): Effect.Effect<Uint8Array, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      return decodeCameraImage(yield* this.capture(options, call));
    });
  }

  /**
   * Rendered frames, one per `intervalMs`, which defaults to 1000, from 100 to
   * 60000. A slow reader gets the newest frame and a count of the dropped ones.
   */
  public frames(
    options: CameraStreamOptions = {},
  ): Stream.Stream<BotPovFrame, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        const { call, ...request } = options;
        return rpcStream("SoulFireCamera.frames", (signal) =>
          this.client.watchBotPov({ ...request, ...this.scope() }, withSignal(call, signal)),
        );
      }),
    );
  }

  /**
   * Top-down terrain around `centerX` and `centerZ`, which default to the bot's
   * position. `radius` defaults to 64, from 1 to 256, and `sampleStep` to 1,
   * from 1 to 16. Entities too with `includeEntities`.
   */
  public worldMap(
    options: WorldMapOptions = {},
    call?: CallOptions,
  ): Effect.Effect<BotWorldMapResponse, SoulFireOperationError> {
    return rpc("SoulFireCamera.worldMap", (signal) =>
      this.client.getBotWorldMap({ ...options, ...this.scope() }, withSignal(call, signal)),
    );
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
}

/**
 * Decodes the image payload returned by a camera capture or stream frame.
 */
export function decodeCameraImage(image: Pick<BotRenderPovResponse, "imageBase64">): Uint8Array {
  const binary = globalThis.atob(image.imageBase64);
  return Uint8Array.from(binary, (character) => character.codePointAt(0) ?? 0);
}
