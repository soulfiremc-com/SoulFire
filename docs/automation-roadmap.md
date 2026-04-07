# Automation roadmap

This document is the current maintainer-facing backlog for SoulFire-native survival automation.

Scope:

- SoulFire-native automation only.
- No port, shim, or code reuse from AltoClef.
- Goal state is broader than "beat the game once": it includes reliable parallel runs, operator controls, settings, protocol support, client visibility, and documentation.

This repository currently contains the CLI and server implementation. GUI client work mentioned here belongs in the separate `SoulFireClient` repository, but it is still part of the end-to-end automation feature surface.

## Current baseline

SoulFire now has:

- A native automation controller and shared team coordinator.
- Item acquisition, crafting, smelting, looting, bartering, and beat-game phase orchestration.
- Shared world memory, shared exploration claims, stronghold estimates, and team status reporting.
- Shared coordination inspection and reset hooks over CLI, gRPC, and MCP.
- Settings-backed role overrides and objective overrides exposed over CLI, gRPC, and MCP.
- Native portal construction and portal casting paths.
- Basic end-fight targeting, ranged attacks, and death/stall recovery hooks.

That is enough for a prototype automation stack. It is not yet the same as a production-grade "10 bots beat the game in parallel" system, and it is still short of the broader feature surface associated with AltoClef-style survival automation.

## Current explicit product constraints

These are worth stating directly based on the current repository state:

- The current command surface is still CLI-first, even though a first dedicated automation API now exists.
- A first `AutomationSettings` page now exists, but it still only covers an initial execution, coordination, and observability slice.
- A first automation proto, gRPC, and MCP surface now exists for state snapshots, coordination snapshots, and core control actions, but it is still far from complete.
- Team collaboration is now configurable, including structure-intel and target-claim sharing, but it is still much narrower than the full coordination model described below.
- Operator overrides now exist for forcing roles and objectives, but not yet for claims, targets, phases, or subteams.
- GUI automation controls and dashboards still live outside this repository and are not yet implemented end-to-end.

## P0: reliability for 10 parallel beat-game bots

These are the gaps that still directly block reliable unattended parallel wins.

### Progression robustness

- Harden portal casting and return-portal recovery.
- Add better ruined-portal recognition and completion logic.
- Add safer lava-source selection and retry logic for portal casting.
- Remember portal ownership and last-good portals per bot and per team.
- Add fallback paths when a portal is blocked, griefed, trapped, or links poorly.
- Improve fortress solving beyond marker following and coarse exploration.
- Add better blaze-spawner camping behavior and safer rod farming loops.
- Improve piglin barter loops, bartering safety, and barter completion detection.
- Improve stronghold solving beyond eye samples and layered exploration.
- Add a real portal-room solver and controlled dig-down logic.
- Add stronger activation logic for fragmented portal-room discovery.
- Improve win detection beyond "dragon/crystal not visible for N ticks".

### End-fight tactics

- Add tower-specific crystal handling.
- Add logic for enclosed-cage crystals.
- Add safer target prioritization between crystals, dragon, and survival recovery.
- Add spawn-platform recovery when a bot enters the End in a bad state.
- Add dragon breath avoidance and lingering-cloud avoidance.
- Add better void-edge safety and recentering.
- Add perch detection and perch-specific damage logic.
- Decide and implement bed strategy policy explicitly.
- Decide and implement melee-only fallback policy explicitly.
- Add cooperative end-fight behavior so not all bots do the same thing at once.

### Recovery and anti-looping

- Distinguish between path stall, combat stall, container stall, and progression stall.
- Add recovery strategies specific to each stall class.
- Add grave-item recovery policies and abandonment thresholds.
- Add re-gear loops after repeated deaths.
- Add explicit "give up on this structure/area" backoff rules.
- Track repeated failed actions and failed locations over longer horizons.
- Add run budgets and timeout ceilings per phase.
- Add bot quarantine logic for repeatedly failing bots during a team run.

### Soak testing and validation

- Add repeatable multi-bot integration scenarios for overworld, nether, stronghold, and End.
- Add a soak-test harness for 10-bot runs across multiple seeds.
- Record completion rate, mean run time, phase failure rate, and death rate.
- Run automated regression suites after major automation changes.
- Build a world-fixture library for edge cases: bad portals, lava caves, split strongholds, partial fortresses, bad End spawn.

### Performance and scaling

- Profile scheduler, pathfinding, and memory pressure with 10 active automation controllers.
- Add explicit per-bot and per-instance CPU budgets for automation work.
- Add backpressure when path recomputation or planner recomputation becomes too frequent.
- Add shared caching for expensive search results where it is safe to do so.
- Prevent one thrashing bot from starving the rest of the team.
- Add caps on remembered targets, claims, planner queue size, and trace retention.
- Add settings for world-memory scan cadence and scan radius so operators can trade accuracy for throughput.
- Benchmark automation under mixed account types, mixed latencies, and mixed proxy quality.
- Verify automation remains stable during long sessions with account reconnects and chunk churn.

## P1: broader automation parity and feature surface

These are the major gaps between the current automation stack and a fuller survival automation system.

### Task catalogue breadth

- Generalized `goto` tasks for blocks, entities, structures, and coordinates.
- Follow/protect/escort tasks for players or other bots.
- Deposit, stash, and withdraw workflows.
- Inventory cleanup and loadout normalization tasks.
- Generalized kill/hunt tasks with entity-specific policies.
- Sleep/bed usage tasks.
- Generalized build/place/activate tasks.
- Farming/replanting tasks.
- Animal breeding and food-production tasks.
- Resource gathering policies that prefer villages, chests, structures, or mob drops when appropriate.

### Planner and recipe depth

- Expand recipe coverage far beyond the current hardcoded set.
- Add alternate acquisition strategies for the same target item.
- Add tool-upgrade planning beyond pickaxe-only progression.
- Add armor planning and shield replacement planning.
- Add explicit bow/ammo replenishment planning.
- Add richer food strategy selection by environment and phase.
- Add planner cost models for danger, travel time, and contention.
- Add better substitute handling for grouped requirements.
- Add planner support for rare or optional end-fight utilities.

### Survival and combat depth

- Add water-bucket acquisition and clutch-capable policies where appropriate.
- Add shield-aware combat timing and shield replacement logic.
- Add better hotbar management and automatic weapon/tool switching.
- Add more explicit mob-specific combat policies, for example creepers, skeletons, endermen, blazes, piglins, and dragon-adjacent mobs.
- Add better low-health, low-food, and low-armor retreat heuristics.
- Add smarter block-placement tactics for self-defense and route stabilization.
- Add safer fluid interaction policies for lava lakes, water columns, and soul-sand bubbles.
- Add more consistent handling of fire, knockback, fall damage, and environmental hazards.

### World tracking and memory

- Track more structure hints and progression-relevant landmarks.
- Track richer dropped-item state and ownership contention.
- Track "last inspected" and "likely refilled" state for containers.
- Track partial structure observations and infer likely structure centers.
- Improve memory expiration rules for far-away high-value targets.
- Persist selected automation memory across reconnects or restarts.
- Add per-bot and per-team blacklists for bad targets and bad routes.

### Building and construction

- Finish the currently stubbed schematic/building controller.
- Add native structure placement/build tasks for shelters, bridges, scaffolds, and simple defensive placements.
- Add safer temporary-block management and cleanup.
- Add better support for deliberate block placement during combat and traversal.

### Interaction surface parity

- Add a butler-style chat control layer if that remains in scope for SoulFire.
- Add automation-aware chat triggers and safety filters.
- Add better integration between visual scripting and automation tasks.
- Add plugin APIs for custom automation goals, strategies, and planners.
- Add automation tasks that can be invoked from scripts and then observed or canceled externally.
- Add shared abstractions so plugins can contribute acquisition sources, structure hints, and recovery handlers.

## P2: team coordination and collaboration controls

The current team coordinator is useful, but still too fixed and too implicit.

### Team strategy

- Replace static role assignment based on bot ordering with configurable role policy.
- Settings-backed role overrides and objective overrides now exist, but they are still coarse manual controls rather than a full operator strategy layer.
- Support "independent runners" mode versus "fully collaborative team" mode.
- Support subteams, for example separate nether and overworld squads.
- Allow dynamic reassignment when bots die, disconnect, or finish their phase.
- Add explicit team-wide quotas and shared goals that are configurable.
- Add shared item handoff, chest dropoff, and rendezvous logic.
- Add task reservation for structures, chests, spawners, portals, and fight targets.
- Add stronger collision avoidance between bots working in the same area.
- Add leader election or operator-selected leader semantics for team-level decisions.
- Add role fallback behavior when specialists die or disconnect.
- Add coordination that accounts for bots with different ping, proxy quality, or combat capability.
- Add contention-aware resource assignment so bots do not overmine the same vein or rush the same container.

### Team economy and logistics

- Add explicit stockpile goals and shared resupply behavior.
- Add dedicated support or logistics roles where that improves team throughput.
- Add temporary team stash logic for overworld, nether, and stronghold staging areas.
- Add bot-to-bot transfer workflows and operator controls around them.
- Add settings for whether bots may cannibalize shared stockpiles versus maintaining strict quotas.
- Add recovery-aware redistribution when one bot dies holding critical resources.

### Collaboration settings

- Expand the existing automation settings page beyond the current first execution and coordination slice.
- Keep the simple on/off collaboration toggle, but continue adding more granular collaboration controls underneath it.
- Shared structure-intel and shared target-claim toggles now exist; shared looting, shared handoffs, and shared stash policies still do not.
- Objective override and per-bot role override now exist; subteam-level overrides and per-phase overrides still do not.
- Add toggles for role specialization, shared looting, and shared End entry policy beyond the current boolean throttle.
- Add caps for how many bots may enter the nether, stronghold, and End at once.
- Add settings for shared exploration spacing and structure claim lease time.
- Add settings for whether death recovery is attempted.
- Add settings for whether beds are used in the End.
- Add settings for whether bow combat is preferred, required, or optional.
- Add settings for portal strategy selection: existing portal only, build-only, cast-only, mixed.
- Add settings for aggression and safety thresholds.
- Add settings for how aggressively bots share information, items, and structure claims.
- Add settings for role assignment policy: static, dynamic, operator-defined, or objective-based.
- Add settings for whether handoffs are allowed, required, or disabled.
- Add settings for whether individual bots may continue solo if team coordination degrades.

## P3: settings, protocol, commands, and client surface

A first automation settings object is now registered alongside Bot, Account, Proxy, AI, and Pathfinding settings, but it still only covers a narrow operator slice.

### Server settings and config model

- Expand `AutomationSettings` from its current first-class settings page into a fuller configuration surface.
- Define stable namespaces and keys for automation configuration.
- Separate team-level settings from per-bot settings where appropriate.
- Support presets for common modes: solo survival, team beat-game, resource farming, structure search.
- Document defaults and safe ranges for each setting.

### Suggested AutomationSettings taxonomy

The settings model will probably need at least the following groups:

- General execution:
  `automation.enabled`, `automation.default_mode`, `automation.allow_manual_override`, `automation.max_active_goals`.
- Team coordination:
  `automation.team.enabled`, `automation.team.role_policy`, `automation.team.subteam_mode`, `automation.team.shared_loot`, `automation.team.shared_structures`, `automation.team.shared_end_entry`.
- Quotas and progression:
  `automation.progression.target_blaze_rods`, `automation.progression.target_ender_pearls`, `automation.progression.target_eyes`, `automation.progression.target_arrows`, `automation.progression.target_beds`.
- Search and exploration:
  `automation.search.spacing_blocks`, `automation.search.claim_ttl_ticks`, `automation.search.fortress_confidence_threshold`, `automation.search.stronghold_confidence_threshold`, `automation.search.portal_room_digdown_enabled`.
- Portal strategy:
  `automation.portal.strategy`, `automation.portal.max_cast_retries`, `automation.portal.allow_ruined_portals`, `automation.portal.allow_return_portal_build`, `automation.portal.max_simultaneous_nether_entries`.
- Combat and survival:
  `automation.combat.bow_policy`, `automation.combat.bed_policy`, `automation.combat.shield_policy`, `automation.combat.retreat_health_threshold`, `automation.combat.retreat_food_threshold`.
- Recovery:
  `automation.recovery.enabled`, `automation.recovery.max_timeouts_per_phase`, `automation.recovery.grave_recovery_enabled`, `automation.recovery.structure_backoff_enabled`, `automation.recovery.max_deaths_before_quarantine`.
- Performance:
  `automation.performance.memory_scan_radius`, `automation.performance.memory_scan_interval_ticks`, `automation.performance.max_claims`, `automation.performance.max_trace_events`, `automation.performance.max_parallel_plans`.
- Observability and debugging:
  `automation.debug.enable_planner_traces`, `automation.debug.enable_event_timeline`, `automation.debug.persist_run_reports`, `automation.debug.verbose_status_strings`.

These key names are illustrative rather than final, but the product needs this level of documented configurability.

### Commands

- Expand the automation command set beyond `beat`, `get`, `status`, `teamstatus`, and `stop`.
- `pause`, `resume`, queue inspection, per-bot memory reset, coordination inspection, coordination reset, granular collaboration toggles, and role/objective overrides now exist.
- Add `restart-phase` and `abort-phase`.
- Manual role/objective commands now exist.
- Add commands to claim or release targets manually.
- Expand queue inspection beyond the current requirement list into richer planner-decision visibility.
- Expand memory inspection beyond the current per-bot remembered-state dump and per-bot reset flow.
- Add JSON or structured export modes for queue, memory, and coordination inspection instead of text-only CLI output.
- Add commands to toggle collaboration on and off without restarting the instance.
- Add commands to apply automation presets and diff automation settings against defaults.
- Add commands to diff current automation settings against a named preset.
- Add commands to quarantine, unquarantine, or reassign a bot during a team run.
- Add commands to override a single bot's current phase or force a structure target.
- Add commands to force portal strategy, force structure target, or skip a failed phase.
- Add commands to export run reports and last-failure context.

### gRPC, MCP, and protocol surface

- Expand the new explicit automation RPCs instead of relying on generic command dispatch for advanced operations.
- Add automation status streaming and event streaming.
- Expose the automation phase, current action, planner queue, role, objective, and recovery state over RPC.
- Team summaries, claims, shared block hints, eye samples, and per-bot memory snapshots now exist as point-in-time RPCs.
- Objective override and per-bot role override RPCs now exist as first-class automation operations.
- Expand the new automation MCP tooling so external agents can inspect and control automation directly beyond the first action set.
- Add versioned proto messages for automation settings and telemetry.
- Add automation-specific audit log events.
- Add server-pushed subscriptions for queue changes, phase changes, recoveries, and coordination-claim churn.
- Add explicit operator override RPCs for claims, roles, objectives, and target abandonment.

### Suggested automation API surface

At minimum, the dedicated automation API likely needs:

Already implemented in the first API slice:

- `GetAutomationTeamState`
- `GetAutomationCoordinationState`
- `GetAutomationBotState`
- `GetAutomationMemoryState`
- `StartAutomationBeat`
- `StartAutomationAcquire`
- `PauseAutomation`
- `ResumeAutomation`
- `StopAutomation`
- `ApplyAutomationPreset`
- `SetAutomationCollaboration`
- `SetAutomationObjectiveOverride`
- `SetAutomationSharedStructures`
- `SetAutomationSharedClaims`
- `SetAutomationRoleOverride`
- `ResetAutomationMemory`
- `ResetAutomationCoordinationState`

- `StartAutomationRun`
- `StopAutomationRun`
- `PauseAutomationRun`
- `ResumeAutomationRun`
- `GetAutomationState`
- `StreamAutomationEvents`
- `GetAutomationTeamState`
- `StreamAutomationTeamEvents`
- `UpdateAutomationSettings`
- `ResetAutomationMemory`
- `GetAutomationPlannerTrace`
- `SetAutomationObjective`
- `SetAutomationRole`
- `ClaimAutomationTarget`
- `ReleaseAutomationTarget`
- `ExportAutomationRunReport`

The proto layer should clearly separate:

- point-in-time state snapshots
- append-only event streams
- mutable operator actions
- stable settings/config documents
- post-run reports and traces

### Permissions, tenancy, and safety rails

- Add explicit permission scopes for viewing automation state versus controlling automation.
- Add separate permission scopes for settings mutation, force actions, and destructive reset operations.
- Add automation audit logs for start, stop, pause, resume, force-role, force-objective, and reset actions.
- Add instance-level resource ceilings so one user cannot accidentally start too many expensive automation runs.
- Add safe defaults so automation features cannot unexpectedly affect non-selected bots.
- Add operator-visible kill switches at bot, team, and instance scope.

### GUI client and operator experience

The official GUI client is in a different repository, but the following features are still needed:

- Dedicated automation settings page.
- Dedicated coordination-inspection page or panel.
- Dedicated automation dashboard per instance.
- Dedicated override-management controls for roles, objectives, and future force actions.
- Per-bot automation panels showing phase, task tree, planner queue, deaths, and last recovery.
- Team view showing roles, quotas, structure targets, and shared objective.
- Map or world overlay for shared claims, portals, fortress hints, stronghold estimate, and portal-room estimate.
- Controls to pause, resume, reprioritize, or remove bots from a collaborative run.
- Controls to toggle collaboration on and off.
- Better surfacing of why a bot is stuck or what it is waiting on.
- Run history and post-run summaries.
- Live timeline views that correlate planner decisions, deaths, claims, and recoveries.
- Rich settings forms with presets, validation, and inline documentation for every option.
- Controls to force objectives, force roles, and manually hand bots into or out of subteams.
- A dedicated view for shared memory and claims so operators can see why the coordinator chose a target.
- Notification surfaces for phase completion, repeated failures, death spikes, and low-confidence runs.
- Quick controls for "pause this bot", "pause the team", "resume only healthy bots", and "quarantine bot".

## P4: observability, metrics, and operator tooling

- Add automation-specific metrics beyond general instance metrics.
- Track phase durations, retries, stalls, deaths, path failures, and completion outcomes.
- Track structure-search efficiency and false-positive rates.
- Track handoff success, portal success, and recovery success.
- Add automation event logs suitable for replaying a run timeline.
- Add structured "why this plan was chosen" traces for debugging planner behavior.
- Add operator-visible alerts for repeated failures or low completion probability.
- Add exportable run reports for comparing seeds, settings, and versions.

### Replay, diagnostics, and reproducibility

- Capture enough run context to replay the automation timeline after a failure.
- Persist seed, version, settings preset, automation settings, and relevant environment metadata with each run report.
- Add one-shot support bundles for bug reports that include last events, last planner trace, claims, and phase summaries.
- Add tooling to compare two runs and highlight why they diverged.
- Add deterministic-ish regression worlds for repeatable debugging even when full determinism is not achievable.

## P5: documentation and discoverability

Current public documentation focuses on installation, usage, commands, plugins, and general operation. Automation-specific documentation is still missing.

The documentation effort should follow Diataxis rather than living as one giant page.

### Tutorials

- Tutorial: start a single automation run.
- Tutorial: run a 10-bot collaborative beat-game session.
- Tutorial: observe a run, pause it, and recover from a stuck bot.

### How-to guides

- How to tune automation for low-resource hardware.
- How to disable collaboration and run independent bots.
- How to investigate a failed fortress or stronghold search.
- How to export a run report for debugging.

### User-facing docs

- Add automation command documentation.
- Add automation settings reference documentation.
- Add automation coordination-state reference documentation.
- Add a guide for running a collaborative beat-game team.
- Add safety guidance for using automation on allowed/private servers only.
- Add troubleshooting docs for common automation failure modes.
- Add a glossary for automation terms such as role, objective, claim, shared memory, and recovery.

### Reference

- Document every automation command and response shape.
- Document every automation settings key, default, type, and safe range.
- Document every coordination snapshot field and reset behavior.
- Document every automation phase, role, objective, claim type, and recovery reason.
- Document the automation proto services, messages, and events once they exist.

### Explanation

- Explain the intended difference between SoulFire-native automation and a port/shim of another project.
- Explain why team coordination is configurable rather than always-on.
- Explain the tradeoffs between safety, speed, stealth, and win rate.
- Explain how automation support differs across protocol versions and account types.

### Maintainer docs

- Document the automation architecture.
- Document the planner model and requirement normalization rules.
- Document the shared-coordinator lifecycle and invariants.
- Document the RPC and settings model for automation.
- Document expected test strategy and soak-test methodology.
- Maintain a versioned roadmap like this one as the implementation evolves.

## P6: cross-version and protocol coverage

SoulFire supports many Minecraft versions and both Java and Bedrock accounts. Automation coverage needs to be treated as a compatibility surface, not a single-version feature.

- Define an automation support matrix by protocol/version.
- Test automation against multiple versions, not just the current native one.
- Audit container, interaction, and entity behavior differences that affect automation.
- Audit dimension, portal, and combat differences across supported versions.
- Decide what automation behavior is supported for Bedrock connections and what is not.
- Surface unsupported automation modes clearly in settings and UI.

## P7: polish and long-tail improvements

- Better loadout preferences and cosmetic user preferences for automation runs.
- More nuanced anti-cheat-aware motion and input jitter for automation-specific actions.
- Better coexistence with other plugins and scripted behaviors.
- Better persistence and resumption when the server or SoulFire restarts mid-run.
- Better modularization so automation pieces can be reused outside the beat-game flow.
- Better separation between experimental automation features and stable ones.

## P8: release management and support policy

- Define what counts as experimental, beta, and stable automation features.
- Add feature flags for risky automation features so operators can opt in deliberately.
- Define release gates for saying "10-bot collaborative beat-game is supported".
- Publish compatibility promises for settings keys, proto messages, and operator workflows.
- Add migration notes when automation settings or behavior change between releases.
- Maintain a known-issues list for automation regressions and protocol-specific caveats.

## Suggested implementation order

If the goal is "10 SoulFire bots reliably beat the game in parallel", the highest-value order is:

1. Soak tests, failure classification, and recovery hardening.
2. Stronger fortress, stronghold, and portal-room solving.
3. End-fight safety and cooperative tactics.
4. Performance profiling and scaling fixes for 10 active bots.
5. Configurable collaboration and automation settings.
6. Automation RPCs, permissions, and operator visibility.
7. GUI client dashboards and controls.
8. Broader task catalogue and parity work beyond beat-game.
9. Public documentation, release policy, and long-tail polish.

## Notable currently missing product pieces

These are worth calling out explicitly because they are easy to overlook:

- The current automation proto/gRPC/MCP surface does not yet include streams, planner traces, force actions, or run-report export.
- Shared-memory and claim inspection now exist, but historical timelines, subscriptions, and manual claim editing still do not.
- Role/objective overrides now exist, but manual claim edits, phase overrides, and force-target workflows still do not.
- No dedicated GUI client automation dashboard exists in this repository.
- No dedicated automation event stream or run-report export exists yet.
- No automation-specific permission model exists yet.
- No 10-bot soak-test suite exists yet.
- No published automation support matrix exists yet.
- General survival automation breadth is still narrower than a mature AltoClef-like system.
