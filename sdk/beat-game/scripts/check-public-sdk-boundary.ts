import { readFile, readdir } from "node:fs/promises";
import { extname, join, relative } from "node:path";

const sourceRoot = new URL("../src/", import.meta.url);
const forbidden = [
  /@soulfiremc\/sdk\/generated\//u,
  /sdk\/typescript\/src/u,
  /generated\/soulfire/u,
  /automation_pb/u,
  /AutomationService/u,
];

const files = await collect(sourceRoot);
const violations: string[] = [];

for (const file of files) {
  const source = await readFile(file, "utf8");
  for (const pattern of forbidden) {
    if (pattern.test(source)) {
      violations.push(`${relative(new URL("..", sourceRoot).pathname, file)}: ${pattern}`);
    }
  }
}

if (violations.length > 0) {
  throw new Error(`Beat-game package crossed the public SDK boundary:\n${violations.join("\n")}`);
}

async function collect(directoryUrl: URL): Promise<string[]> {
  const directory = directoryUrl.pathname;
  const entries = await readdir(directory, { withFileTypes: true });
  const results: string[] = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      results.push(...(await collect(new URL(`${entry.name}/`, directoryUrl))));
    } else if (extname(entry.name) === ".ts") {
      results.push(path);
    }
  }
  return results;
}
