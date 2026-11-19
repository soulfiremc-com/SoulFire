import { afterEach, expect, it, vi } from "vitest";
import { minecraftContainerLifecycleArguments, readEnvironment } from "../smoke/environment.js";

afterEach(() => {
  vi.unstubAllEnvs();
});

it("reads current environment values and preserves empty strings and whitespace", () => {
  const name = "SOULFIRE_SMOKE_TEST_VALUE";
  vi.stubEnv(name, undefined);
  expect(readEnvironment(name)).toBeUndefined();

  vi.stubEnv(name, "  value ");
  expect(readEnvironment(name)).toBe("  value ");

  vi.stubEnv(name, "");
  expect(readEnvironment(name)).toBe("");
});

it("restarts a kept Minecraft container instead of deleting it when it stops", () => {
  // As in two live probes: a Docker daemon restart stopped the servers, and
  // --rm deleted the containers that held long runs.
  expect(minecraftContainerLifecycleArguments(true)).toStrictEqual(["--restart", "unless-stopped"]);
  expect(minecraftContainerLifecycleArguments(false)).toStrictEqual(["--rm"]);
});
