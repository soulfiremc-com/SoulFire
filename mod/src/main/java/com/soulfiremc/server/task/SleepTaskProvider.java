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

import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.SleepCompletionReason;
import com.soulfiremc.grpc.generated.SleepTask;
import com.soulfiremc.grpc.generated.SleepTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Finds a bed, navigates into interaction range, and waits for the server to
/// confirm that the bot entered the sleeping state.
public final class SleepTaskProvider implements BotTaskProvider<SleepTask> {
  private static final int DEFAULT_SEARCH_RADIUS = 24;
  private static final int MAX_SEARCH_RADIUS = 32;
  private static final int DEFAULT_RETRY_INTERVAL_TICKS = 20;
  private static final int MAX_RETRY_INTERVAL_TICKS = 1_200;
  private static final int CONFIRMATION_TIMEOUT_TICKS = 100;
  private static final int BED_APPROACH_RADIUS = 2;
  private static final double SLEEP_RANGE_HORIZONTAL = 2.5;
  private static final double SLEEP_RANGE_VERTICAL = 1.5;
  private static final Comparator<BlockPos> BED_SEARCH_TIE_ORDER = Comparator
    .<BlockPos>comparingInt(BlockPos::getZ)
    .thenComparingInt(BlockPos::getY)
    .thenComparingInt(BlockPos::getX);
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public SleepTask inputPrototype() {
    return SleepTask.getDefaultInstance();
  }

  @Override
  public String summary(SleepTask input) {
    return input.hasBed()
      ? "Sleep in the selected bed"
      : "Find a nearby bed and sleep";
  }

  @Override
  public Set<ControlResource> resources(SleepTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, SleepTask input) {
    var radius = input.getSearchRadius() == 0
      ? DEFAULT_SEARCH_RADIUS
      : Math.min(input.getSearchRadius(), MAX_SEARCH_RADIUS);
    var retryTicks = input.getRetryIntervalTicks() == 0
      ? DEFAULT_RETRY_INTERVAL_TICKS
      : Math.min(
        input.getRetryIntervalTicks(),
        MAX_RETRY_INTERVAL_TICKS
      );
    BlockPos pinnedBed = null;
    if (input.hasBed()) {
      validateDimension(context, input.getBed());
      pinnedBed = toBlockPos(input.getBed());
    }
    var result = new CompletableFuture<SleepTaskResult>();
    return new BotTaskExecution(
      new SleepControl(
        context,
        pinnedBed,
        radius,
        retryTicks,
        input.getWaitUntilPossible(),
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result
      ),
      result
    );
  }

  private static void validateDimension(
    BotTaskContext context,
    BlockPosition position
  ) {
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var actual = level.dimension().identifier().toString();
    if (
      !position.getDimension().isBlank()
        && !position.getDimension().equals(actual)
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Bed is in '%s', but the bot is in '%s'"
            .formatted(position.getDimension(), actual)
        )
        .asRuntimeException();
    }
  }

  /// The server refuses to let a player sleep more than 3 blocks
  /// (horizontally, 2 vertically) from the bed's bottom center: "the bed is
  /// too far away". Stay half a block inside that.
  static boolean inSleepRange(Vec3 position, BlockPos bed) {
    var center = Vec3.atBottomCenterOf(bed);
    return Math.abs(position.x - center.x) <= SLEEP_RANGE_HORIZONTAL
      && Math.abs(position.y - center.y) <= SLEEP_RANGE_VERTICAL
      && Math.abs(position.z - center.z) <= SLEEP_RANGE_HORIZONTAL;
  }

  static @Nullable BlockPos findNearestBed(ClientLevel level, BlockPos origin, int radius) {
    var minX = origin.getX() - radius;
    var maxX = origin.getX() + radius;
    var minY = Math.max(level.getMinY(), origin.getY() - radius);
    var maxY = Math.min(level.getMaxY(), origin.getY() + radius);
    var minZ = origin.getZ() - radius;
    var maxZ = origin.getZ() + radius;
    if (minY > maxY) {
      return null;
    }
    BlockPos nearest = null;
    var nearestDistance = (double) radius * radius;
    var chunks = level.getChunkSource();
    for (var chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
      for (var chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
        var chunk = chunks.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
          continue;
        }
        for (var sectionY = minY >> 4; sectionY <= (maxY >> 4); sectionY++) {
          var section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
          if (!section.maybeHas(state -> state.getBlock() instanceof BedBlock)) {
            continue;
          }
          for (var position : BlockPos.betweenClosed(
            Math.max(minX, chunkX << 4), Math.max(minY, sectionY << 4), Math.max(minZ, chunkZ << 4),
            Math.min(maxX, (chunkX << 4) + 15), Math.min(maxY, (sectionY << 4) + 15), Math.min(maxZ, (chunkZ << 4) + 15)
          )) {
            var distance = position.distSqr(origin);
            if (distance > nearestDistance
              || !(section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15)
              .getBlock() instanceof BedBlock)) {
              continue;
            }
            if (nearest == null || distance < nearestDistance
              || distance == nearestDistance && BED_SEARCH_TIE_ORDER.compare(position, nearest) < 0) {
              nearest = position.immutable();
              nearestDistance = distance;
            }
          }
        }
      }
    }
    return nearest;
  }

  private static BlockPos toBlockPos(BlockPosition position) {
    return new BlockPos(
      position.getX(),
      position.getY(),
      position.getZ()
    );
  }

  private static final class SleepControl implements ControlTask {
    private final BotTaskContext context;
    private final @Nullable BlockPos pinnedBed;
    private final int searchRadius;
    private final int retryTicks;
    private final boolean waitUntilPossible;
    private final PathConstraint constraint;
    private final CompletableFuture<SleepTaskResult> result;
    private @Nullable BlockPos bed;
    private @Nullable PathExecutor path;
    private Stage stage = Stage.FIND_BED;
    private int stageTicks;
    private int ticks;

    private SleepControl(
      BotTaskContext context,
      @Nullable BlockPos pinnedBed,
      int searchRadius,
      int retryTicks,
      boolean waitUntilPossible,
      PathConstraint constraint,
      CompletableFuture<SleepTaskResult> result
    ) {
      this.context = context;
      this.pinnedBed = pinnedBed;
      this.searchRadius = searchRadius;
      this.retryTicks = retryTicks;
      this.waitUntilPossible = waitUntilPossible;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      ticks++;
      try {
        var player = requirePlayer();
        if (player.isSleeping()) {
          var sleepingBed = player.getSleepingPos().orElse(bed);
          complete(
            sleepingBed,
            bed == null
              ? SleepCompletionReason
                .SLEEP_COMPLETION_REASON_ALREADY_SLEEPING
              : SleepCompletionReason
                .SLEEP_COMPLETION_REASON_SLEEPING
          );
          return;
        }
        switch (stage) {
          case FIND_BED -> findBed();
          case NAVIGATE -> navigate();
          case INTERACT -> interact();
          case WAIT_FOR_CONFIRMATION -> waitForConfirmation();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void findBed() {
      if (stageTicks > 0 && stageTicks < retryTicks) {
        stageTicks++;
        return;
      }
      stageTicks = 0;
      bed = pinnedBed == null
        ? findNearestBed(Objects.requireNonNull(context.bot().minecraft().level),
          requirePlayer().blockPosition(), searchRadius)
        : pinnedBed;
      if (bed == null) {
        if (!waitUntilPossible) {
          complete(
            null,
            SleepCompletionReason
              .SLEEP_COMPLETION_REASON_NO_BED_FOUND
          );
          return;
        }
        stageTicks = 1;
        report("Waiting for a nearby bed");
        return;
      }
      requireBed(bed);
      transition(Stage.NAVIGATE, "Walking to bed");
    }

    private void navigate() {
      var target = Objects.requireNonNull(bed);
      var player = requirePlayer();
      if (inSleepRange(player.position(), target)) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.INTERACT, "Entering bed");
        return;
      }
      if (path == null) {
        path = PathExecutor.createPathfinding(
          context.bot(),
          new CloseToPosGoal(SFVec3i.fromInt(target), BED_APPROACH_RADIUS),
          constraint
        );
        path.onStarted();
      }
      if (!path.isDone()) {
        path.tick();
        report(path.progress().planning()
          ? "Planning route to bed"
          : "Walking to bed");
        return;
      }
      var completed = path;
      path = null;
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
        transition(Stage.INTERACT, "Entering bed");
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        completed.onStopped(ControlStopReason.FAILED, cause);
        if (pinnedBed != null || !waitUntilPossible) {
          throw exception;
        }
        bed = null;
        transition(Stage.FIND_BED, "Searching for another reachable bed");
      }
    }

    private void interact() {
      var target = Objects.requireNonNull(bed);
      requireBed(target);
      var player = requirePlayer();
      if (!inSleepRange(player.position(), target)) {
        transition(Stage.NAVIGATE, "Returning to bed");
        return;
      }
      if (stageTicks > 0 && stageTicks < retryTicks) {
        stageTicks++;
        return;
      }
      stageTicks = 0;
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode,
        "Bot game mode is not available"
      );
      var swingAnimation = player.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation();
      var interaction = gameMode.useItemOn(
        player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(
          Vec3.atCenterOf(target).add(0, 0.5, 0),
          Direction.UP,
          target,
          false
        )
      );
      if (!(interaction instanceof InteractionResult.Success success)) {
        rejected("The bed rejected the sleep interaction");
        return;
      }
      if (
        success.swingSource() == InteractionResult.SwingSource.PREDICTED
      ) {
        player.swing(InteractionHand.MAIN_HAND, swingAnimation, false);
      }
      transition(
        Stage.WAIT_FOR_CONFIRMATION,
        "Waiting for sleep confirmation"
      );
    }

    private void waitForConfirmation() {
      if (requirePlayer().isSleeping()) {
        complete(
          bed,
          SleepCompletionReason.SLEEP_COMPLETION_REASON_SLEEPING
        );
        return;
      }
      stageTicks++;
      if (stageTicks < CONFIRMATION_TIMEOUT_TICKS) {
        if (stageTicks % 20 == 0) {
          report("Waiting for sleep confirmation");
        }
        return;
      }
      rejected("The server did not allow the bot to sleep");
    }

    private void rejected(String message) {
      if (!waitUntilPossible) {
        throw Status.FAILED_PRECONDITION
          .withDescription(message)
          .asRuntimeException();
      }
      transition(Stage.INTERACT, "Waiting until sleep is possible");
      stageTicks = 1;
    }

    private void requireBed(BlockPos target) {
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!SFBlockHelpers.isChunkLoaded(level, target)) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Bed is not loaded")
          .asRuntimeException();
      }
      if (!(level.getBlockState(target).getBlock() instanceof BedBlock)) {
        throw Status.FAILED_PRECONDITION
          .withDescription("Selected bed is no longer present")
          .asRuntimeException();
      }
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
    }

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      report(message);
    }

    private void report(String message) {
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(ticks)
        .build());
    }

    private void complete(
      @Nullable BlockPos completedBed,
      SleepCompletionReason reason
    ) {
      var builder = SleepTaskResult.newBuilder().setReason(reason);
      if (completedBed != null) {
        var level = Objects.requireNonNull(context.bot().minecraft().level);
        builder.setBed(BlockPosition.newBuilder()
          .setX(completedBed.getX())
          .setY(completedBed.getY())
          .setZ(completedBed.getZ())
          .setDimension(level.dimension().identifier().toString()));
      }
      result.complete(builder.build());
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
      return "Sleep";
    }
  }

  private enum Stage {
    FIND_BED,
    NAVIGATE,
    INTERACT,
    WAIT_FOR_CONFIRMATION
  }
}
