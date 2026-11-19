import { fromJson } from "@bufbuild/protobuf";
import { ValueSchema } from "@bufbuild/protobuf/wkt";
import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

export default {
  name: "anti-afk-control",
  run: ({ bot, client }) => {
    const instance = client.instance(bot.instanceId);
    const setting = (key: string, value: boolean | { min: number; max: number }) =>
      instance.setConfigEntry({ namespace: "anti-afk", key, value: fromJson(ValueSchema, value) });
    return Effect.gen(function* () {
      const effectContext = yield* Effect.context();
      const runPromise = Effect.runPromiseWith(effectContext);
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon("forceload add 2080 0 2111 31");
      yield* rcon("fill 2080 63 0 2110 63 30 stone");
      yield* placeBot(bot, { x: 2096.5, y: 64, z: 16.5 });
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* setting("delay", { min: 1, max: 1 });
      yield* setting("distance", { min: 2, max: 2 });
      const origin = yield* serverPosition;
      yield* setting("enabled", true);
      // The first timer may still use the default 15-30 second interval.
      yield* eventually(
        serverPosition.pipe(
          Effect.flatMap((position) =>
            check(
              Math.hypot(position.x - origin.x, position.z - origin.z) > 0.25,
              "Anti AFK never moved the idle bot",
            ),
          ),
        ),
        "35 seconds",
        "100 millis",
      );
      yield* setting("enabled", false);
      yield* Effect.tryPromise({
        try: async () => {
          const service = client.service(PovService);
          const sessionId = randomUuid();
          const abort = new AbortController();
          let sequence = 0n;
          let token = "";
          let captured = false;
          const input = (value = true) =>
            service.input({
              sessionId,
              inputToken: token,
              sequence: ++sequence,
              captured: value,
              width: 640,
              height: 480,
            });
          try {
            for await (const frame of service.watch(
              {
                sessionId,
                instanceId: bot.instanceId,
                botId: bot.id,
                width: 640,
                height: 480,
                maxFps: 15,
              },
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15_000)]) },
            )) {
              token = frame.inputToken;
              await input();
              captured = true;
              await runPromise(Effect.sleep(500));
              const start = await runPromise(serverPosition);
              for (const enabled of [false, true]) {
                await runPromise(setting("enabled", enabled));
                for (let sample = 0; sample < 30; sample++) {
                  await input();
                  await runPromise(Effect.sleep(100));
                  const info = await runPromise(bot.info());
                  if (!info.liveState) throw new Error("The controlled bot went offline");
                  const distance = Math.hypot(
                    info.liveState.x - start.x,
                    info.liveState.z - start.z,
                  );
                  if (distance > 0.05)
                    throw new Error(
                      `Captured bot moved ${distance} blocks with Anti AFK enabled=${enabled}`,
                    );
                }
              }
              const native = await runPromise(serverPosition);
              const distance = Math.hypot(native.x - start.x, native.z - start.z);
              if (distance > 0.05)
                throw new Error(`Vanilla observed the controlled bot move ${distance} blocks`);
              break;
            }
            if (!captured) throw new Error("The POV stream ended without taking control");
          } finally {
            await runPromise(setting("enabled", false));
            if (token) {
              // Reclaim control if the plugin stole it, so cleanup also stops its path.
              await input().catch(() => {});
              await input(false).catch(() => {});
            }
            abort.abort();
          }
        },
        catch: (cause) => new CheckFailed({ message: String(cause) }),
      });
    }).pipe(
      Effect.ensuring(
        Effect.gen(function* () {
          for (const cleanup of [
            setting("enabled", false),
            setting("delay", { min: 15, max: 30 }),
            setting("distance", { min: 10, max: 30 }),
            rcon(`gamemode creative ${BOT_NAME}`),
            rcon("fill 2080 63 0 2110 63 30 air"),
            rcon("forceload remove 2080 0 2111 31"),
          ])
            yield* cleanup.pipe(Effect.ignore);
        }),
      ),
    );
  },
} satisfies E2ETest;
