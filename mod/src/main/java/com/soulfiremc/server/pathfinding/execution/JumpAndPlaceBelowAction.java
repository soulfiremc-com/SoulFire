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
import com.soulfiremc.server.bot.BotInteractionSupport;
import com.soulfiremc.server.pathfinding.BlockPlaceAgainstData;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

@RequiredArgsConstructor
public final class JumpAndPlaceBelowAction implements WorldAction {
  private static final int MAX_CONFIRMATION_TICKS = 40;
  private static final int MAX_INTERACTION_FAILURES = 4;
  private static final int INTERACTION_RETRY_TICKS = 4;
  private static final double PLACEMENT_CLEARANCE = 1.01;
  private static final double MAX_HORIZONTAL_DRIFT = 0.04;
  private static final double MAX_HORIZONTAL_OFFSET = 0.15;
  private final SFVec3i blockPlacePosition;
  private final BlockPlaceAgainstData blockPlaceAgainstData;
  private final PathConstraint pathConstraint;
  private ItemPlaceHelper.SelectedPlacementItem placementItem;
  private boolean finishedPlacing;
  private int interactionFailures;
  private int retryTicks;
  private int confirmationTicks;

  public SFVec3i blockPlacePosition() {
    return blockPlacePosition;
  }

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;
    var position = blockPlacePosition.toBlockPos();
    if (
      !SFBlockHelpers.isCollisionShapeFullBlock(
        level.getBlockState(position)
      )
    ) {
      return false;
    }
    return !finishedPlacing
      || !BlockPredictionSupport.hasPendingPrediction(
      connection,
      position
    );
  }

  @Override
  public boolean isValid(BotConnection connection) {
    var level = connection.minecraft().level;
    return BlockPlaceAction.hasPlacementSupport(
      level.getBlockState(blockPlacePosition.toBlockPos()),
      level.getBlockState(blockPlaceAgainstData.againstPos().toBlockPos())
    );
  }

  public boolean isRejected(BotConnection connection) {
    var position = blockPlacePosition.toBlockPos();
    return placementWasRejected(
      interactionFailures,
      BlockPredictionSupport.hasPendingPrediction(connection, position),
      confirmationTicks
    );
  }

  static boolean placementWasRejected(
    int interactionFailures,
    boolean pendingPrediction,
    int confirmationTicks
  ) {
    return interactionFailures >= MAX_INTERACTION_FAILURES
      || pendingPrediction
      && confirmationTicks >= MAX_CONFIRMATION_TICKS;
  }

  @Override
  public SFVec3i targetPosition(BotConnection connection) {
    return blockPlacePosition.add(0, 1, 0);
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;
    var movingInFluid = clientEntity.isInWater() || clientEntity.isInLava();
    connection.controlState().resetAll();

    var placementCenter = new Vec3(
      blockPlacePosition.x + 0.5D,
      clientEntity.getY(),
      blockPlacePosition.z + 0.5D
    );
    if (needsHorizontalCentering(
      clientEntity.position(),
      placementCenter
    )) {
      connection.rotationControl().lookHorizontallyAt(placementCenter);
      var movementInput = MovementAction.movementInputFor(
        clientEntity.position(),
        clientEntity.getYRot(),
        placementCenter
      );
      var controlState = connection.controlState();
      controlState.up(movementInput.forward());
      controlState.down(movementInput.backward());
      controlState.left(movementInput.left());
      controlState.right(movementInput.right());
      return;
    }

    if (placementItem == null) {
      placementItem = ItemPlaceHelper.placeBestBlockInHand(
        connection,
        pathConstraint
      ).orElse(null);
      return;
    }

    if (!placementItem.isReady(clientEntity, pathConstraint)) {
      placementItem = null;
      return;
    }

    if (finishedPlacing) {
      var position = blockPlacePosition.toBlockPos();
      if (BlockPredictionSupport.hasPendingPrediction(connection, position)) {
        confirmationTicks++;
        return;
      }
      if (SFBlockHelpers.isCollisionShapeFullBlock(
        connection.minecraft().level.getBlockState(position)
      )) {
        return;
      }
      finishedPlacing = false;
      confirmationTicks = 0;
      interactionFailures++;
      retryTicks = INTERACTION_RETRY_TICKS;
      return;
    }

    if (retryTicks > 0) {
      retryTicks--;
      return;
    }

    var deltaMovement = clientEntity.getDeltaMovement();
    if (!isHorizontallyStable(deltaMovement)) {
      return;
    }

    var preferredTarget = Vec3.atCenterOf(
      blockPlaceAgainstData.againstPos().toBlockPos()
    ).add(
      blockPlaceAgainstData.blockFace().toDirection().getUnitVec3()
        .multiply(0.5, 0.5, 0.5)
    );
    if (needsAdditionalJumpHeight(clientEntity.getY(), blockPlacePosition.y)) {
      // Keep jumping until the player's collision box no longer intersects
      // the block being placed. Releasing jump on the first airborne tick
      // sends use-item-on while the player still occupies the target cell,
      // which authoritative servers reject.
      if (shouldKeepViewHorizontalWhileAscending(movingInFluid)) {
        // A swimming player follows their pitch, so aiming at the block below
        // while ascending can pin them in flowing water and prevent the pillar
        // jump from ever starting.
        connection.rotationControl().lookHorizontallyAt(placementCenter);
      } else {
        // Pre-aim during a normal jump. Waiting until the player clears the
        // placement cell leaves too little airtime to rotate down and place.
        connection.rotationControl().lookAt(preferredTarget);
      }
      connection.controlState().jump(true);
      return;
    }
    connection.controlState().jump(false);

    var placement = BlockPlacementSupport.evaluate(
      connection,
      placementItem.hand(),
      blockPlacePosition.toBlockPos(),
      blockPlaceAgainstData.againstPos().toBlockPos(),
      blockPlaceAgainstData.blockFace().toDirection()
    );
    if (placement.readiness()
      == BlockPlacementSupport.Readiness.PLAYER_INTERSECTION) {
      connection.controlState().jump(true);
      return;
    }
    if (!placement.ready()) {
      return;
    }

    var candidate = placement.candidate();
    var placeTarget = candidate.hitPosition();
    connection.rotationControl().lookAt(placeTarget);
    var hand = placementItem.hand();
    if (!connection.rotationControl().isFacing(placeTarget)) {
      return;
    }

    var swingAnimation = clientEntity.getItemInHand(hand).getInteractAnimation();
    var interaction = BotInteractionSupport.withSneaking(
      clientEntity,
      true,
      () -> connection.minecraft().gameMode.useItemOn(
        clientEntity,
        hand,
        candidate.hitResult()
      )
    );
    if (interaction instanceof InteractionResult.Success success) {
      if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
        clientEntity.swing(hand, swingAnimation, false);
      }

      finishedPlacing = true;
    } else {
      interactionFailures++;
      retryTicks = INTERACTION_RETRY_TICKS;
    }
  }

  static boolean isHorizontallyStable(Vec3 deltaMovement) {
    return Math.hypot(
      deltaMovement.x,
      deltaMovement.z
    ) < MAX_HORIZONTAL_DRIFT;
  }

  static boolean needsHorizontalCentering(
    Vec3 playerPosition,
    Vec3 placementCenter
  ) {
    return Math.hypot(
      placementCenter.x - playerPosition.x,
      placementCenter.z - playerPosition.z
    ) > MAX_HORIZONTAL_OFFSET;
  }

  static boolean needsAdditionalJumpHeight(double playerY, int placementY) {
    return playerY < placementY + PLACEMENT_CLEARANCE;
  }

  static boolean shouldKeepViewHorizontalWhileAscending(boolean movingInFluid) {
    return movingInFluid;
  }

  @Override
  public int getAllowedTicks() {
    return 5 * 20;
  }

  @Override
  public String toString() {
    return "JumpAndPlaceBelowAction -> " + blockPlacePosition.add(0, 1, 0).formatXYZ();
  }
}
