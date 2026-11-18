import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const repositoryRoot = process.cwd();
const generatedDirectories = [
  path.join(repositoryRoot, "sdk/typescript/src/generated"),
  path.join(repositoryRoot, "sdk/python/src/soulfire"),
];

for (const directory of generatedDirectories) {
  await formatGeneratedDirectory(directory);
}
await updateJsrExports();

async function formatGeneratedDirectory(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await formatGeneratedDirectory(filePath);
      continue;
    }
    if (!isGeneratedSource(entry.name)) {
      continue;
    }

    const source = await readFile(filePath, "utf8");
    const formatted = `${source
      .split(/\r?\n/)
      .map((line) => line.trimEnd())
      .join("\n")
      .trimEnd()}\n`;
    if (formatted !== source) {
      await writeFile(filePath, formatted);
    }
  }
}

function isGeneratedSource(fileName) {
  return (
    fileName.endsWith("_connect.py") ||
    fileName.endsWith("_pb2.py") ||
    fileName.endsWith("_pb2.pyi") ||
    fileName.endsWith("_pb.ts")
  );
}

async function updateJsrExports() {
  const jsrPath = path.join(repositoryRoot, "sdk/typescript/jsr.json");
  const jsr = JSON.parse(await readFile(jsrPath, "utf8"));
  const generatedRoot = path.join(repositoryRoot, "sdk/typescript/src/generated");
  const generated = await generatedTypeScriptModules(generatedRoot);
  jsr.exports = {
    ".": "./src/index.ts",
    "./platform": "./src/platform.ts",
    ...Object.fromEntries(
      generated.map((modulePath) => [
        `./generated/${modulePath.replace(/_pb\.ts$/, "_pb")}`,
        `./src/generated/${modulePath}`,
      ]),
    ),
  };
  await writeFile(jsrPath, `${JSON.stringify(jsr, null, 2)}\n`);
}

async function generatedTypeScriptModules(directory, relative = "") {
  const modules = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryRelative = path.posix.join(relative, entry.name);
    if (entry.isDirectory()) {
      modules.push(
        ...(await generatedTypeScriptModules(path.join(directory, entry.name), entryRelative)),
      );
    } else if (entry.name.endsWith("_pb.ts")) {
      modules.push(entryRelative);
    }
  }
  return modules.toSorted((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}
