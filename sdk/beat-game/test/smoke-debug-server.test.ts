import { Effect } from "effect";
import { describe, expect, it } from "vitest";

import {
  SmokeDebugRequestError,
  SmokeDebugTimeline,
  startSmokeDebugServer,
} from "../smoke/debug-server.js";

const token = "test-token-with-enough-entropy";

describe("smoke debug server", () => {
  it("authenticates probes and preserves bigint values", async () => {
    await Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const server = yield* startSmokeDebugServer({
            port: 0,
            token,
            operations: [
              {
                method: "GET",
                path: "/state",
                description: "Read state",
                execute: () => Effect.succeed({ revision: 42n }),
              },
            ],
          });

          const unauthorized = yield* Effect.promise(() => fetch(`${server.url}/state`));
          expect(unauthorized.status).toBe(401);

          const response = yield* Effect.promise(() =>
            fetch(`${server.url}/state`, { headers: authorization() }),
          );
          expect(response.status).toBe(200);
          const body: unknown = yield* Effect.promise(() => response.json());
          expect(body).toEqual({
            ok: true,
            result: { revision: "42" },
          });
        }),
      ),
    );
  });

  it("routes JSON input and reports typed request failures", async () => {
    await Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const server = yield* startSmokeDebugServer({
            port: 0,
            token,
            operations: [
              {
                method: "POST",
                path: "/blocks/query",
                description: "Query blocks",
                execute: (input) =>
                  typeof input === "object" && input !== null && "radius" in input
                    ? Effect.succeed(input)
                    : Effect.fail(new SmokeDebugRequestError("radius is required")),
              },
            ],
          });

          const accepted = yield* Effect.promise(() =>
            fetch(`${server.url}/blocks/query`, {
              method: "POST",
              headers: {
                ...authorization(),
                "content-type": "application/json",
              },
              body: JSON.stringify({ radius: 8 }),
            }),
          );
          const acceptedBody: unknown = yield* Effect.promise(() => accepted.json());
          expect(acceptedBody).toEqual({
            ok: true,
            result: { radius: 8 },
          });

          const rejected = yield* Effect.promise(() =>
            fetch(`${server.url}/blocks/query`, {
              method: "POST",
              headers: {
                ...authorization(),
                "content-type": "application/json",
              },
              body: "{}",
            }),
          );
          expect(rejected.status).toBe(400);
          const rejectedBody: unknown = yield* Effect.promise(() => rejected.json());
          expect(rejectedBody).toMatchObject({
            ok: false,
            error: { message: "radius is required" },
          });
        }),
      ),
    );
  });

  it("keeps a bounded and filterable diagnostic timeline", () => {
    const timeline = new SmokeDebugTimeline(3);
    timeline.append({ kind: "pathfind-started", id: 1 });
    timeline.append({ kind: "primitive-started", id: 2 });
    timeline.append({ kind: "pathfind-failed", id: 3 });
    timeline.append({ kind: "pathfind-started", id: 4 });

    expect(timeline.query()).toEqual([
      { kind: "primitive-started", id: 2 },
      { kind: "pathfind-failed", id: 3 },
      { kind: "pathfind-started", id: 4 },
    ]);
    expect(
      timeline.query({
        kinds: ["pathfind-started", "pathfind-failed"],
        limit: 2,
      }),
    ).toEqual([
      { kind: "pathfind-failed", id: 3 },
      { kind: "pathfind-started", id: 4 },
    ]);
  });
});

function authorization(): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}
