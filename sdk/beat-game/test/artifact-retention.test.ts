import { mkdir, mkdtemp, rm, stat, utimes } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { pruneArtifactRuns } from "../smoke/artifact-retention.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("pruneArtifactRuns", () => {
  it("keeps the current run and the newest retained runs", async () => {
    const rootDirectory = await mkdtemp(path.join(os.tmpdir(), "soulfire-artifacts-"));
    temporaryDirectories.push(rootDirectory);
    const currentDirectory = path.join(rootDirectory, "current");
    const oldDirectory = path.join(rootDirectory, "old");
    const recentDirectory = path.join(rootDirectory, "recent");
    await Promise.all([mkdir(currentDirectory), mkdir(oldDirectory), mkdir(recentDirectory)]);
    await utimes(oldDirectory, new Date(1000), new Date(1000));
    await utimes(recentDirectory, new Date(2000), new Date(2000));

    const removed = await pruneArtifactRuns({
      rootDirectory,
      currentDirectory,
      maximumRuns: 2,
    });

    expect(removed).toEqual([oldDirectory]);
    await expect(stat(currentDirectory)).resolves.toBeDefined();
    await expect(stat(recentDirectory)).resolves.toBeDefined();
    await expect(stat(oldDirectory)).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("refuses to prune around an artifact directory outside its root", async () => {
    const rootDirectory = await mkdtemp(path.join(os.tmpdir(), "soulfire-artifacts-"));
    temporaryDirectories.push(rootDirectory);

    await expect(
      pruneArtifactRuns({
        rootDirectory,
        currentDirectory: path.join(rootDirectory, "nested", "current"),
        maximumRuns: 2,
      }),
    ).rejects.toThrow("must be a direct child");
  });
});
