import { Code } from "@connectrpc/connect";
import { BotActionStatus } from "@soulfiremc/sdk/generated/soulfire/bot_live_pb";
import {
  AttackEntityCompletionReason,
  BotTaskStatus,
} from "@soulfiremc/sdk/generated/soulfire/task_pb";
import { Effect, Result } from "effect";
import {
  BOT_NAME,
  CheckFailed,
  check,
  type E2ETest,
  eventually,
  placeBot,
  rcon,
} from "../harness.ts";

const TAG = "sf_e2e_combat_targets";

export default {
  name: "combat-targets",
  run: ({ bot }) =>
    Effect.scoped(
      Effect.gen(function* () {
        yield* rcon("forceload add 3280 0 3295 15");
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            yield* rcon(`kill @e[tag=${TAG}]`, { check: false });
            yield* rcon(`gamemode creative ${BOT_NAME}`, { check: false });
            yield* placeBot(bot, { x: 0.5, y: 64, z: 0.5 }).pipe(Effect.ignore);
            yield* rcon("fill 3280 63 0 3295 63 15 air", { check: false });
            yield* rcon("forceload remove 3280 0 3295 15", { check: false });
          }).pipe(Effect.ignore),
        );
        yield* rcon("fill 3280 63 0 3295 63 15 stone");
        yield* placeBot(bot, { x: 3282.5, y: 64, z: 8.5 }, -90);
        yield* rcon(`gamemode survival ${BOT_NAME}`);
        const target = (type: string) =>
          eventually(
            bot.world
              .queryEntities({ radius: 8, selector: { entityTypes: [type], alive: true } })
              .pipe(
                Effect.map((result) => result.entities[0]?.reference),
                Effect.filterOrFail(
                  (reference) => reference !== undefined,
                  () => new CheckFailed({ message: `${type} never became observable` }),
                ),
              ),
            "5 seconds",
          );
        const health = rcon(`data get entity @e[tag=${TAG},type=cow,limit=1] Health`).pipe(
          Effect.map((text) => Number(text.match(/: ([\d.]+)f$/u)?.[1])),
        );
        yield* rcon(`summon cow 3284.5 64 8.5 {Tags:["${TAG}"],NoAI:1b}`);
        const cow = yield* target("minecraft:cow");
        const before = yield* health;
        yield* Effect.sleep("1100 millis");
        yield* bot.attackEntity({ entityId: cow.networkId, connectionEpoch: cow.connectionEpoch });
        yield* eventually(
          health.pipe(
            Effect.flatMap((value) =>
              check(value < before, "Immediate attack did not damage the cow"),
            ),
          ),
          "3 seconds",
        );
        const afterHit = yield* health;
        const task = yield* bot.tasks.attackEntity(cow, {
          maximumAttacks: 1,
          selectBestWeapon: false,
        });
        yield* Effect.addFinalizer(() => task.cancel("test cleanup").pipe(Effect.ignore));
        const result = yield* task.result({ call: { timeoutMs: 10000 } });
        yield* check(
          result.attacks === 1 &&
            result.reason === AttackEntityCompletionReason.ATTACK_LIMIT_REACHED,
          "Attack task did not respect its attack limit",
        );
        yield* eventually(
          health.pipe(
            Effect.flatMap((value) =>
              check(value < afterHit, "Attack task reported a hit without damaging the cow"),
            ),
          ),
          "3 seconds",
        );
        yield* rcon(`kill @e[tag=${TAG}]`);

        yield* rcon(`summon oak_boat 3284.5 64 8.5 {Tags:["${TAG}"]}`);
        const boat = yield* target("minecraft:oak_boat");
        yield* bot.attackEntity({
          entityId: boat.networkId,
          connectionEpoch: boat.connectionEpoch,
        });
        yield* Effect.sleep("300 millis");
        yield* check(
          (yield* rcon("list")).includes(BOT_NAME),
          "Attacking a valid nonliving target disconnected the bot",
        );
        yield* rcon(`kill @e[tag=${TAG}]`);

        for (const fixture of [
          { type: "minecraft:item", nbt: 'Item:{id:"minecraft:stone",count:1},PickupDelay:32767s' },
          { type: "minecraft:experience_orb", nbt: "Value:1,Age:-30000s" },
          { type: "minecraft:arrow", nbt: "" },
        ]) {
          yield* rcon(
            `summon ${fixture.type} 3284.5 65 8.5 {Tags:["${TAG}"],NoGravity:1b${fixture.nbt ? `,${fixture.nbt}` : ""}}`,
          );
          const entity = yield* target(fixture.type);
          const attack = yield* Effect.result(
            bot.attackEntity({
              entityId: entity.networkId,
              connectionEpoch: entity.connectionEpoch,
            }),
          );
          yield* check(
            Result.isFailure(attack) &&
              attack.failure._tag === "SoulFireActionError" &&
              attack.failure.result.status === BotActionStatus.FAILED,
            `Immediate attack accepted ${fixture.type}`,
          );
          const started = yield* Effect.result(
            bot.tasks.attackEntity(entity, { maximumAttacks: 1, selectBestWeapon: false }),
          );
          if (Result.isSuccess(started)) {
            const terminal = yield* started.success.wait({ call: { timeoutMs: 5000 } });
            yield* check(
              terminal.status === BotTaskStatus.FAILED &&
                terminal.failure?.code === "failed_precondition",
              `Attack task accepted ${fixture.type}`,
            );
          } else {
            yield* check(
              started.failure._tag === "SoulFireRpcError" &&
                started.failure.code === Code.FailedPrecondition,
              `Unexpected task rejection for ${fixture.type}`,
            );
          }
          yield* Effect.sleep("300 millis");
          yield* check(
            (yield* rcon("list")).includes(BOT_NAME),
            `Invalid attack disconnected the bot: ${fixture.type}`,
          );
          yield* rcon(`kill @e[tag=${TAG}]`);
        }
      }),
    ),
} satisfies E2ETest;
