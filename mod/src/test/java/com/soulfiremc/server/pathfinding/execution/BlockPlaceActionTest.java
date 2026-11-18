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

import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockPlaceActionTest {
  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void retriesAnInteractionMinecraftRefusedOnce() {
    assertFalse(BlockPlaceAction.placementWasRejected(1, false, 0));
  }

  @Test
  void rejectsRepeatedInteractionFailures() {
    assertTrue(BlockPlaceAction.placementWasRejected(4, false, 0));
  }

  @Test
  void waitsForARecentPredictionToBeConfirmed() {
    assertFalse(BlockPlaceAction.placementWasRejected(0, true, 20));
  }

  @Test
  void rejectsAPlacementWhosePredictionNeverSettles() {
    assertTrue(BlockPlaceAction.placementWasRejected(0, true, 40));
  }

  @Test
  void requiresReplaceableTargetAndStablePlacementSupport() {
    assertTrue(BlockPlaceAction.hasPlacementSupport(
      Blocks.AIR.defaultBlockState(),
      Blocks.STONE.defaultBlockState()
    ));
    assertFalse(BlockPlaceAction.hasPlacementSupport(
      Blocks.AIR.defaultBlockState(),
      Blocks.AIR.defaultBlockState()
    ));
    assertTrue(BlockPlaceAction.hasPlacementSupport(
      Blocks.STONE.defaultBlockState(),
      Blocks.AIR.defaultBlockState()
    ));
  }

  @Test
  void swimsUpInsteadOfSneakingWhilePlacingInWater() {
    // Sneaking in water swims down, under the block being placed
    assertFalse(BlockPlaceAction.sneaksWhilePlacing(true));
    assertTrue(BlockPlaceAction.sneaksWhilePlacing(false));
  }
}
