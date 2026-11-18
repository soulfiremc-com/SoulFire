import { randomUUID } from "node:crypto";
import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "pov-audio",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      yield* rcon("forceload add 1200 -16 1248 16");
      yield* rcon("fill 1218 219 -3 1226 219 3 glass");
      yield* rcon("fill 1218 220 -3 1226 224 3 air");
      yield* placeBot(bot, { x: 1222.5, y: 220, z: 0.5 });
      yield* rcon(`stopsound ${BOT_NAME}`);
      const service = client.service(PovService);
      // oxlint-disable-next-line effecttsgo/abort-controller-in-effect -- the async watch loop and heartbeat run outside Effect, and cleanup must abort before awaiting them
      const abort = new AbortController();
      const sessionId = randomUUID();
      let sequence = 0n;
      let sending = false;
      let enabled = false;
      let frames = 0;
      let audioEnabled = false;
      let audioError = "";
      let failure: unknown;
      const sizes: number[] = [];
      const timestamps: bigint[] = [];
      const watching = (async () => {
        try {
          for await (const frame of service.watch(
            {
              instanceId: bot.instanceId,
              botId: bot.id,
              sessionId,
              width: 320,
              height: 180,
              maxFps: 15,
            },
            { signal: abort.signal },
          )) {
            frames++;
            audioEnabled = frame.audioEnabled === true;
            audioError = frame.audioError;
            for (const packet of frame.audio) {
              sizes.push(packet.data.length);
              timestamps.push(packet.timestampUs);
            }
          }
        } catch (cause) {
          if (!abort.signal.aborted) failure = cause;
        }
      })();
      const heartbeat = setInterval(() => {
        if (!frames || sending) return;
        sending = true;
        void service
          .input(
            {
              sessionId,
              sequence: ++sequence,
              width: 320,
              height: 180,
              audioEnabled: enabled,
            },
            { signal: abort.signal },
          )
          .catch((cause: unknown) => {
            if (!abort.signal.aborted) failure = cause;
          })
          .finally(() => {
            sending = false;
          });
      }, 250);
      const ready = (condition: () => boolean, message: string) =>
        eventually(
          Effect.suspend(() =>
            check(
              failure === undefined && !audioError && condition(),
              failure instanceof Error ? failure.message : audioError || message,
            ),
          ),
          "15 seconds",
        );
      yield* Effect.gen(function* () {
        yield* ready(() => frames >= 5, "The POV video did not start");
        yield* check(!audioEnabled && sizes.length === 0, "POV audio starts without opt-in");
        enabled = true;
        yield* ready(() => audioEnabled && sizes.length >= 10, "Opt-in audio did not start");
        const rateStart = timestamps.length;
        yield* Effect.sleep("1 second");
        yield* check(
          timestamps.length - rateStart >= 35,
          `POV audio stalled at a 15 FPS video target (${timestamps.length - rateStart} packets/s)`,
        );
        const silentPeak = Math.max(...sizes);
        sizes.length = 0;
        for (let note = 0; note < 5; note++) {
          yield* rcon(
            `playsound minecraft:block.note_block.pling master ${BOT_NAME} 1222.5 220 0.5 4 1`,
          );
          yield* Effect.sleep("100 millis");
        }
        yield* ready(
          () => sizes.some((size) => size > silentPeak + 50),
          "Server sound never reached the POV audio stream",
        );
        yield* check(
          timestamps.every((timestamp, index) => timestamp > (timestamps[index - 1] ?? -Infinity)),
          "POV audio timestamps regressed",
        );
        enabled = false;
        yield* ready(() => !audioEnabled, "Mute did not stop server audio");
        const mutedCount = sizes.length;
        const mutedFrame = frames;
        yield* ready(() => frames >= mutedFrame + 5, "Video stopped when audio was muted");
        yield* check(sizes.length === mutedCount, "Muted POV continues transmitting audio");
        sizes.length = 0;
        enabled = true;
        yield* ready(() => audioEnabled && sizes.length >= 3, "Audio could not restart after mute");
      }).pipe(
        Effect.ensuring(
          Effect.promise(async () => {
            clearInterval(heartbeat);
            abort.abort();
            await watching;
          }),
        ),
      );
    }),
} satisfies E2ETest;
