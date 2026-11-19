import { Effect, Fiber, Stream } from "effect";
import { mkdir } from "node:fs/promises";
import process from "node:process";

import { beatGame, beatGameTeam } from "@soulfiremc/beat-game";
import { JsonFileBeatGameCheckpointStore } from "@soulfiremc/beat-game/node";
import { SoulFire } from "@soulfiremc/sdk/node";

const configuration = {
  baseUrl: requiredEnvironment("SOULFIRE_SMOKE_BASE_URL"),
  token: requiredEnvironment("SOULFIRE_SMOKE_TOKEN"),
  instanceId: requiredEnvironment("SOULFIRE_SMOKE_INSTANCE_ID"),
  botIds: commaSeparatedEnvironment("SOULFIRE_SMOKE_BOT_IDS"),
  runId: environment("SOULFIRE_SMOKE_RUN_ID", "beat-game-live-smoke"),
  teamId: environment("SOULFIRE_SMOKE_TEAM_ID", "beat-game-live-smoke-team"),
  checkpointDirectory: environment("SOULFIRE_SMOKE_CHECKPOINT_DIR", "./beat-game-checkpoints"),
  startBots: booleanEnvironment("SOULFIRE_SMOKE_START_BOTS", true),
  crashAfterCheckpoints: optionalPositiveIntegerEnvironment(
    "SOULFIRE_SMOKE_CRASH_AFTER_CHECKPOINTS",
  ),
};

await mkdir(configuration.checkpointDirectory, { recursive: true });

await Effect.runPromise(
  Effect.scoped(
    Effect.gen(function* () {
      const soulfire = yield* SoulFire.connect({
        baseUrl: configuration.baseUrl,
        token: configuration.token,
      });

      const bots = configuration.botIds.map((botId) =>
        soulfire.instance(configuration.instanceId).bot(botId),
      );
      if (configuration.startBots) {
        yield* Effect.forEach(bots, (bot) => bot.start(), {
          concurrency: "unbounded",
          discard: true,
        });
      }

      const checkpointStore = new JsonFileBeatGameCheckpointStore(
        configuration.checkpointDirectory,
      );
      const run =
        bots.length === 1
          ? yield* beatGame(bots[0], {
              runId: configuration.runId,
              checkpointStore,
              team: { teamId: configuration.teamId },
            })
          : yield* beatGameTeam(bots, {
              teamId: configuration.teamId,
              checkpointStore,
            });
      const memberRuns = "runs" in run ? run.runs : [run];
      let savedCheckpoints = 0;

      const eventConsumers = yield* Effect.forEach(memberRuns, (memberRun) =>
        Effect.forkScoped(
          memberRun.events.pipe(
            Stream.runForEach((event) =>
              Effect.sync(() => {
                process.stdout.write(
                  `${jsonLine({
                    kind: "beat-game-event",
                    memberRunId: memberRun.id,
                    event,
                  })}\n`,
                );
                if (event.type !== "checkpoint-saved") {
                  return;
                }
                savedCheckpoints += 1;
                if (
                  configuration.crashAfterCheckpoints !== undefined &&
                  savedCheckpoints >= configuration.crashAfterCheckpoints
                ) {
                  process.stderr.write(
                    `${jsonLine({
                      kind: "intentional-hard-crash",
                      savedCheckpoints,
                    })}\n`,
                  );
                  process.exit(75);
                }
              }),
            ),
          ),
        ),
      );

      const result = yield* run.awaitCompletion;
      yield* Effect.forEach(eventConsumers, Fiber.join, {
        concurrency: "unbounded",
        discard: true,
      });
      process.stdout.write(
        `${jsonLine({
          kind: "beat-game-completed",
          result,
        })}\n`,
      );
    }),
  ),
);

/** @param {string} name */
function requiredEnvironment(name) {
  const value = process.env[name]?.trim();
  if (value === undefined || value.length === 0) {
    throw new Error(`${name} is required`);
  }
  return value;
}

/** @param {string} name */
function commaSeparatedEnvironment(name) {
  const values = requiredEnvironment(name)
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  if (values.length === 0 || new Set(values).size !== values.length) {
    throw new Error(`${name} must contain unique comma-separated bot IDs`);
  }
  return values;
}

/**
 * @param {string} name
 * @param {string} fallback
 */
function environment(name, fallback) {
  const value = process.env[name]?.trim();
  return value === undefined || value.length === 0 ? fallback : value;
}

/**
 * @param {string} name
 * @param {boolean} fallback
 */
function booleanEnvironment(name, fallback) {
  const value = process.env[name]?.trim().toLowerCase();
  if (value === undefined || value.length === 0) {
    return fallback;
  }
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  throw new Error(`${name} must be true or false`);
}

/** @param {string} name */
function optionalPositiveIntegerEnvironment(name) {
  const value = process.env[name]?.trim();
  if (value === undefined || value.length === 0) {
    return undefined;
  }
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return number;
}

/** @param {unknown} value */
function jsonLine(value) {
  return JSON.stringify(
    value,
    /**
     * @param {string} _
     * @param {unknown} nestedValue
     */
    (_, nestedValue) => (typeof nestedValue === "bigint" ? nestedValue.toString() : nestedValue),
  );
}
