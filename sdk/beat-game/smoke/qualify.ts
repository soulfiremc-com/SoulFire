import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const packageDirectory = path.resolve(import.meta.dirname, "..");
const runCount = positiveIntegerEnvironment("SOULFIRE_QUALIFICATION_RUNS", 5);
const seed = environment("SOULFIRE_QUALIFICATION_SEED", "Soulfire beat-game qualification v1");
const controlled = booleanEnvironment("SOULFIRE_QUALIFICATION_CONTROLLED", false);
const continueAfterFailure = booleanEnvironment(
  "SOULFIRE_QUALIFICATION_CONTINUE_ON_FAILURE",
  false,
);
const skipBuild = booleanEnvironment("SOULFIRE_QUALIFICATION_SKIP_BUILD", false);
const qualificationId = environment(
  "SOULFIRE_QUALIFICATION_ID",
  `${new Date().toISOString().replaceAll(/[:.]/gu, "-")}-${randomUUID()}`,
);
const artifactRoot = path.resolve(
  environment(
    "SOULFIRE_QUALIFICATION_ARTIFACT_DIR",
    path.join(repositoryRoot, "temp", "beat-game-qualification", qualificationId),
  ),
);

interface QualificationMetrics {
  readonly runId: string;
  readonly mode: "controlled" | "survival";
  readonly seed: string;
  readonly completed: boolean;
  readonly durationMs: number;
  readonly deaths: number;
  readonly pathAttempts: number;
  readonly pathCompletions: number;
  readonly pathFailures: number;
  readonly pathInterruptions: number;
  readonly actionRetries: number;
  readonly safetyInterruptions: number;
  readonly skillRetries: number;
  readonly portalWorkspaces: number;
  readonly observedPhases: readonly string[];
}

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
process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
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
      metrics: JSON.parse(source) as QualificationMetrics,
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

async function writeReport(results: readonly QualificationRunResult[]) {
  const completed = results.flatMap(({ metrics }) =>
    metrics?.completed === true ? [metrics] : [],
  );
  const durations = completed
    .map(({ durationMs }) => durationMs)
    .sort((left, right) => left - right);
  const report = {
    qualificationId,
    generatedAt: new Date().toISOString(),
    mode: controlled ? "controlled" : "survival",
    seed,
    requiredConsecutiveRuns: runCount,
    attemptedRuns: results.length,
    successfulRuns: completed.length,
    qualified:
      results.length === runCount &&
      completed.length === runCount &&
      results.every(({ exitCode }) => exitCode === 0),
    completionRate: results.length === 0 ? 0 : completed.length / results.length,
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
    results,
  } as const;
  await writeFile(
    path.join(artifactRoot, "qualification-report.json"),
    `${JSON.stringify(report, null, 2)}\n`,
  );
  return report;
}

function percentile(values: readonly number[], percentile: number): number | null {
  if (values.length === 0) {
    return null;
  }
  const rank = Math.max(0, Math.ceil(values.length * percentile) - 1);
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
      env: options.env ?? process.env,
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
  const value = process.env[name]?.trim();
  return value === undefined || value.length === 0 ? fallback : value;
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
  const source = process.env[name]?.trim();
  if (source === undefined || source.length === 0) {
    return fallback;
  }
  const value = Number(source);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}
