import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Effect, Exit, Fiber } from "effect";
import { describe, expect, it } from "vitest";
import { installLocalServer } from "../src/local-server.js";

const fixture = fileURLToPath(new URL("./fixtures/managed-server.ts", import.meta.url));

function temporaryInstall() {
  return Effect.acquireRelease(
    Effect.promise(async () => {
      const directory = await mkdtemp(path.join(os.tmpdir(), "soulfire-install-"));
      const jarPath = path.join(directory, "server.jar");
      await writeFile(jarPath, "fixture");
      return { directory, jarPath, javaPath: process.execPath };
    }),
    ({ directory }) => Effect.promise(() => rm(directory, { recursive: true, force: true })),
  );
}

function running(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

describe("managed process scopes", () => {
  it("owns startup, restart, and shutdown in the enclosing scope", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const options = yield* temporaryInstall();
          let firstPid = 0;
          let lastPid = 0;
          yield* Effect.scoped(
            Effect.gen(function* () {
              const server = yield* installLocalServer({
                ...options,
                javaArgs: [fixture, "ready"],
                startupTimeoutMs: 2000,
              });
              firstPid = server.info.pid;
              expect(running(firstPid)).toBe(true);
              expect(server.token.length).toBeGreaterThan(0);
              yield* server.restart();
              lastPid = server.info.pid;
              expect(lastPid).not.toBe(firstPid);
              expect(running(firstPid)).toBe(false);
              expect(running(lastPid)).toBe(true);
            }),
          );
          expect(running(lastPid)).toBe(false);
        }),
      ),
    ));

  it("interrupts a process that has not reached readiness", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const options = yield* temporaryInstall();
          let markStarted!: (pid: number) => void;
          const started = new Promise<number>((resolve) => {
            markStarted = resolve;
          });
          const fiber = yield* Effect.scoped(
            installLocalServer({
              ...options,
              javaArgs: [fixture, "pending"],
              startupTimeoutMs: 10000,
              onLog: (line) => {
                if (line.startsWith("spawned:")) markStarted(Number(line.slice(8)));
              },
            }),
          ).pipe(Effect.forkScoped);
          const pid = yield* Effect.promise(() => started);
          expect(running(pid)).toBe(true);
          yield* Fiber.interrupt(fiber).pipe(Effect.timeout("2 seconds"));
          expect(running(pid)).toBe(false);
        }),
      ),
    ));

  it("cleans up when readiness fails or times out", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const options = yield* temporaryInstall();
          for (const mode of ["fail", "pending"]) {
            let pid = 0;
            const exit = yield* Effect.exit(
              Effect.scoped(
                installLocalServer({
                  ...options,
                  javaArgs: [fixture, mode],
                  startupTimeoutMs: mode === "pending" ? 200 : 2000,
                  onLog: (line) => {
                    if (line.startsWith("spawned:")) pid = Number(line.slice(8));
                  },
                }),
              ),
            );
            expect(Exit.isFailure(exit)).toBe(true);
            expect(pid).toBeGreaterThan(0);
            expect(running(pid)).toBe(false);
          }
        }),
      ),
    ));
});
