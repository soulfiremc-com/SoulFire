# Beat-game live smoke worker

`run-live.mjs` runs the packed Soulfire SDK and beat-game package in a separate
Node.js process. It is intentionally outside the package tarball so the smoke
test proves that a normal consumer can install and use the published entry
points.

The worker requires:

- `SOULFIRE_SMOKE_BASE_URL`
- `SOULFIRE_SMOKE_TOKEN`
- `SOULFIRE_SMOKE_INSTANCE_ID`
- `SOULFIRE_SMOKE_BOT_IDS`, as one or more comma-separated bot IDs

Optional variables:

- `SOULFIRE_SMOKE_RUN_ID`
- `SOULFIRE_SMOKE_TEAM_ID`
- `SOULFIRE_SMOKE_CHECKPOINT_DIR`
- `SOULFIRE_SMOKE_START_BOTS`, which defaults to `true`
- `SOULFIRE_SMOKE_CRASH_AFTER_CHECKPOINTS`, which exits with status 75 after
  the configured checkpoint count to test hard-crash recovery

Use the same run and team IDs when restarting after an intentional crash. The
JSON checkpoint store then resumes the existing run instead of creating a new
one.

The worker writes newline-delimited JSON events to standard output. If a run
fails, configure CI to retain this output, the checkpoint directory, the
Soulfire log, and the Minecraft world.
