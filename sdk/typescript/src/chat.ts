import type { MessageInitShape } from "@bufbuild/protobuf";
import type { CallOptions, Client } from "@connectrpc/connect";
import { Effect, Filter, Option, Stream } from "effect";
import { operationError, rpcError, type SoulFireOperationError } from "./errors.js";
import { rpc, withSignal } from "./transport.js";

import { requireCompletedAction } from "./actions.js";
import {
  type BotEventFilterSchema,
  type BotActionResult,
  type BotChatEvent,
  type BotEvent,
  type ChatSource,
} from "./generated/soulfire/bot_live_pb.js";
import type { ChatService, TabCompleteResponse } from "./generated/soulfire/chat_pb.js";

export interface ChatMutationOptions {
  call?: CallOptions;
  idempotencyKey?: string;
}

export interface TabCompleteOptions {
  call?: CallOptions;
  cursor?: number;
}

export type ChatMatcher = string | RegExp | ((event: BotChatEvent) => boolean);

export interface ChatMatch {
  readonly captures: readonly string[];
  readonly event: BotChatEvent;
  readonly groups: Readonly<Record<string, string>>;
}

export interface WatchChatOptions {
  readonly call?: CallOptions;
  readonly sources?: readonly ChatSource[];
}

export interface WaitForChatOptions extends WatchChatOptions {
  readonly timeoutMs?: number;
}

type BotEventStream = (
  filter: MessageInitShape<typeof BotEventFilterSchema>,
  options?: CallOptions,
) => Stream.Stream<BotEvent, SoulFireOperationError>;

export class SoulFireChat {
  public constructor(
    private readonly instanceId: string,
    private readonly botId: string,
    private readonly client: Client<typeof ChatService>,
    private readonly actionOptions: (options?: CallOptions) => CallOptions | undefined,
    private readonly eventStream?: BotEventStream | undefined,
  ) {}

  /**
   * To public chat. `message` can't be blank or longer than 256 characters.
   */
  public send(
    message: string,
    options: ChatMutationOptions = {},
  ): Effect.Effect<BotActionResult, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireChat.send", (signal) =>
        this.client.sendPublicChat(
          {
            scope: this.scope(),
            message,
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      return yield* Effect.try({
        try: () => requireCompletedAction(response.result),
        catch: (cause) => operationError("SoulFireChat.send", cause),
      });
    });
  }

  /**
   * Runs a command; the leading `/` is optional.
   */
  public command(
    command: string,
    options: ChatMutationOptions = {},
  ): Effect.Effect<BotActionResult, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireChat.command", (signal) =>
        this.client.sendCommand(
          {
            scope: this.scope(),
            command,
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      return yield* Effect.try({
        try: () => requireCompletedAction(response.result),
        catch: (cause) => operationError("SoulFireChat.command", cause),
      });
    });
  }

  /**
   * Sends `/msg <recipient> <message>`.
   */
  public whisper(
    recipient: string,
    message: string,
    options: ChatMutationOptions = {},
  ): Effect.Effect<BotActionResult, SoulFireOperationError> {
    return Effect.gen({ self: this }, function* () {
      const response = yield* rpc("SoulFireChat.whisper", (signal) =>
        this.client.sendWhisper(
          {
            scope: this.scope(),
            recipient,
            message,
            ...(options.idempotencyKey === undefined
              ? {}
              : { idempotencyKey: options.idempotencyKey }),
          },
          withSignal(this.actionOptions(options.call), signal),
        ),
      );
      return yield* Effect.try({
        try: () => requireCompletedAction(response.result),
        catch: (cause) => operationError("SoulFireChat.whisper", cause),
      });
    });
  }

  /**
   * Tab-completion suggestions for `input` (a command if it starts with `/`) at
   * `options.cursor`, which defaults to the end of `input`.
   */
  public complete(
    input: string,
    options: TabCompleteOptions = {},
  ): Effect.Effect<TabCompleteResponse, SoulFireOperationError> {
    return rpc("SoulFireChat.complete", (signal) =>
      this.client.tabComplete(
        {
          scope: this.scope(),
          input,
          ...(options.cursor === undefined ? {} : { cursor: options.cursor }),
        },
        withSignal(options.call, signal),
      ),
    );
  }

  /**
   * Chat events matching `matcher`, as they arrive, until the caller stops
   * iterating. A string matches as a substring of the plain text; a RegExp's
   * groups go in `captures` and `groups`. `options.sources` keeps only those
   * sources.
   */
  public watch(
    matcher: ChatMatcher,
    options: WatchChatOptions = {},
  ): Stream.Stream<ChatMatch, SoulFireOperationError> {
    if (this.eventStream === undefined)
      return Stream.fail(rpcError("chat.watch", new Error("The bot event stream is unavailable")));
    const sources = options.sources === undefined ? undefined : new Set(options.sources);
    return this.eventStream({ includeChat: true }, options.call).pipe(
      Stream.filterMap(
        Filter.fromPredicateOption((envelope) => {
          if (
            envelope.event.case !== "chat" ||
            (sources !== undefined && !sources.has(envelope.event.value.source))
          )
            return Option.none();
          return Option.fromNullishOr(matchChat(envelope.event.value, matcher));
        }),
      ),
    );
  }

  /**
   * The next chat event matching `matcher` (as in `watch`). No timeout unless
   * `timeoutMs` is set.
   */
  public waitFor(
    matcher: ChatMatcher,
    options: WaitForChatOptions = {},
  ): Effect.Effect<ChatMatch, SoulFireOperationError> {
    const next = this.watch(matcher, options).pipe(
      Stream.runHead,
      Effect.flatMap(
        Effect.fromOption(() =>
          rpcError("chat.waitFor", new Error("The bot event stream ended before chat matched")),
        ),
      ),
    );
    return options.timeoutMs === undefined
      ? next
      : next.pipe(
          Effect.timeoutOrElse({
            duration: options.timeoutMs,
            orElse: () =>
              Effect.fail(
                rpcError(
                  "chat.waitFor",
                  new Error(`Timed out after ${options.timeoutMs} ms waiting for chat`),
                ),
              ),
          }),
        );
  }

  private scope(): { instanceId: string; botId: string } {
    return { instanceId: this.instanceId, botId: this.botId };
  }
}

export function matchChat(event: BotChatEvent, matcher: ChatMatcher): ChatMatch | undefined {
  if (typeof matcher === "string") {
    return event.plainText.includes(matcher) ? { captures: [], event, groups: {} } : undefined;
  }
  if (typeof matcher === "function") {
    return matcher(event) ? { captures: [], event, groups: {} } : undefined;
  }

  matcher.lastIndex = 0;
  const result = matcher.exec(event.plainText);
  if (result === null) {
    return undefined;
  }
  return {
    // Groups that did not take part in the match are undefined at runtime.
    captures: result.slice(1).map((value: string | undefined) => value ?? ""),
    event,
    groups: { ...result.groups },
  };
}
