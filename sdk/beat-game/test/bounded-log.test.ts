import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { BoundedLog } from "../smoke/bounded-log.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("BoundedLog", () => {
  it("serializes writes and retains only the configured generations", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "soulfire-log-"));
    temporaryDirectories.push(directory);
    const filename = path.join(directory, "events.ndjson");
    const values = Array.from({ length: 8 }, (_, index) => ({
      id: index + 1,
      payload: "fixed-width",
    }));
    const lines = values.map((value) => `${JSON.stringify(value)}\n`);
    const log = new BoundedLog(filename, {
      maximumBytes: Buffer.byteLength(lines[0] ?? "") * 2,
      files: 3,
    });

    await Promise.all(lines.map((line) => log.append(line)));
    await log.flush();

    const retained = await Promise.all([
      readEntries(`${filename}.2`),
      readEntries(`${filename}.1`),
      readEntries(filename),
    ]);
    expect(retained.flat().map(({ id }) => id)).toEqual([3, 4, 5, 6, 7, 8]);
  });

  it("bounds a single append larger than one generation", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "soulfire-log-"));
    temporaryDirectories.push(directory);
    const filename = path.join(directory, "minecraft.log");
    const log = new BoundedLog(filename, {
      maximumBytes: 4,
      files: 2,
    });

    await log.append("abcdefghijkl");
    await log.flush();

    await expect(readFile(`${filename}.1`, "utf8")).resolves.toBe("efgh");
    await expect(readFile(filename, "utf8")).resolves.toBe("ijkl");
    await expect(stat(`${filename}.2`)).rejects.toMatchObject({ code: "ENOENT" });
  });
});

async function readEntries(filename: string): Promise<readonly { id: number }[]> {
  return (await readFile(filename, "utf8"))
    .trim()
    .split("\n")
    .map((line) => {
      const entry: unknown = JSON.parse(line);
      if (typeof entry !== "object" || entry === null || !("id" in entry)) {
        throw new Error(`Unexpected log line: ${line}`);
      }
      const { id } = entry;
      if (typeof id !== "number") {
        throw new TypeError(`Unexpected log id: ${line}`);
      }
      return { id };
    });
}
