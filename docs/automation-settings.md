# Automation settings and controls

This document describes the automation controls that currently exist across the SoulFire server, CLI, gRPC and MCP API surface, and the official GUI client.

It is intentionally scoped to what is shipped now:

- It documents the currently implemented settings, commands, client controls, and automation APIs.
- It does not replace the broader backlog in [automation-gap-audit.md](automation-gap-audit.md) and [automation-roadmap.md](automation-roadmap.md).
- It focuses on operator-facing behavior rather than internal implementation details.

## Current settings surfaces

SoulFire now exposes automation controls in two places:

- A built-in `Automation Settings` instance settings page in the server-backed settings model.
- A dedicated `Automation` instance dashboard in the official `SoulFireClient` GUI that shows live team state, per-bot runtime state, quota progress, shared coordination state, and quick control actions.

The GUI settings sidebar now also discovers the built-in automation settings page automatically, so operators do not need a client-only hardcoded entry for it.

## Current settings page

Current settings in the `automation` namespace:

- `enabled` (bot scope)
  Master switch for SoulFire-native automation on an individual bot. The dedicated automation dashboard now exposes this directly per bot.
- `role-override` (bot scope)
  Forces an individual bot into a specific automation role such as `lead` or `stronghold-scout`. `auto` clears the override.
- `allow-death-recovery` (bot scope)
  Controls whether automation attempts to recover dropped items after death. Default `true`.
- `memory-scan-radius` (bot scope)
  Controls the automation world-memory scan radius. Default `48`, valid range `8-96`.
- `memory-scan-interval-ticks` (bot scope)
  Controls how frequently automation performs a full block-memory scan. Default `20`, valid range `1-200`.
- `retreat-health-threshold` (bot scope)
  Controls the health threshold for flee interrupts. Default `8`, valid range `1-20`.
- `retreat-food-threshold` (bot scope)
  Controls the food threshold for eat interrupts. Default `12`, valid range `1-20`.
- `preset` (instance scope)
  Named automation preset that captures the intended coordination style for the instance.
- `team-collaboration` (instance scope)
  Turns team orchestration on or off for the instance. When off, bots do not share roles, claims, structure estimates, or team-wide progression state.
- `role-policy` (instance scope)
  Controls whether bots use the shared static team-role layout or behave as independent runners.
- `objective-override` (instance scope)
  Forces a specific shared automation objective such as `nether-progress` or `end-assault`. `auto` clears the override.
- `shared-structure-intel` (instance scope)
  Controls whether bots reuse each other's portal hints, fortress hints, stronghold hints, and eye-of-ender samples.
- `shared-target-claims` (instance scope)
  Controls whether bots reserve shared work targets like portal frames, exploration cells, and end crystals.
- `shared-end-entry` (instance scope)
  Controls whether End entry is throttled across the team.
- `max-end-bots` (instance scope)
  Caps how many bots may be active in the End at once when shared End entry is enabled.
- `target-blaze-rods` (instance scope)
  Overrides the team-wide blaze rod quota. `0` keeps the automatic quota derived from team size.
- `target-ender-pearls` (instance scope)
  Overrides the team-wide ender pearl quota. `0` keeps the automatic quota derived from team size.
- `target-ender-eyes` (instance scope)
  Overrides the team-wide eye-of-ender quota. `0` keeps the automatic quota derived from team size.
- `target-arrows` (instance scope)
  Overrides the team-wide arrow quota for ranged support. `0` keeps the automatic quota derived from team size.
- `target-beds` (instance scope)
  Overrides the team-wide bed quota for dragon-fight preparation. `0` keeps the automatic quota derived from team size.

## Current CLI commands

Current `automation` command surface:

- `automation beat`
  Starts beat-the-game automation for the selected bots.
- `automation get <target> <count>`
  Starts a resource acquisition goal for the selected bots.
- `automation pause`
  Pauses automation for the selected bots without clearing the current goal mode.
- `automation resume`
  Resumes automation for selected paused bots.
- `automation preset <preset>`
  Applies a named automation preset to the visible instances and matching per-bot defaults.
- `automation collaboration <true|false>`
  Toggles team orchestration for the visible instances. `false` switches the instance to the `independent-runners` preset.
- `automation sharedstructures <true|false>`
  Toggles whether bots share structure and portal intelligence across the visible instances.
- `automation sharedclaims <true|false>`
  Toggles whether bots reserve shared automation targets across the visible instances.
- `automation rolepolicy <policy>`
  Sets the team role-allocation policy for the visible instances.
- `automation objective <objective>`
  Forces or clears the shared automation objective for the visible instances. Use `auto` to clear the override.
- `automation role <role>`
  Forces or clears the automation role override for the selected bots. Use `auto` to clear the override.
- `automation sharedendentry <true|false>`
  Toggles shared End-entry throttling for the visible instances.
- `automation maxendbots <count>`
  Sets how many bots may be active in the End at once for the visible instances.
- `automation quota <target> <count>`
  Overrides one shared automation quota such as blaze rods, pearls, eyes, arrows, or beds for the visible instances. Use `0` to restore automatic team-size-based behavior.
- `automation enabled <true|false>`
  Enables or disables automation for the selected bots.
- `automation deathrecovery <true|false>`
  Enables or disables death-recovery behavior for the selected bots.
- `automation memoryscanradius <radius>`
  Sets the automation world-memory scan radius for the selected bots. Valid range `8-96`.
- `automation memoryscaninterval <ticks>`
  Sets the automation world-memory scan interval for the selected bots. Valid range `1-200`.
- `automation retreathealth <health>`
  Sets the automation retreat-health threshold for the selected bots. Valid range `1-20`.
- `automation retreatfood <food>`
  Sets the automation eat-food threshold for the selected bots. Valid range `1-20`.
- `automation status`
  Shows current bot-level automation state.
- `automation queue`
  Shows the current requirement queue for the selected bots.
- `automation memorystatus [maxEntries]`
  Shows a capped snapshot of remembered automation world state for the selected bots.
- `automation resetmemory`
  Clears remembered automation world state for the selected bots and forces replanning.
- `automation teamstatus`
  Shows instance-level coordination state, including collaboration mode and End-entry limits.
- `automation coordinationstatus [maxEntries]`
  Shows capped shared coordination state for the visible instances, including shared claims, shared structure hints, eye samples, and team requirement counts.
- `automation releaseclaim <key>`
  Releases one shared automation claim by its exact claim key for the visible instances.
- `automation releasebotclaims`
  Releases all shared automation claims currently owned by the selected bots.
- `automation resetcoordination`
  Clears shared automation claims, shared structure hints, and shared eye samples for the visible instances.
- `automation stop`
  Stops automation for the selected bots.

## Current gRPC and MCP API surface

SoulFire now exposes a dedicated automation API instead of requiring operators to go through generic command dispatch for the main runtime actions.

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
- `SetAutomationRolePolicy`
  Changes how the automation coordinator assigns roles for the instance.
- `SetAutomationSharedStructures`
  Enables or disables cross-bot sharing of structure and portal intelligence.
- `SetAutomationSharedClaims`
  Enables or disables cross-bot target reservation.
- `SetAutomationSharedEndEntry`
  Enables or disables shared End-entry throttling.
- `SetAutomationMaxEndBots`
  Changes the maximum number of bots that may be active in the End at once.
- `SetAutomationQuotaOverride`
  Overrides or clears one shared team quota such as blaze rods, pearls, eyes, arrows, or beds.
- `SetAutomationObjectiveOverride`
  Forces or clears the shared automation objective override for the instance.
- `SetAutomationRoleOverride`
  Forces or clears per-bot automation role overrides for selected configured bots.
- `UpdateAutomationBotSettings`
  Applies a sparse bot-settings patch for selected configured bots, covering enablement, death recovery, scan tuning, retreat thresholds, and role override in one call.
- `ResetAutomationMemory`
  Clears remembered automation world state for the selected connected bots and forces replanning.
- `ReleaseAutomationClaim`
  Releases one shared automation claim by exact key for the instance.
- `ReleaseAutomationBotClaims`
  Releases all shared automation claims owned by the selected connected bots.
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
- `set_automation_role_policy`
- `set_automation_shared_structures`
- `set_automation_shared_claims`
- `set_automation_shared_end_entry`
- `set_automation_max_end_bots`
- `set_automation_quota_override`
- `set_automation_objective_override`
- `set_automation_role_override`
- `update_automation_bot_settings`
- `reset_automation_memory`
- `release_automation_claim`
- `release_automation_bot_claims`
- `reset_automation_coordination_state`

## Current GUI client surface

The current `SoulFireClient` automation dashboard provides:

- live polled team-state and coordination-state summaries per instance
- quota progress cards for shared requirement targets
- team-level quick actions for beat, acquire, pause, resume, stop, and coordination reset
- instance-level coordination controls for preset, collaboration, role policy, objective override, shared structures, shared claims, shared End entry, max End bots, and team quota overrides
- direct navigation to the built-in automation settings page when the page is available for the instance
- per-bot runtime cards with editable automation enablement, death recovery, scan tuning, retreat thresholds, role override, status summary, role, objective, phase, location, current action, queued targets, recovery counters, and recent progress timestamps
- bot search plus role, status, and dimension filters for narrowing large team views
- dashboard selection state and bulk actions for pause, resume, stop, reset memory, release claims, and light bot-settings patching across selected bots
- shared coordination inspection for claims, shared structure hints, and eye-of-ender samples

This is a first operator dashboard rather than a finished automation control center. Missing client parity work is tracked in [automation-gap-audit.md](automation-gap-audit.md) and [automation-roadmap.md](automation-roadmap.md).

## Current behavior notes

- When automation is disabled for a bot, the automation controller stands down and releases its claims.
- When team collaboration is disabled, bots stop using shared roles, shared claims, shared structure estimates, and shared progression quotas.
- When the role policy is set to independent mode, bots behave like independent runners even if collaboration remains enabled at the instance level.
- An objective override forces the effective team objective for the instance until it is cleared, even if the inferred coordinator objective would differ.
- A role override forces one bot's effective automation role until it is cleared, even if the coordinator would normally rebalance that bot into another role.
- When shared structure intel is disabled, bots stop reusing other bots' shared portal, fortress, stronghold, and eye-of-ender observations, but still retain their own local automation memory.
- When shared target claims are disabled, bots stop reserving shared targets across the instance and may duplicate teammate work more often.
- Shared End entry can throttle how many bots enter the End simultaneously.
- Team requirement quotas for blaze rods, pearls, eyes, arrows, and beds can now be overridden explicitly from CLI, gRPC, MCP, the built-in settings page, and the automation dashboard while keeping `0` as the automatic team-size-based mode.
- Dedicated per-bot automation tuning now exists in the automation dashboard and over the dedicated automation API, so operators can change enablement, recovery, scan cadence, retreat thresholds, and role override without dropping back to the generic settings surface.
- Exact item requirement keys are centralized and validated against `Items.*` during startup, so automation no longer relies on scattered string literals for targets like lava buckets or bows.
- Requirement queues are exposed over both CLI and gRPC/MCP state snapshots.
- Per-bot automation memory can be inspected and reset from both the CLI and the dedicated automation API.
- Shared coordination state can be inspected and reset from both the CLI and the dedicated automation API.
- Shared coordination claims can be released manually, either by exact claim key or by releasing all claims owned by selected bots.

## Still missing

This is not the finished automation surface. Major remaining gaps are tracked in:

- [automation-gap-audit.md](automation-gap-audit.md) for the flat cross-cutting gap inventory
- [automation-roadmap.md](automation-roadmap.md) for prioritization and implementation order
