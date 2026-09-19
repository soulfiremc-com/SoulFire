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

import com.soulfiremc.grpc.generated.EntityReference;
import com.soulfiremc.grpc.generated.ItemSelector;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.PathfindingSupport.ResolvedGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToWorldBoxGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToWorldXZGoal;
import com.soulfiremc.server.pathfinding.goals.DynamicGoalScorer;
import com.soulfiremc.server.util.SFInventoryHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CombatTaskSupport {
  private static final double MAXIMUM_DRAGON_PART_DISTANCE = 32;
  private static final int DRAGON_APPROACH_BELOW_PODIUM_TOP = 4;
  private static final double DRAGON_APPROACH_HALF_WIDTH = 2;
  private static final double DRAGON_WAITING_RADIUS = 32;
  private static final double MAXIMUM_DRAGON_STAGING_STEP = 12;
  private static final double MINIMUM_REFINABLE_DRAGON_STAGING_SPAN = 2;

  private CombatTaskSupport() {
  }

  static Entity preferredTarget(Entity entity) {
    if (!(entity instanceof EnderDragon dragon)) {
      return entity;
    }
    return isPlausiblePartPosition(dragon.position(), dragon.head.position())
      ? dragon.head
      : dragon;
  }

  static boolean isPlausiblePartPosition(Vec3 parent, Vec3 part) {
    return parent.distanceToSqr(part)
      <= MAXIMUM_DRAGON_PART_DISTANCE * MAXIMUM_DRAGON_PART_DISTANCE;
  }

  static boolean isMeleeApproachable(Entity entity) {
    return !(entity instanceof EnderDragon dragon)
      || isDragonMeleePhase(
      dragon.getPhaseManager().getCurrentPhase().getPhase()
    );
  }

  static boolean isDragonMeleePhase(EnderDragonPhase<?> phase) {
    return phase == EnderDragonPhase.LANDING
      || phase == EnderDragonPhase.HOVERING
      || phase == EnderDragonPhase.SITTING_FLAMING
      || phase == EnderDragonPhase.SITTING_SCANNING
      || phase == EnderDragonPhase.SITTING_ATTACKING;
  }

  static DragonMeleeApproach dragonMeleeApproach(BlockPos podiumSurface) {
    var anchor = podiumSurface.below(DRAGON_APPROACH_BELOW_PODIUM_TOP);
    var position = Vec3.atBottomCenterOf(anchor);
    return new DragonMeleeApproach(
      new AABB(
        position.x - DRAGON_APPROACH_HALF_WIDTH,
        position.y,
        position.z - DRAGON_APPROACH_HALF_WIDTH,
        position.x + DRAGON_APPROACH_HALF_WIDTH,
        position.y + 1,
        position.z + DRAGON_APPROACH_HALF_WIDTH
      ),
      position
    );
  }

  private static DragonMeleeApproach dragonMeleeApproach(
    EnderDragon dragon
  ) {
    var podium = EnderDragonFight.getPodiumLocation(dragon.getFightOrigin());
    var podiumSurface = dragon.level().getHeightmapPos(
      Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
      podium
    );
    return dragonMeleeApproach(podiumSurface);
  }

  static List<DragonWaitingApproach> dragonWaitingApproaches(
    BlockPos fightOrigin,
    Vec3 startingPosition
  ) {
    var podium = EnderDragonFight.getPodiumLocation(fightOrigin);
    var position = Vec3.atBottomCenterOf(podium);
    var radius = Math.hypot(
      startingPosition.x - position.x,
      startingPosition.z - position.z
    );
    var approaches = new ArrayList<DragonWaitingApproach>();
    while (radius > DRAGON_WAITING_RADIUS) {
      radius = Math.max(
        DRAGON_WAITING_RADIUS,
        radius - MAXIMUM_DRAGON_STAGING_STEP
      );
      approaches.add(new DragonWaitingApproach(
        new CloseToWorldXZGoal(position.x, position.z, radius),
        position
      ));
    }
    return List.copyOf(approaches);
  }

  static List<ResolvedGoal> waitingGoals(
    BotConnection bot,
    EntityReference reference
  ) {
    var root = BotTaskSupport.requireEntity(bot, reference);
    if (!(root instanceof EnderDragon dragon)) {
      return List.of();
    }
    var player = Objects.requireNonNull(
      bot.minecraft().player,
      "Bot player is not available"
    );
    return dragonWaitingApproaches(
      dragon.getFightOrigin(),
      player.position()
    ).stream()
      .map(approach -> new ResolvedGoal(
        approach.goal(),
        _ -> approach.position()
      ))
      .toList();
  }

  static Optional<ResolvedGoal> refineDragonWaitingGoal(
    ResolvedGoal failedGoal,
    Vec3 currentPosition
  ) {
    if (!(failedGoal.scorer() instanceof CloseToWorldXZGoal goal)) {
      return Optional.empty();
    }
    var currentRadius = Math.hypot(
      currentPosition.x - goal.x(),
      currentPosition.z - goal.z()
    );
    var remainingSpan = currentRadius - goal.maxRadius();
    if (remainingSpan <= MINIMUM_REFINABLE_DRAGON_STAGING_SPAN) {
      return Optional.empty();
    }
    return Optional.of(new ResolvedGoal(
      new CloseToWorldXZGoal(
        goal.x(),
        goal.z(),
        goal.maxRadius() + remainingSpan / 2
      ),
      failedGoal.position()
    ));
  }

  static boolean ensureBestMeleeWeapon(
    BotConnection bot,
    @Nullable ItemSelector selector
  ) {
    var player = Objects.requireNonNull(bot.minecraft().player);
    var best = SFInventoryHelpers.playerInventorySlots(player.inventoryMenu)
      .mapToObj(slot -> player.inventoryMenu.getSlot(slot).getItem())
      .filter(stack -> selector == null
        || InventoryServiceImpl.matches(stack, selector))
      .filter(stack -> SFItemHelpers.meleeWeaponStats(stack).isPresent())
      .max((left, right) -> Double.compare(
        meleeWeaponScore(left),
        meleeWeaponScore(right)
      ));
    if (best.isEmpty()) {
      return selector == null;
    }
    var selected = best.orElseThrow().copy();
    return TaskInventorySupport.ensureHolding(
      bot,
      stack -> ItemStack.isSameItemSameComponents(stack, selected)
    );
  }

  private static double meleeWeaponScore(ItemStack stack) {
    var base = SFItemHelpers.meleeWeaponStats(stack)
      .orElseThrow()
      .score();
    if (!stack.isDamageableItem()) {
      return base;
    }
    var remaining = stack.getMaxDamage() - stack.getDamageValue();
    return base + (double) remaining / stack.getMaxDamage();
  }

  static PathfindingSupport.ResolvedGoal reachGoal(
    BotConnection bot,
    EntityReference reference,
    double radius
  ) {
    var initialRoot = BotTaskSupport.requireEntity(bot, reference);
    var initialTarget = preferredTarget(initialRoot);
    var dragonApproach = initialRoot instanceof EnderDragon dragon
      ? dragonMeleeApproach(dragon)
      : null;
    var initialBox = dragonApproach == null
      ? initialTarget.getBoundingBox()
      : dragonApproach.box();
    var playerEyeHeight = Objects.requireNonNull(
      bot.minecraft().player,
      "Bot player is not available"
    ).getEyeHeight();
    DynamicGoalScorer scorer = () -> {
      var currentRoot = BotTaskSupport.findEntity(
        bot,
        reference.getNetworkId()
      );
      var box = dragonApproach != null || currentRoot == null
        ? initialBox
        : preferredTarget(currentRoot).getBoundingBox();
      return new CloseToWorldBoxGoal(box, radius, playerEyeHeight);
    };
    return new PathfindingSupport.ResolvedGoal(
      scorer,
      connection -> {
        if (dragonApproach != null) {
          return dragonApproach.position();
        }
        var currentRoot = BotTaskSupport.findEntity(
          connection,
          reference.getNetworkId()
        );
        return currentRoot == null
          ? initialTarget.position()
          : preferredTarget(currentRoot).position();
      }
    );
  }

  record DragonMeleeApproach(AABB box, Vec3 position) {
  }

  record DragonWaitingApproach(CloseToWorldXZGoal goal, Vec3 position) {
  }
}
