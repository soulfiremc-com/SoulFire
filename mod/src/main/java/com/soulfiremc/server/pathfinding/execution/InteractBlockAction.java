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
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;

@Slf4j
@RequiredArgsConstructor
public final class InteractBlockAction implements WorldAction {
  private final SFVec3i blockPosition;
  private final BlockFace interactFace;
  private final boolean desiredOpen;

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;
    return SFBlockHelpers.isPassageBlockOpen(level.getBlockState(blockPosition.toBlockPos())) == desiredOpen;
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return SFVec3i.fromInt(connection.minecraft().player.blockPosition());
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    connection.controlState().resetAll();

    var interactTarget = interactFace.getMiddleOfFace(blockPosition);
    connection.rotationControl().lookAt(interactTarget);
    if (!connection.rotationControl().isFacing(interactTarget)) {
      return;
    }

    var hand = InteractionHand.MAIN_HAND;
    if (connection.minecraft().gameMode.useItemOn(clientEntity, hand, clientEntity.level().clipIncludingBorder(new ClipContext(
      clientEntity.getEyePosition(),
      interactTarget,
      ClipContext.Block.COLLIDER,
      ClipContext.Fluid.NONE,
      clientEntity
    ))) instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
      clientEntity.swing(hand);
    }
  }

  @Override
  public int getAllowedTicks() {
    return 20;
  }

  @Override
  public String toString() {
    return "%s passage block -> %s".formatted(desiredOpen ? "Open" : "Close", blockPosition.formatXYZ());
  }
}
