import type {
  PluginApiDescriptor,
} from "./generated/soulfire/plugin_api_pb.js";
import type {
  SdkApiVersion,
  SdkHandshakeResponse,
  SdkIdentity,
  SdkTransport,
} from "./generated/soulfire/sdk_pb.js";

export const SDK_API_VERSION = {
  major: 1,
  minor: 0,
  patch: 0,
} as const;

export const SDK_VERSION = "2.11.0";

export interface ServerMetadata {
  readonly id: string;
  readonly version: string;
  readonly commitHash: string;
  readonly branchName: string;
  readonly apiVersion: Readonly<SdkApiVersion>;
  readonly minecraftVersion: string;
  readonly supportedMinecraftVersions: readonly string[];
  readonly transports: readonly SdkTransport[];
}

export class SoulFireCompatibilityError extends Error {
  public constructor(
    message: string,
    public override readonly cause?: unknown,
  ) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = "SoulFireCompatibilityError";
  }
}

export class SoulFireCapabilityError extends Error {
  public constructor(public readonly capability: string) {
    super(`SoulFire capability is unavailable: ${capability}`);
    this.name = "SoulFireCapabilityError";
  }
}

export class CapabilitySet {
  readonly #revisions: ReadonlyMap<string, number>;

  public constructor(handshake: SdkHandshakeResponse) {
    this.#revisions = new Map(
      handshake.capabilities.map(({ id, revision }) => [id, revision]),
    );
  }

  public supports(capability: string, minimumRevision = 1): boolean {
    return (this.#revisions.get(capability) ?? 0) >= minimumRevision;
  }

  public require(capability: string, minimumRevision = 1): void {
    if (!this.supports(capability, minimumRevision)) {
      throw new SoulFireCapabilityError(capability);
    }
  }

  public revision(capability: string): number | undefined {
    return this.#revisions.get(capability);
  }

  public entries(): ReadonlyMap<string, number> {
    return this.#revisions;
  }
}

export interface ConnectionMetadata {
  readonly server: ServerMetadata;
  readonly identity: Readonly<SdkIdentity>;
  readonly capabilities: CapabilitySet;
  readonly plugins: readonly PluginApiDescriptor[];
  readonly limits: ReadonlyMap<string, bigint>;
}

export function connectionMetadata(
  handshake: SdkHandshakeResponse,
): ConnectionMetadata {
  if (
    handshake.apiVersion === undefined
    || handshake.identity === undefined
  ) {
    throw new SoulFireCompatibilityError(
      "SoulFire returned an incomplete SDK handshake",
    );
  }
  return {
    server: {
      id: handshake.serverId,
      version: handshake.soulfireVersion,
      commitHash: handshake.commitHash,
      branchName: handshake.branchName,
      apiVersion: handshake.apiVersion,
      minecraftVersion: handshake.nativeMinecraftVersion,
      supportedMinecraftVersions: handshake.supportedMinecraftVersions,
      transports: handshake.transports,
    },
    identity: handshake.identity,
    capabilities: new CapabilitySet(handshake),
    plugins: handshake.plugins,
    limits: new Map(handshake.limits.map(({ id, value }) => [id, value])),
  };
}
