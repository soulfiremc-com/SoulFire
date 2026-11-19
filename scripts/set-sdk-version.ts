import { readFile, writeFile } from "node:fs/promises";

const version = process.argv[2];

if (!version) {
  throw new Error("Usage: bun scripts/set-sdk-version.ts <version>");
}

if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/u.test(version)) {
  throw new Error(`Invalid SDK version: ${version}`);
}

async function updateJsonVersion(path: string): Promise<void> {
  await replaceVersion(path, /^  "version": "[^"]+",$/mu, `  "version": "${version}",`);
}

async function replaceVersion(path: string, pattern: RegExp, replacement: string): Promise<void> {
  const contents = await readFile(path, "utf8");
  const flags = pattern.flags.includes("g") ? pattern.flags : `${pattern.flags}g`;
  const matches = contents.match(new RegExp(pattern.source, flags));

  if (matches?.length !== 1) {
    throw new Error(`Expected exactly one SDK version in ${path}, found ${matches?.length ?? 0}`);
  }

  await writeFile(
    path,
    contents.replace(pattern, () => replacement),
  );
}

await updateJsonVersion("sdk/typescript/package.json");
await updateJsonVersion("sdk/beat-game/package.json");
await updateJsonVersion("sdk/typescript/jsr.json");
await replaceVersion(
  "bun.lock",
  /^    "sdk\/beat-game": \{\n      "name": "@soulfiremc\/beat-game",\n      "version": "[^"]+",$/mu,
  `    "sdk/beat-game": {\n      "name": "@soulfiremc/beat-game",\n      "version": "${version}",`,
);
await replaceVersion(
  "bun.lock",
  /^    "sdk\/typescript": \{\n      "name": "@soulfiremc\/sdk",\n      "version": "[^"]+",$/mu,
  `    "sdk/typescript": {\n      "name": "@soulfiremc/sdk",\n      "version": "${version}",`,
);
await replaceVersion(
  "sdk/typescript/src/connection.ts",
  /^export const SDK_VERSION = "[^"]+";$/mu,
  `export const SDK_VERSION = "${version}";`,
);
await replaceVersion(
  "sdk/python/pyproject.toml",
  /^version = "[^"]+"$/mu,
  `version = "${version}"`,
);
await replaceVersion(
  "sdk/python/src/soulfire/connection.py",
  /^SDK_VERSION: Final = "[^"]+"$/mu,
  `SDK_VERSION: Final = "${version}"`,
);
