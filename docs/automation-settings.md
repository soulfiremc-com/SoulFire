# Automation settings and commands

This document describes the automation controls that currently exist in the SoulFire server and CLI.

It is intentionally narrow in scope:

- It documents the currently implemented settings and commands.
- It does not replace the broader backlog in [automation-roadmap.md](automation-roadmap.md).
- It does not describe GUI client work in the separate `SoulFireClient` repository.

## Current settings page

SoulFire now exposes an `Automation Settings` instance page alongside the other built-in instance pages.

Current settings in the `automation` namespace:

- `enabled` (bot scope)
  Turns SoulFire-native automation on or off for an individual bot.
- `team-collaboration` (instance scope)
  Turns team orchestration on or off for the instance. When off, bots do not share roles, claims, structure estimates, or team-wide progression state.
- `role-policy` (instance scope)
  Controls whether bots use the shared static team-role layout or behave as independent runners.
- `shared-end-entry` (instance scope)
  Controls whether End entry is throttled across the team.
- `max-end-bots` (instance scope)
  Caps how many bots may be active in the End at once when shared End entry is enabled.
- `allow-death-recovery` (bot scope)
  Controls whether automation attempts to recover dropped items after death.
- `memory-scan-radius` (bot scope)
  Controls the automation world-memory scan radius.
- `memory-scan-interval-ticks` (bot scope)
  Controls how frequently automation performs a full block-memory scan.
- `retreat-health-threshold` (bot scope)
  Controls the health threshold for flee interrupts.
- `retreat-food-threshold` (bot scope)
  Controls the food threshold for eat interrupts.

## Current commands

Current `automation` command surface:

- `automation beat`
  Starts beat-the-game automation for the selected bots.
- `automation get <target> <count>`
  Starts a resource acquisition goal for the selected bots.
- `automation pause`
  Pauses automation for the selected bots without clearing the current goal mode.
- `automation resume`
  Resumes automation for selected paused bots.
- `automation collaboration <true|false>`
  Toggles team orchestration for the visible instances. `false` switches the instance to the `independent-runners` preset.
- `automation status`
  Shows current bot-level automation state.
- `automation teamstatus`
  Shows instance-level coordination state, including collaboration mode and End-entry limits.
- `automation stop`
  Stops automation for the selected bots.

## Current behavior notes

- When automation is disabled for a bot, the automation controller stands down and releases its claims.
- When team collaboration is disabled, bots stop using shared roles, shared claims, shared structure estimates, and shared progression quotas.
- When the role policy is set to independent mode, bots behave like independent runners even if collaboration remains enabled at the instance level.
- Exact item requirement keys are now centralized and validated against `Items.*` during startup, so automation no longer relies on scattered string literals for targets like lava buckets or bows.
- Shared End entry can now throttle how many bots enter the End simultaneously.
- Death recovery can now be disabled per bot.

## Still missing

This is not the finished automation surface. Major missing pieces are still tracked in [automation-roadmap.md](automation-roadmap.md), including:

- dedicated automation gRPC and MCP APIs
- GUI dashboards and operator controls
- richer settings coverage and presets
- soak testing and long-run reliability hardening
- broader survival and task parity work
