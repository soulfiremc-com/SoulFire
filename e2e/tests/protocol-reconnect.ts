import {
  BotProtocolService,
  PacketDirection,
} from "@soulfiremc/sdk/generated/soulfire/protocol_pb";
import { Effect } from "effect";
import { CheckFailed, check, type E2ETest } from "../harness.ts";

export default {
  name: "protocol-reconnect",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const runPromise = Effect.runPromiseWith(yield* Effect.context());
      const service = client.service(BotProtocolService);
      const scope = { instanceId: bot.instanceId, botId: bot.id };
      const observed = yield* Effect.tryPromise({
        try: async () => {
          const abort = new AbortController();
          let resolveReady!: () => void;
          let rejectReady!: (cause: unknown) => void;
          const watching = new Promise<void>((resolve, reject) => {
            resolveReady = resolve;
            rejectReady = reject;
          });
          const readyTimer = setTimeout(
            () => rejectReady(new Error("Packet observation never became ready")),
            5000,
          );
          const timer = setTimeout(() => abort.abort(), 15000);
          // The stream below sets these flags while the outer code awaits it.
          const seen = { information: false, encoded: false };
          const stream = (async () => {
            try {
              for await (const event of service.watchPackets(
                {
                  ...scope,
                  directions: [PacketDirection.SERVERBOUND],
                  names: ["minecraft:client_information", "minecraft:client_tick_end"],
                  includeEncodedPacket: true,
                },
                { signal: abort.signal },
              )) {
                resolveReady();
                if (
                  event.protocolState === "configuration" &&
                  event.name === "minecraft:client_information"
                ) {
                  seen.information = true;
                  seen.encoded = event.encodedPacket.length > 1 && !event.encodedPacketTruncated;
                }
                if (seen.information && event.protocolState === "play") {
                  abort.abort();
                  break;
                }
              }
            } catch (error) {
              if (!abort.signal.aborted) {
                rejectReady(error);
                throw error;
              }
            }
          })();
          try {
            await watching;
            clearTimeout(readyTimer);
            await runPromise(bot.restart());
            await stream;
            return seen.information && seen.encoded;
          } finally {
            abort.abort();
            clearTimeout(readyTimer);
            clearTimeout(timer);
            await stream;
          }
        },
        catch: (cause) =>
          new CheckFailed({ message: `Reconnect observation failed: ${String(cause)}` }),
      });
      yield* bot.connect({ readyTimeoutMs: 15000 });
      yield* check(
        observed,
        "Packet observation lost the new connection's encoded configuration packets",
      );
    }).pipe(Effect.scoped),
} satisfies E2ETest;
