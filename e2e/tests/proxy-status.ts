import { randomUUID } from "node:crypto";
import net from "node:net";
import { Transform } from "node:stream";
import { create, fromJson } from "@bufbuild/protobuf";
import { ValueSchema } from "@bufbuild/protobuf/wkt";
import { ProxyProtoSchema, ProxyProto_Type } from "@soulfiremc/sdk/generated/soulfire/common_pb";
import { ProxyCheckService } from "@soulfiremc/sdk/generated/soulfire/proxy-check_pb";
import { Effect } from "effect";
import { CheckFailed, check, type E2ETest } from "../harness.ts";

export default {
  name: "proxy-status",
  run: ({ bot, client }) =>
    Effect.gen(function* () {
      const targetPort = Number(process.env.SOULFIRE_E2E_MINECRAFT_PORT ?? 25566);
      const sockets = new Set<net.Socket>();
      let requests = 0;
      let rejectConnection = false;
      const proxy = net.createServer((socket) => {
        sockets.add(socket);
        let data = Buffer.alloc(0);
        const receive = (chunk: Buffer) => {
          data = Buffer.concat([data, chunk]);
          if (data.length > 4096) {
            socket.destroy();
            return;
          }
          const end = data.indexOf("\r\n\r\n");
          if (end < 0) return;
          socket.removeListener("data", receive);
          requests++;
          if (rejectConnection) {
            socket.end("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
            return;
          }
          const upstream = net.connect(targetPort, "127.0.0.1");
          sockets.add(upstream);
          let first = true;
          let timer: ReturnType<typeof setTimeout> | undefined;
          const delayed = new Transform({
            transform(bytes: Buffer, _encoding, done) {
              if (first) {
                first = false;
                timer = setTimeout(() => {
                  timer = undefined;
                  done(null, bytes);
                }, 250);
              } else {
                done(null, bytes);
              }
            },
            destroy(error, done) {
              if (timer) clearTimeout(timer);
              done(error);
            },
          });
          const close = () => {
            sockets.delete(socket);
            sockets.delete(upstream);
            delayed.destroy();
            socket.destroy();
            upstream.destroy();
          };
          upstream.on("error", close);
          upstream.on("close", close);
          delayed.on("error", close);
          socket.on("close", close);
          upstream.once("connect", () => {
            socket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
            if (data.length > end + 4) upstream.write(data.subarray(end + 4));
            socket.pipe(upstream);
            upstream.pipe(delayed).pipe(socket);
          });
        };
        socket.on("data", receive);
        socket.on("error", () => socket.destroy());
        socket.on("close", () => sockets.delete(socket));
      });
      yield* Effect.acquireRelease(
        Effect.tryPromise({
          try: () =>
            new Promise<void>((resolve, reject) => {
              proxy.once("error", reject);
              proxy.listen(0, "127.0.0.1", resolve);
            }),
          catch: (cause) => new CheckFailed({ message: String(cause) }),
        }),
        () =>
          Effect.promise(
            () =>
              new Promise<void>((resolve) => {
                for (const socket of sockets) socket.destroy();
                proxy.close(() => resolve());
              }),
          ),
      );
      const instance = yield* Effect.acquireRelease(
        client.getOrCreateInstance(`Proxy status ${randomUUID().slice(0, 8)}`, {
          server: `127.0.0.1:${targetPort}`,
        }),
        (instance) => instance.delete().pipe(Effect.orDie),
      );
      for (const [key, value] of Object.entries({
        "proxy-check-address": `127.0.0.1:${targetPort}`,
        "proxy-check-timeout": 3,
      })) {
        yield* instance.setConfigEntry({
          namespace: "proxy",
          key,
          value: fromJson(ValueSchema, value),
        });
      }
      const port = (proxy.address() as net.AddressInfo).port;
      for (let attempt = 0; attempt < 4; attempt++) {
        rejectConnection = attempt === 3;
        const valid = yield* Effect.tryPromise({
          try: async () => {
            const results = [];
            for await (const response of client.service(ProxyCheckService).check(
              {
                instanceId: instance.id,
                proxy: [
                  create(ProxyProtoSchema, {
                    type: ProxyProto_Type.HTTP,
                    address: `inet://127.0.0.1:${port}`,
                  }),
                ],
              },
              { timeoutMs: 10000 },
            )) {
              if (response.data.case === "single") results.push(response.data.value.valid);
            }
            return results.length === 1 && results[0] === !rejectConnection;
          },
          catch: (cause) => new CheckFailed({ message: String(cause) }),
        });
        yield* check(
          valid,
          "Proxy checking did not distinguish delayed status replies from failed connections",
        );
      }
      yield* check(requests === 4, "Status checks did not traverse the proxy");
      yield* bot.world.player();
    }).pipe(Effect.scoped),
} satisfies E2ETest;
