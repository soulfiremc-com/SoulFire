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
import com.soulfiremc.server.util.MathHelper;
import com.soulfiremc.server.util.VectorHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class GapJumpAction implements WorldAction {
  private final SFVec3i blockPosition;
  private final int gapLength;
  private int noJumpTicks;

  @Override
  public boolean isCompleted(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    var botPosition = clientEntity.position();
    var level = connection.minecraft().level;

    var blockMeta = level.getBlockState(blockPosition.toBlockPos());
    var targetMiddleBlock = VectorHelper.topMiddleOfBlock(blockPosition, blockMeta);
    if (MathHelper.isOutsideTolerance(botPosition.y, targetMiddleBlock.y, 0.2)) {
      // We want to be on the same Y level
      return false;
    } else {
      var distance = botPosition.distanceTo(targetMiddleBlock);
      return distance <= 0.3;
    }
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return blockPosition;
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    connection.controlState().resetAll();

    var level = connection.minecraft().level;

    var blockMeta = level.getBlockState(blockPosition.toBlockPos());
    var targetMiddleBlock = VectorHelper.topMiddleOfBlock(blockPosition, blockMeta);

    connection.rotationControl().lookHorizontallyAt(targetMiddleBlock);

    connection.controlState().sprint(true);
    connection.controlState().up(true);

    if (shouldJump()) {
      connection.controlState().jump(true);
    }
  }

  private boolean shouldJump() {
    if (noJumpTicks < gapLength) {
      noJumpTicks++;
      return false;
    } else {
      noJumpTicks = 0;
      return true;
    }
  }

  @Override
  public int getAllowedTicks() {
    // 5-seconds max to walk to a block
    return 5 * 20;
  }

  @Override
  public String toString() {
    return "GapJumpAction -> " + blockPosition.formatXYZ();
  }
}
