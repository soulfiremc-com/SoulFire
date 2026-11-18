import { BotRuntimeState } from "@soulfiremc/sdk/generated/soulfire/bot_pb";
import { Effect } from "effect";
import { check, type E2ETest, eventually, rcon, required } from "../harness.ts";

const NAMES = ["FleetE2EA", "FleetE2EB", "FleetE2EC"];

export default {
  name: "fleet-lifecycle",
  run: ({ client }) =>
    Effect.scoped(
      Effect.gen(function* () {
        const instance = yield* Effect.acquireRelease(
          client.getOrCreateInstance("e2e-fleet", {
            server: `127.0.0.1:${process.env["SOULFIRE_E2E_MINECRAFT_PORT"] ?? 25566}`,
          }),
          (owned) => owned.delete().pipe(Effect.ignore),
        );
        for (const namespace of [
          "auto-eat",
          "auto-armor",
          "auto-totem",
          "auto-jump",
          "anti-afk",
          "auto-respawn",
          "auto-reconnect",
        ]) {
          yield* instance.setConfigEntry({
            namespace,
            key: "enabled",
            value: { kind: { case: "boolValue", value: false } },
          });
        }
        const bots = yield* Effect.forEach(NAMES, (name) =>
          instance.getOrCreateBot(name, { start: false }),
        );
        yield* Effect.addFinalizer(() =>
          Effect.gen(function* () {
            for (const name of NAMES) {
              yield* rcon(`gamemode creative ${name}`, { check: false });
              yield* rcon(`clear ${name}`, { check: false });
              yield* rcon(`tp ${name} 0.5 64 0.5`, { check: false });
            }
          }).pipe(Effect.ignore),
        );
        const expectOnline = (indices: number[]) =>
          eventually(
            Effect.gen(function* () {
              const rows = yield* instance.bots();
              const players = yield* rcon("list");
              for (const [index, bot] of bots.entries()) {
                const row = rows.find((entry) => entry.profileId === bot.id);
                const online = indices.includes(index);
                yield* check(
                  row?.isOnline === online &&
                    row.status?.runtimeState ===
                      (online ? BotRuntimeState.RUNNING : BotRuntimeState.STOPPED) &&
                    players.includes(required(NAMES[index], "the bot name")) === online,
                  `${NAMES[index]} did not become ${online ? "online" : "stopped"}`,
                );
              }
            }),
            "60 seconds",
          );

        yield* instance.start();
        yield* expectOnline([0, 1, 2]);
        for (const [index, item] of ["stick", "gold_ingot", "diamond"].entries()) {
          const bot = required(bots[index], "the bot");
          const name = required(NAMES[index], "the bot name");
          yield* rcon(`gamemode creative ${name}`);
          yield* rcon(`tp ${name} ${index - 0.5} 64 0.5 0 0`);
          yield* rcon(`item replace entity ${name} hotbar.${index + 1} with ${item}`);
          yield* bot.selectHotbar(index + 1);
          yield* bot.look(index * 90 - 90, index * 10);
        }
        for (const [index, item] of ["stick", "gold_ingot", "diamond"].entries()) {
          yield* eventually(
            rcon(`data get entity ${NAMES[index]} SelectedItem`).pipe(
              Effect.flatMap((text) =>
                check(
                  text.includes(`minecraft:${item}`),
                  `${NAMES[index]} selected another bot's item`,
                ),
              ),
            ),
            "5 seconds",
          );
          yield* eventually(
            rcon(`data get entity ${NAMES[index]} Rotation`).pipe(
              Effect.flatMap((text) => {
                const values = text.match(/\[(-?[\d.]+)f, (-?[\d.]+)f\]/);
                return check(
                  !!values &&
                    Number(values[1]) === index * 90 - 90 &&
                    Number(values[2]) === index * 10,
                  `${NAMES[index]} did not retain its own rotation`,
                );
              }),
            ),
            "5 seconds",
          );
        }

        const firstBot = required(bots[0], "the first bot");
        const secondBot = required(bots[1], "the second bot");
        yield* firstBot.stop();
        yield* expectOnline([1, 2]);
        yield* instance.restart({ botIds: [firstBot.id, secondBot.id] });
        yield* firstBot.stop();
        yield* expectOnline([1, 2]);
        yield* Effect.sleep("1 second");
        yield* expectOnline([1, 2]);
        yield* instance.stop();
        yield* expectOnline([]);
        yield* instance.start();
        yield* expectOnline([0, 1, 2]);
      }),
    ),
} satisfies E2ETest;
