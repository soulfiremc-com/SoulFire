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

import com.soulfiremc.grpc.generated.IntRange;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.WithinBlockReachGoal;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.test.utils.TestBlockAccessorBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CollectBlocksTaskProviderTest {
  @Test
  void directCollectionDoesNotExtendAShaftBelowThePlayer() {
    var playerFeet = new BlockPos(10, 64, -5);

    assertFalse(CollectBlocksTaskProvider.isDirectBreakCandidate(
      playerFeet,
      playerFeet.below()
    ));
    assertTrue(CollectBlocksTaskProvider.isDirectBreakCandidate(
      playerFeet,
      playerFeet.offset(1, 0, 0)
    ));
    assertTrue(CollectBlocksTaskProvider.isDirectBreakCandidate(
      playerFeet,
      playerFeet.above(4)
    ));
    assertTrue(CollectBlocksTaskProvider.isDirectBreakCandidate(
      playerFeet,
      playerFeet.offset(1, -1, 0)
    ));
    assertFalse(CollectBlocksTaskProvider.isDirectBreakCandidate(
      playerFeet,
      playerFeet.below()
    ));
  }

  @Test
  void detectsTargetsUnderAFluidColumn() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 56, 0, Blocks.IRON_ORE);
    blocks.setBlockAt(0, 57, 0, Blocks.STONE);
    blocks.setBlockAt(0, 58, 0, Blocks.STONE);
    blocks.setBlockAt(0, 59, 0, Blocks.DIRT);
    blocks.setBlockAt(0, 60, 0, Blocks.WATER);
    blocks.setBlockAt(1, 56, 0, Blocks.IRON_ORE);
    var level = blocks.build();
    var playerFeet = new BlockPos(0, 62, 0);

    assertTrue(CollectBlocksTaskProvider.hasFluidAbove(
      level,
      playerFeet,
      new BlockPos(0, 56, 0)
    ));
    assertFalse(CollectBlocksTaskProvider.hasFluidAbove(
      level,
      playerFeet,
      new BlockPos(1, 56, 0)
    ));
  }

  @Test
  void skipsOreThatWouldLetLavaFlowIn() {
    // Two gold ores at the edge of a Nether lava sea: the outer one touches
    // a lava source, the inner one only touches the outer ore.
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(58, 29, -38, Blocks.NETHER_GOLD_ORE);
    blocks.setBlockAt(59, 29, -38, Blocks.NETHER_GOLD_ORE);
    blocks.setBlockAt(60, 29, -38, Blocks.LAVA);
    blocks.setBlockAt(0, 30, 0, Blocks.NETHER_GOLD_ORE);
    blocks.setBlockAt(0, 31, 0, Blocks.LAVA);
    blocks.setBlockAt(5, 30, 0, Blocks.NETHER_GOLD_ORE);
    blocks.setBlockAt(5, 29, 0, Blocks.LAVA);
    var level = blocks.build();

    assertTrue(CollectBlocksTaskProvider.exposesLava(level, new BlockPos(59, 29, -38)));
    assertTrue(CollectBlocksTaskProvider.exposesLava(level, new BlockPos(0, 30, 0)));
    assertFalse(CollectBlocksTaskProvider.exposesLava(level, new BlockPos(58, 29, -38)));
    assertFalse(CollectBlocksTaskProvider.exposesLava(level, new BlockPos(5, 30, 0)));
  }

  @Test
  void onlyTreatsTheFirstRaycastBlockAsDirectlyReachable() {
    var target = new BlockPos(4, 65, -2);

    assertTrue(CollectBlocksTaskProvider.hitsTargetBlock(
      target,
      new BlockHitResult(
        Vec3.atCenterOf(target),
        Direction.UP,
        target,
        false
      )
    ));
    assertFalse(CollectBlocksTaskProvider.hitsTargetBlock(
      target,
      new BlockHitResult(
        Vec3.atCenterOf(target.below()),
        Direction.UP,
        target.below(),
        false
      )
    ));
  }

  @Test
  void onlyCollectsBlocksVisibleFromTheCurrentEyePosition() {
    var blocks = new TestBlockAccessorBuilder();
    var target = new BlockPos(0, 64, 4);
    var blocker = new BlockPos(0, 64, 2);
    blocks.setBlockAt(target.getX(), target.getY(), target.getZ(), Blocks.OAK_LOG);
    var unobstructed = blocks.build();

    assertTrue(CollectBlocksTaskProvider.hasLineOfSight(
      unobstructed,
      new Vec3(0.5D, 64.5D, 0.5D),
      target
    ));

    blocks.setBlockAt(
      blocker.getX(),
      blocker.getY(),
      blocker.getZ(),
      Blocks.STONE
    );
    assertFalse(CollectBlocksTaskProvider.hasLineOfSight(
      blocks.build(),
      new Vec3(0.5D, 64.5D, 0.5D),
      target
    ));
  }

  @Test
  void onlyUsesAReachGoalForACurrentlyVisibleTarget() {
    var blocks = new TestBlockAccessorBuilder();
    var eyePosition = new Vec3(0.5D, 65.62D, 0.5D);
    var visibleTarget = new SFVec3i(4, 64, 0);
    var buriedTarget = new SFVec3i(0, 63, 2);
    blocks.setBlockAt(4, 64, 0, Blocks.OAK_LOG);
    blocks.setBlockAt(0, 63, 2, Blocks.STONE);
    blocks.setBlockAt(0, 64, 2, Blocks.DIRT);
    blocks.setBlockAt(0, 62, 2, Blocks.DIRT);
    blocks.setBlockAt(-1, 63, 2, Blocks.DIRT);
    blocks.setBlockAt(1, 63, 2, Blocks.DIRT);
    blocks.setBlockAt(0, 63, 1, Blocks.DIRT);
    blocks.setBlockAt(0, 63, 3, Blocks.DIRT);

    var visibleGoals = CollectBlocksTaskProvider.collectionGoals(
      blocks.build(),
      eyePosition,
      visibleTarget,
      false,
      Map.of()
    );
    var buriedGoals = CollectBlocksTaskProvider.collectionGoals(
      blocks.build(),
      eyePosition,
      buriedTarget,
      false,
      Map.of()
    );

    assertEquals(1, visibleGoals.stream()
      .filter(BreakBlockPosGoal.class::isInstance)
      .count());
    assertEquals(
      Set.of(visibleTarget),
      visibleGoals.stream()
        .filter(WithinBlockReachGoal.class::isInstance)
        .map(WithinBlockReachGoal.class::cast)
        .map(WithinBlockReachGoal::block)
        .collect(Collectors.toSet())
    );
    assertEquals(1, buriedGoals.size());
    assertTrue(buriedGoals.iterator().next() instanceof BreakBlockPosGoal);
  }

  @Test
  void usesReachGoalsForOccludedTargetsWhenVisibilityIsOptional() {
    var blocks = new TestBlockAccessorBuilder();
    var eyePosition = new Vec3(0.5D, 65.62D, 0.5D);
    var target = new SFVec3i(0, 70, 0);
    blocks.setBlockAt(0, 70, 0, Blocks.OAK_LOG);
    blocks.setBlockAt(0, 68, 0, Blocks.OAK_LEAVES);

    var goals = CollectBlocksTaskProvider.collectionGoals(
      blocks.build(),
      eyePosition,
      target,
      true,
      Map.of()
    );

    assertTrue(goals.stream()
      .filter(WithinBlockReachGoal.class::isInstance)
      .map(WithinBlockReachGoal.class::cast)
      .anyMatch(goal -> goal.block().equals(target)));
  }

  @Test
  void forcesAnOccludedTargetBreakAfterTheFirstApproachStalls() {
    var blocks = new TestBlockAccessorBuilder();
    var eyePosition = new Vec3(0.5D, 65.62D, 0.5D);
    var target = new SFVec3i(0, 70, 0);
    var failedPosition = new SFVec3i(1, 67, 0);
    blocks.setBlockAt(0, 70, 0, Blocks.OAK_LOG);
    blocks.setBlockAt(0, 69, 0, Blocks.OAK_LEAVES);

    var goals = CollectBlocksTaskProvider.collectionGoals(
      blocks.build(),
      eyePosition,
      target,
      true,
      Map.of(target, Set.of(failedPosition))
    );

    assertEquals(1, goals.size());
    assertTrue(goals.iterator().next() instanceof BreakBlockPosGoal);
  }

  @Test
  void restrictsCollectionToTheRequestedElevationRange() {
    var range = IntRange.newBuilder()
      .setMinimum(60)
      .setMaximum(80)
      .build();

    assertFalse(CollectBlocksTaskProvider.isWithinTargetY(59, range));
    assertTrue(CollectBlocksTaskProvider.isWithinTargetY(60, range));
    assertTrue(CollectBlocksTaskProvider.isWithinTargetY(80, range));
    assertFalse(CollectBlocksTaskProvider.isWithinTargetY(81, range));
    assertTrue(CollectBlocksTaskProvider.isWithinTargetY(0, null));
  }

  @Test
  void reachesTheNearestFaceOfAnOverheadBlock() {
    var playerFeet = new SFVec3i(0, 64, 0);
    var eyePosition = new Vec3(0.5D, 65.62D, 0.5D);
    var overheadLog = playerFeet.add(0, 6, 0);

    assertTrue(
      Vec3.atCenterOf(overheadLog.toBlockPos())
        .distanceToSqr(eyePosition)
        > 4.5D * 4.5D
    );
    assertTrue(CollectBlocksTaskProvider.directBreakFaces(
      eyePosition,
      overheadLog
    ).contains(BlockFace.BOTTOM));
  }

  @Test
  void rejectsDropSensitiveBlocksWhenNoSuitableToolExists() {
    assertFalse(CollectBlocksTaskProvider.hasDropPreservingTool(
      Blocks.STONE.defaultBlockState(),
      List.of(ItemStack.EMPTY)
    ));
    assertTrue(CollectBlocksTaskProvider.hasDropPreservingTool(
      Blocks.OAK_LOG.defaultBlockState(),
      List.of(ItemStack.EMPTY)
    ));
  }

  @Test
  void retriesOnlyTargetsAdjacentToAStalledCollectionPosition() {
    var playerPosition = new SFVec3i(10, 64, -5);
    var adjacentTarget = playerPosition.add(1, 0, 0);
    var overheadTarget = playerPosition.add(0, 3, 0);
    var distantTarget = playerPosition.add(6, 0, 0);

    assertEquals(
      Set.of(adjacentTarget, overheadTarget),
      CollectBlocksTaskProvider.stalledTargets(
        Set.of(adjacentTarget, overheadTarget, distantTarget),
        playerPosition
      )
    );
  }

  @Test
  void retriesOnlyTheNearestTargetWhenNoTargetIsAdjacent() {
    var playerPosition = new SFVec3i(10, 64, -5);
    var overheadTarget = playerPosition.add(0, 7, 0);
    var distantTarget = playerPosition.add(8, 0, 0);

    assertEquals(
      Set.of(overheadTarget),
      CollectBlocksTaskProvider.stalledTargets(
        Set.of(overheadTarget, distantTarget),
        playerPosition
      )
    );
  }

  @Test
  void abandonsATargetWhenThePathfinderRepeatsAFailedApproach() {
    var target = new SFVec3i(10, 64, -5);
    var failedApproaches = new HashMap<SFVec3i, Set<SFVec3i>>();
    var rejectedTargets = new HashSet<SFVec3i>();
    var firstApproach = target.add(1, 0, 0);

    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      firstApproach
    ));
    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      firstApproach
    ));

    assertEquals(Set.of(target), rejectedTargets);
    assertFalse(failedApproaches.containsKey(target));
  }

  @Test
  void abandonsATargetAfterFourDistinctFailedApproaches() {
    var target = new SFVec3i(10, 64, -5);
    var failedApproaches = new HashMap<SFVec3i, Set<SFVec3i>>();
    var rejectedTargets = new HashSet<SFVec3i>();

    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      target.add(1, 0, 0)
    ));
    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      target.add(-1, 0, 0)
    ));
    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      target.add(0, 0, 1)
    ));
    assertTrue(CollectBlocksTaskProvider.recordFailedApproach(
      failedApproaches,
      rejectedTargets,
      target,
      target.add(0, 0, -1)
    ));

    assertEquals(Set.of(target), rejectedTargets);
    assertFalse(failedApproaches.containsKey(target));
  }

  @Test
  void retriesAPreviouslyVisibleTargetFromAnOccludedApproach() {
    assertTrue(CollectBlocksTaskProvider.hasRequiredLineOfSight(
      true,
      true,
      () -> false
    ));
    assertFalse(CollectBlocksTaskProvider.hasRequiredLineOfSight(
      true,
      false,
      () -> false
    ));
  }

  @Test
  void ranksOneNearestMiningTargetDeterministically() {
    var origin = new BlockPos(10, 64, -5);
    var nearestLower = new SFVec3i(9, 64, -5);
    var nearestUpper = new SFVec3i(11, 64, -5);
    var farther = new SFVec3i(10, 64, -8);

    assertEquals(
      List.of(nearestLower, nearestUpper, farther),
      List.of(farther, nearestUpper, nearestLower).stream()
        .sorted(CollectBlocksTaskProvider.candidateComparator(origin))
        .toList()
    );
  }
}
