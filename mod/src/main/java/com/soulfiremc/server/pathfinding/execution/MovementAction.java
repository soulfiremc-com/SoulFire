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
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.MathHelper;
import com.soulfiremc.server.util.VectorHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

@Slf4j
@RequiredArgsConstructor
public final class MovementAction implements WorldAction {
  private static final Random rand = new Random();

  private static final double STEP_HEIGHT = 0.6;
  private final SFVec3i blockPosition;
  // Corner jumps normally require you to stand closer to the block to jump
  private final boolean walkFewTicksNoJump;
  private final PathConstraint settings;
  private boolean didLook;
  private boolean lockYRot;
  private boolean wasStill;
  private int noJumpTicks;

  public static double minXZ(AABB bb) {
    var x = bb.maxX - bb.minX;
    var z = bb.maxZ - bb.minZ;
    return Math.min(x, z);
  }

  @Override
  public boolean isCompleted(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    var botPosition = clientEntity.position();
    var level = connection.minecraft().level;

    var blockMeta = level.getBlockState(blockPosition.toBlockPos());
    var targetMiddleBlock = VectorHelper.topMiddleOfBlock(blockPosition, blockMeta);
    if (MathHelper.isOutsideTolerance(botPosition.y, targetMiddleBlock.y, 0.25)) {
      // We want to be on the same Y level
      return false;
    } else {
      return isAtTargetXZ(clientEntity, botPosition, targetMiddleBlock);
    }
  }

  private boolean isAtTargetXZ(LocalPlayer clientEntity, Vec3 botPosition, Vec3 targetMiddleBlock) {
    var halfDiagonal = minXZ(clientEntity.getBoundingBox()) / 2;

    // Leave more space to allow falling
    var adjustedHalfDiagonal = halfDiagonal - 0.1;
    return botPosition.distanceTo(targetMiddleBlock) < adjustedHalfDiagonal;
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

    var previousYRot = clientEntity.getYRot();
    clientEntity.lookAt(EntityAnchorArgument.Anchor.EYES, targetMiddleBlock);

    var xRotation = 0f;
    var yRotation = 0f;

    if (settings.yawJitterMin() < settings.yawJitterMax()) {
      yRotation = rand.nextFloat((float) settings.yawJitterMin(), (float) settings.yawJitterMax());
    }
    if (settings.pitchJitterMin() < settings.pitchJitterMax()) {
      xRotation = rand.nextFloat((float) settings.pitchJitterMin(), (float) settings.pitchJitterMax());
    }

    clientEntity.setYRot(clientEntity.getYRot() + yRotation);
    clientEntity.setXRot(xRotation);
    var newYRot = clientEntity.getYRot();

    var yRotDifference = Math.abs(MathHelper.wrapDegrees(newYRot - previousYRot));

    // We should only set the yRot once to the server to prevent the bot looking weird due to
    // inaccuracy
    if (!didLook) {
      didLook = true;
    } else if (yRotDifference > 5 || lockYRot) {
      lockYRot = true;
      clientEntity.yRotLast = newYRot;
    }

    var botPosition = clientEntity.position();
    var needsJump = targetMiddleBlock.y - STEP_HEIGHT > botPosition.y;
    if (needsJump) {
      // Make sure not to move if we have still other motion going on
      if (!wasStill) {
        var deltaMovementXZ = VectorHelper.toVector2dXZ(clientEntity.getDeltaMovement());
        var isBaseGravity = DoubleMath.fuzzyEquals(clientEntity.getDeltaMovement().y, -clientEntity.getGravity(), 0.1);
        var isStill = deltaMovementXZ.equals(0, 0);
        var isMovingRoughlyTowardsBlock = !deltaMovementXZ.equals(0, 0)
          && deltaMovementXZ.normalize().dot(VectorHelper.toVector2dXZ(targetMiddleBlock.subtract(clientEntity.position())).normalize()) > 0.8;
        if (isBaseGravity && (isStill || isMovingRoughlyTowardsBlock)) {
          wasStill = true;
        } else {
          return;
        }
      }

      if (shouldJump()) {
        connection.controlState().jump(true);
      }
    }

    if (!isAtTargetXZ(clientEntity, botPosition, targetMiddleBlock)) {
      connection.controlState().up(true);
    }
  }

  private boolean shouldJump() {
    if (!walkFewTicksNoJump) {
      return true;
    }

    if (noJumpTicks < 3) {
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
    return "MovementAction -> " + blockPosition.formatXYZ();
  }
}
