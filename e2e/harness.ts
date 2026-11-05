// The end-to-end harness: a real Minecraft server (Docker) with a void world, Soulfire started from
// a local jar, and one bot on that server. Tests build their scene with server commands (RCON),
// drive the bot through the SDK and check the result.
import { execFile } from "node:child_process";
import { accessSync, appendFileSync, constants, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { promisify } from "node:util";
import { create } from "@bufbuild/protobuf";
import { ValueSchema } from "@bufbuild/protobuf/wkt";
import type { SoulFireBot, SoulFireClient, SoulFireOperationError } from "@soulfiremc/sdk";
import { SoulFire } from "@soulfiremc/sdk/node";
import { Console, Data, Duration, Effect, Schedule, Semaphore } from "effect";

const execFileAsync = promisify(execFile);

export const E2E_DIR = import.meta.dirname;
const REPO_DIR = path.dirname(E2E_DIR);

// ---- errors ----------------------------------------------------------------------------------

/** A command (docker, RCON) that couldn't run, or whose output says it failed. */
export class CommandFailed extends Data.TaggedError("CommandFailed")<{
  readonly command: string;
  readonly message: string;
}> {}

/** Something a test or the setup expected didn't happen. */
export class CheckFailed extends Data.TaggedError("CheckFailed")<{ readonly message: string }> {}

export type TestError = CommandFailed | CheckFailed | SoulFireOperationError;

/** Fails with `message` unless `condition` holds. */
export const check = (condition: boolean, message: string): Effect.Effect<void, CheckFailed> =>
  condition ? Effect.void : Effect.fail(new CheckFailed({ message }));

/** Retries `effect` until it succeeds or `timeout` has passed; then fails with its last error. */
export const eventually = <A, E, R>(
  effect: Effect.Effect<A, E, R>,
  timeout: Duration.Input,
  interval: Duration.Input = "250 millis",
) => Effect.retry(effect, Schedule.spaced(interval).pipe(Schedule.upTo({ duration: timeout })));

const run = (
  command: string,
  args: readonly string[],
  options: { maxBuffer?: number; timeout?: number } = {},
) =>
  Effect.tryPromise({
    try: () => execFileAsync(command, [...args], options),
    catch: (e) =>
      new CommandFailed({
        command: `${command} ${args.join(" ")}`,
        message: e instanceof Error ? e.message : String(e),
      }),
  });

// ---- Minecraft server ------------------------------------------------------------------------

/** Kept between runs (unless --fresh), so a run doesn't wait for the server to start. */
const CONTAINER = "soulfire-e2e";
/** The image the beat-game smoke uses. */
const IMAGE = "itzg/minecraft-server:2026.3.2-java25";
/** Soulfire's native version, so the bot needs no protocol translation. */
export const MINECRAFT_VERSION = "26.3";
/** Not 25565, so a server someone runs for play keeps its port. */
const MINECRAFT_PORT = Number(process.env.SOULFIRE_E2E_MINECRAFT_PORT ?? 25566);
/** A flat world of one air layer: nothing exists that a test didn't build. */
const VOID_WORLD = JSON.stringify({
  layers: [{ block: "minecraft:air", height: 1 }],
  biome: "minecraft:the_void",
});
/** First start downloads the server jar and generates the world. */
const MINECRAFT_START_TIMEOUT = "5 minutes";

/** Command output that means the command failed (RCON itself still succeeds). */
const RCON_ERROR =
  /Unknown or incomplete command|Incorrect argument|Expected |Invalid |That position is not loaded|No player was found|Unknown |Could not /;

// Vanilla's RCON handlers share one command-output buffer.
const rconCommands = Semaphore.makeUnsafe(1);

/**
 * Runs a server command and returns its output. Fails when the output says the command failed,
 * unless `check` is false (for commands whose failure is the answer, like `execute if`).
 */
export const rcon = (
  command: string,
  { check = true } = {},
): Effect.Effect<string, CommandFailed> =>
  rconCommands.withPermit(
    run("docker", ["exec", CONTAINER, "rcon-cli", command], { timeout: 10_000 }).pipe(
      // Cancellation must not release the buffer while the child process is still running.
      Effect.uninterruptible,
      Effect.map(({ stdout }) => stdout.trim()),
      Effect.filterOrFail(
        (out) => !check || !RCON_ERROR.test(out),
        (out) => new CommandFailed({ command: `/${command}`, message: out }),
      ),
    ),
  );

const containerRunning = run("docker", [
  "inspect",
  "--format",
  "{{.State.Running}}",
  CONTAINER,
]).pipe(
  Effect.map(({ stdout }) => stdout.trim() === "true"),
  Effect.orElseSucceed(() => false),
);

/** The host address the running container publishes the server on, e.g. `127.0.0.1:25566`. */
const publishedAddress = run("docker", ["port", CONTAINER, "25565/tcp"]).pipe(
  Effect.map(({ stdout }) => stdout.trim()),
  Effect.orElseSucceed(() => undefined),
);

export const stopMinecraft = run("docker", ["rm", "--force", CONTAINER]).pipe(Effect.ignore);

export const saveMinecraftLog = (file: string) =>
  run("docker", ["logs", CONTAINER], { maxBuffer: 64 * 1024 * 1024 }).pipe(
    Effect.flatMap(({ stdout, stderr }) =>
      Effect.sync(() => appendFileSync(file, stdout + stderr)),
    ),
    Effect.ignore,
  );

/** Starts the server, or reuses the one left running by an earlier run (on the same port). */
export const startMinecraft = Effect.fn("startMinecraft")(function* ({
  fresh,
}: {
  fresh: boolean;
}) {
  if (
    fresh ||
    ((yield* containerRunning) && (yield* publishedAddress) !== `127.0.0.1:${MINECRAFT_PORT}`)
  ) {
    yield* stopMinecraft;
  }
  if (yield* containerRunning) {
    yield* Console.log(`[minecraft] reusing container ${CONTAINER}`);
  } else {
    yield* Console.log(
      `[minecraft] starting ${IMAGE} (Minecraft ${MINECRAFT_VERSION}, void world)...`,
    );
    yield* run("docker", [
      "run",
      "--detach",
      "--rm",
      "--name",
      CONTAINER,
      "--publish",
      `127.0.0.1:${MINECRAFT_PORT}:25565`,
      ...env({
        EULA: "TRUE",
        TYPE: "VANILLA",
        VERSION: MINECRAFT_VERSION,
        MEMORY: "2G",
        ONLINE_MODE: "FALSE",
        ENFORCE_SECURE_PROFILE: "FALSE",
        ENABLE_RCON: "TRUE",
        RCON_PASSWORD: "soulfire-e2e",
        LEVEL_TYPE: "minecraft:flat",
        GENERATOR_SETTINGS: VOID_WORLD,
        GENERATE_STRUCTURES: "FALSE",
        DIFFICULTY: "peaceful",
        MODE: "survival",
        SPAWN_PROTECTION: "0",
        VIEW_DISTANCE: "6",
        SIMULATION_DISTANCE: "6",
      }),
      IMAGE,
    ]);
  }
  const ready = eventually(rcon("list"), MINECRAFT_START_TIMEOUT, "1 second").pipe(
    Effect.mapError(
      (e) =>
        new CommandFailed({
          command: e.command,
          message: `the server didn't start: ${e.message} (see \`docker logs ${CONTAINER}\`)`,
        }),
    ),
  );
  const [took] = yield* Effect.timed(ready);
  yield* Console.log(`[minecraft] ready in ${seconds(took)}`);
  yield* prepareWorld;
  return { host: "127.0.0.1", port: MINECRAFT_PORT };
});

/** A world where nothing changes on its own, and a pad to spawn on (the void kills). */
const prepareWorld = Effect.forEach(
  [
    ...[
      "advance_time false",
      "advance_weather false",
      "spawn_mobs false",
      "random_tick_speed 0",
      "respawn_radius 0",
      "show_advancement_messages false",
    ].map((rule) => `gamerule ${rule}`),
    "time set noon",
    "weather clear",
    "forceload add -16 -16 15 15",
    "fill -2 63 -2 2 63 2 minecraft:stone",
    "setworldspawn 0 64 0",
  ],
  (command) => rcon(command),
  { discard: true },
);

function env(vars: Record<string, string>) {
  return Object.entries(vars).flatMap(([k, v]) => ["--env", `${k}=${v}`]);
}

// ---- Soulfire --------------------------------------------------------------------------------

/** The jar `./gradlew :dedicated-launcher:uberJar` builds, unless SOULFIRE_E2E_JAR names another. */
export function soulfireJar() {
  if (process.env.SOULFIRE_E2E_JAR) return path.resolve(process.env.SOULFIRE_E2E_JAR);
  const version = readFileSync(path.join(REPO_DIR, "gradle.properties"), "utf8").match(
    /^maven_version=(.+)$/m,
  )?.[1];
  return path.join(
    REPO_DIR,
    "dedicated-launcher",
    "build",
    "libs",
    `SoulFireDedicated-${version}.jar`,
  );
}

/** Java 25: SOULFIRE_E2E_JAVA, else the `java` on PATH. */
function javaPath() {
  if (process.env.SOULFIRE_E2E_JAVA) return process.env.SOULFIRE_E2E_JAVA;
  for (const dir of (process.env.PATH ?? "").split(path.delimiter)) {
    const candidate = path.join(dir, "java");
    try {
      accessSync(candidate, constants.X_OK);
      return candidate;
    } catch {}
  }
  throw new Error("no java on PATH; set SOULFIRE_E2E_JAVA");
}

const INSTANCE_NAME = "e2e";
export const BOT_NAME = "E2EBot";

/** Settings for the bot: nothing acts on its own. */
const BOT_SETTINGS: Record<string, Record<string, ReturnType<typeof value>>> = {
  "client-settings": { "render-distance": value(6) },
  "auto-eat": { enabled: value(false) },
  "auto-armor": { enabled: value(false) },
  "auto-totem": { enabled: value(false) },
  "auto-jump": { enabled: value(false) },
  "anti-afk": { enabled: value(false) },
  "auto-respawn": { enabled: value(false) },
  "auto-reconnect": { enabled: value(false) },
};

/**
 * Starts Soulfire from the jar, points a fresh instance at the Minecraft server, and brings the bot
 * in. Soulfire's console goes to `logFile`. Closing the scope stops the bot and Soulfire, whether
 * the setup finished or not.
 */
export const startSoulFire = Effect.fn("startSoulFire")(function* (
  jarPath: string,
  minecraft: { host: string; port: number },
  logFile: string,
) {
  yield* Console.log(`[soulfire] starting ${path.relative(REPO_DIR, jarPath)}...`);
  const directory = path.join(E2E_DIR, ".soulfire");
  yield* Effect.sync(() => mkdirSync(directory, { recursive: true }));
  const [took, soulfire] = yield* Effect.timed(
    SoulFire.install({
      directory,
      jarPath,
      javaPath: javaPath(),
      javaArgs: ["-Xms1G", "-Xmx4G"],
      startupTimeoutMs: 180_000,
      onLog: (line) => appendFileSync(logFile, line + "\n"),
    }),
  );
  yield* Console.log(`[soulfire] ready in ${seconds(took)}`);

  // Soulfire keeps its instances in its database: start from a clean one each run.
  const old = (yield* soulfire.instances()).filter((i) => i.friendlyName === INSTANCE_NAME);
  yield* Effect.forEach(old, (i) => soulfire.instance(i.id).delete(), { discard: true });
  const instance = yield* Effect.acquireRelease(
    soulfire.getOrCreateInstance(INSTANCE_NAME, { server: `${minecraft.host}:${minecraft.port}` }),
    (i) => i.delete().pipe(Effect.ignore),
  );
  for (const [namespace, entries] of Object.entries(BOT_SETTINGS)) {
    for (const [key, v] of Object.entries(entries)) {
      yield* instance.setConfigEntry({ namespace, key, value: v });
    }
  }
  const [joined, bot] = yield* Effect.timed(
    instance.getOrCreateBot(BOT_NAME, {
      readyTimeoutMs: 120_000,
    }),
  );
  yield* Console.log(`[soulfire] ${BOT_NAME} online in ${seconds(joined)}`);
  return { bot, client: soulfire };
});

// ---- what tests use --------------------------------------------------------------------------

export type Pos = { x: number; y: number; z: number };

export interface TestContext {
  bot: SoulFireBot;
  client: SoulFireClient;
}

/** A test: `run` builds its scene with `rcon`, drives the bot, and fails when a check fails. */
export interface E2ETest {
  name: string;
  run(t: TestContext): Effect.Effect<void, TestError>;
}

/**
 * Moves the bot to `at` (feet position) with an empty inventory and no effects, and waits until
 * Soulfire sees it there with the chunks around it loaded.
 */
export const placeBot = Effect.fn("placeBot")(function* (bot: SoulFireBot, at: Pos, yaw = 0) {
  yield* rcon(`clear ${BOT_NAME}`, { check: false }); // "No items were found" when already empty
  yield* rcon(`effect clear ${BOT_NAME}`, { check: false });
  yield* rcon(`tp ${BOT_NAME} ${at.x} ${at.y} ${at.z} ${yaw} 0`);
  yield* eventually(
    Effect.flatMap(bot.world.player(), ({ position: p }) =>
      check(
        !!p &&
          Math.abs(p.x - at.x) < 0.01 &&
          Math.abs(p.y - at.y) < 0.01 &&
          Math.abs(p.z - at.z) < 0.01,
        `bot not at ${JSON.stringify(at)} after teleport (at ${JSON.stringify(p)})`,
      ),
    ),
    "10 seconds",
    "100 millis",
  );
  yield* bot.waitForChunks({ radiusChunks: 2, timeoutMs: 10_000 }, { timeoutMs: 15_000 });
});

/** The bot's position as the server has it (not as Soulfire believes it is). */
export const serverPosition: Effect.Effect<Pos, CommandFailed | CheckFailed> = Effect.flatMap(
  rcon(`data get entity ${BOT_NAME} Pos`),
  (out) => {
    const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    return m
      ? Effect.succeed({ x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) })
      : Effect.fail(new CheckFailed({ message: `unexpected Pos output: ${out}` }));
  },
);

/** The block a position is in. */
export const blockOf = (p: Pos): Pos => ({
  x: Math.floor(p.x),
  y: Math.floor(p.y),
  z: Math.floor(p.z),
});

// ---- helpers ---------------------------------------------------------------------------------

export const seconds = (d: Duration.Duration) => `${(Duration.toMillis(d) / 1000).toFixed(1)}s`;

function value(v: string | number | boolean) {
  const kind =
    typeof v === "string"
      ? { case: "stringValue" as const, value: v }
      : typeof v === "number"
        ? { case: "numberValue" as const, value: v }
        : { case: "boolValue" as const, value: v };
  return create(ValueSchema, { kind });
}
