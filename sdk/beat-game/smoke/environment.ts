import { Config, ConfigProvider, Effect, Option } from "effect";

export function readEnvironment(name: string): string | undefined {
  return Option.getOrUndefined(
    Effect.runSync(
      Config.option(Config.String(name)).parse(
        ConfigProvider.fromEnv({ preserveEmptyStrings: true }),
      ),
    ),
  );
}
