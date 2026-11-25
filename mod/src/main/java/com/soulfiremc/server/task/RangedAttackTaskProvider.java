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
import com.soulfiremc.grpc.generated.EntityReference;
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.grpc.generated.RangedAttackCompletionReason;
import com.soulfiremc.grpc.generated.RangedAttackTask;
import com.soulfiremc.grpc.generated.RangedAttackTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.PathfindingSupport.ResolvedGoal;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.plugins.KillAura;
import com.soulfiremc.server.util.SFEntityHelpers;
import com.soulfiremc.server.util.SFInventoryHelpers;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Server-timed bow and crossbow combat with dynamic pathfinding, target
/// leading, low-arc projectile compensation, and lobbed shots at still
/// targets out of direct sight.
public final class RangedAttackTaskProvider
  implements BotTaskProvider<RangedAttackTask> {
  private static final float DEFAULT_MINIMUM_RANGE = 8;
  private static final float DEFAULT_MAXIMUM_RANGE = 24;
  private static final float MAXIMUM_ALLOWED_RANGE = 64;
  private static final int DEFAULT_UNAVAILABLE_TIMEOUT_SECONDS = 10;
  private static final int MAX_UNAVAILABLE_TIMEOUT_SECONDS = 3_600;
  private static final int DEFAULT_BOW_DRAW_TICKS = 20;
  private static final int MIN_BOW_DRAW_TICKS = 3;
  private static final int MAX_BOW_DRAW_TICKS = 20;
  private static final int MAX_CONSECUTIVE_PATH_FAILURES = 3;
  static final int MAX_IN_RANGE_TICKS_WITHOUT_SHOT = 5 * 20;
  private static final double ARROW_GRAVITY = 0.05;
  private static final double LOB_AIM_DISTANCE = 16;
  private static final double LOB_FACING_COSINE = Math.cos(Math.toRadians(0.5));
  /// The server counts the draw from its own ticks, which can trail the
  /// bot's by one, and a short draw slows the arrow. A lob is aimed for a
  /// full draw, so the bot holds a little past it; the speed tops out there.
  private static final int LOB_BOW_DRAW_TICKS = 23;
  private static final double FULL_BOW_ARROW_SPEED = 3;
  private static final double STATIONARY_DISTANCE_SQUARED = 0.0025;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public RangedAttackTask inputPrototype() {
    return RangedAttackTask.getDefaultInstance();
  }

  @Override
  public String summary(RangedAttackTask input) {
    return "Ranged attack entity " + input.getTarget().getNetworkId();
  }

  @Override
  public Set<ControlResource> resources(RangedAttackTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(
    BotTaskContext context,
    RangedAttackTask input
  ) {
    if (!input.hasTarget()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("target is required")
        .asRuntimeException();
    }
    BotTaskSupport.requireEntity(context.bot(), input.getTarget());
    var minimumRange = normalizeRange(
      input.getMinimumRange(),
      DEFAULT_MINIMUM_RANGE,
      "minimum_range"
    );
    var maximumRange = normalizeRange(
      input.getMaximumRange(),
      DEFAULT_MAXIMUM_RANGE,
      "maximum_range"
    );
    if (minimumRange >= maximumRange) {
      throw Status.INVALID_ARGUMENT
        .withDescription("minimum_range must be smaller than maximum_range")
        .asRuntimeException();
    }
    var bowDrawTicks = input.getBowDrawTicks() == 0
      ? DEFAULT_BOW_DRAW_TICKS
      : input.getBowDrawTicks();
    if (
      bowDrawTicks < MIN_BOW_DRAW_TICKS
        || bowDrawTicks > MAX_BOW_DRAW_TICKS
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "bow_draw_ticks must be between %d and %d"
            .formatted(MIN_BOW_DRAW_TICKS, MAX_BOW_DRAW_TICKS)
        )
        .asRuntimeException();
    }
    var unavailableSeconds = input.getTargetUnavailableTimeoutSeconds() == 0
      ? DEFAULT_UNAVAILABLE_TIMEOUT_SECONDS
      : Math.min(
        input.getTargetUnavailableTimeoutSeconds(),
        MAX_UNAVAILABLE_TIMEOUT_SECONDS
      );
    var result = new CompletableFuture<RangedAttackTaskResult>();
    return new BotTaskExecution(
      new RangedControl(
        context,
        input.getTarget(),
        minimumRange,
        maximumRange,
        input.getMaximumShots(),
        unavailableSeconds * 20,
        input.hasWeapon() ? input.getWeapon() : null,
        bowDrawTicks,
        input.getLeadTarget(),
        input.getCompensateGravity(),
        input.getStrafe(),
        input.getRestoreSelectedSlot(),
        CombatTaskSupport.reachGoal(
          context.bot(),
          input.getTarget(),
          Math.max(3, minimumRange * 0.75F)
        ),
        CombatTaskSupport.waitingGoals(context.bot(), input.getTarget()),
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result
      ),
      result
    );
  }

  private static float normalizeRange(
    float value,
    float defaultValue,
    String field
  ) {
    if (!Float.isFinite(value) || value < 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be finite and non-negative")
        .asRuntimeException();
    }
    var normalized = value == 0 ? defaultValue : value;
    if (normalized > MAXIMUM_ALLOWED_RANGE) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must not exceed 64 blocks")
        .asRuntimeException();
    }
    return normalized;
  }

  private static final class RangedControl implements ControlTask {
    private final BotTaskContext context;
    private final EntityReference target;
    private final float minimumRange;
    private final float maximumRange;
    private final int maximumShots;
    private final int unavailableTimeoutTicks;
    private final @Nullable ItemSelector weaponSelector;
    private final int bowDrawTicks;
    private final boolean leadTarget;
    private final boolean compensateGravity;
    private final boolean strafe;
    private final boolean restoreSelectedSlot;
    private final int originalSelectedSlot;
    private final PathfindingSupport.ResolvedGoal goal;
    private final List<ResolvedGoal> stagingGoals;
    private final com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
      constraint;
    private final CompletableFuture<RangedAttackTaskResult> result;
    private @Nullable PathExecutor path;
    private @Nullable PathPurpose pathPurpose;
    private int stagingGoalIndex;
    private int unavailableTicks;
    private int consecutivePathFailures;
    private int shots;
    private int ticks;
    private int cooldownTicks;
    private boolean strafeRight;
    private boolean lastObservedAlive;
    private boolean active;
    private @Nullable LobSolution lobCache;
    private final ShotProgressWatchdog shotProgress =
      new ShotProgressWatchdog(MAX_IN_RANGE_TICKS_WITHOUT_SHOT);

    private RangedControl(
      BotTaskContext context,
      EntityReference target,
      float minimumRange,
      float maximumRange,
      int maximumShots,
      int unavailableTimeoutTicks,
      @Nullable ItemSelector weaponSelector,
      int bowDrawTicks,
      boolean leadTarget,
      boolean compensateGravity,
      boolean strafe,
      boolean restoreSelectedSlot,
      PathfindingSupport.ResolvedGoal goal,
      List<ResolvedGoal> stagingGoals,
      com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint
        constraint,
      CompletableFuture<RangedAttackTaskResult> result
    ) {
      this.context = context;
      this.target = target;
      this.minimumRange = minimumRange;
      this.maximumRange = maximumRange;
      this.maximumShots = maximumShots;
      this.unavailableTimeoutTicks = unavailableTimeoutTicks;
      this.weaponSelector = weaponSelector;
      this.bowDrawTicks = bowDrawTicks;
      this.leadTarget = leadTarget;
      this.compensateGravity = compensateGravity;
      this.strafe = strafe;
      this.restoreSelectedSlot = restoreSelectedSlot;
      this.originalSelectedSlot = Objects.requireNonNull(
        context.bot().minecraft().player
      ).getInventory().getSelectedSlot();
      this.goal = goal;
      this.stagingGoals = new ArrayList<>(stagingGoals);
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      ticks++;
      if (cooldownTicks > 0) {
        cooldownTicks--;
      }
      try {
        var entity = observedTarget();
        if (entity == null) {
          unavailable();
          return;
        }
        unavailableTicks = 0;
        lastObservedAlive = SFEntityHelpers.isAliveAndTargetable(entity);
        if (!lastObservedAlive) {
          complete(
            RangedAttackCompletionReason
              .RANGED_ATTACK_COMPLETION_REASON_TARGET_DEFEATED,
            false
          );
          return;
        }
        engage(entity);
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private @Nullable Entity observedTarget() {
      var entity = BotTaskSupport.findEntity(
        context.bot(),
        target.getNetworkId()
      );
      if (
        entity == null
          || target.hasUuid()
          && !target.getUuid().equals(entity.getUUID().toString())
      ) {
        return null;
      }
      return entity;
    }

    private void unavailable() {
      stopUsingItem();
      stopPath(ControlStopReason.CANCELLED, null);
      unavailableTicks++;
      if (ticks % 20 == 0) {
        context.reportProgress(BotTaskProgress.newBuilder()
          .setMessage("Waiting for ranged target to become observable")
          .setCurrent(unavailableTicks)
          .setTotal(unavailableTimeoutTicks)
          .setFraction(Math.min(
            1,
            (double) unavailableTicks / unavailableTimeoutTicks
          ))
          .build());
      }
      if (unavailableTicks >= unavailableTimeoutTicks) {
        complete(
          RangedAttackCompletionReason
            .RANGED_ATTACK_COMPLETION_REASON_TARGET_UNAVAILABLE,
          lastObservedAlive
        );
      }
    }

    private void engage(Entity entity) {
      var bot = context.bot();
      var player = Objects.requireNonNull(bot.minecraft().player);
      var attackTarget = CombatTaskSupport.preferredTarget(entity);
      var visiblePoint = KillAura.getEntityVisiblePoint(bot, attackTarget);
      var targetPoint = stableAimPoint(bot, attackTarget, visiblePoint);
      var distance = targetPoint.distanceTo(player.getEyePosition());
      // Crystals sit on top of towers, where the tower's rim hides them from
      // the ground. A live bot could see none of them from twenty blocks out,
      // yet a steep arrow drops onto a still target over the rim.
      var lobDirection = visiblePoint == null && distance <= maximumRange
        ? lobDirection(attackTarget)
        : null;
      if (distance > maximumRange || visiblePoint == null && lobDirection == null) {
        shotProgress.reset();
        stopUsingItem();
        advanceSatisfiedStagingGoals(player.blockPosition());
        var stagingGoal = currentStagingGoal();
        if (stagingGoal != null) {
          continuePath(stagingGoal, PathPurpose.DRAGON_STAGING);
          report(
            "Moving through ranged dragon staging route "
              + (stagingGoalIndex + 1) + "/" + stagingGoals.size(),
            distance
          );
        } else if (visiblePoint == null && !stagingGoals.isEmpty()) {
          stopPath(ControlStopReason.CANCELLED, null);
          bot.controlState().resetAll();
          report("Waiting for a visible dragon shot", distance);
        } else {
          continuePath(goal, PathPurpose.APPROACH);
          report("Closing to ranged attack distance", distance);
        }
        return;
      }

      stopPath(ControlStopReason.CANCELLED, null);
      consecutivePathFailures = 0;
      if (distance < minimumRange) {
        shotProgress.reset();
        stopUsingItem();
        bot.controlState().resetAll();
        bot.rotationControl().lookAt(targetPoint);
        bot.controlState().down(true);
        if (strafe) {
          setStrafe();
        }
        report("Creating ranged attack distance", distance);
        return;
      }

      bot.controlState().resetAll();
      if (strafe) {
        setStrafe();
      }
      var weapon = ensureWeapon();
      if (weapon == null) {
        complete(
          RangedAttackCompletionReason
            .RANGED_ATTACK_COMPLETION_REASON_NO_WEAPON,
          true
        );
        return;
      }
      if (!CrossbowItem.isCharged(weapon) && player.getProjectile(weapon).isEmpty()) {
        stopUsingItem();
        complete(
          RangedAttackCompletionReason
            .RANGED_ATTACK_COMPLETION_REASON_NO_AMMUNITION,
          true
        );
        return;
      }
      shotProgress.awaitShot();
      var velocity = projectileVelocity(weapon);
      var aimPoint = lobDirection != null
        ? player.getEyePosition().add(lobDirection.scale(LOB_AIM_DISTANCE))
        : aimPoint(
          player.getEyePosition(),
          targetPoint,
          attackTarget,
          velocity
        );
      bot.rotationControl().lookAt(aimPoint);
      report("Aiming ranged weapon", distance);
      // A lob flies several times as far as a flat shot, so two degrees off
      // lands blocks wide of a crystal.
      // Yaw barely matters for a near vertical shot, so a lob compares the
      // look direction as a whole.
      var facing = lobDirection != null
        ? player.getLookAngle().dot(lobDirection) >= LOB_FACING_COSINE
        : bot.rotationControl().isFacing(aimPoint);
      if (weapon.getItem() instanceof CrossbowItem) {
        tickCrossbow(weapon, facing);
      } else {
        tickBow(
          facing,
          lobDirection != null ? Math.max(bowDrawTicks, LOB_BOW_DRAW_TICKS) : bowDrawTicks
        );
      }
    }

    /// Returns a launch direction that drops an arrow onto a still target
    /// out of direct sight, or null when the target moves, no weapon is at
    /// hand, or no flight reaches it.
    private @Nullable Vec3 lobDirection(Entity attackTarget) {
      var player = Objects.requireNonNull(context.bot().minecraft().player);
      var level = Objects.requireNonNull(context.bot().minecraft().level);
      var moved = attackTarget.position().distanceToSqr(
        attackTarget.xo,
        attackTarget.yo,
        attackTarget.zo
      );
      if (moved > STATIONARY_DISTANCE_SQUARED) {
        lobCache = null;
        return null;
      }
      var weapon = ensureWeapon();
      if (weapon == null) {
        return null;
      }
      var origin = player.getEyePosition().subtract(0, ArrowTrajectory.LAUNCH_DROP, 0);
      var targetBox = attackTarget.getBoundingBox();
      var speed = weapon.getItem() instanceof CrossbowItem
        ? projectileVelocity(weapon)
        : FULL_BOW_ARROW_SPEED;
      var cached = lobCache;
      if (
        cached != null
          && cached.origin().distanceToSqr(origin) < STATIONARY_DISTANCE_SQUARED
          && cached.target().equals(targetBox)
          && cached.speed() == speed
      ) {
        return cached.direction();
      }
      var direction = ArrowTrajectory.launchDirection(
        origin,
        targetBox,
        speed,
        (from, to) -> level.clip(new ClipContext(
          from,
          to,
          ClipContext.Block.COLLIDER,
          ClipContext.Fluid.NONE,
          player
        )).getType() == HitResult.Type.MISS
      ).orElse(null);
      lobCache = new LobSolution(origin, targetBox, speed, direction);
      return direction;
    }

    private record LobSolution(
      Vec3 origin,
      AABB target,
      double speed,
      @Nullable Vec3 direction
    ) {
    }

    private static Vec3 stableAimPoint(
      BotConnection bot,
      Entity attackTarget,
      @Nullable Vec3 visiblePoint
    ) {
      if (visiblePoint == null) {
        return attackTarget.getEyePosition();
      }
      var center = attackTarget.getBoundingBox().getCenter();
      return KillAura.canSee(bot, center) ? center : visiblePoint;
    }

    private @Nullable ItemStack ensureWeapon() {
      var player = Objects.requireNonNull(context.bot().minecraft().player);
      var selected = SFInventoryHelpers.playerInventorySlots(
          player.inventoryMenu
        )
        .mapToObj(slot -> player.inventoryMenu.getSlot(slot).getItem())
        .filter(stack -> stack.getItem() instanceof ProjectileWeaponItem)
        .filter(stack -> weaponSelector == null
          || InventoryServiceImpl.matches(stack, weaponSelector))
        .max(Comparator.comparingDouble(
          RangedControl::weaponScore
        ));
      if (selected.isEmpty()) {
        return null;
      }
      var expected = selected.orElseThrow().copy();
      if (!TaskInventorySupport.ensureHolding(
        context.bot(),
        stack -> ItemStack.isSameItemSameComponents(stack, expected)
      )) {
        return null;
      }
      return player.getMainHandItem();
    }

    private static double weaponScore(ItemStack stack) {
      var range = stack.getItem() instanceof ProjectileWeaponItem weapon
        ? weapon.getDefaultProjectileRange()
        : 0;
      if (!stack.isDamageableItem()) {
        return range;
      }
      return range + (double) (
        stack.getMaxDamage() - stack.getDamageValue()
      ) / stack.getMaxDamage();
    }

    private void tickBow(boolean facing, int drawTicks) {
      var player = Objects.requireNonNull(context.bot().minecraft().player);
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      if (!player.isUsingItem()) {
        if (cooldownTicks == 0) {
          context.bot().minecraft().options.keyUse.setDown(true);
          gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        return;
      }
      if (player.getTicksUsingItem() < drawTicks) {
        return;
      }
      if (!facing) {
        return;
      }
      context.bot().minecraft().options.keyUse.setDown(false);
      gameMode.releaseUsingItem(player);
      releasedShot();
    }

    private void tickCrossbow(ItemStack weapon, boolean facing) {
      var player = Objects.requireNonNull(context.bot().minecraft().player);
      var gameMode = Objects.requireNonNull(
        context.bot().minecraft().gameMode
      );
      if (CrossbowItem.isCharged(weapon)) {
        stopUsingItem();
        if (cooldownTicks == 0 && facing) {
          gameMode.useItem(player, InteractionHand.MAIN_HAND);
          releasedShot();
        }
        return;
      }
      // The server loads the projectiles while use is held. Wait for that
      // inventory update before releasing, including when loading the last arrow.
      if (!player.isUsingItem()) {
        context.bot().minecraft().options.keyUse.setDown(true);
        gameMode.useItem(player, InteractionHand.MAIN_HAND);
      }
    }

    private void releasedShot() {
      shots++;
      shotProgress.shotReleased();
      cooldownTicks = 5;
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage("Released ranged shot")
        .setCurrent(shots)
        .build());
      if (maximumShots > 0 && shots >= maximumShots) {
        complete(
          RangedAttackCompletionReason
            .RANGED_ATTACK_COMPLETION_REASON_SHOT_LIMIT_REACHED,
          true
        );
      }
    }

    private double projectileVelocity(ItemStack weapon) {
      if (weapon.getItem() instanceof CrossbowItem) {
        return 3.15;
      }
      var draw = bowDrawTicks / 20.0;
      var power = Math.min(1, (draw * draw + 2 * draw) / 3);
      return 3 * power;
    }

    private Vec3 aimPoint(
      Vec3 origin,
      Vec3 targetPoint,
      Entity entity,
      double velocity
    ) {
      var target = targetPoint;
      var horizontal = Math.hypot(
        target.x - origin.x,
        target.z - origin.z
      );
      if (leadTarget && horizontal > 0) {
        var flightTicks = horizontal / velocity;
        target = target.add(entity.getDeltaMovement().scale(flightTicks));
        horizontal = Math.hypot(
          target.x - origin.x,
          target.z - origin.z
        );
      }
      if (!compensateGravity || horizontal < 0.001) {
        return target;
      }
      var height = target.y - origin.y;
      var speedSquared = velocity * velocity;
      var discriminant = speedSquared * speedSquared
        - ARROW_GRAVITY * (
          ARROW_GRAVITY * horizontal * horizontal
            + 2 * height * speedSquared
        );
      if (discriminant < 0) {
        return target;
      }
      var angle = Math.atan(
        (speedSquared - Math.sqrt(discriminant))
          / (ARROW_GRAVITY * horizontal)
      );
      var directionX = (target.x - origin.x) / horizontal;
      var directionZ = (target.z - origin.z) / horizontal;
      return origin.add(
        directionX * horizontal,
        Math.tan(angle) * horizontal,
        directionZ * horizontal
      );
    }

    private void setStrafe() {
      if (ticks % 40 == 0) {
        strafeRight = !strafeRight;
      }
      if (strafeRight) {
        context.bot().controlState().right(true);
      } else {
        context.bot().controlState().left(true);
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
      if (result.isDone()) {
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
            && refineCurrentStagingGoal()
        ) {
          consecutivePathFailures = 0;
          return;
        }
        consecutivePathFailures++;
        if (consecutivePathFailures >= MAX_CONSECUTIVE_PATH_FAILURES) {
          throw new IllegalStateException(
            "Unable to find line of sight after "
              + consecutivePathFailures + " path attempts",
            cause
          );
        }
      }
    }

    private boolean refineCurrentStagingGoal() {
      var failedGoal = currentStagingGoal();
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
      stagingGoals.add(stagingGoalIndex, refined.orElseThrow());
      report("Refining the ranged dragon staging route", 0);
      return true;
    }

    private void report(String message, double distance) {
      if (ticks % 20 != 0) {
        return;
      }
      context.reportProgress(BotTaskProgress.newBuilder()
        .setMessage("%s at %.1f blocks".formatted(message, distance))
        .setCurrent(shots)
        .build());
    }

    private void complete(
      RangedAttackCompletionReason reason,
      boolean targetAlive
    ) {
      stopUsingItem();
      stopPath(ControlStopReason.COMPLETED, null);
      result.complete(RangedAttackTaskResult.newBuilder()
        .setFinalPosition(BotTaskSupport.position(context.bot()))
        .setReason(reason)
        .setShotsReleased(shots)
        .setTargetAlive(targetAlive)
        .build());
    }

    private void stopUsingItem() {
      context.bot().minecraft().options.keyUse.setDown(false);
      var player = context.bot().minecraft().player;
      var gameMode = context.bot().minecraft().gameMode;
      if (player != null && gameMode != null && player.isUsingItem()) {
        if (player.getUsedItemHand() == InteractionHand.MAIN_HAND
          && player.getUseItem().getItem() instanceof BowItem) {
          // Releasing a drawn bow shoots it. A temporary hotbar switch instead
          // cancels use on the server without firing or changing our selection.
          var selectedSlot = player.getInventory().getSelectedSlot();
          player.connection.send(new ServerboundSetCarriedItemPacket(
            (selectedSlot + 1) % Inventory.getSelectionSize()
          ));
          player.connection.send(new ServerboundSetCarriedItemPacket(selectedSlot));
          player.stopUsingItem();
        } else {
          gameMode.releaseUsingItem(player);
        }
      }
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

    private @Nullable ResolvedGoal currentStagingGoal() {
      return stagingGoalIndex < stagingGoals.size()
        ? stagingGoals.get(stagingGoalIndex)
        : null;
    }

    private void advanceSatisfiedStagingGoals(BlockPos playerPosition) {
      if (path != null) {
        return;
      }
      var stagingGoal = currentStagingGoal();
      while (
        stagingGoal != null
          && AttackEntityTaskProvider.isPathGoalSatisfied(
          stagingGoal.scorer(),
          playerPosition
        )
      ) {
        stagingGoalIndex++;
        stagingGoal = currentStagingGoal();
      }
    }

    private enum PathPurpose {
      APPROACH,
      DRAGON_STAGING
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
      active = true;
    }

    @Override
    public void onSuspended() {
      active = false;
      stopUsingItem();
      context.bot().controlState().resetAll();
      if (path != null) {
        path.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      active = true;
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
      if (active) {
        active = false;
        stopUsingItem();
        context.bot().controlState().resetAll();
        var player = context.bot().minecraft().player;
        if (restoreSelectedSlot && player != null) {
          player.getInventory().setSelectedSlot(originalSelectedSlot);
        }
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Ranged attack entity " + target.getNetworkId();
    }
  }

  static final class ShotProgressWatchdog {
    private final int maximumTicksWithoutShot;
    private int ticksWithoutShot;

    ShotProgressWatchdog(int maximumTicksWithoutShot) {
      if (maximumTicksWithoutShot <= 0) {
        throw new IllegalArgumentException(
          "maximumTicksWithoutShot must be positive"
        );
      }
      this.maximumTicksWithoutShot = maximumTicksWithoutShot;
    }

    void awaitShot() {
      ticksWithoutShot++;
      if (ticksWithoutShot >= maximumTicksWithoutShot) {
        throw new IllegalStateException(
          "Ranged weapon did not release a shot within "
            + maximumTicksWithoutShot + " in-range ticks"
        );
      }
    }

    void shotReleased() {
      ticksWithoutShot = 0;
    }

    void reset() {
      ticksWithoutShot = 0;
    }
  }
}
