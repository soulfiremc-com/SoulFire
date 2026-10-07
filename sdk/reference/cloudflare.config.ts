import { defineConfig } from "cf/config";

export default defineConfig(({ mode }) => ({
  accountId: "4dd0e72d7bf89402be905fb40cdb230a",
  worker: {
    name: mode === "python" ? "soulfire-sdk-python" : "soulfire-sdk-typescript",
    domains: [mode === "python" ? "py.soulfiremc.com" : "ts.soulfiremc.com"],
    compatibilityDate: "2026-10-07",
    assets: {
      htmlHandling: mode === "python" ? "auto-trailing-slash" : "none",
      notFoundHandling: "none",
    },
  },
}));
