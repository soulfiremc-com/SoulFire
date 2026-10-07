import * as NodeHttpClient from "@effect/platform-node/NodeHttpClient";
import * as HttpClient from "effect/http/HttpClient";
import { Effect, Layer, type Scope } from "effect";

import { type SoulFireOptions } from "./client.js";
import {
  SoulFireClient,
  SoulFireConnectionError,
  SoulFireService,
  SoulFire as UniversalSoulFire,
} from "./index.js";
import type { ManagedBotOptions, SoulFireInstallOptions } from "./install-types.js";
import { installLocalServer } from "./local-server.js";
import { makeEffectHttpClientFetch } from "./platform.js";

export * from "./index.js";

const nodeHttpClientLayer = NodeHttpClient.layerUndiciNoDispatcher.pipe(
  Layer.provide(NodeHttpClient.dispatcherLayerGlobal),
);

function connect(
  options: SoulFireOptions,
): Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope> {
  return UniversalSoulFire.connectWithHttpClient(options).pipe(
    Effect.provide(nodeHttpClientLayer),
  );
}

function layer(
  options: SoulFireOptions,
): Layer.Layer<SoulFireService, SoulFireConnectionError> {
  return UniversalSoulFire.layerWithHttpClient(options).pipe(
    Layer.provide(nodeHttpClientLayer),
  );
}

function install(
  options: SoulFireInstallOptions = {},
): Effect.Effect<SoulFireClient, SoulFireConnectionError, Scope.Scope> {
  return Effect.gen(function* () {
    const httpClient = yield* HttpClient.HttpClient;
    const fetchImplementation =
      options.fetch ?? makeEffectHttpClientFetch(httpClient);
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
    Effect.provide(nodeHttpClientLayer),
  );
}

function installLayer(
  options: SoulFireInstallOptions = {},
): Layer.Layer<SoulFireService, SoulFireConnectionError> {
  return Layer.effect(SoulFireService, install(options));
}

/**
 * Node.js connection and managed installation helpers.
 *
 * `connect` checks compatibility with an existing SoulFire server. `install`
 * downloads and starts a local server, while `createBot` also provisions a ready
 * bot. These operations require `Scope`. Cleanup stops the managed process and
 * leaves downloaded files and persistent data in place.
 *
 * @remarks
 * Import this object from `@soulfiremc/sdk/node`.
 * `createBot` uses offline authentication unless configured otherwise. Its
 * Minecraft address is separate from a remote client's gRPC-Web URL.
 * @category Connection
 */
export const SoulFire = {
  ...UniversalSoulFire,
  connect,
  layer,
  install,
  installLayer,
  createBot: (options: ManagedBotOptions) => Effect.gen(function* () {
    const client = yield* install(options.installation);
    return yield* client.createBot(options);
  }),
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
    ...(options.interceptors === undefined
      ? {}
      : { interceptors: options.interceptors }),
  };
}
