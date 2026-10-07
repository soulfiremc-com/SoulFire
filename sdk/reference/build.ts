import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  cleanBuildOutputDir,
  writeAssets,
  writeRootConfig,
  writeWorkerConfig,
} from "@cloudflare/build-output-utils";
import { resolveAndParseConfig } from "@cloudflare/config";
import config from "./cloudflare.config";

const language = process.argv[2];
if (language !== "typescript" && language !== "python") {
  throw new Error("Usage: bun build.ts <typescript|python>");
}

const root = import.meta.dir;
const { version } = JSON.parse(readFileSync(join(root, "../typescript/package.json"), "utf8"));
const assets = join(root, "dist", language);
rmSync(assets, { recursive: true, force: true });
mkdirSync(assets, { recursive: true });

function run(command: string, args: string[], cwd = root) {
  execFileSync(command, args, { cwd, stdio: "inherit" });
}

if (language === "typescript") {
  run("bun", ["install", "--frozen-lockfile"], join(root, "../.."));
  run("bun", [
    "x",
    "--no-install",
    "typedoc",
    "--options",
    "typedoc.json",
    "--name",
    `SoulFire TypeScript SDK ${version}`,
  ]);
} else {
  const python = join(root, ".venv", "bin", "python");
  if (!existsSync(python)) {
    run("python3.14", ["-m", "venv", ".venv"]);
  }
  run(python, ["-m", "pip", "install", "-r", "requirements.txt"]);
  run(python, [
    "-m",
    "sphinx",
    "-b",
    "dirhtml",
    "-j",
    "4",
    "-d",
    join(root, "dist", "doctrees", "python"),
    "python-docs",
    assets,
  ]);
}

const context = {
  isPreview: false,
  mode: language === "python" ? "python" : "production",
};
if (language === "typescript") {
  writeFileSync(join(assets, "_redirects"), "/ /index.html 200\n");
}
const result = await resolveAndParseConfig(config, context);
if (!result.success) throw result.error;
const { worker, containers: _containers, ...settings } = result.data;
if (!worker) throw new Error("Missing static reference Worker configuration");
await cleanBuildOutputDir(root);
await writeRootConfig(root, settings, context);
await writeWorkerConfig({ root, config: worker });
await writeAssets({ root, sourceDirectory: assets });
