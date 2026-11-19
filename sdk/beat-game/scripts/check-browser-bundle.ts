import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const entries = [new URL("../src/index.ts", import.meta.url)];

for (const entry of entries) {
  const result = await build({
    bundle: true,
    entryPoints: [fileURLToPath(entry)],
    format: "esm",
    platform: "browser",
    sourcemap: false,
    treeShaking: true,
    write: false,
  });
  const output = result.outputFiles.at(0);
  if (result.outputFiles.length !== 1 || output === undefined || output.contents.length === 0) {
    throw new Error(`The browser beat-game bundle for ${entry.pathname} was empty`);
  }
}
