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

import com.google.protobuf.Any;
import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.CollectBlocksCompletionReason;
import com.soulfiremc.grpc.generated.CollectBlocksTask;
import com.soulfiremc.grpc.generated.CollectBlocksTaskProgressDetail;
import com.soulfiremc.grpc.generated.CollectBlocksTaskResult;
import com.soulfiremc.grpc.generated.IntRange;
import com.soulfiremc.grpc.generated.PathfindOptions;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.BlockBreakRejectedException;
import com.soulfiremc.server.pathfinding.execution.BlockPlaceRejectedException;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.execution.UnreachableGoalException;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.CompositeGoal;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.goals.WithinBlockReachGoal;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.pathfinding.graph.constraint.BlockBreakBlacklistConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/// Durable block collection provider backed by repeated live path searches.
public final class CollectBlocksTaskProvider
  implements BotTaskProvider<CollectBlocksTask> {
  private static final int DEFAULT_SEARCH_RADIUS = 32;
  private static final int RECOVERY_OBSERVATION_TICKS = 100;
  private static final int MAX_SEARCH_RADIUS = 64;
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_FAILED_APPROACHES_PER_TARGET = 4;
  private static final int MAX_CONSECUTIVE_STALLED_PATHS = 16;
  private static final double DIRECT_BREAK_REACH_SQUARED = 4.5D * 4.5D;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public CollectBlocksTask inputPrototype() {
    return CollectBlocksTask.getDefaultInstance();
  }

  @Override
  public String summary(CollectBlocksTask input) {
    return "Collect " + Math.max(1, input.getCount()) + " matching blocks";
  }

  @Override
  public Set<ControlResource> resources(CollectBlocksTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    CollectBlocksTask input
  ) {
    return start(context, input, new RecoveryState());
  }

  @Override
  public ExecutionFactory prepare(CollectBlocksTask input) {
    var recovery = new RecoveryState();
    return context -> start(context, input, recovery);
  }

  private BotTaskExecution start(BotTaskContext context, CollectBlocksTask input, RecoveryState recovery) {
    if (input.getBlockIdsList().isEmpty() && input.getTagsList().isEmpty()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("block_ids or tags must contain at least one selector")
        .asRuntimeException();
    }
    var count = Math.max(1, input.getCount());
    var radius = input.getSearchRadius() == 0
      ? DEFAULT_SEARCH_RADIUS
      : Math.min(input.getSearchRadius(), MAX_SEARCH_RADIUS);
    var result = new CompletableFuture<CollectBlocksTaskResult>();
    var control = new CollectBlocksControl(
      context,
      normalize(input.getBlockIdsList()),
      normalize(input.getTagsList()),
      count,
      radius,
      input.getOptions(),
      input.getAvoidSubmergedTargets(),
      input.getRequireLineOfSight(),
      input.hasTargetYRange() ? input.getTargetYRange() : null,
      result,
      recovery
    );
    return new BotTaskExecution(control, result);
  }

  private static Set<String> normalize(List<String> values) {
    return values.stream()
      .map(value -> value.indexOf(':') < 0
        ? "minecraft:" + value
        : value)
      .collect(Collectors.toUnmodifiableSet());
  }

  static boolean isDirectBreakCandidate(
    BlockPos playerFeet,
    BlockPos target
  ) {
    if (target.getY() >= playerFeet.getY()) {
      return true;
    }
    return target.getY() == playerFeet.getY() - 1
      && (
        target.getX() != playerFeet.getX()
          || target.getZ() != playerFeet.getZ()
      );
  }

  static boolean hasFluidAbove(
    BlockGetter level,
    BlockPos playerFeet,
    BlockPos target
  ) {
    for (
      var y = target.getY() + 1;
      y <= playerFeet.getY() + 1;
      y++
    ) {
      if (!level.getFluidState(target.atY(y)).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static Optional<BlockFace> findDirectBreakFace(
    BlockGetter level,
    LocalPlayer player,
    BlockPos target
  ) {
    var eyePosition = player.getEyePosition();
    var blockPosition = SFVec3i.fromInt(target);
    return directBreakFaces(eyePosition, blockPosition).stream()
      .filter(face -> {
        var hit = level.clip(new ClipContext(
          eyePosition,
          face.getMiddleOfFace(blockPosition),
          ClipContext.Block.OUTLINE,
          ClipContext.Fluid.NONE,
          player
        ));
        return hitsTargetBlock(target, hit);
      })
      .findFirst();
  }

  static List<BlockFace> directBreakFaces(
    Vec3 eyePosition,
    SFVec3i target
  ) {
    return List.of(BlockFace.VALUES).stream()
      .filter(face ->
        face.getMiddleOfFace(target).distanceToSqr(eyePosition)
          <= DIRECT_BREAK_REACH_SQUARED
      )
      .sorted(Comparator.comparingDouble(face ->
        face.getMiddleOfFace(target).distanceToSqr(eyePosition)
      ))
      .toList();
  }

  static boolean hitsTargetBlock(BlockPos target, HitResult hit) {
    return hit instanceof BlockHitResult blockHit
      && blockHit.getBlockPos().equals(target);
  }

  static boolean hasLineOfSight(
    BlockGetter level,
    Vec3 eyePosition,
    BlockPos target
  ) {
    var blockPosition = SFVec3i.fromInt(target);
    return List.of(BlockFace.VALUES).stream()
      .map(face -> level.clip(new ClipContext(
        eyePosition,
        face.getMiddleOfFace(blockPosition),
        ClipContext.Block.OUTLINE,
        ClipContext.Fluid.NONE,
        CollisionContext.empty()
      )))
      .anyMatch(hit -> hitsTargetBlock(target, hit));
  }

  static boolean isWithinTargetY(
    int y,
    @Nullable IntRange targetYRange
  ) {
    return targetYRange == null
      || (!targetYRange.hasMinimum() || y >= targetYRange.getMinimum())
      && (!targetYRange.hasMaximum() || y <= targetYRange.getMaximum());
  }

  static Set<SFVec3i> stalledTargets(
    Set<SFVec3i> attemptedTargets,
    SFVec3i playerPosition
  ) {
    var adjacentTargets = attemptedTargets.stream()
      .filter(target -> WithinBlockReachGoal.isWithinReach(
        target,
        playerPosition
      ))
      .collect(Collectors.toUnmodifiableSet());
    if (!adjacentTargets.isEmpty()) {
      return adjacentTargets;
    }
    return attemptedTargets.stream()
      .min(Comparator.comparingDouble(playerPosition::distance))
      .map(Set::of)
      .orElseGet(Set::of);
  }

  static boolean recordFailedApproach(
    Map<SFVec3i, Set<SFVec3i>> failedApproaches,
    Set<SFVec3i> rejectedTargets,
    SFVec3i target,
    SFVec3i playerPosition
  ) {
    var positions = failedApproaches.computeIfAbsent(
      target,
      _ -> new HashSet<>()
    );
    if (!positions.add(playerPosition)) {
      failedApproaches.remove(target);
      return rejectedTargets.add(target);
    }
    if (positions.size() >= MAX_FAILED_APPROACHES_PER_TARGET) {
      failedApproaches.remove(target);
      rejectedTargets.add(target);
    }
    return true;
  }

  static boolean hasRequiredLineOfSight(
    boolean requireLineOfSight,
    boolean previouslyAttempted,
    BooleanSupplier lineOfSight
  ) {
    return !requireLineOfSight
      || previouslyAttempted
      || lineOfSight.getAsBoolean();
  }

  static Set<SFVec3i> scanCandidates(
    ClientLevel level, BlockPos origin, int searchRadius, BiPredicate<BlockPos, BlockState> matches
  ) {
    var radiusSquared = searchRadius * searchRadius;
    var minimumY = Math.max(level.getMinY(), origin.getY() - searchRadius);
    var maximumY = Math.min(level.getMaxY() - 1, origin.getY() + searchRadius);
    var candidates = new HashSet<SFVec3i>();
    var chunks = level.getChunkSource();
    for (var chunkX = (origin.getX() - searchRadius) >> 4; chunkX <= (origin.getX() + searchRadius) >> 4; chunkX++) {
      for (var chunkZ = (origin.getZ() - searchRadius) >> 4; chunkZ <= (origin.getZ() + searchRadius) >> 4; chunkZ++) {
        var chunk = chunks.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) continue;
        var minX = Math.max(origin.getX() - searchRadius, chunkX << 4);
        var maxX = Math.min(origin.getX() + searchRadius, (chunkX << 4) + 15);
        var minZ = Math.max(origin.getZ() - searchRadius, chunkZ << 4);
        var maxZ = Math.min(origin.getZ() + searchRadius, (chunkZ << 4) + 15);
        for (var sectionY = minimumY >> 4; sectionY <= maximumY >> 4; sectionY++) {
          var section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
          if (section.hasOnlyAir()) continue;
          var minY = Math.max(minimumY, sectionY << 4);
          var maxY = Math.min(maximumY, (sectionY << 4) + 15);
          for (var position : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (position.distSqr(origin) > radiusSquared) continue;
            var state = section.getBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
            if (!state.isAir() && matches.test(position, state)) candidates.add(SFVec3i.fromInt(position));
          }
        }
      }
    }
    return candidates;
  }

  static Comparator<SFVec3i> candidateComparator(BlockPos origin) {
    return Comparator
      .comparingDouble((SFVec3i position) ->
        position.toBlockPos().distSqr(origin)
      )
      .thenComparingInt(position -> position.y)
      .thenComparingInt(position -> position.x)
      .thenComparingInt(position -> position.z);
  }

  static Set<GoalScorer> collectionGoals(
    BlockGetter level,
    Vec3 eyePosition,
    SFVec3i target,
    boolean includeOccludedReachGoals,
    Map<SFVec3i, Set<SFVec3i>> rejectedAdjacentPositions
  ) {
    var goals = new HashSet<GoalScorer>();
    goals.add(new BreakBlockPosGoal(target));
    var visible = hasLineOfSight(
      level,
      eyePosition,
      target.toBlockPos()
    );
    if (
      visible
        || (
          includeOccludedReachGoals
            && !rejectedAdjacentPositions.containsKey(target)
        )
    ) {
      goals.add(new WithinBlockReachGoal(
        target,
        rejectedAdjacentPositions.getOrDefault(target, Set.of())
      ));
    }
    return Set.copyOf(goals);
  }

  private static final class CollectBlocksControl implements ControlTask {
    private final BotTaskContext context;
    private final Set<String> blockIds;
    private final Set<String> tags;
    private final int targetCount;
    private final int searchRadius;
    private final PathfindOptions pathOptions;
    private final boolean avoidSubmergedTargets;
    private final boolean requireLineOfSight;
    private final @Nullable IntRange targetYRange;
    private final CompletableFuture<CollectBlocksTaskResult> result;
    private final RecoveryState recovery;
    private boolean active;
    private boolean recoveryPending;
    private int observationTicks;
    private @Nullable PathExecutor activePath;
    private @Nullable BlockBreakAction activeNearbyBreak;
    private @Nullable DirectBreakTarget activeNearbyTarget;
    private int activeNearbyBreakTicks;
    private Set<SFVec3i> activeTargets = Set.of();

    private CollectBlocksControl(
      BotTaskContext context,
      Set<String> blockIds,
      Set<String> tags,
      int targetCount,
      int searchRadius,
      PathfindOptions pathOptions,
      boolean avoidSubmergedTargets,
      boolean requireLineOfSight,
      @Nullable IntRange targetYRange,
      CompletableFuture<CollectBlocksTaskResult> result,
      RecoveryState recovery
    ) {
      this.context = context;
      this.blockIds = blockIds;
      this.tags = tags;
      this.targetCount = targetCount;
      this.searchRadius = searchRadius;
      this.pathOptions = pathOptions;
      this.avoidSubmergedTargets = avoidSubmergedTargets;
      this.requireLineOfSight = requireLineOfSight;
      this.targetYRange = targetYRange;
      this.result = result;
      this.recovery = recovery;
      recoveryPending = recovery.dimension != null;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      if (!recoverInterruptedWork()) {
        return;
      }
      if (activePath != null) {
        if (stopActivePathWithoutDropTool()) {
          return;
        }
        tickActivePath();
        return;
      }
      if (activeNearbyBreak != null) {
        tickNearbyBreak();
        return;
      }
      if (recovery.blocksBroken >= targetCount) {
        complete(
          CollectBlocksCompletionReason
            .COLLECT_BLOCKS_COMPLETION_REASON_TARGET_REACHED
        );
        return;
      }

      var nearbyTarget = findReachableCandidate();
      if (nearbyTarget.isPresent()) {
        startNearbyBreak(nearbyTarget.get());
        return;
      }

      var candidates = findCandidates();
      if (candidates.isEmpty()) {
        complete(
          recovery.rejectedTargets.isEmpty()
            ? CollectBlocksCompletionReason
              .COLLECT_BLOCKS_COMPLETION_REASON_NO_MATCHING_BLOCKS
            : CollectBlocksCompletionReason
              .COLLECT_BLOCKS_COMPLETION_REASON_NO_REACHABLE_BLOCKS
        );
        return;
      }
      var target = candidates.getFirst();
      activeTargets = Set.of(target);
      recovery.observedPosition = target;
      context.reportProgress(progress(
        "Planning route to matching block",
        CollectBlocksTaskProgressDetail.Phase.PHASE_PLANNING_ROUTE
      ));
      PathConstraint constraint = PathfindingSupport.buildConstraint(
        context.bot(),
        pathOptions
      );
      if (!recovery.rejectedTargets.isEmpty()) {
        constraint = new BlockBreakBlacklistConstraint(
          constraint,
          recovery.rejectedTargets
        );
      }
      var level = context.bot().minecraft().level;
      var player = context.bot().minecraft().player;
      if (level == null || player == null) {
        complete(
          CollectBlocksCompletionReason
            .COLLECT_BLOCKS_COMPLETION_REASON_NO_REACHABLE_BLOCKS
        );
        return;
      }
      activePath = PathExecutor.createPathfinding(
        context.bot(),
        new CompositeGoal(collectionGoals(
          level,
          player.getEyePosition(),
          target,
          !requireLineOfSight,
          recovery.rejectedAdjacentPositions
        )),
        constraint
      );
      activePath.onStarted();
    }

    private boolean recoverInterruptedWork() {
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      if (!level.dimension().identifier().toString().equals(recovery.dimension)) {
        result.completeExceptionally(Status.FAILED_PRECONDITION
          .withDescription("Cannot resume block collection in another dimension").asRuntimeException());
        return false;
      }
      if (recoveryPending) {
        var anchor = Objects.requireNonNull(recovery.observedPosition).toBlockPos();
        if (!SFBlockHelpers.isChunkLoaded(level, anchor)) {
          context.reportProgress(progress("Waiting for collection target chunk",
            CollectBlocksTaskProgressDetail.Phase.PHASE_PLANNING_ROUTE));
          if (++observationTicks >= RECOVERY_OBSERVATION_TICKS) {
            result.completeExceptionally(Status.DEADLINE_EXCEEDED
              .withDescription("Collection target chunk did not arrive after reconnect").asRuntimeException());
          }
          return false;
        }
        observationTicks = 0;
        recoveryPending = false;
      }
      var pending = recovery.pendingBreak;
      if (pending == null) return true;
      var position = pending.toBlockPos();
      if (!SFBlockHelpers.isChunkLoaded(level, position)
        || BlockPredictionSupport.hasPendingPrediction(context.bot(), position)) {
        recovery.clearedTicks = 0;
        context.reportProgress(progress("Waiting to confirm interrupted block collection",
          CollectBlocksTaskProgressDetail.Phase.PHASE_BREAKING_BLOCK));
        if (++observationTicks >= RECOVERY_OBSERVATION_TICKS) {
          result.completeExceptionally(Status.DEADLINE_EXCEEDED
            .withDescription("Interrupted collection block did not become observable").asRuntimeException());
        }
        return false;
      }
      observationTicks = 0;
      if (BlockPredictionSupport.isClearedBreakTarget(level.getBlockState(position))) {
        if (++recovery.clearedTicks < BlockBreakAction.CLEARED_STATE_SETTLE_TICKS) return false;
        recovery.blocksBroken = Math.min(targetCount, recovery.blocksBroken + 1);
        context.reportProgress(progress("Interrupted matching block confirmed mined",
          CollectBlocksTaskProgressDetail.Phase.PHASE_BREAKING_BLOCK));
      }
      recovery.pendingBreak = null;
      recovery.clearedTicks = 0;
      return true;
    }

    private void checkpointNearbyBreak() {
      if (activeNearbyBreak != null && activeNearbyTarget != null && activeNearbyBreak.breakAttempted()) {
        recovery.pendingBreak = activeNearbyTarget.position();
        recovery.clearedTicks = 0;
      }
    }

    private void stopPath(ControlStopReason reason, @Nullable Throwable cause) {
      var path = activePath;
      activePath = null;
      if (path != null) {
        var completed = path.completedBlockBreaks();
        recovery.blocksBroken += (int) Math.min(completed.stream().filter(activeTargets::contains).count(),
          targetCount - recovery.blocksBroken);
        path.pendingBlockBreak().filter(activeTargets::contains).filter(position -> !completed.contains(position))
          .ifPresent(position -> {
            recovery.pendingBreak = position;
            recovery.clearedTicks = 0;
          });
        path.onStopped(reason, cause);
      }
      activeTargets = Set.of();
    }

    private void startNearbyBreak(DirectBreakTarget target) {
      activeNearbyTarget = target;
      recovery.observedPosition = target.position();
      activeNearbyBreak = new BlockBreakAction(
        target.position(),
        target.face()
      );
      activeNearbyBreakTicks = 0;
      activeTargets = Set.of(target.position());
      context.reportProgress(progress(
        "Mining nearby matching block",
        CollectBlocksTaskProgressDetail.Phase.PHASE_BREAKING_BLOCK
      ));
    }

    private void tickNearbyBreak() {
      var blockBreak = activeNearbyBreak;
      var target = activeNearbyTarget;
      if (blockBreak == null || target == null) {
        clearNearbyBreak(false);
        return;
      }
      if (blockBreak.isRejected(context.bot())) {
        recovery.rejectedTargets.add(target.position());
        clearNearbyBreak(true);
        context.reportProgress(progress(
          "Skipping a nearby block rejected by the server",
          CollectBlocksTaskProgressDetail.Phase.PHASE_SKIPPING_TARGET
        ));
        return;
      }
      if (blockBreak.isCompleted(context.bot())) {
        if (blockBreak.breakAttempted()) {
          recovery.blocksBroken++;
          context.reportProgress(progress(
            "Nearby matching block mined",
            CollectBlocksTaskProgressDetail.Phase.PHASE_BREAKING_BLOCK
          ));
        }
        clearNearbyBreak(false);
        return;
      }
      blockBreak.tick(context.bot());
      activeNearbyBreakTicks++;
      if (
        activeNearbyBreakTicks
          > blockBreak.getAllowedTicks() + 20
      ) {
        recovery.rejectedTargets.add(target.position());
        clearNearbyBreak(true);
        context.reportProgress(progress(
          "Skipping a nearby block that could not be mined",
          CollectBlocksTaskProgressDetail.Phase.PHASE_SKIPPING_TARGET
        ));
      }
    }

    private void tickActivePath() {
      var path = activePath;
      if (path == null) {
        return;
      }
      if (!path.isDone()) {
        path.tick();
        var confirmedBreaks = confirmedBreaks(path);
        var remaining = targetCount - recovery.blocksBroken;
        if (confirmedBreaks >= remaining) {
          recovery.blocksBroken = targetCount;
          activeTargets = Set.of();
          activePath = null;
          path.completeEarly();
          complete(
            CollectBlocksCompletionReason
              .COLLECT_BLOCKS_COMPLETION_REASON_TARGET_REACHED
          );
          return;
        }
        var pathProgress = path.progress();
        context.reportProgress(progress(
          pathProgress.planning()
            ? "Planning collection route"
            : "Following collection route",
          pathProgress.planning()
            ? CollectBlocksTaskProgressDetail.Phase.PHASE_PLANNING_ROUTE
            : CollectBlocksTaskProgressDetail.Phase.PHASE_FOLLOWING_ROUTE
        ));
        return;
      }
      activePath = null;
      try {
        path.completion().join();
        path.onStopped(ControlStopReason.COMPLETED, null);
        var confirmedBreaks = confirmedBreaks(path);
        var completedTargets = activeTargets;
        activeTargets = Set.of();
        if (confirmedBreaks == 0) {
          rejectStalledAdjacentPositions(completedTargets);
          if (reachedStalledPathLimit()) {
            complete(
              CollectBlocksCompletionReason
                .COLLECT_BLOCKS_COMPLETION_REASON_NO_REACHABLE_BLOCKS
            );
            return;
          }
          context.reportProgress(progress(
            "Trying another approach to a matching block",
            CollectBlocksTaskProgressDetail.Phase.PHASE_RETRYING_APPROACH
          ));
          return;
        }
        recovery.consecutiveStalledPaths = 0;
        recovery.blocksBroken += (int) Math.min(
          confirmedBreaks,
          targetCount - recovery.blocksBroken
        );
        context.reportProgress(progress(
          "Matching block mined",
          CollectBlocksTaskProgressDetail.Phase.PHASE_BREAKING_BLOCK
        ));
      } catch (CompletionException exception) {
        var confirmedBreaks = confirmedBreaks(path);
        recovery.blocksBroken += (int) Math.min(
          confirmedBreaks,
          targetCount - recovery.blocksBroken
        );
        if (confirmedBreaks > 0) {
          recovery.consecutiveStalledPaths = 0;
        }
        var failedTargets = activeTargets;
        activeTargets = Set.of();
        if (recovery.blocksBroken >= targetCount) {
          complete(
            CollectBlocksCompletionReason
              .COLLECT_BLOCKS_COMPLETION_REASON_TARGET_REACHED
          );
          return;
        }
        var cause = exception.getCause() == null
          ? exception
          : exception.getCause();
        path.onStopped(ControlStopReason.FAILED, cause);
        if (cause instanceof UnreachableGoalException) {
          recovery.rejectedTargets.addAll(failedTargets);
          if (!reachedStalledPathLimit()) {
            context.reportProgress(progress(
              "Trying another matching block after route planning failed",
              CollectBlocksTaskProgressDetail.Phase.PHASE_RETRYING_APPROACH
            ));
            return;
          }
          complete(
            CollectBlocksCompletionReason
              .COLLECT_BLOCKS_COMPLETION_REASON_NO_REACHABLE_BLOCKS
          );
          return;
        }
        if (
          cause instanceof BlockBreakRejectedException rejection
        ) {
          recovery.rejectedTargets.add(rejection.blockPosition());
          reachedStalledPathLimit();
          context.reportProgress(progress(
            "Skipping a matching block rejected by the server",
            CollectBlocksTaskProgressDetail.Phase.PHASE_SKIPPING_TARGET
          ));
          return;
        }
        if (cause instanceof BlockPlaceRejectedException) {
          recovery.rejectedTargets.addAll(failedTargets);
          reachedStalledPathLimit();
          context.reportProgress(progress(
            "Skipping targets whose route requires a rejected placement",
            CollectBlocksTaskProgressDetail.Phase.PHASE_SKIPPING_TARGET
          ));
          return;
        }
        result.completeExceptionally(cause);
      }
    }

    private boolean reachedStalledPathLimit() {
      recovery.consecutiveStalledPaths++;
      return recovery.consecutiveStalledPaths >= MAX_CONSECUTIVE_STALLED_PATHS;
    }

    private boolean rejectStalledAdjacentPositions(
      Set<SFVec3i> attemptedTargets
    ) {
      if (context.bot().minecraft().player == null
        || context.bot().minecraft().level == null) {
        return false;
      }
      // Not blockPosition(), which is one below the node on farmland
      var playerPosition = PathExecutor.playerNode(context.bot());
      var rejectedAny = false;
      for (var target : stalledTargets(
        attemptedTargets,
        playerPosition
      )) {
        rejectedAny |= recordFailedApproach(
          recovery.rejectedAdjacentPositions,
          recovery.rejectedTargets,
          target,
          playerPosition
        );
      }
      return rejectedAny;
    }

    private long confirmedBreaks(PathExecutor path) {
      return path.completedBlockBreaks().stream()
        .filter(activeTargets::contains)
        .count();
    }

    private List<SFVec3i> findCandidates() {
      var bot = context.bot();
      var player = bot.minecraft().player;
      var level = bot.minecraft().level;
      if (player == null || level == null) {
        return List.of();
      }
      var origin = player.blockPosition();
      var candidates = scanCandidates(level, origin, searchRadius, this::matches);
      return candidates.stream()
        .sorted(candidateComparator(origin))
        .filter(position -> canHarvest(
          level.getBlockState(position.toBlockPos())
        ))
        .filter(position -> hasRequiredLineOfSight(
          requireLineOfSight,
          recovery.rejectedAdjacentPositions.containsKey(position),
          () -> hasLineOfSight(
            level,
            player.getEyePosition(),
            position.toBlockPos()
          )
        ))
        .filter(position ->
          !avoidSubmergedTargets || !hasFluidAbove(level, origin, position.toBlockPos())
        )
        .limit(MAX_CANDIDATES)
        .toList();
    }

    private Optional<DirectBreakTarget> findReachableCandidate() {
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      if (player == null || level == null) {
        return Optional.empty();
      }
      var origin = player.blockPosition();
      var eyePosition = player.getEyePosition();
      var candidates = new HashSet<DirectBreakTarget>();
      var radiusSquared = searchRadius * searchRadius;
      for (var x = -5; x <= 5; x++) {
        for (var z = -5; z <= 5; z++) {
          for (var y = -4; y <= 6; y++) {
            if (x * x + y * y + z * z > radiusSquared) {
              continue;
            }
            var position = origin.offset(x, y, z);
            if (
              !isDirectBreakCandidate(origin, position)
                || !SFBlockHelpers.isChunkLoaded(level, position)
                || (
                  avoidSubmergedTargets
                    && hasFluidAbove(level, origin, position)
                )
            ) {
              continue;
            }
            var state = level.getBlockState(position);
            if (!matches(position, state) || !canHarvest(state)) {
              continue;
            }
            findDirectBreakFace(level, player, position)
              .map(face -> new DirectBreakTarget(
                SFVec3i.fromInt(position),
                face
              ))
              .ifPresent(candidates::add);
          }
        }
      }
      return candidates.stream()
        .min(Comparator
          .comparingInt((DirectBreakTarget target) -> {
            var block = target.position().toBlockPos();
            var deltaX = block.getX() - origin.getX();
            var deltaZ = block.getZ() - origin.getZ();
            return deltaX * deltaX + deltaZ * deltaZ;
          })
          .thenComparingDouble(target ->
            target.face().getMiddleOfFace(target.position())
              .distanceToSqr(eyePosition)
        ));
    }

    private boolean stopActivePathWithoutDropTool() {
      var path = activePath;
      var level = context.bot().minecraft().level;
      if (path == null || level == null) {
        return false;
      }
      var hasHarvestableTarget = activeTargets.stream()
        .anyMatch(position -> {
          var blockPosition = position.toBlockPos();
          var state = level.getBlockState(blockPosition);
          return matches(blockPosition, state) && canHarvest(state);
        });
      if (hasHarvestableTarget) {
        return false;
      }
      recovery.blocksBroken += (int) Math.min(
        confirmedBreaks(path),
        targetCount - recovery.blocksBroken
      );
      activePath = null;
      activeTargets = Set.of();
      path.onStopped(ControlStopReason.CANCELLED, null);
      if (recovery.blocksBroken >= targetCount) {
        complete(
          CollectBlocksCompletionReason
            .COLLECT_BLOCKS_COMPLETION_REASON_TARGET_REACHED
        );
      } else {
        context.reportProgress(progress(
          "Selecting another matching block after the target changed",
          CollectBlocksTaskProgressDetail.Phase.PHASE_RETRYING_APPROACH
        ));
      }
      return true;
    }

    private boolean canHarvest(BlockState state) {
      var player = context.bot().minecraft().player;
      return player != null && hasDropPreservingTool(
        state,
        player.inventoryMenu.slots.stream()
          .map(slot -> slot.getItem())
          .toList()
      );
    }

    private boolean matches(BlockPos position, BlockState state) {
      if (!isWithinTargetY(position.getY(), targetYRange)) {
        return false;
      }
      if (recovery.rejectedTargets.contains(SFVec3i.fromInt(position))) {
        return false;
      }
      var blockId = BuiltInRegistries.BLOCK
        .getKey(state.getBlock())
        .toString();
      if (!blockIds.isEmpty() && !blockIds.contains(blockId)) {
        return false;
      }
      for (var tag : tags) {
        if (!state.is(TagKey.create(
          Registries.BLOCK,
          Identifier.parse(tag)
        ))) {
          return false;
        }
      }
      return !state.isAir()
        && state.getDestroySpeed(
        context.bot().minecraft().level,
        position
      ) >= 0;
    }

    private BotTaskProgress progress(
      String message,
      CollectBlocksTaskProgressDetail.Phase phase
    ) {
      var detail = CollectBlocksTaskProgressDetail.newBuilder()
        .setPhase(phase)
        .setConsecutiveStalledPaths(recovery.consecutiveStalledPaths);
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      if (player != null && level != null) {
        var dimension = level.dimension().identifier().toString();
        detail.setPlayerPosition(position(
          SFVec3i.fromInt(player.blockPosition()),
          dimension
        ));
        detail.addAllActiveTargets(positions(activeTargets, dimension));
        detail.addAllRejectedTargets(positions(recovery.rejectedTargets, dimension));
        recovery.rejectedAdjacentPositions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(positionComparator()))
          .map(entry -> CollectBlocksTaskProgressDetail.FailedApproach
            .newBuilder()
            .setTarget(position(entry.getKey(), dimension))
            .addAllPlayerPositions(positions(entry.getValue(), dimension))
            .build())
          .forEach(detail::addFailedApproaches);
        var path = activePath;
        if (path != null) {
          var pathProgress = path.progress();
          detail
            .setPathPlanning(pathProgress.planning())
            .setPathCurrentMovement(Math.max(
              0,
              pathProgress.currentMovement()
            ))
            .setPathTotalMovements(Math.max(
              0,
              pathProgress.totalMovements()
            ))
            .addAllCompletedBreaks(positions(
              path.completedBlockBreaks(),
              dimension
            ));
        }
      }
      return BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(recovery.blocksBroken)
        .setTotal(targetCount)
        .setFraction(Math.min(
          1.0,
          (double) recovery.blocksBroken / targetCount
        ))
        .setDetail(Any.pack(detail.build()))
        .build();
    }

    private static List<BlockPosition> positions(
      Iterable<SFVec3i> positions,
      String dimension
    ) {
      var ordered = new java.util.ArrayList<SFVec3i>();
      positions.forEach(ordered::add);
      return ordered.stream()
        .sorted(positionComparator())
        .map(position -> position(position, dimension))
        .toList();
    }

    private static Comparator<SFVec3i> positionComparator() {
      return Comparator
        .comparingInt((SFVec3i position) -> position.y)
        .thenComparingInt(position -> position.x)
        .thenComparingInt(position -> position.z);
    }

    private static BlockPosition position(
      SFVec3i position,
      String dimension
    ) {
      return BlockPosition.newBuilder()
        .setX(position.x)
        .setY(position.y)
        .setZ(position.z)
        .setDimension(dimension)
        .build();
    }

    private void complete(CollectBlocksCompletionReason reason) {
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      var builder = CollectBlocksTaskResult.newBuilder()
        .setReason(reason)
        .setBlocksBroken(recovery.blocksBroken);
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
    public void onStarted() {
      if (recovery.dimension == null) {
        recovery.dimension = Objects.requireNonNull(context.bot().minecraft().level).dimension().identifier().toString();
        recovery.observedPosition = SFVec3i.fromInt(Objects.requireNonNull(context.bot().minecraft().player).blockPosition());
      }
      active = true;
    }

    @Override
    public void onSuspended() {
      checkpointNearbyBreak();
      // Resume with a fresh route after accounting for work the old route already confirmed.
      stopPath(ControlStopReason.CANCELLED, null);
      clearNearbyBreak(true);
      recoveryPending = true;
      active = false;
    }

    @Override
    public void onResumed() {
      active = true;
    }

    @Override
    public void onStopped(ControlStopReason reason, @Nullable Throwable cause) {
      try {
        checkpointNearbyBreak();
        stopPath(reason, cause);
        clearNearbyBreak(reason != ControlStopReason.COMPLETED);
      } finally {
        active = false;
        if (reason != ControlStopReason.COMPLETED && !result.isDone()) result.cancel(true);
      }
    }

    private void clearNearbyBreak(boolean stopDestroying) {
      if (stopDestroying && active) {
        var gameMode = context.bot().minecraft().gameMode;
        if (gameMode != null) {
          gameMode.stopDestroyBlock();
        }
      }
      activeNearbyBreak = null;
      activeNearbyTarget = null;
      activeNearbyBreakTicks = 0;
      activeTargets = Set.of();
    }

    @Override
    public String description() {
      return "Collect blocks";
    }
  }

  private static final class RecoveryState {
    private final Set<SFVec3i> rejectedTargets = new HashSet<>();
    private final Map<SFVec3i, Set<SFVec3i>> rejectedAdjacentPositions = new HashMap<>();
    private int blocksBroken;
    private int consecutiveStalledPaths;
    private @Nullable String dimension;
    private @Nullable SFVec3i pendingBreak;
    private @Nullable SFVec3i observedPosition;
    private int clearedTicks;
  }

  private record DirectBreakTarget(
    SFVec3i position,
    BlockFace face
  ) {
  }

  static boolean hasDropPreservingTool(
    BlockState state,
    Iterable<ItemStack> inventory
  ) {
    if (!state.requiresCorrectToolForDrops()) {
      return true;
    }
    for (var stack : inventory) {
      if (stack.isCorrectToolForDrops(state)) {
        return true;
      }
    }
    return false;
  }
}
