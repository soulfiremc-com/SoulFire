# Soulfire Pathfinding Plan

## Purpose

This document defines the replacement for the current Soulfire pathfinder.
The replacement combines movement coverage from Baritone with collision support from Botcraft.
It does not copy their search compromises.

The target reader is a Soulfire developer who changes navigation, movement, or task behavior.
The plan covers graph state, movement primitives, search, execution, testing, and migration.

## Required outcomes

The replacement must provide these properties:

- Every search compromise has a name, a setting, and a measurable quality bound.
- The exact search finds an optimal route for its declared cost model.
- A production search never removes alternatives without an explicit bounded-search policy.
- A node contains all state that can change its valid future transitions.
- Collision support covers partial blocks without continuous position search.
- Each movement primitive owns planning rules and execution rules.
- A planned transition has world preconditions that the executor can validate.
- The executor reacts to relevant world changes before an action stalls.
- Partial routes have an explicit frontier goal.
- Tests compare production search with an exact search on bounded worlds.
- Tests measure whether generated transitions execute successfully.

## Non-goals

The first replacement does not use one search for every movement system.
Elytra flight and vehicle navigation need separate state and physics models.

The pathfinder does not plan arbitrary large construction projects.
A construction task can request routes to interaction positions.

The pathfinder does not store a speculative world map in each search state.
It tracks resource counts and the support created by the preceding placement.

## Reference comparison

### Baritone

[Baritone](https://github.com/cabaletta/baritone) has the broadest terrain
editing coverage in this comparison. It supports chunk-aware routing, mining,
placing, pillaring, parkour, liquids, climbables, and path segment planning.
AltoClef depends on this coverage instead of implementing another general
pathfinder.

Baritone also contains force-progress rules, heuristic favoring, and partial
route selection that can discard useful alternatives. Those rules often keep
a bot moving, but they make completeness and route quality difficult to
explain. Soulfire must expose a bound or a named frontier reason for each such
tradeoff.

### Botcraft

[Botcraft](https://github.com/adepierre/Botcraft) has strong version-aware
physics and collision handling. Its path state includes a block position and
a floating-point feet height. It supports ladder and scaffolding traversal and
uses a fixed search budget. It focuses on movement through the observed world,
not Baritone-style terrain editing.

The feet height itself is not the main performance risk. Unbounded branching,
repeated collision queries, and unstable state equality cost more. Soulfire
therefore keeps Botcraft's collision accuracy but stores a quantized support
surface. Equal collision surfaces produce equal graph states.

### Mineflayer

[mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder)
provides configurable A* movements, goals, breaking, placement, swimming,
long-distance routing, entity avoidance, and environment-driven replanning.
Its plugin API is a useful reference for caller-defined movement policy.
Soulfire needs stricter transition preconditions because its durable tasks can
outlive one path request.

### AltoClef and UnionClef

[AltoClef](https://github.com/gaucho-matrero/altoclef) is primarily a stateful
task system above Baritone. Its game completion is evidence for the combined
task and movement architecture, not for a separate AltoClef pathfinder.

[UnionClef](https://github.com/3ndetz/unionclef) combines an AltoClef
descendant with Shredder and Tungsten pathfinders. It is useful for current
Fabric compatibility and parkour work, but it shares the same code family.

## Implemented replacement slice

The repository now contains the first production replacement slice:

- immutable route labels and Pareto cost/resource dominance;
- canonical block, support-surface, support-origin, and movement-mode state;
- one tracked navigation resource: usable block items;
- search-local block, collision-cell, and transition caches;
- exact, normal, urgent, and escape search modes with explicit bounds;
- separate complete, partial, unreachable, interrupted, and search-budget
  results;
- no open-set clearing or opposite-direction transition removal;
- ladders, vines, scaffolding, verified water landings, and three-block
  parkour gaps;
- deterministic movement targets with optional bounded camera smoothing;
- live look-ahead validation for movement, placement, climbing, and parkour;
- next-partial-route prefetch and guarded start-state splicing;
- protocol and SDK fields for search policy, expansion budget, fall distance,
  parkour gap, route cost, and search diagnostics.

The search state does not include water buckets, empty buckets, tools, or a
speculative world map. Those values must not return until a transition changes
them and a live controller fixture proves that transition.

## Problems in the replaced implementation

The replaced search periodically removed the complete open set.
It kept only the node with the best goal heuristic.
This operation loses completeness and path-quality meaning.

The replaced resource rule compared only the number of usable blocks.
A costly route with more blocks can suppress a cheaper route with fewer blocks.

The replaced graph omitted a transition that opposed the arrival direction.
This rule can remove valid transitions after a world change or an interaction.

The replaced graph identified a state with a block position and usable-block count.
This state cannot distinguish slabs, stairs, snow layers, climbables, or movement modes.

The replaced graph tracked only whether the previous transition placed the
current floor. It did not model movement mode, support height, or momentum.

The replaced executor calculated the next partial route after the current route ended.
It stopped movement and waited before the next calculation.

The replaced executor usually learned about a changed path after an action stalled.
It did not validate the remaining route after nearby block updates.

The replaced rotation jitter changed the target angle on each movement tick.
This behavior reduces precision near ledges, scaffolds, liquids, and portals.

## Design principles

### Sound transitions before broad coverage

A generated transition must be executable from its declared start state.
The project will not add a movement primitive without execution tests.

### Explicit search quality

The search result must report its search mode and quality bound.
A timeout must not silently change the meaning of the configured costs.

### Canonical state

The graph uses discrete states.
It does not use an arbitrary floating-point feet position as a node key.

### Separate planning from policy

Movement primitives describe possible transitions.
A path policy describes permitted actions and their ordered costs.

### Local accuracy and coarse distance planning

The local graph uses exact loaded-world collision data.
A separate coarse layer chooses regions and useful world frontiers.

### Humanization is not navigation

Visual smoothing can change camera motion inside a safe tolerance.
It must not change movement targets or block-interaction precision.

## Architecture

The replacement has six layers:

1. `NavigationSnapshot` provides immutable world data for one search.
2. `NavigationCellCache` converts block collision data into canonical cells.
3. `MovementPrimitive` generates typed transitions between navigation states.
4. `RouteSearch` finds an exact, bounded, or frontier route.
5. `RoutePlan` stores transitions, preconditions, costs, and search metadata.
6. `RouteExecutor` validates and executes the plan while it calculates the next segment.

The task API continues to submit a goal and a path policy.
The task API does not depend on a specific search implementation.

## Navigation state

The state contains only data that can change future transitions:

```java
public record NavigationState(
  SFVec3i blockPosition,
  SupportSurface supportSurface,
  SupportOrigin supportOrigin,
  MovementMode movementMode,
  ResourceState resources
) {}
```

### Block position

The block position identifies the cell that contains the player's feet.
It does not identify a complete standing position.

### Support surface

A support surface identifies a canonical collision surface in a navigation cell.
Most cells contain one support surface.

The cache derives support surfaces from collision shapes.
It stores fixed-point coordinates so equal surfaces have stable value identity.

This model supports these blocks without continuous search:

- slabs;
- stairs;
- snow layers;
- carpets;
- beds;
- scaffolding;
- trapdoors;
- farmland;
- path blocks;
- collision shapes from version-specific blocks.

### Movement mode

The first implementation supports these modes:

- `GROUND`;
- `SWIMMING`;
- `CLIMBING`.

A mode exists only when it changes valid transitions.

Crawling and airborne modes stay out of the state until tested controllers
consume them.

### Momentum class

The current graph does not store momentum. Each parkour primitive owns its
bounded run-up and ends on verified support.

Add a small terminal momentum class only when chained-jump fixtures prove
that it changes the next transition. Elytra flight uses a separate planner
with a continuous motion model.

### Resource state

The resource state tracks navigation resources, not the complete inventory.
The first implementation tracks only usable block items. Water and empty
bucket counts stay out of the node until a tested bucket movement needs them.

Resource dominance compares cost and resources together.
A state dominates another state only if it is no worse for every tracked value.

### Speculative support without a world overlay

The search does not include a map of speculative block changes. Benchmarks
showed that even a canonical bounded map produced too many different bridge
and pillar states.

`SupportOrigin.PLACED` records that the preceding transition created the
current floor. This small value is enough to continue a bridge or pillar. The
usable-block count proves that the route can finish the complete sequence.

The search does not backtrack through older speculative edits. The executor
validates block actions against the live world and replans when an edit fails
or changes a later precondition.

## Navigation cells and caching

A navigation cell stores derived data for one block position:

- collision boxes;
- support surfaces;
- free body volumes;
- fluid data;
- climbable data;
- hazard data;
- interaction data;
- the stable block state read by the current search snapshot.

The cache invalidates a cell when its block or a collision neighbor changes.
The cache key includes the dimension and block position.

A persistent terrain cache stores coarse data for unloaded chunks.
It stores passable regions, water, hazards, and known portals.
It does not claim exact movement support for unloaded blocks.

## Movement primitive contract

Each primitive generates one or more `NavigationTransition` values.

```java
public interface MovementPrimitive {
  void generate(
    NavigationSnapshot snapshot,
    NavigationState from,
    PathPolicy policy,
    TransitionConsumer output
  );

  MovementController createController(NavigationTransition transition);
}
```

A transition contains these values:

```java
public record NavigationTransition(
  NavigationState from,
  NavigationState to,
  MovementPrimitive primitive,
  CostVector cost,
  WorldPreconditions preconditions,
  WorldEffects plannedEffects
) {}
```

The planning rules and controller belong to the same primitive.
This ownership prevents planning support without execution support.

## Movement coverage

### Ground and collision movement

- Walk on full and partial collision surfaces.
- Move diagonally after collision-shape validation.
- Step up to the current player step height.
- Jump up one block.
- Move below a low ceiling when the player pose permits it.
- Move through open doors and fence gates.
- Open permitted doors and fence gates.

### Vertical movement

- Climb ladders and vines.
- Descend ladders and vines.
- Catch a climbable during a controlled fall.
- Move up and down scaffolding.
- Pillar with a permitted placeable block.
- Dig downward with fluid and falling-block safety checks.

### Falling and liquids

- Use configurable safe falls.
- Fall any supported distance into verified still water.
- Use a water bucket for a controlled long fall.
- Swim horizontally and vertically.
- Enter and leave water through valid collision geometry.
- Avoid liquid creation during unsafe breaking.
- Avoid uncontrolled lava transitions.

### Parkour

- Jump one-block, two-block, and three-block gaps when physics permits it.
- Support diagonal gap jumps.
- Support a run-up transition.
- Support parkour placement.
- Add a small terminal momentum class when chained-jump fixtures require it.

### Terrain changes

- Break permitted blocks with tool and durability costs.
- Place permitted support blocks.
- Protect caller-defined blocks from breaking and placement.
- Reserve interaction faces for block actions.
- Validate falling-block and fluid neighbors before a break.

### Separate planners

These movement types use separate planners after the ground replacement is stable:

- elytra flight;
- boats;
- minecarts;
- mounted entities;
- creative and spectator flight.

## Cost model

The path policy uses hard constraints and an ordered cost vector.

```java
public record CostVector(
  double expectedDamage,
  int irreversibleChanges,
  int placedBlocks,
  int brokenBlocks,
  double durationCost
) {}
```

The default policy compares values in this order:

1. Expected damage.
2. Irreversible world changes.
3. Duration, including configured break and placement penalties.
4. Placed blocks as a tie-breaker.
5. Broken blocks as a tie-breaker.

Putting raw block counts before duration would make any no-edit detour beat a
short, sensible bridge. The configurable duration penalties express resource
preference without turning it into an absolute ban.

A specialized policy can use a different order.
The order remains explicit in the policy type.

These conditions are hard constraints:

- protected blocks;
- forbidden block actions;
- unsupported placement;
- lethal hazards outside the permitted risk budget;
- invalid interaction reach;
- unsafe falling-block or fluid release.

The pathfinder does not use a very large numeric penalty for a forbidden action.

## Search modes

### Exact A*

Exact A* uses an admissible heuristic and no destructive pruning.
It is the reference search for bounded test worlds.

Goal distance is multiplied by a conservative route-cost-per-block value.
The value comes from the cheapest supported displacement, a three-block
vertical fall. This keeps the geometric heuristic below known movement costs
instead of overstating progress on cheap downward movement.

The search supports state reopening.
It does not assume that the first route to a state is final.

### Anytime bounded A*

The production search starts with an explicit heuristic weight.
It returns the first bounded route and improves that route while time remains.

The initial modes are:

| Mode | Initial weight | Use |
| --- | ---: | --- |
| `PRECISION` | 1.0 | Building, liquids, and dangerous terrain |
| `NORMAL` | 1.2 | General travel |
| `URGENT` | 1.5 | Time-sensitive movement |
| `ESCAPE` | Policy-specific | Immediate survival movement |

The result reports the final weight and known quality bound.
The caller can reject a route above its permitted bound.

### Frontier search

Frontier search has an explicit loaded-world frontier goal.
It does not return an arbitrary node because the final search expired.

A useful frontier must meet these conditions:

- It makes measurable progress toward the coarse route.
- It has a safe terminal support state.
- It does not end during a destructive transition.
- It exposes an unloaded or newly loaded region.
- It is not in the recent frontier loop history.

### Coarse route search

The coarse search operates on known regions and chunk frontiers.
It uses persistent terrain summaries.

The local search follows one coarse segment at a time.
The executor calculates the next local segment before the current segment ends.

## Route results

A route result contains these fields:

- the complete transition list;
- the structured total cost;
- the search mode;
- the final quality bound;
- the number of expanded states;
- the number of generated transitions;
- the elapsed calculation time;
- whether the result ends at a frontier.

An expired search with no valid route returns no route.
It does not return an unqualified best-effort path.

## Execution and replanning

The executor validates each transition before it starts.
It also validates a bounded look-ahead window after each relevant world update.

The executor starts the next-segment calculation before the current segment
ends. It prefetches only after the remaining suffix contains no inventory-
changing block action. It splices the segment only when the player reaches the
predicted start block. Live action preconditions reject stale geometry.

The executor replans for these events:

- a changed block affects a route precondition;
- a chunk on the route loads or unloads;
- an entity blocks a required volume;
- the player leaves the expected movement corridor;
- an action exceeds its progress deadline;
- a block action changes the expected usable-block count;
- a control suspension invalidates motion assumptions.

The executor does not wait one second before every replan.
It waits for a stable support state only when the active primitive requires one.

### Rotation control

Precise movement and block actions use deterministic targets.
The path request can enable visual camera smoothing.

Camera smoothing must stay inside the primitive's safe angle tolerance.
The executor does not sample a new random target offset on each tick.

## Dynamic hazards

Static path cost covers known hazard blocks and nearby hostile entities.
A local safety controller handles hazards that need tick-level reactions.

The local controller can interrupt navigation for these hazards:

- projectiles;
- explosions;
- dragon breath;
- immediate melee threats;
- fire and lava contact;
- an uncontrolled fall.

After the interruption, the executor validates the saved plan.
It resumes the plan only when its preconditions remain valid.

## Public path policy

The path request must expose these policy groups:

- search mode and maximum quality bound;
- calculation deadline and expansion budget;
- damage and fall budget;
- break and placement permissions;
- protected blocks and areas;
- allowed movement primitives;
- sprint and parkour permissions;
- fluid rules;
- precision rotation rules;
- frontier behavior.

The SDK can select `PRECISION` for portal work.
It does not need global instance-setting changes.

## Testing strategy

### Search tests

- Compare exact A* with Dijkstra on small generated graphs.
- Compare bounded search cost with exact A* cost.
- Test state reopening after a cheaper route appears.
- Test resource dominance with cost and resource tradeoffs.
- Test long pillars and bridges under the default expansion budget.
- Test that support provenance does not create history-dependent states.
- Test cancellation without frontier corruption.
- Test deterministic results for a fixed snapshot and policy.

### Movement tests

- Use a generated micro-world for each supported block shape.
- Test every orientation and relevant block-state property.
- Test the controller from each valid transition start state.
- Test that invalid transitions are not generated.
- Test world precondition invalidation.

### Integration tests

- Store NBT fixtures for stairs, slabs, snow, climbables, water, hazards, and parkour.
- Execute routes on a real local Minecraft fixture.
- Change route blocks during movement.
- Suspend and resume control during each movement family.
- Replay retained failed worlds from beat-game runs.

### Performance tests

Record these values for each fixture:

- expanded states;
- generated transitions;
- navigation-cell cache hit rate;
- peak open-set size;
- calculation duration at p50, p95, and p99;
- route cost and bounded-search ratio;
- transition execution success rate.

The primary movement quality metric is:

```text
successfully executed generated transitions / all executed generated transitions
```

## Migration

The replacement keeps the external goal and task entry points during development.
It does not keep the old graph as a fallback after migration.

The migration sequence is:

1. Add canonical state, costs, route metadata, and exact search.
2. Adapt current movements into typed primitives.
3. Add collision-surface support and missing movement primitives.
4. Add bounded search and explicit frontier search.
5. Replace the executor and add proactive invalidation.
6. Add coarse routes and persistent terrain summaries.
7. Move all task callers to the new path policy.
8. Remove the old graph, old pruning setting, and old executor.
9. Make the new fixture suite a required build gate.

## Completion criteria

The replacement is complete when all these statements are true:

- No production caller uses the old graph or executor.
- No search clears the frontier to force goal progress.
- Exact search passes the graph oracle suite.
- Production search obeys its configured quality bound.
- All declared movement primitives have live execution fixtures.
- The pathfinder supports the listed Baritone and Botcraft ground features.
- Partial routes splice without a planned one-second stop.
- Relevant block updates invalidate a route before its affected action starts.
- Precision policies use deterministic movement targets.
- Retained beat-game worlds no longer reproduce the known portal-route failures.
