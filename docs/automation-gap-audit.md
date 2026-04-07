# Automation gap audit

This document is the flat maintainer-facing inventory of missing or improvable automation product surface after the current implementation pass.

It is intentionally broader than a single repository:

- `SoulFire` owns the automation engine, settings model, CLI, gRPC, and MCP surface.
- `SoulFireClient` owns the official operator UI for inspecting and controlling that automation.
- The goal is not just "a bot can beat the game once." The goal is a reliable, observable, configurable automation platform that can support AltoClef-like operator expectations without porting AltoClef itself.

For prioritization and sequencing, use [automation-roadmap.md](automation-roadmap.md). This document is the wider inventory.

## What shipped in this phase

These gaps were closed in the current implementation slice:

- first dedicated automation dashboard in `SoulFireClient`
- automation sidebar entry in the client
- automatic discovery of the built-in automation settings page in the client settings navigation
- dedicated client controls for preset, collaboration, role policy, objective override, shared structures, shared claims, shared End entry, and max End bots
- server-side proto, gRPC, and MCP support for `SetAutomationRolePolicy`, `SetAutomationSharedEndEntry`, and `SetAutomationMaxEndBots`
- explicit automation quota overrides for blaze rods, pearls, eyes, arrows, and beds
- refreshed operator docs for automation settings, commands, APIs, and current GUI behavior

## Remaining gaps by area

### Settings and configuration depth

- Add more progression caps beyond the current blaze rods, pearls, eyes, arrows, and beds.
- Add caps for how many bots may be active in the nether, stronghold, and portal-room search at once.
- Add portal-strategy settings such as `existing-only`, `build-only`, `cast-only`, or `mixed`.
- Add combat policy settings for beds, bows, shields, melee fallback, and dragon perch handling.
- Add safety policy settings for retreat aggression, exploration spacing, loot contention, and abandonment thresholds.
- Add shared-economy settings for handoffs, chest drop-offs, stockpile behavior, and stash use.
- Add richer preset coverage for solo farming, structure search, logistics, and soak-test modes.
- Add inline defaults, safe ranges, and rationale for every automation setting in the operator UI.

### Team coordination and collaboration

- Add subteams instead of treating the whole instance as one flat collaborative team.
- Add dynamic reassignment when bots die, disconnect, or get stuck repeatedly.
- Add operator-selected leader semantics or explicit squad leaders.
- Add manual claim creation, claim retargeting, claim extension, and claim reassignment.
- Add shared item handoff and rendezvous workflows.
- Add logistics roles and resupply routes.
- Add collision avoidance for teammates working in the same structure or fight area.
- Add richer coordination around portal ownership, fortress lanes, stronghold search lanes, and End-fight roles.
- Add bot quarantine and recovery-aware redistribution when one bot drags the team down.

### Protocol and automation API surface

- Add event streams instead of polling-only snapshots.
- Add stable automation telemetry messages for phase changes, recoveries, stalls, deaths, target churn, and claim churn.
- Add run-history and run-report export APIs.
- Add planner-trace APIs and settings-diff APIs.
- Add explicit force-action APIs for phase override, force target, abandon target, quarantine bot, and release from quarantine.
- Add versioned automation config documents instead of one-off boolean and enum setters for every feature forever.
- Add automation-specific audit events that external operators can subscribe to.
- Add richer permission separation between view, control, settings mutation, and destructive reset actions.

### GUI client and operator workflow

- Add streaming subscriptions instead of 2-second polling.
- Add a real shared-coordination view with maps, overlays, and structure confidence visualization.
- Add a dedicated shared-memory browser instead of only team state and coordination summaries.
- Add bulk bot selection, bulk actions, and filtered team views.
- Add force-action controls such as quarantine, force objective, force target, and phase restart.
- Add better stuck-bot diagnostics that explain what a bot is waiting for and why.
- Add run history, incident history, and post-run summaries.
- Add timeline views for deaths, recoveries, claim churn, and phase transitions.
- Add richer settings forms with inline documentation and validation for every automation option.
- Add operator notifications for repeated failures, death spikes, low-confidence searches, and degraded team health.

### Core automation parity and feature breadth

- Add generalized survival tasks such as `goto`, `follow`, `protect`, `stash`, `withdraw`, `sleep`, `build`, and `farm`.
- Add broader recipe and substitution planning.
- Add stronger fortress solving, portal-room solving, and stronghold confidence handling.
- Add more deliberate End-fight policies, including bed strategy and cooperative role specialization.
- Add richer combat handling for skeletons, creepers, blazes, endermen, and dragon-adjacent hazards.
- Add better portal casting retry and portal recovery behavior.
- Add building and scaffold placement behaviors that go beyond the current beat-game path.
- Add explicit long-run anti-looping and abandonment strategies.

### Observability, testing, and release quality

- Add 10-bot soak-test scenarios across multiple seeds and edge-case worlds.
- Add published completion-rate, death-rate, and stall-rate metrics.
- Add regression fixtures for bad portals, split strongholds, poor End spawns, and problematic fortresses.
- Add compatibility documentation by Minecraft version and connection type.
- Add release policy for experimental versus stable automation features.
- Add migration notes when automation settings, proto messages, or operator workflows change.
- Add a known-issues list and support matrix for automation behavior.

### Documentation and discoverability

- Add tutorial docs for single-bot, team, and recovery flows.
- Add how-to guides for low-resource hardware, private-server safety, and debugging failed runs.
- Add a reference for every automation setting key, proto message, role, objective, phase, and recovery reason.
- Add explanations for the coordination model, quota model, and tradeoffs between safety and speed.
- Add maintainer architecture docs for the coordinator lifecycle, claim model, and planner model.

## Recommended next implementation slices

If the goal is to keep shipping high-value operator-facing work rather than boiling the ocean, the next strong slices are:

1. Streaming automation events plus client subscriptions.
2. Force-action controls for quarantine, phase override, and target override.
3. Richer configuration coverage for portal strategy, combat policy, and progression caps.
4. Shared-memory and map-style coordination views in the client.
5. Run history, audit events, and exportable reports.

## Bottom line

SoulFire now has a real first automation operator surface instead of only CLI hooks and hidden settings. It still does not have full AltoClef-like operator parity, deep automation configurability, or mature collaborative-run observability. That is why this gap audit remains large even after a substantial implementation slice.
