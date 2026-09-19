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

import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.cost.Costs;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.pathfinding.graph.actions.movement.MovementMiningCost;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

@Slf4j
public final class BlockBreakAction implements WorldAction {
  private static final int MAXIMUM_BLOCK_REPLACEMENT_RETRIES = 16;
  private static final int CLEARED_STATE_SETTLE_TICKS = 4;

  @Getter
  private final SFVec3i blockPosition;
  private final BlockFace blockBreakSideHint;
  private boolean putInHand;
  private boolean breakAttempted;
  private boolean predictedBroken;
  private BlockState attemptedState;
  private int totalTicks = -1;
  private int allowedTicks;
  private int blockReplacementRetries;
  private int clearedStateTicks;
  private double fluidAnchorY = Double.NaN;

  public BlockBreakAction(
    SFVec3i blockPosition,
    BlockFace blockBreakSideHint
  ) {
    this.blockPosition = blockPosition;
    this.blockBreakSideHint = blockBreakSideHint;
  }

  public BlockBreakAction(MovementMiningCost movementMiningCost) {
    this(
      movementMiningCost.block(),
      movementMiningCost.blockBreakSideHint()
    );
  }

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;
    var position = blockPosition.toBlockPos();
    var state = level.getBlockState(position);
    if (!BlockPredictionSupport.isClearedBreakTarget(state)) {
      clearedStateTicks = 0;
      return false;
    }
    if (!breakAttempted) {
      return true;
    }

    var pendingPrediction = BlockPredictionSupport.hasPendingPrediction(
      connection,
      position
    );
    predictedBroken |= pendingPrediction;
    if (pendingPrediction) {
      clearedStateTicks = 0;
      return false;
    }
    return ++clearedStateTicks >= CLEARED_STATE_SETTLE_TICKS;
  }

  public boolean isRejected(BotConnection connection) {
    var position = blockPosition.toBlockPos();
    var reconciliation = BlockPredictionSupport.reconcileBreak(
      connection.minecraft().level.getBlockState(position),
      attemptedState,
      breakAttempted,
      predictedBroken,
      BlockPredictionSupport.hasPendingPrediction(connection, position),
      blockReplacementRetries,
      MAXIMUM_BLOCK_REPLACEMENT_RETRIES
    );
    if (
      reconciliation
        == BlockPredictionSupport.BreakReconciliation.RETRY_REPLACEMENT
    ) {
      breakAttempted = false;
      predictedBroken = false;
      putInHand = false;
      attemptedState = null;
      totalTicks = -1;
      clearedStateTicks = 0;
      blockReplacementRetries++;
      return false;
    }
    return reconciliation
      == BlockPredictionSupport.BreakReconciliation.REJECTED;
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return SFVec3i.fromInt(connection.minecraft().player.blockPosition());
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    connection.controlState().resetAll();
    var movingInFluid = clientEntity.isInWater() || clientEntity.isInLava();
    if (movingInFluid) {
      if (Double.isNaN(fluidAnchorY)) {
        fluidAnchorY = clientEntity.getY();
      }
      if (shouldMaintainFluidHeight(
        movingInFluid,
        clientEntity.getY(),
        fluidAnchorY
      )) {
        connection.controlState().jump(true);
      }
    } else {
      fluidAnchorY = Double.NaN;
    }

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
    if (BlockPredictionSupport.isClearedBreakTarget(optionalBlock)) {
      predictedBroken |= BlockPredictionSupport.hasPendingPrediction(
        connection,
        blockPosition.toBlockPos()
      );
      return;
    }

    if (totalTicks == -1) {
      totalTicks = Costs.getRequiredMiningTicks(
          clientEntity,
          clientEntity.getInventory().getSelectedItem(),
          optionalBlock)
        .ticks();
      allowedTicks += totalTicks + 20;
    }

    var gameMode = connection.minecraft().gameMode;
    var target = blockPosition.toBlockPos();
    var direction = blockBreakSideHint.toDirection();
    if (!breakAttempted) {
      if (gameMode.startDestroyBlock(target, direction)) {
        breakAttempted = true;
        attemptedState = optionalBlock;
        clientEntity.swing(InteractionHand.MAIN_HAND, clientEntity.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
        clientEntity.connection.send(ServerboundPunchPacket.INSTANCE);
      }
      return;
    }

    if (gameMode.continueDestroyBlock(target, direction)) {
      predictedBroken |= BlockPredictionSupport.isClearedBreakTarget(
        level.getBlockState(target)
      );
      clientEntity.swing(InteractionHand.MAIN_HAND, clientEntity.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
      clientEntity.connection.send(ServerboundPunchPacket.INSTANCE);
    }
  }

  public boolean breakAttempted() {
    return breakAttempted;
  }

  static boolean shouldMaintainFluidHeight(
    boolean movingInFluid,
    double currentY,
    double anchorY
  ) {
    return movingInFluid && currentY <= anchorY;
  }

  @Override
  public int getAllowedTicks() {
    return Math.max(20, allowedTicks);
  }

  @Override
  public String toString() {
    return "BlockBreakAction -> " + blockPosition.formatXYZ();
  }
}
