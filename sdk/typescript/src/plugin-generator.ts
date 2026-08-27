import { fromBinary } from "@bufbuild/protobuf";
import {
  FileDescriptorSetSchema,
  type FileDescriptorProto,
  type FileDescriptorSet,
} from "@bufbuild/protobuf/wkt";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import {
  access,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  rmdir,
  writeFile,
} from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join, relative, resolve } from "node:path";
import { promisify } from "node:util";

import type { PluginApiDescriptor } from "./generated/soulfire/plugin_api_pb.js";
import { SDK_VERSION } from "./connection.js";

const executeFile = promisify(execFile);
const require = createRequire(import.meta.url);

export type PluginSdkLanguage = "typescript" | "python";

export interface PluginSdkMetadata {
  readonly pluginId: string;
  readonly pluginVersion: string;
  readonly description: string;
  readonly author: string;
  readonly license: string;
  readonly requiredSoulFireVersion: string;
  readonly apiMajorVersion: number;
  readonly descriptorSha256: string;
  readonly serviceNames: readonly string[];
  readonly eventTypeUrls: readonly string[];
  readonly taskTypeUrls: readonly string[];
  readonly taskTypes: readonly PluginTaskMetadata[];
  readonly documentationUrl?: string;
  readonly sourceUrl?: string;
  readonly typescriptPackage?: string;
  readonly pythonPackage?: string;
}

export interface PluginTaskMetadata {
  readonly inputTypeUrl: string;
  readonly resultTypeUrl: string;
  readonly progressTypeUrl?: string;
  readonly permissions: readonly string[];
}

export interface GeneratePluginSdkOptions {
  readonly descriptorSet: Uint8Array;
  readonly metadata?: PluginSdkMetadata;
  readonly pluginId?: string;
  readonly pluginVersion?: string;
  readonly apiMajorVersion?: number;
  readonly requiredSoulFireVersion?: string;
  readonly language: PluginSdkLanguage;
  readonly outputDirectory?: string;
  readonly packageName?: string;
}

interface MethodModel {
  readonly name: string;
  readonly localName: string;
  readonly pythonName: string;
  readonly inputType: PythonType;
  readonly outputType: PythonType;
  readonly serverStreaming: boolean;
}

interface ServiceModel {
  readonly name: string;
  readonly fullName: string;
  readonly localName: string;
  readonly pythonName: string;
  readonly fileName: string;
  readonly methods: readonly MethodModel[];
}

interface EventModel {
  readonly typeUrl: string;
  readonly localName: string;
  readonly pythonName: string;
  readonly messageType: PythonType;
}

interface TaskModel {
  readonly inputTypeUrl: string;
  readonly resultTypeUrl: string;
  readonly progressTypeUrl?: string;
  readonly localName: string;
  readonly pythonName: string;
  readonly inputType: PythonType;
  readonly resultType: PythonType;
  readonly progressType?: PythonType;
}

interface PythonType {
  readonly fileName: string;
  readonly moduleName: string;
  readonly importName: string;
  readonly expression: string;
  readonly typescriptSchemaName: string;
}

interface GenerationModel {
  readonly descriptorSet: FileDescriptorSet;
  readonly metadata: PluginSdkMetadata;
  readonly services: readonly ServiceModel[];
  readonly events: readonly EventModel[];
  readonly tasks: readonly TaskModel[];
  readonly sourceFiles: readonly string[];
  readonly typescriptPackageName: string;
  readonly pythonPackageName: string;
  readonly pythonModuleName: string;
}

export function pluginMetadata(
  descriptor: PluginApiDescriptor,
): PluginSdkMetadata {
  return {
    pluginId: descriptor.pluginId,
    pluginVersion: descriptor.pluginVersion,
    description: descriptor.description,
    author: descriptor.author,
    license: descriptor.license,
    requiredSoulFireVersion: descriptor.requiredSoulfireVersion,
    apiMajorVersion: descriptor.apiMajorVersion,
    descriptorSha256: descriptor.descriptorSha256.toLowerCase(),
    serviceNames: descriptor.services.map((service) => service.fullName),
    eventTypeUrls: [...descriptor.eventTypeUrls],
    taskTypeUrls: [...descriptor.taskTypeUrls],
    taskTypes: descriptor.taskTypes.map((task) => ({
      inputTypeUrl: task.inputTypeUrl,
      resultTypeUrl: task.resultTypeUrl,
      ...(task.progressTypeUrl === undefined
        ? {}
        : { progressTypeUrl: task.progressTypeUrl }),
      permissions: [...task.permissions],
    })),
    ...(descriptor.documentationUrl === undefined
      ? {}
      : { documentationUrl: descriptor.documentationUrl }),
    ...(descriptor.sourceUrl === undefined
      ? {}
      : { sourceUrl: descriptor.sourceUrl }),
    ...(descriptor.sdkPackages?.typescriptPackage === undefined
      ? {}
      : { typescriptPackage: descriptor.sdkPackages.typescriptPackage }),
    ...(descriptor.sdkPackages?.pythonPackage === undefined
      ? {}
      : { pythonPackage: descriptor.sdkPackages.pythonPackage }),
  };
}

export async function generatePluginSdk(
  options: GeneratePluginSdkOptions,
): Promise<string> {
  const model = generationModel(options);
  const defaultDirectory = `${model.pythonPackageName}-${options.language}`;
  const outputDirectory = resolve(
    options.outputDirectory ?? defaultDirectory,
  );
  const parent = dirname(outputDirectory);
  await mkdir(parent, { recursive: true });
  const outputState = await inspectOutput(outputDirectory);
  const staging = await mkdtemp(join(parent, ".soulfire-sdk-"));

  try {
    await writeFile(
      join(staging, "plugin-api.binpb"),
      options.descriptorSet,
    );
    await writeFile(
      join(staging, "soulfire-plugin.json"),
      `${JSON.stringify(model.metadata, null, 2)}\n`,
      "utf8",
    );

    if (options.language === "typescript") {
      await generateTypeScript(staging, model);
    } else {
      await generatePython(staging, model);
    }

    if (outputState === "empty") {
      await rmdir(outputDirectory);
    }
    await rename(staging, outputDirectory);
    return outputDirectory;
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
}

function generationModel(options: GeneratePluginSdkOptions): GenerationModel {
  const descriptorSha256 = createHash("sha256")
    .update(options.descriptorSet)
    .digest("hex");
  const descriptorSet = fromBinary(
    FileDescriptorSetSchema,
    options.descriptorSet,
  );
  const inferred = inferMetadata(descriptorSet, descriptorSha256);
  const supplied = options.metadata;
  const pluginId = options.pluginId ?? supplied?.pluginId ?? inferred.pluginId;
  if (pluginId.length === 0) {
    throw new Error(
      "Unable to infer a plugin ID from the descriptor. Pass --plugin.",
    );
  }
  if (
    supplied !== undefined
    && supplied.descriptorSha256.toLowerCase() !== descriptorSha256
  ) {
    throw new Error(
      `Descriptor hash mismatch: expected ${supplied.descriptorSha256}, received ${descriptorSha256}`,
    );
  }
  const serviceNames = supplied?.serviceNames.length
    ? supplied.serviceNames
    : inferred.serviceNames;
  const metadata: PluginSdkMetadata = {
    ...(supplied ?? inferred),
    pluginId,
    pluginVersion: options.pluginVersion
      ?? supplied?.pluginVersion
      ?? inferred.pluginVersion,
    apiMajorVersion: options.apiMajorVersion
      ?? supplied?.apiMajorVersion
      ?? inferred.apiMajorVersion,
    requiredSoulFireVersion: options.requiredSoulFireVersion
      ?? supplied?.requiredSoulFireVersion
      ?? inferred.requiredSoulFireVersion,
    descriptorSha256,
    serviceNames,
    taskTypes: supplied?.taskTypes ?? inferred.taskTypes,
  };
  const messageTypes = indexPythonTypes(descriptorSet);
  const services = serviceModels(descriptorSet, serviceNames, messageTypes);
  const events = eventModels(messageTypes, metadata.eventTypeUrls);
  const tasks = taskModels(messageTypes, metadata.taskTypes);
  if (
    services.length === 0
    && events.length === 0
    && tasks.length === 0
    && metadata.taskTypeUrls.length === 0
  ) {
    throw new Error(
      "The descriptor set does not contain a plugin RPC service, event, or task",
    );
  }
  const sourceFiles = sourceClosure(
    descriptorSet.file,
    [
      ...services.map((service) => service.fileName),
      ...events.map((event) => event.messageType.fileName),
      ...tasks.flatMap((task) => [
        task.inputType.fileName,
        task.resultType.fileName,
        ...(task.progressType === undefined
          ? []
          : [task.progressType.fileName]),
      ]),
    ],
  );
  const safeId = packageSegment(pluginId);
  const typescriptPackageName = options.packageName
    ?? metadata.typescriptPackage
    ?? `soulfire-plugin-${safeId}`;
  const pythonPackageName = options.packageName
    ?? metadata.pythonPackage
    ?? `soulfire-plugin-${safeId}`;

  return {
    descriptorSet,
    metadata,
    services,
    events,
    tasks,
    sourceFiles,
    typescriptPackageName,
    pythonPackageName,
    pythonModuleName: `soulfire.plugin.${pythonIdentifier(safeId)}`,
  };
}

function inferMetadata(
  descriptorSet: FileDescriptorSet,
  descriptorSha256: string,
): PluginSdkMetadata {
  const services = descriptorSet.file.flatMap((file) =>
    file.service.map((service) => ({
      file,
      fullName: qualify(file.package, service.name),
    }))
  );
  const first = services[0];
  const packageParts = first?.file.package.split(".") ?? [];
  const pluginIndex = packageParts.indexOf("plugin");
  const pluginId = pluginIndex >= 0
    ? (packageParts[pluginIndex + 1] ?? "")
    : (packageParts.at(-2) ?? packageParts.at(-1) ?? "");
  const versionPart = packageParts.findLast((part) => /^v\d+$/.test(part));

  return {
    pluginId,
    pluginVersion: "0.1.0",
    description: `Generated SoulFire SDK for ${pluginId || "plugin"}`,
    author: "",
    license: "LicenseRef-Proprietary",
    requiredSoulFireVersion: "*",
    apiMajorVersion: versionPart === undefined
      ? 1
      : Number.parseInt(versionPart.slice(1), 10),
    descriptorSha256,
    serviceNames: services.map(({ fullName }) => fullName),
    eventTypeUrls: [],
    taskTypeUrls: [],
    taskTypes: [],
  };
}

function serviceModels(
  descriptorSet: FileDescriptorSet,
  allowedServices: readonly string[],
  messageTypes: ReadonlyMap<string, PythonType>,
): readonly ServiceModel[] {
  const allowed = new Set(allowedServices);
  return descriptorSet.file.flatMap((file) =>
    file.service.flatMap((service) => {
      const fullName = qualify(file.package, service.name);
      if (!allowed.has(fullName)) {
        return [];
      }
      return [{
        name: service.name,
        fullName,
        localName: lowerCamel(service.name),
        pythonName: snakeCase(service.name),
        fileName: file.name,
        methods: service.method.map((method) => ({
          name: method.name,
          localName: lowerCamel(method.name),
          pythonName: snakeCase(method.name),
          inputType: requirePythonType(messageTypes, method.inputType),
          outputType: requirePythonType(messageTypes, method.outputType),
          serverStreaming: method.serverStreaming,
        })),
      }];
    })
  );
}

function indexPythonTypes(
  descriptorSet: FileDescriptorSet,
): ReadonlyMap<string, PythonType> {
  const result = new Map<string, PythonType>();
  for (const file of descriptorSet.file) {
    const moduleName = protoPythonModule(file.name);
    const visit = (
      messages: FileDescriptorProto["messageType"],
      parentNames: readonly string[],
    ): void => {
      for (const message of messages) {
        const names = [...parentNames, message.name];
        result.set(
          `.${qualify(file.package, names.join("."))}`,
          {
            fileName: file.name,
            moduleName,
            importName: names[0] ?? message.name,
            expression: names.join("."),
            typescriptSchemaName: `${names.join("_")}Schema`,
          },
        );
        visit(message.nestedType, names);
      }
    };
    visit(file.messageType, []);
  }
  return result;
}

function eventModels(
  messageTypes: ReadonlyMap<string, PythonType>,
  typeUrls: readonly string[],
): readonly EventModel[] {
  return [...new Set(typeUrls)].map((typeUrl) => {
    const typeName = typeUrl.includes("/")
      ? typeUrl.slice(typeUrl.lastIndexOf("/") + 1)
      : typeUrl;
    const messageType = requirePythonType(messageTypes, `.${typeName}`);
    const simpleName = typeName.slice(typeName.lastIndexOf(".") + 1);
    return {
      typeUrl,
      localName: `${lowerCamel(simpleName)}Events`,
      pythonName: `${snakeCase(simpleName)}_events`,
      messageType,
    };
  });
}

function taskModels(
  messageTypes: ReadonlyMap<string, PythonType>,
  tasks: readonly PluginTaskMetadata[],
): readonly TaskModel[] {
  return tasks.map((task) => {
    const inputType = requirePythonType(
      messageTypes,
      `.${typeNameFromUrl(task.inputTypeUrl)}`,
    );
    const resultType = requirePythonType(
      messageTypes,
      `.${typeNameFromUrl(task.resultTypeUrl)}`,
    );
    const inputName = typeNameFromUrl(task.inputTypeUrl).split(".").at(-1)
      ?? "PluginTask";
    const methodName = inputName.replace(/Task$/, "") || inputName;
    const progressType = task.progressTypeUrl === undefined
      ? undefined
      : requirePythonType(
        messageTypes,
        `.${typeNameFromUrl(task.progressTypeUrl)}`,
      );
    return {
      inputTypeUrl: task.inputTypeUrl,
      resultTypeUrl: task.resultTypeUrl,
      ...(task.progressTypeUrl === undefined
        ? {}
        : { progressTypeUrl: task.progressTypeUrl }),
      localName: lowerCamel(methodName),
      pythonName: snakeCase(methodName),
      inputType,
      resultType,
      ...(progressType === undefined ? {} : { progressType }),
    };
  });
}

function typeNameFromUrl(typeUrl: string): string {
  return typeUrl.includes("/")
    ? typeUrl.slice(typeUrl.lastIndexOf("/") + 1)
    : typeUrl;
}

function requirePythonType(
  types: ReadonlyMap<string, PythonType>,
  fullName: string,
): PythonType {
  const type = types.get(fullName);
  if (type === undefined) {
    throw new Error(`RPC message type is missing from descriptor set: ${fullName}`);
  }
  return type;
}

function sourceClosure(
  files: readonly FileDescriptorProto[],
  roots: readonly string[],
): readonly string[] {
  const indexed = new Map(files.map((file) => [file.name, file]));
  const included = new Set<string>();
  const pending = [...roots];
  while (pending.length > 0) {
    const name = pending.pop();
    if (name === undefined || included.has(name) || isSharedSchema(name)) {
      continue;
    }
    const file = indexed.get(name);
    if (file === undefined) {
      throw new Error(`Descriptor dependency is missing: ${name}`);
    }
    included.add(name);
    pending.push(...file.dependency);
  }
  return [...included].sort();
}

async function generateTypeScript(
  outputDirectory: string,
  model: GenerationModel,
): Promise<void> {
  const packageJson = {
    name: model.typescriptPackageName,
    version: model.metadata.pluginVersion,
    packageManager: "bun@1.4.0",
    description: model.metadata.description,
    license: model.metadata.license,
    type: "module",
    sideEffects: false,
    files: ["dist", "README.md", "plugin-api.binpb", "soulfire-plugin.json"],
    exports: {
      ".": {
        types: "./dist/index.d.ts",
        import: "./dist/index.js",
      },
      "./promise": {
        types: "./dist/promise.d.ts",
        import: "./dist/promise.js",
      },
      "./generated/*": {
        types: "./dist/generated/*.d.ts",
        import: "./dist/generated/*.js",
      },
    },
    scripts: {
      build: "tsc -p tsconfig.json",
      check: "tsc -p tsconfig.json --noEmit",
    },
    peerDependencies: {
      "@effect/platform": "^0.97.1",
      "@soulfiremc/sdk": `^${SDK_VERSION}`,
      effect: "^3.22.1",
    },
    dependencies: {
      "@bufbuild/protobuf": "2.14.0",
      "@connectrpc/connect": "2.1.2",
    },
    devDependencies: {
      "@effect/platform": "0.97.1",
      "@soulfiremc/sdk": `^${SDK_VERSION}`,
      effect: "3.22.1",
      typescript: "^7.0.0",
    },
  };
  const tsconfig = {
    compilerOptions: {
      declaration: true,
      declarationMap: true,
      exactOptionalPropertyTypes: true,
      forceConsistentCasingInFileNames: true,
      lib: ["DOM", "ES2023", "ESNext.Disposable"],
      module: "NodeNext",
      moduleResolution: "NodeNext",
      noEmit: false,
      noUncheckedIndexedAccess: true,
      outDir: "dist",
      rootDir: "src",
      sourceMap: true,
      strict: true,
      target: "ES2023",
      verbatimModuleSyntax: true,
    },
    include: ["src/**/*.ts"],
  };
  await mkdir(join(outputDirectory, "src"), { recursive: true });
  await writeFile(
    join(outputDirectory, "package.json"),
    `${JSON.stringify(packageJson, null, 2)}\n`,
    "utf8",
  );
  await writeFile(
    join(outputDirectory, "tsconfig.json"),
    `${JSON.stringify(tsconfig, null, 2)}\n`,
    "utf8",
  );
  await writeFile(
    join(outputDirectory, "bunfig.toml"),
    '[install]\nlinker = "hoisted"\n',
    "utf8",
  );
  await writeFile(
    join(outputDirectory, "src", "index.ts"),
    typeScriptEffectSource(model),
    "utf8",
  );
  await writeFile(
    join(outputDirectory, "src", "promise.ts"),
    typeScriptPromiseSource(model),
    "utf8",
  );
  await writeFile(
    join(outputDirectory, "README.md"),
    typeScriptReadme(model),
    "utf8",
  );
  await runBufGenerate(
    outputDirectory,
    model.sourceFiles,
    {
      version: "v2",
      plugins: [{
        remote: "buf.build/bufbuild/es:v2.13.0",
        out: "src/generated",
        opt: ["target=ts", "import_extension=js"],
      }],
    },
  );
  await rewriteTypeScriptSharedImports(outputDirectory);
}

async function generatePython(
  outputDirectory: string,
  model: GenerationModel,
): Promise<void> {
  const moduleDirectory = join(
    outputDirectory,
    "src",
    ...model.pythonModuleName.split("."),
  );
  await mkdir(moduleDirectory, { recursive: true });
  const pyproject = `[build-system]
requires = ["hatchling>=1.27"]
build-backend = "hatchling.build"

[project]
name = ${JSON.stringify(model.pythonPackageName)}
version = ${JSON.stringify(model.metadata.pluginVersion)}
description = ${JSON.stringify(model.metadata.description)}
readme = "README.md"
requires-python = ">=3.14"
license = ${JSON.stringify(model.metadata.license)}
dependencies = [
  "soulfire>=${SDK_VERSION},<${nextMinorVersion(SDK_VERSION)}",
  "connectrpc>=0.11.1,<0.12",
  "googleapis-common-protos>=1.70,<2",
  "protobuf>=6.31.1,<8",
]

[tool.hatch.build.targets.wheel]
packages = ["src/soulfire"]

[tool.pyright]
include = ["src/soulfire"]
exclude = ["src/**/*_connect.py", "src/**/*_pb2.py", "src/**/*_pb2.pyi"]
ignore = ["src/**/*_connect.py", "src/**/*_pb2.py", "src/**/*_pb2.pyi"]
pythonVersion = "3.14"
typeCheckingMode = "strict"

[tool.ruff]
extend-exclude = ["src/**/*_connect.py", "src/**/*_pb2.py", "src/**/*_pb2.pyi"]
line-length = 100
target-version = "py314"

[tool.ruff.lint]
select = ["E", "F", "I", "UP", "B", "SIM", "RUF"]
`;
  await writeFile(join(outputDirectory, "pyproject.toml"), pyproject, "utf8");
  await writeFile(
    join(moduleDirectory, "sdk.py"),
    pythonSdkSource(model),
    "utf8",
  );
  await writeFile(
    join(moduleDirectory, "__init__.py"),
    pythonInitSource(model),
    "utf8",
  );
  await writeFile(
    join(moduleDirectory, "py.typed"),
    "",
    "utf8",
  );
  await writeFile(
    join(moduleDirectory, "plugin-api.binpb"),
    await readFile(join(outputDirectory, "plugin-api.binpb")),
  );
  await writeFile(
    join(moduleDirectory, "soulfire-plugin.json"),
    await readFile(join(outputDirectory, "soulfire-plugin.json")),
  );
  await writeFile(
    join(outputDirectory, "README.md"),
    pythonReadme(model),
    "utf8",
  );
  await runBufGenerate(
    outputDirectory,
    model.sourceFiles,
    {
      version: "v2",
      plugins: [
        {
          remote: "buf.build/protocolbuffers/python:v31.1",
          out: "src",
        },
        {
          remote: "buf.build/protocolbuffers/pyi:v31.1",
          out: "src",
        },
        {
          remote: "buf.build/connectrpc/py:v0.11.1",
          out: "src",
          opt: ["protobuf=google"],
        },
      ],
    },
  );
}

async function runBufGenerate(
  outputDirectory: string,
  sourceFiles: readonly string[],
  template: unknown,
): Promise<void> {
  const descriptorPath = join(outputDirectory, "plugin-api.binpb");
  const executable = resolveBufExecutable();
  const arguments_ = [
    "generate",
    descriptorPath,
    "--template",
    JSON.stringify(template),
    ...sourceFiles.flatMap((file) => ["--path", file]),
  ];
  try {
    await executeFile(executable, arguments_, {
      cwd: outputDirectory,
      maxBuffer: 16 * 1024 * 1024,
    });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`Buf code generation failed: ${detail}`, { cause: error });
  }
}

function resolveBufExecutable(): string {
  const platformPackages: Readonly<Record<string, string>> = {
    "darwin-arm64": "@bufbuild/buf-darwin-arm64",
    "darwin-x64": "@bufbuild/buf-darwin-x64",
    "linux-arm64": "@bufbuild/buf-linux-aarch64",
    "linux-arm": "@bufbuild/buf-linux-armv7",
    "linux-x64": "@bufbuild/buf-linux-x64",
    "win32-arm64": "@bufbuild/buf-win32-arm64",
    "win32-x64": "@bufbuild/buf-win32-x64",
  };
  const key = `${process.platform}-${process.arch}`;
  const packageName = platformPackages[key];
  if (packageName === undefined) {
    throw new Error(
      `The SoulFire SDK generator does not support ${process.platform} ${process.arch}`,
    );
  }
  try {
    const packagePath = require.resolve(`${packageName}/package.json`);
    return join(
      dirname(packagePath),
      "bin",
      process.platform === "win32" ? "buf.exe" : "buf",
    );
  } catch (error) {
    throw new Error(
      `The optional Buf binary package ${packageName} is unavailable. Reinstall @soulfiremc/sdk with optional dependencies enabled.`,
      { cause: error },
    );
  }
}

function typeScriptEffectSource(model: GenerationModel): string {
  const imports = typeScriptImports(model);
  const clients = model.services.map(effectServiceSource).join("\n\n");
  const fields = model.services.map((service) =>
    `    private readonly ${service.localName}: ${service.name}Client,`
  ).join("\n");
  const constructors = model.services.map((service) =>
    `new ${service.name}Client(
  Effect.runSync(catalog.service(PLUGIN_ID, ${service.name})),
),`
  ).join("\n");
  const extensionMethods = [
    extensionMethodSources(model.services),
    eventMethodSources(model.events),
    taskMethodSources(model.tasks),
  ].filter((source) => source.length > 0).join("\n\n");
  const compatibilityChecks = [
    ...model.services.map((service) =>
      `descriptor.services.some((service) =>
      service.fullName === ${JSON.stringify(service.fullName)}
    )`
    ),
    ...model.events.map((event) =>
      `descriptor.eventTypeUrls.includes(${JSON.stringify(event.typeUrl)})`
    ),
    ...model.tasks.map((task) =>
      `descriptor.taskTypes.some((task) =>
      task.inputTypeUrl === ${JSON.stringify(task.inputTypeUrl)}
      && task.resultTypeUrl === ${JSON.stringify(task.resultTypeUrl)}
    )`
    ),
  ].join("\n    && ") || "true";

  return `import type { MessageInitShape } from "@bufbuild/protobuf";
import type { Client } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import {
  type EffectPluginCatalog,
  type EffectSoulFireTask,
  type EffectSoulFireTasks,
  defineSoulFirePlugin,
  SoulFireExtensionTypeId,
  type SoulFirePluginModule,
  SoulFirePluginError,
  type SoulFireRpcError,
  type SoulFireTaskFailed,
  type TaskStartOptions,
  type TypedPluginEvent,
  type WatchPluginEventOptions,
} from "@soulfiremc/sdk";
${imports}

const PLUGIN_ID = ${JSON.stringify(model.metadata.pluginId)};
const API_MAJOR_VERSION = ${model.metadata.apiMajorVersion};

type StreamValue<T> = T extends AsyncIterable<infer Value> ? Value : never;

${clients}

export class ${pascalCase(model.metadata.pluginId)}PluginClient {
  public readonly [SoulFireExtensionTypeId] = true;

  public constructor(
    private readonly catalog: EffectPluginCatalog,
${fields}
  ) {}

${extensionMethods}
}

export const plugin: SoulFirePluginModule<${pascalCase(model.metadata.pluginId)}PluginClient> = defineSoulFirePlugin({
  pluginId: PLUGIN_ID,
  isCompatible: (descriptor) =>
    descriptor.apiMajorVersion === API_MAJOR_VERSION
    && ${compatibilityChecks},
  create(catalog: EffectPluginCatalog) {
    return new ${pascalCase(model.metadata.pluginId)}PluginClient(
      catalog,
${indent(constructors, 6)}
    );
  },
});
`;
}

function typeScriptImports(model: GenerationModel): string {
  const groups = new Map<string, Set<string>>();
  for (const service of model.services) {
    addImport(groups, service.fileName, service.name);
  }
  for (const event of model.events) {
    addImport(
      groups,
      event.messageType.fileName,
      event.messageType.typescriptSchemaName,
    );
  }
  for (const task of model.tasks) {
    addImport(
      groups,
      task.inputType.fileName,
      task.inputType.typescriptSchemaName,
    );
    addImport(
      groups,
      task.resultType.fileName,
      task.resultType.typescriptSchemaName,
    );
    if (task.progressType !== undefined) {
      addImport(
        groups,
        task.progressType.fileName,
        task.progressType.typescriptSchemaName,
      );
    }
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([fileName, names]) =>
      `import { ${[...names].sort().join(", ")} } from "./generated/${
        protoTypeScriptModule(fileName)
      }";`
    )
    .join("\n");
}

function taskMethodSources(tasks: readonly TaskModel[]): string {
  return tasks.map((task) =>
    `  public ${task.localName}(
    tasks: EffectSoulFireTasks,
    input: MessageInitShape<typeof ${task.inputType.typescriptSchemaName}>,
    options: TaskStartOptions = {},
  ): Effect.Effect<
    EffectSoulFireTask<typeof ${task.resultType.typescriptSchemaName}>,
    SoulFireRpcError | SoulFireTaskFailed
  > {
    return tasks.start(
      ${task.inputType.typescriptSchemaName},
      input,
      ${task.resultType.typescriptSchemaName},
      options,
    );
  }`
  ).join("\n\n");
}

function eventMethodSources(events: readonly EventModel[]): string {
  return events.map((event) =>
    `  public ${event.localName}(
    options: Omit<WatchPluginEventOptions, "pluginIds" | "typeUrls"> = {},
  ): Stream.Stream<
    TypedPluginEvent<typeof ${event.messageType.typescriptSchemaName}>,
    SoulFirePluginError
  > {
    return this.catalog.typedEvents(
      PLUGIN_ID,
      ${event.messageType.typescriptSchemaName},
      options,
    );
  }`
  ).join("\n\n");
}

function effectServiceSource(service: ServiceModel): string {
  const methods = service.methods.map((method) => {
    const request = `Parameters<Client<typeof ${service.name}>[${JSON.stringify(method.localName)}]>[0]`;
    if (method.serverStreaming) {
      return `  public ${method.localName}(
    request: ${request},
  ): Stream.Stream<
    StreamValue<ReturnType<Client<typeof ${service.name}>[${JSON.stringify(method.localName)}]>>,
    SoulFirePluginError
  > {
    return Stream.fromAsyncIterable(
      this.client.${method.localName}(request),
      (cause) => new SoulFirePluginError({ pluginId: PLUGIN_ID, cause }),
    );
  }`;
    }
    return `  public ${method.localName}(
    request: ${request},
  ): Effect.Effect<
    Awaited<ReturnType<Client<typeof ${service.name}>[${JSON.stringify(method.localName)}]>>,
    SoulFirePluginError
  > {
    return Effect.tryPromise({
      try: () => this.client.${method.localName}(request),
      catch: (cause) => new SoulFirePluginError({ pluginId: PLUGIN_ID, cause }),
    });
  }`;
  }).join("\n\n");
  return `export class ${service.name}Client {
  public constructor(
    private readonly client: Client<typeof ${service.name}>,
  ) {}

${methods}
}`;
}

function typeScriptPromiseSource(model: GenerationModel): string {
  void model;
  return `// Promise clients use the same plugin module. The official SDK maps every
// Effect and Stream method on the extension to Promise and AsyncIterable.
export { plugin } from "./index.js";
`;
}

function pythonSdkSource(model: GenerationModel): string {
  const imports = pythonImports(model);
  const runtimeImports = pythonRuntimeImports(model);
  const clients = model.services.map(pythonServiceSource).join("\n\n\n");
  const className = `${pascalCase(model.metadata.pluginId)}PluginClient`;
  const asyncClass = pythonPluginClientClass(model, className, true);
  const syncClass = pythonPluginClientClass(model, className, false);
  const compatibility = pythonCompatibilityReturn(model);
  const definitions = [
    clients,
    asyncClass,
    syncClass,
    `def _is_compatible(descriptor: PluginApiDescriptor) -> bool:
${compatibility}`,
  ].filter((value) => value.length > 0).join("\n\n\n");

  return `from __future__ import annotations

${runtimeImports}

${imports}

PLUGIN_ID = ${JSON.stringify(model.metadata.pluginId)}
API_MAJOR_VERSION = ${model.metadata.apiMajorVersion}


${definitions}


class _AsyncPluginModule:
    plugin_id = PLUGIN_ID

    @staticmethod
    def is_compatible(descriptor: PluginApiDescriptor) -> bool:
        return _is_compatible(descriptor)

    @staticmethod
    def create(
        catalog: AsyncPluginCatalog,
        _: PluginApiDescriptor,
    ) -> Async${className}:
        return Async${className}(catalog)


class _PluginModule:
    plugin_id = PLUGIN_ID

    @staticmethod
    def is_compatible(descriptor: PluginApiDescriptor) -> bool:
        return _is_compatible(descriptor)

    @staticmethod
    def create(
        catalog: PluginCatalog,
        _: PluginApiDescriptor,
    ) -> ${className}:
        return ${className}(catalog)


async_plugin = _AsyncPluginModule()
plugin = _PluginModule()
`;
}

function pythonPluginClientClass(
  model: GenerationModel,
  className: string,
  asynchronous: boolean,
): string {
  const catalog = asynchronous ? "AsyncPluginCatalog" : "PluginCatalog";
  const prefix = asynchronous ? "Async" : "";
  const fields = model.services.map((service) =>
    pythonPluginClientField(service, asynchronous)
  ).join("\n");
  const constructor = `    def __init__(self, catalog: ${catalog}) -> None:
        self._catalog = catalog${fields.length === 0 ? "" : `\n${fields}`}`;
  const sections = [
    constructor,
    pythonEventMethods(model.events, asynchronous),
    pythonTaskMethods(model.tasks, asynchronous),
  ].filter((value) => value.length > 0).join("\n\n");
  return `class ${prefix}${className}:
    __slots__ = (${pythonSlots(model.services)})

${sections}`;
}

function pythonPluginClientField(
  service: ServiceModel,
  asynchronous: boolean,
): string {
  const type = asynchronous
    ? `Async${service.name}Client`
    : `${service.name}ClientFacade`;
  const client = asynchronous
    ? `${service.name}Client`
    : `${service.name}ClientSync`;
  const invocation = `${type}(catalog.service(${client}))`;
  const value = invocation.length <= 88
    ? invocation
    : `${type}(
                catalog.service(${client})
            )`;
  return `        self.${service.pythonName}: ${type} = (
            ${value}
        )`;
}

function pythonCompatibilityReturn(model: GenerationModel): string {
  const predicates: Array<
    | { readonly kind: "service"; readonly value: string }
    | { readonly kind: "event"; readonly value: string }
    | {
      readonly input: string;
      readonly kind: "task";
      readonly result: string;
    }
  > = [
    ...model.services.map((service) => ({
      kind: "service" as const,
      value: service.fullName,
    })),
    ...model.events.map((event) => ({
      kind: "event" as const,
      value: event.typeUrl,
    })),
    ...model.tasks.map((task) => ({
      input: task.inputTypeUrl,
      kind: "task" as const,
      result: task.resultTypeUrl,
    })),
  ];
  const apiVersion =
    "descriptor.api_major_version == API_MAJOR_VERSION";
  if (predicates.length === 0) {
    return `    return ${apiVersion}`;
  }
  if (predicates.length === 1) {
    return `    return ${apiVersion} and ${
      pythonCompatibilityPredicate(predicates[0]!, 4)
    }`;
  }
  return `    return (
        ${apiVersion}
${predicates.map((predicate) =>
    `        and ${pythonCompatibilityPredicate(predicate, 8)}`
  ).join("\n")}
    )`;
}

function pythonCompatibilityPredicate(
  predicate:
    | { readonly kind: "service"; readonly value: string }
    | { readonly kind: "event"; readonly value: string }
    | {
      readonly input: string;
      readonly kind: "task";
      readonly result: string;
    },
  indentation: number,
): string {
  if (predicate.kind === "event") {
    return `${
      JSON.stringify(predicate.value)
    } in descriptor.event_type_urls`;
  }
  const indent = " ".repeat(indentation);
  const body = " ".repeat(indentation + 4);
  if (predicate.kind === "service") {
    return `any(
${body}service.full_name == ${JSON.stringify(predicate.value)}
${body}for service in descriptor.services
${indent})`;
  }
  return `any(
${body}task.input_type_url == ${JSON.stringify(predicate.input)}
${body}and task.result_type_url == ${JSON.stringify(predicate.result)}
${body}for task in descriptor.task_types
${indent})`;
}

function pythonRuntimeImports(model: GenerationModel): string {
  const hasIterators = model.events.length > 0
    || model.services.some((service) =>
      service.methods.some((method) => method.serverStreaming)
    );
  const collectionImports = hasIterators
    ? "from collections.abc import AsyncIterator, Iterator"
    : "";
  const typingImports = model.tasks.length > 0
    ? "from typing import Unpack"
    : "";
  const pluginImports = model.events.length > 0
    ? `from soulfire.plugins import (
    AsyncPluginCatalog,
    PluginCatalog,
    TypedPluginEvent,
)`
    : "from soulfire.plugins import AsyncPluginCatalog, PluginCatalog";
  const taskImports = model.tasks.length > 0
    ? `from soulfire.tasks import (
    AsyncSoulFireTask,
    AsyncSoulFireTasks,
    SoulFireTask,
    SoulFireTasks,
    TaskStartOptions,
)`
    : "";
  const standardLibrary = [
    collectionImports,
    typingImports,
  ].filter((value) => value.length > 0).join("\n");
  const soulfire = [
    "from soulfire.plugin_api_pb2 import PluginApiDescriptor",
    pluginImports,
    taskImports,
  ].filter((value) => value.length > 0).join("\n");
  return [standardLibrary, soulfire]
    .filter((value) => value.length > 0)
    .join("\n\n");
}

function pythonImports(model: GenerationModel): string {
  const groups = new Map<string, Set<string>>();
  for (const service of model.services) {
    const connect = pythonImportModule(
      model,
      protoPythonConnectModule(service.fileName),
    );
    addImport(groups, connect, `${service.name}Client`);
    addImport(groups, connect, `${service.name}ClientSync`);
    for (const method of service.methods) {
      addImport(
        groups,
        pythonImportModule(model, method.inputType.moduleName),
        method.inputType.importName,
      );
      addImport(
        groups,
        pythonImportModule(model, method.outputType.moduleName),
        method.outputType.importName,
      );
    }
  }
  for (const event of model.events) {
    addImport(
      groups,
      pythonImportModule(model, event.messageType.moduleName),
      event.messageType.importName,
    );
  }
  for (const task of model.tasks) {
    addImport(
      groups,
      pythonImportModule(model, task.inputType.moduleName),
      task.inputType.importName,
    );
    addImport(
      groups,
      pythonImportModule(model, task.resultType.moduleName),
      task.resultType.importName,
    );
    if (task.progressType !== undefined) {
      addImport(
        groups,
        pythonImportModule(model, task.progressType.moduleName),
        task.progressType.importName,
      );
    }
  }
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))
    .map(([module, names]) => {
      const sorted = [...names].sort();
      return sorted.length === 1
        ? `from ${module} import ${sorted[0]}`
        : `from ${module} import (\n${
          sorted.map((name) => `    ${name},`).join("\n")
        }\n)`;
    })
    .join("\n");
}

function addImport(
  imports: Map<string, Set<string>>,
  module: string,
  name: string,
): void {
  const names = imports.get(module) ?? new Set<string>();
  names.add(name);
  imports.set(module, names);
}

function pythonServiceSource(service: ServiceModel): string {
  const asyncMethods = service.methods.map((method) => {
    const returnType = method.serverStreaming
      ? `AsyncIterator[${method.outputType.expression}]`
      : method.outputType.expression;
    const prefix = method.serverStreaming ? "def" : "async def";
    const await_ = method.serverStreaming ? "" : "await ";
    return `    ${prefix} ${method.pythonName}(
        self,
        request: ${method.inputType.expression},
        *,
        timeout_ms: int | None = None,
    ) -> ${returnType}:
        return ${await_}self._client.${method.pythonName}(
            request,
            timeout_ms=timeout_ms,
        )`;
  }).join("\n\n");
  const syncMethods = service.methods.map((method) => {
    const returnType = method.serverStreaming
      ? `Iterator[${method.outputType.expression}]`
      : method.outputType.expression;
    return `    def ${method.pythonName}(
        self,
        request: ${method.inputType.expression},
        *,
        timeout_ms: int | None = None,
    ) -> ${returnType}:
        return self._client.${method.pythonName}(
            request,
            timeout_ms=timeout_ms,
        )`;
  }).join("\n\n");
  return `class Async${service.name}Client:
    __slots__ = ("_client",)

    def __init__(self, client: ${service.name}Client) -> None:
        self._client = client

${asyncMethods}


class ${service.name}ClientFacade:
    __slots__ = ("_client",)

    def __init__(self, client: ${service.name}ClientSync) -> None:
        self._client = client

${syncMethods}`;
}

function pythonEventMethods(
  events: readonly EventModel[],
  asynchronous: boolean,
): string {
  const iterator = asynchronous ? "AsyncIterator" : "Iterator";
  return events.map((event) =>
    `    def ${event.pythonName}(
        self,
        *,
        instance_id: str | None = None,
        bot_id: str | None = None,
        task_id: str | None = None,
        after_sequence: int = 0,
        timeout_ms: int | None = None,
    ) -> ${iterator}[TypedPluginEvent[${event.messageType.expression}]]:
        return self._catalog.typed_events(
            PLUGIN_ID,
            ${event.messageType.expression},
            instance_id=instance_id,
            bot_id=bot_id,
            task_id=task_id,
            after_sequence=after_sequence,
            timeout_ms=timeout_ms,
        )`
  ).join("\n\n");
}

function pythonTaskMethods(
  tasks: readonly TaskModel[],
  asynchronous: boolean,
): string {
  const tasksType = asynchronous ? "AsyncSoulFireTasks" : "SoulFireTasks";
  const taskType = asynchronous ? "AsyncSoulFireTask" : "SoulFireTask";
  const prefix = asynchronous ? "async def" : "def";
  const await_ = asynchronous ? "await " : "";
  return tasks.map((task) =>
    `    ${prefix} ${task.pythonName}(
        self,
        tasks: ${tasksType},
        task_input: ${task.inputType.expression},
        **options: Unpack[TaskStartOptions],
    ) -> ${taskType}[${task.resultType.expression}]:
        return ${await_}tasks.start(
            task_input,
            ${task.resultType.expression},
            **options,
        )`
  ).join("\n\n");
}

function pythonInitSource(model: GenerationModel): string {
  const className = `${pascalCase(model.metadata.pluginId)}PluginClient`;
  const exports = [
    `Async${className}`,
    className,
    "async_plugin",
    "plugin",
    ...model.services.flatMap((service) => [
      `Async${service.name}Client`,
      `${service.name}ClientFacade`,
    ]),
  ].sort();
  return `from .sdk import (
${exports.map((name) => `    ${name},`).join("\n")}
)

__all__ = [
${exports.map((name) => `    ${JSON.stringify(name)},`).join("\n")}
]
`;
}

function typeScriptReadme(model: GenerationModel): string {
  return `# ${model.typescriptPackageName}

Typed TypeScript companion SDK for the SoulFire \`${model.metadata.pluginId}\` plugin.

The default entry point is Effect-first. Import \`${model.typescriptPackageName}/promise\` when an application uses Promises and async iterables.

\`\`\`ts
import { Effect } from "effect";
import { SoulFire } from "@soulfiremc/sdk";
import { plugin } from "${model.typescriptPackageName}";

const program = Effect.scoped(
  Effect.gen(function* () {
    const soulfire = yield* SoulFire.connect({
      baseUrl: process.env.SOULFIRE_URL!,
      token: process.env.SOULFIRE_TOKEN,
    });
    const extension = yield* soulfire.plugins.require(plugin);
    return extension;
  }),
);
\`\`\`

This package requires plugin API major version ${model.metadata.apiMajorVersion}. Regenerate it whenever the plugin descriptor changes.
`;
}

function pythonReadme(model: GenerationModel): string {
  return `# ${model.pythonPackageName}

Typed Python 3.14 companion SDK for the SoulFire \`${model.metadata.pluginId}\` plugin.

Both async and sync plugin modules are available:

\`\`\`python
from ${model.pythonModuleName} import async_plugin, plugin

extension = client.plugins.require(plugin)
async_extension = async_client.plugins.require(async_plugin)
\`\`\`

This package requires plugin API major version ${model.metadata.apiMajorVersion}. Regenerate it whenever the plugin descriptor changes.
`;
}

async function inspectOutput(
  outputDirectory: string,
): Promise<"missing" | "empty"> {
  try {
    await access(outputDirectory);
  } catch {
    return "missing";
  }
  const entries = await readdir(outputDirectory);
  if (entries.length > 0) {
    throw new Error(
      `Output directory is not empty: ${outputDirectory}. Choose a new directory.`,
    );
  }
  return "empty";
}

function isSharedSchema(name: string): boolean {
  return name.startsWith("google/")
    || /^soulfire\/[^/]+\.proto$/.test(name);
}

function qualify(packageName: string, name: string): string {
  return packageName.length === 0 ? name : `${packageName}.${name}`;
}

function protoTypeScriptModule(name: string): string {
  return `${name.replace(/\.proto$/, "")}_pb.js`;
}

function protoPythonModule(name: string): string {
  return `${name.replace(/\.proto$/, "")}_pb2`.replaceAll("/", ".");
}

function protoPythonConnectModule(name: string): string {
  return `${name.replace(/\.proto$/, "")}_connect`.replaceAll("/", ".");
}

function lowerCamel(value: string): string {
  if (value.length === 0) {
    return value;
  }
  return `${value[0]?.toLowerCase()}${value.slice(1)}`;
}

function snakeCase(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1_$2")
    .replaceAll("-", "_")
    .toLowerCase();
}

function pascalCase(value: string): string {
  return value
    .split(/[^a-zA-Z0-9]+/)
    .filter((part) => part.length > 0)
    .map((part) => `${part[0]?.toUpperCase()}${part.slice(1)}`)
    .join("") || "SoulFire";
}

function packageSegment(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function nextMinorVersion(version: string): string {
  const [majorText, minorText] = version.split(".");
  const major = Number.parseInt(majorText ?? "", 10);
  const minor = Number.parseInt(minorText ?? "", 10);
  if (!Number.isSafeInteger(major) || !Number.isSafeInteger(minor)) {
    throw new Error(`Invalid SoulFire SDK version: ${version}`);
  }
  return `${major}.${minor + 1}`;
}

function pythonIdentifier(value: string): string {
  const normalized = value.replace(/[^a-zA-Z0-9_]+/g, "_");
  return /^\d/.test(normalized) ? `plugin_${normalized}` : normalized;
}

function pythonSlots(services: readonly ServiceModel[]): string {
  const slots = ["_catalog", ...services.map((service) => service.pythonName)];
  if (slots.length === 1) {
    return `${JSON.stringify(slots[0])},`;
  }
  return slots.map((slot) => JSON.stringify(slot)).join(", ");
}

function indent(value: string, spaces: number): string {
  const prefix = " ".repeat(spaces);
  return value.split("\n").map((line) => `${prefix}${line}`).join("\n");
}

function extensionMethodSources(
  services: readonly ServiceModel[],
): string {
  const counts = new Map<string, number>();
  for (const service of services) {
    for (const method of service.methods) {
      counts.set(method.localName, (counts.get(method.localName) ?? 0) + 1);
    }
  }
  return services.flatMap((service) =>
    service.methods.map((method) => {
      const publicName = counts.get(method.localName) === 1
        ? method.localName
        : `${service.localName}${pascalCase(method.name)}`;
      return `  public ${publicName}(
    request: Parameters<${service.name}Client[${JSON.stringify(method.localName)}]>[0],
  ): ReturnType<${service.name}Client[${JSON.stringify(method.localName)}]> {
    return this.${service.localName}.${method.localName}(request);
  }`;
    })
  ).join("\n\n");
}

function pythonImportModule(
  model: GenerationModel,
  fullName: string,
): string {
  const prefix = `${model.pythonModuleName}.`;
  if (fullName.startsWith(prefix)) {
    return `.${fullName.slice(prefix.length)}`;
  }
  if (
    fullName.startsWith("soulfire.")
    || fullName.startsWith("google.protobuf.")
  ) {
    return fullName;
  }
  throw new Error(
    `Plugin RPC module ${fullName} is neither part of the generated package nor a supported shared schema`,
  );
}

async function rewriteTypeScriptSharedImports(
  outputDirectory: string,
): Promise<void> {
  const generatedRoot = join(outputDirectory, "src", "generated");
  const files = await listTypeScriptFiles(generatedRoot);
  const existingFiles = new Set(files);
  for (const file of files) {
    const source = await readFile(file, "utf8");
    let changed = false;
    const rewritten = source.replace(
      /from "(\.[^"]+_pb\.js)"/g,
      (match, specifier: string) => {
        const target = resolve(dirname(file), specifier.replace(/\.js$/, ".ts"));
        if (existingFiles.has(target)) {
          return match;
        }
        const modulePath = relative(generatedRoot, target)
          .replaceAll("\\", "/")
          .replace(/\.ts$/, "");
        if (modulePath.startsWith("../")) {
          throw new Error(
            `Generated import escapes its output directory: ${specifier}`,
          );
        }
        changed = true;
        return `from "@soulfiremc/sdk/generated/${modulePath}"`;
      },
    );
    if (changed) {
      await writeFile(file, rewritten, "utf8");
    }
  }
}

async function listTypeScriptFiles(
  directory: string,
): Promise<readonly string[]> {
  const result: string[] = [];
  const visit = async (current: string): Promise<void> => {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(path);
      } else if (entry.isFile() && entry.name.endsWith(".ts")) {
        result.push(path);
      }
    }
  };
  await visit(directory);
  return result;
}
