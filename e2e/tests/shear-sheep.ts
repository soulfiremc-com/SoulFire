// Shears used on a sheep drop one to three wool and leave the sheep alive,
// where killing it drops one. The beat-game bot shears sheep for its beds.
import { Hand } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import { Effect } from "effect";
import { BOT_NAME, check, type E2ETest, placeBot, rcon } from "../harness.ts";

export default {
  name: "shear-sheep",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 4416 0 4447 31");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon("kill @e[type=minecraft:sheep,tag=shear_probe]", { check: false });
            yield* rcon("kill @e[type=minecraft:item,x=4416,y=60,z=0,dx=32,dy=20,dz=32]", {
              check: false,
            });
            yield* rcon(`clear ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 4416 63 0 4447 70 31 air", { check: false });
            yield* rcon("forceload remove 4416 0 4447 31", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 4416 63 0 4447 70 31 air");
        yield* rcon("fill 4416 63 0 4447 63 31 stone");
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        yield* placeBot(bot, { x: 4432.5, y: 64, z: 16.5 });
        yield* rcon(`clear ${BOT_NAME}`);
        yield* rcon(`item replace entity ${BOT_NAME} hotbar.0 with shears`);
        yield* bot.selectHotbar(0);
        yield* rcon(
          'summon minecraft:sheep 4434.5 64 16.5 {Tags:["shear_probe"],NoAI:1b,Color:0b}',
        );
        yield* Effect.sleep("1 second");
        const sheep = (yield* bot.world.queryEntities({
          radius: 8,
          selector: { entityTypes: ["minecraft:sheep"], alive: true },
        })).entities[0];
        yield* check(sheep?.reference !== undefined, "The bot does not see the sheep");
        yield* bot.look(-90, 20);
        yield* bot.interactEntity({
          connectionEpoch: sheep?.reference?.connectionEpoch ?? "",
          entityId: sheep?.reference?.networkId ?? 0,
          hand: Hand.MAIN,
          sneaking: false,
        });
        yield* Effect.sleep("1 second");
        const sheared = yield* rcon(
          "execute if entity @e[type=minecraft:sheep,tag=shear_probe,nbt={Sheared:1b}]",
        );
        yield* check(sheared.includes("passed"), `The sheep was not sheared: ${sheared}`);
        const wool = yield* rcon(
          'execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:white_wool"}},x=4416,y=60,z=0,dx=32,dy=20,dz=32]',
        );
        yield* check(wool.includes("passed"), `No wool dropped: ${wool}`);
      }),
    ),
} satisfies E2ETest;
