// Runs the end-to-end tests: starts the Minecraft server and Soulfire, then each test in turn.
//
//   bun run test                      every test in tests/
//   bun run test walk-straight        only those named
//   bun run test --fresh              a new Minecraft server (and world) instead of the running one
//   bun run test --stop               remove the Minecraft server at the end
//
// SOULFIRE_E2E_JAR runs another Soulfire jar (e.g. one built from upstream, to compare).
import { existsSync, mkdirSync, readdirSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";
import * as NodeRuntime from "@effect/platform-node/NodeRuntime";
import { Cause, Console, Data, Effect, Result } from "effect";
import {
  E2E_DIR,
  type E2ETest,
  saveMinecraftLog,
  seconds,
  soulfireJar,
  startMinecraft,
  startSoulFire,
  stopMinecraft,
} from "./harness.ts";

class TestsFailed extends Data.TaggedError("TestsFailed")<{ readonly message: string }> {}

const { values: flags, positionals: names } = parseArgs({
  allowPositionals: true,
  options: {
    fresh: { type: "boolean", default: false },
    stop: { type: "boolean", default: false },
  },
});

const tests: E2ETest[] = [];
for (const file of readdirSync(path.join(E2E_DIR, "tests"))
  .filter((f) => f.endsWith(".ts"))
  .sort()) {
  tests.push((await import(path.join(E2E_DIR, "tests", file))).default);
}
if (!tests.length) throw new Error("no tests in tests/");
const selected = names.length ? tests.filter((t) => names.includes(t.name)) : tests;
const unknown = names.filter((n) => !tests.some((t) => t.name === n));
if (unknown.length)
  throw new Error(
    `no such test: ${unknown.join(", ")} (have: ${tests.map((t) => t.name).join(", ")})`,
  );

const jar = soulfireJar();
if (!existsSync(jar))
  throw new Error(
    `no Soulfire jar at ${jar}: run ./gradlew :dedicated-launcher:uberJar, or set SOULFIRE_E2E_JAR`,
  );

const runDir = path.join(E2E_DIR, "runs", new Date().toISOString().replace(/[:.]/g, "-"));
mkdirSync(runDir, { recursive: true });

const program = Effect.gen(function* () {
  yield* Console.log(`[e2e] logs in ${path.relative(process.cwd(), runDir)}`);
  // Added first, so it runs last: after the bot and Soulfire have stopped.
  yield* Effect.addFinalizer(() =>
    saveMinecraftLog(path.join(runDir, "minecraft.log")).pipe(
      Effect.andThen(flags.stop ? stopMinecraft : Effect.void),
    ),
  );

  const [setUp, context] = yield* Effect.timed(
    Effect.gen(function* () {
      const minecraft = yield* startMinecraft({ fresh: flags.fresh });
      return yield* startSoulFire(jar, minecraft, path.join(runDir, "soulfire.log"));
    }),
  );
  yield* Console.log(`[e2e] set up in ${seconds(setUp)}\n`);

  let failed = 0;
  for (const test of selected) {
    const [took, result] = yield* Effect.timed(Effect.result(test.run(context)));
    if (Result.isSuccess(result)) {
      yield* Console.log(`  ok    ${test.name} (${seconds(took)})`);
    } else {
      failed++;
      const message =
        "message" in result.failure
          ? result.failure.message
          : Cause.pretty(Cause.fail(result.failure));
      yield* Console.log(
        `  FAIL  ${test.name} (${seconds(took)})\n        ${message.replace(/\n/g, "\n        ")}`,
      );
    }
  }
  yield* Console.log(`\n${selected.length - failed}/${selected.length} passed`);
  if (failed)
    return yield* new TestsFailed({ message: `${failed} of ${selected.length} tests failed` });
});

NodeRuntime.runMain(Effect.scoped(program));
