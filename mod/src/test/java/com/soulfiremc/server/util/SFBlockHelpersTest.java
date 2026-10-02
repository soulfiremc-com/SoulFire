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
package com.soulfiremc.server.util;

import com.soulfiremc.test.utils.TestBlockAccessorBuilder;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SFBlockHelpersTest {
  private static final BlockPos FLOOR = new BlockPos(0, 2, 0);

  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void acceptsOrdinaryAndSupportedGravityFloors() {
    var stoneFloor = new TestBlockAccessorBuilder();
    stoneFloor.setBlockAt(0, 2, 0, Blocks.STONE);

    assertTrue(SFBlockHelpers.isStableWalkableFloorBlock(
      stoneFloor.build(),
      FLOOR,
      Blocks.STONE.defaultBlockState()
    ));

    var supportedGravel = new TestBlockAccessorBuilder();
    supportedGravel.setBlockAt(0, 1, 0, Blocks.NETHERRACK);
    supportedGravel.setBlockAt(0, 2, 0, Blocks.GRAVEL);

    assertTrue(SFBlockHelpers.isStableWalkableFloorBlock(
      supportedGravel.build(),
      FLOOR,
      Blocks.GRAVEL.defaultBlockState()
    ));
  }

  @Test
  void rejectsSuspendedGravityFloorsOverAirOrLava() {
    var overAir = new TestBlockAccessorBuilder();
    overAir.setBlockAt(0, 2, 0, Blocks.GRAVEL);

    assertFalse(SFBlockHelpers.isStableWalkableFloorBlock(
      overAir.build(),
      FLOOR,
      Blocks.GRAVEL.defaultBlockState()
    ));

    var overLava = new TestBlockAccessorBuilder();
    overLava.setBlockAt(0, 1, 0, Blocks.LAVA);
    overLava.setBlockAt(0, 2, 0, Blocks.GRAVEL);

    assertFalse(SFBlockHelpers.isStableWalkableFloorBlock(
      overLava.build(),
      FLOOR,
      Blocks.GRAVEL.defaultBlockState()
    ));
  }

  @Test
  void rejectsAGravityColumnWhoseBottomBlockWillFall() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 1, 0, Blocks.GRAVEL);
    blocks.setBlockAt(0, 2, 0, Blocks.SAND);

    assertFalse(SFBlockHelpers.isStableWalkableFloorBlock(
      blocks.build(),
      FLOOR,
      Blocks.SAND.defaultBlockState()
    ));
  }

  @Test
  void farmlandAndDirtPathsAreWalkableRaisedFloors() {
    for (var block : List.of(Blocks.FARMLAND, Blocks.DIRT_PATH)) {
      var state = block.defaultBlockState();
      assertTrue(SFBlockHelpers.isRaisedFullFloorBlock(state), block.toString());
      assertTrue(SFBlockHelpers.isWalkableFloorBlock(state), block.toString());
      assertTrue(SFBlockHelpers.isFloorBelowFeet(state), block.toString());
    }
  }

  @Test
  void onlyNearlyFullSquareTopsAreRaisedFloors() {
    // Big dripleaf has a 15/16 top too, but tips over
    for (var block : List.of(
      Blocks.STONE,
      Blocks.SOUL_SAND,
      Blocks.MUD,
      Blocks.CHEST,
      Blocks.OAK_SLAB,
      Blocks.HONEY_BLOCK,
      Blocks.BIG_DRIPLEAF
    )) {
      assertFalse(
        SFBlockHelpers.isRaisedFullFloorBlock(block.defaultBlockState()),
        block.toString()
      );
    }
  }

  @Test
  void onlyFarmlandBreaksWhenFallenOn() {
    assertTrue(SFBlockHelpers.breaksWhenFallenOn(Blocks.FARMLAND.defaultBlockState()));
    assertFalse(SFBlockHelpers.breaksWhenFallenOn(Blocks.DIRT_PATH.defaultBlockState()));
    assertFalse(SFBlockHelpers.breaksWhenFallenOn(Blocks.STONE.defaultBlockState()));
  }

  @Test
  void findsLavaAndFireAroundTheFeetOrTheHead() {
    var feet = new BlockPos(0, 3, 0);
    var fallingLava = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);

    var beside = new TestBlockAccessorBuilder();
    beside.setBlockStateAt(0, 3, -1, fallingLava);
    assertTrue(SFBlockHelpers.isNearLavaOrFire(beside.build(), feet));

    var atTheCorner = new TestBlockAccessorBuilder();
    atTheCorner.setBlockAt(1, 4, 1, Blocks.FIRE);
    assertTrue(SFBlockHelpers.isNearLavaOrFire(atTheCorner.build(), feet));
  }

  @Test
  void ignoresLavaOutOfReachAndBlocksThatDontBurn() {
    var feet = new BlockPos(0, 3, 0);

    // Below the floor, above the head, two blocks away; and blocks that only slow or prick
    var away = new TestBlockAccessorBuilder();
    away.setBlockAt(1, 2, 0, Blocks.LAVA);
    away.setBlockAt(0, 5, -1, Blocks.LAVA);
    away.setBlockAt(2, 3, 0, Blocks.LAVA);
    away.setBlockAt(-1, 3, 0, Blocks.COBWEB);
    away.setBlockAt(0, 4, 1, Blocks.CACTUS);
    assertFalse(SFBlockHelpers.isNearLavaOrFire(away.build(), feet));
  }
}
