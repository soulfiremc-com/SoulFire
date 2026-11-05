import { create } from "@bufbuild/protobuf";
import { StructSchema, ValueSchema } from "@bufbuild/protobuf/wkt";
import {
  beatGameWithDriver,
  BeatGamePhase,
  BeatGameRunStatus,
  decideBeatGameAction,
  defaultBeatGameStrategy,
  makeSoulFireBeatGameDriver,
  type BeatGameBlockSelector,
  type BeatGameDriver,
  type BeatGameEntitySelector,
  type BeatGameEvent,
  type BeatGamePathPolicy,
  type BeatGamePosition,
  type BeatGameQueryBlocks,
  type BeatGameQueryEntities,
  type BeatGameRaycastQuery,
  type BeatGameRun,
  type BeatGameStrategy,
} from "../dist/index.js";
import { JsonFileBeatGameCheckpointStore } from "../dist/node.js";
import {
  MinecraftAccountProto_AccountTypeProto,
  MinecraftAccountProto_OfflineJavaDataSchema,
  MinecraftAccountProtoSchema,
  SettingsNamespace_SettingsEntrySchema,
  SettingsNamespaceSchema,
} from "@soulfiremc/sdk/generated/soulfire/common_pb";
import { goals, type SoulFireBot } from "@soulfiremc/sdk";
import { SoulFire } from "@soulfiremc/sdk/node";
import * as NodeRuntime from "@effect/platform-node/NodeRuntime";
import { execFile as execFileCallback } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import {
  chmod,
  lstat,
  mkdir,
  readFile,
  readlink,
  readdir,
  realpath,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { Cause, Duration, Effect, Exit, Fiber, Ref, Schedule, Scope, Stream } from "effect";
import { pruneArtifactRuns } from "./artifact-retention.ts";
import { BoundedLog } from "./bounded-log.ts";
import {
  SmokeDebugRequestError,
  type SmokeDebugOperation,
  SmokeDebugTimeline,
  startSmokeDebugServer,
} from "./debug-server.ts";
import { decodeSmokeTaskProgress } from "./debug-task-progress.ts";
import {
  buildSmokeActivePathDiagnostics,
  buildSmokeDecisionDiagnostics,
  buildSmokeSpatialDiagnostics,
  buildSmokeStuckDiagnostics,
  summarizeSmokeEnvironment,
  summarizeSmokeSpatialDiagnostics,
  type SmokeActivePathTrace,
} from "./debug-diagnostics.ts";

const execFile = promisify(execFileCallback);
const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const runId = environment("SOULFIRE_E2E_RUN_ID", `beat-game-e2e-${randomUUID()}`);
const artifactRootDirectory = path.join(repositoryRoot, "temp", "beat-game-e2e");
const configuredArtifactDirectory = optionalEnvironment("SOULFIRE_E2E_ARTIFACT_DIR");
const artifactDirectory = path.resolve(
  configuredArtifactDirectory ?? path.join(artifactRootDirectory, runId),
);
const minecraftDataRootDirectory = path.resolve(
  environment(
    "SOULFIRE_E2E_MINECRAFT_DATA_ROOT",
    path.join(repositoryRoot, "temp", "beat-game-minecraft"),
  ),
);
const soulfireDownloadCacheDirectory = path.resolve(
  environment(
    "SOULFIRE_E2E_SOULFIRE_DOWNLOAD_CACHE",
    path.join(repositoryRoot, "temp", "beat-game-e2e-cache", "mc-downloads"),
  ),
);
const soulfireRuntimeDirectory = path.resolve(
  environment(
    "SOULFIRE_E2E_SOULFIRE_RUNTIME",
    path.join(repositoryRoot, "temp", "beat-game-e2e-cache", "soulfire-runtime"),
  ),
);
const soulfireProcessFile = path.join(soulfireRuntimeDirectory, "process.json");
const botName = environment("SOULFIRE_E2E_BOT_NAME", "SFSmokeBot");
const smokeMode = booleanEnvironment("SOULFIRE_E2E_CONTROLLED", false) ? "controlled" : "survival";
const debugBlockBreak = booleanEnvironment("SOULFIRE_E2E_DEBUG_BLOCK_BREAK", false);
const debugWorldNeighborhood = booleanEnvironment("SOULFIRE_E2E_DEBUG_WORLD_NEIGHBORHOOD", false);
const debugApiEvalEnabled = booleanEnvironment("SOULFIRE_E2E_DEBUG_EVAL", false);
const debugApiEnabled = debugApiEvalEnabled || booleanEnvironment("SOULFIRE_E2E_DEBUG_API", false);
const debugApiPort = positiveIntegerEnvironment("SOULFIRE_E2E_DEBUG_PORT", 25_566);
const debugApiTimelineEntries = positiveIntegerEnvironment(
  "SOULFIRE_E2E_DEBUG_TIMELINE_ENTRIES",
  4_000,
);
const debugBlockQueryResultLimit = 32;
const verboseOutput = booleanEnvironment("SOULFIRE_E2E_VERBOSE", false);
const longTravelGate = booleanEnvironment("SOULFIRE_E2E_LONG_TRAVEL", smokeMode === "controlled");
const minecraftPort = 25_565;
const attachedMinecraftContainer = optionalEnvironment("SOULFIRE_E2E_MINECRAFT_CONTAINER");
const timeoutMs = positiveIntegerEnvironment(
  "SOULFIRE_E2E_TIMEOUT_MS",
  smokeMode === "controlled" ? 45 * 60 * 1_000 : 8 * 60 * 60 * 1_000,
);
const artifactLogMaximumBytes = positiveIntegerEnvironment(
  "SOULFIRE_E2E_ARTIFACT_LOG_MAX_BYTES",
  32 * 1024 * 1024,
);
const artifactLogFiles = positiveIntegerEnvironment("SOULFIRE_E2E_ARTIFACT_LOG_FILES", 3);
const artifactRuns = positiveIntegerEnvironment("SOULFIRE_E2E_ARTIFACT_RUNS", 4);
const debugTimeline = new SmokeDebugTimeline(debugApiTimelineEntries);
const debugTimelineOmittedKinds = new Set(["inventory-observed"]);
let activeDebugActionContext: SmokeDebugActionContext | undefined;
const eventLog = new BoundedLog(path.join(artifactDirectory, "events.ndjson"), {
  maximumBytes: artifactLogMaximumBytes,
  files: artifactLogFiles,
});
const soulfireLog = new BoundedLog(path.join(artifactDirectory, "soulfire.log"), {
  maximumBytes: artifactLogMaximumBytes,
  files: artifactLogFiles,
});
const minecraftLog = new BoundedLog(path.join(artifactDirectory, "minecraft.log"), {
  maximumBytes: artifactLogMaximumBytes,
  files: artifactLogFiles,
});
const fixtureConfiguration = {
  mode: smokeMode,
  image: environment("SOULFIRE_E2E_IMAGE", "itzg/minecraft-server:2026.3.2-java25"),
  minecraftVersion: environment("SOULFIRE_E2E_MINECRAFT_VERSION", "1.21.11"),
  seed:
    smokeMode === "controlled"
      ? environment("SOULFIRE_E2E_SEED", "SoulFire SDK controlled e2e")
      : optionalEnvironment("SOULFIRE_E2E_SEED"),
  attachedMinecraftContainer,
  keepContainer: booleanEnvironment("SOULFIRE_E2E_KEEP_CONTAINER", false),
} as const;

interface CommandResult {
  readonly stdout: string;
  readonly stderr: string;
}

interface SoulFireProcessRecord {
  readonly jarPath: string;
  readonly pid: number;
  readonly runDirectory: string;
}

interface BaseMinecraftFixture {
  readonly containerName: string;
  readonly dataDirectory?: string;
  readonly freshWorld: boolean;
  readonly managed: boolean;
  readonly port: number;
}

interface SurvivalMinecraftFixture extends BaseMinecraftFixture {
  readonly mode: "survival";
  readonly seed: string | undefined;
}

interface ControlledMinecraftFixture extends BaseMinecraftFixture {
  readonly mode: "controlled";
  readonly seed: string;
  readonly stronghold: Readonly<{ x: number; z: number }>;
  readonly spawn: Readonly<{ x: number; y: number; z: number }>;
  readonly longTravelTarget: Readonly<{ x: number; y: number; z: number }>;
}

type MinecraftFixture = SurvivalMinecraftFixture | ControlledMinecraftFixture;

interface SmokePathOutcome {
  readonly pathId: string;
  readonly status: "completed" | "failed" | "interrupted";
  readonly startedAt: string;
  readonly completedAt: string;
  readonly goal: SmokeActivePathTrace["goal"];
  readonly origin: BeatGamePosition;
  readonly playerPosition?: BeatGamePosition;
  readonly cause?: string;
}

const program = Effect.scoped(
  Effect.gen(function* () {
    if (configuredArtifactDirectory === undefined) {
      const removedArtifactDirectories = yield* fromPromise("prune old smoke artifacts", () =>
        pruneArtifactRuns({
          rootDirectory: artifactRootDirectory,
          currentDirectory: artifactDirectory,
          maximumRuns: artifactRuns,
        }),
      );
      if (removedArtifactDirectories.length > 0) {
        process.stdout.write(
          `Pruned ${removedArtifactDirectories.length} old smoke artifact directories\n`,
        );
      }
    }
    yield* fromPromise("create artifact directory", () =>
      mkdir(artifactDirectory, { recursive: true }),
    );
    yield* Effect.addFinalizer(() =>
      fromPromise("flush artifact logs", () =>
        Promise.all([eventLog.flush(), soulfireLog.flush(), minecraftLog.flush()]).then(
          () => undefined,
        ),
      ).pipe(Effect.ignore),
    );
    yield* writeJson("configuration.json", {
      runId,
      soulfireRuntimeDirectory,
      botName,
      timeoutMs,
      artifactLogMaximumBytes,
      artifactLogFiles,
      artifactRuns,
      debugApiEnabled,
      debugApiEvalEnabled,
      debugApiPort,
      debugApiTimelineEntries,
      verboseOutput,
      longTravelGate,
      fixtureConfiguration,
    });

    const smokeScope = yield* Effect.scope;
    const fixtureFiber = yield* Effect.forkChild(
      Scope.provide(Effect.acquireRelease(startMinecraftFixture, stopMinecraftFixture), smokeScope),
    );
    const soulfireFiber = yield* Effect.forkChild(
      Scope.provide(
        Effect.gen(function* () {
          const [dedicatedJar, javaPath] = yield* Effect.all([findDedicatedJar, findJava], {
            concurrency: "unbounded",
          });
          yield* fromPromise("reset reusable Soulfire runtime", () =>
            resetReusableSoulFireRuntime(soulfireRuntimeDirectory, dedicatedJar),
          );
          const soulfire = yield* SoulFire.install({
            directory: soulfireRuntimeDirectory,
            jarPath: dedicatedJar,
            javaPath,
            javaArgs: [
              "-Xms1G",
              "-Xmx4G",
              ...(debugBlockBreak
                ? ["-DMC_DEBUG_ENABLED=true", "-DMC_DEBUG_BLOCK_BREAK=true"]
                : []),
            ],
            startupTimeoutMs: 180_000,
            defaultTimeoutMs: 10 * 60_000,
            onLog: (line) => {
              if (verboseOutput) {
                process.stdout.write(`[soulfire] ${line}\n`);
              }
              void soulfireLog.append(`${line}\n`).catch((cause: unknown) => {
                process.stderr.write(`Could not write Soulfire log: ${String(cause)}\n`);
              });
            },
          });
          const localServer = soulfire.localServer;
          if (localServer === undefined) {
            return yield* Effect.fail(
              new Error("Auto-provisioned Soulfire did not expose its local process"),
            );
          }
          yield* fromPromise("record Soulfire process", () =>
            writeFile(
              soulfireProcessFile,
              `${json(
                {
                  pid: localServer.pid,
                  jarPath: localServer.jarPath,
                  runDirectory: localServer.runDirectory,
                },
                2,
              )}\n`,
            ),
          );
          yield* Effect.addFinalizer(() =>
            soulfire
              .stopLocalServer()
              .pipe(
                Effect.ignore,
                Effect.andThen(
                  fromPromise("remove Soulfire process record", () =>
                    rm(soulfireProcessFile, { force: true }),
                  ).pipe(Effect.ignore),
                ),
              ),
          );
          return { dedicatedJar, javaPath, soulfire };
        }),
        smokeScope,
      ),
    );
    const [fixture, soulfireRuntime] = yield* Effect.all(
      [Fiber.join(fixtureFiber), Fiber.join(soulfireFiber)],
      { concurrency: "unbounded" },
    );
    const { dedicatedJar, javaPath, soulfire } = soulfireRuntime;
    yield* record("fixture-ready", {
      ...fixture,
      dedicatedJar,
      javaPath,
    });
    yield* record("soulfire-ready", {
      server: soulfire.server,
      localServer: soulfire.localServer,
    });

    const instance = yield* soulfire.createInstance("Beat game SDK smoke");
    yield* instance.setConfigEntry({
      namespace: "bot",
      key: "address",
      value: create(ValueSchema, {
        kind: {
          case: "stringValue",
          value: `127.0.0.1:${fixture.port}`,
        },
      }),
    });
    const profileId = offlineUuid(botName);
    yield* instance.addAccounts([
      create(MinecraftAccountProtoSchema, {
        type: MinecraftAccountProto_AccountTypeProto.OFFLINE,
        profileId,
        lastKnownName: botName,
        accountData: {
          case: "offlineJavaData",
          value: create(MinecraftAccountProto_OfflineJavaDataSchema),
        },
        config: [
          create(SettingsNamespaceSchema, {
            namespace: "bot",
            entries: [
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "use-io-uring",
                value: create(ValueSchema, {
                  kind: { case: "boolValue", value: false },
                }),
              }),
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "protocol-version",
                value: create(ValueSchema, {
                  kind: {
                    case: "stringValue",
                    value: fixtureConfiguration.minecraftVersion,
                  },
                }),
              }),
            ],
          }),
          create(SettingsNamespaceSchema, {
            namespace: "client-settings",
            entries: [
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "render-distance",
                value: create(ValueSchema, {
                  kind: { case: "numberValue", value: 10 },
                }),
              }),
            ],
          }),
          create(SettingsNamespaceSchema, {
            namespace: "pathfinding",
            entries: [
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "y-rot-jitter",
                value: minMaxValue(0, 0),
              }),
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "x-rot-jitter",
                value: minMaxValue(0, 0),
              }),
            ],
          }),
          create(SettingsNamespaceSchema, {
            namespace: "auto-eat",
            entries: [
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "enabled",
                value: create(ValueSchema, {
                  kind: { case: "boolValue", value: false },
                }),
              }),
            ],
          }),
          create(SettingsNamespaceSchema, {
            namespace: "auto-respawn",
            entries: [
              create(SettingsNamespace_SettingsEntrySchema, {
                key: "enabled",
                value: create(ValueSchema, {
                  kind: { case: "boolValue", value: false },
                }),
              }),
            ],
          }),
        ],
      }),
    ]);
    const bot = instance.bot(profileId);
    yield* bot.start();
    yield* bot.waitForOnline().pipe(Effect.timeout(Duration.minutes(2)));
    if (fixture.mode === "controlled") {
      yield* poll(
        rcon(fixture, "list").pipe(
          Effect.flatMap((result) =>
            result.stdout.includes(botName)
              ? Effect.succeed(result)
              : Effect.fail(new Error(`${botName} is not listed by Minecraft`)),
          ),
        ),
        "Minecraft player join",
        120,
        500,
      );
    }
    yield* poll(
      bot.world.player().pipe(
        Effect.mapError((cause) => new Error("read bot readiness", { cause })),
        Effect.flatMap((player) =>
          player.dead || player.position !== undefined
            ? Effect.succeed(player)
            : Effect.fail(new Error("Bot has not loaded its position")),
        ),
      ),
      "Soulfire world readiness",
      180,
      500,
    );
    const startupAirGuard = yield* guardStartupAir(bot).pipe(Effect.forkScoped);
    yield* record("bot-online", { instanceId: instance.id, profileId });
    let lastTaskProgressFingerprint: string | undefined;
    yield* bot.tasks.watch({ includeSnapshot: false }).pipe(
      Stream.tapError((cause) =>
        record("task-progress-watch-failed", {
          cause: String(cause),
        }).pipe(Effect.ignore),
      ),
      Stream.retry(Schedule.spaced(Duration.seconds(1))),
      Stream.runForEach((event) => {
        const task = event.task;
        const progress = decodeSmokeTaskProgress(task?.progress);
        const fingerprint = json(
          task === undefined
            ? {}
            : {
                taskId: task.taskId,
                taskType: task.taskType,
                status: task.status,
                summary: task.summary,
                progress,
                failure: task.failure,
              },
        );
        if (fingerprint === lastTaskProgressFingerprint) {
          return Effect.void;
        }
        lastTaskProgressFingerprint = fingerprint;
        return record("task-progress-observed", {
          sequence: event.sequence,
          ...(task === undefined
            ? {}
            : {
                task: {
                  taskId: task.taskId,
                  taskType: task.taskType,
                  status: task.status,
                  summary: task.summary,
                  progress,
                  failure: task.failure,
                  revision: task.revision,
                  updatedAt: task.updatedAt,
                },
              }),
        });
      }),
      Effect.forkScoped,
    );

    const joinedPlayer = yield* bot.world.player();
    const initialInventory = yield* bot.inventory.snapshot();
    const initialItems = initialInventory.slots.flatMap((slot) =>
      slot.item === undefined || slot.item.count < 1
        ? []
        : [
            {
              slot: slot.slot,
              itemId: slot.item.itemId,
              count: slot.item.count,
            },
          ],
    );
    yield* record("bot-initial-state", {
      mode: fixture.mode,
      player: joinedPlayer,
      inventory: initialInventory,
    });
    if (debugWorldNeighborhood && joinedPlayer.position !== undefined) {
      const center = {
        x: Math.floor(joinedPlayer.position.x),
        y: Math.floor(joinedPlayer.position.y),
        z: Math.floor(joinedPlayer.position.z),
      };
      const blocks = yield* Effect.forEach(
        Array.from({ length: 7 }, (_, xOffset) =>
          Array.from({ length: 7 }, (_, yOffset) =>
            Array.from({ length: 7 }, (_, zOffset) => ({
              x: center.x + xOffset - 3,
              y: center.y + yOffset - 3,
              z: center.z + zOffset - 3,
            })),
          ),
        ).flat(2),
        (position) =>
          bot.world
            .block({
              position: {
                ...position,
                dimension: joinedPlayer.position?.dimension ?? "",
              },
              includeShapes: true,
            })
            .pipe(
              Effect.map(({ block }) => ({
                position,
                blockId: block?.blockId,
                properties: block?.properties,
                fluid: block?.fluid,
                collisionShape: block?.collisionShape,
              })),
            ),
        { concurrency: 32 },
      );
      yield* record("world-neighborhood", { center, blocks });
    }
    if (fixture.mode === "survival" && fixture.freshWorld && initialItems.length > 0) {
      return yield* Effect.fail(
        new Error(
          `Authoritative smoke bot joined with a non-empty inventory: ${json(initialItems)}`,
        ),
      );
    }

    if (fixture.mode === "controlled") {
      yield* provisionBot(fixture, botName);
      yield* poll(
        bot.world.player().pipe(
          Effect.mapError((cause) => new Error("read prepared bot position", { cause })),
          Effect.flatMap((preparedPlayer) =>
            preparedPlayer.onGround &&
            Math.abs((preparedPlayer.position?.y ?? 0) - fixture.spawn.y) < 1
              ? Effect.succeed(preparedPlayer)
              : Effect.fail(new Error("Bot has not reached the prepared arena")),
          ),
        ),
        "prepared arena arrival",
        120,
        250,
      );
      const player = yield* bot.world.player();
      yield* record("bot-provisioned", { player });
      yield* controlEndEncounter(fixture, botName, bot).pipe(Effect.forkScoped);
    }
    const checkpointStore = new JsonFileBeatGameCheckpointStore(
      path.join(artifactDirectory, "checkpoints"),
    );
    const environmentSession = yield* bot.observe({
      filter: { includeEnvironment: true },
    });
    if (joinedPlayer.dead) {
      yield* record("startup-readiness-deferred", {
        reason: "The planner must respawn a bot that attached while dead",
        position: joinedPlayer.position,
      });
    } else {
      yield* environmentSession.waitFor(
        (_event, state) => state.environment.gameTime !== undefined,
        { timeoutMs: 5_000 },
      );
      yield* record("environment-observed", {
        gameTime: environmentSession.state.environment.gameTime,
        raining: environmentSession.state.environment.raining,
      });
    }
    const debugSession = debugApiEnabled ? environmentSession : undefined;
    const environment = Effect.sync(() => {
      const state = environmentSession.state.environment;
      return {
        ...(state.gameTime === undefined ? {} : { gameTime: state.gameTime }),
        ...(state.raining === undefined ? {} : { raining: state.raining }),
      };
    });
    const baseDriver = makeSoulFireBeatGameDriver(bot, { environment });
    if (joinedPlayer.dead) {
      yield* record("bot-chunks-deferred", {
        radiusChunks: 4,
        reason: "Chunk readiness will be confirmed after respawn",
      });
    } else {
      yield* baseDriver.waitForChunks(4, 60_000);
      yield* record("bot-chunks-ready", { radiusChunks: 4 });
    }
    yield* Fiber.interrupt(startupAirGuard);
    yield* bot.resetMovement().pipe(Effect.ignore);
    if (fixture.mode === "controlled") {
      const corridorSample = yield* Effect.forEach(
        [-1, 0, 1, 8, 15, 16, 31, 32, 47, 48, 63, 64].map((offset) => ({
          x: fixture.spawn.x + offset,
          y: fixture.spawn.y - 1,
          z: fixture.spawn.z,
        })),
        (position) =>
          bot.world
            .block({
              position: {
                ...position,
                dimension: "minecraft:overworld",
              },
              includeShapes: true,
            })
            .pipe(
              Effect.map(({ block }) => ({
                position,
                blockId: block?.blockId,
                collisionShape: block?.collisionShape,
              })),
            ),
        { concurrency: "unbounded" },
      );
      yield* record("controlled-corridor-sampled", { blocks: corridorSample });
      const missingFloor = corridorSample.find(({ blockId }) => blockId !== "minecraft:stone");
      if (missingFloor !== undefined) {
        return yield* Effect.fail(
          new Error(`Controlled corridor floor is not observable at ${json(missingFloor)}`),
        );
      }
      const corridorAirSample = yield* Effect.forEach(
        [-1, 0, 1, 8, 15, 16, 31, 32, 41, 47, 48, 63, 64].flatMap((offset) =>
          [-1, 0, 1].map((zOffset) => ({
            x: fixture.spawn.x + offset,
            y: fixture.spawn.y,
            z: fixture.spawn.z + zOffset,
          })),
        ),
        (position) =>
          bot.world
            .block({
              position: {
                ...position,
                dimension: "minecraft:overworld",
              },
            })
            .pipe(
              Effect.map(({ block }) => ({
                position,
                blockId: block?.blockId,
              })),
            ),
        { concurrency: "unbounded" },
      );
      yield* record("controlled-corridor-air-sampled", {
        blocks: corridorAirSample,
      });
      const obstructedAir = corridorAirSample.find(({ blockId }) => blockId !== "minecraft:air");
      if (obstructedAir !== undefined) {
        return yield* Effect.fail(
          new Error(`Controlled corridor is not dry at ${json(obstructedAir)}`),
        );
      }
    }
    let lastObservedInventoryRevision: bigint | undefined;
    const observedEntityFingerprints = new Map<string, string>();
    let lastObservedVitals:
      | Readonly<{
          health: number;
          food: number;
          air: number;
          dead: boolean;
        }>
      | undefined;
    let activePathTrace: SmokeActivePathTrace | undefined;
    let lastPathOutcome: SmokePathOutcome | undefined;
    let pathAttempts = 0;
    let pathCompletions = 0;
    let pathFailures = 0;
    let pathInterruptions = 0;
    const tracePath = (
      kind: "pathfind" | "pathfind-xz",
      goal: SmokeActivePathTrace["goal"],
      policy: BeatGamePathPolicy,
      details: Readonly<Record<string, unknown>>,
      execute: () => ReturnType<typeof baseDriver.pathfind>,
    ) =>
      Effect.suspend(() => {
        pathAttempts += 1;
        const pathId = randomUUID();
        const startedAt = new Date().toISOString();
        const owner = currentDebugActionContext();
        let trace: SmokeActivePathTrace | undefined;
        return Effect.gen(function* () {
          const fiberId = String(yield* Effect.fiberId);
          const observation = yield* baseDriver.observe;
          trace = {
            pathId,
            startedAt,
            fiberId,
            owner,
            origin: observation.player.position,
            goal,
            policy,
          };
          activePathTrace = trace;
          yield* record(`${kind}-started`, {
            pathId,
            startedAt,
            fiberId,
            owner,
            origin: trace.origin,
            ...details,
            policy,
          }).pipe(Effect.orDie);
          yield* execute();
          const player = yield* bot.world.player().pipe(Effect.orDie);
          const completedAt = new Date().toISOString();
          lastPathOutcome = {
            pathId,
            status: "completed",
            startedAt,
            completedAt,
            goal: trace.goal,
            origin: trace.origin,
            ...(player.position === undefined ? {} : { playerPosition: player.position }),
          };
          pathCompletions += 1;
          yield* record(`${kind}-completed`, {
            pathId,
            startedAt,
            completedAt,
            durationMs: elapsedMilliseconds(startedAt, completedAt),
            fiberId,
            owner,
            ...details,
            playerPosition: player.position,
            playerVelocity: player.velocity,
          }).pipe(Effect.orDie);
        }).pipe(
          Effect.onExit((exit) => {
            if (Exit.isSuccess(exit)) {
              return Effect.void;
            }
            const completedAt = new Date().toISOString();
            const status = Exit.hasInterrupts(exit) ? "interrupted" : "failed";
            if (status === "interrupted") {
              pathInterruptions += 1;
            } else {
              pathFailures += 1;
            }
            const failure = debugExitFailure(exit);
            if (trace !== undefined) {
              lastPathOutcome = {
                pathId,
                status,
                startedAt,
                completedAt,
                goal: trace.goal,
                origin: trace.origin,
                cause: failure.cause,
              };
            }
            return record(`${kind}-${status}`, {
              pathId,
              startedAt,
              completedAt,
              durationMs: elapsedMilliseconds(startedAt, completedAt),
              owner,
              ...details,
              ...failure,
            }).pipe(Effect.orDie);
          }),
          Effect.ensuring(
            Effect.sync(() => {
              if (activePathTrace?.pathId === pathId) {
                activePathTrace = undefined;
              }
            }),
          ),
        );
      });
    const driver = {
      ...baseDriver,
      environment,
      observe: baseDriver.observe.pipe(
        Effect.tap((observation) => {
          const effects = [];
          if (observation.inventory.revision !== lastObservedInventoryRevision) {
            lastObservedInventoryRevision = observation.inventory.revision;
            effects.push(
              record("inventory-observed", {
                revision: observation.inventory.revision,
                counts: observation.inventory.counts,
              }).pipe(Effect.orDie),
            );
          }
          const vitals = {
            health: observation.player.health,
            food: observation.player.food,
            air: observation.player.air,
            dead: observation.player.dead,
          };
          if (
            lastObservedVitals === undefined ||
            vitals.health !== lastObservedVitals.health ||
            vitals.food !== lastObservedVitals.food ||
            vitals.air !== lastObservedVitals.air ||
            vitals.dead !== lastObservedVitals.dead
          ) {
            lastObservedVitals = vitals;
            effects.push(
              record("player-vitals-observed", {
                ...vitals,
                position: observation.player.position,
              }).pipe(Effect.orDie),
            );
          }
          return Effect.all(effects, {
            concurrency: "unbounded",
            discard: true,
          });
        }),
      ),
      recipesFor: (resultItemId: string) =>
        baseDriver.recipesFor(resultItemId).pipe(
          Effect.tap((recipes) =>
            record("recipes-observed", {
              resultItemId,
              recipes,
            }).pipe(Effect.orDie),
          ),
        ),
      canCraft: (recipeId: string, count: number) =>
        baseDriver.canCraft(recipeId, count).pipe(
          Effect.tap((craftability) =>
            record("craftability-observed", {
              recipeId,
              count,
              craftability,
            }).pipe(Effect.orDie),
          ),
        ),
      queryBlocks: (query: Parameters<typeof baseDriver.queryBlocks>[0]) =>
        baseDriver.queryBlocks(query).pipe(
          Effect.tap((blocks) =>
            shouldTraceBlockQuery(query)
              ? record("block-query", {
                  owner: currentDebugActionContext(),
                  query,
                  resultCount: blocks.length,
                  resultsTruncated: blocks.length > debugBlockQueryResultLimit,
                  blocks: blocks.slice(0, debugBlockQueryResultLimit),
                }).pipe(Effect.orDie)
              : Effect.void,
          ),
        ),
      queryEntities: (query: Parameters<typeof baseDriver.queryEntities>[0]) =>
        baseDriver.queryEntities(query).pipe(
          Effect.tap((entities) => {
            const selectorKey = json(query.selector);
            const fingerprint = [...entities]
              .sort((left, right) => left.networkId - right.networkId)
              .map((entity) =>
                [
                  entity.connectionEpoch,
                  entity.networkId,
                  entity.entityType,
                  Math.floor(entity.position.x / 4),
                  Math.floor(entity.position.y / 4),
                  Math.floor(entity.position.z / 4),
                  entity.alive,
                ].join(":"),
              )
              .join("|");
            if (observedEntityFingerprints.get(selectorKey) === fingerprint) {
              return Effect.void;
            }
            observedEntityFingerprints.set(selectorKey, fingerprint);
            return record("entity-query", { query, entities }).pipe(Effect.orDie);
          }),
        ),
      raycast: (query: Parameters<typeof baseDriver.raycast>[0]) =>
        Effect.gen(function* () {
          const observation = yield* baseDriver.observe;
          const result = yield* baseDriver.raycast(query);
          yield* record("raycast-query", {
            owner: currentDebugActionContext(),
            origin: {
              ...observation.player.position,
              y: observation.player.position.y + 1.62,
            },
            query,
            result,
          }).pipe(Effect.orDie);
          return result;
        }),
      pathfind: (
        position: Parameters<typeof baseDriver.pathfind>[0],
        radius: number,
        policy: Parameters<typeof baseDriver.pathfind>[2],
      ) =>
        tracePath(
          "pathfind",
          { type: "position", position, radius },
          policy,
          { position, radius },
          () => baseDriver.pathfind(position, radius, policy),
        ),
      pathfindXZ: (
        x: number,
        z: number,
        dimension: string,
        radius: number,
        policy: Parameters<typeof baseDriver.pathfindXZ>[4],
      ) =>
        tracePath(
          "pathfind-xz",
          { type: "xz", x, z, dimension, radius },
          policy,
          { x, z, dimension, radius },
          () => baseDriver.pathfindXZ(x, z, dimension, radius, policy),
        ),
      runTask: (
        task: Parameters<typeof baseDriver.runTask>[0],
        policy: Parameters<typeof baseDriver.runTask>[1],
        execution: Parameters<typeof baseDriver.runTask>[2],
      ) =>
        Effect.suspend(() => {
          const operationId = randomUUID();
          const startedAt = new Date().toISOString();
          const owner = currentDebugActionContext();
          return Effect.gen(function* () {
            const fiberId = String(yield* Effect.fiberId);
            yield* record("task-started", {
              operationId,
              startedAt,
              fiberId,
              owner,
              task,
              policy,
              execution,
            }).pipe(Effect.orDie);
            return yield* baseDriver.runTask(task, policy, execution).pipe(
              Effect.onExit((exit) => {
                const completedAt = new Date().toISOString();
                if (Exit.isSuccess(exit)) {
                  return record("task-completed", {
                    operationId,
                    startedAt,
                    completedAt,
                    durationMs: elapsedMilliseconds(startedAt, completedAt),
                    owner,
                    task,
                    result: exit.value,
                  }).pipe(Effect.orDie);
                }
                const status = Exit.hasInterrupts(exit) ? "interrupted" : "failed";
                return record(`task-${status}`, {
                  operationId,
                  startedAt,
                  completedAt,
                  durationMs: elapsedMilliseconds(startedAt, completedAt),
                  owner,
                  task,
                  ...debugExitFailure(exit),
                }).pipe(Effect.orDie);
              }),
            );
          });
        }),
      act: (action: Parameters<typeof baseDriver.act>[0]) =>
        Effect.suspend(() => {
          const operationId = randomUUID();
          const startedAt = new Date().toISOString();
          const owner = currentDebugActionContext();
          return Effect.gen(function* () {
            const fiberId = String(yield* Effect.fiberId);
            yield* record("primitive-started", {
              operationId,
              startedAt,
              fiberId,
              owner,
              action,
            }).pipe(Effect.orDie);
            return yield* baseDriver.act(action).pipe(
              Effect.onExit((exit) => {
                const completedAt = new Date().toISOString();
                if (Exit.isSuccess(exit)) {
                  return record("primitive-completed", {
                    operationId,
                    startedAt,
                    completedAt,
                    durationMs: elapsedMilliseconds(startedAt, completedAt),
                    owner,
                    action,
                  }).pipe(Effect.orDie);
                }
                const status = Exit.hasInterrupts(exit) ? "interrupted" : "failed";
                return record(`primitive-${status}`, {
                  operationId,
                  startedAt,
                  completedAt,
                  durationMs: elapsedMilliseconds(startedAt, completedAt),
                  owner,
                  action,
                  ...debugExitFailure(exit),
                }).pipe(Effect.orDie);
              }),
            );
          });
        }),
    };
    if (fixture.mode === "controlled" && longTravelGate) {
      const travelPolicy: BeatGamePathPolicy = {
        ...defaultBeatGameStrategy.path,
        allowMining: false,
        allowPlacing: false,
        avoidFluids: true,
        sprint: true,
        searchMode: "NORMAL",
        maxSearchTimeMs: 120_000,
      };
      const travelTarget = {
        ...fixture.longTravelTarget,
        dimension: "minecraft:overworld",
      };
      const returnTarget = {
        ...fixture.spawn,
        x: fixture.spawn.x + 0.5,
        z: fixture.spawn.z + 0.5,
        dimension: "minecraft:overworld",
      };
      yield* record("long-travel-gate-started", {
        travelTarget,
        returnTarget,
        policy: travelPolicy,
      });
      yield* driver
        .pathfind(travelTarget, 1.25, travelPolicy)
        .pipe(Effect.timeout(Duration.minutes(6)));
      const reached = yield* driver.observe;
      const targetDistance = Math.hypot(
        reached.player.position.x - travelTarget.x,
        reached.player.position.z - travelTarget.z,
      );
      if (targetDistance > 1.5) {
        return yield* Effect.fail(
          new Error(`Long-travel path ended ${targetDistance.toFixed(2)} blocks from its target`),
        );
      }
      yield* driver
        .pathfind(returnTarget, 1.25, travelPolicy)
        .pipe(Effect.timeout(Duration.minutes(6)));
      const returned = yield* driver.observe;
      yield* record("long-travel-gate-completed", {
        targetDistance,
        reached: reached.player.position,
        returned: returned.player.position,
        horizontalDistance:
          Math.hypot(travelTarget.x - returnTarget.x, travelTarget.z - returnTarget.z) * 2,
      });
    }
    const beatGameStrategy = {
      ...defaultBeatGameStrategy,
      actionTimeoutMs: 600_000,
      observationPollMs: 250,
      entitySearchRadius: 320,
      path: {
        ...defaultBeatGameStrategy.path,
        maxSearchTimeMs: 120_000,
      },
    } satisfies BeatGameStrategy;
    const run = yield* beatGameWithDriver(driver, {
      runId,
      checkpointStore,
      team: { teamId: `${runId}-team` },
      strategy: beatGameStrategy,
    });
    const initialRunSnapshot = yield* run.snapshot;
    const initialPlanner = initialRunSnapshot.checkpoint.planner;
    const observedPhases = new Set<BeatGamePhase>([initialPlanner.phase]);
    let actionRetries = 0;
    let safetyInterruptions = 0;
    if (initialPlanner.currentAction !== undefined) {
      activeDebugActionContext = {
        action: initialPlanner.currentAction,
        ...(initialPlanner.currentActionId === undefined
          ? {}
          : { actionId: initialPlanner.currentActionId }),
        phase: initialPlanner.phase,
        startedAt: initialPlanner.updatedAt,
      };
    }
    yield* Stream.runForEach(run.events, (event) =>
      Effect.sync(() => {
        updateDebugActionContext(event);
        observedPhases.add(event.phase);
        if (event.type === "phase-changed") {
          observedPhases.add(event.current);
        } else if (event.type === "action-retried") {
          actionRetries += 1;
        } else if (
          event.type === "action-failed" &&
          /interrupt|preempt|evad|defend|safety/iu.test(event.detail ?? "")
        ) {
          safetyInterruptions += 1;
        }
      }).pipe(Effect.andThen(record("beat-game-event", { event }))),
    ).pipe(Effect.forkScoped);
    if (debugApiEnabled) {
      const debugOperations: SmokeDebugOperation[] = [
        {
          method: "GET",
          path: "/diagnostics/snapshot",
          description:
            "Capture spatial state, entity motion, decisions, tasks, and pathfinding activity around one pinned origin",
          execute: (input) =>
            captureSmokeDiagnostics({
              activePath: activePathTrace,
              bot,
              driver,
              environment: () => summarizeSmokeEnvironment(debugSession?.state.environment),
              input,
              lastPathOutcome,
              run,
              strategy: beatGameStrategy,
            }),
        },
        {
          method: "GET",
          path: "/diagnostics/stuck",
          description:
            "Detect stalled paths, frozen task progress, repeated replans, and path failure loops with supporting live context",
          execute: () =>
            Effect.gen(function* () {
              const capturedAt = new Date().toISOString();
              const [snapshot, observation, finalPlayer] = yield* Effect.all(
                [run.snapshot, driver.observe, bot.world.player()],
                { concurrency: "unbounded" },
              );
              const planner = snapshot.checkpoint.planner;
              const currentPosition = finalPlayer.position ?? observation.player.position;
              const activePath =
                activePathTrace === undefined
                  ? undefined
                  : buildSmokeActivePathDiagnostics(activePathTrace, currentPosition, capturedAt);
              const [blocks, entities, surface] = yield* Effect.all(
                [
                  driver.queryBlocks({
                    center: observation.player.position,
                    radius: 8,
                    selector: {},
                    maximumResults: 768,
                  }),
                  driver.queryEntities({
                    origin: observation.player.position,
                    radius: 48,
                    selector: {},
                    maximumResults: 128,
                  }),
                  driver.sampleSurface(observation.player.position, 12, 2),
                ],
                { concurrency: "unbounded" },
              );
              const nearby = summarizeSmokeSpatialDiagnostics(
                buildSmokeSpatialDiagnostics({
                  origin: observation.player.position,
                  originVelocity: observation.player.velocity,
                  finalPosition: currentPosition,
                  localBlockRadius: 8,
                  entityRadius: 48,
                  surfaceRadius: 12,
                  startedAt: capturedAt,
                  completedAt: new Date().toISOString(),
                  blocks,
                  entities,
                  surface,
                }),
              );
              const activity = debugTimeline.query({
                kinds: [
                  "beat-game-event",
                  "pathfind-completed",
                  "pathfind-failed",
                  "pathfind-interrupted",
                  "pathfind-xz-completed",
                  "pathfind-xz-failed",
                  "pathfind-xz-interrupted",
                  "primitive-completed",
                  "task-progress-observed",
                ],
                limit: debugApiTimelineEntries,
              });
              return {
                analysis: buildSmokeStuckDiagnostics({
                  capturedAt,
                  currentAction: planner.currentAction,
                  currentActionStartedAt: currentDebugActionContext()?.startedAt,
                  activePath,
                  activity,
                }),
                decision: buildSmokeDecisionDiagnostics({
                  checkpoint: snapshot.checkpoint,
                  observation,
                  strategy: beatGameStrategy,
                  nextIfReplanned: decideBeatGameAction({
                    checkpoint: snapshot.checkpoint,
                    observation,
                    strategy: beatGameStrategy,
                  }),
                }),
                player: observation.player,
                inventory: observation.inventory,
                environment: summarizeSmokeEnvironment(debugSession?.state.environment),
                nearby,
                pathfinding: {
                  active: activePath,
                  lastOutcome: lastPathOutcome,
                },
                recentActivity: querySignificantDebugActivity(40),
              };
            }),
        },
        {
          method: "GET",
          path: "/overview",
          description:
            "Return a compact live overview of decisions, paths, nearby blocks, and nearby entities",
          execute: () =>
            Effect.gen(function* () {
              const [snapshot, observation] = yield* Effect.all([run.snapshot, driver.observe], {
                concurrency: "unbounded",
              });
              const capturedAt = new Date().toISOString();
              const origin = observation.player.position;
              const [blocks, entities, surface] = yield* Effect.all(
                [
                  driver.queryBlocks({
                    center: origin,
                    radius: 8,
                    selector: {},
                    maximumResults: 768,
                  }),
                  driver.queryEntities({
                    origin,
                    radius: 48,
                    selector: {},
                    maximumResults: 128,
                  }),
                  driver.sampleSurface(origin, 12, 2),
                ],
                { concurrency: "unbounded" },
              );
              const finalPlayer = yield* bot.world.player();
              const planner = snapshot.checkpoint.planner;
              const nextIfReplanned = decideBeatGameAction({
                checkpoint: snapshot.checkpoint,
                observation,
                strategy: beatGameStrategy,
              });
              const spatial = summarizeSmokeSpatialDiagnostics(
                buildSmokeSpatialDiagnostics({
                  origin,
                  originVelocity: observation.player.velocity,
                  finalPosition: finalPlayer.position ?? origin,
                  localBlockRadius: 8,
                  entityRadius: 48,
                  surfaceRadius: 12,
                  startedAt: capturedAt,
                  completedAt: new Date().toISOString(),
                  blocks,
                  entities,
                  surface,
                }),
              );
              return {
                run: {
                  runId: snapshot.checkpoint.runId,
                  phase: planner.phase,
                  status: planner.status,
                  objective: planner.objective,
                  currentAction: planner.currentAction,
                  currentActionId: planner.currentActionId,
                  retryCount: planner.retryCount,
                  pendingRequirements: planner.requirements
                    .filter((requirement) => !requirement.satisfied)
                    .map((requirement) => ({
                      key: requirement.key,
                      currentCount: requirement.currentCount,
                      targetCount: requirement.targetCount,
                      missingCount: Math.max(0, requirement.targetCount - requirement.currentCount),
                    })),
                  lastStableAction: snapshot.checkpoint.lastStableAction,
                },
                decision: {
                  ...buildSmokeDecisionDiagnostics({
                    checkpoint: snapshot.checkpoint,
                    observation,
                    strategy: beatGameStrategy,
                    nextIfReplanned,
                  }),
                  activity: queryCurrentDecisionActivity(planner.currentAction, 30),
                },
                player: observation.player,
                inventory: observation.inventory,
                environment: summarizeSmokeEnvironment(debugSession?.state.environment),
                nearby: spatial,
                pathfinding: {
                  active:
                    activePathTrace === undefined
                      ? undefined
                      : buildSmokeActivePathDiagnostics(
                          activePathTrace,
                          observation.player.position,
                          new Date().toISOString(),
                        ),
                  taskRoute: latestSmokeTaskRoute(),
                  lastOutcome: lastPathOutcome,
                },
                pathActivity: queryDebugTimeline({
                  kind: [
                    "pathfind-started",
                    "pathfind-completed",
                    "pathfind-failed",
                    "pathfind-interrupted",
                    "pathfind-xz-started",
                    "pathfind-xz-completed",
                    "pathfind-xz-failed",
                    "pathfind-xz-interrupted",
                  ].join(","),
                  limit: 12,
                }),
                worldActivity: debugTimeline
                  .query({
                    kinds: ["block-query", "entity-query", "raycast-query"],
                    limit: 12,
                  })
                  .map(compactDecisionActivityEntry),
                taskActivity: queryDebugTimeline({
                  kind: [
                    "task-started",
                    "task-progress-observed",
                    "task-completed",
                    "task-failed",
                    "task-interrupted",
                  ].join(","),
                  limit: 20,
                }),
              };
            }),
        },
        {
          method: "GET",
          path: "/decision/trace",
          description:
            "Explain the active planner decision, its blockers, live signals, and correlated execution activity",
          execute: () =>
            Effect.gen(function* () {
              const [snapshot, observation] = yield* Effect.all([run.snapshot, driver.observe], {
                concurrency: "unbounded",
              });
              const planner = snapshot.checkpoint.planner;
              return {
                ...buildSmokeDecisionDiagnostics({
                  checkpoint: snapshot.checkpoint,
                  observation,
                  strategy: beatGameStrategy,
                  nextIfReplanned: decideBeatGameAction({
                    checkpoint: snapshot.checkpoint,
                    observation,
                    strategy: beatGameStrategy,
                  }),
                }),
                activity: queryCurrentDecisionActivity(planner.currentAction, 250),
                environment: summarizeSmokeEnvironment(debugSession?.state.environment),
              };
            }),
        },
        {
          method: "GET",
          path: "/state",
          description: "Return the live beat-game snapshot, raw player state, and inventory",
          execute: () =>
            Effect.all(
              {
                beatGame: run.snapshot,
                player: bot.world.player(),
                inventory: bot.inventory.snapshot(),
                environment: Effect.sync(() =>
                  summarizeSmokeEnvironment(debugSession?.state.environment),
                ),
              },
              { concurrency: "unbounded" },
            ),
        },
        {
          method: "GET",
          path: "/events",
          description: "Return recent decisions, tasks, primitives, queries, and path activity",
          execute: (input) => debugRequest(() => queryDebugTimeline(input)),
        },
        {
          method: "POST",
          path: "/world/block",
          description: "Read one exact block with optional block entity and collision shapes",
          execute: (input) =>
            debugRequest(() => parseDebugBlockRequest(input)).pipe(
              Effect.flatMap((request) => bot.world.block(request)),
            ),
        },
        {
          method: "POST",
          path: "/world/nearby",
          description: "Read a bounded block volume around the player or an explicit center",
          execute: (input) => inspectDebugWorldVolume(bot, input),
        },
        {
          method: "POST",
          path: "/world/blocks",
          description: "Query nearby blocks with the beat-game block selector",
          execute: (input) =>
            debugRequest(() => parseDebugBlockQuery(input)).pipe(
              Effect.flatMap(driver.queryBlocks),
            ),
        },
        {
          method: "POST",
          path: "/world/entities",
          description: "Query nearby entities with the beat-game entity selector",
          execute: (input) =>
            debugRequest(() => parseDebugEntityQuery(input)).pipe(
              Effect.flatMap(driver.queryEntities),
            ),
        },
        {
          method: "POST",
          path: "/world/raycast",
          description: "Raycast from the bot player's current eye position",
          execute: (input) =>
            debugRequest(() => parseDebugRaycast(input)).pipe(Effect.flatMap(driver.raycast)),
        },
        {
          method: "POST",
          path: "/world/surface",
          description: "Sample loaded terrain columns around a position",
          execute: (input) =>
            debugRequest(() => parseDebugSurfaceRequest(input)).pipe(
              Effect.flatMap(({ center, radius, sampleStep }) =>
                driver.sampleSurface(center, radius, sampleStep),
              ),
            ),
        },
        {
          method: "POST",
          path: "/path/plan",
          description: "Plan a read-only route with step descriptions and break/place traces",
          execute: (input) =>
            debugRequest(() => parseDebugPathPlan(input)).pipe(
              Effect.flatMap(({ position, radius, policy }) =>
                bot.pathfinder.plan(goals.near(position, radius), {
                  path: pathfinderOptions(policy),
                  includeDescriptions: true,
                }),
              ),
            ),
        },
        {
          method: "GET",
          path: "/path/active",
          description:
            "Inspect the correlated active route, current progress, and latest outcome without replanning",
          execute: () =>
            captureSmokeActivePath({
              activePath: activePathTrace,
              bot,
              includePlan: false,
              lastPathOutcome,
              taskRoute: latestSmokeTaskRoute(),
            }),
        },
        {
          method: "POST",
          path: "/path/active",
          description:
            "Inspect the active route and optionally calculate a fresh read-only path trace",
          execute: (input) =>
            debugRequest(() => {
              const request = debugRecord(input, "active path request");
              return optionalDebugBoolean(request, "includePlan") ?? true;
            }).pipe(
              Effect.flatMap((includePlan) =>
                captureSmokeActivePath({
                  activePath: activePathTrace,
                  bot,
                  includePlan,
                  lastPathOutcome,
                  taskRoute: latestSmokeTaskRoute(),
                }),
              ),
            ),
        },
      ];
      if (debugApiEvalEnabled) {
        debugOperations.push({
          method: "POST",
          path: "/eval",
          description:
            "Execute explicitly enabled unsafe JavaScript against the live smoke context",
          unsafe: true,
          execute: (input) =>
            evaluateDebugSource(input, {
              artifactDirectory,
              baseDriver,
              bot,
              checkpointStore,
              driver,
              Effect,
              run,
              runId,
            }),
        });
      }
      const debugServer = yield* startSmokeDebugServer({
        operations: debugOperations,
        port: debugApiPort,
      });
      yield* writePrivateJson("debug-api.json", {
        url: debugServer.url,
        token: debugServer.token,
        unsafeEvalEnabled: debugApiEvalEnabled,
        operations: debugServer.operations,
      });
      yield* record("debug-api-ready", {
        url: debugServer.url,
        unsafeEvalEnabled: debugApiEvalEnabled,
        descriptor: path.join(artifactDirectory, "debug-api.json"),
      });
    }

    const result = yield* run.awaitCompletion.pipe(Effect.timeout(Duration.millis(timeoutMs)));
    const finalPlayer = yield* bot.world.player();
    if (
      result.finalCheckpoint.planner.phase !== BeatGamePhase.COMPLETE ||
      result.finalCheckpoint.planner.status !== BeatGameRunStatus.COMPLETED
    ) {
      return yield* Effect.fail(
        new Error(
          `Beat-game run ended in ${result.finalCheckpoint.planner.phase}/${
            result.finalCheckpoint.planner.status
          }`,
        ),
      );
    }
    if (isEnd(finalPlayer.position?.dimension ?? "")) {
      return yield* Effect.fail(
        new Error("Beat-game run completed while the bot was still in the End"),
      );
    }
    const requiredPhases = Object.values(BeatGamePhase);
    const missingPhases = requiredPhases.filter((phase) => !observedPhases.has(phase));
    if (fixture.mode === "controlled" && missingPhases.length > 0) {
      return yield* Effect.fail(
        new Error(`Controlled completion skipped phase gates: ${missingPhases.join(", ")}`),
      );
    }
    const skillRetries = result.finalCheckpoint.memory.skillHistory.reduce(
      (total, skill) =>
        total + Object.values(skill.retries).reduce((skillTotal, count) => skillTotal + count, 0),
      0,
    );
    const qualificationMetrics = {
      runId,
      mode: fixture.mode,
      seed: fixture.seed ?? "server-generated",
      completed: true,
      durationMs: result.durationMs,
      deaths: result.finalCheckpoint.memory.deathPositions.length,
      pathAttempts,
      pathCompletions,
      pathFailures,
      pathInterruptions,
      actionRetries,
      safetyInterruptions,
      skillRetries,
      portalWorkspaces: result.finalCheckpoint.memory.portalWorkspaces.length,
      observedPhases: requiredPhases.filter((phase) => observedPhases.has(phase)),
    };
    yield* writeJson("qualification.json", qualificationMetrics);
    yield* record("qualification-metrics", qualificationMetrics);
    if (fixture.mode === "survival" && fixture.freshWorld) {
      yield* assertAdminCommandPolicy(fixture);
    }
    yield* record("beat-game-completed", {
      result,
      finalPlayer,
      exitedEnd: !isEnd(finalPlayer.position?.dimension ?? ""),
    });
  }).pipe(
    Effect.timeout(Duration.millis(timeoutMs + 5 * 60_000)),
    Effect.tapCause((cause) =>
      record("smoke-failed", { cause: String(cause) }).pipe(Effect.ignore),
    ),
  ),
);

const startMinecraftFixture = Effect.suspend(() => {
  const managedContainerName = `soulfire-beat-game-${randomUUID().slice(0, 12)}`;
  const managedDataDirectory = path.join(minecraftDataRootDirectory, managedContainerName);
  const start = Effect.gen(function* () {
    if (fixtureConfiguration.attachedMinecraftContainer !== undefined) {
      if (fixtureConfiguration.mode !== "survival") {
        return yield* Effect.fail(
          new Error("Controlled smoke mode cannot attach to an existing Minecraft world"),
        );
      }
      const containerName = fixtureConfiguration.attachedMinecraftContainer;
      const inspection = yield* docker([
        "inspect",
        "--format",
        "{{.State.Running}}",
        containerName,
      ]);
      if (inspection.stdout.trim() !== "true") {
        return yield* Effect.fail(new Error(`Minecraft container ${containerName} is not running`));
      }
      const port = yield* publishedMinecraftPort(containerName);
      const fixture = {
        mode: "survival",
        containerName,
        freshWorld: false,
        managed: false,
        port,
        seed: fixtureConfiguration.seed,
      } satisfies SurvivalMinecraftFixture;
      yield* record("minecraft-attached", {
        ...fixture,
        serverType: "PAPER",
      });
      return fixture;
    }

    const containerName = managedContainerName;
    yield* fromPromise("create persistent Minecraft data directory", () =>
      mkdir(managedDataDirectory, { recursive: true }),
    );
    const commonArguments = [
      "run",
      "--detach",
      "--rm",
      "--name",
      containerName,
      "--log-driver",
      "local",
      "--log-opt",
      "max-size=16m",
      "--log-opt",
      "max-file=2",
      "--publish",
      `127.0.0.1:${minecraftPort}:25565`,
      "--volume",
      `${managedDataDirectory}:/data`,
      "--env",
      "EULA=TRUE",
      "--env",
      "TYPE=PAPER",
      "--env",
      `VERSION=${fixtureConfiguration.minecraftVersion}`,
      "--env",
      "MEMORY=4G",
      "--env",
      "ONLINE_MODE=FALSE",
      "--env",
      "MODE=survival",
      "--env",
      "SPAWN_PROTECTION=0",
      "--env",
      "VIEW_DISTANCE=10",
      "--env",
      "SIMULATION_DISTANCE=10",
    ];

    if (fixtureConfiguration.mode === "survival") {
      const serverArguments = [...commonArguments, "--env", "ENABLE_RCON=false"];
      if (fixtureConfiguration.seed !== undefined) {
        serverArguments.push("--env", `SEED=${fixtureConfiguration.seed}`);
      }
      serverArguments.push(fixtureConfiguration.image);
      yield* docker(serverArguments);
      yield* waitForMinecraftServer(containerName);
      const port = yield* publishedMinecraftPort(containerName);
      const fixture = {
        mode: "survival",
        containerName,
        dataDirectory: managedDataDirectory,
        freshWorld: true,
        managed: true,
        port,
        seed: fixtureConfiguration.seed,
      } satisfies SurvivalMinecraftFixture;
      yield* record("minecraft-ready", {
        ...fixture,
        serverType: "PAPER",
        rconEnabled: false,
        seed: fixture.seed ?? "server-generated",
      });
      return fixture;
    }

    const seed = fixtureConfiguration.seed;
    if (seed === undefined) {
      return yield* Effect.fail(new Error("The controlled smoke fixture requires a seed"));
    }
    yield* docker([
      ...commonArguments,
      "--env",
      "ENABLE_RCON=true",
      "--env",
      "RCON_PASSWORD=soulfire-smoke",
      "--env",
      "DIFFICULTY=peaceful",
      "--env",
      `SEED=${seed}`,
      fixtureConfiguration.image,
    ]);
    yield* waitForMinecraftServer(containerName);
    yield* poll(rcon(containerName, "list"), "Minecraft RCON readiness", 120, 1_000);
    const port = yield* publishedMinecraftPort(containerName);
    yield* rcon(containerName, "gamerule keep_inventory true");
    yield* rcon(containerName, "gamerule mob_griefing false");
    yield* rcon(containerName, "gamerule respawn_radius 0");
    yield* rcon(containerName, "time set day");
    yield* rcon(containerName, "weather clear");
    const locate = yield* rcon(containerName, "locate structure minecraft:stronghold");
    const stronghold = parseStronghold(locate.stdout);
    const spawnCoordinates = { x: stronghold.x + 32, z: stronghold.z };
    yield* rcon(
      containerName,
      `forceload add ${spawnCoordinates.x - 16} ${
        spawnCoordinates.z - 16
      } ${spawnCoordinates.x + 16} ${spawnCoordinates.z + 16}`,
    );
    const worldSpawn = yield* rcon(
      containerName,
      `execute positioned ${spawnCoordinates.x} 0 ${
        spawnCoordinates.z
      } positioned over motion_blocking_no_leaves run setworldspawn ~ ~ ~`,
    );
    const naturalSpawn = parseWorldSpawn(worldSpawn.stdout);
    const spawn = { ...naturalSpawn, y: 49 };
    const testCorridor = {
      minimumX: stronghold.x - 255,
      maximumX: spawn.x + 255,
      minimumZ: spawn.z - 32,
      maximumZ: spawn.z + 32,
    };
    yield* rcon(
      containerName,
      `forceload add ${testCorridor.minimumX} ${testCorridor.minimumZ} ${
        testCorridor.maximumX
      } ${testCorridor.maximumZ}`,
    );
    for (let minimumX = testCorridor.minimumX; minimumX <= testCorridor.maximumX; minimumX += 16) {
      const maximumX = Math.min(minimumX + 15, testCorridor.maximumX);
      for (
        let minimumZ = testCorridor.minimumZ;
        minimumZ <= testCorridor.maximumZ;
        minimumZ += 16
      ) {
        const maximumZ = Math.min(minimumZ + 15, testCorridor.maximumZ);
        yield* rcon(
          containerName,
          `fill ${minimumX} ${spawn.y - 16} ${minimumZ} ${
            maximumX
          } ${spawn.y + 9} ${maximumZ} stone`,
        );
      }
    }
    for (let minimumX = testCorridor.minimumX; minimumX <= testCorridor.maximumX; minimumX += 16) {
      const maximumX = Math.min(minimumX + 15, testCorridor.maximumX);
      for (
        let minimumZ = testCorridor.minimumZ;
        minimumZ <= testCorridor.maximumZ;
        minimumZ += 16
      ) {
        const maximumZ = Math.min(minimumZ + 15, testCorridor.maximumZ);
        yield* rcon(
          containerName,
          `fill ${minimumX} ${spawn.y} ${minimumZ} ${maximumX} ${spawn.y + 8} ${maximumZ} air`,
        );
      }
    }
    yield* rcon(containerName, `setworldspawn ${spawn.x} ${spawn.y} ${spawn.z}`);
    const netherPortal = {
      x: Math.floor(spawn.x / 8),
      y: 49,
      z: Math.floor(spawn.z / 8),
    };
    yield* rcon(
      containerName,
      `execute in minecraft:the_nether run forceload add ${
        netherPortal.x - 16
      } ${netherPortal.z - 16} ${netherPortal.x + 16} ${netherPortal.z + 16}`,
    );
    yield* rcon(
      containerName,
      `execute in minecraft:the_nether run fill ${netherPortal.x - 16} ${
        netherPortal.y + 1
      } ${netherPortal.z - 16} ${netherPortal.x + 16} ${
        netherPortal.y + 12
      } ${netherPortal.z + 16} air`,
    );
    yield* rcon(
      containerName,
      `execute in minecraft:the_nether run fill ${netherPortal.x - 16} ${
        netherPortal.y
      } ${netherPortal.z - 16} ${netherPortal.x + 16} ${
        netherPortal.y
      } ${netherPortal.z + 16} stone`,
    );
    for (const command of [
      `fill ${netherPortal.x} ${netherPortal.y} ${netherPortal.z - 1} ${
        netherPortal.x
      } ${netherPortal.y} ${netherPortal.z + 2} obsidian`,
      `fill ${netherPortal.x} ${netherPortal.y + 4} ${
        netherPortal.z - 1
      } ${netherPortal.x} ${netherPortal.y + 4} ${netherPortal.z + 2} obsidian`,
      `fill ${netherPortal.x} ${netherPortal.y + 1} ${
        netherPortal.z - 1
      } ${netherPortal.x} ${netherPortal.y + 3} ${netherPortal.z - 1} obsidian`,
      `fill ${netherPortal.x} ${netherPortal.y + 1} ${
        netherPortal.z + 2
      } ${netherPortal.x} ${netherPortal.y + 3} ${netherPortal.z + 2} obsidian`,
      `fill ${netherPortal.x} ${netherPortal.y + 1} ${netherPortal.z} ${
        netherPortal.x
      } ${netherPortal.y + 3} ${netherPortal.z + 1} nether_portal[axis=z]`,
    ]) {
      yield* rcon(containerName, `execute in minecraft:the_nether run ${command}`);
    }
    const fixture = {
      mode: "controlled",
      containerName,
      dataDirectory: managedDataDirectory,
      freshWorld: true,
      managed: true,
      port,
      seed,
      stronghold,
      spawn,
      longTravelTarget: {
        x: spawn.x + 224.5,
        y: spawn.y,
        z: spawn.z + 0.5,
      },
    } satisfies ControlledMinecraftFixture;
    yield* record("minecraft-ready", {
      ...fixture,
      serverType: "PAPER",
      rconEnabled: true,
      netherPortal,
    });
    return fixture;
  });

  return start.pipe(
    Effect.onError(() =>
      fixtureConfiguration.attachedMinecraftContainer !== undefined ||
      fixtureConfiguration.keepContainer
        ? Effect.void
        : Effect.all(
            [
              docker(["rm", "--force", managedContainerName]).pipe(Effect.ignore),
              fromPromise("remove Minecraft data directory", () =>
                rm(managedDataDirectory, { recursive: true, force: true }),
              ).pipe(Effect.ignore),
            ],
            { discard: true },
          ),
    ),
  );
});

function stopMinecraftFixture(fixture: MinecraftFixture): Effect.Effect<void> {
  return Effect.gen(function* () {
    const logs = yield* docker(["logs", "--tail", "20000", fixture.containerName]).pipe(
      Effect.result,
    );
    if (logs._tag === "Success") {
      yield* fromPromise("write Minecraft logs", () =>
        minecraftLog.append(`${logs.success.stdout}${logs.success.stderr}`),
      ).pipe(Effect.ignore);
    }
    if (fixture.managed && !fixtureConfiguration.keepContainer) {
      yield* docker(["rm", "--force", fixture.containerName]).pipe(Effect.ignore);
      if (fixture.dataDirectory !== undefined) {
        const dataDirectory = fixture.dataDirectory;
        yield* fromPromise("remove Minecraft data directory", () =>
          rm(dataDirectory, { recursive: true, force: true }),
        ).pipe(Effect.ignore);
      }
    }
  });
}

async function resetReusableSoulFireRuntime(
  soulfireDirectory: string,
  dedicatedJar: string,
): Promise<void> {
  const serverDirectory = path.join(soulfireDirectory, "server");
  await mkdir(serverDirectory, { recursive: true });
  await stopReusableSoulFireProcesses(serverDirectory, dedicatedJar);
  await Promise.all([
    rm(path.join(serverDirectory, "soulfire.sqlite"), { force: true }),
    rm(path.join(serverDirectory, "soulfire.sqlite-shm"), { force: true }),
    rm(path.join(serverDirectory, "soulfire.sqlite-wal"), { force: true }),
    rm(path.join(serverDirectory, "object-storage"), {
      recursive: true,
      force: true,
    }),
    rm(path.join(serverDirectory, "logs"), { recursive: true, force: true }),
    rm(path.join(serverDirectory, "minecraft", "logs"), {
      recursive: true,
      force: true,
    }),
  ]);
  await rm(soulfireProcessFile, { force: true });
  await prepareSoulFireDownloadCache(soulfireDirectory);
}

async function stopReusableSoulFireProcesses(
  serverDirectory: string,
  dedicatedJar: string,
): Promise<void> {
  const [expectedRunDirectory, expectedJar] = await Promise.all([
    realpath(serverDirectory),
    realpath(dedicatedJar),
  ]);
  const recorded = await readSoulFireProcessRecord();
  const processIds = new Set<number>();
  if (recorded !== undefined && isProcessRunning(recorded.pid)) {
    const [recordedRunDirectory, recordedJar] = await Promise.all([
      realpath(recorded.runDirectory),
      realpath(recorded.jarPath),
    ]);
    if (recordedRunDirectory !== expectedRunDirectory || recordedJar !== expectedJar) {
      throw new Error(
        `Refusing to stop recorded process ${recorded.pid}: its Soulfire runtime does not match ${expectedRunDirectory}`,
      );
    }
    processIds.add(recorded.pid);
  }

  const procEntries = await readdir("/proc", { withFileTypes: true });
  await Promise.all(
    procEntries.flatMap((entry) => {
      const pid = Number(entry.name);
      if (!entry.isDirectory() || !Number.isSafeInteger(pid) || pid < 1) {
        return [];
      }
      return [
        matchesSoulFireProcess(pid, expectedRunDirectory, expectedJar).then((matches) => {
          if (matches) {
            processIds.add(pid);
          }
        }),
      ];
    }),
  );

  for (const pid of processIds) {
    await stopProcess(pid);
  }
}

async function readSoulFireProcessRecord(): Promise<SoulFireProcessRecord | undefined> {
  let source: string;
  try {
    source = await readFile(soulfireProcessFile, "utf8");
  } catch (cause) {
    if (isNodeError(cause, "ENOENT")) {
      return undefined;
    }
    throw cause;
  }
  const value: unknown = JSON.parse(source);
  if (
    typeof value !== "object" ||
    value === null ||
    !("pid" in value) ||
    !Number.isSafeInteger(value.pid) ||
    Number(value.pid) < 1 ||
    !("jarPath" in value) ||
    typeof value.jarPath !== "string" ||
    !("runDirectory" in value) ||
    typeof value.runDirectory !== "string"
  ) {
    throw new Error(`Invalid Soulfire process record at ${soulfireProcessFile}`);
  }
  return {
    jarPath: value.jarPath,
    pid: Number(value.pid),
    runDirectory: value.runDirectory,
  };
}

async function matchesSoulFireProcess(
  pid: number,
  expectedRunDirectory: string,
  expectedJar: string,
): Promise<boolean> {
  try {
    const [workingDirectory, commandLine] = await Promise.all([
      realpath(`/proc/${pid}/cwd`),
      readFile(`/proc/${pid}/cmdline`, "utf8"),
    ]);
    if (workingDirectory !== expectedRunDirectory) {
      return false;
    }
    const arguments_ = commandLine.split("\0").filter(Boolean);
    const jarFlag = arguments_.lastIndexOf("-jar");
    const jarArgument = arguments_[jarFlag + 1];
    return (
      jarFlag >= 0 && jarArgument !== undefined && (await realpath(jarArgument)) === expectedJar
    );
  } catch (cause) {
    if (
      isNodeError(cause, "ENOENT") ||
      isNodeError(cause, "EACCES") ||
      isNodeError(cause, "ESRCH")
    ) {
      return false;
    }
    throw cause;
  }
}

async function stopProcess(pid: number): Promise<void> {
  if (!isProcessRunning(pid) || !signalProcess(pid, "SIGTERM")) {
    return;
  }
  if (await waitForProcessExit(pid, 5_000)) {
    return;
  }
  if (!signalProcess(pid, "SIGKILL")) {
    return;
  }
  if (!(await waitForProcessExit(pid, 2_000))) {
    throw new Error(`Soulfire process ${pid} did not stop`);
  }
}

function signalProcess(pid: number, signal: NodeJS.Signals): boolean {
  try {
    process.kill(pid, signal);
    return true;
  } catch (cause) {
    if (isNodeError(cause, "ESRCH")) {
      return false;
    }
    throw cause;
  }
}

async function waitForProcessExit(pid: number, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!isProcessRunning(pid)) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return !isProcessRunning(pid);
}

function isProcessRunning(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (cause) {
    if (isNodeError(cause, "ESRCH")) {
      return false;
    }
    if (isNodeError(cause, "EPERM")) {
      return true;
    }
    throw cause;
  }
}

function isNodeError(cause: unknown, code: string): cause is NodeJS.ErrnoException {
  return cause instanceof Error && "code" in cause && cause.code === code;
}

async function prepareSoulFireDownloadCache(soulfireDirectory: string): Promise<void> {
  const serverDirectory = path.join(soulfireDirectory, "server");
  const sessionDownloadDirectory = path.join(serverDirectory, "mc-downloads");
  await Promise.all([
    mkdir(serverDirectory, { recursive: true }),
    mkdir(soulfireDownloadCacheDirectory, { recursive: true }),
  ]);
  try {
    const metadata = await lstat(sessionDownloadDirectory);
    if (metadata.isSymbolicLink()) {
      const target = await readlink(sessionDownloadDirectory);
      if (path.resolve(serverDirectory, target) === soulfireDownloadCacheDirectory) {
        return;
      }
    }
    await rm(sessionDownloadDirectory, { recursive: true, force: true });
  } catch (cause) {
    if (!isNodeError(cause, "ENOENT")) {
      throw cause;
    }
  }
  await symlink(soulfireDownloadCacheDirectory, sessionDownloadDirectory, "dir");
}

function provisionBot(
  fixture: ControlledMinecraftFixture,
  username: string,
): Effect.Effect<void, Error> {
  const commands = [
    "minecraft:cooked_beef 64",
    "minecraft:oak_log 64",
    "minecraft:cobblestone 64",
    "minecraft:iron_ingot 64",
    "minecraft:iron_pickaxe 1",
    "minecraft:water_bucket 1",
    "minecraft:flint_and_steel 1",
    "minecraft:shield 1",
    "minecraft:obsidian 64",
    "minecraft:blaze_rod 16",
    "minecraft:ender_pearl 32",
    "minecraft:ender_eye 32",
    "minecraft:bow 1",
    "minecraft:arrow 64",
    "minecraft:torch 64",
    "minecraft:diamond_sword 1",
    "minecraft:diamond_pickaxe 1",
    "minecraft:diamond_helmet 1",
    "minecraft:diamond_chestplate 1",
    "minecraft:diamond_leggings 1",
    "minecraft:diamond_boots 1",
  ].map((item) => `give ${username} ${item}`);
  return Effect.forEach(
    [
      ...commands,
      `effect give ${username} minecraft:resistance infinite 4 true`,
      `effect give ${username} minecraft:regeneration infinite 4 true`,
      `effect give ${username} minecraft:saturation infinite 0 true`,
      `spawnpoint ${username} ${fixture.spawn.x} ${fixture.spawn.y} ${fixture.spawn.z}`,
      `tp ${username} ${fixture.spawn.x + 0.5} ${fixture.spawn.y} ${fixture.spawn.z + 0.5}`,
    ],
    (command) =>
      rcon(fixture.containerName, command).pipe(
        Effect.flatMap((result) =>
          /No (?:player|entity) was found|Incorrect argument/iu.test(result.stdout)
            ? Effect.fail(
                new Error(`Minecraft rejected fixture command ${command}: ${result.stdout.trim()}`),
              )
            : Effect.void,
        ),
      ),
    {
      concurrency: 1,
      discard: true,
    },
  );
}

function controlEndEncounter(
  fixture: ControlledMinecraftFixture,
  username: string,
  bot: SoulFireBot,
): Effect.Effect<never> {
  return Effect.gen(function* () {
    const prepared = yield* Ref.make(false);
    const dying = yield* Ref.make(false);
    const tick = bot.world.player().pipe(
      Effect.flatMap((player) => {
        if (!isEnd(player.position?.dimension ?? "")) {
          return Effect.void;
        }
        return Effect.gen(function* () {
          const isPrepared = yield* Ref.get(prepared);
          if (!isPrepared) {
            yield* rcon(
              fixture.containerName,
              "execute in minecraft:the_end run kill @e[type=minecraft:end_crystal]",
            );
            yield* rcon(
              fixture.containerName,
              "execute in minecraft:the_end run fill 0 48 -8 105 48 8 minecraft:end_stone",
            );
            const dragon = yield* rcon(
              fixture.containerName,
              "execute in minecraft:the_end run attribute @e[type=minecraft:ender_dragon,limit=1] minecraft:max_health base set 1",
            );
            if (/No entity was found|Incorrect argument/iu.test(dragon.stdout)) {
              return;
            }
            const staged = yield* rcon(
              fixture.containerName,
              "execute in minecraft:the_end run data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:6}",
            );
            if (!staged.stdout.includes("Modified entity data")) {
              return;
            }
            const teleported = yield* rcon(
              fixture.containerName,
              `execute in minecraft:the_end at @a[name=${username},limit=1] run tp @e[type=minecraft:ender_dragon,limit=1] ~-20 ~3 ~`,
            );
            if (!teleported.stdout.includes("Teleported")) {
              return;
            }
            yield* Ref.set(prepared, true);
            yield* record("end-encounter-prepared", {
              dragon: dragon.stdout.trim(),
              staged: staged.stdout.trim(),
              teleported: teleported.stdout.trim(),
            });
          }
          if (yield* Ref.get(dying)) {
            return;
          }
          const phase = yield* rcon(
            fixture.containerName,
            "execute in minecraft:the_end run data get entity @e[type=minecraft:ender_dragon,limit=1] DragonPhase",
          );
          if (/following entity data:\s*9\s*$/iu.test(phase.stdout.trim())) {
            yield* Ref.set(dying, true);
            yield* record("end-encounter-dying", {
              phase: phase.stdout.trim(),
            });
            return;
          }
        });
      }),
      Effect.catch(() => Effect.void),
      Effect.andThen(Effect.sleep(1_000)),
    );
    return yield* Effect.forever(tick);
  });
}

const findDedicatedJar = fromPromise("find dedicated Soulfire JAR", async () => {
  const directory = path.join(repositoryRoot, "dedicated-launcher", "build", "libs");
  const candidates = (await readdir(directory)).filter(
    (name) =>
      /^SoulFireDedicated-.+\.jar$/u.test(name) &&
      !name.endsWith("-javadoc.jar") &&
      !name.endsWith("-sources.jar") &&
      !name.endsWith("-unshaded.jar"),
  );
  if (candidates.length !== 1) {
    throw new Error(
      `Expected one dedicated Soulfire JAR in ${directory}, found ${
        candidates.join(", ") || "none"
      }`,
    );
  }
  return path.join(directory, candidates[0]!);
});

const findJava = fromPromise("find Java", async () =>
  realpath(environment("SOULFIRE_E2E_JAVA_PATH", "/usr/bin/java")),
);

function docker(args: readonly string[]): Effect.Effect<CommandResult, Error> {
  return runCommand("docker", args);
}

function rcon(
  target: string | ControlledMinecraftFixture,
  command: string,
): Effect.Effect<CommandResult, Error> {
  const containerName = typeof target === "string" ? target : target.containerName;
  return docker(["exec", containerName, "rcon-cli", command]).pipe(
    Effect.tap((result) => record("rcon", { command, output: result.stdout.trim() })),
  );
}

function waitForMinecraftServer(containerName: string): Effect.Effect<CommandResult, Error> {
  return poll(
    docker(["logs", containerName]).pipe(
      Effect.flatMap((result) =>
        /Done \([^)]+\)! For help/iu.test(`${result.stdout}\n${result.stderr}`)
          ? Effect.succeed(result)
          : Effect.fail(new Error("Minecraft has not finished starting")),
      ),
    ),
    "Minecraft server readiness",
    300,
    1_000,
  );
}

function publishedMinecraftPort(containerName: string): Effect.Effect<number, Error> {
  return docker(["port", containerName, "25565/tcp"]).pipe(
    Effect.map((result) => parsePublishedPort(result.stdout)),
  );
}

function assertAdminCommandPolicy(fixture: SurvivalMinecraftFixture): Effect.Effect<void, Error> {
  return docker(["logs", fixture.containerName]).pipe(
    Effect.flatMap((result) => {
      const logs = `${result.stdout}\n${result.stderr}`;
      const adminLines = logs
        .split(/\r?\n/u)
        .filter((line) => /\[Rcon:|issued server command:|made .* a server operator/iu.test(line));
      if (adminLines.length > 0) {
        return Effect.fail(
          new Error(
            `Authoritative smoke violated its administrative command policy:\n${adminLines.join(
              "\n",
            )}`,
          ),
        );
      }
      return record("admin-command-audit", {
        passed: true,
        rconEnabled: false,
        commandsObserved: 0,
      });
    }),
  );
}

function runCommand(command: string, args: readonly string[]): Effect.Effect<CommandResult, Error> {
  return fromPromise(`${command} ${args.join(" ")}`, async () => {
    const result = await execFile(command, [...args], {
      maxBuffer: 16 * 1024 * 1024,
    });
    return {
      stdout: result.stdout,
      stderr: result.stderr,
    };
  });
}

function guardStartupAir(bot: SoulFireBot): Effect.Effect<never> {
  const tick = bot.world.player().pipe(
    Effect.flatMap((player) =>
      !player.dead && player.maxAir > 0 && player.air < player.maxAir
        ? bot.setMovement({ jump: true })
        : bot.resetMovement(),
    ),
    Effect.catch(() => Effect.void),
    Effect.andThen(Effect.sleep(100)),
  );
  return tick.pipe(Effect.forever);
}

function poll<A>(
  effect: Effect.Effect<A, Error>,
  description: string,
  attempts: number,
  delayMs: number,
): Effect.Effect<A, Error> {
  return effect.pipe(
    Effect.catch((cause) =>
      attempts <= 1
        ? Effect.fail(
            new Error(`${description} did not become ready`, {
              cause,
            }),
          )
        : Effect.sleep(delayMs).pipe(
            Effect.andThen(poll(effect, description, attempts - 1, delayMs)),
          ),
    ),
  );
}

function debugRequest<A>(evaluate: () => A): Effect.Effect<A, SmokeDebugRequestError> {
  return Effect.try({
    try: evaluate,
    catch: (cause) =>
      cause instanceof SmokeDebugRequestError
        ? cause
        : new SmokeDebugRequestError(cause instanceof Error ? cause.message : String(cause)),
  });
}

function queryDebugTimeline(input: unknown): readonly unknown[] {
  const request = debugRecord(input, "event query");
  const kinds = optionalDebugString(request, "kind")
    ?.split(",")
    .map((kind) => kind.trim())
    .filter((kind) => kind.length > 0);
  return debugTimeline.query({
    ...(kinds === undefined || kinds.length === 0 ? {} : { kinds }),
    limit: optionalDebugInteger(request, "limit", 1, debugApiTimelineEntries) ?? 250,
  });
}

function captureSmokeDiagnostics(
  options: Readonly<{
    activePath: SmokeActivePathTrace | undefined;
    bot: SoulFireBot;
    driver: BeatGameDriver;
    environment: () => unknown;
    input: unknown;
    lastPathOutcome: SmokePathOutcome | undefined;
    run: BeatGameRun;
    strategy: BeatGameStrategy;
  }>,
): Effect.Effect<unknown, unknown> {
  return Effect.gen(function* () {
    const request = yield* debugRequest(() =>
      debugRecord(options.input, "diagnostic snapshot request"),
    );
    const localBlockRadius = yield* debugRequest(
      () => optionalDebugInteger(request, "blockRadius", 1, 12) ?? 5,
    );
    const entityRadius = yield* debugRequest(
      () => optionalDebugInteger(request, "entityRadius", 1, 128) ?? 48,
    );
    const surfaceRadius = yield* debugRequest(
      () => optionalDebugInteger(request, "surfaceRadius", 1, 64) ?? 12,
    );
    const historyLimit = yield* debugRequest(
      () => optionalDebugInteger(request, "historyLimit", 1, 200) ?? 40,
    );
    const startedAt = new Date().toISOString();
    const [snapshot, observation] = yield* Effect.all(
      [options.run.snapshot, options.driver.observe],
      { concurrency: "unbounded" },
    );
    const origin = observation.player.position;
    const [blocks, entities, surface] = yield* Effect.all(
      [
        options.driver.queryBlocks({
          center: origin,
          radius: localBlockRadius,
          selector: {},
          maximumResults: Math.ceil((4 / 3) * Math.PI * localBlockRadius ** 3) + 32,
        }),
        options.driver.queryEntities({
          origin,
          radius: entityRadius,
          selector: {},
          maximumResults: 256,
        }),
        options.driver.sampleSurface(origin, surfaceRadius, 2),
      ],
      { concurrency: "unbounded" },
    );
    const finalPlayer = yield* options.bot.world.player();
    const completedAt = new Date().toISOString();
    const planner = snapshot.checkpoint.planner;
    const finalPosition = finalPlayer.position ?? origin;
    const nextIfReplanned = decideBeatGameAction({
      checkpoint: snapshot.checkpoint,
      observation,
      strategy: options.strategy,
    });
    return {
      run: {
        runId: snapshot.checkpoint.runId,
        phase: planner.phase,
        status: planner.status,
        objective: planner.objective,
        currentAction: planner.currentAction,
        currentActionId: planner.currentActionId,
        retryCount: planner.retryCount,
        pendingRequirements: planner.requirements.filter((requirement) => !requirement.satisfied),
        lastStableAction: snapshot.checkpoint.lastStableAction,
      },
      player: observation.player,
      inventory: observation.inventory,
      environment: options.environment(),
      decision: {
        ...buildSmokeDecisionDiagnostics({
          checkpoint: snapshot.checkpoint,
          observation,
          strategy: options.strategy,
          nextIfReplanned,
        }),
        latestActionEvent: latestDebugTimelineEntry(["beat-game-event"], (entry) =>
          isBeatGameActionEvent(entry),
        ),
        activity: queryCurrentDecisionActivity(planner.currentAction, 100),
      },
      spatial: buildSmokeSpatialDiagnostics({
        origin,
        originVelocity: observation.player.velocity,
        finalPosition,
        localBlockRadius,
        entityRadius,
        surfaceRadius,
        startedAt,
        completedAt,
        blocks,
        entities,
        surface,
      }),
      pathfinding: {
        active:
          options.activePath === undefined
            ? undefined
            : buildSmokeActivePathDiagnostics(options.activePath, finalPosition, completedAt),
        lastOutcome: options.lastPathOutcome,
      },
      decisions: querySignificantDebugActivity(historyLimit),
      currentIntent: {
        taskStarted: latestDebugTimelineEntry("task-started"),
        taskProgress: latestDebugTimelineEntry("task-progress-observed"),
        taskFailure: latestDebugTimelineEntry(["task-failed", "task-interrupted"]),
        pathStarted: latestDebugTimelineEntry(["pathfind-started", "pathfind-xz-started"]),
        pathFailure: latestDebugTimelineEntry([
          "pathfind-failed",
          "pathfind-interrupted",
          "pathfind-xz-failed",
          "pathfind-xz-interrupted",
        ]),
        primitiveStarted: latestDebugTimelineEntry("primitive-started"),
        primitiveFailure: latestDebugTimelineEntry(["primitive-failed", "primitive-interrupted"]),
      },
      paths: queryDebugTimeline({
        kind: [
          "pathfind-started",
          "pathfind-completed",
          "pathfind-failed",
          "pathfind-interrupted",
          "pathfind-xz-started",
          "pathfind-xz-completed",
          "pathfind-xz-failed",
          "pathfind-xz-interrupted",
        ].join(","),
        limit: historyLimit,
      }),
      tasks: queryDebugTimeline({
        kind: [
          "task-started",
          "task-progress-observed",
          "task-completed",
          "task-failed",
          "task-interrupted",
        ].join(","),
        limit: historyLimit,
      }),
    };
  });
}

function latestDebugTimelineEntry(
  kinds: string | readonly string[],
  predicate: (entry: unknown) => boolean = () => true,
): unknown {
  return debugTimeline
    .query({
      kinds: typeof kinds === "string" ? [kinds] : kinds,
      limit: debugApiTimelineEntries,
    })
    .findLast(predicate);
}

function isBeatGameActionEvent(entry: unknown): boolean {
  if (!isDebugRecord(entry) || entry.kind !== "beat-game-event") {
    return false;
  }
  const event = entry.event;
  return (
    isDebugRecord(event) &&
    typeof event.type === "string" &&
    ["action-started", "action-retried", "action-succeeded", "action-failed"].includes(event.type)
  );
}

function querySignificantDebugActivity(limit: number): readonly unknown[] {
  return debugTimeline
    .query({
      kinds: [
        "beat-game-event",
        "primitive-started",
        "primitive-failed",
        "primitive-interrupted",
        "task-started",
        "task-failed",
        "task-interrupted",
      ],
      limit: debugApiTimelineEntries,
    })
    .filter((entry) => {
      if (!isDebugRecord(entry) || entry.kind !== "beat-game-event") {
        return true;
      }
      const event = entry.event;
      return (
        isDebugRecord(event) &&
        typeof event.type === "string" &&
        !["checkpoint-saved", "observation-recorded", "team-claim-changed"].includes(event.type)
      );
    })
    .slice(-limit);
}

function queryCurrentDecisionActivity(currentAction: string | undefined, limit: number) {
  const activity = debugTimeline.query({
    kinds: [
      "beat-game-event",
      "block-query",
      "entity-query",
      "pathfind-started",
      "pathfind-completed",
      "pathfind-failed",
      "pathfind-interrupted",
      "pathfind-xz-started",
      "pathfind-xz-completed",
      "pathfind-xz-failed",
      "pathfind-xz-interrupted",
      "player-vitals-observed",
      "primitive-started",
      "primitive-completed",
      "primitive-failed",
      "primitive-interrupted",
      "task-started",
      "task-progress-observed",
      "task-completed",
      "task-failed",
      "task-interrupted",
    ],
    limit: debugApiTimelineEntries,
  });
  if (currentAction === undefined) {
    return {
      action: undefined,
      correlatedSince: undefined,
      entries: [],
      recentActionOutcomes: recentActionOutcomes(activity),
    };
  }
  const started = activity.findLast((entry) => {
    if (!isDebugRecord(entry) || entry.kind !== "beat-game-event") {
      return false;
    }
    const event = entry.event;
    return (
      isDebugRecord(event) && event.type === "action-started" && event.action === currentAction
    );
  });
  const correlatedSince = debugObservedAt(started);
  const activeContext = currentDebugActionContext();
  const effectiveCorrelatedSince =
    correlatedSince ??
    (activeContext?.action === currentAction ? activeContext.startedAt : undefined);
  return {
    action: currentAction,
    correlatedSince: effectiveCorrelatedSince,
    correlation:
      effectiveCorrelatedSince === undefined
        ? "No active action start is available"
        : correlatedSince === undefined
          ? "Entries are correlated from the retained active action context"
          : "Entries are correlated from the latest matching action-start event",
    entries: activity
      .filter((entry) => {
        const observedAt = debugObservedAt(entry);
        return (
          effectiveCorrelatedSince === undefined ||
          observedAt === undefined ||
          observedAt >= effectiveCorrelatedSince
        );
      })
      .slice(-limit)
      .map(compactDecisionActivityEntry),
    recentActionOutcomes: recentActionOutcomes(activity),
  };
}

function compactDecisionActivityEntry(entry: unknown): unknown {
  if (!isDebugRecord(entry)) {
    return entry;
  }
  if (entry.kind === "block-query") {
    return compactBlockQueryActivity(entry);
  }
  if (entry.kind !== "entity-query") {
    return entry;
  }
  const entities = Array.isArray(entry.entities) ? entry.entities.filter(isDebugRecord) : [];
  const byEntityType = new Map<string, number>();
  for (const entity of entities) {
    const entityType = typeof entity.entityType === "string" ? entity.entityType : "unknown";
    byEntityType.set(entityType, (byEntityType.get(entityType) ?? 0) + 1);
  }
  const { entities: _entities, ...metadata } = entry;
  return {
    ...metadata,
    result: {
      count: entities.length,
      byEntityType: [...byEntityType]
        .map(([entityType, count]) => ({ entityType, count }))
        .sort(
          (left, right) =>
            right.count - left.count || left.entityType.localeCompare(right.entityType),
        ),
      sample: entities.slice(0, 12).map((entity) => ({
        networkId: entity.networkId,
        entityType: entity.entityType,
        position: entity.position,
        alive: entity.alive,
        ...(entity.health === undefined ? {} : { health: entity.health }),
        ...(entity.itemId === undefined ? {} : { itemId: entity.itemId }),
        ...(entity.target === undefined ? {} : { target: entity.target }),
      })),
    },
  };
}

function compactBlockQueryActivity(entry: Readonly<Record<string, unknown>>): unknown {
  const blocks = Array.isArray(entry.blocks) ? entry.blocks.filter(isDebugRecord) : [];
  const byBlockId = new Map<string, number>();
  for (const block of blocks) {
    const blockId = typeof block.blockId === "string" ? block.blockId : "unknown";
    byBlockId.set(blockId, (byBlockId.get(blockId) ?? 0) + 1);
  }
  const { blocks: _blocks, ...metadata } = entry;
  return {
    ...metadata,
    result: {
      count: typeof entry.resultCount === "number" ? entry.resultCount : blocks.length,
      sampleTruncated: entry.resultsTruncated === true,
      byBlockId: [...byBlockId]
        .map(([blockId, count]) => ({ blockId, count }))
        .sort(
          (left, right) => right.count - left.count || left.blockId.localeCompare(right.blockId),
        ),
      sample: blocks.slice(0, 16).map((block) => ({
        blockId: block.blockId,
        position: block.position,
        properties: block.properties,
        diggable: block.diggable,
        replaceable: block.replaceable,
        solid: block.solid,
      })),
    },
  };
}

function shouldTraceBlockQuery(query: BeatGameQueryBlocks): boolean {
  return (
    query.radius <= 0.5 ||
    Object.values(query.selector).some(
      (value) =>
        value !== undefined &&
        (!Array.isArray(value) || value.length > 0) &&
        (typeof value !== "object" || value === null || Object.keys(value).length > 0),
    )
  );
}

function recentActionOutcomes(activity: readonly unknown[]): readonly unknown[] {
  return activity
    .filter((entry) => {
      if (!isDebugRecord(entry) || entry.kind !== "beat-game-event") {
        return false;
      }
      const event = entry.event;
      return (
        isDebugRecord(event) &&
        typeof event.type === "string" &&
        ["action-succeeded", "action-failed"].includes(event.type)
      );
    })
    .slice(-12);
}

function debugObservedAt(entry: unknown): string | undefined {
  if (!isDebugRecord(entry)) {
    return undefined;
  }
  return typeof entry.observedAt === "string" ? entry.observedAt : undefined;
}

function isDebugRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseDebugBlockRequest(input: unknown): Readonly<{
  position: Readonly<{ x: number; y: number; z: number; dimension: string }>;
  includeBlockEntity: boolean;
  includeShapes: boolean;
}> {
  const request = debugRecord(input, "block request");
  return {
    position: debugPosition(request.position, "position", true),
    includeBlockEntity: optionalDebugBoolean(request, "includeBlockEntity") ?? true,
    includeShapes: optionalDebugBoolean(request, "includeShapes") ?? true,
  };
}

function inspectDebugWorldVolume(
  bot: SoulFireBot,
  input: unknown,
): Effect.Effect<unknown, unknown, never> {
  return Effect.gen(function* () {
    const parsed = yield* debugRequest(() => {
      const request = debugRecord(input, "nearby world request");
      return {
        center:
          request.center === undefined ? undefined : debugPosition(request.center, "center", true),
        horizontalRadius: optionalDebugInteger(request, "horizontalRadius", 0, 32) ?? 8,
        verticalRadius: optionalDebugInteger(request, "verticalRadius", 0, 16) ?? 4,
        includeAir: optionalDebugBoolean(request, "includeAir") ?? false,
        includeBlockEntity: optionalDebugBoolean(request, "includeBlockEntity") ?? true,
        includeShapes: optionalDebugBoolean(request, "includeShapes") ?? false,
      };
    });
    const center =
      parsed.center ??
      (yield* bot.world.player().pipe(
        Effect.flatMap((player) =>
          player.position === undefined
            ? Effect.fail(new SmokeDebugRequestError("The bot player has no current position", 409))
            : Effect.succeed({
                x: Math.floor(player.position.x),
                y: Math.floor(player.position.y),
                z: Math.floor(player.position.z),
                dimension: player.position.dimension,
              }),
        ),
      ));
    const width = parsed.horizontalRadius * 2 + 1;
    const height = parsed.verticalRadius * 2 + 1;
    const volume = width * width * height;
    if (volume > 32_768) {
      return yield* Effect.fail(
        new SmokeDebugRequestError(
          `Requested block volume ${volume} exceeds the 32768 block limit`,
        ),
      );
    }
    const positions: Array<
      Readonly<{
        x: number;
        y: number;
        z: number;
        dimension: string;
      }>
    > = [];
    for (
      let x = center.x - parsed.horizontalRadius;
      x <= center.x + parsed.horizontalRadius;
      x += 1
    ) {
      for (
        let y = center.y - parsed.verticalRadius;
        y <= center.y + parsed.verticalRadius;
        y += 1
      ) {
        for (
          let z = center.z - parsed.horizontalRadius;
          z <= center.z + parsed.horizontalRadius;
          z += 1
        ) {
          positions.push({ x, y, z, dimension: center.dimension });
        }
      }
    }
    const blocks = yield* Effect.forEach(
      positions,
      (position) =>
        bot.world
          .block({
            position,
            includeBlockEntity: parsed.includeBlockEntity,
            includeShapes: parsed.includeShapes,
          })
          .pipe(Effect.map((response) => ({ position, ...response }))),
      { concurrency: 32 },
    );
    return {
      center,
      horizontalRadius: parsed.horizontalRadius,
      verticalRadius: parsed.verticalRadius,
      scanned: blocks.length,
      blocks: parsed.includeAir
        ? blocks
        : blocks.filter(({ block }) => block !== undefined && block.blockId !== "minecraft:air"),
    };
  });
}

function parseDebugBlockQuery(input: unknown): BeatGameQueryBlocks {
  const request = debugRecord(input, "block query");
  return {
    center: debugPosition(request.center, "center"),
    radius: debugNumber(request, "radius", 0, 512),
    selector: debugBlockSelector(request.selector),
    ...(request.maximumResults === undefined
      ? {}
      : {
          maximumResults: debugInteger(request, "maximumResults", 1, 10_000),
        }),
  };
}

function parseDebugEntityQuery(input: unknown): BeatGameQueryEntities {
  const request = debugRecord(input, "entity query");
  return {
    ...(request.origin === undefined ? {} : { origin: debugPosition(request.origin, "origin") }),
    radius: debugNumber(request, "radius", 0, 1_024),
    selector: debugEntitySelector(request.selector),
    ...(request.maximumResults === undefined
      ? {}
      : {
          maximumResults: debugInteger(request, "maximumResults", 1, 10_000),
        }),
  };
}

function parseDebugRaycast(input: unknown): BeatGameRaycastQuery {
  const request = debugRecord(input, "raycast request");
  const direction = debugRecord(request.direction, "direction");
  return {
    direction: {
      x: debugNumber(direction, "x", -1, 1),
      y: debugNumber(direction, "y", -1, 1),
      z: debugNumber(direction, "z", -1, 1),
    },
    maximumDistance: debugNumber(request, "maximumDistance", 0.01, 512),
    ...(request.includeFluids === undefined
      ? {}
      : { includeFluids: debugBoolean(request, "includeFluids") }),
  };
}

function parseDebugSurfaceRequest(input: unknown): Readonly<{
  center: BeatGamePosition;
  radius: number;
  sampleStep: number;
}> {
  const request = debugRecord(input, "surface request");
  return {
    center: debugPosition(request.center, "center"),
    radius: optionalDebugInteger(request, "radius", 0, 512) ?? 16,
    sampleStep: optionalDebugInteger(request, "sampleStep", 1, 64) ?? 1,
  };
}

function parseDebugPathPlan(input: unknown): Readonly<{
  position: BeatGamePosition;
  radius: number;
  policy: BeatGamePathPolicy;
}> {
  const request = debugRecord(input, "path plan request");
  const suppliedPolicy = request.policy === undefined ? {} : debugRecord(request.policy, "policy");
  const defaults = defaultBeatGameStrategy.path;
  return {
    position: debugPosition(request.position, "position"),
    radius: optionalDebugNumber(request, "radius", 0, 128) ?? 1,
    policy: {
      allowMining: optionalDebugBoolean(suppliedPolicy, "allowMining") ?? defaults.allowMining,
      allowPlacing: optionalDebugBoolean(suppliedPolicy, "allowPlacing") ?? defaults.allowPlacing,
      maxFallDistance:
        optionalDebugInteger(suppliedPolicy, "maxFallDistance", 0, 64) ?? defaults.maxFallDistance,
      maxSearchTimeMs:
        optionalDebugInteger(suppliedPolicy, "maxSearchTimeMs", 1, 10 * 60_000) ??
        defaults.maxSearchTimeMs,
      ...(suppliedPolicy.avoidFluids === undefined
        ? {}
        : {
            avoidFluids: debugBoolean(suppliedPolicy, "avoidFluids"),
          }),
      ...(suppliedPolicy.sprint === undefined
        ? {}
        : { sprint: debugBoolean(suppliedPolicy, "sprint") }),
      ...(suppliedPolicy.minimumY === undefined
        ? {}
        : {
            minimumY: debugInteger(suppliedPolicy, "minimumY", -2_048, 2_048),
          }),
      ...(suppliedPolicy.maximumY === undefined
        ? {}
        : {
            maximumY: debugInteger(suppliedPolicy, "maximumY", -2_048, 2_048),
          }),
      ...(suppliedPolicy.additionalPlaceItemIds === undefined
        ? {}
        : {
            additionalPlaceItemIds: debugStringArray(
              suppliedPolicy.additionalPlaceItemIds,
              "policy.additionalPlaceItemIds",
            ),
          }),
    },
  };
}

function captureSmokeActivePath(
  options: Readonly<{
    activePath: SmokeActivePathTrace | undefined;
    bot: SoulFireBot;
    includePlan: boolean;
    lastPathOutcome: SmokePathOutcome | undefined;
    taskRoute: ReturnType<typeof latestSmokeTaskRoute>;
  }>,
): Effect.Effect<unknown, unknown> {
  if (options.activePath === undefined) {
    return Effect.succeed({
      status: options.taskRoute === undefined ? "idle" : "task-active",
      taskRoute: options.taskRoute,
      lastOutcome: options.lastPathOutcome,
    });
  }
  const trace = options.activePath;
  return Effect.gen(function* () {
    const player = yield* options.bot.world.player();
    if (player.position === undefined) {
      return yield* Effect.fail(
        new SmokeDebugRequestError("The bot player has no current position", 409),
      );
    }
    const diagnostics = buildSmokeActivePathDiagnostics(
      trace,
      player.position,
      new Date().toISOString(),
    );
    if (!options.includePlan) {
      return {
        ...diagnostics,
        taskRoute: options.taskRoute,
        lastOutcome: options.lastPathOutcome,
      };
    }
    const goal =
      trace.goal.type === "position"
        ? goals.near(trace.goal.position, trace.goal.radius)
        : goals.xz(trace.goal.x, trace.goal.z, {
            dimension: trace.goal.dimension,
            radius: trace.goal.radius,
          });
    const plan = yield* options.bot.pathfinder
      .plan(goal, {
        path: pathfinderOptions(trace.policy),
        includeDescriptions: true,
      })
      .pipe(
        Effect.map((result) => ({ ok: true as const, result })),
        Effect.catch((cause) =>
          Effect.succeed({
            ok: false as const,
            error: cause instanceof Error ? cause.message : String(cause),
          }),
        ),
      );
    return {
      ...diagnostics,
      taskRoute: options.taskRoute,
      plan,
      lastOutcome: options.lastPathOutcome,
    };
  });
}

function latestSmokeTaskRoute() {
  const capturedAt = new Date().toISOString();
  const activity = debugTimeline.query({
    kinds: [
      "task-started",
      "task-progress-observed",
      "task-completed",
      "task-failed",
      "task-interrupted",
    ],
    limit: debugApiTimelineEntries,
  });
  const latest = activity.at(-1);
  if (!isDebugRecord(latest) || latest.kind !== "task-progress-observed") {
    return undefined;
  }
  return buildSmokeStuckDiagnostics({
    capturedAt,
    activity,
  }).latestTask;
}

function pathfinderOptions(policy: BeatGamePathPolicy) {
  const timeoutSeconds = Math.max(1, Math.ceil(policy.maxSearchTimeMs / 1_000));
  return {
    allowMining: policy.allowMining,
    allowPlacing: policy.allowPlacing,
    avoidFluids: policy.avoidFluids ?? false,
    timeoutSeconds,
    searchTimeoutSeconds: timeoutSeconds,
    ...(policy.additionalPlaceItemIds === undefined || policy.additionalPlaceItemIds.length === 0
      ? {}
      : { additionalPlaceItemIds: [...policy.additionalPlaceItemIds] }),
    ...(policy.sprint === undefined ? {} : { sprint: policy.sprint }),
    ...(policy.minimumY === undefined ? {} : { minimumY: policy.minimumY }),
    ...(policy.maximumY === undefined ? {} : { maximumY: policy.maximumY }),
  };
}

function evaluateDebugSource(
  input: unknown,
  context: Readonly<Record<string, unknown>>,
): Effect.Effect<unknown, SmokeDebugRequestError | Error> {
  return debugRequest(() => {
    const request = debugRecord(input, "eval request");
    return debugString(request, "source");
  }).pipe(
    Effect.flatMap((source) =>
      Effect.tryPromise({
        try: async () => {
          const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor as new (
            ...parameters: string[]
          ) => (context: Readonly<Record<string, unknown>>) => Promise<unknown>;
          const evaluator = new AsyncFunction(
            "context",
            `"use strict";\n${source}\n//# sourceURL=soulfire-smoke-debug-eval.js`,
          );
          const result = await evaluator(
            Object.freeze({
              ...context,
              runEffect: Effect.runPromise,
            }),
          );
          return Effect.isEffect(result)
            ? await Effect.runPromise(result as Effect.Effect<unknown, unknown>)
            : result;
        },
        catch: (cause) => (cause instanceof Error ? cause : new Error(String(cause))),
      }),
    ),
  );
}

function debugBlockSelector(input: unknown): BeatGameBlockSelector {
  const selector = input === undefined ? {} : debugRecord(input, "block selector");
  return {
    ...(selector.blockIds === undefined
      ? {}
      : { blockIds: debugStringArray(selector.blockIds, "selector.blockIds") }),
    ...(selector.tags === undefined
      ? {}
      : { tags: debugStringArray(selector.tags, "selector.tags") }),
    ...(selector.properties === undefined
      ? {}
      : {
          properties: debugStringRecord(selector.properties, "selector.properties"),
        }),
    ...debugOptionalBooleans(selector, [
      "solid",
      "replaceable",
      "interactive",
      "diggable",
      "requireLineOfSight",
    ]),
  };
}

function debugEntitySelector(input: unknown): BeatGameEntitySelector {
  const selector = input === undefined ? {} : debugRecord(input, "entity selector");
  return {
    ...(selector.entityTypes === undefined
      ? {}
      : {
          entityTypes: debugStringArray(selector.entityTypes, "selector.entityTypes"),
        }),
    ...(selector.tags === undefined
      ? {}
      : { tags: debugStringArray(selector.tags, "selector.tags") }),
    ...(selector.categories === undefined
      ? {}
      : {
          categories: debugNumberArray(selector.categories, "selector.categories"),
        }),
    ...(selector.uuid === undefined ? {} : { uuid: debugString(selector, "uuid") }),
    ...(selector.networkId === undefined ? {} : { networkId: debugInteger(selector, "networkId") }),
    ...debugOptionalBooleans(selector, ["alive", "requireLineOfSight"]),
  };
}

function debugOptionalBooleans(
  input: Readonly<Record<string, unknown>>,
  keys: readonly string[],
): Readonly<Record<string, boolean>> {
  return Object.fromEntries(
    keys.flatMap((key) => (input[key] === undefined ? [] : [[key, debugBoolean(input, key)]])),
  );
}

function debugPosition(input: unknown, name: string, integer = false): BeatGamePosition {
  const position = debugRecord(input, name);
  const coordinate = (key: "x" | "y" | "z") => {
    const value = debugNumber(position, key);
    if (integer && !Number.isSafeInteger(value)) {
      throw new SmokeDebugRequestError(`${name}.${key} must be an integer`);
    }
    return value;
  };
  return {
    x: coordinate("x"),
    y: coordinate("y"),
    z: coordinate("z"),
    dimension: debugString(position, "dimension"),
  };
}

function debugRecord(input: unknown, name: string): Readonly<Record<string, unknown>> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new SmokeDebugRequestError(`${name} must be an object`);
  }
  return input as Readonly<Record<string, unknown>>;
}

function debugString(input: Readonly<Record<string, unknown>>, key: string): string {
  const value = input[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new SmokeDebugRequestError(`${key} must be a non-empty string`);
  }
  return value;
}

function optionalDebugString(
  input: Readonly<Record<string, unknown>>,
  key: string,
): string | undefined {
  return input[key] === undefined ? undefined : debugString(input, key);
}

function debugNumber(
  input: Readonly<Record<string, unknown>>,
  key: string,
  minimum = -Number.MAX_VALUE,
  maximum = Number.MAX_VALUE,
): number {
  const value = input[key];
  if (typeof value !== "number" || !Number.isFinite(value) || value < minimum || value > maximum) {
    throw new SmokeDebugRequestError(
      `${key} must be a finite number between ${minimum} and ${maximum}`,
    );
  }
  return value;
}

function optionalDebugNumber(
  input: Readonly<Record<string, unknown>>,
  key: string,
  minimum = -Number.MAX_VALUE,
  maximum = Number.MAX_VALUE,
): number | undefined {
  return input[key] === undefined ? undefined : debugNumber(input, key, minimum, maximum);
}

function debugInteger(
  input: Readonly<Record<string, unknown>>,
  key: string,
  minimum = Number.MIN_SAFE_INTEGER,
  maximum = Number.MAX_SAFE_INTEGER,
): number {
  const value = input[key];
  const parsed = typeof value === "string" && value.trim().length > 0 ? Number(value) : value;
  if (
    typeof parsed !== "number" ||
    !Number.isSafeInteger(parsed) ||
    parsed < minimum ||
    parsed > maximum
  ) {
    throw new SmokeDebugRequestError(`${key} must be an integer between ${minimum} and ${maximum}`);
  }
  return parsed;
}

function optionalDebugInteger(
  input: Readonly<Record<string, unknown>>,
  key: string,
  minimum = Number.MIN_SAFE_INTEGER,
  maximum = Number.MAX_SAFE_INTEGER,
): number | undefined {
  return input[key] === undefined ? undefined : debugInteger(input, key, minimum, maximum);
}

function debugBoolean(input: Readonly<Record<string, unknown>>, key: string): boolean {
  const value = input[key];
  if (typeof value !== "boolean") {
    throw new SmokeDebugRequestError(`${key} must be a boolean`);
  }
  return value;
}

function optionalDebugBoolean(
  input: Readonly<Record<string, unknown>>,
  key: string,
): boolean | undefined {
  return input[key] === undefined ? undefined : debugBoolean(input, key);
}

function debugStringArray(input: unknown, name: string): readonly string[] {
  if (
    !Array.isArray(input) ||
    !input.every((value) => typeof value === "string" && value.length > 0)
  ) {
    throw new SmokeDebugRequestError(`${name} must be an array of non-empty strings`);
  }
  return input;
}

function debugNumberArray(input: unknown, name: string): readonly number[] {
  if (
    !Array.isArray(input) ||
    !input.every((value) => typeof value === "number" && Number.isFinite(value))
  ) {
    throw new SmokeDebugRequestError(`${name} must be an array of finite numbers`);
  }
  return input;
}

function debugStringRecord(input: unknown, name: string): Readonly<Record<string, string>> {
  const record = debugRecord(input, name);
  if (!Object.values(record).every((value) => typeof value === "string")) {
    throw new SmokeDebugRequestError(`${name} values must all be strings`);
  }
  return record as Readonly<Record<string, string>>;
}

function record(
  kind: string,
  value: Readonly<Record<string, unknown>>,
): Effect.Effect<void, Error> {
  return Effect.suspend(() => {
    const entry = { observedAt: new Date().toISOString(), kind, ...value };
    if (!debugTimelineOmittedKinds.has(kind)) {
      debugTimeline.append(entry);
    }
    const line = json(entry);
    if (verboseOutput) {
      process.stdout.write(`${line}\n`);
    }
    return fromPromise(`record ${kind}`, () => eventLog.append(`${line}\n`));
  });
}

interface SmokeDebugActionContext {
  readonly action: string;
  readonly actionId?: string;
  readonly phase?: string;
  readonly sequence?: string;
  readonly attempt?: number;
  readonly startedAt?: string;
}

function currentDebugActionContext(): SmokeDebugActionContext | undefined {
  if (activeDebugActionContext !== undefined) {
    return activeDebugActionContext;
  }
  const latest = debugTimeline
    .query({
      kinds: ["beat-game-event"],
      limit: debugApiTimelineEntries,
    })
    .findLast((entry) => {
      if (!isDebugRecord(entry) || entry.kind !== "beat-game-event") {
        return false;
      }
      const event = entry.event;
      return (
        isDebugRecord(event) &&
        typeof event.type === "string" &&
        ["action-started", "action-retried", "action-succeeded", "action-failed"].includes(
          event.type,
        )
      );
    });
  if (!isDebugRecord(latest) || !isDebugRecord(latest.event)) {
    return undefined;
  }
  const event = latest.event;
  if (event.type !== "action-started" && event.type !== "action-retried") {
    return undefined;
  }
  if (typeof event.action !== "string") {
    return undefined;
  }
  return {
    action: event.action,
    ...(typeof event.actionId === "string" ? { actionId: event.actionId } : {}),
    ...(typeof event.phase === "string" ? { phase: event.phase } : {}),
    ...(typeof event.sequence === "string" ? { sequence: event.sequence } : {}),
    ...(typeof event.attempt === "number" ? { attempt: event.attempt } : {}),
    ...(typeof latest.observedAt === "string" ? { startedAt: latest.observedAt } : {}),
  };
}

function updateDebugActionContext(event: BeatGameEvent): void {
  if (event.type === "action-started" || event.type === "action-retried") {
    const existing =
      activeDebugActionContext?.action === event.action ? activeDebugActionContext : undefined;
    activeDebugActionContext = {
      action: event.action,
      ...(existing?.actionId === undefined ? {} : { actionId: existing.actionId }),
      phase: event.phase,
      sequence: event.sequence.toString(),
      attempt: event.attempt,
      startedAt: existing?.startedAt ?? event.timestamp,
    };
    return;
  }
  if (
    (event.type === "action-succeeded" || event.type === "action-failed") &&
    activeDebugActionContext?.action === event.action
  ) {
    activeDebugActionContext = undefined;
  }
}

function debugExitFailure(exit: Exit.Exit<unknown, unknown>): Readonly<{
  cause: string;
  interruptors: readonly string[];
}> {
  if (Exit.isSuccess(exit)) {
    return { cause: "", interruptors: [] };
  }
  return {
    cause: Cause.pretty(exit.cause),
    interruptors: Array.from(Cause.interruptors(exit.cause), String),
  };
}

function elapsedMilliseconds(startedAt: string, completedAt: string): number {
  return Math.max(0, Date.parse(completedAt) - Date.parse(startedAt));
}

function writeJson(filename: string, value: unknown): Effect.Effect<void, Error> {
  return fromPromise(`write ${filename}`, () =>
    writeFile(path.join(artifactDirectory, filename), `${json(value, 2)}\n`),
  );
}

function writePrivateJson(filename: string, value: unknown): Effect.Effect<void, Error> {
  const target = path.join(artifactDirectory, filename);
  return fromPromise(`write private ${filename}`, async () => {
    await writeFile(target, `${json(value, 2)}\n`, { mode: 0o600 });
    await chmod(target, 0o600);
  });
}

function fromPromise<A>(operation: string, run: () => Promise<A>): Effect.Effect<A, Error> {
  return Effect.tryPromise({
    try: run,
    catch: (cause) =>
      cause instanceof Error
        ? new Error(`${operation}: ${cause.message}`, { cause })
        : new Error(`${operation}: ${String(cause)}`),
  });
}

function parsePublishedPort(output: string): number {
  const match = output.trim().match(/:(\d+)$/u);
  const value = Number(match?.[1]);
  if (!Number.isSafeInteger(value) || value < 1 || value > 65_535) {
    throw new Error(`Could not parse Minecraft port from ${JSON.stringify(output)}`);
  }
  return value;
}

function parseStronghold(output: string): Readonly<{ x: number; z: number }> {
  const match = output.match(/\[\s*(-?\d+)\s*,\s*(?:~|-?\d+)\s*,\s*(-?\d+)\s*\]/u);
  if (match === null) {
    throw new Error(`Could not parse stronghold coordinates from ${JSON.stringify(output)}`);
  }
  return { x: Number(match[1]), z: Number(match[2]) };
}

function parseWorldSpawn(output: string): Readonly<{ x: number; y: number; z: number }> {
  const match = output.match(/Set the world spawn point to\s+(-?\d+),\s*(-?\d+),\s*(-?\d+)/iu);
  if (match === null) {
    throw new Error(`Could not parse world spawn coordinates from ${JSON.stringify(output)}`);
  }
  return {
    x: Number(match[1]),
    y: Number(match[2]),
    z: Number(match[3]),
  };
}

function offlineUuid(username: string): string {
  const bytes = createHash("md5").update(`OfflinePlayer:${username}`, "utf8").digest();
  bytes[6] = (bytes[6]! & 0x0f) | 0x30;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

function isEnd(dimension: string): boolean {
  return dimension === "minecraft:the_end" || dimension.endsWith(":the_end");
}

function environment(name: string, fallback: string): string {
  const value = process.env[name]?.trim();
  return value === undefined || value.length === 0 ? fallback : value;
}

function optionalEnvironment(name: string): string | undefined {
  const value = process.env[name]?.trim();
  return value === undefined || value.length === 0 ? undefined : value;
}

function minMaxValue(min: number, max: number) {
  return create(ValueSchema, {
    kind: {
      case: "structValue",
      value: create(StructSchema, {
        fields: {
          min: create(ValueSchema, {
            kind: { case: "numberValue", value: min },
          }),
          max: create(ValueSchema, {
            kind: { case: "numberValue", value: max },
          }),
        },
      }),
    },
  });
}

function booleanEnvironment(name: string, fallback: boolean): boolean {
  const value = process.env[name]?.trim().toLowerCase();
  if (value === undefined || value.length === 0) {
    return fallback;
  }
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  throw new Error(`${name} must be true or false`);
}

function positiveIntegerEnvironment(name: string, fallback: number): number {
  const value = process.env[name]?.trim();
  const parsed = value === undefined || value.length === 0 ? fallback : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function json(value: unknown, indentation?: number): string {
  return JSON.stringify(
    value,
    (_, nested) => (typeof nested === "bigint" ? nested.toString() : nested),
    indentation,
  );
}

NodeRuntime.runMain(
  program.pipe(
    Effect.tap(() =>
      Effect.sync(() => {
        process.stdout.write(`Beat-game E2E smoke passed. Artifacts: ${artifactDirectory}\n`);
      }),
    ),
    Effect.tapCause((cause) =>
      Effect.sync(() => {
        process.stderr.write(
          `Beat-game E2E smoke failed. Artifacts: ${artifactDirectory}\n${String(cause)}\n`,
        );
      }),
    ),
  ),
);
