import * as FetchHttpClient from "effect/http/FetchHttpClient";
import * as HttpClient from "effect/http/HttpClient";
import { Effect, Layer, type Scope } from "effect";

import { type SoulFireOptions } from "./client.js";
import {
  type SoulFireBot,
  type SoulFireClient,
  SoulFireConnectionError,
  type SoulFireOperationError,
  SoulFireService,
  SoulFire as UniversalSoulFire,
} from "./index.js";
import type { ManagedBotOptions, SoulFireInstallOptions } from "./install-types.js";
import { installLocalServer } from "./local-server.js";
import { makeEffectHttpClientFetch } from "./platform.js";

export * from "./index.js";

function connect(
  options: SoulFireOptions,
): Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope> {
  return UniversalSoulFire.connectWithHttpClient(options).pipe(
    Effect.provide(FetchHttpClient.layer),
  );
}

function layer(options: SoulFireOptions): Layer.Layer<SoulFireService, SoulFireConnectionError> {
  return UniversalSoulFire.layerWithHttpClient(options).pipe(Layer.provide(FetchHttpClient.layer));
}

function install(
  options: SoulFireInstallOptions = {},
): Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope> {
  return Effect.gen(function* () {
    const httpClient = yield* HttpClient.HttpClient;
    const fetchImplementation = options.fetch ?? makeEffectHttpClientFetch(httpClient);
    const localServer = yield* installLocalServer({
      ...options,
      fetch: fetchImplementation,
    });
    return yield* UniversalSoulFire.connectManaged(
      connectionOptions(localServer.info.baseUrl, localServer.token, {
        ...options,
        fetch: fetchImplementation,
      }),
      localServer,
    );
  }).pipe(
    Effect.mapError((cause) => new SoulFireConnectionError({ cause })),
    Effect.provide(FetchHttpClient.layer),
  );
}

function installLayer(
  options: SoulFireInstallOptions = {},
): Layer.Layer<SoulFireService, SoulFireConnectionError> {
  return Layer.effect(SoulFireService, install(options));
}

function createBot(
  options: ManagedBotOptions,
): Effect.Effect<SoulFireBot, SoulFireConnectionError | SoulFireOperationError, Scope.Scope> {
  return Effect.gen(function* () {
    const client = yield* install(options.installation);
    return yield* client.createBot(options);
  });
}

/**
 * Bun connection and managed installation helpers.
 *
 * `connect` checks compatibility with an existing SoulFire server. `install`
 * downloads and starts a local server, while `createBot` also provisions a ready
 * bot. These operations require `Scope`. Cleanup stops the managed process and
 * leaves downloaded files and persistent data in place.
 *
 * @remarks
 * Import this object from `@soulfiremc/sdk/bun`.
 * `createBot` uses offline authentication unless configured otherwise. Its
 * Minecraft address is separate from a remote client's gRPC-Web URL.
 * @category Connection
 */
export const SoulFire: {
  readonly unauthenticated: typeof SoulFireClient.unauthenticated;
  readonly connectManaged: typeof SoulFireClient.connectManaged;
  readonly connectWithHttpClient: (
    options: SoulFireOptions,
  ) => Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope | HttpClient.HttpClient>;
  readonly layerWithHttpClient: (
    options: SoulFireOptions,
  ) => Layer.Layer<SoulFireService, SoulFireConnectionError, HttpClient.HttpClient>;
  readonly connect: (
    options: SoulFireOptions,
  ) => Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope>;
  readonly layer: (
    options: SoulFireOptions,
  ) => Layer.Layer<SoulFireService, SoulFireConnectionError>;
  readonly install: (
    options?: SoulFireInstallOptions,
  ) => Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope>;
  readonly installLayer: (
    options?: SoulFireInstallOptions,
  ) => Layer.Layer<SoulFireService, SoulFireConnectionError>;
  readonly createBot: (
    options: ManagedBotOptions,
  ) => Effect.Effect<SoulFireBot, SoulFireConnectionError | SoulFireOperationError, Scope.Scope>;
} = {
  ...UniversalSoulFire,
  connect,
  layer,
  install,
  installLayer,
  createBot,
} as const;

function connectionOptions(
  baseUrl: string,
  token: string,
  options: SoulFireInstallOptions,
): SoulFireOptions {
  return {
    baseUrl,
    token,
    ...(options.defaultTimeoutMs === undefined
      ? {}
      : { defaultTimeoutMs: options.defaultTimeoutMs }),
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
    ...(options.interceptors === undefined ? {} : { interceptors: options.interceptors }),
  };
}
