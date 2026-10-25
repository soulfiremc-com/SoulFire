/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.pathfinding;

import com.google.common.base.Stopwatch;
import com.soulfiremc.server.pathfinding.cost.Costs;
import com.soulfiremc.server.pathfinding.execution.BlockPlaceAction;
import com.soulfiremc.server.pathfinding.execution.ClimbAction;
import com.soulfiremc.server.pathfinding.execution.GapJumpAction;
import com.soulfiremc.server.pathfinding.execution.JumpAndPlaceBelowAction;
import com.soulfiremc.server.pathfinding.execution.MovementAction;
import com.soulfiremc.server.pathfinding.execution.WorldAction;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;
import com.soulfiremc.server.pathfinding.graph.NavigationChunk;
import com.soulfiremc.server.util.structs.CancellationToken;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Finds routes with Anytime Repairing A* over an immutable navigation
/// snapshot. Each epsilon stage reuses discovered costs through `OPEN`,
/// `CLOSED`, and `INCONS`. A result reports its certified bound rather than
/// treating heuristic inflation as proof of route quality.
@Slf4j
public record RouteFinder(
  MinecraftGraph baseGraph,
  GoalScorer scorer,
  Executor executor
) {
  private static final long IMPROVEMENT_BUDGET_MILLIS = 50;
  private static final int MAX_NODES_AFTER_LEVEL_BOUNDARY = 2_048;
  private static final double EPSILON_STEP = 0.5;
  private static final double SEARCH_BUDGET_MINIMUM_PROGRESS_FRACTION = 0.25;
  private static final double SEARCH_BUDGET_MAXIMUM_PROGRESS_BLOCKS = 8;

  public RouteFinder(MinecraftGraph baseGraph, GoalScorer scorer) {
    this(baseGraph, scorer, ForkJoinPool.commonPool());
  }

  public CompletableFuture<RouteSearchResult> findRouteFuture(NodeState from) {
    var cancellationToken = new CancellationToken();
    var future = CompletableFuture.supplyAsync(
      () -> findRouteSync(from, cancellationToken),
      executor
    );
    future.whenComplete((_, _) -> {
      if (future.isCancelled()) {
        cancellationToken.cancel();
      }
    });
    return future;
  }

  private RouteSearchResult findRouteSync(
    NodeState suppliedStart,
    CancellationToken cancellationToken
  ) {
    var stopwatch = Stopwatch.createStarted();
    var constraint = baseGraph.pathConstraint();
    var searchMode = constraint.searchMode();
    var start = baseGraph.snapshot().stateAt(
      suppliedStart.blockPosition(),
      suppliedStart.resources(),
      SupportOrigin.WORLD
    );
    var domain = new MinecraftSearchDomain(scorer.snapshot());
    var search = new AnytimeRepairingAStar<>(
      start,
      domain,
      new AnytimeRepairingAStar.Configuration(
        searchMode.initialEpsilon(),
        constraint.maximumQualityBound(),
        EPSILON_STEP,
        System.nanoTime()
          + TimeUnit.SECONDS.toNanos(constraint.expireTimeout()),
        TimeUnit.MILLISECONDS.toNanos(IMPROVEMENT_BUDGET_MILLIS),
        constraint.maximumExpandedStates(),
        MAX_NODES_AFTER_LEVEL_BOUNDARY,
        () -> Thread.currentThread().isInterrupted()
          || cancellationToken.isCancelled()
      )
    );
    var outcome = search.search();
    var boundaryDiagnostics = search.boundaryDiagnostics();
    stopwatch.stop();

    var routeCost = routeCost(outcome.path());
    var metadata = new RouteSearchMetadata(
      searchMode,
      outcome.certifiedQualityBound(),
      constraint.maximumQualityBound(),
      searchMode.initialEpsilon(),
      outcome.finalEpsilon(),
      routeCost,
      outcome.expandedStates(),
      outcome.generatedTransitions(),
      stopwatch.elapsed().toMillis(),
      frontierReason(outcome.stopReason()),
      outcome.repairIterations(),
      outcome.repairedInconsistentStates(),
      Math.max(0, outcome.incumbentCosts().size() - 1),
      baseGraph.snapshot().worldRevision(),
      domain.unavailableChunks(outcome.endpoint()).stream()
        .sorted()
        .toList()
    );
    RouteSearchResult result = switch (outcome.status()) {
      case FOUND -> new FoundRouteResult(
        actions(outcome.path()),
        metadata,
        new GoalCompletion(
          Objects.requireNonNull(outcome.endpoint()),
          outcome.path().isEmpty() ? List.of() : outcome.path().getLast().actions()
        )
      );
      case PARTIAL -> new PartialRouteResult(
        actions(outcome.path()),
        requireEndpoint(outcome.endpoint()),
        metadata
      );
      case WORLD_DATA_PENDING -> new WorldDataPendingResult(
        requireEndpoint(outcome.endpoint()),
        metadata
      );
      case UNREACHABLE -> new NoRouteFoundResult(metadata);
      case SEARCH_LIMIT -> new SearchLimitReachedResult(metadata);
      case INTERRUPTED -> new SearchInterruptedResult(metadata);
      case QUALITY_BOUND_NOT_MET ->
        new QualityBoundNotMetResult(metadata);
    };

    var closestExpandedPosition = boundaryDiagnostics
      .closestExpandedState()
      .map(NodeState::blockPosition)
      .map(SFVec3i::formatXYZ)
      .orElse("none");
    var closestExpandedHeuristic = boundaryDiagnostics
      .closestExpandedHeuristic()
      .isPresent()
      ? Double.toString(
        boundaryDiagnostics.closestExpandedHeuristic().getAsDouble()
      )
      : "none";
    log.info(
      "ARA* finished with {} after {}ms, {} expansions, {} repairs, certified bound {}, final epsilon {}, closest generated/expanded states {}/{} at heuristics {}/{}, {}/{}/{} reached/progressive/valid boundaries, and {}/{} graph cache hits",
      result.getClass().getSimpleName(),
      metadata.elapsedMillis(),
      metadata.expandedStates(),
      metadata.repairIterations(),
      metadata.qualityBound(),
      metadata.finalEpsilon(),
      boundaryDiagnostics.closestState().blockPosition(),
      closestExpandedPosition,
      boundaryDiagnostics.closestHeuristic(),
      closestExpandedHeuristic,
      boundaryDiagnostics.reachedBoundaries(),
      boundaryDiagnostics.progressiveBoundaries(),
      boundaryDiagnostics.validBoundaries(),
      baseGraph.transitionCache().hits(),
      baseGraph.transitionCache().hits()
        + baseGraph.transitionCache().misses()
    );
    return result;
  }

  private static SFVec3i requireEndpoint(@Nullable NodeState endpoint) {
    if (endpoint == null) {
      throw new IllegalStateException("A partial route has no endpoint");
    }
    return endpoint.blockPosition();
  }

  private static FrontierReason frontierReason(
    AnytimeRepairingAStar.StopReason reason
  ) {
    return switch (reason) {
      case NONE -> FrontierReason.NONE;
      case FRONTIER -> FrontierReason.LEVEL_BOUNDARY;
      case DEADLINE -> FrontierReason.SEARCH_DEADLINE;
      case EXPANSION_BUDGET -> FrontierReason.SEARCH_BUDGET;
    };
  }

  private static List<WorldAction> actions(
    List<GraphInstructions> transitions
  ) {
    return transitions.stream()
      .flatMap(transition -> transition.actions().stream())
      .toList();
  }

  private static RouteCost routeCost(
    List<GraphInstructions> transitions
  ) {
    var result = RouteCost.ZERO;
    for (var transition : transitions) {
      result = result.add(transition.routeCost());
    }
    return result;
  }

  private final class MinecraftSearchDomain implements
    AnytimeRepairingAStar.Domain<NodeState, GraphInstructions> {
    private final GoalScorer goal;
    private final Map<NodeState, Set<NavigationChunk>> boundaryChunks =
      new HashMap<>();

    private MinecraftSearchDomain(GoalScorer goal) {
      this.goal = goal;
    }

    @Override
    public double heuristic(NodeState state) {
      return goal.computeScore(
        baseGraph,
        state.blockPosition(),
        List.of()
      ) * Costs.HEURISTIC_COST_PER_BLOCK;
    }

    @Override
    public boolean isGoal(
      NodeState state,
      @Nullable GraphInstructions incomingTransition
    ) {
      return goal.isFinished(
        state,
        incomingTransition == null
          ? List.of()
          : incomingTransition.actions()
      );
    }

    @Override
    public boolean expand(
      NodeState state,
      Consumer<
        AnytimeRepairingAStar.Transition<NodeState, GraphInstructions>
      > output
    ) {
      var expansion = baseGraph.insertActions(
        state,
        instructions -> createTransition(
          state,
          instructions,
          output
        )
      );
      if (expansion.reachedLevelBoundary()) {
        boundaryChunks.put(state, expansion.unavailableChunks());
      }
      return expansion.reachedLevelBoundary();
    }

    private Set<NavigationChunk> unavailableChunks(
      @Nullable NodeState state
    ) {
      return state == null
        ? Set.of()
        : boundaryChunks.getOrDefault(state, Set.of());
    }

    private void createTransition(
      NodeState state,
      GraphInstructions instructions,
      Consumer<
        AnytimeRepairingAStar.Transition<NodeState, GraphInstructions>
      > output
    ) {
      if (
        instructions.requiresOneBlock()
          && state.usableBlockItems() < 1
      ) {
        return;
      }
      var newBlockCount = state.usableBlockItems()
        + instructions.deltaUsableBlockItems();
      if (newBlockCount < 0) {
        return;
      }

      var transitionCost = instructions.routeCost();
      var resources = state.resources().addUsableBlockItems(
        instructions.deltaUsableBlockItems()
      );
      var target = baseGraph.snapshot().stateAt(
        instructions.blockPosition(),
        resources,
        supportOrigin(instructions)
      );
      output.accept(new AnytimeRepairingAStar.Transition<>(
        target,
        transitionCost.optimizationCost(),
        instructions
      ));
    }

    @Override
    public Object dominanceKey(NodeState state) {
      return StateIdentity.of(state);
    }

    @Override
    public boolean dominates(NodeState left, NodeState right) {
      return left.resources().dominates(right.resources());
    }

    @Override
    public boolean isValidFrontier(
      NodeState state,
      @Nullable GraphInstructions incomingTransition
    ) {
      if (incomingTransition == null) {
        return false;
      }
      if (
        state.movementMode() == MovementMode.GROUND
          && state.supportSurface().equals(SupportSurface.NONE)
      ) {
        return false;
      }
      if (incomingTransition.actions().isEmpty()) {
        return false;
      }
      return switch (incomingTransition.actions().getLast()) {
        case MovementAction _, ClimbAction _, GapJumpAction _,
             JumpAndPlaceBelowAction _ -> true;
        default -> false;
      };
    }

    @Override
    public boolean isValidSearchBudgetFrontier(
      NodeState state,
      @Nullable GraphInstructions incomingTransition,
      double startHeuristic,
      double stateHeuristic
    ) {
      if (!isValidFrontier(state, incomingTransition)) {
        return false;
      }
      var minimumProgress = Math.min(
        startHeuristic * SEARCH_BUDGET_MINIMUM_PROGRESS_FRACTION,
        Costs.HEURISTIC_COST_PER_BLOCK
          * SEARCH_BUDGET_MAXIMUM_PROGRESS_BLOCKS
      );
      return startHeuristic - stateHeuristic >= minimumProgress;
    }
  }

  private static SupportOrigin supportOrigin(
    GraphInstructions instructions
  ) {
    var floor = instructions.blockPosition().sub(0, 1, 0);
    return instructions.actions().stream().anyMatch(action -> switch (action) {
      case BlockPlaceAction place ->
        place.blockPosition().equals(floor);
      case JumpAndPlaceBelowAction place ->
        place.blockPlacePosition().equals(floor);
      default -> false;
    }) ? SupportOrigin.PLACED : SupportOrigin.WORLD;
  }

  private record StateIdentity(
    SFVec3i blockPosition,
    SupportSurface supportSurface,
    SupportOrigin supportOrigin,
    MovementMode movementMode
  ) {
    private static StateIdentity of(NodeState state) {
      return new StateIdentity(
        state.blockPosition(),
        state.supportSurface(),
        state.supportOrigin(),
        state.movementMode()
      );
    }
  }

  public enum FrontierReason {
    NONE,
    LEVEL_BOUNDARY,
    SEARCH_DEADLINE,
    SEARCH_BUDGET
  }

  public record RouteSearchMetadata(
    RouteSearchMode searchMode,
    double qualityBound,
    double requiredQualityBound,
    double initialEpsilon,
    double finalEpsilon,
    RouteCost routeCost,
    long expandedStates,
    long generatedTransitions,
    long elapsedMillis,
    FrontierReason frontierReason,
    int repairIterations,
    int repairedInconsistentStates,
    int incumbentImprovements,
    long worldRevision,
    List<NavigationChunk> unavailableChunks
  ) {
    public RouteSearchMetadata {
      unavailableChunks = List.copyOf(unavailableChunks);
    }
  }

  public sealed interface RouteSearchResult {
    RouteSearchMetadata metadata();
  }

  public record NoRouteFoundResult(
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}

  public record SearchInterruptedResult(
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}

  public record SearchLimitReachedResult(
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}

  public record WorldDataPendingResult(
    SFVec3i endpoint,
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}

  public record QualityBoundNotMetResult(
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}

  public record GoalCompletion(NodeState state, List<WorldAction> actions) {
    public GoalCompletion {
      actions = List.copyOf(actions);
    }

    public boolean isSatisfied(GoalScorer goal) {
      return goal.isFinished(state, actions);
    }
  }

  public record FoundRouteResult(
    List<WorldAction> actions,
    RouteSearchMetadata metadata,
    GoalCompletion completion
  ) implements RouteSearchResult {}

  public record PartialRouteResult(
    List<WorldAction> actions,
    SFVec3i endpoint,
    RouteSearchMetadata metadata
  ) implements RouteSearchResult {}
}
