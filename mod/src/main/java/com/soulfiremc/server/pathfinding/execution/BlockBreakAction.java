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

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.cost.Costs;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.pathfinding.graph.actions.movement.MovementMiningCost;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

@Slf4j
@RequiredArgsConstructor
public final class BlockBreakAction implements WorldAction {
  @Getter
  private final SFVec3i blockPosition;
  private final BlockFace blockBreakSideHint;
  private boolean putInHand;
  private int remainingTicks = -1;
  private int totalTicks = -1;

  public BlockBreakAction(MovementMiningCost movementMiningCost) {
    this(movementMiningCost.block(), movementMiningCost.blockBreakSideHint());
  }

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;
    var blockType = level.getBlockState(blockPosition.toBlockPos()).getBlock();

    return SFBlockHelpers.isEmptyBlock(blockType);
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return SFVec3i.fromInt(connection.minecraft().player.blockPosition());
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    connection.controlState().resetAll();

    var level = connection.minecraft().level;
    var breakTarget = blockBreakSideHint.getMiddleOfFace(blockPosition);
    connection.rotationControl().lookAt(breakTarget);
    if (!connection.rotationControl().isFacing(breakTarget)) {
      return;
    }

    if (!putInHand) {
      if (ItemPlaceHelper.placeBestToolInHand(connection, blockPosition)) {
        putInHand = true;
      }

      return;
    }

    var optionalBlock = level.getBlockState(blockPosition.toBlockPos());
    if (optionalBlock.getBlock() == Blocks.VOID_AIR) {
      log.warn("Block at {} is not loaded!", blockPosition);
      return;
    }

    if (remainingTicks == -1) {
      remainingTicks = totalTicks =
        Costs.getRequiredMiningTicks(
            clientEntity,
            clientEntity.getInventory().getSelectedItem(),
            optionalBlock)
          .ticks();
    }

    if (connection.minecraft().gameMode.continueDestroyBlock(blockPosition.toBlockPos(), blockBreakSideHint.toDirection())) {
      connection.minecraft().player.swing(InteractionHand.MAIN_HAND);
    }
  }

  @Override
  public int getAllowedTicks() {
    return totalTicks == -1 ? 20 : totalTicks + 20;
  }

  @Override
  public String toString() {
    return "BlockBreakAction -> " + blockPosition.formatXYZ();
  }
}
