import { create, createRegistry, type Registry } from "@bufbuild/protobuf";
import { anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect } from "effect";
import { describe, expect, it } from "vitest";
import { SoulFireStateError } from "../src/errors.js";
import {
  EchoRequestSchema,
  EchoResponseSchema,
  file_soulfire_plugin_example_v1_example,
} from "../src/generated/soulfire/plugin/example/v1/example_pb.js";
import { PluginApiDescriptorSchema } from "../src/generated/soulfire/plugin_api_pb.js";
import { BotTaskSchema, BotTaskService, BotTaskStatus } from "../src/generated/soulfire/task_pb.js";
import {
  ReflectivePlugin,
  SoulFirePluginCompatibilityError,
  SoulFirePluginDescriptorError,
} from "../src/plugins.js";
import { SoulFireTasks } from "../src/tasks.js";
import { taskInput, unpacked } from "./task-input.js";

const inputTypeUrl = `type.googleapis.com/${EchoRequestSchema.typeName}`;

function fixture(registry: Registry = createRegistry(file_soulfire_plugin_example_v1_example)) {
  const inputs: unknown[] = [];
  const transport = createRouterTransport(({ service }) => {
    service(BotTaskService, {
      startBotTask(request) {
        const input = unpacked(anyUnpack(taskInput(request), EchoRequestSchema));
        inputs.push(input);
        return create(BotTaskSchema, {
          instanceId: request.instanceId,
          botId: request.botId,
          taskId: "task-id",
          status: BotTaskStatus.COMPLETED,
          result: anyPack(
            EchoResponseSchema,
            create(EchoResponseSchema, { message: input.message }),
          ),
        });
      },
    });
  });
  const descriptor = create(PluginApiDescriptorSchema, {
    pluginId: "example",
    taskTypes: [
      { inputTypeUrl, resultTypeUrl: `type.googleapis.com/${EchoResponseSchema.typeName}` },
    ],
  });
  return {
    inputs,
    plugin: new ReflectivePlugin(descriptor, registry, transport),
    tasks: new SoulFireTasks(
      "instance-id",
      "bot-id",
      createClient(BotTaskService, transport),
      (options) => options,
    ),
  };
}

describe("reflective plugin tasks", () => {
  it("serializes descriptor-driven input and decodes the advertised result", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const { plugin, tasks, inputs } = fixture();
        const message = "Grüße 日本語 +[1,2]";
        const task = yield* plugin.startTask(tasks, inputTypeUrl, {
          instanceId: "instance-id",
          message,
        });
        const result = yield* task.result();
        expect(result).toMatchObject({ $typeName: EchoResponseSchema.typeName, message });
        expect(inputs).toHaveLength(1);
        expect(inputs[0]).toMatchObject({ instanceId: "instance-id", message });
      }),
    ));

  it("returns invalid JSON through the typed error channel without starting a task", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const { plugin, tasks, inputs } = fixture();
        const error = yield* Effect.flip(plugin.startTask(tasks, inputTypeUrl, { message: 42 }));
        expect(error).toBeInstanceOf(SoulFireStateError);
        expect(inputs).toHaveLength(0);
      }),
    ));

  it.each([
    { name: "input", registry: () => createRegistry() },
    { name: "result", registry: () => createRegistry(EchoRequestSchema) },
  ])("returns a missing $name descriptor through the typed error channel", ({ registry }) =>
    Effect.runPromise(
      Effect.gen(function* () {
        const { plugin, tasks, inputs } = fixture(registry());
        const error = yield* Effect.flip(plugin.startTask(tasks, inputTypeUrl, {}));
        expect(error).toBeInstanceOf(SoulFireStateError);
        if (!(error instanceof SoulFireStateError)) throw error;
        expect(error.cause).toBeInstanceOf(SoulFirePluginDescriptorError);
        expect(inputs).toHaveLength(0);
      }),
    ),
  );

  it("rejects unadvertised tasks before transport work", () =>
    Effect.runPromise(
      Effect.gen(function* () {
        const { plugin, tasks, inputs } = fixture();
        const error = yield* Effect.flip(
          plugin.startTask(tasks, "type.googleapis.com/unknown.Task", {}),
        );
        expect(error).toBeInstanceOf(SoulFireStateError);
        if (!(error instanceof SoulFireStateError)) throw error;
        expect(error.cause).toBeInstanceOf(SoulFirePluginCompatibilityError);
        expect(inputs).toHaveLength(0);
      }),
    ));
});
