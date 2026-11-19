import { spawn } from "node:child_process";
import { SecureRandom } from "effect/crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { DateTime, Schema } from "effect";
import { readEnvironment } from "./environment.ts";

const encodePrettyJson = Schema.encodeSync(Schema.fromJsonString(Schema.Unknown, { space: 2 }));

const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const packageDirectory = path.resolve(import.meta.dirname, "..");
const runCount = positiveIntegerEnvironment("SOULFIRE_QUALIFICATION_RUNS", 5);
const seed = environment("SOULFIRE_QUALIFICATION_SEED", "SoulFire beat-game qualification v1");
const controlled = booleanEnvironment("SOULFIRE_QUALIFICATION_CONTROLLED", false);
const continueAfterFailure = booleanEnvironment(
  "SOULFIRE_QUALIFICATION_CONTINUE_ON_FAILURE",
  false,
);
const skipBuild = booleanEnvironment("SOULFIRE_QUALIFICATION_SKIP_BUILD", false);
const qualificationId = environment(
  "SOULFIRE_QUALIFICATION_ID",
  `${DateTime.formatIso(DateTime.nowUnsafe()).replaceAll(/[:.]/gu, "-")}-${SecureRandom.uuidV4Unsafe()}`,
);
const artifactRoot = path.resolve(
  environment(
    "SOULFIRE_QUALIFICATION_ARTIFACT_DIR",
    path.join(repositoryRoot, "temp", "beat-game-qualification", qualificationId),
  ),
);

const QualificationMetrics = Schema.Struct({
  runId: Schema.String,
  mode: Schema.Literals(["controlled", "survival"]),
  seed: Schema.String,
  completed: Schema.Boolean,
  durationMs: Schema.Finite,
  deaths: Schema.Finite,
  pathAttempts: Schema.Finite,
  pathCompletions: Schema.Finite,
  pathFailures: Schema.Finite,
  pathInterruptions: Schema.Finite,
  actionRetries: Schema.Finite,
  safetyInterruptions: Schema.Finite,
  skillRetries: Schema.Finite,
  portalWorkspaces: Schema.Finite,
  observedPhases: Schema.Array(Schema.String),
});
type QualificationMetrics = typeof QualificationMetrics.Type;
const decodeQualificationMetrics = Schema.decodeSync(Schema.fromJsonString(QualificationMetrics));

interface QualificationRunResult {
  readonly index: number;
  readonly runId: string;
  readonly artifactDirectory: string;
  readonly exitCode: number;
  readonly metrics?: QualificationMetrics;
  readonly error?: string;
}

await mkdir(artifactRoot, { recursive: true });
if (!skipBuild) {
  await runCommand("bun", ["run", "--filter", "@soulfiremc/sdk", "build"], {
    cwd: repositoryRoot,
  });
  await runCommand("bun", ["run", "build"], { cwd: packageDirectory });
  await runCommand("bun", ["run", "typecheck:smoke"], {
    cwd: packageDirectory,
  });
  await runCommand("./gradlew", [":dedicated-launcher:uberJar"], {
    cwd: repositoryRoot,
  });
}

const results: QualificationRunResult[] = [];
for (let index = 1; index <= runCount; index += 1) {
  const runId = `${qualificationId}-${index}`;
  const artifactDirectory = path.join(artifactRoot, `run-${index}`);
  await mkdir(artifactDirectory, { recursive: true });
  process.stdout.write(`Starting beat-game qualification ${index}/${runCount} (${runId})\n`);
  const exitCode = await runCommand(
    process.execPath,
    ["--experimental-strip-types", "smoke/e2e.ts"],
    {
      cwd: packageDirectory,
      allowFailure: true,
      env: {
        ...process.env,
        SOULFIRE_E2E_ARTIFACT_DIR: artifactDirectory,
        SOULFIRE_E2E_CONTROLLED: String(controlled),
        SOULFIRE_E2E_KEEP_CONTAINER: "false",
        SOULFIRE_E2E_RUN_ID: runId,
        SOULFIRE_E2E_SEED: seed,
      },
    },
  );
  const result = await readRunResult(index, runId, artifactDirectory, exitCode);
  results.push(result);
  await writeReport(results);
  if (exitCode !== 0 && !continueAfterFailure) {
    break;
  }
}

const report = await writeReport(results);
process.stdout.write(`${encodePrettyJson(report)}\n`);
if (!report.qualified) {
  process.exitCode = 1;
}

async function readRunResult(
  index: number,
  runId: string,
  artifactDirectory: string,
  exitCode: number,
): Promise<QualificationRunResult> {
  try {
    const source = await readFile(path.join(artifactDirectory, "qualification.json"), "utf8");
    return {
      index,
      runId,
      artifactDirectory,
      exitCode,
      metrics: decodeQualificationMetrics(source),
    };
  } catch (cause) {
    return {
      index,
      runId,
      artifactDirectory,
      exitCode,
      error: cause instanceof Error ? cause.message : String(cause),
    };
  }
}

async function writeReport(runResults: readonly QualificationRunResult[]) {
  const completed = runResults.flatMap(({ metrics }) =>
    metrics?.completed === true ? [metrics] : [],
  );
  const durations = completed
    .map(({ durationMs }) => durationMs)
    .toSorted((left, right) => left - right);
  const qualificationReport = {
    qualificationId,
    generatedAt: DateTime.formatIso(DateTime.nowUnsafe()),
    mode: controlled ? "controlled" : "survival",
    seed,
    requiredConsecutiveRuns: runCount,
    attemptedRuns: runResults.length,
    successfulRuns: completed.length,
    qualified:
      runResults.length === runCount &&
      completed.length === runCount &&
      runResults.every(({ exitCode }) => exitCode === 0),
    completionRate: runResults.length === 0 ? 0 : completed.length / runResults.length,
    medianCompletionTimeMs: percentile(durations, 0.5),
    p95CompletionTimeMs: percentile(durations, 0.95),
    totals: {
      deaths: sum(completed, "deaths"),
      pathAttempts: sum(completed, "pathAttempts"),
      pathFailures: sum(completed, "pathFailures"),
      pathInterruptions: sum(completed, "pathInterruptions"),
      actionRetries: sum(completed, "actionRetries"),
      safetyInterruptions: sum(completed, "safetyInterruptions"),
      skillRetries: sum(completed, "skillRetries"),
      portalWorkspaces: sum(completed, "portalWorkspaces"),
    },
    results: runResults,
  } as const;
  await writeFile(
    path.join(artifactRoot, "qualification-report.json"),
    `${encodePrettyJson(qualificationReport)}\n`,
  );
  return qualificationReport;
}

function percentile(values: readonly number[], fraction: number): number | null {
  if (values.length === 0) {
    return null;
  }
  const rank = Math.max(0, Math.ceil(values.length * fraction) - 1);
  return values[rank] ?? null;
}

function sum(values: readonly QualificationMetrics[], key: keyof QualificationMetrics): number {
  return values.reduce((total, value) => {
    const item = value[key];
    return total + (typeof item === "number" ? item : 0);
  }, 0);
}

function runCommand(
  command: string,
  args: readonly string[],
  options: {
    readonly cwd: string;
    readonly allowFailure?: boolean;
    readonly env?: NodeJS.ProcessEnv;
  },
): Promise<number> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      ...(options.env === undefined ? {} : { env: options.env }),
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      const exitCode = code ?? 1;
      if (exitCode === 0 || options.allowFailure === true) {
        resolve(exitCode);
        return;
      }
      reject(
        new Error(
          `${command} ${args.join(" ")} exited with ${exitCode}${
            signal === null ? "" : ` after ${signal}`
          }`,
        ),
      );
    });
  });
}

function environment(name: string, fallback: string): string {
  const value = readEnvironment(name)?.trim();
  return value === undefined || value.length === 0 ? fallback : value;
}

function booleanEnvironment(name: string, fallback: boolean): boolean {
  const value = readEnvironment(name)?.trim().toLowerCase();
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
  const source = readEnvironment(name)?.trim();
  if (source === undefined || source.length === 0) {
    return fallback;
  }
  const value = Number(source);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}
