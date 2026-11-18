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

import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.jspecify.annotations.Nullable;

/// Keeps routes out of flooded passages, where the head is under water and a
/// solid block above it leaves no way up for air. A live bot followed such a
/// route along the floor of a flooded tunnel and drowned under its ceiling.
/// A bot that already starts in one may still swim through to get out.
public record FloodedPassageConstraint(
  PathConstraint delegate,
  boolean startsInFloodedPassage
) implements DelegatePathConstraint {
  public static FloodedPassageConstraint forPlayer(
    PathConstraint delegate,
    @Nullable LocalPlayer player
  ) {
    var level = player == null ? null : player.level();
    return new FloodedPassageConstraint(
      delegate,
      level != null && isFloodedPassage(level, player.blockPosition())
    );
  }

  @Override
  public boolean allowsInstruction(
    GraphInstructions instruction,
    BlockGetter blockAccessor
  ) {
    return delegate.allowsInstruction(instruction, blockAccessor)
      && (startsInFloodedPassage
      || !isFloodedPassage(blockAccessor, instruction.blockPosition().toBlockPos()));
  }

  static boolean isFloodedPassage(BlockGetter level, BlockPos feet) {
    var head = feet.above();
    var ceiling = head.above();
    return !level.getFluidState(head).isEmpty()
      && level.getFluidState(ceiling).isEmpty()
      && !level.getBlockState(ceiling).getCollisionShape(level, ceiling).isEmpty();
  }
}
