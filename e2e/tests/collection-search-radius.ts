import { Console, Effect } from "effect";
import { CollectBlocksCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "collection-search-radius",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const forced: number[] = [];
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.start();
            yield* eventually(bot.world.player(), "30 seconds", "100 millis");
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 });
            yield* bot.selectHotbar(0);
            yield* rcon("fill 4260 63 4 4278 66 12 air");
            yield* rcon("kill @e[type=item,x=4260,y=-200,z=4,dx=18,dy=300,dz=8]", { check: false });
            for (const x of forced) yield* rcon(`forceload remove ${x} 0`);
          }).pipe(Effect.orDie),
        );
        for (const x of [4256, 4272]) {
          if ((yield* rcon(`forceload query ${x} 0`, { check: false })).includes("not marked")) {
            yield* rcon(`forceload add ${x} 0`);
            forced.push(x);
          }
        }
        yield* rcon(`gamemode creative ${BOT_NAME}`);
        const xp = yield* rcon(`data get entity ${BOT_NAME} XpTotal`);
        const score = yield* rcon(`data get entity ${BOT_NAME} Score`);
        const cases = [
          { name: "boundary", radius: 1, offset: { x: 1, y: 0, z: 0 }, collect: true },
          { name: "outside-horizontal", radius: 1, offset: { x: 3, y: 0, z: 0 }, collect: false },
          { name: "outside-diagonal", radius: 1, offset: { x: 1, y: 0, z: 1 }, collect: false },
          { name: "outside-vertical", radius: 1, offset: { x: 0, y: 2, z: 0 }, collect: false },
          { name: "inside-diagonal", radius: 2, offset: { x: 1, y: 0, z: 1 }, collect: true },
          { name: "vertical-boundary", radius: 2, offset: { x: 0, y: 2, z: 0 }, collect: true },
        ];
        const outcomes: boolean[] = [];
        for (const entry of cases) {
          yield* Effect.scoped(
            Effect.gen(function* () {
              yield* rcon("fill 4260 64 4 4278 66 12 air");
              yield* rcon("fill 4260 63 4 4278 63 12 stone");
              yield* placeBot(bot, { x: 4262.5, y: 64, z: 8.5 }, -90);
              yield* bot.selectHotbar(0);
              const position = {
                x: 4262 + entry.offset.x,
                y: 64 + entry.offset.y,
                z: 8 + entry.offset.z,
              };
              yield* rcon(`setblock ${position.x} ${position.y} ${position.z} dirt`);
              yield* eventually(
                bot.world
                  .block({ position })
                  .pipe(
                    Effect.tap(({ block }) =>
                      check(block?.blockId === "minecraft:dirt", "Target not observed"),
                    ),
                  ),
                "10 seconds",
                "100 millis",
              );
              const observed = yield* bot.world.queryBlocks({
                region: {
                  region: {
                    case: "box",
                    value: {
                      minimum: { x: 4257, y: 60, z: 3 },
                      maximum: { x: 4267, y: 70, z: 13 },
                    },
                  },
                },
                selector: { blockIds: ["minecraft:dirt"] },
                pageSize: 10,
              });
              yield* check(
                observed.blocks.length === 1 &&
                  !observed.nextPageToken &&
                  observed.blocks[0]?.position?.x === position.x &&
                  observed.blocks[0]?.position?.y === position.y &&
                  observed.blocks[0]?.position?.z === position.z,
                "Unexpected dirt in the nearby mining range",
              );
              const task = yield* bot.tasks.collectBlocks("minecraft:dirt", {
                count: 1,
                searchRadius: entry.radius,
              });
              yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
              const result = yield* task.result({ call: { timeoutMs: 10_000 } });
              const expectedBlock = entry.collect ? "air" : "dirt";
              const physical = yield* rcon(
                `execute if block ${position.x} ${position.y} ${position.z} ${expectedBlock}`,
                { check: false },
              );
              const air = yield* rcon(
                `execute if block ${position.x} ${position.y} ${position.z} air`,
                { check: false },
              );
              const passed =
                result.blocksBroken === (entry.collect ? 1 : 0) &&
                result.reason ===
                  (entry.collect
                    ? CollectBlocksCompletionReason.TARGET_REACHED
                    : CollectBlocksCompletionReason.NO_MATCHING_BLOCKS) &&
                physical === "Test passed";
              outcomes.push(passed);
              yield* Console.log(
                JSON.stringify({
                  name: entry.name,
                  radius: entry.radius,
                  position,
                  result,
                  physical,
                  air,
                  passed,
                }),
              );
            }),
          );
        }
        yield* check(
          (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xp &&
            (yield* rcon(`data get entity ${BOT_NAME} Score`)) === score,
          "The radius fixture changed XP or score",
        );
        yield* check(outcomes.every(Boolean), "Nearby collection exceeded its search radius");
      }),
    ),
} satisfies E2ETest;
