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

public interface DelegatePathConstraint extends PathConstraint {
  @Override
  default boolean doUsableBlocksDecreaseWhenPlaced() {
    return delegate().doUsableBlocksDecreaseWhenPlaced();
  }

  @Override
  default boolean canBlocksDropWhenBroken() {
    return delegate().canBlocksDropWhenBroken();
  }

  @Override
  default boolean canBreakBlocks() {
    return delegate().canBreakBlocks();
  }

  @Override
  default boolean canPlaceBlocks() {
    return delegate().canPlaceBlocks();
  }

  @Override
  default boolean isPlaceable(ItemStack item) {
    return delegate().isPlaceable(item);
  }

  @Override
  default boolean isTool(ItemStack item) {
    return delegate().isTool(item);
  }

  @Override
  default boolean isOutOfLevel(BlockState blockState, SFVec3i pos) {
    return delegate().isOutOfLevel(blockState, pos);
  }

  @Override
  default boolean canBreakBlock(SFVec3i pos, BlockState blockState) {
    return delegate().canBreakBlock(pos, blockState);
  }

  @Override
  default boolean canPlaceBlock(SFVec3i pos) {
    return delegate().canPlaceBlock(pos);
  }

  @Override
  default boolean collidesWithAtEdge(DiagonalCollisionCalculator.CollisionData collisionData) {
    return delegate().collidesWithAtEdge(collisionData);
  }

  @Override
  default GraphInstructions modifyAsNeeded(GraphInstructions instruction) {
    return delegate().modifyAsNeeded(instruction);
  }

  @Override
  default double breakBlockPenalty() {
    return delegate().breakBlockPenalty();
  }

  @Override
  default double placeBlockPenalty() {
    return delegate().placeBlockPenalty();
  }

  @Override
  default int expireTimeout() {
    return delegate().expireTimeout();
  }

  @Override
  default boolean disablePruning() {
    return delegate().disablePruning();
  }

  @Override
  default double yawJitterMin() {
    return delegate().yawJitterMin();
  }

  @Override
  default double yawJitterMax() {
    return delegate().yawJitterMax();
  }

  @Override
  default double pitchJitterMin() {
    return delegate().pitchJitterMin();
  }

  @Override
  default double pitchJitterMax() {
    return delegate().pitchJitterMax();
  }

  PathConstraint delegate();
}
