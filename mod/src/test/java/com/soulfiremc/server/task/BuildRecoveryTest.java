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
package com.soulfiremc.server.task;

import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.BuildBlock;
import com.soulfiremc.grpc.generated.BuildBlockStatus;
import com.soulfiremc.grpc.generated.BuildMaterialSubstitution;
import com.soulfiremc.grpc.generated.BuildOffset;
import com.soulfiremc.grpc.generated.BuildTask;
import com.soulfiremc.grpc.generated.BuildTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotInteractionSupport;
import com.soulfiremc.server.bot.BotRotationController;
import com.soulfiremc.server.bot.ControlState;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildRecoveryTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void keepsConfirmedMixedOutcomesAndInitialSelection() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.blocks.put(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
      var factory = fixture.factory("stone", "gold_block", "stone", "stone");
      var first = fixture.start(factory, 3);
      fixture.until(first, () -> fixture.progress.getCurrent() == 3);
      first.control().onSuspended();
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var second = fixture.start(factory, 2);
      second.control().onSuspended();
      second.control().onStopped(ControlStopReason.CANCELLED, null);
      var third = fixture.start(factory, 6);
      var result = fixture.finish(third);
      third.control().onStopped(ControlStopReason.COMPLETED, null);
      assertEquals(2, result.getBlocksPlaced());
      assertEquals(1, result.getBlocksAlreadyCorrect());
      assertEquals(1, result.getBlocksFailed());
      assertEquals(List.of(BuildBlockStatus.BUILD_BLOCK_STATUS_ALREADY_CORRECT,
        BuildBlockStatus.BUILD_BLOCK_STATUS_MISSING_MATERIAL,
        BuildBlockStatus.BUILD_BLOCK_STATUS_PLACED,
        BuildBlockStatus.BUILD_BLOCK_STATUS_PLACED),
        result.getOutcomesList().stream().map(outcome -> outcome.getStatus()).toList());
      verify(fixture.inventory).setSelectedSlot(3);
      assertEquals(2, fixture.interactions);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"stone", "gold_block"})
  void reconcilesAPendingPlacementAgainstTheFreshWorldView(String requestedBlock) throws Exception {
    try (var fixture = new Fixture()) {
      var input = BuildTask.newBuilder().setOrigin(BlockPosition.newBuilder().setY(64))
        .addBlocks(BuildBlock.newBuilder().setBlockId("minecraft:" + requestedBlock))
        .addSubstitutions(BuildMaterialSubstitution.newBuilder().setSourceBlockId("minecraft:gold_block")
          .addReplacementBlockIds("minecraft:stone")).build();
      var factory = new BuildTaskProvider().prepare(input);
      var first = fixture.start(factory, 0);
      fixture.until(first, () -> fixture.interactions == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var result = fixture.finish(fixture.start(factory, 2));
      assertEquals(1, result.getBlocksPlaced());
      assertEquals(0, result.getBlocksAlreadyCorrect());
      assertEquals("minecraft:" + requestedBlock, result.getOutcomes(0).getRequestedBlockId());
      assertEquals("minecraft:stone", result.getOutcomes(0).getPlacedBlockId());
      assertEquals(1, fixture.interactions);
    }
  }

  @Test
  void waitsForTheRecoveredChunkBeforeReconciling() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory("stone");
      var first = fixture.start(factory, 0);
      fixture.until(first, () -> fixture.interactions == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory, 2);
      for (var tick = 0; tick < 10; tick++) recovered.control().tick();
      assertFalse(recovered.result().isDone());
      fixture.loaded = true;
      assertEquals(1, fixture.finish(recovered).getBlocksPlaced());
      assertEquals(1, fixture.interactions);
    }
  }

  @Test
  void boundsWaitingForAnUnobservableRecoveredChunk() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory("stone");
      var first = fixture.start(factory, 0);
      fixture.until(first, () -> fixture.interactions == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory, 2);
      for (var tick = 0; tick < 100; tick++) recovered.control().tick();
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.DEADLINE_EXCEEDED,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    }
  }

  @Test
  void preservesTheUnconfirmedPlacementBudgetWithoutRepeatingItsInteraction() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.acceptPlacement = false;
      var factory = fixture.factory("stone");
      var first = fixture.start(factory, 0);
      fixture.until(first, () -> fixture.interactions == 1);
      for (var tick = 0; tick < 20; tick++) first.control().tick();
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var recovered = fixture.start(factory, 2);
      for (var tick = 0; tick < 21; tick++) recovered.control().tick();
      assertTrue(recovered.result().isDone());
      assertEquals(1, ((BuildTaskResult) recovered.result().join()).getBlocksFailed());
      assertEquals(1, fixture.interactions);
    }
  }

  @Test
  void reconcilesAnIncorrectBlockClearedByTheOldPath() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.blocks.put(new BlockPos(0, 64, 0), Blocks.DIRT.defaultBlockState());
      fixture.pathReady = false;
      var factory = fixture.factory("stone");
      var first = fixture.start(factory, 0);
      first.control().tick();
      fixture.blocks.remove(new BlockPos(0, 64, 0));
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.pathReady = true;
      var result = fixture.finish(fixture.start(factory, 2));
      assertEquals(1, result.getIncorrectBlocksBroken());
      assertEquals(1, result.getBlocksPlaced());
    }
  }

  @Test
  void rejectsRecoveryInAnotherDimension() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory("stone");
      var first = fixture.start(factory, 0);
      fixture.until(first, () -> fixture.interactions == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.nether = true;
      var recovered = fixture.start(factory, 2);
      recovered.control().tick();
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.FAILED_PRECONDITION,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
      assertEquals(1, fixture.interactions);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final MockedStatic<BotInteractionSupport> interactionsSupport = mockStatic(BotInteractionSupport.class);
    final MockedStatic<TaskInventorySupport> items = mockStatic(TaskInventorySupport.class);
    final MockedStatic<PathExecutor> paths = mockStatic(PathExecutor.class);
    final MockedStatic<BlockPredictionSupport> predictions = mockStatic(BlockPredictionSupport.class);
    BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    Inventory inventory;
    boolean loaded = true;
    boolean nether;
    boolean pathReady = true;
    boolean acceptPlacement = true;
    int interactions;

    Fixture() {
      var stone = new ItemStack(Holder.direct(Items.STONE, DataComponentMap.EMPTY), 64);
      items.when(() -> TaskInventorySupport.findInventorySlot(any(), any())).thenAnswer(call -> {
        Predicate<ItemStack> selector = call.getArgument(1);
        return selector.test(stone) ? OptionalInt.of(2) : OptionalInt.empty();
      });
      interactionsSupport.when(() -> BotInteractionSupport.withSneaking(any(), anyBoolean(), any()))
        .thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
      items.when(() -> TaskInventorySupport.ensureHolding(any(), any())).thenReturn(true);
      paths.when(() -> PathExecutor.createPathfinding(any(), any(), any())).thenAnswer(_ -> {
        var path = mock(PathExecutor.class);
        when(path.isDone()).thenAnswer(_ -> pathReady);
        when(path.completion()).thenReturn(CompletableFuture.completedFuture(null));
        return path;
      });
      predictions.when(() -> BlockPredictionSupport.isClearedBreakTarget(any())).thenCallRealMethod();
    }

    BotTaskProvider.ExecutionFactory factory(String... ids) {
      var input = BuildTask.newBuilder().setOrigin(BlockPosition.newBuilder().setY(64))
        .setRestoreSelectedSlot(true).setBreakIncorrectBlocks(true);
      for (var index = 0; index < ids.length; index++) {
        input.addBlocks(BuildBlock.newBuilder().setBlockId("minecraft:" + ids[index])
          .setOffset(BuildOffset.newBuilder().setX(index)));
      }
      return new BuildTaskProvider().prepare(input.build());
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory, int selectedSlot) throws Exception {
      var minecraft = mock(Minecraft.class);
      var level = mock(ClientLevel.class);
      var player = mock(LocalPlayer.class);
      var chunks = mock(ClientChunkCache.class);
      var gameMode = mock(MultiPlayerGameMode.class);
      inventory = mock(Inventory.class);
      when(inventory.getSelectedSlot()).thenReturn(selectedSlot);
      when(player.getInventory()).thenReturn(inventory);
      when(player.getItemInHand(any())).thenReturn(mock(ItemStack.class));
      when(level.dimension()).thenReturn(nether ? Level.NETHER : Level.OVERWORLD);
      when(level.getChunkSource()).thenReturn(chunks);
      when(chunks.hasChunk(anyInt(), anyInt())).thenAnswer(_ -> loaded);
      when(level.getBlockState(any())).thenAnswer(call -> {
        BlockPos position = call.getArgument(0);
        return blocks.getOrDefault(position, position.getY() == 63
          ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
      });
      when(gameMode.useItemOn(any(), any(), any())).thenAnswer(call -> {
        interactions++;
        var hit = (BlockHitResult) call.getArgument(2);
        if (acceptPlacement) blocks.put(hit.getBlockPos().relative(hit.getDirection()), Blocks.STONE.defaultBlockState());
        return InteractionResult.SUCCESS;
      });
      minecraft.level = level;
      minecraft.player = player;
      minecraft.gameMode = gameMode;
      var bot = mock(BotConnection.class);
      var rotation = mock(BotRotationController.class);
      when(rotation.isFacing(any())).thenReturn(true);
      when(bot.rotationControl()).thenReturn(rotation);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      when(bot.controlState()).thenReturn(new ControlState());
      var execution = factory.start(new BotTaskContext(null, bot, null,
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), value -> progress = value));
      execution.control().onStarted();
      return execution;
    }

    BuildTaskResult finish(BotTaskExecution execution) {
      until(execution, () -> execution.result().isDone());
      return (BuildTaskResult) execution.result().join();
    }

    void until(BotTaskExecution execution, BooleanSupplier condition) {
      for (var tick = 0; tick < 200 && !condition.getAsBoolean() && !execution.result().isDone(); tick++) execution.control().tick();
      if (execution.result().isCompletedExceptionally()) execution.result().join();
      assertTrue(condition.getAsBoolean());
    }

    @Override
    public void close() {
      predictions.close();
      paths.close();
      items.close();
      interactionsSupport.close();
    }
  }
}
