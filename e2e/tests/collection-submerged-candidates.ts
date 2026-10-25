import { Console, Effect } from "effect";
import { CollectBlocksCompletionReason } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { BOT_NAME, check, type E2ETest, eventually, placeBot, rcon } from "../harness.ts";

export default {
  name: "collection-submerged-candidates",
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
            // Drain the contained sources before removing their walls and base.
            yield* rcon("fill 4261 65 6 4276 65 9 air");
            yield* rcon("fill 4260 65 4 4278 65 12 air");
            yield* rcon("fill 4260 63 4 4278 64 12 air");
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
        yield* rcon("fill 4260 64 4 4278 65 12 air");
        yield* rcon("fill 4260 63 4 4278 63 12 stone");
        yield* placeBot(bot, { x: 4260.5, y: 64, z: 4.5 }, -90);
        yield* bot.selectHotbar(0);
        const xp = yield* rcon(`data get entity ${BOT_NAME} XpTotal`);
        const score = yield* rcon(`data get entity ${BOT_NAME} Score`);
        yield* rcon("fill 4261 64 6 4276 64 9 dirt");
        yield* rcon("fill 4260 65 5 4277 65 10 stone outline");
        yield* rcon("fill 4261 65 6 4276 65 9 water[level=0]");
        yield* rcon("setblock 4278 64 4 dirt");
        const region = {
          region: {
            case: "box" as const,
            value: {
              minimum: { x: 4240, y: 64, z: -16 },
              maximum: { x: 4280, y: 64, z: 24 },
            },
          },
        };
        yield* eventually(
          bot.world
            .queryBlocks({ region, selector: { blockIds: ["minecraft:dirt"] }, pageSize: 100 })
            .pipe(
              Effect.tap((value) =>
                check(
                  value.blocks.length === 65 &&
                    !value.nextPageToken &&
                    value.blocks.every(
                      ({ position }) =>
                        position &&
                        ((position.x === 4278 && position.y === 64 && position.z === 4) ||
                          (position.x >= 4261 &&
                            position.x <= 4276 &&
                            position.y === 64 &&
                            position.z >= 6 &&
                            position.z <= 9)),
                    ),
                  "The 65 candidate blocks have not arrived",
                ),
              ),
            ),
          "10 seconds",
          "100 millis",
        );
        yield* Effect.sleep("500 millis");
        const observeWater = bot.world.queryBlocks({
          region: {
            region: {
              case: "box",
              value: {
                minimum: { x: 4259, y: 63, z: 3 },
                maximum: { x: 4279, y: 66, z: 13 },
              },
            },
          },
          selector: { blockIds: ["minecraft:water"] },
          pageSize: 100,
        });
        const water = yield* observeWater;
        yield* check(
          water.blocks.length === 64 &&
            water.blocks.every(
              ({ position }) =>
                position &&
                position.y === 65 &&
                position.x >= 4261 &&
                position.x <= 4276 &&
                position.z >= 6 &&
                position.z <= 9,
            ),
          "The water is not confined above the owned pool",
        );
        const task = yield* bot.tasks.collectBlocks("minecraft:dirt", {
          count: 1,
          searchRadius: 20,
          avoidSubmergedTargets: true,
          targetYRange: { minimum: 64, maximum: 64 },
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        const result = yield* task.result({ call: { timeoutMs: 30_000 } });
        const dry = yield* rcon("execute if block 4278 64 4 air", { check: false });
        const remaining = yield* bot.world.queryBlocks({
          region,
          selector: { blockIds: ["minecraft:dirt"] },
          pageSize: 100,
        });
        const waterAfter = yield* observeWater;
        yield* check(
          waterAfter.blocks.length === 64 &&
            waterAfter.blocks.every(
              ({ position }) =>
                position &&
                position.y === 65 &&
                position.x >= 4261 &&
                position.x <= 4276 &&
                position.z >= 6 &&
                position.z <= 9,
            ),
          "Collection disturbed the contained water",
        );
        yield* Console.log(
          JSON.stringify({
            result,
            dry,
            remaining: remaining.blocks.length,
            water: waterAfter.blocks.length,
          }),
        );
        yield* check(
          result.reason === CollectBlocksCompletionReason.TARGET_REACHED &&
            result.blocksBroken === 1 &&
            dry === "Test passed" &&
            remaining.blocks.length === 64 &&
            remaining.blocks.every(
              ({ position }) =>
                position &&
                position.x >= 4261 &&
                position.x <= 4276 &&
                position.z >= 6 &&
                position.z <= 9,
            ),
          "Submerged candidates hid the eligible dry block",
        );
        yield* check(
          (yield* rcon(`data get entity ${BOT_NAME} XpTotal`)) === xp &&
            (yield* rcon(`data get entity ${BOT_NAME} Score`)) === score,
          "The collection fixture changed XP or score",
        );
      }),
    ),
} satisfies E2ETest;
