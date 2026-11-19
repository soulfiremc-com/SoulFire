import { create, createRegistry } from "@bufbuild/protobuf";
import { anyPack } from "@bufbuild/protobuf/wkt";
import { createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import {
  file_soulfire_plugin_example_v1_example,
  TickSchema,
} from "../src/generated/soulfire/plugin/example/v1/example_pb.js";
import {
  PluginApiDescriptorSchema,
  PluginApiService,
  PluginEventKind,
  PluginEventSchema,
  type WatchPluginEventsRequest,
} from "../src/generated/soulfire/plugin_api_pb.js";
import { PluginCatalog, ReflectivePlugin } from "../src/plugins.js";

const tickTypeUrl = "type.googleapis.com/soulfire.plugin.example.v1.Tick";

describe("plugin events", () => {
  it("filters and decodes typed plugin event streams", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          let request: WatchPluginEventsRequest | undefined;
          const transport = eventTransport((value) => {
            request = value;
          });
          const catalog = new PluginCatalog(transport, [pluginDescriptor()]);
          const collected = yield* Stream.runCollect(
            catalog.typedEvents("example", TickSchema, {
              instanceId: "instance-id",
              afterSequence: 40n,
            }),
          );
          const events = Array.from(collected);
          expect(request).toMatchObject({
            pluginIds: ["example"],
            typeUrls: [tickTypeUrl],
            instanceId: "instance-id",
            afterSequence: 40n,
          });
          expect(events).toHaveLength(2);
          expect(events[0]?.event).toMatchObject({
            kind: PluginEventKind.READY,
            resumeGap: true,
          });
          expect(events[0]?.value).toBeUndefined();
          expect(events[1]?.value?.sequence).toBe(42);
        }),
      ),
    ));

  it("decodes unknown plugin event payloads reflectively", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const transport = eventTransport();
          const plugin = new ReflectivePlugin(
            pluginDescriptor(),
            createRegistry(file_soulfire_plugin_example_v1_example),
            transport,
          );
          const collected1 = yield* Stream.runCollect(plugin.events());
          const events = Array.from(collected1);
          expect(events[1]?.message).toMatchObject({
            typeName: "soulfire.plugin.example.v1.Tick",
            json: { sequence: 42 },
          });
        }),
      ),
    ));
});

function pluginDescriptor() {
  return create(PluginApiDescriptorSchema, {
    pluginId: "example",
    pluginVersion: "1.0.0",
    eventTypeUrls: [tickTypeUrl],
    eventTypes: [{ typeUrl: tickTypeUrl }],
  });
}

function eventTransport(onRequest?: (request: WatchPluginEventsRequest) => void) {
  return createRouterTransport(({ service }) => {
    service(PluginApiService, {
      async *watchPluginEvents(request) {
        onRequest?.(request);
        yield create(PluginEventSchema, {
          sequence: 41n,
          kind: PluginEventKind.READY,
          resumeGap: true,
        });
        yield create(PluginEventSchema, {
          sequence: 42n,
          kind: PluginEventKind.DATA,
          pluginId: "example",
          typeUrl: tickTypeUrl,
          payload: anyPack(TickSchema, create(TickSchema, { sequence: 42 })),
        });
      },
    });
  });
}
