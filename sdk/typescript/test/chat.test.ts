import { create } from "@bufbuild/protobuf";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect, Stream } from "effect";
import { describe, expect, it } from "vitest";

import { SoulFireChat, matchChat } from "../src/chat.js";
import {
  BotChatEventSchema,
  BotEventSchema,
  ChatSource,
} from "../src/generated/soulfire/bot_live_pb.js";
import { ChatService } from "../src/generated/soulfire/chat_pb.js";

describe("SoulFireChat", () => {
  it("matches regular-expression captures without retaining RegExp state", () => {
    const event = create(BotChatEventSchema, {
      plainText: "Alex joined with code 4821",
      source: ChatSource.SYSTEM,
    });
    const matcher = /(?<player>\w+) joined with code (\d+)/gu;

    expect(matchChat(event, matcher)).toMatchObject({
      captures: ["Alex", "4821"],
      groups: { player: "Alex" },
    });
    expect(matchChat(event, matcher)).toBeDefined();
  });

  it("waits for the first matching chat source", () =>
    Effect.runPromise(
      Effect.scoped(
        Effect.gen(function* () {
          const client = createClient(
            ChatService,
            createRouterTransport(({ service }) => {
              service(ChatService, {});
            }),
          );
          const chat = new SoulFireChat(
            "instance-id",
            "bot-id",
            client,
            (options) => options,
            () =>
              Stream.make(
                create(BotEventSchema, {
                  event: {
                    case: "chat",
                    value: {
                      plainText: "authentication accepted",
                      source: ChatSource.PLAYER,
                    },
                  },
                }),
                create(BotEventSchema, {
                  event: {
                    case: "chat",
                    value: {
                      plainText: "authentication accepted",
                      source: ChatSource.SYSTEM,
                    },
                  },
                }),
              ),
          );
          const match = yield* chat.waitFor("authentication accepted", {
            sources: [ChatSource.SYSTEM],
            timeoutMs: 100,
          });
          expect(match.event.source).toBe(ChatSource.SYSTEM);
        }),
      ),
    ));
});
