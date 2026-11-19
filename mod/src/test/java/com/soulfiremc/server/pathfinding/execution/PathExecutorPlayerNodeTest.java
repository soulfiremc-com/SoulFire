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

import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.test.utils.TestBlockAccessorBuilder;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PathExecutorPlayerNodeTest {
  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void feetOnFarmlandStartAboveIt() {
    // Farmland is 15/16 high, so the feet are inside its block
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 0, 0, Blocks.FARMLAND);

    assertEquals(
      new SFVec3i(0, 1, 0),
      PathExecutor.playerNode(blocks.build(), new BlockPos(0, 0, 0))
    );
  }

  @Test
  void feetInAirOrABottomSlabStayInTheirBlock() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 0, 0, Blocks.STONE);
    blocks.setBlockAt(1, 1, 0, Blocks.OAK_SLAB);

    assertEquals(
      new SFVec3i(0, 1, 0),
      PathExecutor.playerNode(blocks.build(), new BlockPos(0, 1, 0))
    );
    assertEquals(
      new SFVec3i(1, 1, 0),
      PathExecutor.playerNode(blocks.build(), new BlockPos(1, 1, 0))
    );
  }

  @Test
  void aPlayerPressedOverAFencePostStartsInTheFreeCellNearest() {
    // Wedged between a chest and a fence post, feet over the post's cell
    var blocks = new TestBlockAccessorBuilder();
    for (var x = -2; x <= 2; x++) {
      for (var z = -2; z <= 2; z++) {
        blocks.setBlockAt(x, 0, z, Blocks.STONE);
      }
    }
    blocks.setBlockAt(1, 1, 0, Blocks.CHEST);
    blocks.setBlockAt(0, 1, 1, Blocks.OAK_FENCE);
    var level = blocks.build();

    assertEquals(new SFVec3i(0, 1, 0), PathExecutor.playerNode(level, new Vec3(0.76, 1, 1.07)));
    assertEquals(new SFVec3i(0, 1, 0), PathExecutor.playerNode(level, new Vec3(0.5, 1, 0.5)));
  }

  @Test
  void aPlayerOnTheEdgeOfALedgeStartsAboveTheLedge() {
    // As in a live run: the bot stood on the edge of a netherrack block with
    // its feet over an open drop into lava.
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 0, 0, Blocks.NETHERRACK);
    blocks.setBlockAt(-1, -10, 0, Blocks.LAVA);

    assertEquals(
      new SFVec3i(0, 1, 0),
      PathExecutor.playerNode(blocks.build(), new Vec3(-0.12, 1, 0.3))
    );
  }

  @Test
  void aPlayerBesideAFenceBelowItsFeetStartsAboveTheStone() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 0, 0, Blocks.STONE);
    blocks.setBlockAt(1, 0, 0, Blocks.OAK_FENCE);

    assertEquals(
      new SFVec3i(0, 1, 0),
      PathExecutor.playerNode(blocks.build(), new Vec3(1.05, 1, 0.5))
    );
  }

  @Test
  void aPlayerOnASlabOrFarmlandKeepsItsNode() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 0, 0, Blocks.FARMLAND);
    blocks.setBlockAt(1, 0, 0, Blocks.STONE);
    blocks.setBlockAt(1, 1, 0, Blocks.OAK_SLAB);
    var level = blocks.build();

    assertEquals(new SFVec3i(0, 1, 0), PathExecutor.playerNode(level, new Vec3(0.5, 0.9375, 0.5)));
    assertEquals(new SFVec3i(1, 1, 0), PathExecutor.playerNode(level, new Vec3(1.5, 1.5, 0.5)));
  }

  @Test
  void aPlayerOnADirtPathIsInTheNodeAboveIt() {
    var blocks = new TestBlockAccessorBuilder();
    blocks.setBlockAt(0, 67, 0, Blocks.DIRT_PATH);

    assertEquals(new SFVec3i(0, 68, 0), PathExecutor.playerNode(blocks.build(), new Vec3(0.5, 67.9375, 0.5)));
  }
}
