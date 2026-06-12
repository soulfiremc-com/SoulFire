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

import com.google.common.math.DoubleMath;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.pathfinding.BlockPlaceAgainstData;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;

@Slf4j
@RequiredArgsConstructor
public final class JumpAndPlaceBelowAction implements WorldAction {
  private final SFVec3i blockPlacePosition;
  private final BlockPlaceAgainstData blockPlaceAgainstData;
  private boolean putOnHotbar;
  private boolean finishedPlacing;

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;

    return SFBlockHelpers.isCollisionShapeFullBlock(level.getBlockState(blockPlacePosition.toBlockPos()));
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return blockPlacePosition.add(0, 1, 0);
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    connection.controlState().resetAll();

    var placeTarget = blockPlaceAgainstData.againstPos().toBlockPos().getCenter().add(
      blockPlaceAgainstData.blockFace().toDirection().getUnitVec3().multiply(0.5, 0.5, 0.5));
    connection.rotationControl().lookAt(placeTarget);

    if (!putOnHotbar) {
      if (ItemPlaceHelper.placeBestBlockInHand(connection)) {
        putOnHotbar = true;
      }

      return;
    }

    if (finishedPlacing) {
      return;
    }

    var deltaMovement = clientEntity.getDeltaMovement();
    if (clientEntity.getY() < blockPlacePosition.y + 1
      // Ensure we're roughly standing still
      && DoubleMath.fuzzyEquals(deltaMovement.y, -clientEntity.getGravity(), 0.1)
      // No X movement
      && deltaMovement.x == 0
      // No Z movement
      && deltaMovement.z == 0) {
      // Make sure we are so high that we can place the block
      connection.controlState().jump(true);
      return;
    } else {
      connection.controlState().jump(false);
    }

    var hand = InteractionHand.MAIN_HAND;
    if (!connection.rotationControl().isFacing(placeTarget)) {
      return;
    }

    if (connection.minecraft().gameMode.useItemOn(clientEntity, hand, clientEntity.level().clipIncludingBorder(new ClipContext(
      clientEntity.getEyePosition(),
      placeTarget,
      ClipContext.Block.COLLIDER,
      ClipContext.Fluid.NONE,
      clientEntity
    ))) instanceof InteractionResult.Success success) {
      if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
        clientEntity.swing(hand);
      }

      finishedPlacing = true;
    }
  }

  @Override
  public int getAllowedTicks() {
    // 3-seconds max to place a block
    return 3 * 20;
  }

  @Override
  public String toString() {
    return "JumpAndPlaceBelowAction -> " + blockPlacePosition.add(0, 1, 0).formatXYZ();
  }
}
