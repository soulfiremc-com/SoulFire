import { stripVTControlCharacters } from "node:util";
import { Effect, Semaphore, type Scope } from "effect";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHmac } from "node:crypto";
import { mkdir, readFile } from "node:fs/promises";
import path from "node:path";
import readline from "node:readline";

import { operationError, type SoulFireOperationError } from "./errors.js";
import type { LocalSoulFireServer, SoulFireInstallOptions } from "./install-types.js";
import {
  ensureDownload,
  ensureJvm,
  findAvailablePort,
  getJavaHome,
  requireLocalFile,
  requireSha256Digest,
  resolveDedicatedAsset,
  resolveRelease,
} from "./local-server-io.js";

const ROOT_USER_UUID = "00000000-0000-0000-0000-000000000000";
const DEFAULT_STARTUP_TIMEOUT_MS = 120_000;

export { resolveRelease } from "./local-server-io.js";

export interface LocalServerHandle {
  readonly info: LocalSoulFireServer;
  readonly token: string;
  isRunning(): boolean;
  logs(): readonly string[];
  restart(): Effect.Effect<void, SoulFireOperationError>;
  stop(): Effect.Effect<void>;
  close(): Effect.Effect<void>;
}

export function installLocalServer(
  options: SoulFireInstallOptions = {},
): Effect.Effect<LocalServerHandle, SoulFireOperationError, Scope.Scope> {
  return Effect.gen(function* () {
    const startupTimeout = options.startupTimeoutMs ?? DEFAULT_STARTUP_TIMEOUT_MS;
    if (!Number.isFinite(startupTimeout) || startupTimeout <= 0) {
      return yield* Effect.fail(
        operationError(
          "install.timeout",
          new RangeError("startupTimeoutMs must be a positive finite number"),
        ),
      );
    }
    const fetchImplementation = options.fetch ?? globalThis.fetch;
    const directory = path.resolve(options.directory ?? ".soulfire");
    yield* installIO("install.mkdir", () => mkdir(directory, { recursive: true }));
    const managedJvmDirectory = path.join(directory, "jvm-25");
    const javaPath = yield* installIO("install.java", (signal) =>
      options.javaPath === undefined
        ? ensureJvm(managedJvmDirectory, (input, init) =>
            fetchImplementation(input, { ...init, signal }),
          )
        : requireLocalFile(options.javaPath, "Java executable"),
    );
    const javaHome =
      options.javaPath === undefined
        ? getJavaHome(managedJvmDirectory)
        : path.dirname(path.dirname(javaPath));
    let jarPath: string;
    let installedVersion = "local";
    if (options.jarPath === undefined) {
      const release = yield* installIO("install.release", (signal) =>
        resolveRelease(options.version, (input, init) =>
          fetchImplementation(input, { ...init, signal }),
        ),
      );
      const jar = yield* Effect.try({
        try: () => resolveDedicatedAsset(release, options.version),
        catch: (cause) => operationError("install.release", cause),
      });
      jarPath = path.join(directory, "jars", jar.name);
      const digest = yield* Effect.try({
        try: () => requireSha256Digest(jar.digest, "Soulfire release"),
        catch: (cause) => operationError("install.release", cause),
      });
      yield* installIO("install.download", (signal) =>
        ensureDownload(jar.browser_download_url, jarPath, digest, (input, init) =>
          fetchImplementation(input, { ...init, signal }),
        ),
      );
      installedVersion = release.tag_name;
    } else
      jarPath = yield* installIO("install.jar", () =>
        requireLocalFile(options.jarPath!, "Soulfire JAR"),
      );
    const runDirectory = path.join(directory, "server");
    yield* installIO("install.mkdir", () => mkdir(runDirectory, { recursive: true }));
    const port = options.port ?? (yield* installIO("install.port", () => findAvailablePort()));
    if (!Number.isSafeInteger(port) || port < 1 || port > 65535)
      return yield* Effect.fail(
        operationError(
          "install.port",
          new RangeError("port must be an integer between 1 and 65535"),
        ),
      );
    const logs: string[] = [];
    const spawnServer = () =>
      spawn(javaPath, [...(options.javaArgs ?? []), `-Dsf.grpc.port=${port}`, "-jar", jarPath], {
        cwd: runDirectory,
        env: { ...process.env, JAVA_HOME: javaHome },
        stdio: "pipe",
        windowsHide: true,
      });
    const semaphore = yield* Semaphore.make(1);
    let child: ChildProcessWithoutNullStreams;
    let info: LocalSoulFireServer;
    const start = Effect.fn("SoulFire.install.start")(function* () {
      child = yield* Effect.try({
        try: spawnServer,
        catch: (cause) => operationError("install.spawn", cause),
      });
      const current = child;
      const output = readline.createInterface({ input: current.stdout });
      const errors = readline.createInterface({ input: current.stderr });
      const onLine = (rawLine: string) => {
        const line = stripVTControlCharacters(rawLine).trim();
        if (line.length === 0) return;
        logs.push(line);
        try {
          options.onLog?.(line);
        } catch {
          /* Keep draining process output if a log consumer fails. */
        }
      };
      output.on("line", onLine);
      errors.on("line", onLine);
      current.once("close", () => {
        output.close();
        errors.close();
      });
      yield* waitForServerReady(current, output, errors).pipe(
        Effect.timeoutOrElse({
          duration: startupTimeout,
          orElse: () =>
            Effect.fail(
              operationError(
                "install.ready",
                new Error("Soulfire did not finish loading before the startup timeout"),
              ),
            ),
        }),
        Effect.onError(() => stopChild(current)),
      );
      if (current.pid === undefined)
        return yield* Effect.fail(
          operationError("install.pid", new Error("Soulfire process did not provide a process ID")),
        );
      info = {
        baseUrl: `http://127.0.0.1:${port}`,
        directory,
        jarPath,
        javaPath,
        pid: current.pid,
        runDirectory,
        version: installedVersion,
      };
    });
    const stop = Effect.suspend(() => (child === undefined ? Effect.void : stopChild(child)));
    // Register process ownership before waiting for readiness.
    yield* Effect.acquireRelease(Effect.void, () => semaphore.withPermits(1)(stop));
    yield* start();
    const secretKey = yield* installIO("install.token", () =>
      readFile(path.join(runDirectory, "secret-key.bin")),
    );
    return {
      get info() {
        return info;
      },
      token: createRootApiToken(secretKey),
      isRunning: () => child.exitCode === null && child.signalCode === null,
      logs: () => [...logs],
      restart: () => semaphore.withPermits(1)(stop.pipe(Effect.andThen(start()))),
      stop: () => semaphore.withPermits(1)(stop),
      close: () => semaphore.withPermits(1)(stop),
    };
  });
}

function waitForServerReady(
  child: ChildProcessWithoutNullStreams,
  output: readline.Interface,
  errors: readline.Interface,
): Effect.Effect<void, SoulFireOperationError> {
  return Effect.callback<void, SoulFireOperationError>((resume) => {
    const cleanup = () => {
      output.off("line", onLine);
      errors.off("line", onLine);
      child.off("error", onError);
      child.off("exit", onExit);
    };
    const finish = (result: Effect.Effect<void, SoulFireOperationError>) => {
      cleanup();
      resume(result);
    };
    const onLine = (line: string) => {
      if (line.includes("Finished loading!")) finish(Effect.void);
    };
    const onError = (cause: Error) => finish(Effect.fail(operationError("install.ready", cause)));
    const onExit = (code: number | null) =>
      finish(
        Effect.fail(
          operationError(
            "install.ready",
            new Error(`Soulfire exited before finishing loading (exit code ${code ?? "unknown"})`),
          ),
        ),
      );
    output.on("line", onLine);
    errors.on("line", onLine);
    child.once("error", onError);
    child.once("exit", onExit);
    return Effect.sync(cleanup);
  });
}

function stopChild(child: ChildProcessWithoutNullStreams): Effect.Effect<void> {
  return Effect.suspend(() => {
    if (child.pid === undefined || child.exitCode !== null || child.signalCode !== null)
      return Effect.void;
    return Effect.callback<void>((resume) => {
      const onExit = () => resume(Effect.void);
      child.once("exit", onExit);
      child.kill("SIGTERM");
      return Effect.sync(() => child.off("exit", onExit));
    }).pipe(
      Effect.timeoutOrElse({
        duration: 5000,
        orElse: () =>
          Effect.callback<void>((resume) => {
            if (child.exitCode !== null || child.signalCode !== null) {
              resume(Effect.void);
              return;
            }
            const onExit = () => resume(Effect.void);
            child.once("exit", onExit);
            child.kill("SIGKILL");
            return Effect.sync(() => child.off("exit", onExit));
          }),
      }),
    );
  });
}

function createRootApiToken(secretKey: Buffer): string {
  const issuedAt = Math.floor(Date.now() / 1_000);
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const claims = Buffer.from(
    JSON.stringify({
      aud: ["api"],
      iat: issuedAt,
      sub: ROOT_USER_UUID,
    }),
  ).toString("base64url");
  const unsignedToken = `${header}.${claims}`;
  const signature = createHmac("sha256", secretKey).update(unsignedToken).digest("base64url");
  return `${unsignedToken}.${signature}`;
}

function installIO<A>(
  operation: string,
  call: (signal: AbortSignal) => Promise<A>,
): Effect.Effect<A, SoulFireOperationError> {
  return Effect.tryPromise({ try: call, catch: (cause) => operationError(operation, cause) }).pipe(
    Effect.withSpan(operation),
  );
}
