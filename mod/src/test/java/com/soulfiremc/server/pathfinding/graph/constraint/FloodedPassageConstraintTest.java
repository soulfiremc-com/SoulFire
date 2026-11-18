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
package com.soulfiremc.server.pathfinding.graph.constraint;

import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import com.soulfiremc.server.pathfinding.graph.actions.movement.ActionDirection;
import com.soulfiremc.test.utils.TestBlockAccessorBuilder;
import com.soulfiremc.test.utils.TestPathConstraint;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FloodedPassageConstraintTest {
  @Test
  void rejectsSwimmingUnderASolidCeiling() {
    // A flooded tunnel at x=0 and open water at x=1
    var blocks = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 1; x++) {
      blocks.setBlockAt(x, 63, 0, Blocks.STONE);
      blocks.setBlockAt(x, 64, 0, Blocks.WATER);
      blocks.setBlockAt(x, 65, 0, Blocks.WATER);
    }
    blocks.setBlockAt(0, 66, 0, Blocks.CLAY);
    blocks.setBlockAt(1, 66, 0, Blocks.WATER);
    var level = blocks.build();
    var outside = new FloodedPassageConstraint(TestPathConstraint.INSTANCE, false);
    var inside = new FloodedPassageConstraint(TestPathConstraint.INSTANCE, true);

    assertFalse(outside.allowsInstruction(instruction(0, 64, 0), level));
    assertTrue(outside.allowsInstruction(instruction(1, 64, 0), level));
    // A bot already in the tunnel may still swim out through it
    assertTrue(inside.allowsInstruction(instruction(0, 64, 0), level));
  }

  private static GraphInstructions instruction(int x, int y, int z) {
    return new GraphInstructions(
      SFVec3i.from(x, y, z),
      0,
      false,
      ActionDirection.NORTH,
      1,
      List.of()
    );
  }
}
