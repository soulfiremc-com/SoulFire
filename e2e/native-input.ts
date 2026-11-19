import type { MessageInitShape } from "@bufbuild/protobuf";
import type { SoulFireBot, SoulFireClient } from "@soulfiremc/sdk";
import type { PovFrame, PovInputEventSchema } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { PovInputEvent_Kind as Kind, PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import { randomUuid, CheckFailed, required } from "./harness.ts";

// Each native screen uses GUI scale 3 and logical dimensions 427x240.
export const nativeInput = (
  client: SoulFireClient,
  bot: SoulFireBot,
  action: (input: {
    click: (x: number, y: number) => Promise<unknown>;
    type: (text: string) => Promise<unknown>;
    escape: () => Promise<unknown>;
  }) => Promise<void>,
) =>
  Effect.tryPromise({
    try: async () => {
      const service = client.service(PovService);
      const sessionId = randomUuid();
      const abort = new AbortController();
      let latest: PovFrame | undefined;
      let streamError: Error | undefined;
      let sequence = 0n;
      let pending: Promise<unknown> = Promise.resolve();
      const watching = (async () => {
        try {
          for await (const frame of service.watch(
            {
              instanceId: bot.instanceId,
              botId: bot.id,
              sessionId,
              width: 1280,
              height: 720,
              maxFps: 15,
            },
            { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
          ))
            latest = frame;
        } catch (error) {
          if (!abort.signal.aborted)
            streamError = error instanceof Error ? error : new Error(String(error));
        }
      })();
      const send = (
        events: MessageInitShape<typeof PovInputEventSchema>[] = [],
        escape = false,
      ) => {
        const sent = pending.then(() =>
          service.input({
            sessionId,
            inputToken: required(latest, "a POV frame").inputToken,
            sequence: ++sequence,
            captured: true,
            width: 1280,
            height: 720,
            events,
            escape,
          }),
        );
        pending = sent;
        return sent;
      };
      // The frame stream updates `latest` while the loops below wait for it.
      const screenOpen = () => latest?.screenOpen;
      const heartbeat = setInterval(() => {
        if (latest)
          void send().catch((error: unknown) => {
            streamError = error instanceof Error ? error : new Error(String(error));
          });
      }, 500);
      try {
        const deadline = performance.now() + 10_000;
        while (!screenOpen()) {
          if (streamError) throw streamError;
          if (performance.now() > deadline) throw new Error("The native inventory did not render");
          await Effect.runPromise(Effect.sleep(25));
        }
        await action({
          click: (x, y) =>
            send([
              { kind: Kind.MOVE, x: x / 427, y: y / 240 },
              { kind: Kind.BUTTON, code: 0, action: 1 },
              { kind: Kind.BUTTON, code: 0, action: 0 },
            ]),
          type: (text) =>
            send(
              Array.from(text, (character) => ({
                kind: Kind.CHARACTER,
                code: required(character.codePointAt(0), "a code point"),
              })),
            ),
          escape: async () => {
            await send([], true);
            const escapeDeadline = performance.now() + 5000;
            while (screenOpen() !== false) {
              if (streamError) throw streamError;
              if (performance.now() > escapeDeadline)
                throw new Error("Escape did not return to the game view");
              await Effect.runPromise(Effect.sleep(25));
            }
          },
        });
        if (streamError) throw streamError;
      } finally {
        clearInterval(heartbeat);
        abort.abort();
        await watching;
        await pending.catch(() => {});
      }
    },
    catch: (cause) => new CheckFailed({ message: `Native input failed: ${String(cause)}` }),
  });
