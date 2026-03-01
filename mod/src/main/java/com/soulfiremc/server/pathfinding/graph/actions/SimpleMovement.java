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
package com.soulfiremc.server.pathfinding.graph.actions;

import com.soulfiremc.server.pathfinding.BlockPlaceAgainstData;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.cost.Costs;
import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.BlockPlaceAction;
import com.soulfiremc.server.pathfinding.execution.InteractBlockAction;
import com.soulfiremc.server.pathfinding.execution.MovementAction;
import com.soulfiremc.server.pathfinding.execution.WorldAction;
import com.soulfiremc.server.pathfinding.graph.BlockFace;
import com.soulfiremc.server.pathfinding.graph.DiagonalCollisionCalculator;
import com.soulfiremc.server.pathfinding.graph.GraphInstructions;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;
import com.soulfiremc.server.pathfinding.graph.actions.movement.*;
import com.soulfiremc.server.util.SFBlockHelpers;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.level.block.state.BlockState;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public final class SimpleMovement extends GraphAction implements Cloneable {
  private static final SFVec3i FEET_POSITION_RELATIVE_BLOCK = SFVec3i.ZERO;
  private final MovementDirection direction;
  private final MovementModifier modifier;
  private final SFVec3i targetFeetBlock;
  private final boolean diagonal;
  private final boolean allowBlockActions;
  private final boolean allowInteractablePassage;
  // Mutable
  private MovementMiningCost[] blockBreakCosts;
  // Mutable
  private boolean[] unsafeToBreak;
  // Mutable
  private boolean[] noNeedToBreak;
  // Mutable
  private MovementSide blockedSide;
  // Mutable
  private BlockPlaceAgainstData blockPlaceAgainstData;
  // Mutable
  private double cost;
  // Mutable
  private boolean floorHasSupport;
  // Mutable
  private boolean floorCanBePlaced;
  // Mutable
  private boolean supportInTargetBlock;
  // Mutable
  private InteractablePassage interactablePassage;

  private SimpleMovement(MovementDirection direction, MovementModifier modifier, SubscriptionConsumer blockSubscribers) {
    super(modifier == MovementModifier.NORMAL ? direction.actionDirection() : ActionDirection.SPECIAL);
    this.direction = direction;
    this.modifier = modifier;
    this.diagonal = direction.isDiagonal();

    this.cost =
      (diagonal ? Costs.DIAGONAL : Costs.STRAIGHT)
        // Add modifier costs
        // Jump up block gets a tiny bit extra (you can move midair)
        // But that's fine since we also want to slightly discourage jumping up
        + switch (modifier) {
        case NORMAL -> 0;
        case FALL_1 -> Costs.FALL_1;
        case FALL_2 -> Costs.FALL_2;
        case FALL_3 -> Costs.FALL_3;
        case JUMP_UP_BLOCK -> Costs.JUMP_UP_BLOCK;
      };

    this.targetFeetBlock = modifier.offset(direction.offset(FEET_POSITION_RELATIVE_BLOCK));
    this.allowBlockActions =
      !diagonal && switch (modifier) {
        case JUMP_UP_BLOCK, NORMAL, FALL_1 -> true;
        case FALL_2, FALL_3 -> false;
      };
    this.allowInteractablePassage = !diagonal && modifier == MovementModifier.NORMAL;

    var arraySize = registerRequiredFreeBlocks(blockSubscribers);
    if (allowBlockActions) {
      blockBreakCosts = new MovementMiningCost[arraySize];
      unsafeToBreak = new boolean[arraySize];
      noNeedToBreak = new boolean[arraySize];
    } else {
      blockBreakCosts = null;
      unsafeToBreak = null;
      noNeedToBreak = null;
    }

    this.registerRequiredSolidBlock(blockSubscribers);
    this.registerDiagonalCollisionBlocks(blockSubscribers);
    this.registerPossibleBlocksToPlaceAgainst(blockSubscribers);
  }

  public static void registerMovements(Consumer<GraphAction> callback, SubscriptionConsumer blockSubscribers) {
    for (var direction : MovementDirection.VALUES) {
      for (var modifier : MovementModifier.VALUES) {
        callback.accept(new SimpleMovement(direction, modifier, blockSubscribers));
      }
    }
  }

  private int registerRequiredFreeBlocks(SubscriptionConsumer blockSubscribers) {
    var blockIndexCounter = 0;

    if (modifier == MovementModifier.JUMP_UP_BLOCK) {
      var aboveHeadBlockIndex = blockIndexCounter++;
      var aboveHead = FEET_POSITION_RELATIVE_BLOCK.add(0, 2, 0);

      // Make block above the head block free for jump
      blockSubscribers.subscribe(aboveHead,
        new MovementFreeSubscription(aboveHeadBlockIndex, BlockFace.BOTTOM, BodyPart.HEAD, false, null));

      if (allowBlockActions) {
        blockSubscribers.subscribe(aboveHead.add(0, 1, 0),
          new MovementBreakSafetyCheckSubscription(aboveHeadBlockIndex, BlockSafetyType.FALLING_AND_FLUIDS));

        for (var skyDirection : SkyDirection.VALUES) {
          blockSubscribers.subscribe(skyDirection.offset(aboveHead),
            new MovementBreakSafetyCheckSubscription(aboveHeadBlockIndex, BlockSafetyType.FLUIDS));
        }
      }
    }

    var targetEdge = direction.offset(FEET_POSITION_RELATIVE_BLOCK);
    for (var bodyOffset : BodyPart.VALUES) {
      BlockFace blockBreakSideHint;
      BlockFace interactionSideHint;
      if (diagonal) {
        blockBreakSideHint = null; // We don't mine blocks in diagonals
        interactionSideHint = null;
      } else {
        blockBreakSideHint = direction.toSkyDirection().blockFace();
        interactionSideHint = direction.toSkyDirection().opposite().blockFace();
      }

      var blockIndex = blockIndexCounter++;

      // Apply jump shift to target diagonal and offset for body part
      var block = bodyOffset.offset(modifier.offsetIfJump(targetEdge));

      // Apply jump shift to target diagonal and offset for body part
      blockSubscribers.subscribe(block,
        new MovementFreeSubscription(blockIndex, blockBreakSideHint, bodyOffset, block.equals(targetFeetBlock), interactionSideHint));

      if (allowBlockActions) {
        if (bodyOffset == BodyPart.HEAD) {
          blockSubscribers.subscribe(block.add(0, 1, 0),
            new MovementBreakSafetyCheckSubscription(blockIndex, BlockSafetyType.FALLING_AND_FLUIDS));
        }

        for (var skyDirection : SkyDirection.VALUES) {
          blockSubscribers.subscribe(skyDirection.offset(block),
            new MovementBreakSafetyCheckSubscription(blockIndex, BlockSafetyType.FLUIDS));
        }
      }
    }

    // Require free blocks to fall into the target position
    switch (modifier) {
      case FALL_1 -> {
        var fallOneBlockIndex = blockIndexCounter++;
        var fallFree = MovementModifier.FALL_1.offset(targetEdge);

        blockSubscribers.subscribe(fallFree,
          new MovementFreeSubscription(fallOneBlockIndex, BlockFace.TOP, BodyPart.FEET, false, null));

        // Require free blocks to fall into the target position
        if (allowBlockActions) {
          for (var skyDirection : SkyDirection.VALUES) {
            blockSubscribers.subscribe(skyDirection.offset(fallFree),
              new MovementBreakSafetyCheckSubscription(fallOneBlockIndex, BlockSafetyType.FLUIDS));
          }
        }
      }
      case FALL_2 -> {
        blockSubscribers.subscribe(MovementModifier.FALL_1.offset(targetEdge),
          new MovementFreeSubscription(blockIndexCounter++, BlockFace.TOP, BodyPart.FEET, false, null));
        blockSubscribers.subscribe(MovementModifier.FALL_2.offset(targetEdge),
          new MovementFreeSubscription(blockIndexCounter++, BlockFace.TOP, BodyPart.FEET, false, null));
      }
      case FALL_3 -> {
        blockSubscribers.subscribe(MovementModifier.FALL_1.offset(targetEdge),
          new MovementFreeSubscription(blockIndexCounter++, BlockFace.TOP, BodyPart.FEET, false, null));
        blockSubscribers.subscribe(MovementModifier.FALL_2.offset(targetEdge),
          new MovementFreeSubscription(blockIndexCounter++, BlockFace.TOP, BodyPart.FEET, false, null));
        blockSubscribers.subscribe(MovementModifier.FALL_3.offset(targetEdge),
          new MovementFreeSubscription(blockIndexCounter++, BlockFace.TOP, BodyPart.FEET, false, null));
      }
    }

    return blockIndexCounter;
  }

  private void registerDiagonalCollisionBlocks(SubscriptionConsumer blockSubscribers) {
    if (!diagonal) {
      return;
    }

    var diagonalDirection = direction.toDiagonalDirection();
    for (var side : MovementSide.VALUES) {
      // If these blocks are solid, the bot moves slower because the bot is running around a corner
      var corner = modifier.offsetIfJump(diagonalDirection.side(side).offset(FEET_POSITION_RELATIVE_BLOCK));
      for (var bodyOffset : BodyPart.VALUES) {
        // Apply jump shift to target edge and offset for body part
        blockSubscribers.subscribe(bodyOffset.offset(corner), new MovementDiagonalCollisionSubscription(
          side,
          diagonalDirection.ordinal(),
          bodyOffset
        ));
      }
    }
  }

  private void registerRequiredSolidBlock(SubscriptionConsumer blockSubscribers) {
    // Floor block
    blockSubscribers.subscribe(targetFeetBlock.sub(0, 1, 0), MovementSolidSubscription.INSTANCE);
  }

  private void registerPossibleBlocksToPlaceAgainst(SubscriptionConsumer blockSubscribers) {
    if (!allowBlockActions) {
      return;
    }

    var floorBlock = targetFeetBlock.sub(0, 1, 0);
    switch (modifier) {
      case NORMAL -> { // 5
        // Below
        blockSubscribers.subscribe(floorBlock.sub(0, 1, 0), new MovementAgainstPlaceSolidSubscription(BlockFace.TOP));

        for (var skyDirection : SkyDirection.VALUES) {
          blockSubscribers.subscribe(skyDirection.offset(floorBlock), new MovementAgainstPlaceSolidSubscription(skyDirection.opposite().blockFace()));
        }
      }
      case JUMP_UP_BLOCK, FALL_1 -> { // 4 - no scaffolding
        // Below
        blockSubscribers.subscribe(floorBlock.sub(0, 1, 0), new MovementAgainstPlaceSolidSubscription(BlockFace.TOP));
        for (var skyDirection : SkyDirection.VALUES) {
          if (skyDirection == direction.toSkyDirection().opposite()) {
            // Cannot do scaffolding here
            continue;
          }

          blockSubscribers.subscribe(skyDirection.offset(floorBlock), new MovementAgainstPlaceSolidSubscription(skyDirection.opposite().blockFace()));
        }
      }
      default -> throw new IllegalStateException("Unexpected value: " + modifier);
    }
  }

  @Override
  public List<GraphInstructions> getInstructions(MinecraftGraph graph, SFVec3i node) {
    var requiresAgainstBlock = !floorHasSupport && !supportInTargetBlock && floorCanBePlaced;
    if (!floorHasSupport && !supportInTargetBlock && !requiresAgainstBlock) {
      return Collections.emptyList();
    }

    if (requiresAgainstBlock && blockPlaceAgainstData == null) {
      return Collections.emptyList();
    }

    var cost = this.cost;

    var blocksToBreak = blockBreakCosts == null ? 0 : blockBreakCosts.length;
    var blockToPlace = requiresAgainstBlock ? 1 : 0;
    var blockToInteract = interactablePassage == null ? 0 : 2;

    var usableBlockItemsDiff = 0;
    var actions = new ArrayList<WorldAction>(1 + blocksToBreak + blockToPlace + blockToInteract);
    if (blockBreakCosts != null) {
      for (var breakCost : blockBreakCosts) {
        if (breakCost == null) {
          continue;
        }

        cost += breakCost.miningCost();
        actions.add(new BlockBreakAction(breakCost));

        if (graph.pathConstraint().canBlocksDropWhenBroken() && breakCost.willDropUsableBlockItem()) {
          usableBlockItemsDiff++;
        }
      }
    }

    var absoluteTargetFeetBlock = node.add(targetFeetBlock);

    if (interactablePassage != null) {
      cost += Costs.INTERACT_OPENABLE * 2;
      actions.add(new InteractBlockAction(interactablePassage.block(), interactablePassage.interactFace(), true));
    }

    // Even creative mode needs a block in the inv to place
    var requiresOneBlock = requiresAgainstBlock && usableBlockItemsDiff <= 0;
    if (requiresAgainstBlock) {
      if (graph.pathConstraint().doUsableBlocksDecreaseWhenPlaced()) {
        // After the place we'll have one less usable block item
        usableBlockItemsDiff--;
      }

      cost += graph.pathConstraint().placeBlockPenalty();

      var floorBlock = absoluteTargetFeetBlock.sub(0, 1, 0);
      actions.add(new BlockPlaceAction(floorBlock, blockPlaceAgainstData));
    }

    actions.add(new MovementAction(absoluteTargetFeetBlock, diagonal, graph.pathConstraint()));
    if (interactablePassage != null) {
      actions.add(new InteractBlockAction(interactablePassage.block(), interactablePassage.interactFace(), false));
    }

    return Collections.singletonList(new GraphInstructions(
      absoluteTargetFeetBlock,
      usableBlockItemsDiff,
      requiresOneBlock,
      actionDirection,
      cost,
      actions
    ));
  }

  @Override
  public SimpleMovement copy() {
    return this.clone();
  }

  @Override
  public SimpleMovement clone() {
    try {
      var c = (SimpleMovement) super.clone();

      c.blockBreakCosts = this.blockBreakCosts == null ? null : new MovementMiningCost[this.blockBreakCosts.length];
      c.unsafeToBreak = this.unsafeToBreak == null ? null : new boolean[this.unsafeToBreak.length];
      c.noNeedToBreak = this.noNeedToBreak == null ? null : new boolean[this.noNeedToBreak.length];

      return c;
    } catch (CloneNotSupportedException _) {
      throw new InternalError();
    }
  }

  private interface SimpleMovementSubscription extends MinecraftGraph.MovementSubscription<SimpleMovement> {
  }

  private record MovementFreeSubscription(
    int blockArrayIndex,
    @Nullable BlockFace blockBreakSideHint,
    BodyPart bodyPart,
    boolean targetFeetBlock,
    @Nullable BlockFace interactionSideHint
  ) implements SimpleMovementSubscription {
    @Override
    public MinecraftGraph.SubscriptionSingleResult processBlock(MinecraftGraph graph, SFVec3i key, SimpleMovement simpleMovement,
                                                                BlockState blockState, SFVec3i absoluteKey) {
      if (SFBlockHelpers.isBodyPassableBlock(blockState)) {
        if (simpleMovement.allowBlockActions) {
          simpleMovement.noNeedToBreak[blockArrayIndex] = true;
        }

        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      if (bodyPart == BodyPart.FEET && targetFeetBlock && SFBlockHelpers.canSupportFeetInBlock(blockState)) {
        if (simpleMovement.allowBlockActions) {
          simpleMovement.noNeedToBreak[blockArrayIndex] = true;
        }

        simpleMovement.supportInTargetBlock = true;
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      if (interactionSideHint != null && simpleMovement.allowInteractablePassage && SFBlockHelpers.isOpenablePassageBlock(blockState)) {
        var interactableBlock = SFVec3i.fromInt(SFBlockHelpers.getOpenablePassagePos(absoluteKey.toBlockPos(), blockState));
        var interactablePassage = simpleMovement.interactablePassage;
        if (interactablePassage == null) {
          simpleMovement.interactablePassage = new InteractablePassage(interactableBlock, interactionSideHint);
          if (simpleMovement.allowBlockActions) {
            simpleMovement.noNeedToBreak[blockArrayIndex] = true;
          }

          return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
        } else if (interactablePassage.block().equals(interactableBlock)) {
          if (simpleMovement.allowBlockActions) {
            simpleMovement.noNeedToBreak[blockArrayIndex] = true;
          }

          return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
        }

        return MinecraftGraph.SubscriptionSingleResult.IMPOSSIBLE;
      }

      // Search for a way to break this block
      if (!graph.pathConstraint().canBreakBlock(absoluteKey, blockState)
        || !simpleMovement.allowBlockActions
        || blockBreakSideHint == null
        // Check if we previously found out this block is unsafe to break
        || simpleMovement.unsafeToBreak[blockArrayIndex]) {
        // No way to break this block
        return MinecraftGraph.SubscriptionSingleResult.IMPOSSIBLE;
      }

      var cacheableMiningCost = graph.inventory().getMiningCosts(blockState);
      // We can mine this block, lets add costs and continue
      simpleMovement.blockBreakCosts[blockArrayIndex] =
        new MovementMiningCost(
          absoluteKey,
          cacheableMiningCost.miningCost(),
          cacheableMiningCost.willDropUsableBlockItem(),
          blockBreakSideHint);
      return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
    }
  }

  private record MovementBreakSafetyCheckSubscription(int blockArrayIndex, BlockSafetyType safetyType) implements SimpleMovementSubscription {
    @Override
    public MinecraftGraph.SubscriptionSingleResult processBlock(MinecraftGraph graph, SFVec3i key, SimpleMovement simpleMovement,
                                                                BlockState blockState, SFVec3i absoluteKey) {
      // There is no need to break this block, so there is no need for safety checks
      if (simpleMovement.noNeedToBreak[blockArrayIndex]) {
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      // The block was already marked as unsafe
      if (simpleMovement.unsafeToBreak[blockArrayIndex]) {
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      var unsafe = safetyType.isUnsafeBlock(blockState);

      if (!unsafe) {
        // All good, we can continue
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      var currentValue = simpleMovement.blockBreakCosts[blockArrayIndex];

      if (currentValue != null) {
        // We learned that this block needs to be broken, so we need to set it as impossible
        return MinecraftGraph.SubscriptionSingleResult.IMPOSSIBLE;
      }

      // Store for a later time that this is unsafe,
      // so if we check this block,
      // we know it's unsafe
      simpleMovement.unsafeToBreak[blockArrayIndex] = true;

      return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
    }
  }

  private record MovementSolidSubscription() implements SimpleMovementSubscription {
    private static final MovementSolidSubscription INSTANCE = new MovementSolidSubscription();

    @Override
    public MinecraftGraph.SubscriptionSingleResult processBlock(MinecraftGraph graph, SFVec3i key, SimpleMovement simpleMovement,
                                                                BlockState blockState, SFVec3i absoluteKey) {
      // Block is safe to walk on, no need to check for more
      if (SFBlockHelpers.isWalkableFloorBlock(blockState)) {
        simpleMovement.floorHasSupport = true;
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      if (!graph.pathConstraint().canPlaceBlock(absoluteKey)
        || !simpleMovement.allowBlockActions
        || !blockState.canBeReplaced()) {
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      simpleMovement.floorCanBePlaced = true;
      return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
    }
  }

  private record MovementDiagonalCollisionSubscription(MovementSide side, int diagonalArrayIndex, BodyPart bodyPart) implements SimpleMovementSubscription {
    @Override
    public MinecraftGraph.SubscriptionSingleResult processBlock(MinecraftGraph graph, SFVec3i key, SimpleMovement simpleMovement,
                                                                BlockState blockState, SFVec3i absoluteKey) {
      if (SFBlockHelpers.isHurtOnTouchSide(blockState)
        || SFBlockHelpers.affectsTouchMovementSpeed(blockState.getBlock())) {
        // Since this is a corner, we can also avoid touching blocks that hurt us, e.g., cacti
        return MinecraftGraph.SubscriptionSingleResult.IMPOSSIBLE;
      } else if (graph.pathConstraint().collidesWithAtEdge(new DiagonalCollisionCalculator.CollisionData(blockState, diagonalArrayIndex, bodyPart, side))) {
        var blockedSide = simpleMovement.blockedSide;
        if (blockedSide == null) {
          simpleMovement.blockedSide = side;
          simpleMovement.cost = simpleMovement.cost + Costs.CORNER_SLIDE;
          return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
        } else if (blockedSide == side) {
          return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
        } else {
          // Diagonal path is blocked on both sides
          return MinecraftGraph.SubscriptionSingleResult.IMPOSSIBLE;
        }
      }

      return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
    }
  }

  private record MovementAgainstPlaceSolidSubscription(BlockFace againstFace) implements SimpleMovementSubscription {
    @Override
    public MinecraftGraph.SubscriptionSingleResult processBlock(MinecraftGraph graph, SFVec3i key, SimpleMovement simpleMovement,
                                                                BlockState blockState, SFVec3i absoluteKey) {
      // We already found one, no need to check for more
      if (simpleMovement.blockPlaceAgainstData != null) {
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      // This block should not be placed against
      if (!SFBlockHelpers.isCollisionShapeFullBlock(blockState)) {
        return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
      }

      // Fixup the position to be the block we are placing against instead of relative
      simpleMovement.blockPlaceAgainstData = new BlockPlaceAgainstData(absoluteKey, againstFace);
      return MinecraftGraph.SubscriptionSingleResult.CONTINUE;
    }
  }

  private record InteractablePassage(SFVec3i block, BlockFace interactFace) {
  }
}
