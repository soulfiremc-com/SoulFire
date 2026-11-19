import { fromJson } from "@bufbuild/protobuf";
import { ValueSchema } from "@bufbuild/protobuf/wkt";
import { PovService } from "@soulfiremc/sdk/generated/soulfire/pov_pb";
import { Effect } from "effect";
import {
  randomUuid,
  BOT_NAME,
  check,
  CheckFailed,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
  serverPosition,
} from "../harness.ts";

export default {
  name: "auto-jump",
  run: ({ bot, client }) => {
    const instance = client.instance(bot.instanceId);
    const enabled = (value: boolean) =>
      instance.setConfigEntry({
        namespace: "auto-jump",
        key: "enabled",
        value: fromJson(ValueSchema, value),
      });
    return Effect.gen(function* () {
      const effectContext = yield* Effect.context();
      const runPromise = Effect.runPromiseWith(effectContext);
      yield* rcon(`gamemode creative ${BOT_NAME}`);
      yield* rcon("forceload add 2016 0");
      yield* rcon("fill 2016 63 0 2020 63 4 stone");
      yield* placeBot(bot, { x: 2018.5, y: 64, z: 2.5 });
      yield* rcon(`gamemode survival ${BOT_NAME}`);
      yield* instance.setConfigEntry({
        namespace: "auto-jump",
        key: "delay",
        value: fromJson(ValueSchema, { min: 0, max: 0 }),
      });
      yield* enabled(true);
      yield* eventually(
        bot
          .info()
          .pipe(
            Effect.flatMap((info) =>
              check((info.liveState?.y ?? 0) > 64.1, "Automatic jumping never started"),
            ),
          ),
        "8 seconds",
        "25 millis",
      );
      let maximumY = 64;
      let landed = false;
      for (let sample = 0; sample < 80; sample++) {
        const info = yield* bot.info();
        maximumY = Math.max(maximumY, info.liveState?.y ?? 64);
        landed ||= Math.abs((info.liveState?.y ?? 0) - 64) < 0.01;
        yield* Effect.sleep("25 millis");
      }
      const native = yield* serverPosition;
      yield* enabled(false);
      yield* check(
        maximumY <= 65.5 && native.y <= 65.5 && landed,
        `Automatic jumping bypassed native jump physics: max Y=${maximumY}, server Y=${native.y}, landed=${landed}`,
      );
      yield* eventually(
        bot
          .info()
          .pipe(
            Effect.flatMap((info) =>
              check(Math.abs((info.liveState?.y ?? 0) - 64) < 0.01, "The bot did not land"),
            ),
          ),
        "3 seconds",
        "25 millis",
      );
      yield* Effect.tryPromise({
        try: async () => {
          const service = client.service(PovService);
          const sessionId = randomUuid();
          const abort = new AbortController();
          let sequence = 0n;
          let token = "";
          let captured = false;
          const input = (value: boolean) =>
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
              { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10_000)]) },
            )) {
              token = frame.inputToken;
              await input(true);
              captured = true;
              await runPromise(enabled(true));
              for (let sample = 0; sample < 60; sample++) {
                if (sample % 10 === 0) await input(true);
                const info = await runPromise(bot.info());
                if (!info.liveState || Math.abs(info.liveState.y - 64) >= 0.01)
                  throw new Error(
                    `Automatic jumping moved a captured bot to Y=${info.liveState?.y}`,
                  );
                await runPromise(Effect.sleep(25));
              }
              const nativePosition = await runPromise(serverPosition);
              if (Math.abs(nativePosition.y - 64) >= 0.01)
                throw new Error(`Vanilla observed a captured bot jumping to Y=${nativePosition.y}`);
              break;
            }
            if (!captured) throw new Error("The POV stream ended without taking control");
          } finally {
            await runPromise(enabled(false));
            if (token) await input(false).catch(() => {});
            abort.abort();
          }
        },
        catch: (cause) => new CheckFailed({ message: String(cause) }),
      });
    }).pipe(
      Effect.ensuring(
        Effect.gen(function* () {
          for (const cleanup of [
            enabled(false),
            instance.setConfigEntry({
              namespace: "auto-jump",
              key: "delay",
              value: fromJson(ValueSchema, { min: 2, max: 5 }),
            }),
            rcon(`gamemode creative ${BOT_NAME}`),
            rcon("fill 2016 63 0 2020 63 4 air"),
            rcon("forceload remove 2016 0"),
          ])
            yield* cleanup.pipe(Effect.ignore);
        }),
      ),
    );
  },
} satisfies E2ETest;
