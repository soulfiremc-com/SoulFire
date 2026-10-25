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
import com.soulfiremc.grpc.generated.ExcavateCompletionReason;
import com.soulfiremc.grpc.generated.ExcavateTask;
import com.soulfiremc.grpc.generated.ExcavateTaskResult;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.DelegatePathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Clears an inclusive cuboid without allowing the pathfinder to mine outside
/// the requested region.
public final class ExcavateTaskProvider implements BotTaskProvider<ExcavateTask> {
  private static final long MAX_REGION_VOLUME = 32_768;
  private static final int RECOVERY_CHUNK_TICKS = 100;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public ExcavateTask inputPrototype() {
    return ExcavateTask.getDefaultInstance();
  }

  @Override
  public String summary(ExcavateTask input) {
    return input.getMaximumBlocks() == 0
      ? "Excavate a cuboid"
      : "Excavate up to " + input.getMaximumBlocks() + " blocks";
  }

  @Override
  public Set<ControlResource> resources(ExcavateTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, ExcavateTask input) {
    return start(context, input, new RecoveryState());
  }

  @Override
  public ExecutionFactory prepare(ExcavateTask input) {
    var recovery = new RecoveryState();
    return context -> start(context, input, recovery);
  }

  private BotTaskExecution start(BotTaskContext context, ExcavateTask input, RecoveryState recovery) {
    if (!input.hasCornerA() || !input.hasCornerB()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("corner_a and corner_b must be set")
        .asRuntimeException();
    }
    var level = Objects.requireNonNull(
      context.bot().minecraft().level,
      "Bot level is not available"
    );
    validateDimension(input.getCornerA(), level.dimension().identifier().toString(), "corner_a");
    validateDimension(input.getCornerB(), level.dimension().identifier().toString(), "corner_b");
    var from = toBlockPos(input.getCornerA());
    var to = toBlockPos(input.getCornerB());
    validateVolume(from, to);

    var minimum = new BlockPos(
      Math.min(from.getX(), to.getX()),
      Math.min(from.getY(), to.getY()),
      Math.min(from.getZ(), to.getZ())
    );
    var maximum = new BlockPos(
      Math.max(from.getX(), to.getX()),
      Math.max(from.getY(), to.getY()),
      Math.max(from.getZ(), to.getZ())
    );
    if (minimum.getY() < level.getMinY() || maximum.getY() >= level.getMaxY()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Excavation cuboid extends outside the dimension build height")
        .asRuntimeException();
    }

    var recovering = recovery.region != null;
    if (!recovering) {
      recovery.region = selectRegion(level,
        Objects.requireNonNull(context.bot().minecraft().player).blockPosition(),
        minimum, maximum, input.getMaximumBlocks());
      recovery.pending.addAll(recovery.region.selected());
    }
    var region = Objects.requireNonNull(recovery.region);
    var baseConstraint = PathfindingSupport.buildConstraint(
      context.bot(),
      input.getOptions().toBuilder().setAllowMining(true).build()
    );
    var result = new CompletableFuture<ExcavateTaskResult>();
    var control = new ExcavateControl(
      context,
      recovery,
      recovering,
      restrictDamage(baseConstraint, region.diggable()),
      result
    );
    return new BotTaskExecution(control, result);
  }

  static Region selectRegion(
    ClientLevel level,
    BlockPos origin,
    BlockPos minimum,
    BlockPos maximum,
    int maximumBlocks
  ) {
    var allDiggable = new LinkedHashSet<SFVec3i>();
    var skipped = 0;
    var chunks = level.getChunkSource();
    for (var chunkX = minimum.getX() >> 4; chunkX <= (maximum.getX() >> 4); chunkX++) {
      for (var chunkZ = minimum.getZ() >> 4; chunkZ <= (maximum.getZ() >> 4); chunkZ++) {
        var chunk = chunks.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Excavation cuboid contains an unloaded chunk at %d, %d".formatted(chunkX, chunkZ))
            .asRuntimeException();
        }
        var minX = Math.max(minimum.getX(), chunkX << 4);
        var maxX = Math.min(maximum.getX(), (chunkX << 4) + 15);
        var minZ = Math.max(minimum.getZ(), chunkZ << 4);
        var maxZ = Math.min(maximum.getZ(), (chunkZ << 4) + 15);
        for (var sectionY = minimum.getY() >> 4; sectionY <= (maximum.getY() >> 4); sectionY++) {
          var section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
          var minY = Math.max(minimum.getY(), sectionY << 4);
          var maxY = Math.min(maximum.getY(), (sectionY << 4) + 15);
          if (section.hasOnlyAir()) {
            skipped += (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            continue;
          }
          for (var position : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            var state = section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
            if (isDiggable(level, position, state)) {
              allDiggable.add(SFVec3i.fromInt(position));
            } else {
              skipped++;
            }
          }
        }
      }
    }

    var limited = maximumBlocks > 0
      && allDiggable.size() > maximumBlocks;
    var selected = allDiggable.stream()
      .sorted(Comparator.comparingDouble((SFVec3i position) -> position.toBlockPos().distSqr(origin))
        .thenComparingInt(position -> position.x)
        .thenComparingInt(position -> position.y)
        .thenComparingInt(position -> position.z))
      .limit(maximumBlocks == 0
        ? allDiggable.size()
        : maximumBlocks)
      .collect(
        java.util.stream.Collectors.toCollection(LinkedHashSet::new)
      );
    return new Region(List.copyOf(selected), Set.copyOf(allDiggable), skipped, limited,
      level.dimension().identifier().toString());
  }

  private static void validateDimension(
    BlockPosition position,
    String dimension,
    String field
  ) {
    if (!position.getDimension().isBlank()
      && !position.getDimension().equals(dimension)) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "%s is in '%s', but the bot is in '%s'"
            .formatted(field, position.getDimension(), dimension)
        )
        .asRuntimeException();
    }
  }

  private static BlockPos toBlockPos(BlockPosition position) {
    return new BlockPos(position.getX(), position.getY(), position.getZ());
  }

  private static void validateVolume(BlockPos from, BlockPos to) {
    var sizeX = Math.abs((long) from.getX() - to.getX()) + 1;
    var sizeY = Math.abs((long) from.getY() - to.getY()) + 1;
    var sizeZ = Math.abs((long) from.getZ() - to.getZ()) + 1;
    var volume = sizeX * sizeY * sizeZ;
    if (volume > MAX_REGION_VOLUME) {
      throw Status.RESOURCE_EXHAUSTED
        .withDescription(
          "Excavation cuboid contains %d blocks; maximum is %d"
            .formatted(volume, MAX_REGION_VOLUME)
        )
        .asRuntimeException();
    }
  }

  private static boolean isDiggable(
    net.minecraft.world.level.Level level,
    BlockPos position,
    BlockState state
  ) {
    return !state.isAir()
      && !state.canBeReplaced()
      && state.getDestroySpeed(level, position) >= 0;
  }

  private static PathConstraint restrictDamage(
    PathConstraint delegate,
    Set<SFVec3i> regionBlocks
  ) {
    return new DelegatePathConstraint() {
      @Override
      public boolean canBreakBlock(SFVec3i position, BlockState state) {
        return regionBlocks.contains(position)
          && delegate.canBreakBlock(position, state);
      }

      @Override
      public boolean canPlaceBlock(SFVec3i position) {
        return !regionBlocks.contains(position)
          && delegate.canPlaceBlock(position);
      }

      @Override
      public PathConstraint delegate() {
        return delegate;
      }
    };
  }

  record Region(
    List<SFVec3i> selected,
    Set<SFVec3i> diggable,
    int skipped,
    boolean limited,
    String dimension
  ) {}

  private static final class RecoveryState {
    private @Nullable Region region;
    private final Set<SFVec3i> pending = new LinkedHashSet<>();
    private int blocksBroken;
    private int unreachable;
  }

  private static final class ExcavateControl implements ControlTask {
    private final BotTaskContext context;
    private final RecoveryState recovery;
    private boolean recoveryPending;
    private int recoveryTicks;
    private final PathConstraint constraint;
    private final CompletableFuture<ExcavateTaskResult> result;
    private @Nullable PathExecutor activePath;
    private @Nullable SFVec3i activeTarget;

    private ExcavateControl(
      BotTaskContext context,
      RecoveryState recovery,
      boolean recovering,
      PathConstraint constraint,
      CompletableFuture<ExcavateTaskResult> result
    ) {
      this.context = context;
      this.recovery = recovery;
      recoveryPending = recovering;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      if (recoveryPending) {
        if (!recoverRegion()) {
          return;
        }
        recoveryPending = false;
      }
      refreshClearedBlocks();
      if (activePath != null) {
        tickPath();
        return;
      }
      if (recovery.pending.isEmpty()) {
        complete(recovery.unreachable > 0 && recovery.blocksBroken == 0
          ? ExcavateCompletionReason.EXCAVATE_COMPLETION_REASON_NO_REACHABLE_BLOCKS
          : region().limited()
            ? ExcavateCompletionReason.EXCAVATE_COMPLETION_REASON_BLOCK_LIMIT_REACHED
            : ExcavateCompletionReason.EXCAVATE_COMPLETION_REASON_AREA_CLEARED);
        return;
      }

      var player = context.bot().minecraft().player;
      if (player == null) {
        result.completeExceptionally(new IllegalStateException("Bot player is not available"));
        return;
      }
      activeTarget = recovery.pending.stream()
        .min(Comparator.comparingDouble(position ->
          position.toBlockPos().distSqr(player.blockPosition())))
        .orElseThrow();
      context.reportProgress(progress("Planning excavation route"));
      activePath = PathExecutor.createPathfinding(
        context.bot(),
        new BreakBlockPosGoal(activeTarget),
        constraint
      );
      activePath.onStarted();
    }

    private Region region() {
      return Objects.requireNonNull(recovery.region);
    }

    private boolean recoverRegion() {
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!level.dimension().identifier().toString().equals(region().dimension())) {
        result.completeExceptionally(Status.FAILED_PRECONDITION
          .withDescription("Cannot resume excavation in another dimension")
          .asRuntimeException());
        return false;
      }
      if (recovery.pending.stream().anyMatch(position ->
        !SFBlockHelpers.isChunkLoaded(level, position.toBlockPos()))) {
        context.reportProgress(progress("Waiting for excavation chunks"));
        if (++recoveryTicks >= RECOVERY_CHUNK_TICKS) {
          result.completeExceptionally(Status.DEADLINE_EXCEEDED
            .withDescription("Excavation chunks did not arrive after reconnect")
            .asRuntimeException());
        }
        return false;
      }
      return true;
    }

    private void tickPath() {
      var path = activePath;
      if (path == null) {
        return;
      }
      if (!path.isDone()) {
        path.tick();
        context.reportProgress(progress(
          path.progress().planning()
            ? "Planning excavation route"
            : "Excavating block"
        ));
        return;
      }

      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
      } catch (CompletionException exception) {
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        path.onStopped(ControlStopReason.FAILED, cause);
        if (activeTarget != null && recovery.pending.remove(activeTarget)) {
          recovery.unreachable++;
        }
      } finally {
        activeTarget = null;
      }
      refreshClearedBlocks();
    }

    private void refreshClearedBlocks() {
      var level = context.bot().minecraft().level;
      if (level == null) {
        return;
      }
      var cleared = recovery.pending.stream()
        .filter(position -> {
          var blockPosition = position.toBlockPos();
          if (!SFBlockHelpers.isChunkLoaded(level, blockPosition)) {
            return false;
          }
          var state = level.getBlockState(blockPosition);
          return !isDiggable(level, blockPosition, state);
        })
        .toList();
      recovery.pending.removeAll(cleared);
      recovery.blocksBroken += cleared.size();
    }

    private BotTaskProgress progress(String message) {
      var total = region().selected().size();
      return BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(recovery.blocksBroken + recovery.unreachable)
        .setTotal(total)
        .setFraction(total == 0
          ? 1.0
          : Math.min(
            1.0,
            (double) (recovery.blocksBroken + recovery.unreachable) / total
          ))
        .build();
    }

    private void complete(ExcavateCompletionReason reason) {
      var builder = ExcavateTaskResult.newBuilder()
        .setReason(reason)
        .setBlocksBroken(recovery.blocksBroken)
        .setBlocksSkipped(region().skipped())
        .setUnreachableBlocks(recovery.unreachable);
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      if (player != null && level != null) {
        builder.setFinalPosition(WorldPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .setDimension(level.dimension().identifier().toString()));
      }
      result.complete(builder.build());
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public void onSuspended() {
      if (activePath != null) {
        activePath.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      if (activePath != null) {
        activePath.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var path = activePath;
      activePath = null;
      activeTarget = null;
      if (path != null) {
        path.onStopped(reason, cause);
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Excavate cuboid";
    }
  }
}
