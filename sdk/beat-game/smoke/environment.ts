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

// A kept world must outlive a Docker daemon restart. Two live probes lost
// their servers when the daemon restarted and --rm deleted the containers.
export function minecraftContainerLifecycleArguments(keepContainer: boolean): readonly string[] {
  return keepContainer ? ["--restart", "unless-stopped"] : ["--rm"];
}
