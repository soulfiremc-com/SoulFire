import { randomUUID } from "node:crypto";
import { PovService, PovInputEvent_Kind as Kind } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

const TAG = "sf_e2e_camera_handoff";
const yawDelta = (a: number, b: number) => ((a - b + 540) % 360) - 180;

export default {
  name: "pov-camera-handoff",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3440 0 3487 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon("fill 3440 63 0 3487 63 15 air", { check: false });
            yield* rcon("forceload remove 3440 0 3487 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3440 63 0 3487 63 15 stone");
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        for (const mode of [
          "sdk-look",
          "completed-attack",
          "cancelled-attack",
          "preempted-attack",
        ] as const) {
          yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
          yield* Effect.sleep("500 millis");
          yield* placeBot(bot, { x: 3455.5, y: 64, z: 8.5 }, -90);
          const task = yield* Effect.gen(function* () {
            if (mode === "sdk-look") {
              yield* bot.look(-90, 0);
              return;
            }
            yield* rcon(
              `summon cow ${mode === "completed-attack" ? 3458.5 : mode === "preempted-attack" ? 3477.5 : 3466.5} 64 8.5 {Tags:["${TAG}"],NoAI:1b}`,
            );
            const target = yield* eventually(
              bot.world
                .queryEntities({
                  radius: 24,
                  selector: { entityTypes: ["minecraft:cow"], alive: true },
                })
                .pipe(
                  Effect.map((result) => result.entities[0]?.reference),
                  Effect.filterOrFail(
                    (reference) => reference !== undefined,
                    () => new CheckFailed({ message: "The cow never became observable" }),
                  ),
                ),
              "5 seconds",
            );
            const attack = yield* bot.tasks.attackEntity(target, {
              maximumAttacks: 1,
              selectBestWeapon: false,
              restoreSelectedSlot: false,
            });
            yield* Effect.addFinalizer(() => attack.cancel("test cleanup").pipe(Effect.ignore));
            if (mode === "completed-attack") yield* attack.result({ call: { timeoutMs: 10000 } });
            else {
              yield* eventually(
                serverPosition.pipe(
                  Effect.flatMap((p) =>
                    check(p.x > 3456.3, "The bot never began pursuing its target"),
                  ),
                ),
                "5 seconds",
              );
              if (mode === "cancelled-attack") {
                yield* attack.cancel("verify camera handoff");
                yield* attack.wait({ call: { timeoutMs: 5000 } });
              }
            }
            return attack;
          });
          if (mode !== "preempted-attack") yield* Effect.sleep("700 millis");
          yield* Effect.tryPromise({
            try: async () => {
              const pov = client.service(PovService);
              const sessionId = randomUUID();
              const abort = new AbortController();
              let sequence = 0n;
              const nextSequence = () => ++sequence;
              const rotation = async () => {
                const text = await Effect.runPromise(rcon(`data get entity ${BOT_NAME} Rotation`));
                const match = text.match(/\[(-?[\d.]+)f, (-?[\d.]+)f\]/);
                if (!match) throw new Error(`Unexpected rotation: ${text}`);
                return Number(match[1]);
              };
              try {
                for await (const frame of pov.watch(
                  {
                    instanceId: bot.instanceId,
                    botId: bot.id,
                    sessionId,
                    width: 320,
                    height: 180,
                    maxFps: 15,
                  },
                  { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10000)]) },
                )) {
                  if (frame.screenOpen) continue;
                  const input = (
                    events: NonNullable<Parameters<typeof pov.input>[0]["events"]> = [],
                    captured = true,
                  ) =>
                    pov.input({
                      sessionId,
                      inputToken: frame.inputToken,
                      sequence: nextSequence(),
                      captured,
                      width: 320,
                      height: 180,
                      events,
                    });
                  await input();
                  await new Promise((resolve) => {
                    setTimeout(resolve, 150);
                  });
                  const before = await rotation();
                  await input([{ kind: Kind.MOVE, relative: true, x: 240, y: 0 }]);
                  await new Promise((resolve) => {
                    setTimeout(resolve, 400);
                  });
                  const moved = await rotation();
                  await input();
                  await new Promise((resolve) => {
                    setTimeout(resolve, 350);
                  });
                  const retained = await rotation();
                  if (
                    Math.abs(yawDelta(moved, before)) < 10 ||
                    Math.abs(yawDelta(retained, moved)) > 1
                  )
                    throw new Error(
                      `${mode} overrode manual POV rotation: ${JSON.stringify({ before, moved, retained })}`,
                    );
                  await input([], false);
                  await input();
                  await input([{ kind: Kind.MOVE, relative: true, x: -120, y: 0 }]);
                  await new Promise((resolve) => {
                    setTimeout(resolve, 400);
                  });
                  const recaptured = await rotation();
                  if (yawDelta(recaptured, retained) > -5)
                    throw new Error(`${mode} restored old aiming after recapture`);
                  await input([], false);
                  return;
                }
                throw new Error("POV ended before camera input");
              } finally {
                abort.abort();
              }
            },
            catch: (cause) => new CheckFailed({ message: String(cause) }),
          });
          if (mode === "preempted-attack" && task)
            yield* check(
              (yield* task.wait({ call: { timeoutMs: 5000 } })).status === BotTaskStatus.CANCELLED,
              "Capturing POV did not replace the active attack task",
            );
          yield* Effect.sleep("300 millis");
        }
      }),
    ),
} satisfies E2ETest;
