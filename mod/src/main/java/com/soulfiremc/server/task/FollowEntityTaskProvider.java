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
package com.soulfiremc.server.task;

import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.FollowEntityCompletionReason;
import com.soulfiremc.grpc.generated.FollowEntityTask;
import com.soulfiremc.grpc.generated.FollowEntityTaskResult;
import com.soulfiremc.grpc.generated.PathfindGoal;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.PathfindingSupport.ResolvedGoal;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import io.grpc.Status;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Core provider for continuously following a live entity.
public final class FollowEntityTaskProvider
  implements BotTaskProvider<FollowEntityTask> {
  private static final int DEFAULT_UNAVAILABLE_TIMEOUT_SECONDS = 10;
  private static final int MAX_UNAVAILABLE_TIMEOUT_SECONDS = 3_600;
  private static final int MAX_CONSECUTIVE_PATH_FAILURES = 3;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public FollowEntityTask inputPrototype() {
    return FollowEntityTask.getDefaultInstance();
  }

  @Override
  public String summary(FollowEntityTask input) {
    return "Follow entity " + input.getTarget().getEntityId();
  }

  @Override
  public Set<ControlResource> resources(FollowEntityTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    FollowEntityTask input
  ) {
    if (!input.hasTarget()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("target is required")
        .asRuntimeException();
    }
    var target = normalizeTarget(input.getTarget());
    BotTaskSupport.validateConnectionEpoch(
      context.bot(),
      target.hasConnectionEpoch() ? target.getConnectionEpoch() : ""
    );
    var constraint = PathfindingSupport.buildConstraint(
      context.bot(),
      input.getOptions()
    );
    var timeoutSeconds = input.getTargetUnavailableTimeoutSeconds() == 0
      ? DEFAULT_UNAVAILABLE_TIMEOUT_SECONDS
      : Math.min(
        input.getTargetUnavailableTimeoutSeconds(),
        MAX_UNAVAILABLE_TIMEOUT_SECONDS
      );
    var result = new CompletableFuture<FollowEntityTaskResult>();
    var control = new FollowControl(
      context,
      target,
      timeoutSeconds * 20,
      constraint,
      result
    );
    return new BotTaskExecution(control, result);
  }

  private static PathfindGoal.EntityGoal normalizeTarget(
    PathfindGoal.EntityGoal target
  ) {
    if (!Float.isFinite(target.getRadius())) {
      throw Status.INVALID_ARGUMENT
        .withDescription("target.radius must be finite")
        .asRuntimeException();
    }
    if (target.getEntityId() <= 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription("target.entity_id must be positive")
        .asRuntimeException();
    }
    return target.getRadius() > 0
      ? target
      : target.toBuilder().setRadius(3).build();
  }

  private static final class FollowControl implements ControlTask {
    private final BotTaskContext context;
    private final PathfindGoal.EntityGoal target;
    private final int unavailableTimeoutTicks;
    private final com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
      constraint;
    private final CompletableFuture<FollowEntityTaskResult> result;
    private @Nullable PathExecutor path;
    private @Nullable ResolvedGoal goal;
    private int unavailableTicks;
    private int consecutivePathFailures;
    private int ticks;

    private FollowControl(
      BotTaskContext context,
      PathfindGoal.EntityGoal target,
      int unavailableTimeoutTicks,
      com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
        constraint,
      CompletableFuture<FollowEntityTaskResult> result
    ) {
      this.context = context;
      this.target = target;
      this.unavailableTimeoutTicks = unavailableTimeoutTicks;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      ticks++;
      var bot = context.bot();
      var player = Objects.requireNonNull(
        bot.minecraft().player,
        "Bot player is not available"
      );
      Objects.requireNonNull(
        bot.minecraft().level,
        "Bot level is not available"
      );
      var entity = BotTaskSupport.findEntity(bot, target.getEntityId());
      if (entity == null || entity.isRemoved()) {
        stopPath(ControlStopReason.CANCELLED, null);
        unavailableTicks++;
        if (ticks % 20 == 0) {
          context.reportProgress(BotTaskProgress.newBuilder()
            .setMessage("Waiting for followed entity to become observable")
            .setCurrent(unavailableTicks)
            .setTotal(unavailableTimeoutTicks)
            .setFraction(Math.min(
              1.0,
              (double) unavailableTicks / unavailableTimeoutTicks
            ))
            .build());
        }
        if (unavailableTicks >= unavailableTimeoutTicks) {
          result.complete(FollowEntityTaskResult.newBuilder()
            .setFinalPosition(BotTaskSupport.position(bot))
            .setReason(FollowEntityCompletionReason
              .FOLLOW_ENTITY_COMPLETION_REASON_TARGET_UNAVAILABLE)
            .build());
        }
        return;
      }

      unavailableTicks = 0;
      var distance = player.position().distanceTo(entity.position());
      if (ticks % 20 == 0) {
        context.reportProgress(BotTaskProgress.newBuilder()
          .setMessage(distance <= target.getRadius()
            ? "Holding follow distance"
            : "Following entity")
          .setCurrent(Math.round(distance * 100))
          .setTotal(Math.round(target.getRadius() * 100))
          .build());
      }
      if (distance <= target.getRadius()) {
        consecutivePathFailures = 0;
        stopPath(ControlStopReason.CANCELLED, null);
        bot.controlState().resetAll();
        return;
      }

      if (path != null && path.completion().isDone()) {
        finishPath();
      }
      if (result.isDone()) {
        return;
      }
      if (path == null) {
        if (goal == null) {
          goal = PathfindingSupport.resolveGoal(
            bot,
            PathfindGoal.newBuilder().setEntity(target).build()
          );
        }
        path = PathExecutor.createPathfinding(
          bot,
          goal.scorer(),
          constraint
        );
        path.onStarted();
      }
      path.tick();
    }

    private void finishPath() {
      var completed = path;
      path = null;
      if (completed == null) {
        return;
      }
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
        consecutivePathFailures = 0;
      } catch (CompletionException exception) {
        var cause = Objects.requireNonNullElse(
          exception.getCause(),
          exception
        );
        completed.onStopped(ControlStopReason.FAILED, cause);
        consecutivePathFailures++;
        if (consecutivePathFailures >= MAX_CONSECUTIVE_PATH_FAILURES) {
          result.completeExceptionally(new IllegalStateException(
            "Unable to find a route to the followed entity after "
              + consecutivePathFailures + " attempts",
            cause
          ));
        }
      }
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var active = path;
      path = null;
      if (active != null) {
        active.onStopped(reason, cause);
      }
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public void onSuspended() {
      if (path != null) {
        path.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      if (path != null) {
        path.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      stopPath(reason, cause);
      context.bot().controlState().resetAll();
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Follow entity " + target.getEntityId();
    }

  }
}
