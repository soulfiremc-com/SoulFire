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
import com.soulfiremc.server.bot.ControllingTask;
import com.soulfiremc.server.pathfinding.NodeState;
import com.soulfiremc.server.pathfinding.RouteFinder;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;
import com.soulfiremc.server.pathfinding.graph.ProjectedInventory;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SFHelpers;
import com.soulfiremc.server.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class PathExecutor implements ControllingTask {
  private static final int MAX_ERROR_DISTANCE = 20;
  private final Queue<WorldAction> worldActionQueue = new LinkedBlockingQueue<>();
  private final BotConnection connection;
  private final LiveRouteFinder findPath;
  private final CompletableFuture<Void> pathCompletionFuture;
  private int totalMovements;
  private int ticks;
  private int movementNumber = 1;

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
    var pathCompletionFuture = new CompletableFuture<Void>();

    // Cancel the path if the bot is disconnected
    bot.shutdownHooks().add(() -> pathCompletionFuture.cancel(true));

    var pathExecutor = new PathExecutor(bot, new LiveRouteFinder(bot, goalScorer, pathConstraint), pathCompletionFuture);
    pathExecutor.submitForPathCalculation(true);

    return pathCompletionFuture;
  }

  @Override
  public boolean isDone() {
    return pathCompletionFuture.isDone();
  }

  public void submitForPathCalculation(boolean isInitial) {
    unregister();

    connection.scheduler().schedule(() -> {
      try {
        if (isDone()) {
          return;
        }

        if (!isInitial) {
          log.info("Waiting for one second for bot to finish falling...");
          TimeUtil.waitTime(1, TimeUnit.SECONDS);
          if (isDone()) {
            return;
          }
        }

        var routeSearchResult = findPath.findPath();
        if (isDone()) {
          return;
        }

        SFHelpers.mustSupply(() -> switch (routeSearchResult.routeSearchResult()) {
          case RouteFinder.FoundRouteResult foundRouteResult -> () -> {
            var newActions = repositionIfNeeded(foundRouteResult.actions(), routeSearchResult.start(), isInitial, this.findPath);
            if (newActions.isEmpty()) {
              log.info("We're already at the goal!");
              return;
            }

            log.info("Found path with {} actions!", newActions.size());

            preparePath(newActions);

            // Register again
            register();
          };
          case RouteFinder.NoRouteFoundResult _ -> throw new IllegalStateException("No route found to the goal!");
          case RouteFinder.PartialRouteResult partialRouteResult -> () -> {
            var newActions = addRecalculate(repositionIfNeeded(partialRouteResult.actions(), routeSearchResult.start(), isInitial, this.findPath));
            if (newActions.isEmpty()) {
              log.info("We're already at the goal!");
              return;
            }

            log.info("Found path with {} actions!", newActions.size());

            preparePath(newActions);

            // Register again
            register();
          };
          case RouteFinder.SearchExpiredResult _ -> throw new IllegalStateException("Route search expired before finding a route!");
          case RouteFinder.SearchInterruptedResult _ -> throw new IllegalStateException("Route search was interrupted before finding a route!");
        });
      } catch (Throwable t) {
        log.error("Error while calculating path", t);
        pathCompletionFuture.completeExceptionally(t);
      }
    });
  }

  public void preparePath(List<WorldAction> worldActions) {
    this.worldActionQueue.clear();
    this.worldActionQueue.addAll(worldActions);
    this.totalMovements = worldActions.size();
    this.ticks = 0;
    this.movementNumber = 1;
  }

  @Override
  public void tick() {
    // This method should not be called if the path is cancelled
    if (isDone()) {
      unregister();
      return;
    }

    if (worldActionQueue.isEmpty()) {
      unregister();
      return;
    }

    var worldAction = worldActionQueue.peek();
    if (worldAction == null) {
      unregister();
      return;
    }

    if (worldAction instanceof RecalculatePathAction) {
      log.info("Recalculating path...");
      recalculatePath();
      return;
    }

    if (ticks > 0 && ticks >= worldAction.getAllowedTicks()) {
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

    if (worldAction.isCompleted(connection)) {
      worldActionQueue.remove();
      log.info("Reached goal {}/{} in {} ticks!", movementNumber, totalMovements, ticks);
      movementNumber++;
      ticks = 0;

      // Directly use tick to execute next goal
      worldAction = worldActionQueue.peek();

      // If there are no more goals, stop
      if (worldAction == null) {
        log.info("Finished all goals!");
        connection.controlState().resetAll();
        pathCompletionFuture.complete(null);
        unregister();
        return;
      }

      if (worldAction instanceof RecalculatePathAction) {
        log.info("Recalculating path...");
        recalculatePath();
        return;
      }

      log.debug("Next goal: {}", worldAction);
    }

    ticks++;
    worldAction.tick(connection);
  }

  @Override
  public void stop() {
    if (!isDone()) {
      pathCompletionFuture.cancel(true);
    }

    unregister();
  }

  public synchronized void register() {
    if (isDone()) {
      return;
    }

    connection.botControl().registerControllingTask(this);
  }

  public synchronized void unregister() {
    connection.botControl().unregisterControllingTask(this);
    connection.controlState().resetAll();
  }

  public void recalculatePath() {
    submitForPathCalculation(false);
  }

  private record LiveRouteFinder(BotConnection bot, GoalScorer goalScorer, PathConstraint pathConstraint) {
    public LiveRouteFinderResult findPath() {
      var clientEntity = bot.minecraft().player;
      var level = bot.minecraft().player.level();
      var inventory =
        new ProjectedInventory(clientEntity.getInventory(), clientEntity, pathConstraint);
      var start =
        SFVec3i.fromInt(clientEntity.blockPosition());
      var startBlockState = level.getBlockState(start.toBlockPos());
      if (SFBlockHelpers.isTopFullBlock(startBlockState)) {
        // If the player is inside a block, move them up
        start = start.add(0, 1, 0);
      }

      var routeFinder =
        new RouteFinder(new MinecraftGraph(level, inventory, pathConstraint), goalScorer, bot.scheduler());

      log.info("Starting calculations at: {}", start.formatXYZ());
      var routeSearchResultFuture = routeFinder.findRouteFuture(NodeState.forInfo(start, inventory));
      bot.shutdownHooks().add(() -> routeSearchResultFuture.cancel(true));
      var routeSearchResult = routeSearchResultFuture.join();
      log.info("Route search result: {}", routeSearchResult);

      return new LiveRouteFinderResult(routeSearchResult, start);
    }

    private record LiveRouteFinderResult(RouteFinder.RouteSearchResult routeSearchResult, SFVec3i start) {
    }
  }
}
