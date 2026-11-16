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
package com.soulfiremc.test;

import com.soulfiremc.server.pathfinding.NodeState;
import com.soulfiremc.server.pathfinding.RouteFinder;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.BlockPlaceAction;
import com.soulfiremc.server.pathfinding.execution.ClimbAction;
import com.soulfiremc.server.pathfinding.execution.GapJumpAction;
import com.soulfiremc.server.pathfinding.execution.InteractBlockAction;
import com.soulfiremc.server.pathfinding.execution.JumpAndPlaceBelowAction;
import com.soulfiremc.server.pathfinding.execution.MovementAction;
import com.soulfiremc.server.pathfinding.goals.AdjacentToBlockGoal;
import com.soulfiremc.server.pathfinding.goals.PosGoal;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;
import com.soulfiremc.server.pathfinding.graph.ProjectedInventory;
import com.soulfiremc.server.pathfinding.graph.constraint.AdditionalPlacementConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.AvoidFluidConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.DelegatePathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockActionsConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockBreakingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockPlacingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.test.utils.TestBlockAccessorBuilder;
import com.soulfiremc.test.utils.TestBootstrap;
import com.soulfiremc.test.utils.TestMiningCostCalculator;
import com.soulfiremc.test.utils.TestPathConstraint;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PathfindingTest {
  private static ItemStack itemStack(Item item) {
    return itemStack(item, 1);
  }

  private static ItemStack itemStack(Item item, int count) {
    var itemStack = new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), count);
    if (item == Items.DIAMOND_PICKAXE) {
      itemStack.set(
        DataComponents.TOOL,
        new Tool(
          List.of(Tool.Rule.minesAndDrops(HolderSet.direct(Holder.direct(Blocks.STONE)), 8.0F)),
          1.0F,
          1,
          false));
    }

    return itemStack;
  }

  @BeforeAll
  static void setup() {
    // Bootstrap mixins and Minecraft registries
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void noBlockBreakingConstraintRejectsIndividualBlocks() {
    var constraint = new NoBlockBreakingConstraint(TestPathConstraint.INSTANCE);

    assertFalse(constraint.canBreakBlocks());
    assertFalse(constraint.canBreakBlock(SFVec3i.ZERO, Blocks.STONE.defaultBlockState()));
  }

  @Test
  void noBlockPlacingConstraintRejectsIndividualBlocks() {
    var constraint = new NoBlockPlacingConstraint(TestPathConstraint.INSTANCE);

    assertFalse(constraint.canPlaceBlocks());
    assertFalse(constraint.canPlaceBlock(SFVec3i.ZERO));
  }

  @Test
  void pathfindingAcrossFlatGroundWithoutBlockActions() {
    var accessor = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 4; x++) {
      accessor.setBlockAt(x, 0, 0, Blocks.STONE);
      accessor.setBlockAt(x, 0, -1, Blocks.STONE);
    }
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(itemStack(Items.STONE)),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(4, 1, -1));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertFalse(foundRouteResult.actions().stream().anyMatch(BlockPlaceAction.class::isInstance));
  }

  @Test
  void pathfindingAcceptsAnAlreadySatisfiedGoalWithoutMoving() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    var constraint = new NoBlockActionsConstraint(
      TestPathConstraint.INSTANCE
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(0, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var found = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(List.of(), found.actions());
    assertEquals(0, found.metadata().expandedStates());
    assertEquals(0, found.metadata().routeCost().optimizationCost());
    assertEquals(1, found.metadata().qualityBound());
  }

  @Test
  void pathfindingApproachesABlockedDiagonalAscentCardinally() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, 0, 1, Blocks.STONE);
    accessor.setBlockAt(1, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 1, 1, Blocks.STONE);
    var constraint = new NoBlockActionsConstraint(
      TestPathConstraint.INSTANCE
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(1, 2, 1)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    var movements = foundRouteResult.actions().stream()
      .filter(MovementAction.class::isInstance)
      .map(MovementAction.class::cast)
      .toList();
    assertEquals(
      List.of(new SFVec3i(0, 1, 1), new SFVec3i(1, 2, 1)),
      movements.stream().map(MovementAction::blockPosition).toList()
    );
  }

  @Test
  void pathfindingBuildsOutFromASingleDryBlockSurroundedByWater() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.DIRT);
    accessor.setBlockAt(1, 0, 0, Blocks.WATER);
    accessor.setBlockAt(2, 0, 0, Blocks.WATER);
    var level = accessor.build();
    var constraint = new AvoidFluidConstraint(
      new AdditionalPlacementConstraint(
        TestPathConstraint.INSTANCE,
        Set.of(Items.OAK_LOG)
      ),
      OptionalInt.empty()
    );
    var inventory = new ProjectedInventory(
      List.of(itemStack(Items.OAK_LOG, 2)),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(level, inventory, constraint),
      new PosGoal(2, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    assertEquals(
      2,
      foundRouteResult.actions().stream()
        .filter(BlockPlaceAction.class::isInstance)
        .count()
    );
  }

  @Test
  void avoidFluidsPathfindingCanAscendFromASubmergedStart() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.WATER);
    accessor.setBlockAt(0, 2, 0, Blocks.WATER);
    accessor.setBlockAt(1, 1, 0, Blocks.WATER);
    accessor.setBlockAt(1, 2, 0, Blocks.WATER);
    accessor.setBlockAt(2, 2, 0, Blocks.DIRT);
    var level = accessor.build();
    var constraint = new AvoidFluidConstraint(
      TestPathConstraint.INSTANCE,
      OptionalInt.of(1)
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(level, inventory, constraint),
      new PosGoal(2, 3, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
  }

  @Test
  void pathfindingSwimsStraightUpWithoutPlacementBlocks() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.DIRT);
    accessor.setBlockAt(0, 1, 0, Blocks.WATER);
    accessor.setBlockAt(0, 2, 0, Blocks.WATER);
    var level = accessor.build();
    var constraint = new AvoidFluidConstraint(
      TestPathConstraint.INSTANCE,
      OptionalInt.of(1)
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(level, inventory, constraint),
      new PosGoal(0, 2, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    assertEquals(1, foundRouteResult.actions().size());
    assertInstanceOf(MovementAction.class, foundRouteResult.actions().getFirst());
    assertFalse(foundRouteResult.actions().stream().anyMatch(
      JumpAndPlaceBelowAction.class::isInstance
    ));
  }

  @ParameterizedTest
  @ValueSource(strings = {"farmland", "dirt_path"})
  void pathfindingWalksAcrossRaisedFloors(String floorId) {
    var floor = floorId.equals("farmland") ? Blocks.FARMLAND : Blocks.DIRT_PATH;
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    for (var x = 1; x <= 4; x++) {
      accessor.setBlockAt(x, 0, 0, floor);
      if (floor == Blocks.FARMLAND) {
        accessor.setBlockAt(x, 1, 0, Blocks.WHEAT);
      }
    }
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(4, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
  }

  @Test
  void pathfindingLeavesFarmlandItStandsOn() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.FARMLAND);
    accessor.setBlockAt(1, 0, 0, Blocks.FARMLAND);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(2, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
  }

  @Test
  void pathfindingDropsOntoFarmlandWhenThereIsNoOtherWay() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.FARMLAND);
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(1, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 2, 0), inventory)
    ).join();

    assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
  }

  @Test
  void pathfindingStepsDownBesideFarmlandRatherThanDropOntoIt() {
    // With a stone step beside it, the route avoids dropping onto farmland
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.FARMLAND);
    accessor.setBlockAt(0, 0, 1, Blocks.STONE);
    accessor.setBlockAt(1, 0, 1, Blocks.STONE);
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(1, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 2, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    var movements = foundRouteResult.actions().stream()
      .filter(MovementAction.class::isInstance)
      .map(MovementAction.class::cast)
      .map(MovementAction::blockPosition)
      .toList();
    // Onto the stone first, not straight onto the farmland
    assertEquals(2, movements.size());
    assertEquals(new SFVec3i(1, 1, 0), movements.getLast());
  }

  @Test
  void pathfindingDetoursAroundPackedIce() {
    // A mountain ridge of packed ice beside a stone ledge: sliding on the ice
    // can carry the bot off the edge, so the route takes the stone instead
    var accessor = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 4; x++) {
      accessor.setBlockAt(x, 0, 0, x == 0 || x == 4 ? Blocks.STONE : Blocks.PACKED_ICE);
      accessor.setBlockAt(x, 0, 1, Blocks.STONE);
    }
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(4, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    var steppedOnIce = foundRouteResult.actions().stream()
      .filter(MovementAction.class::isInstance)
      .map(MovementAction.class::cast)
      .map(MovementAction::blockPosition)
      .anyMatch(position -> position.z == 0 && position.x >= 1 && position.x <= 3);
    assertFalse(steppedOnIce);
  }

  @Test
  void pathfindingDownStaircaseWithoutBlockActions() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, -1, 1, Blocks.STONE);
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(itemStack(Items.STONE)),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(0, 0, 1));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingDoesNotJumpIntoPartialSupportAboveAFullBlock() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 1, 0, Blocks.STONE);
    accessor.setBlockStateAt(
      1,
      2,
      0,
      Blocks.OAK_STAIRS.defaultBlockState()
        .setValue(StairBlock.HALF, Half.BOTTOM)
    );
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(1, 2, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    assertFalse(route instanceof RouteFinder.FoundRouteResult);
  }

  @Test
  void pathfindingStraight() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(2, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class, route);
    assertEquals(2, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingCrossesANegativeChunkBoundaryTowardPositiveX() {
    var accessor = new TestBlockAccessorBuilder();
    for (var x = -1024; x <= -992; x++) {
      accessor.setBlockAt(x, 48, -1296, Blocks.STONE);
    }
    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(-992, 49, -1296)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(-1024, 49, -1296), inventory)
    ).join();

    var found = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(32, found.actions().stream()
      .filter(MovementAction.class::isInstance)
      .count());
  }

  @Test
  void pathfindingCarvesOutBesideASupportingBlock() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.OAK_LOG);
    for (var x = -1; x <= 1; x++) {
      for (var z = -1; z <= 1; z++) {
        if (x != 0 || z != 0) {
          accessor.setBlockAt(x, 0, z, Blocks.OAK_LEAVES);
        }
        accessor.setBlockAt(x, 1, z, Blocks.OAK_LEAVES);
        accessor.setBlockAt(x, 2, z, Blocks.OAK_LEAVES);
      }
    }
    accessor.setBlockAt(0, 1, 0, Blocks.AIR);
    accessor.setBlockAt(0, 2, 0, Blocks.AIR);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      TestPathConstraint.INSTANCE
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(
        accessor.build(),
        inventory,
        TestPathConstraint.INSTANCE
      ),
      new AdjacentToBlockGoal(SFVec3i.ZERO)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );

    assertEquals(3, route.actions().size());
    assertEquals(
      2,
      route.actions().stream()
        .filter(BlockBreakAction.class::isInstance)
        .count()
    );
    assertTrue(route.actions().stream().anyMatch(
      MovementAction.class::isInstance
    ));
  }

  @Test
  void pathfindingContinuesThroughLoadedTerrainBesideAWorldDataBoundary() {
    var accessor = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 4; x++) {
      accessor.setBlockAt(x, 0, 0, Blocks.STONE);
    }
    var loadedAreaConstraint = new DelegatePathConstraint() {
      @Override
      public boolean isOutOfLevel(BlockState blockState, SFVec3i position) {
        return position.z < -2;
      }

      @Override
      public PathConstraint delegate() {
        return TestPathConstraint.INSTANCE;
      }
    };
    var constraint = new NoBlockActionsConstraint(loadedAreaConstraint);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(4, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );
    assertEquals(4, foundRouteResult.actions().size());
  }

  @Test
  void partialRouteEndsAtTheClosestReachableNodeToTheGoal() {
    var accessor = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 4; x++) {
      accessor.setBlockAt(x, 0, 0, Blocks.STONE);
    }
    var loadedAreaConstraint = new DelegatePathConstraint() {
      @Override
      public boolean isOutOfLevel(BlockState blockState, SFVec3i position) {
        return position.x > 4;
      }

      @Override
      public PathConstraint delegate() {
        return TestPathConstraint.INSTANCE;
      }
    };
    var constraint = new NoBlockActionsConstraint(loadedAreaConstraint);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(10, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var partialRoute = assertInstanceOf(
      RouteFinder.PartialRouteResult.class,
      route
    );
    var lastMovement = assertInstanceOf(
      MovementAction.class,
      partialRoute.actions().getLast()
    );
    assertEquals(new SFVec3i(3, 1, 0), lastMovement.blockPosition());
    assertEquals(lastMovement.blockPosition(), partialRoute.endpoint());
    assertFalse(partialRoute.metadata().unavailableChunks().isEmpty());
  }

  @Test
  void fluidAvoidancePreservesADryPartialRouteAtTheLoadedBoundary() {
    var accessor = new TestBlockAccessorBuilder();
    for (var x = 0; x <= 64; x++) {
      for (var z = -2; z <= 2; z++) {
        accessor.setBlockAt(x, 0, z, Blocks.STONE);
      }
    }
    var loadedAreaConstraint = new DelegatePathConstraint() {
      @Override
      public boolean isOutOfLevel(BlockState blockState, SFVec3i position) {
        return position.x > 64;
      }

      @Override
      public PathConstraint delegate() {
        return TestPathConstraint.INSTANCE;
      }
    };
    var constraint = new AvoidFluidConstraint(
      new NoBlockActionsConstraint(loadedAreaConstraint),
      OptionalInt.empty()
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(200, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var partialRoute = assertInstanceOf(
      RouteFinder.PartialRouteResult.class,
      route
    );
    assertTrue(partialRoute.endpoint().x > 0);
    assertFalse(partialRoute.metadata().unavailableChunks().isEmpty());
  }

  @Test
  void waitsForWorldDataWhenTheStartTouchesAnUnloadedBoundary() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    var loadedAreaConstraint = new DelegatePathConstraint() {
      @Override
      public boolean isOutOfLevel(BlockState blockState, SFVec3i position) {
        return position.x > 0;
      }

      @Override
      public PathConstraint delegate() {
        return TestPathConstraint.INSTANCE;
      }
    };
    var constraint = new NoBlockActionsConstraint(loadedAreaConstraint);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint, 19),
      new PosGoal(10, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var pending = assertInstanceOf(
      RouteFinder.WorldDataPendingResult.class,
      route
    );
    assertEquals(new SFVec3i(0, 1, 0), pending.endpoint());
    assertEquals(19, pending.metadata().worldRevision());
    assertFalse(pending.metadata().unavailableChunks().isEmpty());
  }

  @Test
  void pathfindingImpossible() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder =
      new RouteFinder(
        new MinecraftGraph(
          accessor.build(),
          inventory,
          TestPathConstraint.INSTANCE),
        // This is impossible to reach
        new PosGoal(3, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
  }

  @Test
  void pathfindingDiagonal() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 1, Blocks.STONE);
    accessor.setBlockAt(2, 0, 2, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(2, 1, 2));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();

    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class, route);
    assertEquals(2, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingDiagonalImpossible() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 1, Blocks.STONE);
    accessor.setBlockAt(2, 0, 2, Blocks.STONE);

    // Barricade
    accessor.setBlockAt(1, 1, 2, Blocks.BEDROCK);
    accessor.setBlockAt(1, 2, 2, Blocks.BEDROCK);
    accessor.setBlockAt(2, 1, 1, Blocks.BEDROCK);
    accessor.setBlockAt(2, 2, 1, Blocks.BEDROCK);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(2, 1, 2));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void pathfindingJump(int height) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, height, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, height + 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (height > 1) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(1, foundRouteResult.actions().size());
    }
  }

  @Test
  void pathfindingBreaksTheOriginCeilingBeforeJumpingOntoALedge() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, 1, 0, Blocks.WATER);
    accessor.setBlockAt(0, 2, 0, Blocks.WATER);
    accessor.setBlockAt(0, 1, 1, Blocks.STONE);
    accessor.setBlockAt(0, 3, 0, Blocks.STONE);

    var constraint = new NoBlockPlacingConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(itemStack(Items.DIAMOND_PICKAXE)),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(0, 2, 1));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);
    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );

    assertEquals(2, foundRouteResult.actions().size());
    assertInstanceOf(BlockBreakAction.class, foundRouteResult.actions().get(0));
    assertInstanceOf(MovementAction.class, foundRouteResult.actions().get(1));
  }

  @Test
  void pathfindingSwimsHorizontallyWithoutSolidGround() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.WATER);
    accessor.setBlockAt(0, 2, 0, Blocks.WATER);
    accessor.setBlockAt(1, 1, 0, Blocks.WATER);
    accessor.setBlockAt(1, 2, 0, Blocks.WATER);

    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, constraint);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(1, 1, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
    assertInstanceOf(MovementAction.class, foundRouteResult.actions().getFirst());
  }

  @Test
  void pathfindingEntersADeepChannelAtTheWaterSurface() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.STONE);
    accessor.setBlockAt(4, 0, 0, Blocks.STONE);
    for (var x = 1; x <= 3; x++) {
      accessor.setBlockAt(x, -1, 0, Blocks.STONE);
      accessor.setBlockAt(x, 0, 0, Blocks.WATER);
      accessor.setBlockAt(x, 1, 0, Blocks.WATER);
    }

    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(4, 1, 0)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 2, 0), inventory)
      ).join()
    );
    var movements = route.actions().stream()
      .filter(MovementAction.class::isInstance)
      .map(MovementAction.class::cast)
      .toList();

    assertFalse(movements.isEmpty());
    assertEquals(1, movements.getFirst().blockPosition().y);
    assertTrue(movements.stream().allMatch(
      movement -> movement.blockPosition().y >= 1
    ));
  }

  @Test
  void pathfindingSwimsThroughWaterAndClimbsOntoBank() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.WATER);
    accessor.setBlockAt(1, 1, 0, Blocks.WATER);
    accessor.setBlockAt(2, 1, 0, Blocks.STONE);

    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, constraint);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint
    ), new PosGoal(2, 2, 0));

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();

    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(2, foundRouteResult.actions().size());
    assertFalse(foundRouteResult.actions().stream().anyMatch(BlockPlaceAction.class::isInstance));
  }

  @Test
  void pathfindingWalksAroundFluidInsteadOfParkouringOverIt() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.WATER);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);
    for (var x = 0; x <= 2; x++) {
      accessor.setBlockAt(x, 0, 1, Blocks.STONE);
    }

    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(2, 1, 0)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );

    assertFalse(route.actions().stream().anyMatch(GapJumpAction.class::isInstance));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pathfindingClimbsLadderColumns(boolean ascending) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, 1, 0, Blocks.LADDER);
    accessor.setBlockAt(0, 2, 0, Blocks.LADDER);

    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      TestPathConstraint.INSTANCE
    );
    var startY = ascending ? 1 : 2;
    var goalY = ascending ? 2 : 1;
    var routeFinder = new RouteFinder(
      new MinecraftGraph(
        accessor.build(),
        inventory,
        TestPathConstraint.INSTANCE
      ),
      new PosGoal(0, goalY, 0)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, startY, 0), inventory)
      ).join()
    );

    assertEquals(1, route.actions().size());
    assertInstanceOf(ClimbAction.class, route.actions().getFirst());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pathfindingUsesLadderBetweenFloors(boolean ascending) {
    // A ladder on the north face of a wall, from the ground up to the floor on top of the wall
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, -1, Blocks.STONE);
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    for (var y = 1; y <= 5; y++) {
      accessor.setBlockAt(0, y, 0, Blocks.LADDER);
      accessor.setBlockAt(0, y, 1, Blocks.STONE);
    }

    var constraint = new NoBlockActionsConstraint(TestPathConstraint.INSTANCE);
    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, constraint);
    var ground = new SFVec3i(0, 1, -1);
    var top = new SFVec3i(0, 6, 1);
    var goal = ascending ? top : ground;
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(goal.x, goal.y, goal.z)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(ascending ? ground : top, inventory)
      ).join()
    );

    // Down, the route may leave the ladder anywhere it can drop to the ground from
    if (ascending) {
      assertTrue(route.actions().stream().anyMatch(ClimbAction.class::isInstance));
    }
  }

  @Test
  void pathfindingRejectsDiagonalJumpAscents() {
    var height = 1;
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, height, 1, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, height + 1, 1));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class,
      routeFinder.findRouteFuture(initialState).join()
    );
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void pathfindingFall(int height) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, -height, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, -height + 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (height > 3) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(1, foundRouteResult.actions().size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void pathfindingFallDiagonal(int height) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, -height, 1, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, -height + 1, 1));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (height > 3) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(1, foundRouteResult.actions().size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void pathfindingGapJump(int gapLength) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(gapLength + 1, 0, 0, Blocks.STONE);
    for (var x = 1; x <= gapLength; x++) {
      accessor.setBlockAt(x, -3, 0, Blocks.STONE);
    }

    var constraint = new MovementLimitConstraint(
      TestPathConstraint.INSTANCE,
      3,
      3
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      constraint), new PosGoal(gapLength + 1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (gapLength > 3) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(1, foundRouteResult.actions().size());
    }
  }

  @Test
  void pathfindingRejectsParkourOverALethalDrop() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);
    var constraint = new MovementLimitConstraint(
      TestPathConstraint.INSTANCE,
      3,
      3
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(2, 1, 0)
    );

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );
  }

  @Test
  void pathfindingHonorsOrdinaryFallLimit() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, -2, 0, Blocks.STONE);
    var constraint = new MovementLimitConstraint(
      new NoBlockActionsConstraint(TestPathConstraint.INSTANCE),
      1,
      3
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(1, -1, 0)
    );

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );
  }

  @Test
  void pathfindingHonorsParkourGapLimit() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(3, 0, 0, Blocks.STONE);
    var constraint = new MovementLimitConstraint(
      new NoBlockActionsConstraint(TestPathConstraint.INSTANCE),
      3,
      1
    );
    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      constraint
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(accessor.build(), inventory, constraint),
      new PosGoal(3, 1, 0)
    );

    assertInstanceOf(
      RouteFinder.NoRouteFoundResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );
  }

  @Test
  void pathfindingFallsIntoVerifiedWater() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, -4, 0, Blocks.WATER);
    accessor.setBlockAt(1, -5, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      TestPathConstraint.INSTANCE
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(
        accessor.build(),
        inventory,
        TestPathConstraint.INSTANCE
      ),
      new PosGoal(1, -4, 0)
    );

    var route = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      routeFinder.findRouteFuture(
        NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
      ).join()
    );

    assertEquals(1, route.actions().size());
    assertInstanceOf(MovementAction.class, route.actions().getFirst());
  }

  private record MovementLimitConstraint(
    PathConstraint delegate,
    int maximumFallDistance,
    int maximumParkourGap
  ) implements DelegatePathConstraint {}

  @Test
  void pathfindingThroughCarpet() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, 1, 0, Blocks.CARPET.white());
    accessor.setBlockAt(1, 1, 0, Blocks.CARPET.white());

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingThroughSnowLayers() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockStateAt(0, 1, 0, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
    accessor.setBlockStateAt(1, 1, 0, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingOnBottomSlabs() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockStateAt(0, 0, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    accessor.setBlockStateAt(1, 0, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 0, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 0, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingOnBottomStairs() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockStateAt(0, 0, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM));
    accessor.setBlockStateAt(1, 0, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 0, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 0, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @Test
  void pathfindingThroughClosedDoor() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockStateAt(1, 1, 0, Blocks.OAK_DOOR.defaultBlockState());
    accessor.setBlockStateAt(1, 2, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getFirst());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getLast());
  }

  @Test
  void pathfindingThroughClosedFenceGate() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockStateAt(1, 1, 0, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getFirst());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getLast());
  }

  @Test
  void pathfindingUpIntoClosedFenceGate() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 1, 0, Blocks.STONE);
    accessor.setBlockStateAt(1, 2, 0, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 2, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getFirst());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getLast());
  }

  @Test
  void pathfindingDownIntoClosedFenceGate() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockStateAt(1, 1, 0, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 2, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getFirst());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getLast());
  }

  @Test
  void pathfindingDownIntoClosedDoor() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockStateAt(1, 1, 0, Blocks.OAK_DOOR.defaultBlockState());
    accessor.setBlockStateAt(1, 2, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

    var inventory = new ProjectedInventory(List.of(), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(1, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 2, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getFirst());
    assertInstanceOf(InteractBlockAction.class, foundRouteResult.actions().getLast());
  }

  @Test
  void pathfindingUp() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(itemStack(Items.STONE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 2, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class, route);
    assertEquals(1, foundRouteResult.actions().size());
  }

  @ParameterizedTest
  @ValueSource(ints = {15, 20, 25})
  void pathfindingUpStacking(int amount) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(itemStack(Items.STONE, amount)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 21, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (amount < 20) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(20, foundRouteResult.actions().size());
    }
  }

  @Test
  void pathfindingDown() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, -1, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(itemStack(Items.DIAMOND_PICKAXE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 0, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class, route);
    assertEquals(2, foundRouteResult.actions().size());
    assertInstanceOf(BlockBreakAction.class, foundRouteResult.actions().get(0));
    assertInstanceOf(MovementAction.class, foundRouteResult.actions().get(1));
  }

  @Test
  void pathfindingThroughWallToMoveUp() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 1, 0, Blocks.STONE);
    accessor.setBlockAt(1, 2, 0, Blocks.STONE);
    accessor.setBlockAt(2, 0, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(itemStack(Items.DIAMOND_PICKAXE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(2, 1, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    var route = routeFinder.findRouteFuture(initialState).join();
    var foundRouteResult = assertInstanceOf(
      RouteFinder.FoundRouteResult.class, route);
    assertEquals(3, foundRouteResult.actions().size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pathfindingMoveUpSideUnsafe(boolean unsafe) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, 3, 0, Blocks.STONE);
    if (unsafe) {
      accessor.setBlockAt(1, 3, 0, Blocks.WATER);
    }

    var inventory = new ProjectedInventory(List.of(itemStack(Items.DIAMOND_PICKAXE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 2, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (unsafe) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(2, foundRouteResult.actions().size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pathfindingDigSideUnsafe(boolean unsafe) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, -1, 0, Blocks.STONE);
    if (unsafe) {
      accessor.setBlockAt(1, 0, 0, Blocks.LAVA);
    }

    var inventory = new ProjectedInventory(List.of(itemStack(Items.DIAMOND_PICKAXE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 0, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (unsafe) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(2, foundRouteResult.actions().size());
    }
  }

  @Test
  void pathfindingExtinguishesFireBeforeEnteringTheTargetBlock() {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 0, 0, Blocks.STONE);
    accessor.setBlockAt(1, 1, 0, Blocks.FIRE);

    var inventory = new ProjectedInventory(
      List.of(),
      TestMiningCostCalculator.INSTANCE,
      TestPathConstraint.INSTANCE
    );
    var routeFinder = new RouteFinder(
      new MinecraftGraph(
        accessor.build(),
        inventory,
        TestPathConstraint.INSTANCE
      ),
      new PosGoal(1, 1, 0)
    );

    var route = routeFinder.findRouteFuture(
      NodeState.forInfo(new SFVec3i(0, 1, 0), inventory)
    ).join();
    var foundRoute = assertInstanceOf(
      RouteFinder.FoundRouteResult.class,
      route
    );

    assertEquals(2, foundRoute.actions().size());
    assertInstanceOf(BlockBreakAction.class, foundRoute.actions().getFirst());
    assertInstanceOf(MovementAction.class, foundRoute.actions().getLast());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void pathfindingDigBelowUnsafe(int level) {
    var accessor = new TestBlockAccessorBuilder();
    accessor.setBlockAt(0, 0, 0, Blocks.STONE);
    accessor.setBlockAt(0, -1, 0, Blocks.LAVA);
    accessor.setBlockAt(0, -2, 0, Blocks.LAVA);
    accessor.setBlockAt(0, -3, 0, Blocks.LAVA);
    accessor.setBlockAt(0, -4, 0, Blocks.LAVA);

    accessor.setBlockAt(0, -level, 0, Blocks.STONE);

    var inventory = new ProjectedInventory(List.of(itemStack(Items.DIAMOND_PICKAXE)), TestMiningCostCalculator.INSTANCE, TestPathConstraint.INSTANCE);
    var routeFinder = new RouteFinder(new MinecraftGraph(
      accessor.build(),
      inventory,
      TestPathConstraint.INSTANCE), new PosGoal(0, 0, 0));

    var initialState = NodeState.forInfo(new SFVec3i(0, 1, 0), inventory);

    if (level > 1) {
      assertInstanceOf(
        RouteFinder.NoRouteFoundResult.class, routeFinder.findRouteFuture(initialState).join());
    } else {
      var route = routeFinder.findRouteFuture(initialState).join();
      var foundRouteResult = assertInstanceOf(
        RouteFinder.FoundRouteResult.class, route);
      assertEquals(2, foundRouteResult.actions().size());
    }
  }
}
