import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { type SoulFireOperationError } from "./errors.js";
import { rpc, rpcStream, withSignal } from "./transport.js";

import {
  BotProtocolService,
  type BotProtocolInfo,
  type PacketDirection,
  type PacketSchema,
  type RawPacketEvent,
  type WatchPacketsRequestSchema,
} from "./generated/soulfire/protocol_pb.js";

type BotScoped<T extends DescMessage> = Omit<
  MessageInitShape<T>,
  "$typeName" | "botId" | "instanceId"
>;

export type WatchPacketsOptions = BotScoped<typeof WatchPacketsRequestSchema> & {
  call?: CallOptions;
};

export interface SendRawPacketOptions {
  call?: CallOptions;
  expectedName?: string;
}

/**
 * Advanced access to Soulfire's native Minecraft packet codec.
 *
 * Encoded bytes use the native protocol reported by {@link info}, not the
 * remote server protocol. Soulfire applies ViaVersion translation afterward.
 */
export class SoulFireProtocol {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof BotProtocolService>,
  ) {}

  public info(options?: CallOptions): Effect.Effect<BotProtocolInfo, SoulFireOperationError> {
    return rpc("SoulFireProtocol.info", (signal) =>
      this.client.getProtocolInfo(this.scope(), withSignal(options, signal)),
    );
  }

  public schemas(
    direction: PacketDirection,
    options?: CallOptions,
  ): Effect.Effect<readonly PacketSchema[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireProtocol.schemas", (signal) =>
        this.client.listPacketSchemas({ ...this.scope(), direction }, withSignal(options, signal)),
      );
      return response.packets;
    });
  }

  public packets(
    options: WatchPacketsOptions = {},
  ): Stream.Stream<RawPacketEvent, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        const { call, ...request } = options;
        return rpcStream("SoulFireProtocol.packets", (signal) =>
          this.client.watchPackets({ ...request, ...this.scope() }, withSignal(call, signal)),
        );
      }),
    );
  }

  public send(
    encodedPacket: Uint8Array,
    options: SendRawPacketOptions = {},
  ): Effect.Effect<
    {
      name: string;
      encodedBytes: number;
    },
    SoulFireOperationError
  > {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireProtocol.send", (signal) =>
        this.client.sendRawPacket(
          {
            ...this.scope(),
            encodedPacket,
            ...(options.expectedName === undefined ? {} : { expectedName: options.expectedName }),
          },
          withSignal(options.call, signal),
        ),
      );
      return {
        name: response.name,
        encodedBytes: response.encodedBytes,
      };
    });
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
}
