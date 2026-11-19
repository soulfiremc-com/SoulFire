import { afterEach, expect, it, vi } from "vitest";
import { readEnvironment } from "../smoke/environment.js";

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
