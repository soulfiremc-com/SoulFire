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
import com.soulfiremc.server.util.SFBlockHelpers;
import com.soulfiremc.server.util.VectorHelper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;


@Slf4j
@RequiredArgsConstructor
public final class MovementAction implements WorldAction {
  private static final double STEP_HEIGHT = 0.6;
  private static final double TARGET_HEIGHT_TOLERANCE = 0.25;
  // Ground friction takes walking speed below this in a few ticks
  private static final double SETTLED_SPEED = 0.03;
  @Getter
  private final SFVec3i blockPosition;
  // Corner jumps normally require you to stand closer to the block to jump
  private final boolean walkFewTicksNoJump;
  private final PathConstraint pathConstraint;
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
    var targetMiddleBlock = VectorHelper.standingMiddleOfBlock(blockPosition, blockMeta);
    if (!hasReachedTargetHeight(
      botPosition.y,
      targetMiddleBlock.y,
      clientEntity.onGround()
        || clientEntity.isInWater()
        || clientEntity.isInLava()
        || clientEntity.onClimbable()
    )) {
      // We want to be on the same Y level
      return false;
    } else {
      return isAtTargetXZ(clientEntity, botPosition, targetMiddleBlock)
        // Leaving a block while still sliding, off its centre, can carry the player into lava or fire around it.
        // Not in water, where a current may never let it settle.
        && (!clientEntity.onGround()
          || clientEntity.isInWater()
          || isSettled(clientEntity.getDeltaMovement())
          || !SFBlockHelpers.isNearLavaOrFire(level, blockPosition.toBlockPos()));
    }
  }

  private static boolean isSettled(Vec3 deltaMovement) {
    return deltaMovement.horizontalDistance() < SETTLED_SPEED;
  }

  @Override
  public boolean isValid(BotConnection connection) {
    return hasValidTarget(connection, blockPosition);
  }

  static boolean hasValidTarget(
    BotConnection connection,
    SFVec3i target
  ) {
    var level = connection.minecraft().level;
    var feet = level.getBlockState(target.toBlockPos());
    var head = level.getBlockState(target.add(0, 1, 0).toBlockPos());
    var floorPosition = target.sub(0, 1, 0).toBlockPos();
    var floor = level.getBlockState(floorPosition);
    return hasValidTargetStates(
      feet,
      head,
      floor,
      SFBlockHelpers.isStableWalkableFloorBlock(
        level,
        floorPosition,
        floor
      )
    );
  }

  static boolean hasValidTargetStates(
    BlockState feet,
    BlockState head,
    BlockState floor
  ) {
    return hasValidTargetStates(
      feet,
      head,
      floor,
      SFBlockHelpers.isWalkableFloorBlock(floor)
    );
  }

  static boolean hasValidTargetStates(
    BlockState feet,
    BlockState head,
    BlockState floor,
    boolean stableWalkableFloor
  ) {
    var supportInsideFeet = SFBlockHelpers.canSupportFeetInBlock(feet);
    var feetInSwimmableWater = SFBlockHelpers.isSwimmableWaterBlock(feet);
    var headPassable = SFBlockHelpers.isBodyPassableBlock(head)
      || SFBlockHelpers.isSwimmableWaterBlock(head);
    var climbable = feet.is(net.minecraft.tags.BlockTags.CLIMBABLE)
      || feet.is(net.minecraft.world.level.block.Blocks.LADDER)
      || feet.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
    var feetPassable = SFBlockHelpers.isBodyPassableBlock(feet)
      || supportInsideFeet
      || feetInSwimmableWater
      || climbable;
    if (!feetPassable || !headPassable) {
      return false;
    }
    return stableWalkableFloor
      || supportInsideFeet
      || feetInSwimmableWater
      || climbable;
  }

  static boolean hasReachedTargetHeight(
    double currentY,
    double targetY,
    boolean verticallySupported
  ) {
    return verticallySupported
      && !MathHelper.isOutsideTolerance(currentY, targetY, TARGET_HEIGHT_TOLERANCE);
  }

  static boolean needsUpwardInput(
    double currentY,
    double targetY,
    boolean movingInFluid
  ) {
    return movingInFluid
      // Keep swimming while crossing a fluid block at the target height.
      // Releasing jump as soon as the player enters the vertical completion
      // tolerance lets shallow-water movement sink against the next block
      // edge and repeatedly stall. If the player is above the tolerance,
      // releasing jump still lets gravity bring them back down.
      ? currentY <= targetY + TARGET_HEIGHT_TOLERANCE
      : currentY < targetY - STEP_HEIGHT;
  }

  private boolean isAtTargetXZ(LocalPlayer clientEntity, Vec3 botPosition, Vec3 targetMiddleBlock) {
    var halfDiagonal = minXZ(clientEntity.getBoundingBox()) / 2;

    // Leave more space to allow falling
    var adjustedHalfDiagonal = halfDiagonal - 0.1;
    return horizontalDistance(botPosition, targetMiddleBlock) < adjustedHalfDiagonal;
  }

  static double horizontalDistance(Vec3 currentPosition, Vec3 targetPosition) {
    return Math.hypot(
      targetPosition.x - currentPosition.x,
      targetPosition.z - currentPosition.z
    );
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
    var targetMiddleBlock = VectorHelper.standingMiddleOfBlock(blockPosition, blockMeta);

    if (pathConstraint.smoothCamera()) {
      connection.rotationControl().lookHorizontallyAtSmoothly(
        targetMiddleBlock,
        8,
        3
      );
    } else {
      connection.rotationControl().lookHorizontallyAt(targetMiddleBlock, 0, 0);
    }

    var botPosition = clientEntity.position();
    var movingInFluid = clientEntity.isInWater() || clientEntity.isInLava();
    // A climbing player slides down unless it climbs (jumps) back up to the target
    var climbing = clientEntity.onClimbable();
    var needsJump = needsUpwardInput(
      botPosition.y,
      targetMiddleBlock.y,
      movingInFluid
    ) || (climbing && botPosition.y < targetMiddleBlock.y);
    if (needsJump) {
      // Fluid movement never reaches the normal grounded-gravity state. Waiting
      // for it here leaves the bot submerged and unable to swim up one block.
      // Neither does a climbing player.
      if (!movingInFluid && !climbing && !wasStill) {
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

      if (movingInFluid || climbing || shouldJump()) {
        connection.controlState().jump(true);
      }
    }

    if (!isAtTargetXZ(clientEntity, botPosition, targetMiddleBlock)) {
      var movementInput = movementInputFor(
        botPosition,
        clientEntity.getYRot(),
        targetMiddleBlock
      );
      var controlState = connection.controlState();
      controlState.up(movementInput.forward());
      controlState.down(movementInput.backward());
      controlState.left(movementInput.left());
      controlState.right(movementInput.right());
      // Sprint-swimming lowers the player pose enough to leave submerged,
      // low-ceiling spaces. On land this also lets ordinary path segments run
      // instead of walking whenever the player can sprint.
      controlState.sprint(pathConstraint.sprint() && movementInput.forward());
    }
  }

  static MovementInput movementInputFor(
    Vec3 currentPosition,
    float currentYaw,
    Vec3 targetPosition
  ) {
    var differenceX = targetPosition.x - currentPosition.x;
    var differenceZ = targetPosition.z - currentPosition.z;
    var horizontalDistance = horizontalDistance(currentPosition, targetPosition);
    if (horizontalDistance < 1.0E-6) {
      return MovementInput.NONE;
    }

    var targetYaw = (float) (Mth.atan2(differenceZ, differenceX) * Mth.RAD_TO_DEG) - 90.0F;
    var relativeYaw = Mth.wrapDegrees(targetYaw - currentYaw);
    var direction = Math.floorMod(Math.round(relativeYaw / 45.0F), 8);
    return switch (direction) {
      case 0 -> new MovementInput(true, false, false, false, horizontalDistance);
      case 1 -> new MovementInput(true, false, false, true, horizontalDistance);
      case 2 -> new MovementInput(false, false, false, true, horizontalDistance);
      case 3 -> new MovementInput(false, true, false, true, horizontalDistance);
      case 4 -> new MovementInput(false, true, false, false, horizontalDistance);
      case 5 -> new MovementInput(false, true, true, false, horizontalDistance);
      case 6 -> new MovementInput(false, false, true, false, horizontalDistance);
      case 7 -> new MovementInput(true, false, true, false, horizontalDistance);
      default -> throw new IllegalStateException("Unexpected movement direction: " + direction);
    };
  }

  boolean shouldJump() {
    if (!walkFewTicksNoJump) {
      return true;
    }

    if (noJumpTicks < 3) {
      noJumpTicks++;
      return false;
    }

    return true;
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

  record MovementInput(
    boolean forward,
    boolean backward,
    boolean left,
    boolean right,
    double horizontalDistance
  ) {
    private static final MovementInput NONE =
      new MovementInput(false, false, false, false, 0.0);
  }
}
