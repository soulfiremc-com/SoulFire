import { BotTaskStatus } from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, placeBot, rcon } from "../harness.ts";

export default {
  name: "processing-station-fuel",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3920 0 3935 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3920 63 0 3935 66 15 air", { check: false });
            yield* rcon("forceload remove 3920 0 3935 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3920 63 0 3935 63 15 stone");
        for (const kind of ["smelt", "brew"]) {
          const brewing = kind === "brew";
          const x = brewing ? 3930 : 3927;
          const station = { x, y: 64, z: 8 };
          for (const mode of brewing
            ? ["closed", "open", "missing", "occupied"]
            : ["closed", "open", "residual", "missing", "occupied"]) {
            yield* bot.closeContainer().pipe(Effect.ignore);
            yield* rcon(`gamemode creative ${BOT_NAME}`);
            yield* placeBot(bot, { x: 3928.5, y: 64, z: 8.5 }, -90);
            if (mode !== "residual") {
              yield* rcon(`setblock ${x} 64 8 air`, { check: false });
              yield* rcon(`setblock ${x} 64 8 ${brewing ? "brewing_stand" : "furnace"}`);
            }
            if (mode === "closed" || mode === "open") {
              yield* rcon(
                `data merge block ${x} 64 8 {Items:[{Slot:${brewing ? 4 : 1}b,id:"minecraft:${brewing ? "blaze_powder" : "coal"}",count:1}]}`,
              );
              yield* Effect.sleep("300 millis");
            }
            if (mode === "residual") {
              const burn = yield* rcon(`data get block ${x} 64 8 lit_time_remaining`);
              yield* check(
                Number(burn.match(/: (\d+)$/u)?.[1]) > 200,
                "The previous smelt left insufficient burn time for the fixture",
              );
            }
            if (mode === "occupied") {
              yield* rcon(
                brewing
                  ? `data merge block ${x} 64 8 {Items:[{Slot:0b,id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:water"}}}]}`
                  : `data merge block ${x} 64 8 {Items:[{Slot:2b,id:"minecraft:iron_ingot",count:2}]}`,
              );
              yield* rcon(`give ${BOT_NAME} minecraft:${brewing ? "blaze_powder" : "coal"}`);
            }
            for (const item of brewing
              ? ['potion[minecraft:potion_contents={potion:"minecraft:water"}]', "nether_wart"]
              : ["iron_ore"]) {
              yield* rcon(`give ${BOT_NAME} minecraft:${item}`);
            }
            if (mode !== "closed") yield* bot.inventory.open(station);
            const before = yield* rcon(`data get block ${x} 64 8 Items`);
            const inventoryBefore = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
            const task = yield* brewing
              ? bot.tasks.brew(
                  { itemIds: ["minecraft:potion"] },
                  { itemIds: ["minecraft:nether_wart"] },
                  1,
                  { station },
                )
              : bot.tasks.smelt({ itemIds: ["minecraft:iron_ore"] }, 1, {
                  station,
                  fuel: { itemIds: ["minecraft:coal"] },
                });
            yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
            const status = yield* task.wait({ call: { timeoutMs: 30_000 } });
            if (mode === "missing" || mode === "occupied") {
              yield* check(
                status.status === BotTaskStatus.FAILED &&
                  status.failure?.code === "failed_precondition",
                `The ${kind} task accepted a ${mode} station`,
              );
              yield* check(
                (yield* rcon(`data get block ${x} 64 8 Items`)) === before,
                `The rejected ${kind} task changed station contents`,
              );
              yield* check(
                (yield* rcon(`data get entity ${BOT_NAME} Inventory`)) === inventoryBefore,
                `The rejected ${kind} task moved inventory items`,
              );
            } else {
              yield* task.result();
              const inventory = yield* rcon(`data get entity ${BOT_NAME} Inventory`);
              yield* check(
                inventory.includes(brewing ? "minecraft:awkward" : "minecraft:iron_ingot"),
                `${kind}/${mode} did not produce native output using station fuel`,
              );
              yield* check(
                (yield* rcon(`data get block ${x} 64 8 Items`)).endsWith(": []"),
                `${kind}/${mode} left items in the station`,
              );
            }
          }
        }
      }),
    ),
} satisfies E2ETest;
