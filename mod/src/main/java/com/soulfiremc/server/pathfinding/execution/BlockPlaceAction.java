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
import com.soulfiremc.server.pathfinding.BlockPlaceAgainstData;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

@Slf4j
@RequiredArgsConstructor
public final class BlockPlaceAction implements WorldAction {
  @Getter
  private final SFVec3i blockPosition;
  private final BlockPlaceAgainstData blockPlaceAgainstData;
  private boolean putOnHotbar;
  private boolean finishedPlacing;

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;

    return SFBlockHelpers.isCollisionShapeFullBlock(level.getBlockState(blockPosition.toBlockPos()));
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return SFVec3i.fromInt(connection.minecraft().player.blockPosition());
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;

    connection.controlState().resetAll();

    if (!putOnHotbar) {
      if (ItemPlaceHelper.placeBestBlockInHand(connection)) {
        putOnHotbar = true;
      }

      return;
    }

    if (finishedPlacing) {
      return;
    }

    var placeTarget = Vec3.atCenterOf(blockPlaceAgainstData.againstPos().toBlockPos()).add(
      blockPlaceAgainstData.blockFace().toDirection().getUnitVec3().multiply(0.5, 0.5, 0.5));
    connection.rotationControl().lookAt(placeTarget);
    if (!connection.rotationControl().isFacing(placeTarget)) {
      return;
    }

    var hand = InteractionHand.MAIN_HAND;
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
    }
    finishedPlacing = true;
  }

  @Override
  public int getAllowedTicks() {
    // 3-seconds max to place a block
    return 3 * 20;
  }

  @Override
  public String toString() {
    return "BlockPlaceAction -> " + blockPosition.formatXYZ();
  }
}
