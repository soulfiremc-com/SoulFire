import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { Effect, Result } from "effect";
import { afterEach, assert, describe, expect, it } from "vitest";

import {
  BeatGameCheckpointError,
  BeatGamePhase,
  InMemoryBeatGameCheckpointStore,
} from "../src/index.js";
import { JsonFileBeatGameCheckpointStore } from "../src/node.js";
import { checkpoint } from "./fixtures.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { force: true, recursive: true })),
  );
});

describe("beat-game checkpoint stores", () => {
  it("enforces compare-and-set revisions and returns defensive copies", async () => {
    const store = new InMemoryBeatGameCheckpointStore();
    const initial = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
    await Effect.runPromise(store.save(initial, undefined));

    const loaded = await Effect.runPromise(store.load(initial.runId));
    expect(loaded).toEqual(initial);
    expect(loaded).not.toBe(initial);

    const conflict = await Effect.runPromise(
      store.save({ ...initial, revision: 2 }, 0).pipe(Effect.result),
    );
    assert(Result.isFailure(conflict));
    expect(conflict.failure).toBeInstanceOf(BeatGameCheckpointError);
    expect(conflict.failure.actualRevision).toBe(1);
  });

  it("writes restart-safe JSON and removes only the expected revision", async () => {
    const directory = await mkdtemp(join(tmpdir(), "soulfire-beat-game-"));
    temporaryDirectories.push(directory);
    const store = new JsonFileBeatGameCheckpointStore(directory);
    const initial = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);

    await Effect.runPromise(store.save(initial, undefined));
    const files = await readdir(directory);
    const checkpointFile = files.find((file) => file.endsWith(".json"));
    expect(checkpointFile).toBeDefined();
    const source = await readFile(join(directory, checkpointFile ?? ""), "utf8");
    expect(JSON.parse(source)).toMatchObject({
      runId: "run-1",
      revision: 1,
    });

    const next = { ...initial, revision: 2 };
    await Effect.runPromise(store.save(next, 1));
    await Effect.runPromise(store.remove(initial.runId, 2));
    expect(await Effect.runPromise(store.load(initial.runId))).toBeUndefined();
  });

  it("rejects structurally corrupt durable checkpoints", async () => {
    const directory = await mkdtemp(join(tmpdir(), "soulfire-beat-game-"));
    temporaryDirectories.push(directory);
    const store = new JsonFileBeatGameCheckpointStore(directory);
    const initial = checkpoint(BeatGamePhase.LOCATE_STRONGHOLD);
    await Effect.runPromise(store.save(initial, undefined));
    const file = (await readdir(directory)).find((entry) => entry.endsWith(".json"));
    expect(file).toBeDefined();
    await writeFile(
      join(directory, file ?? ""),
      JSON.stringify({
        ...initial,
        planner: {
          ...initial.planner,
          phase: "NOT_A_REAL_PHASE",
        },
      }),
    );

    const loaded = await Effect.runPromise(store.load(initial.runId).pipe(Effect.result));
    assert(Result.isFailure(loaded));
    expect(loaded.failure).toBeInstanceOf(BeatGameCheckpointError);
  });

  it("rejects corrupt durable exploration progress", async () => {
    const store = new InMemoryBeatGameCheckpointStore();
    const initial = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
    const result = await Effect.runPromise(
      store
        .save(
          {
            ...initial,
            memory: {
              ...initial.memory,
              explorationFrontiers: {
                "minecraft:overworld:find-logs": {
                  origin: {
                    x: 0,
                    y: 64,
                    z: 0,
                    dimension: "minecraft:overworld",
                  },
                  nextIndex: 0,
                },
              },
            },
          },
          undefined,
        )
        .pipe(Effect.result),
    );

    assert(Result.isFailure(result));
    expect(result.failure).toBeInstanceOf(BeatGameCheckpointError);
  });

  it("rejects corrupt pending exploration attempt state", async () => {
    const store = new InMemoryBeatGameCheckpointStore();
    const initial = checkpoint(BeatGamePhase.PREPARE_OVERWORLD);
    const result = await Effect.runPromise(
      store
        .save(
          {
            ...initial,
            memory: {
              ...initial.memory,
              explorationFrontiers: {
                "minecraft:overworld:find-logs": {
                  progressVersion: 2,
                  origin: {
                    x: 0,
                    y: 64,
                    z: 0,
                    dimension: "minecraft:overworld",
                  },
                  nextIndex: 1,
                  targetAttempts: -1,
                },
              },
            },
          },
          undefined,
        )
        .pipe(Effect.result),
    );

    assert(Result.isFailure(result));
    expect(result.failure).toBeInstanceOf(BeatGameCheckpointError);
  });
});
