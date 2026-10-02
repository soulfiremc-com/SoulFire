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
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.util.VectorHelper;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

  @Test
  void settlesInABlockNearLavaBeforeGoingOn() {
    var action = new MovementAction(new SFVec3i(0, 64, 0), true, mock(PathConstraint.class));
    var nearCentre = new Vec3(0.6, 64, 0.6);
    var lavaFall = Map.of(new BlockPos(-1, 65, -1), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
    var walking = new Vec3(-0.15, -0.08, -0.15);

    // Within the distance that completes a step, but still moving
    assertFalse(action.isCompleted(connection(nearCentre, walking, Support.GROUND, lavaFall)));
    assertFalse(action.isCompleted(connection(nearCentre, new Vec3(0.04, -0.08, 0), Support.GROUND, lavaFall)));
    assertTrue(action.isCompleted(connection(nearCentre, new Vec3(0.02, -0.08, 0), Support.GROUND, lavaFall)));
    // Nothing around to touch, in water, or climbing
    assertTrue(action.isCompleted(connection(nearCentre, walking, Support.GROUND, Map.of())));
    assertTrue(action.isCompleted(connection(nearCentre, walking, Support.WADING, lavaFall)));
    assertTrue(action.isCompleted(connection(nearCentre, walking, Support.CLIMBING, lavaFall)));
  }

  private enum Support {
    GROUND,
    WADING,
    CLIMBING
  }

  private static BotConnection connection(Vec3 position, Vec3 deltaMovement, Support support, Map<BlockPos, BlockState> blocks) {
    var player = mock(LocalPlayer.class);
    when(player.position()).thenReturn(position);
    when(player.getDeltaMovement()).thenReturn(deltaMovement);
    when(player.onGround()).thenReturn(support != Support.CLIMBING);
    when(player.isInWater()).thenReturn(support == Support.WADING);
    when(player.onClimbable()).thenReturn(support == Support.CLIMBING);
    when(player.getBoundingBox()).thenReturn(new AABB(
      position.x - 0.3, position.y, position.z - 0.3,
      position.x + 0.3, position.y + 1.8, position.z + 0.3));
    var level = mock(ClientLevel.class);
    when(level.getBlockState(any(BlockPos.class)))
      .thenAnswer(call -> blocks.getOrDefault(call.<BlockPos>getArgument(0), Blocks.AIR.defaultBlockState()));
    var minecraft = mock(Minecraft.class);
    minecraft.player = player;
    minecraft.level = level;
    var connection = mock(BotConnection.class);
    when(connection.minecraft()).thenReturn(minecraft);
    return connection;
  }
}
