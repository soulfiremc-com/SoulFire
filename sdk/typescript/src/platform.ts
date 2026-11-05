import type * as HttpClient from "effect/http/HttpClient";
import * as HttpClientRequest from "effect/http/HttpClientRequest";
import type { HttpMethod } from "effect/http/HttpMethod";
import { Effect, Stream } from "effect";

/**
 * Adapts an Effect Platform HTTP client to the Fetch contract used by
 * ConnectRPC. This keeps Soulfire transports portable across any runtime with
 * an `HttpClient` layer.
 */
export function makeEffectHttpClientFetch(client: HttpClient.HttpClient): typeof globalThis.fetch {
  return async (input, init) => {
    const webRequest = new Request(input, init);
    let request = HttpClientRequest.make(webRequest.method as HttpMethod)(webRequest.url);
    if (webRequest.method !== "GET" && webRequest.method !== "HEAD") {
      request = HttpClientRequest.bodyUint8Array(
        request,
        new Uint8Array(await webRequest.arrayBuffer()),
      );
    }
    request = HttpClientRequest.setHeaders(request, webRequest.headers.entries());
    const httpRequest = request;
    return new Promise<Response>((resolve, reject) => {
      const effectController = new AbortController();
      const abort = () => effectController.abort(webRequest.signal.reason);
      if (webRequest.signal.aborted) {
        abort();
      } else {
        webRequest.signal.addEventListener("abort", abort, { once: true });
      }

      let responseResolved = false;
      let bodyController: ReadableStreamDefaultController<Uint8Array> | undefined;
      const transfer = Effect.gen(function* () {
        const response = yield* client.execute(httpRequest);
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            bodyController = controller;
          },
          cancel(reason) {
            effectController.abort(reason);
          },
        });
        responseResolved = true;
        resolve(
          new Response(body, {
            headers: Object.entries(response.headers),
            status: response.status,
          }),
        );
        yield* Stream.runForEach(response.stream, (chunk) =>
          Effect.sync(() => bodyController?.enqueue(chunk)),
        );
        bodyController?.close();
      });

      void Effect.runPromise(transfer, {
        signal: effectController.signal,
      })
        .catch((cause) => {
          if (!responseResolved) {
            reject(cause);
            return;
          }
          try {
            bodyController?.error(cause);
          } catch {
            // The Fetch consumer already cancelled or closed the body.
          }
        })
        .finally(() => {
          webRequest.signal.removeEventListener("abort", abort);
        });
    });
  };
}
