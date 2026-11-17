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
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class SFBlockHelpersTest {
  private static final BlockPos FLOOR = new BlockPos(0, 2, 0);

  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @CsvSource({"0,0,0,0", "15,15,0,0", "16,31,1,1", "-1,-17,-1,-2", "-16,-32,-1,-2"})
  void chunkAvailabilityFollowsReceivedTerrain(int blockX, int blockZ, int chunkX, int chunkZ) {
    var level = mock(ClientLevel.class);
    var chunks = mock(ClientChunkCache.class);
    when(level.getChunkSource()).thenReturn(chunks);
    var position = new BlockPos(blockX, 64, blockZ);
    assertFalse(SFBlockHelpers.isChunkLoaded(level, position));
    when(chunks.hasChunk(chunkX, chunkZ)).thenReturn(true);
    assertTrue(SFBlockHelpers.isChunkLoaded(level, position));
    when(chunks.hasChunk(chunkX, chunkZ)).thenReturn(false);
    assertFalse(SFBlockHelpers.isChunkLoaded(level, position));
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
  void detectsALavaDropBesideTheFeet() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, -2, 0, Blocks.LAVA);
    var level = accessor.build();

    assertTrue(SFBlockHelpers.isNextToLavaDrop(level, new BlockPos(0, 1, 0), 3));
    assertFalse(SFBlockHelpers.isNextToLavaDrop(level, new BlockPos(0, 1, 0), 2));
    // A solid block above the lava catches the fall
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    assertFalse(SFBlockHelpers.isNextToLavaDrop(accessor.build(), new BlockPos(0, 1, 0), 3));
  }

  @Test
  void iceIsSlipperyAndStoneIsNot() {
    assertTrue(SFBlockHelpers.isSlippery(Blocks.ICE.defaultBlockState()));
    assertTrue(SFBlockHelpers.isSlippery(Blocks.PACKED_ICE.defaultBlockState()));
    assertTrue(SFBlockHelpers.isSlippery(Blocks.BLUE_ICE.defaultBlockState()));
    assertFalse(SFBlockHelpers.isSlippery(Blocks.STONE.defaultBlockState()));
    assertFalse(SFBlockHelpers.isSlippery(Blocks.SNOW_BLOCK.defaultBlockState()));
  }

  @Test
  void onlyFarmlandBreaksWhenFallenOn() {
    assertTrue(SFBlockHelpers.breaksWhenFallenOn(Blocks.FARMLAND.defaultBlockState()));
    assertFalse(SFBlockHelpers.breaksWhenFallenOn(Blocks.DIRT_PATH.defaultBlockState()));
    assertFalse(SFBlockHelpers.breaksWhenFallenOn(Blocks.STONE.defaultBlockState()));
  }
}
