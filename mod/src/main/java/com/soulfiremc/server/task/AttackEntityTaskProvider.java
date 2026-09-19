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

import com.soulfiremc.grpc.generated.AttackEntityCompletionReason;
import com.soulfiremc.grpc.generated.AttackEntityTask;
import com.soulfiremc.grpc.generated.AttackEntityTaskResult;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.EntityReference;
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.NodeState;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.PathfindingSupport.ResolvedGoal;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.SFEntityHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Core provider for chasing and attacking one entity until a terminal policy
/// is reached.
public final class AttackEntityTaskProvider
  implements BotTaskProvider<AttackEntityTask> {
  private static final int DEFAULT_UNAVAILABLE_TIMEOUT_SECONDS = 10;
  private static final int MAX_UNAVAILABLE_TIMEOUT_SECONDS = 3_600;
  private static final int MAX_CONSECUTIVE_PATH_FAILURES = 3;
  private static final float DEFAULT_ATTACK_RANGE = 3.0F;
  private static final float MAX_ATTACK_RANGE = 6.0F;
  private static final double DIRECT_PURSUIT_RANGE = 12.0;
  private static final double DIRECT_PURSUIT_VERTICAL_RANGE = 2.5;
  private static final double DIRECT_PURSUIT_TERRAIN_LOOKAHEAD = 1.25;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );
  private static final Set<ControlResource> SHIELD_RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.OFF_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public AttackEntityTask inputPrototype() {
    return AttackEntityTask.getDefaultInstance();
  }

  @Override
  public String summary(AttackEntityTask input) {
    return "Attack entity " + input.getTarget().getNetworkId();
  }

  @Override
  public Set<ControlResource> resources(AttackEntityTask input) {
    return input.getUseOffhandShield() ? SHIELD_RESOURCES : RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    AttackEntityTask input
  ) {
    if (!input.hasTarget()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("target is required")
        .asRuntimeException();
    }
    var target = input.getTarget();
    BotTaskSupport.requireEntity(context.bot(), target);
    var attackRange = normalizeAttackRange(input.getAttackRange());
    var resolved = CombatTaskSupport.reachGoal(
      context.bot(),
      target,
      pathfindingApproachRange(attackRange)
    );
    var waitingGoals = CombatTaskSupport.waitingGoals(context.bot(), target);
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
    var result = new CompletableFuture<AttackEntityTaskResult>();
    var control = new AttackControl(
      context,
      target,
      attackRange,
      input.getSprinting(),
      input.getMaximumAttacks(),
      timeoutSeconds * 20,
      !input.hasSelectBestWeapon() || input.getSelectBestWeapon(),
      input.hasWeapon() ? input.getWeapon() : null,
      input.getRestoreSelectedSlot(),
      input.getUseOffhandShield(),
      resolved,
      waitingGoals,
      constraint,
      result
    );
    return new BotTaskExecution(control, result);
  }

  private static float normalizeAttackRange(float value) {
    if (!Float.isFinite(value) || value < 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription("attack_range must be finite and non-negative")
        .asRuntimeException();
    }
    if (value == 0) {
      return DEFAULT_ATTACK_RANGE;
    }
    if (value > MAX_ATTACK_RANGE) {
      throw Status.INVALID_ARGUMENT
        .withDescription("attack_range must not exceed 6 blocks")
        .asRuntimeException();
    }
    return value;
  }

  static float pathfindingApproachRange(float attackRange) {
    return Math.max(1.0F, attackRange - 0.5F);
  }

  static double distanceToBoundingBox(Vec3 position, AABB box) {
    var x = Math.max(box.minX - position.x, Math.max(0, position.x - box.maxX));
    var y = Math.max(box.minY - position.y, Math.max(0, position.y - box.maxY));
    var z = Math.max(box.minZ - position.z, Math.max(0, position.z - box.maxZ));
    return Math.sqrt(x * x + y * y + z * z);
  }

  static boolean shouldPursueDirectly(
    double distance,
    double verticalDistance,
    boolean hasLineOfSight,
    boolean movingInFluid,
    boolean safeTerrainAhead
  ) {
    return hasLineOfSight
      && distance <= DIRECT_PURSUIT_RANGE
      && (movingInFluid
        || verticalDistance <= DIRECT_PURSUIT_VERTICAL_RANGE)
      && (movingInFluid || safeTerrainAhead);
  }

  static boolean hasDirectPursuitSupport(
    BlockState feet,
    BlockState head,
    BlockState floor
  ) {
    return SFBlockHelpers.isBodyPassableBlock(feet)
      && SFBlockHelpers.isBodyPassableBlock(head)
      && SFBlockHelpers.isWalkableFloorBlock(floor);
  }

  static boolean isPathGoalSatisfied(
    GoalScorer scorer,
    BlockPos playerPosition
  ) {
    return scorer.isFinished(
      new NodeState(SFVec3i.fromInt(playerPosition), 0),
      List.of()
    );
  }

  private static boolean hasSafeTerrainAhead(
    LocalPlayer player,
    Vec3 target
  ) {
    var level = player.level();
    var offset = target.subtract(player.position());
    var horizontalLength = Math.hypot(offset.x, offset.z);
    if (horizontalLength < 0.001) {
      return true;
    }
    var scale = DIRECT_PURSUIT_TERRAIN_LOOKAHEAD / horizontalLength;
    var feetPosition = BlockPos.containing(
      player.getX() + offset.x * scale,
      player.getY(),
      player.getZ() + offset.z * scale
    );
    return hasDirectPursuitSupport(
      level.getBlockState(feetPosition),
      level.getBlockState(feetPosition.above()),
      level.getBlockState(feetPosition.below())
    );
  }

  static boolean shouldAscendInFluid(double verticalOffset) {
    return verticalOffset > 0.35;
  }

  private static final class AttackControl implements ControlTask {
    private final BotTaskContext context;
    private final EntityReference target;
    private final float attackRange;
    private final boolean sprinting;
    private final int maximumAttacks;
    private final int unavailableTimeoutTicks;
    private final boolean selectBestWeapon;
    private final @Nullable ItemSelector weaponSelector;
    private final boolean restoreSelectedSlot;
    private final boolean useOffhandShield;
    private final int originalSelectedSlot;
    private final PathfindingSupport.ResolvedGoal goal;
    private final List<ResolvedGoal> waitingGoals;
    private final com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
      constraint;
    private final CompletableFuture<AttackEntityTaskResult> result;
    private @Nullable PathExecutor path;
    private @Nullable PathPurpose pathPurpose;
    private int waitingGoalIndex;
    private int unavailableTicks;
    private int consecutivePathFailures;
    private int attacks;
    private int ticks;
    private boolean lastObservedAlive;

    private AttackControl(
      BotTaskContext context,
      EntityReference target,
      float attackRange,
      boolean sprinting,
      int maximumAttacks,
      int unavailableTimeoutTicks,
      boolean selectBestWeapon,
      @Nullable ItemSelector weaponSelector,
      boolean restoreSelectedSlot,
      boolean useOffhandShield,
      ResolvedGoal goal,
      List<ResolvedGoal> waitingGoals,
      com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
        constraint,
      CompletableFuture<AttackEntityTaskResult> result
    ) {
      this.context = context;
      this.target = target;
      this.attackRange = attackRange;
      this.sprinting = sprinting;
      this.maximumAttacks = maximumAttacks;
      this.unavailableTimeoutTicks = unavailableTimeoutTicks;
      this.selectBestWeapon = selectBestWeapon;
      this.weaponSelector = weaponSelector;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.useOffhandShield = useOffhandShield;
      this.originalSelectedSlot = Objects.requireNonNull(
        context.bot().minecraft().player
      ).getInventory().getSelectedSlot();
      this.goal = goal;
      this.waitingGoals = new ArrayList<>(waitingGoals);
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
      var gameMode = Objects.requireNonNull(
        bot.minecraft().gameMode,
        "Bot game mode is not available"
      );
      var entity = BotTaskSupport.findEntity(bot, target.getNetworkId());
      if (entity == null
        || target.hasUuid()
        && !target.getUuid().equals(entity.getUUID().toString())) {
        stopPath(ControlStopReason.CANCELLED, null);
        unavailableTicks++;
        reportUnavailable();
        if (unavailableTicks >= unavailableTimeoutTicks) {
          complete(
            AttackEntityCompletionReason
              .ATTACK_ENTITY_COMPLETION_REASON_TARGET_UNAVAILABLE,
            lastObservedAlive
          );
        }
        return;
      }

      unavailableTicks = 0;
      lastObservedAlive = SFEntityHelpers.isAliveAndTargetable(entity);
      if (!lastObservedAlive) {
        stopPath(ControlStopReason.COMPLETED, null);
        complete(
          AttackEntityCompletionReason
            .ATTACK_ENTITY_COMPLETION_REASON_TARGET_DEFEATED,
          false
        );
        return;
      }

      advanceSatisfiedWaitingGoals(player.blockPosition());
      var waitingGoal = currentWaitingGoal();
      if (waitingGoal != null) {
        continuePath(waitingGoal, PathPurpose.DRAGON_STAGING);
        if (ticks % 20 == 0) {
          context.reportProgress(BotTaskProgress.newBuilder()
            .setMessage(
              "Moving through dragon staging route "
              + (waitingGoalIndex + 1) + "/" + waitingGoals.size()
            )
            .setCurrent(attacks)
            .build());
        }
        return;
      }
      if (!CombatTaskSupport.isMeleeApproachable(entity)) {
        stopPath(ControlStopReason.CANCELLED, null);
        bot.controlState().resetAll();
        if (ticks % 20 == 0) {
          context.reportProgress(BotTaskProgress.newBuilder()
            .setMessage("Waiting for dragon to perch")
            .setCurrent(attacks)
            .build());
        }
        return;
      }

      var attackTarget = CombatTaskSupport.preferredTarget(entity);
      var visiblePoint = attackTarget.getEyePosition();
      var distance = distanceToBoundingBox(
        player.getEyePosition(),
        attackTarget.getBoundingBox()
      );
      if (ticks % 20 == 0) {
        context.reportProgress(BotTaskProgress.newBuilder()
          .setMessage(distance <= attackRange
            ? "Attacking entity"
            : "Chasing entity")
          .setCurrent(attacks)
          .build());
      }
      if (distance > attackRange) {
        var movingInFluid = player.isInWater() || player.isInLava();
        if (shouldPursueDirectly(
          distance,
          Math.abs(player.getY() - attackTarget.getY()),
          player.hasLineOfSight(attackTarget),
          movingInFluid,
          movingInFluid || hasSafeTerrainAhead(player, visiblePoint)
        )) {
          stopPath(ControlStopReason.CANCELLED, null);
          bot.controlState().resetAll();
          bot.rotationControl().lookAt(visiblePoint);
          raiseShield(player, gameMode);
          bot.controlState().up(true);
          bot.controlState().sprint(sprinting);
          if (movingInFluid) {
            var verticalOffset =
              attackTarget.getBoundingBox().getCenter().y
                - player.getBoundingBox().getCenter().y;
            // Looking at the live target already steers sprint-swimming up or
            // down. Sneaking toward a lower target pins the player against the
            // lake floor and can stop horizontal pursuit entirely.
            bot.controlState().jump(shouldAscendInFluid(verticalOffset));
          } else {
            bot.controlState().jump(player.onGround() && distance > 4.0);
          }
        } else {
          raiseShield(player, gameMode);
          if (isPathGoalSatisfied(goal.scorer(), player.blockPosition())) {
            // The stable dragon podium goal can be reached before the head
            // enters melee range. Wait for the head here instead of submitting
            // the same zero-length route every tick.
            stopPath(ControlStopReason.CANCELLED, null);
            bot.controlState().resetAll();
            bot.rotationControl().lookAt(visiblePoint);
          } else {
            continuePath(goal, PathPurpose.APPROACH);
          }
        }
        return;
      }

      consecutivePathFailures = 0;
      stopPath(ControlStopReason.CANCELLED, null);
      bot.controlState().resetAll();
      if (!ensureBestWeapon()) {
        return;
      }
      bot.rotationControl().lookAt(visiblePoint);
      if (player.getAttackStrengthScale(0) < 1.0F
        || !bot.rotationControl().isFacing(visiblePoint)) {
        raiseShield(player, gameMode);
        return;
      }
      lowerShield(player, gameMode);
      var wasSprinting = player.isSprinting();
      player.setSprinting(sprinting);
      try {
        gameMode.attack(player, attackTarget);
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
      } finally {
        player.setSprinting(wasSprinting);
      }
      attacks++;
      if (maximumAttacks > 0 && attacks >= maximumAttacks) {
        complete(
          AttackEntityCompletionReason
            .ATTACK_ENTITY_COMPLETION_REASON_ATTACK_LIMIT_REACHED,
          entity.isAlive()
        );
      }
    }

    private void continuePath(
      ResolvedGoal desiredGoal,
      PathPurpose desiredPurpose
    ) {
      if (path != null && pathPurpose != desiredPurpose) {
        stopPath(ControlStopReason.CANCELLED, null);
      }
      if (path != null && path.completion().isDone()) {
        finishPath();
        return;
      }
      if (result.isDone()
        || desiredPurpose == PathPurpose.DRAGON_STAGING
        && currentWaitingGoal() == null) {
        return;
      }
      if (path == null) {
        path = PathExecutor.createPathfinding(
          context.bot(),
          desiredGoal.scorer(),
          constraint
        );
        pathPurpose = desiredPurpose;
        path.onStarted();
      }
      path.tick();
    }

    private void finishPath() {
      var completed = path;
      var completedPurpose = pathPurpose;
      path = null;
      pathPurpose = null;
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
        if (
          completedPurpose == PathPurpose.DRAGON_STAGING
            && refineCurrentWaitingGoal()
        ) {
          consecutivePathFailures = 0;
          return;
        }
        consecutivePathFailures++;
        if (consecutivePathFailures >= MAX_CONSECUTIVE_PATH_FAILURES) {
          result.completeExceptionally(new IllegalStateException(
            "Unable to reach the target entity after "
              + consecutivePathFailures + " path attempts",
            cause
          ));
        }
      }
    }

    private boolean refineCurrentWaitingGoal() {
      var failedGoal = currentWaitingGoal();
      if (failedGoal == null) {
        return false;
      }
      var player = Objects.requireNonNull(context.bot().minecraft().player);
      var refined = CombatTaskSupport.refineDragonWaitingGoal(
        failedGoal,
        player.position()
      );
      if (refined.isEmpty()) {
        return false;
      }
      waitingGoals.add(waitingGoalIndex, refined.orElseThrow());
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage("Refining the dragon staging route after a path limit")
        .setCurrent(attacks)
        .build());
      return true;
    }

    private void reportUnavailable() {
      if (ticks % 20 != 0) {
        return;
      }
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage("Waiting for target entity to become observable")
        .setCurrent(unavailableTicks)
        .setTotal(unavailableTimeoutTicks)
        .setFraction(Math.min(
          1.0,
          (double) unavailableTicks / unavailableTimeoutTicks
        ))
        .build());
    }

    private @Nullable ResolvedGoal currentWaitingGoal() {
      return waitingGoalIndex < waitingGoals.size()
        ? waitingGoals.get(waitingGoalIndex)
        : null;
    }

    private void advanceSatisfiedWaitingGoals(BlockPos playerPosition) {
      if (path != null) {
        return;
      }
      var waitingGoal = currentWaitingGoal();
      while (
        waitingGoal != null
          && isPathGoalSatisfied(waitingGoal.scorer(), playerPosition)
      ) {
        waitingGoalIndex++;
        waitingGoal = currentWaitingGoal();
      }
    }

    private void complete(
      AttackEntityCompletionReason reason,
      boolean targetAlive
    ) {
      var player = context.bot().minecraft().player;
      var gameMode = context.bot().minecraft().gameMode;
      if (player != null && gameMode != null) {
        lowerShield(player, gameMode);
      }
      result.complete(AttackEntityTaskResult.newBuilder()
        .setFinalPosition(BotTaskSupport.position(context.bot()))
        .setReason(reason)
        .setAttacks(attacks)
        .setTargetAlive(targetAlive)
        .build());
    }

    private void raiseShield(
      LocalPlayer player,
      MultiPlayerGameMode gameMode
    ) {
      if (!useOffhandShield || !player.getOffhandItem().is(Items.SHIELD)) {
        return;
      }
      if (player.isUsingItem()) {
        if (player.getUsedItemHand() == InteractionHand.OFF_HAND) {
          return;
        }
        gameMode.releaseUsingItem(player);
      }
      gameMode.useItem(player, InteractionHand.OFF_HAND);
    }

    private void lowerShield(
      LocalPlayer player,
      MultiPlayerGameMode gameMode
    ) {
      if (useOffhandShield
        && player.isUsingItem()
        && player.getUsedItemHand() == InteractionHand.OFF_HAND) {
        gameMode.releaseUsingItem(player);
      }
    }

    private boolean ensureBestWeapon() {
      if (!selectBestWeapon) {
        return true;
      }
      if (CombatTaskSupport.ensureBestMeleeWeapon(
        context.bot(),
        weaponSelector
      )) {
        return true;
      }
      throw Status.FAILED_PRECONDITION
        .withDescription("No matching melee weapon is available")
        .asRuntimeException();
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var active = path;
      path = null;
      pathPurpose = null;
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
      var player = context.bot().minecraft().player;
      var gameMode = context.bot().minecraft().gameMode;
      if (player != null && gameMode != null) {
        lowerShield(player, gameMode);
      }
      if (restoreSelectedSlot && player != null) {
        player.getInventory().setSelectedSlot(originalSelectedSlot);
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Attack entity " + target.getNetworkId();
    }

    private enum PathPurpose {
      APPROACH,
      DRAGON_STAGING
    }
  }
}
