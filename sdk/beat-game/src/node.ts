import { mkdir, open, readFile, rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";

import { Effect } from "effect";

import { BeatGameCheckpointError } from "./errors.js";
import type { BeatGameCheckpoint } from "./model.js";
import {
  assertValidCheckpoint,
  makeCheckpointError,
  validateCheckpointRevision,
  type BeatGameCheckpointStore,
} from "./stores.js";

export interface JsonFileCheckpointStoreOptions {
  readonly lockTimeoutMs?: number;
  readonly staleLockMs?: number;
}

export class JsonFileBeatGameCheckpointStore implements BeatGameCheckpointStore {
  private readonly directory: string;
  readonly #lockTimeoutMs: number;
  readonly #staleLockMs: number;

  public constructor(directory: string, options: JsonFileCheckpointStoreOptions = {}) {
    this.directory = directory;
    this.#lockTimeoutMs = positiveInteger(options.lockTimeoutMs ?? 5000, "lockTimeoutMs");
    this.#staleLockMs = positiveInteger(options.staleLockMs ?? 30_000, "staleLockMs");
  }

  public readonly load = (
    runId: string,
  ): Effect.Effect<BeatGameCheckpoint | undefined, BeatGameCheckpointError> =>
    Effect.tryPromise({
      try: async () => this.#read(runId),
      catch: (cause) =>
        makeCheckpointError(
          undefined,
          runId,
          `Could not load checkpoint ${runId}`,
          undefined,
          undefined,
          cause,
        ),
    });

  public readonly save = (
    checkpoint: BeatGameCheckpoint,
    expectedRevision: number | undefined,
  ): Effect.Effect<BeatGameCheckpoint, BeatGameCheckpointError> =>
    Effect.tryPromise({
      try: async () =>
        this.#withLock(checkpoint.runId, async () => {
          assertValidCheckpoint(checkpoint);
          const current = await this.#read(checkpoint.runId);
          const validation = Effect.runSync(
            Effect.result(validateCheckpointRevision(checkpoint, current, expectedRevision)),
          );
          if (validation._tag === "Failure") {
            throw validation.failure;
          }
          await mkdir(this.directory, { recursive: true });
          const path = this.#path(checkpoint.runId);
          const temporary = `${path}.${process.pid}.${crypto.randomUUID()}.tmp`;
          try {
            const handle = await open(temporary, "wx", 0o600);
            try {
              await handle.writeFile(`${JSON.stringify(checkpoint, null, 2)}\n`, "utf8");
              await handle.sync();
            } finally {
              await handle.close();
            }
            await rename(temporary, path);
            await syncDirectory(this.directory);
          } finally {
            await rm(temporary, { force: true });
          }
          return structuredClone(checkpoint);
        }),
      catch: (cause) =>
        cause instanceof BeatGameCheckpointError
          ? cause
          : makeCheckpointError(
              checkpoint,
              checkpoint.runId,
              `Could not save checkpoint ${checkpoint.runId}`,
              expectedRevision,
              undefined,
              cause,
            ),
    });

  public readonly remove = (
    runId: string,
    expectedRevision?: number,
  ): Effect.Effect<void, BeatGameCheckpointError> =>
    Effect.tryPromise({
      try: async () =>
        this.#withLock(runId, async () => {
          const current = await this.#read(runId);
          if (expectedRevision !== undefined && current?.revision !== expectedRevision) {
            throw makeCheckpointError(
              current,
              runId,
              "Checkpoint revision changed before removal",
              expectedRevision,
              current?.revision,
            );
          }
          await rm(this.#path(runId), { force: true });
        }),
      catch: (cause) =>
        cause instanceof BeatGameCheckpointError
          ? cause
          : makeCheckpointError(
              undefined,
              runId,
              `Could not remove checkpoint ${runId}`,
              expectedRevision,
              undefined,
              cause,
            ),
    });

  async #read(runId: string): Promise<BeatGameCheckpoint | undefined> {
    try {
      const source = await readFile(this.#path(runId), "utf8");
      return parseCheckpoint(source, runId);
    } catch (error) {
      if (isNodeError(error) && error.code === "ENOENT") {
        return undefined;
      }
      throw error;
    }
  }

  async #withLock<T>(runId: string, operation: () => Promise<T>): Promise<T> {
    await mkdir(this.directory, { recursive: true });
    const lockPath = `${this.#path(runId)}.lock`;
    const deadline = Date.now() + this.#lockTimeoutMs;
    for (;;) {
      try {
        const handle = await open(lockPath, "wx", 0o600);
        try {
          await handle.writeFile(`${process.pid}\n`, "utf8");
          return await operation();
        } finally {
          await handle.close();
          await rm(lockPath, { force: true });
        }
      } catch (error) {
        if (!isNodeError(error) || error.code !== "EEXIST") {
          throw error;
        }
        if (await isStale(lockPath, this.#staleLockMs)) {
          await rm(lockPath, { force: true });
          continue;
        }
        if (Date.now() >= deadline) {
          throw new Error(`Timed out waiting for checkpoint lock ${lockPath}`, { cause: error });
        }
        await delay(50);
      }
    }
  }

  #path(runId: string): string {
    if (runId.length === 0) {
      throw new TypeError("runId must not be empty");
    }
    const encodedRunId = Buffer.from(runId, "utf8").toString("base64url");
    return join(this.directory, `checkpoint-${encodedRunId}.json`);
  }
}

function parseCheckpoint(source: string, runId: string): BeatGameCheckpoint {
  const value: unknown = JSON.parse(source);
  assertValidCheckpoint(value, runId);
  return value;
}

async function isStale(path: string, staleLockMs: number): Promise<boolean> {
  try {
    const info = await stat(path);
    return Date.now() - info.mtimeMs >= staleLockMs;
  } catch (error) {
    if (isNodeError(error) && error.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

function delay(durationMs: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, durationMs);
  });
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && "code" in error;
}

async function syncDirectory(directory: string): Promise<void> {
  try {
    const handle = await open(directory, "r");
    try {
      await handle.sync();
    } finally {
      await handle.close();
    }
  } catch (error) {
    if (isNodeError(error) && ["EINVAL", "EISDIR", "ENOTSUP", "EPERM"].includes(error.code ?? "")) {
      return;
    }
    throw error;
  }
}

function positiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new RangeError(`${name} must be a positive safe integer`);
  }
  return value;
}
