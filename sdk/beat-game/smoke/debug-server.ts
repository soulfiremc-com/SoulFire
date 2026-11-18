import { randomBytes, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { Cause, Effect, Exit, Option, type Scope } from "effect";

const DEFAULT_MAXIMUM_BODY_BYTES = 1024 * 1024;

export type SmokeDebugMethod = "GET" | "POST";

export interface SmokeDebugOperation {
  readonly method: SmokeDebugMethod;
  readonly path: `/${string}`;
  readonly description: string;
  readonly unsafe?: boolean;
  readonly execute: (input: unknown) => Effect.Effect<unknown, unknown>;
}

export interface SmokeDebugServerOptions {
  readonly operations: readonly SmokeDebugOperation[];
  readonly port: number;
  readonly token?: string;
  readonly maximumBodyBytes?: number;
}

export interface SmokeDebugServer {
  readonly host: "127.0.0.1";
  readonly port: number;
  readonly token: string;
  readonly url: string;
  readonly operations: readonly Readonly<{
    method: SmokeDebugMethod;
    path: `/${string}`;
    description: string;
    unsafe: boolean;
  }>[];
}

export interface SmokeDebugTimelineQuery {
  readonly kinds?: readonly string[];
  readonly limit?: number;
}

export class SmokeDebugTimeline {
  readonly #maximumEntries: number;
  readonly #entries: unknown[] = [];

  public constructor(maximumEntries: number) {
    if (!Number.isSafeInteger(maximumEntries) || maximumEntries < 1) {
      throw new RangeError("maximumEntries must be a positive safe integer");
    }
    this.#maximumEntries = maximumEntries;
  }

  public append(entry: unknown): void {
    this.#entries.push(entry);
    const excess = this.#entries.length - this.#maximumEntries;
    if (excess > 0) {
      this.#entries.splice(0, excess);
    }
  }

  public query(options: SmokeDebugTimelineQuery = {}): readonly unknown[] {
    const requestedLimit = options.limit ?? 250;
    if (!Number.isSafeInteger(requestedLimit) || requestedLimit < 1) {
      throw new RangeError("limit must be a positive safe integer");
    }
    const kinds = options.kinds === undefined ? undefined : new Set(options.kinds);
    const filtered =
      kinds === undefined
        ? this.#entries
        : this.#entries.filter(
            (entry) => isRecord(entry) && typeof entry.kind === "string" && kinds.has(entry.kind),
          );
    return filtered.slice(-Math.min(requestedLimit, this.#maximumEntries));
  }
}

export class SmokeDebugRequestError extends Error {
  public readonly status: number;

  public constructor(message: string, status: number = 400) {
    super(message);
    this.name = "SmokeDebugRequestError";
    this.status = status;
  }
}

export function startSmokeDebugServer(
  options: SmokeDebugServerOptions,
): Effect.Effect<SmokeDebugServer, Error, Scope.Scope> {
  return Effect.gen(function* () {
    validateOptions(options);
    const token = options.token ?? randomBytes(32).toString("base64url");
    const maximumBodyBytes = options.maximumBodyBytes ?? DEFAULT_MAXIMUM_BODY_BYTES;
    const operationIndex = indexOperations(options.operations);
    const server = createServer((request, response) => {
      void handleRequest(request, response, token, operationIndex, maximumBodyBytes);
    });
    const address = yield* Effect.acquireRelease(listen(server, options.port), () => close(server));
    const operations = [...options.operations]
      .toSorted(
        (left, right) =>
          left.path.localeCompare(right.path) || left.method.localeCompare(right.method),
      )
      .map((operation) => ({
        method: operation.method,
        path: operation.path,
        description: operation.description,
        unsafe: operation.unsafe ?? false,
      }));
    return {
      host: "127.0.0.1",
      port: address.port,
      token,
      url: `http://127.0.0.1:${address.port}`,
      operations,
    };
  });
}

function validateOptions(options: SmokeDebugServerOptions): void {
  if (!Number.isSafeInteger(options.port) || options.port < 0 || options.port > 65_535) {
    throw new RangeError("port must be an integer between 0 and 65535");
  }
  if (
    options.maximumBodyBytes !== undefined &&
    (!Number.isSafeInteger(options.maximumBodyBytes) || options.maximumBodyBytes < 1)
  ) {
    throw new RangeError("maximumBodyBytes must be a positive safe integer");
  }
  if (options.token !== undefined && options.token.length < 16) {
    throw new RangeError("token must contain at least 16 characters");
  }
}

function indexOperations(
  operations: readonly SmokeDebugOperation[],
): ReadonlyMap<string, SmokeDebugOperation> {
  const index = new Map<string, SmokeDebugOperation>();
  for (const operation of operations) {
    if (!operation.path.startsWith("/") || operation.path.includes("?")) {
      throw new RangeError(`Invalid debug operation path: ${operation.path}`);
    }
    const key = operationKey(operation.method, operation.path);
    if (index.has(key)) {
      throw new RangeError(`Duplicate debug operation: ${operation.method} ${operation.path}`);
    }
    index.set(key, operation);
  }
  return index;
}

function listen(server: Server, port: number): Effect.Effect<AddressInfo, Error> {
  return Effect.callback<AddressInfo, Error>((resume) => {
    const onError = (cause: Error) => resume(Effect.fail(cause));
    server.once("error", onError);
    server.listen(port, "127.0.0.1", () => {
      server.off("error", onError);
      const address = server.address();
      if (address === null || typeof address === "string") {
        resume(Effect.fail(new Error("Debug server did not expose a TCP address")));
        return;
      }
      resume(Effect.succeed(address));
    });
    return Effect.sync(() => {
      server.off("error", onError);
      server.close();
    });
  });
}

function close(server: Server): Effect.Effect<void> {
  return Effect.callback<void>((resume) => {
    server.close(() => resume(Effect.void));
    server.closeIdleConnections();
    server.closeAllConnections();
  });
}

async function handleRequest(
  request: IncomingMessage,
  response: ServerResponse,
  token: string,
  operations: ReadonlyMap<string, SmokeDebugOperation>,
  maximumBodyBytes: number,
): Promise<void> {
  try {
    if (!authorized(request, token)) {
      response.setHeader("www-authenticate", "Bearer");
      writeJson(response, 401, {
        ok: false,
        error: { message: "A valid bearer token is required" },
      });
      return;
    }
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (request.method === "GET" && url.pathname === "/") {
      writeJson(response, 200, {
        ok: true,
        result: {
          operations: [...operations.values()]
            .toSorted(
              (left, right) =>
                left.path.localeCompare(right.path) || left.method.localeCompare(right.method),
            )
            .map((operation) => ({
              method: operation.method,
              path: operation.path,
              description: operation.description,
              unsafe: operation.unsafe ?? false,
            })),
        },
      });
      return;
    }
    const pathOperations = [...operations.values()].filter(
      (operation) => operation.path === url.pathname,
    );
    if (pathOperations.length === 0) {
      writeJson(response, 404, {
        ok: false,
        error: { message: `Unknown debug operation: ${url.pathname}` },
      });
      return;
    }
    const method =
      request.method === "GET" || request.method === "POST" ? request.method : undefined;
    const operation =
      method === undefined ? undefined : operations.get(operationKey(method, url.pathname));
    if (operation === undefined) {
      response.setHeader("allow", pathOperations.map(({ method: allowed }) => allowed).join(", "));
      writeJson(response, 405, {
        ok: false,
        error: { message: `Method ${request.method ?? "UNKNOWN"} is not allowed` },
      });
      return;
    }
    const input =
      operation.method === "POST"
        ? await readJson(request, maximumBodyBytes)
        : Object.fromEntries(url.searchParams);
    const exit = await Effect.runPromiseExit(operation.execute(input));
    if (Exit.isSuccess(exit)) {
      writeJson(response, 200, { ok: true, result: exit.value });
      return;
    }
    const failure = Option.getOrUndefined(Cause.findErrorOption(exit.cause));
    const status = failure instanceof SmokeDebugRequestError ? failure.status : 500;
    writeJson(response, status, {
      ok: false,
      error: {
        message: failure instanceof Error ? failure.message : Cause.pretty(exit.cause),
        ...(failure instanceof Error ? { name: failure.name } : {}),
      },
    });
  } catch (cause) {
    const error = cause instanceof Error ? cause : new Error(String(cause));
    const status = error instanceof SmokeDebugRequestError ? error.status : 500;
    writeJson(response, status, {
      ok: false,
      error: { name: error.name, message: error.message },
    });
  }
}

function authorized(request: IncomingMessage, token: string): boolean {
  const authorization = request.headers.authorization;
  if (authorization === undefined || !authorization.startsWith("Bearer ")) {
    return false;
  }
  const supplied = Buffer.from(authorization.slice("Bearer ".length));
  const expected = Buffer.from(token);
  return supplied.byteLength === expected.byteLength && timingSafeEqual(supplied, expected);
}

async function readJson(request: IncomingMessage, maximumBodyBytes: number): Promise<unknown> {
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    bytes += buffer.byteLength;
    if (bytes > maximumBodyBytes) {
      throw new SmokeDebugRequestError(`Request body exceeds ${maximumBodyBytes} bytes`, 413);
    }
    chunks.push(buffer);
  }
  if (bytes === 0) {
    return {};
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
  } catch {
    throw new SmokeDebugRequestError("Request body must be valid JSON", 400);
  }
}

function writeJson(response: ServerResponse, status: number, value: unknown): void {
  if (response.writableEnded) {
    return;
  }
  const body = `${JSON.stringify(value, (_, nested) =>
    typeof nested === "bigint" ? nested.toString() : nested,
  )}\n`;
  response.writeHead(status, {
    "cache-control": "no-store",
    "content-length": Buffer.byteLength(body),
    "content-type": "application/json; charset=utf-8",
  });
  response.end(body);
}

function operationKey(method: string, path: string): string {
  return `${method} ${path}`;
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
