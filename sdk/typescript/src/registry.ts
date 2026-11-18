import type { DescMessage, MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import type { Effect } from "effect";
import { type SoulFireOperationError } from "./errors.js";
import { rpc, withSignal } from "./transport.js";

import {
  type RegistryService,
  type GetRegistryEntryRequestSchema,
  type GetRegistryEntryResponse,
  type GetRegistryIdentityResponse,
  type ListRegistryEntriesRequestSchema,
  type ListRegistryEntriesResponse,
  type ListRegistryTagsRequestSchema,
  type ListRegistryTagsResponse,
} from "./generated/soulfire/registry_pb.js";

type RegistryRequest<T extends DescMessage> = Omit<
  MessageInitShape<T>,
  "$typeName" | "botId" | "instanceId"
>;

export class SoulFireRegistry {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof RegistryService>,
  ) {}

  public identity(
    options?: CallOptions,
  ): Effect.Effect<GetRegistryIdentityResponse, SoulFireOperationError> {
    return rpc("SoulFireRegistry.identity", (signal) =>
      this.client.getRegistryIdentity(this.scope(), withSignal(options, signal)),
    );
  }

  /**
   * Entries of one registry, sorted by id, filtered by `idPrefix` and by `tags`
   * (an entry must have all of them). `pageSize` defaults to 100, at most 1000.
   */
  public entries(
    request: RegistryRequest<typeof ListRegistryEntriesRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<ListRegistryEntriesResponse, SoulFireOperationError> {
    return rpc("SoulFireRegistry.entries", (signal) =>
      this.client.listRegistryEntries({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  public entry(
    request: RegistryRequest<typeof GetRegistryEntryRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<GetRegistryEntryResponse, SoulFireOperationError> {
    return rpc("SoulFireRegistry.entry", (signal) =>
      this.client.getRegistryEntry({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  public tags(
    request: RegistryRequest<typeof ListRegistryTagsRequestSchema>,
    options?: CallOptions,
  ): Effect.Effect<ListRegistryTagsResponse, SoulFireOperationError> {
    return rpc("SoulFireRegistry.tags", (signal) =>
      this.client.listRegistryTags({ ...request, ...this.scope() }, withSignal(options, signal)),
    );
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
}
