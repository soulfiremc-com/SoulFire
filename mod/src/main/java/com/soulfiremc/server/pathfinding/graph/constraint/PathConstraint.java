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

import com.soulfiremc.server.pathfinding.RouteSearchMode;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.graph.DiagonalCollisionCalculator;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public interface PathConstraint {
  boolean doUsableBlocksDecreaseWhenPlaced();

  boolean canBlocksDropWhenBroken();

  boolean canBreakBlocks();

  boolean canPlaceBlocks();

  boolean isPlaceable(ItemStack item);

  boolean isPlaceableBlockDrop(BlockState blockState);

  boolean isTool(ItemStack item);

  boolean isOutOfLevel(BlockState blockState, SFVec3i pos);

  boolean canBreakBlock(SFVec3i pos, BlockState blockState);

  boolean canPlaceBlock(SFVec3i pos);

  boolean collidesWithAtEdge(DiagonalCollisionCalculator.CollisionData collisionData);

  GraphInstructions modifyAsNeeded(GraphInstructions instruction);

  /// Returns whether a generated movement may be considered by pathfinding.
  /// The block accessor belongs to the current route-search snapshot.
  default boolean allowsInstruction(
    GraphInstructions instruction,
    BlockGetter blockAccessor
  ) {
    return true;
  }

  /// Returns the cost penalty for breaking a block during pathfinding.
  double breakBlockPenalty();

  /// Returns the cost penalty for placing a block during pathfinding.
  double placeBlockPenalty();

  /// Returns the maximum time in seconds before pathfinding gives up.
  int expireTimeout();

  /// Returns the explicit quality and latency policy for route search.
  default RouteSearchMode searchMode() {
    return RouteSearchMode.NORMAL;
  }

  /// Returns the largest accepted multiplicative route-quality bound.
  default double maximumQualityBound() {
    return searchMode().defaultQualityBound();
  }

  /// Returns the hard state-expansion budget for the complete ARA* session.
  default int maximumExpandedStates() {
    return 50_000;
  }

  /// Returns the largest ordinary fall onto solid ground.
  default int maximumFallDistance() {
    return 3;
  }

  /// Returns the largest horizontal parkour gap. Zero disables parkour.
  default int maximumParkourGap() {
    return 0;
  }

  /// Returns whether ordinary forward path traversal should sprint.
  default boolean sprint() {
    return true;
  }

  /// Returns whether the executor can smooth camera movement when a movement
  /// primitive declares a safe tolerance.
  default boolean smoothCamera() {
    return false;
  }

  /// Returns the pitch the bot holds while it walks.
  default float walkingPitch() {
    return 0.0F;
  }
}
