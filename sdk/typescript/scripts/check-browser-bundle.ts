import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const entries = [new URL("../src/browser.ts", import.meta.url)];
const sizes: string[] = [];

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
    throw new Error(`The browser SDK bundle for ${entry.pathname} was empty`);
  }
  sizes.push(`${entry.pathname.split("/").at(-1)} ${output.contents.length}`);
}

process.stdout.write(`Verified browser-safe SDK bundles (${sizes.join(", ")} bytes).\n`);
