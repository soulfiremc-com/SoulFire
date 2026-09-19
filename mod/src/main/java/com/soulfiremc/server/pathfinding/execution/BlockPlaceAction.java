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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

@Slf4j
@RequiredArgsConstructor
public final class BlockPlaceAction implements WorldAction {
  private static final int MAX_CONFIRMATION_TICKS = 40;
  private static final int MAX_INTERACTION_FAILURES = 4;
  private static final int INTERACTION_RETRY_TICKS = 4;
  @Getter
  private final SFVec3i blockPosition;
  private final BlockPlaceAgainstData blockPlaceAgainstData;
  private final PathConstraint pathConstraint;
  private ItemPlaceHelper.SelectedPlacementItem placementItem;
  private boolean finishedPlacing;
  private int interactionFailures;
  private int retryTicks;
  private int confirmationTicks;

  @Override
  public boolean isCompleted(BotConnection connection) {
    var level = connection.minecraft().level;
    var position = blockPosition.toBlockPos();
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
    return hasPlacementSupport(
      level.getBlockState(blockPosition.toBlockPos()),
      level.getBlockState(blockPlaceAgainstData.againstPos().toBlockPos())
    );
  }

  static boolean hasPlacementSupport(
    BlockState target,
    BlockState against
  ) {
    return SFBlockHelpers.isCollisionShapeFullBlock(target)
      || (
      target.canBeReplaced()
        && SFBlockHelpers.isCollisionShapeFullBlock(against)
    );
  }

  public boolean isRejected(BotConnection connection) {
    var position = blockPosition.toBlockPos();
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
    return SFVec3i.fromInt(connection.minecraft().player.blockPosition());
  }

  @Override
  public void tick(BotConnection connection) {
    var clientEntity = connection.minecraft().player;

    connection.controlState().resetAll();

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
      var position = blockPosition.toBlockPos();
      if (BlockPredictionSupport.hasPendingPrediction(connection, position)) {
        confirmationTicks++;
        return;
      }
      if (SFBlockHelpers.isCollisionShapeFullBlock(
        connection.minecraft().level.getBlockState(position)
      )) {
        return;
      }
      // The client accepted and predicted the interaction, but the server
      // rolled it back. Re-resolve player clearance and the clicked face
      // before retrying instead of failing the complete route immediately.
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

    connection.controlState().shift(true);
    var placement = BlockPlacementSupport.evaluate(
      connection,
      placementItem.hand(),
      blockPosition.toBlockPos(),
      blockPlaceAgainstData.againstPos().toBlockPos(),
      blockPlaceAgainstData.blockFace().toDirection()
    );
    if (placement.readiness()
      == BlockPlacementSupport.Readiness.PLAYER_INTERSECTION) {
      BlockPlacementSupport.moveToPlayerClearance(
        connection,
        blockPosition.toBlockPos()
      );
      return;
    }
    if ((placement.readiness()
      == BlockPlacementSupport.Readiness.FACE_OCCLUDED
      || placement.readiness()
      == BlockPlacementSupport.Readiness.OUT_OF_REACH)
      && BlockPlacementSupport.moveTowardPlacementView(
      connection,
      blockPosition.toBlockPos(),
      blockPlaceAgainstData.againstPos().toBlockPos(),
      blockPlaceAgainstData.blockFace().toDirection()
    )) {
      return;
    }
    if (!placement.ready()) {
      return;
    }

    var candidate = placement.candidate();
    var placeTarget = candidate.hitPosition();
    connection.rotationControl().lookAt(placeTarget);
    if (!connection.rotationControl().isFacing(placeTarget)) {
      return;
    }

    var hand = placementItem.hand();
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
      log.debug(
        "Minecraft refused block placement at {} using {}: {}",
        blockPosition,
        candidate.against(),
        interaction
      );
    }
  }

  @Override
  public int getAllowedTicks() {
    // Allow time to brake, clear the target cell, rotate, and confirm the
    // server result. A stalled action is still bounded by PathExecutor.
    return 5 * 20;
  }

  @Override
  public String toString() {
    return "BlockPlaceAction -> " + blockPosition.formatXYZ();
  }
}
