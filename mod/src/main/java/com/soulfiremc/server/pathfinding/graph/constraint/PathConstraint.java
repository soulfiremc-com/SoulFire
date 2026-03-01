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
import com.soulfiremc.server.pathfinding.graph.DiagonalCollisionCalculator;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public interface PathConstraint {
  boolean doUsableBlocksDecreaseWhenPlaced();

  boolean canBlocksDropWhenBroken();

  boolean canBreakBlocks();

  boolean canPlaceBlocks();

  boolean isPlaceable(ItemStack item);

  boolean isTool(ItemStack item);

  boolean isOutOfLevel(BlockState blockState, SFVec3i pos);

  boolean canBreakBlock(SFVec3i pos, BlockState blockState);

  boolean canPlaceBlock(SFVec3i pos);

  boolean collidesWithAtEdge(DiagonalCollisionCalculator.CollisionData collisionData);

  GraphInstructions modifyAsNeeded(GraphInstructions instruction);

  /// Returns the cost penalty for breaking a block during pathfinding.
  double breakBlockPenalty();

  /// Returns the cost penalty for placing a block during pathfinding.
  double placeBlockPenalty();

  /// Returns the maximum time in seconds before pathfinding gives up.
  int expireTimeout();

  /// Returns whether pruning of the pathfinding search space is disabled.
  boolean disablePruning();

  double yawJitterMin();

  double yawJitterMax();

  double pitchJitterMin();

  double pitchJitterMax();
}
