# Soulfire Game-Beating Plan

## Purpose

This document records the game-beating investigation and the required reliability work.
It connects progression planning with pathfinding, local skills, recovery, and release gates.

The target reader is a Soulfire developer who changes the beat-game SDK or its bot tasks.

## Main finding

Soulfire has a broad progression planner.
Its main weakness is reliable execution of mechanically coupled Minecraft actions.

The current SDK can select resources, phases, portal strategies, and recovery actions.
It does not yet complete a qualified fixed-seed survival gate.

The current live evidence shows repeated failure near portal construction and entry.
This failure occurs before the later stronghold and End stages receive comparable live coverage.

## Current architecture

Soulfire owns these functions near the Minecraft connection:

- protocol state;
- world and entity observations;
- inventory state;
- control arbitration;
- pathfinding;
- direct actions;
- reusable tasks.

The TypeScript beat-game package owns these functions:

- progression phases;
- resource requirements;
- portal strategy;
- stronghold strategy;
- safety policy;
- world memory;
- checkpoints;
- multi-bot coordination.

This ownership boundary is valid.
The boundary becomes a problem when one mechanical skill requires many remote direct actions.

## Evidence from the retained Nether run

The retained run covers almost three days of checkpoint history.
Its last checkpoint contains these values:

- revision `31601`;
- phase `ENTER_NETHER`;
- current action `build-and-enter-nether`;
- no retained portal memory;
- 64 unreachable memories;
- 6 remembered death positions.

The Soulfire log contains these repeated errors:

- 5,873 occurrences of `No route found to the goal`;
- 136 occurrences of `Bot action was replaced`.

The completed-action history contains repeated food, bucket, recovery, and portal actions.
The bot did not retain stable portal progress at the end of the run.

This evidence identifies a closed-loop execution problem.
It does not identify a missing high-level phase graph.

## Why AltoClef completes the game

[AltoClef](https://github.com/gaucho-matrero/altoclef) combines three mature
layers:

1. Baritone provides movement and terrain changes.
2. AltoClef provides stateful Minecraft tasks.
3. Priority task chains provide tick-level survival reactions.

AltoClef reads exact client state on each tick.
Its task runner can interrupt a task without removing that task's private progress.

Its reliable portal task retains these values:

- portal origin and axis;
- completed frame state;
- lava-search blacklist;
- protected obsidian;
- required items;
- progress and recovery timers.

The portal task reads the world again after each action.
It repairs one missing frame part at a time.

AltoClef also contains an older fast portal-casting task.
Its source describes that task as unreliable.
Water spills and pathfinder stalls reduce its success rate.

The methodical portal task replaces speed with persistent state and local checks.
Soulfire needs the same property.

## Qualification of the comparison

AltoClef is archived.
Its published completion claim applies to its supported Minecraft 1.18 environment.

It does not prove reliable completion on every current version or seed.
It does prove that its task and movement architecture completed a historical autonomous run.

Soulfire targets newer protocol and game versions.
Its wider compatibility increases the required collision, item, combat, and progression coverage.

## Other GitHub projects

### UnionClef

[UnionClef](https://github.com/3ndetz/unionclef) continues the AltoClef code
family on newer Minecraft versions.
It combines an AltoClef descendant with Baritone-derived and parkour pathfinders.

It is useful as a compatibility reference.
It is not an independent proof of a different game-beating architecture.

### SeekerCraft

[SeekerCraft](https://github.com/XJungit/seeker-craft) uses an external
reasoning system and a Rust protocol client.
It exposes typed tools and keeps reactive modes near the game tick.

Its published progress reaches the earlier survival and diamond stages.
Its Nether and finale stages remain incomplete in its current progress table.

This project supports one Soulfire design choice.
An external planner can work when local typed skills own immediate reactions.

### MineDojo and Voyager

[MineDojo](https://github.com/MineDojo/MineDojo) defines dragon completion as
a benchmark task.
It does not provide an agent that reliably passes that benchmark.

[Voyager](https://github.com/MineDojo/Voyager) demonstrates open-ended
exploration and technology-tree progress.
It does not demonstrate reliable full game completion.

### Mineflayer-based language-model bots

These bots often make broad semantic decisions.
Their exact mechanical skills and recovery behavior are less deterministic.

They are useful references for tool design and memory.
They are not replacements for a local execution layer.

[Project-MCSR](https://github.com/NatsuDragneelX/Project-MCSR) lists portal,
stronghold, and dragon features, but the same README calls the repository an
early-game foundation and lists Nether and End navigation as future work. It
does not publish a reproducible autonomous completion gate.

[Tumph/enderdragon](https://github.com/Tumph/enderdragon) describes a goal of
speedrunning the dragon, but its repository is a Mindcraft language-model bot
fork. Its README documents the general Mineflayer agent platform, not a
qualified end-to-end completion run.

These repositories show why feature lists are not sufficient evidence. A
game-beating claim needs a fixed version, seed policy, recorded completion
condition, retry policy, and repeated run results.

## Main gaps in Soulfire

### No qualified completion gate

The repository contains fake-driver coverage and a manual live worker.
It does not yet require repeated fixed-seed survival completion.

A planner test can prove a phase transition.
It cannot prove that Minecraft accepted a placement or movement sequence.

### Portal progress is not durable enough

The SDK selects a portal frame and performs a serial action sequence.
It publishes the portal after construction.
It saves portal memory after entry.

An interruption before these points can remove the selected origin and partial frame progress.
The next attempt can select another workspace and repeat resource collection.

### Mechanical loops are too remote

Portal casting needs frequent world reads, precise positioning, liquid checks, and recovery.
The current implementation composes these operations across remote driver calls.

The TypeScript strategy can continue to select the portal method.
A local reusable task must own the mechanical construction loop.

### The old pathfinder did not support enough terrain states

The replaced baseline lacked complete climbable, partial-collision, long-fall,
and parkour support. It also lacked planned next-segment splicing and proactive
route invalidation.

These gaps create resource and movement churn during long survival tasks.
The first replacement slice now covers ladders, vines, scaffolding, canonical
support surfaces, verified water landings, three-block parkour, look-ahead
validation, and partial-route prefetch. Live game-beating fixtures still need
to qualify this coverage.

### Precision and humanization conflicted

The replaced movement code sampled rotation jitter on each tick.
That behavior reduced precision during portal, scaffold, bucket, and ledge
actions.

The path request now has a deterministic precision policy.
Visual camera smoothing must stay inside a safe movement tolerance.

### Survival reactions and task progress are separate

The SDK has safety and recovery decisions.
Some reactions require tick-level latency and resumable local task state.

A local safety controller must handle immediate hazards.
The high-level planner must retain the interrupted objective and its durable progress.

## Target architecture

The game-beating system has four control layers:

1. The progression planner selects phases, resources, targets, and policies.
2. Durable skills own mechanically coupled Minecraft operations.
3. The pathfinder provides validated transitions and route execution.
4. The safety controller handles immediate threats and returns control after the threat ends.

The planner remains in TypeScript.
Generic durable skills remain near the Minecraft connection.

## Durable skill contract

A durable skill must expose these values:

- a stable skill identifier;
- current substep;
- selected world targets;
- protected blocks and items;
- completed world changes;
- required resources;
- retry and blacklist memory;
- an observation that proves completion;
- a resumable checkpoint payload.

A safety interruption must not remove this state.
A process restart must restore enough state to inspect and continue the skill.

## Portal construction replacement

The portal strategy remains in the beat-game planner.
The mechanical loop moves into a durable portal-construction skill.

The skill stores these values after each successful substep:

- workspace origin;
- portal axis;
- complete target frame;
- observed frame blocks;
- water source and flow state;
- candidate lava sources;
- rejected lava sources;
- bucket state;
- ignition state;
- portal-interior state;
- entry attempt state.

The skill uses a methodical default sequence:

1. Select and reserve one workspace.
2. Validate escape routes and liquid containment.
3. Protect the workspace and completed frame blocks.
4. Cast or place one frame block.
5. Observe the affected blocks.
6. Save the updated skill state.
7. Repair missing or incorrect blocks.
8. Ignite the complete frame.
9. Observe portal blocks.
10. Enter and confirm the dimension change.

The skill does not select another workspace after a normal interruption.
It abandons a workspace only after a recorded terminal reason.

## Required durable skills

The game-beating path needs durable local skills for these operations:

- portal construction and entry;
- safe liquid collection and placement;
- protected structure assembly;
- stronghold triangulation observations;
- safe Nether corridor travel;
- blaze combat and rod collection;
- ender pearl collection and bartering;
- End portal preparation and entry;
- End crystal destruction;
- dragon combat and breath avoidance;
- death recovery and item retrieval.

Each skill needs a real fixture and restart test.

## Safety controller

The safety controller evaluates immediate threats on each game tick.
It can claim movement and combat control for these conditions:

- lethal fall;
- projectile impact path;
- nearby explosion;
- lava or fire contact;
- dragon breath;
- hostile melee range;
- critical health or hunger.

The controller saves the interrupted skill handle.
It returns control only after the skill validates its saved plan.

The controller does not make progression decisions.

## World memory

World memory needs stable records for these objects:

- portals and incomplete portal workspaces;
- stronghold measurements and candidate intersections;
- fortresses and bastions;
- blaze spawners;
- safe corridors and shelters;
- dangerous and unreachable positions;
- death positions and item-expiry times;
- End entry points and dragon-fight state.

Each record includes a dimension, observation time, confidence, and invalidation reason.

An incomplete structure is memory.
It must not disappear because the action that created it did not finish.

## Pathfinding dependency

The pathfinding replacement in `PATHFINDING_PLAN.md` is part of the game-beating gate.
The most important features for game completion are:

- canonical support surfaces;
- climbables and scaffolding;
- deterministic precision policies;
- explicit protected blocks;
- proactive world-change invalidation;
- next-segment calculation and splicing;
- safe water and long-fall movement;
- bounded-search quality;
- persistent coarse terrain memory.

Portal fixtures must use the `PRECISION` search mode.
Long travel can use the `NORMAL` mode.
Immediate escape uses a dedicated safety policy.

## Test pyramid

### Planner tests

Fake-driver tests continue to cover progression decisions, retries, checkpoints, and team coordination.
These tests do not count as mechanical completion evidence.

### Skill fixture tests

Each durable skill runs against a staged local Minecraft fixture.
The fixture changes timing and selected block states between runs.

Each skill fixture includes interruption and restart cases.

### Phase gates

The live suite has separate gates for these phases:

1. Spawn to stable iron equipment.
2. Portal construction and Nether entry.
3. Fortress discovery and blaze rods.
4. Pearl acquisition and Overworld return.
5. Stronghold location and End entry.
6. Crystal destruction and dragon defeat.
7. Exit portal entry and completion observation.

A failure preserves the world, logs, events, checkpoint, seed, and task state.

### Full completion gate

The full gate starts from a clean fixed-seed world.
It uses one bot and no staged progression resources.

The gate records completion time, deaths, route failures, replans, resource churn, and skill retries.

One successful run is not sufficient.
The release gate requires a repeated-success threshold.

## Reliability metrics

The suite records these metrics:

- completion rate;
- median and p95 completion time;
- deaths per run;
- path search failures per kilometer;
- repeated unreachable targets;
- portal workspaces selected per run;
- lava and water bucket churn;
- skill retries by reason;
- control interruptions;
- checkpoint recovery success;
- route transition execution success.

The initial fixed-seed target is five consecutive single-bot completions.
The target increases after the fixture and runtime stabilize.

## Implementation order

1. Add the fixed-seed phase-two portal fixture.
2. Add durable incomplete-portal memory.
3. Implement the local resumable portal skill.
4. Add deterministic precision path policies.
5. Replace the pathfinder according to `PATHFINDING_PLAN.md`.
6. Add local hazard preemption and skill resume.
7. Add fortress, blaze, and pearl phase fixtures.
8. Add stronghold and End-entry fixtures.
9. Add dragon-combat fixtures.
10. Enable repeated clean-world completion runs.
11. Make the approved reliability threshold a release gate.

## Completion criteria

The game-beating work is complete when all these statements are true:

- A process restart preserves an incomplete portal workspace and its frame state.
- Safety interruptions preserve and resume the active durable skill.
- Portal work uses deterministic precision movement.
- Each game phase passes its real Minecraft fixture.
- Failed worlds reproduce through retained artifacts.
- A clean fixed-seed run defeats the dragon and enters the exit portal.
- The repeated-success threshold passes within the approved duration budget.
- The release workflow runs the required single-bot completion gate.
- The documented supported version matrix passes its approved conformance suite.
