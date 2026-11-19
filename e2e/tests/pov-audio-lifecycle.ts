import type { SoulFireBot } from "@soulfiremc/sdk";
import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  check,
  type E2ETest,
  eventually,
  rcon,
  required,
} from "../harness.ts";

export default {
  name: "pov-audio-lifecycle",
  run: ({ bot, client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const instance = client.instance(bot.instanceId);
        const others: SoulFireBot[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            for (const other of others) yield* other.stop().pipe(Effect.ignore);
            yield* instance.removeAccounts(others.map((other) => other.id)).pipe(Effect.ignore);
          }),
        );
        const suffix = randomUuid().slice(0, 6);
        const otherNames = [`AudioA_${suffix}`, `AudioB_${suffix}`];
        for (const name of otherNames) {
          const other = yield* instance.getOrCreateBot(name, { start: false });
          others.push(other);
          yield* other.start();
          yield* other.connect({ readyTimeoutMs: 30_000 });
        }
        const service = client.service(PovService);
        const names = [BOT_NAME, ...otherNames];
        const sessions = [bot, ...others].map((target, index) => ({
          name: required(names[index], "the bot name"),
          target,
          id: randomUuid(),
          abort: new AbortController(),
          sequence: 0n,
          frames: 0,
          packets: 0,
          enabled: false,
          requested: false,
          error: "",
          lastTimestamp: -1n,
          failure: undefined as unknown,
          sending: false,
          pending: Promise.resolve(),
        }));
        const watchers = sessions.map(async (session) => {
          try {
            for await (const frame of service.watch(
              {
                instanceId: bot.instanceId,
                botId: session.target.id,
                sessionId: session.id,
                width: 320,
                height: 180,
                maxFps: 30,
              },
              { signal: session.abort.signal },
            )) {
              session.frames++;
              session.enabled = frame.audioEnabled === true;
              session.error = frame.audioError;
              for (const packet of frame.audio) {
                if (packet.timestampUs <= session.lastTimestamp)
                  throw new Error("Audio timestamps regressed after a lifecycle transition");
                session.lastTimestamp = packet.timestampUs;
                session.packets++;
              }
            }
          } catch (failure) {
            if (!session.abort.signal.aborted) session.failure = failure;
          }
        });
        const heartbeat = setInterval(() => {
          for (const session of sessions) {
            if (!session.frames || session.sending) continue;
            session.sending = true;
            session.pending = service
              .input(
                {
                  sessionId: session.id,
                  sequence: ++session.sequence,
                  width: 320,
                  height: 180,
                  audioEnabled: session.requested,
                },
                { signal: session.abort.signal },
              )
              .then(() => undefined)
              .catch((failure: unknown) => {
                if (!session.abort.signal.aborted) session.failure = failure;
              })
              .finally(() => {
                session.sending = false;
              });
          }
        }, 250);
        const ready = (condition: () => boolean, message: string) =>
          eventually(
            Effect.suspend(() =>
              check(
                sessions.every((session) => !session.failure && !session.error) && condition(),
                message,
              ),
            ),
            "15 seconds",
          );
        yield* Effect.gen(function* () {
          yield* ready(
            () => sessions.every((session) => session.frames >= 3),
            "Concurrent POVs did not start",
          );
          for (let cycle = 0; cycle < 3; cycle++) {
            const before = sessions.map((session) => session.packets);
            for (const session of sessions) session.requested = true;
            yield* ready(
              () =>
                sessions.every(
                  (session, index) =>
                    session.enabled && session.packets >= (before[index] ?? Infinity) + 10,
                ),
              "Audio initialization or restart ended a concurrent POV",
            );
            for (const session of sessions) yield* rcon(`stopsound ${session.name}`);
            const stopped = sessions.map((session) => session.packets);
            yield* ready(
              () =>
                sessions.every(
                  (session, index) => session.packets >= (stopped[index] ?? Infinity) + 10,
                ),
              "Stopping native sounds broke audio capture",
            );
            for (const session of sessions) session.requested = false;
            yield* ready(
              () => sessions.every((session) => !session.enabled),
              "Muting did not release every audio context",
            );
            const muted = sessions.map((session) => ({
              packets: session.packets,
              frames: session.frames,
            }));
            yield* ready(
              () =>
                sessions.every(
                  (session, index) => session.frames >= (muted[index]?.frames ?? Infinity) + 5,
                ),
              "Video stopped during audio cleanup",
            );
            yield* check(
              sessions.every((session, index) => session.packets === muted[index]?.packets),
              "A muted bot continued sending audio",
            );
          }
        }).pipe(
          Effect.ensuring(
            Effect.promise(async () => {
              clearInterval(heartbeat);
              for (const session of sessions) session.abort.abort();
              await Promise.allSettled(watchers);
              await Promise.allSettled(sessions.map((session) => session.pending));
            }),
          ),
        );
      }),
    ),
} satisfies E2ETest;
