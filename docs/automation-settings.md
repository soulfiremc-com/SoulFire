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
- `shared-structure-intel` (instance scope)
  Controls whether bots reuse each other's portal hints, stronghold hints, fortress hints, and eye-of-ender samples.
- `shared-target-claims` (instance scope)
  Controls whether bots reserve shared work targets like portal frames, exploration cells, and end crystals.
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
- `automation sharedstructures <true|false>`
  Toggles whether bots share structure and portal intelligence across the visible instances.
- `automation sharedclaims <true|false>`
  Toggles whether bots reserve shared automation targets across the visible instances.
- `automation queue`
  Shows the current requirement queue for the selected bots.
- `automation memorystatus [maxEntries]`
  Shows a capped snapshot of remembered automation world state for the selected bots.
- `automation resetmemory`
  Clears remembered automation world state for the selected bots and forces replanning.
- `automation coordinationstatus [maxEntries]`
  Shows capped shared coordination state for the visible instances, including shared claims, shared structure hints, eye samples, and team requirement counts.
- `automation resetcoordination`
  Clears shared automation claims, shared structure hints, and shared eye samples for the visible instances.
- `automation status`
  Shows current bot-level automation state.
- `automation teamstatus`
  Shows instance-level coordination state, including collaboration mode and End-entry limits.
- `automation stop`
  Stops automation for the selected bots.

## Current gRPC and MCP API surface

SoulFire now exposes a first dedicated automation API instead of requiring operators to go through generic command dispatch for the main runtime actions.

Current gRPC RPCs:

- `GetAutomationTeamState`
  Returns instance-level automation settings, team objective, team quotas, and structured per-bot runtime state.
- `GetAutomationCoordinationState`
  Returns shared instance-level coordination state, including shared claims, shared structure hints, eye samples, and team requirement counts.
- `GetAutomationBotState`
  Returns structured automation state for one connected bot, including the queued requirement targets.
- `GetAutomationMemoryState`
  Returns a capped per-bot snapshot of remembered automation world state.
- `StartAutomationBeat`
  Starts beat-the-game automation for the selected connected bots.
- `StartAutomationAcquire`
  Starts a resource acquisition goal for the selected connected bots.
- `PauseAutomation`
  Pauses automation without clearing the current goal.
- `ResumeAutomation`
  Resumes paused automation.
- `StopAutomation`
  Stops automation and clears the current goal.
- `ApplyAutomationPreset`
  Applies a named automation preset to the instance and persists matching per-bot automation defaults.
- `SetAutomationCollaboration`
  Toggles team orchestration by switching between collaborative and independent preset behavior.
- `SetAutomationSharedStructures`
  Enables or disables cross-bot sharing of structure and portal intelligence.
- `SetAutomationSharedClaims`
  Enables or disables cross-bot target reservation.
- `ResetAutomationMemory`
  Clears remembered automation world state for the selected connected bots and forces replanning.
- `ResetAutomationCoordinationState`
  Clears shared automation claims, shared structure hints, and eye samples for the instance.

Matching MCP tools are also available:

- `get_automation_team_state`
- `get_automation_coordination_state`
- `get_automation_bot_state`
- `get_automation_memory_state`
- `start_automation_beat`
- `start_automation_acquire`
- `pause_automation`
- `resume_automation`
- `stop_automation`
- `apply_automation_preset`
- `set_automation_collaboration`
- `set_automation_shared_structures`
- `set_automation_shared_claims`
- `reset_automation_memory`
- `reset_automation_coordination_state`

## Current behavior notes

- When automation is disabled for a bot, the automation controller stands down and releases its claims.
- When team collaboration is disabled, bots stop using shared roles, shared claims, shared structure estimates, and shared progression quotas.
- When the role policy is set to independent mode, bots behave like independent runners even if collaboration remains enabled at the instance level.
- When shared structure intel is disabled, bots stop reusing other bots' shared portal, fortress, stronghold, and eye-of-ender observations, but still retain their own local automation memory.
- When shared target claims are disabled, bots stop reserving shared targets across the instance and may duplicate teammate work more often.
- Exact item requirement keys are now centralized and validated against `Items.*` during startup, so automation no longer relies on scattered string literals for targets like lava buckets or bows.
- Shared End entry can now throttle how many bots enter the End simultaneously.
- Death recovery can now be disabled per bot.
- A dedicated automation gRPC/MCP control surface now exists for runtime inspection and control.
- Requirement queues are now exposed over both CLI and gRPC/MCP state snapshots.
- Per-bot automation memory can now be inspected and reset from both the CLI and the dedicated automation API.
- Shared coordination state can now be inspected and reset from both the CLI and the dedicated automation API.

## Still missing

This is not the finished automation surface. Major missing pieces are still tracked in [automation-roadmap.md](automation-roadmap.md), including:

- GUI dashboards and operator controls
- richer settings coverage and presets
- automation event streams, planner traces, and run-report export
- manual coordination edits such as releasing individual claims or editing shared structure hints
- soak testing and long-run reliability hardening
- broader survival and task parity work
