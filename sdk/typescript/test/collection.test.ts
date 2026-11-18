import { create } from "@bufbuild/protobuf";
import { anyPack, anyUnpack } from "@bufbuild/protobuf/wkt";
import { createClient, createRouterTransport } from "@connectrpc/connect";
import { Effect, Exit } from "effect";
import { expect, it } from "vitest";

import { SoulFireBot } from "../src/client.js";
import { BotLiveService } from "../src/generated/soulfire/bot_live_pb.js";
import { BotService } from "../src/generated/soulfire/bot_pb.js";
import {
  BotTaskService,
  BotTaskStatus,
  CollectBlocksTaskResultSchema,
  CollectBlocksTaskSchema,
} from "../src/generated/soulfire/task_pb.js";

it.each([true, false])(
  "foreground collection cleans up unfinished work (complete=%s)",
  async (complete) => {
    let cancellations = 0;
    const transport = createRouterTransport(({ service }) => {
      service(BotTaskService, {
        startBotTask(request) {
          const input = anyUnpack(request.input!, CollectBlocksTaskSchema)!;
          expect(input.tags).toEqual(["minecraft:logs"]);
          expect(input.count).toBe(8);
          return { taskId: "collection", status: BotTaskStatus.RUNNING };
        },
        async *watchBotTask(_, context) {
          if (complete) {
            yield {
              task: {
                taskId: "collection",
                status: BotTaskStatus.COMPLETED,
                result: anyPack(
                  CollectBlocksTaskResultSchema,
                  create(CollectBlocksTaskResultSchema, { blocksBroken: 8 }),
                ),
              },
            };
          } else {
            await new Promise<void>((resolve) => {
              if (context.signal.aborted) resolve();
              else context.signal.addEventListener("abort", () => resolve(), { once: true });
            });
          }
        },
        cancelBotTask() {
          cancellations += 1;
          return { taskId: "collection", status: BotTaskStatus.CANCELLED };
        },
      });
    });
    const bot = new SoulFireBot(
      "instance",
      "bot",
      createClient(BotService, transport),
      createClient(BotLiveService, transport),
      createClient(BotTaskService, transport),
    );
    const exit = await Effect.runPromiseExit(
      bot.collect("#logs", { count: 8 }).pipe(Effect.timeout("100 millis")),
    );
    expect(Exit.isSuccess(exit)).toBe(complete);
    expect(Exit.isSuccess(exit) ? exit.value.blocksBroken : undefined).toBe(
      complete ? 8 : undefined,
    );
    expect(cancellations).toBe(complete ? 0 : 1);
  },
);
