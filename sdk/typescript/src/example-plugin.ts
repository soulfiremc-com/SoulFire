import type { Client } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { SoulFirePluginError } from "./errors.js";
import { rpcStream } from "./transport.js";

import {
  ExamplePluginService,
  type EchoResponse,
  type Tick,
} from "./generated/soulfire/plugin/example/v1/example_pb.js";
import type { PluginApiDescriptor } from "./generated/soulfire/plugin_api_pb.js";
import {
  SoulFireExtensionTypeId,
  type PluginCatalog,
  type SoulFireExtension,
  type SoulFirePluginModule,
} from "./plugins.js";

// isolatedDeclarations cannot emit a computed symbol key that a class declares,
// so the extension marker comes from this explicitly typed base class.
const SoulFireExtensionBase: new () => SoulFireExtension = class {
  public readonly [SoulFireExtensionTypeId] = true as const;
};

export class ExamplePluginClient extends SoulFireExtensionBase {
  public constructor(private readonly client: Client<typeof ExamplePluginService>) {
    super();
  }

  public echo(
    instanceId: string,
    message: string,
  ): Effect.Effect<EchoResponse, SoulFirePluginError> {
    return Effect.tryPromise({
      try: (signal) => this.client.echo({ instanceId, message }, { signal }),
      catch: (cause) => new SoulFirePluginError({ pluginId: "example", cause }),
    });
  }

  public watchTicks(instanceId: string, count: number): Stream.Stream<Tick, SoulFirePluginError> {
    return rpcStream("example.watchTicks", (signal) =>
      this.client.watchTicks({ instanceId, count }, { signal }),
    ).pipe(Stream.mapError((cause) => new SoulFirePluginError({ pluginId: "example", cause })));
  }
}

export const examplePlugin: SoulFirePluginModule<ExamplePluginClient> = {
  pluginId: "example",
  isCompatible: (descriptor) => descriptor.apiMajorVersion === 1 && hasExampleService(descriptor),
  create(catalog: PluginCatalog) {
    const client = catalog.service("example", ExamplePluginService);
    return new ExamplePluginClient(client);
  },
};

function hasExampleService(descriptor: PluginApiDescriptor): boolean {
  return descriptor.services.some(
    (service) => service.fullName === "soulfire.plugin.example.v1.ExamplePluginService",
  );
}
