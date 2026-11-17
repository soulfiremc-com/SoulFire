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

import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.util.VectorHelper;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MovementActionTest {
  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void movementInputTargetsWorldDirectionsIndependentlyOfViewYaw() {
    var origin = Vec3.ZERO;

    assertInput(true, false, false, false,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(0.0, 0.0, 1.0)));
    assertInput(false, true, false, false,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(0.0, 0.0, -1.0)));
    assertInput(false, false, true, false,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(1.0, 0.0, 0.0)));
    assertInput(false, false, false, true,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(-1.0, 0.0, 0.0)));

    assertInput(false, false, false, true,
      MovementAction.movementInputFor(origin, -90.0F, new Vec3(0.0, 0.0, 1.0)));
  }

  @Test
  void movementInputUsesDiagonalControlsAndStopsAtTheTarget() {
    var origin = Vec3.ZERO;

    assertInput(true, false, true, false,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(1.0, 0.0, 1.0)));
    assertInput(false, true, false, true,
      MovementAction.movementInputFor(origin, 0.0F, new Vec3(-1.0, 0.0, -1.0)));
    assertInput(false, false, false, false,
      MovementAction.movementInputFor(origin, 135.0F, origin));
  }

  @Test
  void horizontalTargetDistanceIgnoresHeightDuringARequiredDrop() {
    var currentPosition = new Vec3(4.5, 12.0, -2.5);

    assertEquals(
      0.0,
      MovementAction.horizontalDistance(
        currentPosition,
        new Vec3(4.5, 8.0, -2.5)
      )
    );
    assertEquals(
      5.0,
      MovementAction.horizontalDistance(
        currentPosition,
        new Vec3(7.5, 20.0, 1.5)
      )
    );
  }

  @Test
  void doesNotCompleteAVerticalStepWhilePassingThroughItInMidair() {
    assertFalse(MovementAction.hasReachedTargetHeight(
      66.0,
      66.0,
      false
    ));
    assertTrue(MovementAction.hasReachedTargetHeight(
      66.0,
      66.0,
      true
    ));
    assertFalse(MovementAction.hasReachedTargetHeight(
      65.5,
      66.0,
      true
    ));
  }

  @Test
  void anOpenDoorIsWalkedThroughNotOnto() {
    // A door's collision box is a full block tall along one edge: its top as
    // the target height left the bot jumping in the doorway until the step
    // timed out.
    var cell = new SFVec3i(3, 64, -2);
    var openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
    var openGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true);

    for (var state : new BlockState[] {openDoor, openGate, Blocks.AIR.defaultBlockState()}) {
      var target = VectorHelper.standingMiddleOfBlock(cell, state);
      assertEquals(new Vec3(3.5, 64, -1.5), target);
      assertTrue(MovementAction.hasReachedTargetHeight(64.0, target.y, true));
      assertFalse(MovementAction.needsUpwardInput(64.0, target.y, false));
    }
  }

  @Test
  void whatYouStandOnInsideTheCellStillRaisesTheTarget() {
    var cell = new SFVec3i(0, 10, 0);

    assertEquals(10.5, VectorHelper.standingMiddleOfBlock(cell, Blocks.OAK_SLAB.defaultBlockState()).y);
    assertEquals(10.0625, VectorHelper.standingMiddleOfBlock(cell, Blocks.CARPET.white().defaultBlockState()).y);
  }

  @Test
  void keepsSwimmingWhileCrossingAFluidBlockAtTargetHeight() {
    assertTrue(MovementAction.needsUpwardInput(61.5, 62.0, true));
    assertTrue(MovementAction.needsUpwardInput(61.74, 62.0, true));
    assertTrue(MovementAction.needsUpwardInput(61.75, 62.0, true));
    assertTrue(MovementAction.needsUpwardInput(62.25, 62.0, true));
    assertFalse(MovementAction.needsUpwardInput(62.26, 62.0, true));

    assertFalse(MovementAction.needsUpwardInput(61.5, 62.0, false));
    assertTrue(MovementAction.needsUpwardInput(61.39, 62.0, false));
  }

  @Test
  void keepsJumpingAfterTheInitialDiagonalApproach() {
    var action = new MovementAction(SFVec3i.ZERO, true, null);

    assertFalse(action.shouldJump());
    assertFalse(action.shouldJump());
    assertFalse(action.shouldJump());
    assertTrue(action.shouldJump());
    assertTrue(action.shouldJump());
  }

  @Test
  void acceptsPartialCollisionSupportInsideTheFeetBlock() {
    assertTrue(MovementAction.hasValidTargetStates(
      Blocks.STONE_SLAB.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.AIR.defaultBlockState()
    ));
    assertTrue(MovementAction.hasValidTargetStates(
      Blocks.SNOW.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.STONE.defaultBlockState()
    ));
  }

  @Test
  void aLadderIsClimbedInsideNotStoodOn() {
    var cell = new SFVec3i(0, 10, 0);
    var ladder = Blocks.LADDER.defaultBlockState();

    assertEquals(new Vec3(0.5, 10, 0.5), VectorHelper.standingMiddleOfBlock(cell, ladder));
    assertTrue(MovementAction.hasValidTargetStates(ladder, ladder, ladder));
  }

  @Test
  void sneaksToHoldItsHeightOnAClimbableUnlessHeadingDown() {
    // Stepping across a vine wall at the same height
    assertTrue(MovementAction.holdsClimbableHeight(true, 98.0, 98.0));
    assertTrue(MovementAction.holdsClimbableHeight(true, 98.2, 98.0));
    // Climbing down to a block below
    assertFalse(MovementAction.holdsClimbableHeight(true, 99.0, 98.0));
    assertFalse(MovementAction.holdsClimbableHeight(false, 98.0, 98.0));
  }

  @Test
  void acceptsWaterWithoutASolidFloorAsASwimmingTarget() {
    assertTrue(MovementAction.hasValidTargetStates(
      Blocks.WATER.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.WATER.defaultBlockState()
    ));
  }

  @Test
  void acceptsAFullySubmergedSwimmingTarget() {
    assertTrue(MovementAction.hasValidTargetStates(
      Blocks.WATER.defaultBlockState(),
      Blocks.WATER.defaultBlockState(),
      Blocks.WATER.defaultBlockState()
    ));
  }

  @Test
  void rejectsAStaleMovementTargetWithoutSupport() {
    assertFalse(MovementAction.hasValidTargetStates(
      Blocks.AIR.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.AIR.defaultBlockState()
    ));
  }

  @Test
  void rejectsAGravityFloorThatWillDisappearBeforeLanding() {
    assertFalse(MovementAction.hasValidTargetStates(
      Blocks.AIR.defaultBlockState(),
      Blocks.AIR.defaultBlockState(),
      Blocks.GRAVEL.defaultBlockState(),
      false
    ));
  }

  private static void assertInput(
    boolean forward,
    boolean backward,
    boolean left,
    boolean right,
    MovementAction.MovementInput input
  ) {
    assertEquals(forward, input.forward());
    assertEquals(backward, input.backward());
    assertEquals(left, input.left());
    assertEquals(right, input.right());
  }
}
