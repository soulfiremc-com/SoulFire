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

import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.GapJumpAction;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.OptionalInt;

public record AvoidFluidConstraint(
  PathConstraint delegate,
  OptionalInt submergedStartY
) implements DelegatePathConstraint {
  public static AvoidFluidConstraint forPlayer(
    PathConstraint delegate,
    @Nullable LocalPlayer player
  ) {
    var submergedStartY = player != null
      && (player.isInWater() || player.isInLava())
      ? OptionalInt.of(player.blockPosition().getY())
      : OptionalInt.empty();
    return new AvoidFluidConstraint(delegate, submergedStartY);
  }

  @Override
  public boolean allowsInstruction(
    GraphInstructions instruction,
    BlockGetter blockAccessor
  ) {
    if (!delegate.allowsInstruction(instruction, blockAccessor)) {
      return false;
    }
    if (instruction.actions().stream().anyMatch(GapJumpAction.class::isInstance)) {
      return false;
    }
    return isDryDestination(blockAccessor, instruction)
      || isAscendingFluidEscape(blockAccessor, instruction, submergedStartY);
  }

  static boolean isDryDestination(
    BlockGetter level,
    GraphInstructions instruction
  ) {
    var feet = instruction.blockPosition().toBlockPos();
    return remainsDryAfterInstruction(level, instruction, feet)
      && remainsDryAfterInstruction(level, instruction, feet.above());
  }

  private static boolean remainsDryAfterInstruction(
    BlockGetter level,
    GraphInstructions instruction,
    BlockPos position
  ) {
    if (!level.getFluidState(position).isEmpty()) {
      return false;
    }
    var breaksPosition = instruction.actions().stream()
      .filter(BlockBreakAction.class::isInstance)
      .map(BlockBreakAction.class::cast)
      .anyMatch(action ->
        action.blockPosition().toBlockPos().equals(position)
      );
    if (!breaksPosition) {
      return true;
    }
    return level.getFluidState(position.above()).isEmpty()
      && level.getFluidState(position.north()).isEmpty()
      && level.getFluidState(position.south()).isEmpty()
      && level.getFluidState(position.east()).isEmpty()
      && level.getFluidState(position.west()).isEmpty();
  }

  /// Swimming up out of still water is allowed. Falling water is not: a
  /// live bot in a pool at the foot of a waterfall kept routing up the
  /// waterfall, ran low on air partway and dropped back into the pool.
  static boolean isAscendingFluidEscape(
    BlockGetter level,
    GraphInstructions instruction,
    OptionalInt submergedStartY
  ) {
    var feet = instruction.blockPosition().toBlockPos();
    return submergedStartY.isPresent()
      && instruction.blockPosition().y > submergedStartY.getAsInt()
      && !isFallingFluid(level.getFluidState(feet))
      && !isFallingFluid(level.getFluidState(feet.above()));
  }

  private static boolean isFallingFluid(FluidState fluid) {
    return !fluid.isEmpty()
      && fluid.hasProperty(FlowingFluid.FALLING)
      && fluid.getValue(FlowingFluid.FALLING);
  }
}
