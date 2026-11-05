#!/usr/bin/env node

import { Effect } from "effect";

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { SDK_VERSION } from "./connection.js";
import { SoulFire } from "./index.js";
import {
  generatePluginSdk,
  pluginMetadata,
  type PluginSdkLanguage,
  type PluginSdkMetadata,
} from "./plugin-generator.js";

interface GenerateArguments {
  readonly descriptor?: string;
  readonly server?: string;
  readonly plugin?: string;
  readonly token?: string;
  readonly language: PluginSdkLanguage;
  readonly output?: string;
  readonly packageName?: string;
  readonly pluginVersion?: string;
  readonly apiMajorVersion?: number;
  readonly requiredSoulFireVersion?: string;
}

async function main(arguments_: readonly string[]): Promise<void> {
  const nodeMajor = Number.parseInt(process.versions.node.split(".")[0] ?? "", 10);
  if (!Number.isFinite(nodeMajor) || nodeMajor < 22) {
    throw new Error("soulfire-sdk requires Node.js 22 or newer");
  }
  const [command, ...rest] = arguments_;
  if (command === undefined || command === "--help" || command === "-h") {
    process.stdout.write(help());
    return;
  }
  if (command === "--version" || command === "-v") {
    process.stdout.write(`${SDK_VERSION}\n`);
    return;
  }
  if (command !== "generate") {
    throw new Error(`Unknown command: ${command}\n\n${help()}`);
  }

  const options = parseGenerateArguments(rest);
  const source =
    options.server === undefined
      ? {
          descriptorSet: new Uint8Array(
            await readFile(requireValue(options.descriptor, "--descriptor")),
          ),
          metadata: undefined,
        }
      : await downloadDescriptor(
          options.server,
          requireValue(options.plugin, "--plugin"),
          options.token,
        );
  const outputDirectory = await generatePluginSdk({
    descriptorSet: source.descriptorSet,
    ...(source.metadata === undefined ? {} : { metadata: source.metadata }),
    ...(options.plugin === undefined ? {} : { pluginId: options.plugin }),
    ...(options.pluginVersion === undefined ? {} : { pluginVersion: options.pluginVersion }),
    ...(options.apiMajorVersion === undefined ? {} : { apiMajorVersion: options.apiMajorVersion }),
    ...(options.requiredSoulFireVersion === undefined
      ? {}
      : { requiredSoulFireVersion: options.requiredSoulFireVersion }),
    language: options.language,
    ...(options.output === undefined ? {} : { outputDirectory: options.output }),
    ...(options.packageName === undefined ? {} : { packageName: options.packageName }),
  });
  process.stdout.write(`Generated ${options.language} plugin SDK at ${outputDirectory}\n`);
}

async function downloadDescriptor(
  server: string,
  pluginId: string,
  token: string | undefined,
): Promise<{
  readonly descriptorSet: Uint8Array;
  readonly metadata: PluginSdkMetadata;
}> {
  return Effect.runPromise(
    Effect.scoped(
      Effect.gen(function* () {
        const client = yield* SoulFire.connect({
          baseUrl: server,
          ...(token === undefined ? {} : { token }),
          requiredCapabilities: ["plugin.rpc.v1", "plugin.discovery.v1"],
          requiredPlugins: [{ pluginId }],
        });
        const descriptor = client.plugins.requireDescriptor(pluginId);
        return {
          descriptorSet: yield* client.plugins.descriptorSet(pluginId),
          metadata: pluginMetadata(descriptor),
        };
      }),
    ),
  );
}

function parseGenerateArguments(arguments_: readonly string[]): GenerateArguments {
  const values = new Map<string, string>();
  for (let index = 0; index < arguments_.length; index += 1) {
    const key = arguments_[index];
    if (key === "--help" || key === "-h") {
      process.stdout.write(help());
      process.exit(0);
    }
    if (key === undefined || !key.startsWith("--")) {
      throw new Error(`Unexpected argument: ${key ?? ""}`);
    }
    const value = arguments_[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`Missing value for ${key}`);
    }
    if (values.has(key)) {
      throw new Error(`Duplicate option: ${key}`);
    }
    values.set(key, value);
    index += 1;
  }
  const known = new Set([
    "--descriptor",
    "--server",
    "--plugin",
    "--token",
    "--language",
    "--output",
    "--package",
    "--plugin-version",
    "--api-major-version",
    "--required-soulfire-version",
  ]);
  for (const key of values.keys()) {
    if (!known.has(key)) {
      throw new Error(`Unknown option: ${key}`);
    }
  }
  const descriptor = values.get("--descriptor");
  const server = values.get("--server");
  if ((descriptor === undefined) === (server === undefined)) {
    throw new Error("Pass exactly one of --descriptor or --server");
  }
  const language = values.get("--language");
  if (language !== "typescript" && language !== "python") {
    throw new Error("--language must be typescript or python");
  }
  const apiMajorVersion = optionalPositiveInteger(
    values.get("--api-major-version"),
    "--api-major-version",
  );
  const plugin = values.get("--plugin");
  const token = values.get("--token") ?? process.env.SOULFIRE_TOKEN;
  const output = values.get("--output");
  const packageName = values.get("--package");
  const pluginVersion = values.get("--plugin-version");
  const requiredSoulFireVersion = values.get("--required-soulfire-version");
  return {
    language,
    ...(descriptor === undefined ? {} : { descriptor }),
    ...(server === undefined ? {} : { server }),
    ...(plugin === undefined ? {} : { plugin }),
    ...(token === undefined ? {} : { token }),
    ...(output === undefined ? {} : { output }),
    ...(packageName === undefined ? {} : { packageName }),
    ...(pluginVersion === undefined ? {} : { pluginVersion }),
    ...(apiMajorVersion === undefined ? {} : { apiMajorVersion }),
    ...(requiredSoulFireVersion === undefined ? {} : { requiredSoulFireVersion }),
  };
}

function optionalPositiveInteger(value: string | undefined, option: string): number | undefined {
  if (value === undefined) {
    return undefined;
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new Error(`${option} must be a positive integer`);
  }
  return parsed;
}

function requireValue(value: string | undefined, option: string): string {
  if (value === undefined || value.length === 0) {
    throw new Error(`${option} is required`);
  }
  return value;
}

function help(): string {
  return `Soulfire plugin SDK generator

Usage:
  soulfire-sdk generate --server <url> --plugin <id> --language <typescript|python>
  soulfire-sdk generate --descriptor <file.binpb> --language <typescript|python>

Options:
  --server <url>                       Download the descriptor from SoulFire
  --descriptor <path>                 Read a FileDescriptorSet from disk
  --plugin <id>                       Plugin ID, required with --server
  --token <token>                     Soulfire token, or use SOULFIRE_TOKEN
  --language <typescript|python>       Companion SDK language
  --output <directory>                Output directory
  --package <name>                    npm or Python distribution name
  --plugin-version <version>          Override descriptor-only package version
  --api-major-version <number>        Override inferred API major version
  --required-soulfire-version <range> Override inferred Soulfire version range
`;
}

function isMain(moduleUrl: string): boolean {
  const entry = process.argv[1];
  return entry !== undefined && pathToFileURL(entry).href === moduleUrl;
}

if (isMain(import.meta.url)) {
  main(process.argv.slice(2)).catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`soulfire-sdk: ${message}\n`);
    process.exitCode = 1;
  });
}
