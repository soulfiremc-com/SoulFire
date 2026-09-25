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
package com.soulfiremc.server.pathfinding.execution;

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.NodeState;
import com.soulfiremc.server.pathfinding.RouteFinder;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;
import com.soulfiremc.server.pathfinding.graph.ProjectedInventory;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SFHelpers;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class PathExecutor implements ControlTask {
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );
  private static final int MAX_ERROR_DISTANCE = 20;
  private static final int MAX_CONSECUTIVE_STATIONARY_PARTIAL_ROUTES = 3;
  private static final int MAX_CONSECUTIVE_STALLED_ACTIONS = 5;
  private static final int PARTIAL_ROUTE_PREFETCH_ACTIONS = 8;
  private static final int WORLD_DATA_WAIT_TIMEOUT_TICKS = 20 * 30;
  private final Queue<WorldAction> worldActionQueue = new LinkedBlockingQueue<>();
  private final Set<SFVec3i> completedBlockBreaks = new HashSet<>();
  private final BotConnection connection;
  private final LiveRouteFinder findPath;
  private final CompletableFuture<Void> pathCompletionFuture;
  private final PartialRouteProgressGuard partialRouteProgressGuard =
    new PartialRouteProgressGuard(
      MAX_CONSECUTIVE_STATIONARY_PARTIAL_ROUTES
    );
  private final ActionStallGuard actionStallGuard =
    new ActionStallGuard(MAX_CONSECUTIVE_STALLED_ACTIONS);
  private volatile boolean awaitingPath;
  private volatile CompletableFuture<PlannedRoute> prefetchedRoute;
  private volatile SFVec3i prefetchedRouteStart;
  private volatile SFVec3i partialRouteEndpoint;
  private volatile RouteFinder.RouteSearchMetadata partialRouteMetadata;
  private volatile WorldDataWait worldDataWait;
  private int totalMovements;
  private int ticks;
  private int movementNumber = 1;

  @Override
  public Set<ControlResource> resources() {
    return RESOURCES;
  }

  @Override
  public boolean allowsCombatOverlay() {
    return !isDone() && !awaitingPath && worldDataWait == null
      && worldActionQueue.peek() instanceof MovementAction;
  }

  private PathExecutor(
    BotConnection connection,
    LiveRouteFinder findPath,
    CompletableFuture<Void> pathCompletionFuture) {
    this.connection = connection;
    this.findPath = findPath;
    this.pathCompletionFuture = pathCompletionFuture;
  }

  private static List<WorldAction> repositionIfNeeded(List<WorldAction> actions, SFVec3i from, boolean requiresRepositioning, LiveRouteFinder findPath) {
    if (!requiresRepositioning) {
      return actions;
    }

    var repositionActions = new ArrayList<WorldAction>();
    repositionActions.add(new MovementAction(from, false, findPath.pathConstraint));
    repositionActions.addAll(actions);

    return repositionActions;
  }

  private static List<WorldAction> addRecalculate(List<WorldAction> actions) {
    var repositionActions = new ArrayList<>(actions);
    repositionActions.add(new RecalculatePathAction());

    return repositionActions;
  }

  public static CompletableFuture<Void> executePathfinding(BotConnection bot, GoalScorer goalScorer, PathConstraint pathConstraint) {
    var pathExecutor = createPathfinding(bot, goalScorer, pathConstraint);
    bot.botControl().replace(pathExecutor);
    return pathExecutor.completion();
  }

  public static PathExecutor createPathfinding(
    BotConnection bot,
    GoalScorer goalScorer,
    PathConstraint pathConstraint
  ) {
    var completion = new CompletableFuture<Void>();
    bot.shutdownHooks().add(() -> completion.cancel(true));
    return new PathExecutor(
      bot,
      new LiveRouteFinder(bot, goalScorer, pathConstraint),
      completion
    );
  }

  public static CompletableFuture<PlannedRoute> plan(
    BotConnection bot,
    GoalScorer goalScorer,
    PathConstraint pathConstraint
  ) {
    var finder = new LiveRouteFinder(bot, goalScorer, pathConstraint);
    return bot.scheduler().supplyAsync(finder::findPath);
  }

  public CompletableFuture<Void> completion() {
    return pathCompletionFuture;
  }

  public Progress progress() {
    return new Progress(
      awaitingPath || worldDataWait != null,
      movementNumber,
      totalMovements
    );
  }

  public Set<SFVec3i> completedBlockBreaks() {
    return Set.copyOf(completedBlockBreaks);
  }

  public void completeEarly() {
    if (isDone()) {
      return;
    }

    awaitingPath = false;
    worldDataWait = null;
    cancelPrefetch();
    stopActiveBlockBreak();
    worldActionQueue.clear();
    connection.controlState().resetAll();
    pathCompletionFuture.complete(null);
  }

  @Override
  public void onStarted() {
    submitForPathCalculation(true);
  }

  @Override
  public ControlPriority priority() {
    return ControlPriority.HIGH;
  }

  @Override
  public String description() {
    return "PathExecutor";
  }

  @Override
  public boolean isDone() {
    return pathCompletionFuture.isDone();
  }

  public void submitForPathCalculation(boolean isInitial) {
    if (awaitingPath || isDone()) {
      return;
    }

    awaitingPath = true;
    worldDataWait = null;
    cancelPrefetch();
    partialRouteEndpoint = null;
    partialRouteMetadata = null;
    stopActiveBlockBreak();
    worldActionQueue.clear();
    connection.controlState().resetAll();

    connection.scheduler().schedule(() -> calculatePath(isInitial));
  }

  private void calculatePath(boolean isInitial) {
    try {
      if (isDone()) {
        return;
      }

      var player = connection.minecraft().player;
      if (
        !isInitial
          && !player.onGround()
          && !player.isInWater()
          && !player.isInLava()
          && !player.onClimbable()
      ) {
        connection.scheduler().schedule(
          () -> calculatePath(false),
          50,
          TimeUnit.MILLISECONDS
        );
        return;
      }

      var routeSearchResult = findPath.findPath();
      if (isDone()) {
        return;
      }

      acceptPlannedRoute(routeSearchResult, isInitial);
    } catch (Throwable t) {
      log.error("Error while calculating path", t);
      awaitingPath = false;
      pathCompletionFuture.completeExceptionally(t);
    }
  }

  private void acceptPlannedRoute(
    PlannedRoute routeSearchResult,
    boolean isInitial
  ) {
    SFHelpers.mustSupply(() -> switch (routeSearchResult.routeSearchResult()) {
          case RouteFinder.FoundRouteResult foundRouteResult -> () -> {
            partialRouteProgressGuard.reset();
            var newActions = repositionIfNeeded(foundRouteResult.actions(), routeSearchResult.start(), isInitial, this.findPath);
            if (newActions.isEmpty()) {
              log.info("We're already at the goal!");
              awaitingPath = false;
              pathCompletionFuture.complete(null);
              return;
            }

            log.info("Found path with {} actions!", newActions.size());

            preparePath(newActions, null, null);
          };
          case RouteFinder.NoRouteFoundResult _ ->
            throw UnreachableGoalException.noRoute();
          case RouteFinder.SearchLimitReachedResult limit ->
            throw UnreachableGoalException.searchLimit(
              Math.toIntExact(limit.metadata().expandedStates())
            );
          case RouteFinder.QualityBoundNotMetResult unqualified ->
            throw UnreachableGoalException.qualityBound(
              unqualified.metadata().qualityBound(),
              findPath.pathConstraint().maximumQualityBound()
            );
          case RouteFinder.PartialRouteResult partialRouteResult -> () -> {
            preparePartialPath(
              partialRouteResult.actions(),
              partialRouteResult.endpoint(),
              partialRouteResult.metadata(),
              routeSearchResult.start(),
              isInitial
            );
          };
          case RouteFinder.WorldDataPendingResult pending -> () ->
            beginWorldDataWait(pending.metadata());
          case RouteFinder.SearchInterruptedResult _ -> throw new IllegalStateException("Route search was interrupted before finding a route!");
        });
  }

  private void preparePartialPath(
    List<WorldAction> actions,
    SFVec3i routeEndpoint,
    RouteFinder.RouteSearchMetadata metadata,
    SFVec3i start,
    boolean isInitial
  ) {
    if (partialRouteProgressGuard.shouldAbort(routeEndpoint)) {
      awaitingPath = false;
      pathCompletionFuture.completeExceptionally(
        UnreachableGoalException.stalled(
          MAX_CONSECUTIVE_STATIONARY_PARTIAL_ROUTES
        )
      );
      return;
    }
    var newActions = addRecalculate(
      repositionIfNeeded(actions, start, isInitial, findPath)
    );
    log.info("Found path with {} actions!", newActions.size());
    preparePath(newActions, routeEndpoint, metadata);
  }

  public void preparePath(List<WorldAction> worldActions) {
    preparePath(worldActions, null, null);
  }

  private void preparePath(
    List<WorldAction> worldActions,
    SFVec3i routeEndpoint,
    RouteFinder.RouteSearchMetadata metadata
  ) {
    this.worldActionQueue.clear();
    this.worldActionQueue.addAll(worldActions);
    this.partialRouteEndpoint = routeEndpoint;
    this.partialRouteMetadata = metadata;
    this.worldDataWait = null;
    this.totalMovements = worldActions.size();
    this.ticks = 0;
    this.movementNumber = 1;
    this.awaitingPath = false;
  }

  @Override
  public void tick() {
    if (isDone()) {
      return;
    }

    var wait = worldDataWait;
    if (wait != null) {
      continueWorldDataWait(wait);
      return;
    }

    if (awaitingPath || worldActionQueue.isEmpty()) {
      return;
    }

    var worldAction = worldActionQueue.peek();
    if (worldAction == null) {
      return;
    }

    maybePrefetchPartialRoute();

    if (worldAction instanceof RecalculatePathAction) {
      continuePartialRoute();
      return;
    }

    if (worldAction.isCompleted(connection)) {
      completeCurrentAction(worldAction);
      return;
    }

    if (ticks > 0 && ticks >= worldAction.getAllowedTicks()) {
      var playerPosition = SFVec3i.fromInt(
        connection.minecraft().player.blockPosition()
      );
      if (actionStallGuard.shouldAbort(
        worldAction.toString(),
        playerPosition
      )) {
        pathCompletionFuture.completeExceptionally(
          UnreachableGoalException.stalledAction(
            MAX_CONSECUTIVE_STALLED_ACTIONS,
            worldAction.toString()
          )
        );
        return;
      }
      log.warn("Took too long to complete action: {}", worldAction);
      log.warn("Recalculating path...");
      recalculatePath();
      return;
    }

    if (SFVec3i.fromInt(connection.minecraft().player.blockPosition())
      .distance(worldAction.targetPosition(connection)) > MAX_ERROR_DISTANCE) {
      log.warn("More than {} blocks away from target, this must be a mistake!", MAX_ERROR_DISTANCE);
      log.warn("Recalculating path...");
      recalculatePath();
      return;
    }

    if (!worldAction.isValid(connection)) {
      var playerPosition = SFVec3i.fromInt(
        connection.minecraft().player.blockPosition()
      );
      var invalidAction = "invalid precondition for " + worldAction;
      if (actionStallGuard.shouldAbort(invalidAction, playerPosition)) {
        pathCompletionFuture.completeExceptionally(
          UnreachableGoalException.stalledAction(
            MAX_CONSECUTIVE_STALLED_ACTIONS,
            invalidAction
          )
        );
        return;
      }
      log.info(
        "The current path action's precondition changed; recalculating before {}",
        worldAction
      );
      recalculatePath();
      return;
    }

    if (
      worldAction instanceof BlockBreakAction blockBreakAction
        && blockBreakAction.isRejected(connection)
    ) {
      pathCompletionFuture.completeExceptionally(
        new BlockBreakRejectedException(blockBreakAction.blockPosition())
      );
      return;
    }
    if (
      worldAction instanceof BlockPlaceAction blockPlaceAction
        && blockPlaceAction.isRejected(connection)
    ) {
      pathCompletionFuture.completeExceptionally(
        new BlockPlaceRejectedException(blockPlaceAction.blockPosition())
      );
      return;
    }
    if (
      worldAction
        instanceof JumpAndPlaceBelowAction jumpAndPlaceBelowAction
        && jumpAndPlaceBelowAction.isRejected(connection)
    ) {
      pathCompletionFuture.completeExceptionally(
        new BlockPlaceRejectedException(
          jumpAndPlaceBelowAction.blockPlacePosition()
        )
      );
      return;
    }

    ticks++;
    worldAction.tick(connection);
  }

  private void completeCurrentAction(WorldAction worldAction) {
    if (
      worldAction instanceof BlockBreakAction blockBreakAction
        && blockBreakAction.breakAttempted()
    ) {
      completedBlockBreaks.add(blockBreakAction.blockPosition());
    }
    worldActionQueue.remove();
    log.info(
      "Reached goal {}/{} in {} ticks!",
      movementNumber,
      totalMovements,
      ticks
    );
    movementNumber++;
    ticks = 0;

    var nextAction = worldActionQueue.peek();
    if (nextAction == null) {
      log.info("Finished all goals!");
      connection.controlState().resetAll();
      pathCompletionFuture.complete(null);
      return;
    }
    log.debug("Next goal: {}", nextAction);
  }

  @Override
  public void onSuspended() {
    stopActiveBlockBreak();
    connection.controlState().resetAll();
  }

  @Override
  public void onResumed() {
    if (!isDone() && !awaitingPath && worldDataWait == null) {
      log.info("Resuming path execution, recalculating path...");
      recalculatePath();
    }
  }

  @Override
  public void onStopped(ControlStopReason reason, Throwable cause) {
    if (reason != ControlStopReason.COMPLETED && !isDone()) {
      pathCompletionFuture.cancel(true);
    }

    awaitingPath = false;
    worldDataWait = null;
    cancelPrefetch();
    stopActiveBlockBreak();
    worldActionQueue.clear();
    connection.controlState().resetAll();
  }

  private void stopActiveBlockBreak() {
    if (
      worldActionQueue.peek() instanceof BlockBreakAction blockBreakAction
        && blockBreakAction.breakAttempted()
    ) {
      var gameMode = connection.minecraft().gameMode;
      if (gameMode != null) {
        gameMode.stopDestroyBlock();
      }
    }
  }

  public void recalculatePath() {
    cancelPrefetch();
    partialRouteEndpoint = null;
    partialRouteMetadata = null;
    submitForPathCalculation(false);
  }

  private void maybePrefetchPartialRoute() {
    var endpoint = partialRouteEndpoint;
    var metadata = partialRouteMetadata;
    if (
      endpoint == null
        || prefetchedRoute != null
        || worldActionQueue.size() > PARTIAL_ROUTE_PREFETCH_ACTIONS + 1
        || worldActionQueue.stream().anyMatch(action ->
        action instanceof BlockBreakAction
          || action instanceof BlockPlaceAction
          || action instanceof JumpAndPlaceBelowAction)
    ) {
      return;
    }
    if (
      metadata != null
        && metadata.frontierReason()
          == RouteFinder.FrontierReason.LEVEL_BOUNDARY
        && !findPath.hasNewWorldData(metadata)
    ) {
      return;
    }

    log.debug("Prefetching the next partial route from {}", endpoint);
    prefetchedRouteStart = endpoint;
    prefetchedRoute = findPath.findPathFutureFrom(endpoint);
  }

  private void continuePartialRoute() {
    var future = prefetchedRoute;
    if (future == null) {
      var metadata = partialRouteMetadata;
      if (
        metadata != null
          && metadata.frontierReason()
            == RouteFinder.FrontierReason.LEVEL_BOUNDARY
      ) {
        beginWorldDataWait(metadata);
        return;
      }
      log.info("Calculating the next partial route...");
      recalculatePath();
      return;
    }
    if (future.isDone()) {
      finishPrefetchedRoute(future);
      return;
    }

    log.debug("Waiting for the prefetched partial route");
    awaitingPath = true;
    connection.controlState().resetAll();
    future.whenComplete((_, _) -> connection.scheduler().schedule(
      () -> finishPrefetchedRoute(future)
    ));
  }

  private void finishPrefetchedRoute(
    CompletableFuture<PlannedRoute> future
  ) {
    if (isDone() || future != prefetchedRoute) {
      return;
    }
    var expectedStart = prefetchedRouteStart;
    prefetchedRoute = null;
    prefetchedRouteStart = null;
    partialRouteEndpoint = null;
    partialRouteMetadata = null;
    awaitingPath = false;

    var playerStart = SFVec3i.fromInt(
      connection.minecraft().player.blockPosition()
    );
    if (!Objects.equals(expectedStart, playerStart)) {
      log.debug(
        "Discarding a prefetched route because the player moved from {} to {}",
        expectedStart,
        playerStart
      );
      submitForPathCalculation(false);
      return;
    }

    try {
      var route = future.join();
      if (
        route.routeSearchResult()
          instanceof RouteFinder.WorldDataPendingResult pending
      ) {
        beginWorldDataWait(pending.metadata());
        return;
      }
      if (
        !(route.routeSearchResult()
          instanceof RouteFinder.FoundRouteResult)
          && !(route.routeSearchResult()
          instanceof RouteFinder.PartialRouteResult)
      ) {
        log.debug(
          "Discarding advisory prefetch result {} and searching from live state",
          route.routeSearchResult().getClass().getSimpleName()
        );
        submitForPathCalculation(false);
        return;
      }
      if (findPath.isStale(route.routeSearchResult().metadata())) {
        log.debug("Discarding a prefetched route from an old world revision");
        submitForPathCalculation(false);
        return;
      }
      acceptPlannedRoute(route, false);
    } catch (Throwable t) {
      log.warn("The prefetched route failed; calculating from live state", t);
      submitForPathCalculation(false);
    }
  }

  private void cancelPrefetch() {
    var future = prefetchedRoute;
    prefetchedRoute = null;
    prefetchedRouteStart = null;
    if (future != null) {
      future.cancel(true);
    }
  }

  private void beginWorldDataWait(
    RouteFinder.RouteSearchMetadata metadata
  ) {
    if (metadata.unavailableChunks().isEmpty()) {
      throw new IllegalStateException(
        "A world-data-pending route did not report missing chunks"
      );
    }
    cancelPrefetch();
    partialRouteEndpoint = null;
    partialRouteMetadata = null;
    stopActiveBlockBreak();
    worldActionQueue.clear();
    connection.controlState().resetAll();
    awaitingPath = false;
    worldDataWait = new WorldDataWait(
      metadata,
      new WorldDataWaitGuard(
        metadata.worldRevision(),
        WORLD_DATA_WAIT_TIMEOUT_TICKS
      )
    );
    log.info(
      "Waiting for {} navigation chunks after world revision {}",
      metadata.unavailableChunks().size(),
      metadata.worldRevision()
    );
  }

  private void continueWorldDataWait(WorldDataWait wait) {
    if (wait != worldDataWait) {
      return;
    }
    var decision = wait.guard().tick(
      findPath.currentWorldRevision(),
      findPath.areChunksLoaded(wait.metadata())
    );
    switch (decision) {
      case WAIT -> connection.controlState().resetAll();
      case RETRY -> {
        log.info("Navigation world data changed; retrying from live state");
        worldDataWait = null;
        submitForPathCalculation(false);
      }
      case TIMED_OUT -> {
        worldDataWait = null;
        pathCompletionFuture.completeExceptionally(
          UnreachableGoalException.worldDataTimeout(
            wait.metadata().worldRevision(),
            wait.metadata().unavailableChunks().size()
          )
        );
      }
    }
  }

  static final class PartialRouteProgressGuard {
    private final int maximumConsecutiveStationaryRoutes;
    private SFVec3i previousEndpoint;
    private int consecutiveStationaryRoutes;

    PartialRouteProgressGuard(int maximumConsecutiveStationaryRoutes) {
      if (maximumConsecutiveStationaryRoutes < 1) {
        throw new IllegalArgumentException(
          "maximumConsecutiveStationaryRoutes must be positive"
        );
      }
      this.maximumConsecutiveStationaryRoutes =
        maximumConsecutiveStationaryRoutes;
    }

    boolean shouldAbort(SFVec3i endpoint) {
      if (!Objects.equals(endpoint, previousEndpoint)) {
        previousEndpoint = endpoint;
        consecutiveStationaryRoutes = 1;
        return false;
      }
      consecutiveStationaryRoutes++;
      return consecutiveStationaryRoutes
        >= maximumConsecutiveStationaryRoutes;
    }

    void reset() {
      previousEndpoint = null;
      consecutiveStationaryRoutes = 0;
    }
  }

  private record LiveRouteFinder(
    BotConnection bot,
    GoalScorer goalScorer,
    PathConstraint pathConstraint
  ) {
    public PlannedRoute findPath() {
      return findPathFutureFrom(null).join();
    }

    public CompletableFuture<PlannedRoute> findPathFutureFrom(
      SFVec3i requestedStart
    ) {
      var clientEntity = bot.minecraft().player;
      var level = Objects.requireNonNull(
        bot.minecraft().level,
        "Bot level is not available"
      );
      var inventory =
        new ProjectedInventory(clientEntity.getInventory(), clientEntity, pathConstraint);
      var start = requestedStart == null
        ? SFVec3i.fromInt(clientEntity.blockPosition())
        : requestedStart;
      var startBlockState = level.getBlockState(start.toBlockPos());
      if (requestedStart == null && SFBlockHelpers.isTopFullBlock(startBlockState)) {
        // If the player is inside a block, move them up
        start = start.add(0, 1, 0);
      }

      var routeFinder =
        new RouteFinder(
          new MinecraftGraph(
            level,
            inventory,
            pathConstraint,
            bot.navigationWorldState().revision(level)
          ),
          goalScorer,
          bot.scheduler()
        );

      log.info(
        "Starting calculations at {} for goal {}",
        start.formatXYZ(),
        goalScorer
      );
      var routeStart = start;
      var routeSearchResultFuture = routeFinder.findRouteFuture(NodeState.forInfo(start, inventory));
      bot.shutdownHooks().add(() -> routeSearchResultFuture.cancel(true));
      var plannedRouteFuture = routeSearchResultFuture.thenApply(
        routeSearchResult -> {
          log.info("Route search result: {}", routeSearchResult);
          return new PlannedRoute(routeSearchResult, routeStart);
        }
      );
      plannedRouteFuture.whenComplete((_, _) -> {
        if (plannedRouteFuture.isCancelled()) {
          routeSearchResultFuture.cancel(true);
        }
      });
      return plannedRouteFuture;
    }

    public long currentWorldRevision() {
      var level = Objects.requireNonNull(
        bot.minecraft().level,
        "Bot level is not available"
      );
      return bot.navigationWorldState().revision(level);
    }

    public boolean areChunksLoaded(
      RouteFinder.RouteSearchMetadata metadata
    ) {
      var level = Objects.requireNonNull(
        bot.minecraft().level,
        "Bot level is not available"
      );
      return metadata.unavailableChunks().stream().allMatch(chunk ->
        level.getChunkSource().hasChunk(chunk.x(), chunk.z())
      );
    }

    public boolean hasNewWorldData(
      RouteFinder.RouteSearchMetadata metadata
    ) {
      return currentWorldRevision() > metadata.worldRevision()
        && areChunksLoaded(metadata);
    }

    public boolean isStale(RouteFinder.RouteSearchMetadata metadata) {
      return currentWorldRevision() != metadata.worldRevision();
    }
  }

  public record PlannedRoute(
    RouteFinder.RouteSearchResult routeSearchResult,
    SFVec3i start
  ) {}

  public record Progress(boolean planning, int currentMovement, int totalMovements) {
  }

  private record WorldDataWait(
    RouteFinder.RouteSearchMetadata metadata,
    WorldDataWaitGuard guard
  ) {}

  static final class WorldDataWaitGuard {
    private final long snapshotRevision;
    private final int maximumWaitTicks;
    private int waitedTicks;

    WorldDataWaitGuard(long snapshotRevision, int maximumWaitTicks) {
      if (maximumWaitTicks < 1) {
        throw new IllegalArgumentException(
          "maximumWaitTicks must be positive"
        );
      }
      this.snapshotRevision = snapshotRevision;
      this.maximumWaitTicks = maximumWaitTicks;
    }

    WorldDataWaitDecision tick(
      long currentRevision,
      boolean dependenciesLoaded
    ) {
      if (currentRevision > snapshotRevision && dependenciesLoaded) {
        return WorldDataWaitDecision.RETRY;
      }
      waitedTicks++;
      return waitedTicks >= maximumWaitTicks
        ? WorldDataWaitDecision.TIMED_OUT
        : WorldDataWaitDecision.WAIT;
    }
  }

  enum WorldDataWaitDecision {
    WAIT,
    RETRY,
    TIMED_OUT
  }

  static final class ActionStallGuard {
    private final int maximumConsecutiveStalls;
    private final Map<StalledAction, Integer> stalls = new HashMap<>();

    ActionStallGuard(int maximumConsecutiveStalls) {
      if (maximumConsecutiveStalls < 1) {
        throw new IllegalArgumentException(
          "maximumConsecutiveStalls must be positive"
        );
      }
      this.maximumConsecutiveStalls = maximumConsecutiveStalls;
    }

    boolean shouldAbort(String action, SFVec3i position) {
      var stalledAction = new StalledAction(action, position);
      var occurrences = stalls.merge(stalledAction, 1, Integer::sum);
      return occurrences >= maximumConsecutiveStalls;
    }

    private record StalledAction(String action, SFVec3i position) {}
  }
}
