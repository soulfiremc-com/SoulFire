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
import com.soulfiremc.grpc.generated.FleeCompletionReason;
import com.soulfiremc.grpc.generated.FleeTask;
import com.soulfiremc.grpc.generated.FleeTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.goals.AwayFromPositionsGoal;
import com.soulfiremc.server.pathfinding.goals.DynamicGoalScorer;
import com.soulfiremc.server.pathfinding.graph.constraint.ProjectileAvoidanceConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.ThreatAvoidanceConstraint;
import com.soulfiremc.server.util.SFEntityHelpers;
import io.grpc.Status;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.StreamSupport;

/// Monitors a typed threat selector and runs dynamic away-from-entity paths
/// until the bot has remained safe for the configured period.
public final class FleeTaskProvider implements BotTaskProvider<FleeTask> {
  private static final float DEFAULT_TRIGGER_RADIUS = 8;
  private static final float DEFAULT_SAFE_DISTANCE = 16;
  private static final float MAX_RADIUS = 128;
  private static final int DEFAULT_SAFE_SECONDS = 2;
  private static final int MAX_SAFE_SECONDS = 300;
  private static final int MAX_CONSECUTIVE_FAILURES = 3;
  private static final double DEFENSIVE_ATTACK_SEARCH_RADIUS = 4;
  private static final double DEFENSIVE_ATTACK_RANGE = 3;
  private static final double GENERIC_THREAT_EXCLUSION_RADIUS = 4;
  private static final double ENDERMAN_EXCLUSION_RADIUS = 6;
  private static final double CREEPER_EXCLUSION_RADIUS = 8;
  private static final double RANGED_THREAT_EXCLUSION_RADIUS = 10;
  private static final double MAXIMUM_THREAT_INFLUENCE_RADIUS = 32;
  private static final double GENERIC_THREAT_PENALTY = 64;
  private static final double RANGED_THREAT_PENALTY = 96;
  private static final double CREEPER_THREAT_PENALTY = 128;
  private static final double SAFETY_MARGIN_REPLAN_THRESHOLD = 4;
  private static final double SAFETY_MARGIN_REGRESSION = 0.5;
  private static final int THREAT_REPLAN_COOLDOWN_TICKS = 20;
  private static final double PROJECTILE_MAXIMUM_TICKS_TO_IMPACT = 20;
  private static final double ARROW_INERTIA = 0.99;
  private static final double ARROW_GRAVITY = 0.05;
  private static final double PROJECTILE_DODGE_HORIZONTAL_RADIUS = 1.5;
  private static final double PROJECTILE_DODGE_VERTICAL_RADIUS = 2.2;
  private static final double PROJECTILE_SHIELD_TICKS_TO_IMPACT = 8;
  private static final int PROJECTILE_REPLAN_COOLDOWN_TICKS = 2;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.OFF_HAND,
    ControlResource.INVENTORY
  );
  private static final Set<EntityType<?>> GROUP_AGGRO_ENTITY_TYPES = Set.of(
    EntityTypes.PIGLIN,
    EntityTypes.PIGLIN_BRUTE,
    EntityTypes.ZOMBIFIED_PIGLIN
  );

  @Override
  public FleeTask inputPrototype() {
    return FleeTask.getDefaultInstance();
  }

  @Override
  public String summary(FleeTask input) {
    return input.getMaximumEscapes() == 0
      ? "Flee from matching threats"
      : "Complete up to " + input.getMaximumEscapes() + " escape(s)";
  }

  @Override
  public Set<ControlResource> resources(FleeTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, FleeTask input) {
    BotTaskSupport.requireSafeEntitySelector(input.getThreats());
    var triggerRadius = radius(
      input.getTriggerRadius(),
      DEFAULT_TRIGGER_RADIUS,
      "trigger_radius"
    );
    var safeDistance = radius(
      input.getSafeDistance(),
      DEFAULT_SAFE_DISTANCE,
      "safe_distance"
    );
    if (safeDistance <= triggerRadius) {
      throw Status.INVALID_ARGUMENT
        .withDescription("safe_distance must be greater than trigger_radius")
        .asRuntimeException();
    }
    var safeSeconds = input.getSafeSeconds() == 0
      ? DEFAULT_SAFE_SECONDS
      : Math.min(input.getSafeSeconds(), MAX_SAFE_SECONDS);
    var result = new CompletableFuture<FleeTaskResult>();
    return new BotTaskExecution(
      new FleeControl(
        context,
        input,
        triggerRadius,
        safeDistance,
        safeSeconds * 20,
        result
      ),
      result
    );
  }

  private static float radius(
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
    if (normalized > MAX_RADIUS) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must not exceed " + MAX_RADIUS)
        .asRuntimeException();
    }
    return normalized;
  }

  static boolean shouldDefensivelyStrike(
    boolean targetable,
    boolean excluded,
    boolean hasLineOfSight,
    double distance,
    float attackStrength
  ) {
    return targetable
      && !excluded
      && hasLineOfSight
      && distance <= DEFENSIVE_ATTACK_RANGE
      && attackStrength >= 1;
  }

  static boolean isGroupAggroEntityType(EntityType<?> entityType) {
    return GROUP_AGGRO_ENTITY_TYPES.contains(entityType);
  }

  static boolean shouldExcludeDefensiveStrike(
    EntityType<?> entityType,
    boolean activelyAggressive
  ) {
    return entityType == EntityTypes.CREEPER
      || entityType == EntityTypes.ENDERMAN
      || (isGroupAggroEntityType(entityType) && !activelyAggressive);
  }

  private static boolean isActivelyAggressive(Entity entity) {
    return entity instanceof Mob mob
      && (
        mob.isAggressive()
          || (mob instanceof NeutralMob neutralMob && neutralMob.isAngry())
          || (mob instanceof Enderman enderMan && enderMan.isCreepy())
      );
  }

  static @Nullable ProjectileDodge incomingProjectileDodge(
    Vec3 projectilePosition,
    Vec3 projectileVelocity,
    Vec3 playerCenter,
    float playerYaw,
    int tieBreaker
  ) {
    if (projectileVelocity.lengthSqr() < 1.0E-6) {
      return null;
    }

    var position = projectilePosition;
    var velocity = projectileVelocity;
    for (var tick = 0; tick < PROJECTILE_MAXIMUM_TICKS_TO_IMPACT; tick++) {
      var speedSquared = velocity.lengthSqr();
      if (speedSquared < 1.0E-6) {
        return null;
      }
      var projectedFraction = playerCenter.subtract(position)
        .dot(velocity) / speedSquared;
      var segmentFraction = Mth.clamp(projectedFraction, 0, 1);
      var closest = position.add(velocity.scale(segmentFraction));
      var miss = playerCenter.subtract(closest);
      if (
        projectedFraction > 0
          && miss.x * miss.x + miss.z * miss.z
          <= PROJECTILE_DODGE_HORIZONTAL_RADIUS
            * PROJECTILE_DODGE_HORIZONTAL_RADIUS
          && Math.abs(miss.y) <= PROJECTILE_DODGE_VERTICAL_RADIUS
      ) {
        var yawRadians = playerYaw * Mth.DEG_TO_RAD;
        var leftX = Math.cos(yawRadians);
        var leftZ = Math.sin(yawRadians);
        var leftMissSquared = Mth.square(miss.x + leftX)
          + Mth.square(miss.z + leftZ);
        var rightMissSquared = Mth.square(miss.x - leftX)
          + Mth.square(miss.z - leftZ);
        var left = Math.abs(leftMissSquared - rightMissSquared) < 1.0E-6
          ? (tieBreaker & 1) == 0
          : leftMissSquared > rightMissSquared;
        return new ProjectileDodge(tick + segmentFraction, left);
      }
      position = position.add(velocity);
      velocity = velocity.scale(ARROW_INERTIA)
        .add(0, -ARROW_GRAVITY, 0);
    }
    return null;
  }

  private static double exclusionRadius(Entity entity) {
    if (entity instanceof Creeper) {
      return CREEPER_EXCLUSION_RADIUS;
    }
    if (entity instanceof RangedAttackMob) {
      return RANGED_THREAT_EXCLUSION_RADIUS;
    }
    if (entity instanceof Enderman) {
      return ENDERMAN_EXCLUSION_RADIUS;
    }
    return GENERIC_THREAT_EXCLUSION_RADIUS;
  }

  private static double maximumPenalty(Entity entity) {
    if (entity instanceof Creeper) {
      return CREEPER_THREAT_PENALTY;
    }
    if (entity instanceof RangedAttackMob) {
      return RANGED_THREAT_PENALTY;
    }
    return GENERIC_THREAT_PENALTY;
  }

  record ProjectileDodge(double ticksToImpact, boolean left) {
  }

  private record IncomingProjectile(
    AbstractArrow arrow,
    ProjectileDodge dodge
  ) {
  }

  private static final class ThreatField {
    private final BotTaskContext context;
    private final FleeTask input;
    private final float safeDistance;
    private long observedGameTime = Long.MIN_VALUE;
    private ThreatAvoidanceConstraint.Snapshot snapshot =
      new ThreatAvoidanceConstraint.Snapshot(SFVec3i.ZERO, List.of());

    private ThreatField(
      BotTaskContext context,
      FleeTask input,
      float safeDistance
    ) {
      this.context = context;
      this.input = input;
      this.safeDistance = safeDistance;
    }

    private synchronized ThreatAvoidanceConstraint.Snapshot
    constraintSnapshot() {
      var minecraft = context.bot().minecraft();
      var level = minecraft.level;
      var player = minecraft.player;
      if (level == null || player == null) {
        snapshot = new ThreatAvoidanceConstraint.Snapshot(
          SFVec3i.ZERO,
          List.of()
        );
        return snapshot;
      }
      var gameTime = level.getGameTime();
      if (gameTime == observedGameTime) {
        return snapshot;
      }
      observedGameTime = gameTime;
      var observerPosition = SFVec3i.fromDouble(player.position());
      var influenceRadius = Math.min(
        safeDistance,
        MAXIMUM_THREAT_INFLUENCE_RADIUS
      );
      var threats = BotTaskSupport.matchingEntities(
          context.bot(),
          input.getThreats(),
          player.position(),
          safeDistance,
          true
        ).stream()
        .map(entity -> {
          var exclusionRadius = exclusionRadius(entity);
          return new ThreatAvoidanceConstraint.Threat(
            SFVec3i.fromDouble(entity.position()),
            exclusionRadius,
            Math.max(exclusionRadius, influenceRadius),
            maximumPenalty(entity)
          );
        })
        .toList();
      snapshot = new ThreatAvoidanceConstraint.Snapshot(
        observerPosition,
        threats
      );
      return snapshot;
    }

    private double minimumSafetyMargin() {
      var current = constraintSnapshot();
      return current.threats().stream()
        .mapToDouble(threat -> current.observerPosition()
          .distance(threat.position()) - threat.exclusionRadius())
        .min()
        .orElse(Double.POSITIVE_INFINITY);
    }
  }

  private static final class FleeControl implements ControlTask {
    private final BotTaskContext context;
    private final FleeTask input;
    private final float triggerRadius;
    private final float safeDistance;
    private final int safeTicksRequired;
    private final CompletableFuture<FleeTaskResult> result;
    private final ThreatField threatField;
    private @Nullable BotTaskExecution activeEscape;
    private int safeTicks;
    private int escapes;
    private int consecutiveFailures;
    private int ticks;
    private int lastThreatReplanTick = -THREAT_REPLAN_COOLDOWN_TICKS;
    private int lastProjectileReplanTick = -PROJECTILE_REPLAN_COOLDOWN_TICKS;
    private double previousSafetyMargin = Double.POSITIVE_INFINITY;
    private Set<Integer> plannedProjectileIds = Set.of();
    private boolean shieldRaised;

    private FleeControl(
      BotTaskContext context,
      FleeTask input,
      float triggerRadius,
      float safeDistance,
      int safeTicksRequired,
      CompletableFuture<FleeTaskResult> result
    ) {
      this.context = context;
      this.input = input;
      this.triggerRadius = triggerRadius;
      this.safeDistance = safeDistance;
      this.safeTicksRequired = safeTicksRequired;
      this.result = result;
      this.threatField = new ThreatField(context, input, safeDistance);
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      var currentPlayer = context.bot().minecraft().player;
      if (currentPlayer != null && currentPlayer.isDeadOrDying()) {
        abortForPlayerDeath();
        return;
      }
      ticks++;
      try {
        if (activeEscape != null) {
          tickEscape();
          defendWhileFleeing();
          return;
        }
        var bot = context.bot();
        var player = Objects.requireNonNull(bot.minecraft().player);
        var threat = BotTaskSupport.nearestMatchingEntity(
          bot,
          input.getThreats(),
          player.position(),
          triggerRadius,
          true
        );
        if (threat == null) {
          safeTicks++;
          if (ticks % 20 == 0) {
            context.reportProgress(BotTaskProgress.newBuilder()
              .setMessage(input.getCompleteWhenSafe()
                ? "Confirming the area is safe"
                : "Monitoring for threats")
              .setCurrent(escapes)
              .build());
          }
          if (input.getCompleteWhenSafe()
            && safeTicks >= safeTicksRequired) {
            complete(FleeCompletionReason.FLEE_COMPLETION_REASON_SAFE);
          }
          return;
        }
        safeTicks = 0;
        startEscape();
        dodgeIncomingProjectile();
        defendWhileFleeing();
        context.reportProgress(BotTaskProgress.newBuilder()
          .setMessage("Escaping from " + threat.getName().getString())
          .setCurrent(escapes)
          .build());
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void defendWhileFleeing() {
      if (shieldRaised) {
        return;
      }
      var bot = context.bot();
      var player = Objects.requireNonNull(bot.minecraft().player);
      var gameMode = Objects.requireNonNull(bot.minecraft().gameMode);
      var threat = BotTaskSupport.nearestMatchingEntity(
        bot,
        input.getThreats(),
        player.position(),
        DEFENSIVE_ATTACK_SEARCH_RADIUS,
        true,
        entity -> !isGroupAggroEntityType(entity.getType())
          || isActivelyAggressive(entity)
      );
      if (threat == null) {
        return;
      }
      var attackTarget = CombatTaskSupport.preferredTarget(threat);
      var excluded = shouldExcludeDefensiveStrike(
        attackTarget.getType(),
        isActivelyAggressive(attackTarget)
      );
      var distance = AttackEntityTaskProvider.distanceToBoundingBox(
        player.getEyePosition(),
        attackTarget.getBoundingBox()
      );
      if (!shouldDefensivelyStrike(
        SFEntityHelpers.isAliveAndTargetable(attackTarget),
        excluded,
        player.hasLineOfSight(attackTarget),
        distance,
        player.getAttackStrengthScale(0)
      )) {
        return;
      }
      if (!CombatTaskSupport.ensureBestMeleeWeapon(bot, null)) {
        return;
      }
      var wasSprinting = player.isSprinting();
      player.setSprinting(true);
      try {
        gameMode.attack(player, attackTarget);
        player.swing(InteractionHand.MAIN_HAND, player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
      } finally {
        player.setSprinting(wasSprinting);
      }
    }

    private void abortForPlayerDeath() {
      var failure = Status.FAILED_PRECONDITION
        .withDescription("Player died while fleeing")
        .asRuntimeException();
      var escape = activeEscape;
      activeEscape = null;
      if (escape != null) {
        escape.control().onStopped(ControlStopReason.FAILED, failure);
      }
      lowerShield();
      context.bot().controlState().resetAll();
      result.completeExceptionally(failure);
    }

    private void tickEscape() {
      var escape = Objects.requireNonNull(activeEscape);
      if (!escape.result().isDone()) {
        escape.control().tick();
      }
      if (!escape.result().isDone()) {
        maybeRestartForThreatRegression(escape);
        maybeRestartForNewProjectile();
        dodgeIncomingProjectile();
        return;
      }
      lowerShield();
      activeEscape = null;
      try {
        escape.result().join();
        escape.control().onStopped(ControlStopReason.COMPLETED, null);
        consecutiveFailures = 0;
        escapes++;
        safeTicks = 0;
        if (input.getMaximumEscapes() > 0
          && escapes >= input.getMaximumEscapes()) {
          complete(
            FleeCompletionReason
              .FLEE_COMPLETION_REASON_ESCAPE_LIMIT_REACHED
          );
        }
      } catch (Throwable throwable) {
        var cause = throwable instanceof CompletionException
          && throwable.getCause() != null
          ? throwable.getCause()
          : throwable;
        escape.control().onStopped(ControlStopReason.FAILED, cause);
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          throw new CompletionException(
            "Unable to escape after "
              + consecutiveFailures + " path attempts",
            cause
          );
        }
      }
    }

    private void startEscape() {
      lastThreatReplanTick = ticks;
      var searchSnapshot = threatField.constraintSnapshot();
      var projectileSnapshot = projectileSnapshot();
      var escape = GoToTaskProvider.start(
        context,
        groupEscapeGoal(searchSnapshot),
        input.getOptions(),
        constraint -> new ProjectileAvoidanceConstraint(
          new ThreatAvoidanceConstraint(
            constraint,
            searchSnapshot
          ),
          projectileSnapshot
        )
      );
      activeEscape = escape;
      plannedProjectileIds = projectileSnapshot.trajectories().stream()
        .map(ProjectileAvoidanceConstraint.Trajectory::entityId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
      previousSafetyMargin = threatField.minimumSafetyMargin();
      escape.control().onStarted();
    }

    private ProjectileAvoidanceConstraint.Snapshot projectileSnapshot() {
      var minecraft = context.bot().minecraft();
      var player = minecraft.player;
      var level = minecraft.level;
      if (player == null || level == null) {
        return new ProjectileAvoidanceConstraint.Snapshot(
          Vec3.ZERO,
          List.of()
        );
      }
      var trajectories = StreamSupport.stream(
          level.entitiesForRendering().spliterator(),
          false
        )
        .filter(AbstractArrow.class::isInstance)
        .map(AbstractArrow.class::cast)
        .filter(Entity::isAlive)
        .filter(arrow -> arrow.getOwner() != player)
        .filter(arrow -> arrow.getDeltaMovement().lengthSqr() >= 1.0E-6)
        .map(FleeControl::trajectory)
        .toList();
      return new ProjectileAvoidanceConstraint.Snapshot(
        player.getBoundingBox().getCenter(),
        trajectories
      );
    }

    private static ProjectileAvoidanceConstraint.Trajectory trajectory(
      AbstractArrow arrow
    ) {
      var segments = new ArrayList<
        ProjectileAvoidanceConstraint.Segment
      >();
      var position = arrow.position();
      var velocity = arrow.getDeltaMovement();
      for (var tick = 0; tick < PROJECTILE_MAXIMUM_TICKS_TO_IMPACT; tick++) {
        if (velocity.lengthSqr() < 1.0E-6) {
          break;
        }
        var next = position.add(velocity);
        segments.add(new ProjectileAvoidanceConstraint.Segment(
          position,
          next
        ));
        position = next;
        velocity = velocity.scale(ARROW_INERTIA)
          .add(0, -ARROW_GRAVITY, 0);
      }
      return new ProjectileAvoidanceConstraint.Trajectory(
        arrow.getId(),
        segments
      );
    }

    private DynamicGoalScorer groupEscapeGoal(
      ThreatAvoidanceConstraint.Snapshot snapshot
    ) {
      return () -> new AwayFromPositionsGoal(
        snapshot.threats().stream()
          .map(ThreatAvoidanceConstraint.Threat::position)
          .toList(),
        Math.max(1, Math.round(safeDistance))
      );
    }

    private void maybeRestartForThreatRegression(
      BotTaskExecution escape
    ) {
      var margin = threatField.minimumSafetyMargin();
      var regressed = margin
        < previousSafetyMargin - SAFETY_MARGIN_REGRESSION;
      previousSafetyMargin = margin;
      if (
        !regressed
          || margin > SAFETY_MARGIN_REPLAN_THRESHOLD
          || ticks - lastThreatReplanTick < THREAT_REPLAN_COOLDOWN_TICKS
      ) {
        return;
      }
      lastThreatReplanTick = ticks;
      activeEscape = null;
      escape.control().onStopped(ControlStopReason.REPLACED, null);
      startEscape();
    }

    private void maybeRestartForNewProjectile() {
      var incoming = incomingProjectile();
      if (
        incoming == null
          || plannedProjectileIds.contains(incoming.arrow().getId())
          || ticks - lastProjectileReplanTick
            < PROJECTILE_REPLAN_COOLDOWN_TICKS
      ) {
        return;
      }
      lastProjectileReplanTick = ticks;
      var escape = activeEscape;
      activeEscape = null;
      if (escape != null) {
        escape.control().onStopped(ControlStopReason.REPLACED, null);
      }
      startEscape();
    }

    private void dodgeIncomingProjectile() {
      var incoming = incomingProjectile();
      if (incoming == null) {
        lowerShield();
        return;
      }
      var minecraft = context.bot().minecraft();
      var player = minecraft.player;
      var gameMode = minecraft.gameMode;
      if (player == null || gameMode == null) {
        return;
      }
      if (
        incoming.dodge().ticksToImpact()
          <= PROJECTILE_SHIELD_TICKS_TO_IMPACT
          && player.getOffhandItem().is(Items.SHIELD)
      ) {
        var bot = context.bot();
        bot.controlState().resetAll();
        bot.rotationControl().lookAt(incoming.arrow().position());
        if (
          player.isUsingItem()
            && player.getUsedItemHand() != InteractionHand.OFF_HAND
        ) {
          gameMode.releaseUsingItem(player);
        }
        if (!player.isUsingItem()) {
          gameMode.useItem(player, InteractionHand.OFF_HAND);
        }
        shieldRaised = true;
        return;
      }
      lowerShield();
      var controls = context.bot().controlState();
      controls.left(incoming.dodge().left());
      controls.right(!incoming.dodge().left());
      controls.jump(player.onGround());
    }

    private @Nullable IncomingProjectile incomingProjectile() {
      var minecraft = context.bot().minecraft();
      var player = minecraft.player;
      var level = minecraft.level;
      if (player == null || level == null) {
        return null;
      }
      var playerCenter = player.getBoundingBox().getCenter();
      return StreamSupport.stream(
          level.entitiesForRendering().spliterator(),
          false
        )
        .filter(AbstractArrow.class::isInstance)
        .map(AbstractArrow.class::cast)
        .filter(Entity::isAlive)
        .filter(arrow -> arrow.getOwner() != player)
        .map(arrow -> {
          var dodge = incomingProjectileDodge(
            arrow.position(),
            arrow.getDeltaMovement(),
            playerCenter,
            player.getYRot(),
            arrow.getId()
          );
          return dodge == null ? null : new IncomingProjectile(arrow, dodge);
        })
        .filter(Objects::nonNull)
        .min(Comparator.comparingDouble(
          incoming -> incoming.dodge().ticksToImpact()
        ))
        .orElse(null);
    }

    private void lowerShield() {
      if (!shieldRaised) {
        return;
      }
      shieldRaised = false;
      var minecraft = context.bot().minecraft();
      var player = minecraft.player;
      var gameMode = minecraft.gameMode;
      if (
        player != null
          && gameMode != null
          && player.isUsingItem()
          && player.getUsedItemHand() == InteractionHand.OFF_HAND
      ) {
        gameMode.releaseUsingItem(player);
      }
    }

    private void complete(FleeCompletionReason reason) {
      lowerShield();
      result.complete(FleeTaskResult.newBuilder()
        .setFinalPosition(BotTaskSupport.position(context.bot()))
        .setReason(reason)
        .setEscapes(escapes)
        .build());
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
      lowerShield();
      if (activeEscape != null) {
        activeEscape.control().onSuspended();
      }
    }

    @Override
    public void onResumed() {
      if (activeEscape != null) {
        activeEscape.control().onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var escape = activeEscape;
      activeEscape = null;
      if (escape != null) {
        escape.control().onStopped(reason, cause);
      }
      lowerShield();
      context.bot().controlState().resetAll();
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Flee from matching threats";
    }
  }
}
