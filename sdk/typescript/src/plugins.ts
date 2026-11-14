import {
  createFileRegistry,
  fromBinary,
  fromJson,
  toJson,
  type DescMessage,
  type DescMethod,
  type DescMethodServerStreaming,
  type DescMethodUnary,
  type DescService,
  type JsonValue,
  type Message,
  type MessageInitShape,
  type MessageShape,
  type Registry,
} from "@bufbuild/protobuf";
import { FileDescriptorSetSchema } from "@bufbuild/protobuf/wkt";
import { createClient, type CallOptions, type Client, type Transport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { operationError, SoulFirePluginError, type SoulFireOperationError } from "./errors.js";
import { rpc, rpcStream, withSignal } from "./transport.js";

import {
  PluginApiEventKind,
  PluginApiService,
  type PluginApiDescriptor,
  type PluginApiEvent,
  type PluginEvent,
} from "./generated/soulfire/plugin_api_pb.js";
import type { SoulFireTask, SoulFireTasks, TaskStartOptions } from "./tasks.js";

export interface WatchPluginEventOptions {
  readonly pluginIds?: readonly string[];
  readonly typeUrls?: readonly string[];
  readonly instanceId?: string;
  readonly botId?: string;
  readonly taskId?: string;
  readonly afterSequence?: bigint;
  readonly call?: CallOptions;
}

export interface TypedPluginEvent<T extends DescMessage> {
  readonly event: PluginEvent;
  readonly value?: MessageShape<T>;
}

export interface ReflectivePluginEvent {
  readonly event: PluginEvent;
  readonly message?: ReflectiveMessage;
}

export interface SoulFirePluginModule<T extends SoulFireExtension> {
  readonly pluginId: string;
  readonly isCompatible?: (descriptor: PluginApiDescriptor) => boolean;
  create(catalog: PluginCatalog, descriptor: PluginApiDescriptor): T;
}

export class SoulFirePluginNotFoundError extends Error {
  public constructor(public readonly pluginId: string) {
    super(`Soulfire plugin is not installed: ${pluginId}`);
    this.name = "SoulFirePluginNotFoundError";
  }
}

export class SoulFirePluginCompatibilityError extends Error {
  public constructor(
    public readonly pluginId: string,
    message: string,
  ) {
    super(message);
    this.name = "SoulFirePluginCompatibilityError";
  }
}

export class SoulFirePluginDescriptorError extends Error {
  public constructor(
    public readonly pluginId: string,
    message: string,
  ) {
    super(message);
    this.name = "SoulFirePluginDescriptorError";
  }
}

export interface ReflectiveMessage {
  readonly typeName: string;
  readonly value: Message;
  readonly json: JsonValue;
}

export class ReflectivePlugin {
  public constructor(
    public readonly descriptor: PluginApiDescriptor,
    private readonly registry: Registry,
    private readonly transport: Transport,
  ) {}

  public call(
    serviceName: string,
    methodName: string,
    input: JsonValue,
    options?: CallOptions,
  ): Effect.Effect<ReflectiveMessage, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const method = yield* Effect.try({
        try: () => this.#method(serviceName, methodName),
        catch: (cause) => operationError("plugin.call", cause),
      });
      if (method.methodKind !== "unary")
        return yield* Effect.fail(
          operationError(
            "plugin.call",
            new SoulFirePluginDescriptorError(
              this.descriptor.pluginId,
              "The requested method is not a unary RPC",
            ),
          ),
        );
      const unary: DescMethodUnary = { ...method, methodKind: "unary" };
      const request = yield* Effect.try({
        try: () => fromJson(method.input, input, { registry: this.registry }),
        catch: (cause) => operationError("plugin.call", cause),
      });
      const response = yield* rpc("plugin.call", (signal) =>
        this.transport.unary(
          unary,
          withSignal(options, signal).signal,
          options?.timeoutMs,
          options?.headers,
          request,
          options?.contextValues,
        ),
      );
      return yield* Effect.try({
        try: () => reflectiveMessage(method.output, response.message, this.registry),
        catch: (cause) => operationError("plugin.call", cause),
      });
    });
  }

  public stream(
    serviceName: string,
    methodName: string,
    input: JsonValue,
    options?: CallOptions,
  ): Stream.Stream<ReflectiveMessage, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.gen({ self: this }, function* () {
        const method = yield* Effect.try({
          try: () => this.#method(serviceName, methodName),
          catch: (cause) => operationError("plugin.stream", cause),
        });
        if (method.methodKind !== "server_streaming")
          return yield* Effect.fail(
            operationError(
              "plugin.stream",
              new SoulFirePluginDescriptorError(
                this.descriptor.pluginId,
                "The requested method is not a server-streaming RPC",
              ),
            ),
          );
        const request = yield* Effect.try({
          try: () => fromJson(method.input, input, { registry: this.registry }),
          catch: (cause) => operationError("plugin.stream", cause),
        });
        const transport = this.transport;
        const streaming: DescMethodServerStreaming = {
          ...method,
          methodKind: "server_streaming",
        };
        return rpcStream("plugin.stream", (signal) =>
          (async function* () {
            const response = await transport.stream(
              streaming,
              withSignal(options, signal).signal,
              options?.timeoutMs,
              options?.headers,
              singleMessage(request),
              options?.contextValues,
            );
            yield* response.message;
          })(),
        ).pipe(
          Stream.mapEffect((value) =>
            Effect.try({
              try: () => reflectiveMessage(method.output, value, this.registry),
              catch: (cause) => operationError("plugin.stream", cause),
            }),
          ),
        );
      }),
    );
  }

  /**
   * Watches events published by this plugin and decodes their payloads from
   * the plugin's downloaded descriptor set.
   */
  public events(
    options: Omit<WatchPluginEventOptions, "pluginIds"> = {},
  ): Stream.Stream<ReflectivePluginEvent, SoulFireOperationError> {
    const client = createClient(PluginApiService, this.transport);
    const { call, typeUrls = [], ...request } = options;
    return mapReflectiveEvents(
      rpcStream("plugins.events", (signal) =>
        client.watchPluginEvents(
          {
            ...request,
            pluginIds: [this.descriptor.pluginId],
            typeUrls: unique(typeUrls),
          },
          withSignal(call, signal),
        ),
      ),
      this.registry,
      this.descriptor.pluginId,
    );
  }

  /**
   * Starts a plugin-defined task using its downloaded request and result
   * descriptors. Generated companion SDKs expose a statically typed method
   * for the same operation.
   */
  public startTask(
    tasks: SoulFireTasks,
    inputTypeUrl: string,
    input: JsonValue,
    options: TaskStartOptions = {},
  ): Effect.Effect<SoulFireTask<DescMessage>, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const task = this.descriptor.taskTypes.find(
        (candidate) => candidate.inputTypeUrl === inputTypeUrl,
      );
      if (task === undefined) {
        return yield* Effect.fail(
          operationError(
            "ReflectivePlugin.startTask",
            new SoulFirePluginCompatibilityError(
              this.descriptor.pluginId,
              `Plugin ${this.descriptor.pluginId} does not expose task ${inputTypeUrl}`,
            ),
          ),
        );
      }
      const { inputSchema, resultSchema, value } = yield* Effect.try({
        try: () => {
          const inputSchema = this.#message(task.inputTypeUrl);
          const resultSchema = this.#message(task.resultTypeUrl);
          const value = fromJson(inputSchema, input, { registry: this.registry });
          return { inputSchema, resultSchema, value };
        },
        catch: (cause) => operationError("ReflectivePlugin.startTask", cause),
      });
      return yield* tasks.start(
        inputSchema,
        value as MessageInitShape<typeof inputSchema>,
        resultSchema,
        options,
      );
    });
  }

  #message(typeUrl: string): DescMessage {
    const typeName = typeUrl.includes("/") ? typeUrl.slice(typeUrl.lastIndexOf("/") + 1) : typeUrl;
    const message = this.registry.getMessage(typeName);
    if (message === undefined) {
      throw new SoulFirePluginDescriptorError(
        this.descriptor.pluginId,
        `Plugin descriptor does not contain message ${typeName}`,
      );
    }
    return message;
  }

  #method(serviceName: string, methodName: string): DescMethod {
    const service = this.registry.getService(serviceName);
    if (service === undefined) {
      throw new SoulFirePluginDescriptorError(
        this.descriptor.pluginId,
        `Plugin ${this.descriptor.pluginId} does not describe service ${serviceName}`,
      );
    }
    if (!this.descriptor.services.some((candidate) => candidate.fullName === serviceName)) {
      throw new SoulFirePluginCompatibilityError(
        this.descriptor.pluginId,
        `Plugin ${this.descriptor.pluginId} does not expose ${serviceName}`,
      );
    }
    const method = service.methods.find(
      (candidate) => candidate.name === methodName || candidate.localName === methodName,
    );
    if (method === undefined) {
      throw new SoulFirePluginDescriptorError(
        this.descriptor.pluginId,
        `Service ${serviceName} does not describe method ${methodName}`,
      );
    }
    return method;
  }
}

export class PluginCatalog {
  readonly #client: Client<typeof PluginApiService>;
  readonly #transport: Transport;
  #plugins: Map<string, PluginApiDescriptor>;
  #revision: bigint;
  readonly #reflective = new Map<
    string,
    {
      readonly hash: string;
      readonly plugin: ReflectivePlugin;
    }
  >();

  public constructor(
    transport: Transport,
    plugins: readonly PluginApiDescriptor[] = [],
    revision = 0n,
  ) {
    this.#transport = transport;
    this.#client = createClient(PluginApiService, transport);
    this.#plugins = indexPlugins(plugins);
    this.#revision = revision;
  }

  public get revision(): bigint {
    return this.#revision;
  }

  public all(): readonly PluginApiDescriptor[] {
    return [...this.#plugins.values()];
  }

  public get(pluginId: string): PluginApiDescriptor | undefined {
    return this.#plugins.get(pluginId);
  }

  public requireDescriptor(pluginId: string): PluginApiDescriptor {
    const descriptor = this.get(pluginId);
    if (descriptor === undefined) {
      throw new SoulFirePluginNotFoundError(pluginId);
    }
    return descriptor;
  }

  public require<T extends SoulFireExtension>(
    module: SoulFirePluginModule<T>,
  ): Effect.Effect<T, SoulFirePluginError> {
    return Effect.try({
      try: () => {
        const descriptor = this.requireDescriptor(module.pluginId);
        if (module.isCompatible !== undefined && !module.isCompatible(descriptor))
          throw new SoulFirePluginCompatibilityError(
            module.pluginId,
            "Installed plugin is incompatible with its SDK module",
          );
        return module.create(this, descriptor);
      },
      catch: (cause) => new SoulFirePluginError({ pluginId: module.pluginId, cause }),
    });
  }

  public service<T extends DescService>(pluginId: string, service: T): Client<T> {
    const descriptor = this.requireDescriptor(pluginId);
    if (!descriptor.services.some((registered) => registered.fullName === service.typeName)) {
      throw new SoulFirePluginCompatibilityError(
        pluginId,
        `Plugin ${pluginId} does not expose ${service.typeName}`,
      );
    }
    return createClient(service, this.#transport);
  }

  public refresh(
    options?: CallOptions,
  ): Effect.Effect<readonly PluginApiDescriptor[], SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("PluginCatalog.refresh", (signal) =>
        this.#client.listPluginApis({}, withSignal(options, signal)),
      );
      this.#replace(response.plugins, response.revision);
      return this.all();
    });
  }

  public descriptorSet(
    pluginId: string,
    options?: CallOptions,
  ): Effect.Effect<Uint8Array, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const descriptor = yield* Effect.try({
        try: () => this.requireDescriptor(pluginId),
        catch: (cause) => operationError("PluginCatalog.descriptorSet", cause),
      });
      const response = yield* rpc("PluginCatalog.descriptorSet", (signal) =>
        this.#client.getPluginDescriptorSet(
          {
            pluginId,
            expectedSha256: descriptor.descriptorSha256,
          },
          withSignal(options, signal),
        ),
      );
      const actualHash = yield* rpc("plugins.sha256", () => sha256(response.descriptorSet));
      if (actualHash !== response.descriptorSha256.toLowerCase()) {
        return yield* Effect.fail(
          operationError(
            "PluginCatalog.descriptorSet",
            new SoulFirePluginDescriptorError(
              pluginId,
              `Descriptor hash mismatch for plugin ${pluginId}`,
            ),
          ),
        );
      }
      return response.descriptorSet;
    });
  }

  public reflective(
    pluginId: string,
    options?: CallOptions,
  ): Effect.Effect<ReflectivePlugin, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const descriptor = yield* Effect.try({
        try: () => this.requireDescriptor(pluginId),
        catch: (cause) => operationError("PluginCatalog.reflective", cause),
      });
      const cached = this.#reflective.get(pluginId);
      if (cached?.hash === descriptor.descriptorSha256) {
        return cached.plugin;
      }
      const bytes = yield* this.descriptorSet(pluginId, options);
      const registry = yield* Effect.try({
        try: () => createFileRegistry(fromBinary(FileDescriptorSetSchema, bytes)),
        catch: (cause) => operationError("plugins.reflective", cause),
      });
      const plugin = new ReflectivePlugin(descriptor, registry, this.#transport);
      this.#reflective.set(pluginId, {
        hash: descriptor.descriptorSha256,
        plugin,
      });
      return plugin;
    });
  }

  public watch(options?: CallOptions): Stream.Stream<PluginApiEvent, SoulFireOperationError> {
    return rpcStream("plugins.watch", (signal) =>
      this.#client.watchPluginApis({ afterRevision: this.#revision }, withSignal(options, signal)),
    ).pipe(
      Stream.tap((event) =>
        Effect.sync(() => {
          if (event.kind === PluginApiEventKind.SNAPSHOT)
            this.#replace(event.plugins, event.revision);
          else if (event.plugin !== undefined) {
            this.#plugins.set(event.plugin.pluginId, event.plugin);
            this.#revision = event.revision;
          } else if (event.removedPluginId !== undefined) {
            this.#plugins.delete(event.removedPluginId);
            this.#revision = event.revision;
          }
        }),
      ),
    );
  }

  /**
   * Watches the normalized event stream published by installed plugins.
   *
   * The initial READY envelope reports whether the requested sequence could
   * be resumed. Plugin events are live and are not retained by the server.
   */
  public events(
    options: WatchPluginEventOptions = {},
  ): Stream.Stream<PluginEvent, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.sync(() => {
        const { pluginIds = [], typeUrls = [], call, ...request } = options;
        return rpcStream("PluginCatalog.events", (signal) =>
          this.#client.watchPluginEvents(
            {
              ...request,
              pluginIds: unique(pluginIds),
              typeUrls: unique(typeUrls),
            },
            withSignal(call, signal),
          ),
        );
      }),
    );
  }

  /**
   * Watches one advertised event type and decodes each DATA payload.
   */
  public typedEvents<T extends DescMessage>(
    pluginId: string,
    schema: T,
    options: Omit<WatchPluginEventOptions, "pluginIds" | "typeUrls"> = {},
  ): Stream.Stream<TypedPluginEvent<T>, SoulFireOperationError> {
    return Stream.unwrap(
      Effect.gen({ self: this }, function* () {
        const typeUrl = typeUrlFor(schema);
        const descriptor = yield* Effect.try({
          try: () => this.requireDescriptor(pluginId),
          catch: (cause) => operationError("PluginCatalog.typedEvents", cause),
        });
        const eventTypeUrls = new Set([
          ...descriptor.eventTypeUrls,
          ...descriptor.eventTypes.map((event) => event.typeUrl),
        ]);
        if (!eventTypeUrls.has(typeUrl)) {
          return yield* Effect.fail(
            operationError(
              "PluginCatalog.typedEvents",
              new SoulFirePluginCompatibilityError(
                pluginId,
                `Plugin ${pluginId} does not publish ${typeUrl}`,
              ),
            ),
          );
        }
        return mapTypedEvents(
          this.events({
            ...options,
            pluginIds: [pluginId],
            typeUrls: [typeUrl],
          }),
          schema,
          pluginId,
        );
      }),
    );
  }

  #replace(plugins: readonly PluginApiDescriptor[], revision: bigint): void {
    this.#plugins = indexPlugins(plugins);
    this.#revision = revision;
    this.#reflective.clear();
  }
}

function indexPlugins(plugins: readonly PluginApiDescriptor[]): Map<string, PluginApiDescriptor> {
  return new Map(plugins.map((plugin) => [plugin.pluginId, plugin]));
}

async function sha256(value: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", Uint8Array.from(value).buffer);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function reflectiveMessage(
  descriptor: DescMessage,
  value: Message,
  registry: Registry,
): ReflectiveMessage {
  return {
    typeName: descriptor.typeName,
    value,
    json: toJson(descriptor, value, { registry }),
  };
}

function mapTypedEvents<T extends DescMessage>(
  events: Stream.Stream<PluginEvent, SoulFireOperationError>,
  schema: T,
  pluginId: string,
): Stream.Stream<TypedPluginEvent<T>, SoulFireOperationError> {
  const expectedTypeUrl = typeUrlFor(schema);
  return events.pipe(
    Stream.mapEffect((event) =>
      Effect.try({
        try: (): TypedPluginEvent<T> => {
          if (event.payload === undefined) return { event };
          if (event.typeUrl !== expectedTypeUrl || event.payload.typeUrl !== expectedTypeUrl)
            throw new SoulFirePluginDescriptorError(
              pluginId,
              `Expected ${expectedTypeUrl}, received ${event.typeUrl ?? event.payload.typeUrl}`,
            );
          return { event, value: fromBinary(schema, event.payload.value) };
        },
        catch: (cause) => operationError("plugins.typedEvents", cause),
      }),
    ),
  );
}

function mapReflectiveEvents(
  events: Stream.Stream<PluginEvent, SoulFireOperationError>,
  registry: Registry,
  pluginId: string,
): Stream.Stream<ReflectivePluginEvent, SoulFireOperationError> {
  return events.pipe(
    Stream.mapEffect((event) =>
      Effect.try({
        try: (): ReflectivePluginEvent => {
          if (event.payload === undefined) return { event };
          const typeName = typeNameFromUrl(event.typeUrl ?? event.payload.typeUrl);
          const schema = registry.getMessage(typeName);
          if (schema === undefined)
            throw new SoulFirePluginDescriptorError(
              pluginId,
              `Plugin descriptor does not contain event type ${typeName}`,
            );
          return {
            event,
            message: reflectiveMessage(schema, fromBinary(schema, event.payload.value), registry),
          };
        },
        catch: (cause) => operationError("plugins.reflectiveEvents", cause),
      }),
    ),
  );
}

async function* singleMessage(message: Message): AsyncIterable<Message> {
  yield message;
}

function typeUrlFor(schema: DescMessage): string {
  return `type.googleapis.com/${schema.typeName}`;
}

function typeNameFromUrl(typeUrl: string): string {
  const separator = typeUrl.lastIndexOf("/");
  const typeName = separator === -1 ? typeUrl : typeUrl.slice(separator + 1);
  if (typeName.length === 0) {
    throw new Error(`Invalid protobuf type URL: ${typeUrl}`);
  }
  return typeName;
}

function unique(values: readonly string[]): string[] {
  return [...new Set(values)];
}

export const SoulFireExtensionTypeId: unique symbol = Symbol.for(
  "@soulfiremc/sdk/SoulFireExtension",
);
export interface SoulFireExtension {
  readonly [SoulFireExtensionTypeId]: true;
}
export function defineSoulFirePlugin<T extends SoulFireExtension>(
  module: SoulFirePluginModule<T>,
): SoulFirePluginModule<T> {
  if (module.pluginId.trim().length === 0)
    throw new TypeError("Soulfire plugin modules require a non-empty pluginId");
  return Object.freeze(module);
}
