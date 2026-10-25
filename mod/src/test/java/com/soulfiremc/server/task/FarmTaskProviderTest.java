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
import com.soulfiremc.grpc.generated.FarmTask;
import com.soulfiremc.grpc.generated.FarmTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlStopReason;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class FarmTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void keepsConfirmedHarvestsAcrossExecutions() {
    var fixture = new Fixture();
    var factory = fixture.factory(3);
    var first = fixture.start(factory);
    fixture.tickUntil(first, () -> fixture.progress.getCurrent() == 1);
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    var recovered = fixture.start(factory);
    fixture.tickUntil(recovered, () -> recovered.result().isDone());
    assertEquals(3, ((FarmTaskResult) recovered.result().join()).getCropsHarvested());
    assertEquals(3, fixture.harvests);
  }

  @Test
  void reconcilesAnAcceptedInteractionBeforeItsConfirmationTick() {
    var fixture = new Fixture();
    var factory = fixture.factory(1);
    var first = fixture.start(factory);
    fixture.tickUntil(first, () -> fixture.harvests == 1);
    assertEquals(0, fixture.progress.getCurrent());
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    var recovered = fixture.start(factory);
    fixture.tickUntil(recovered, () -> recovered.result().isDone());
    assertEquals(1, ((FarmTaskResult) recovered.result().join()).getCropsHarvested());
    assertEquals(1, fixture.harvests);
  }

  @Test
  void waitsForTheRecoveredCropChunkBeforeInterpretingItsState() {
    var fixture = new Fixture();
    var factory = fixture.factory(1);
    var first = fixture.start(factory);
    fixture.tickUntil(first, () -> fixture.harvests == 1);
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    fixture.loaded = false;
    var recovered = fixture.start(factory);
    for (var tick = 0; tick < 10; tick++) {
      recovered.control().tick();
    }
    assertFalse(recovered.result().isDone());
    assertEquals(0, fixture.progress.getCurrent());
    fixture.loaded = true;
    fixture.tickUntil(recovered, () -> recovered.result().isDone());
    assertEquals(1, ((FarmTaskResult) recovered.result().join()).getCropsHarvested());
    assertEquals(1, fixture.harvests);
  }

  @Test
  void boundsWaitingForARecoveredChunkThatNeverArrives() {
    var fixture = new Fixture();
    var factory = fixture.factory(1);
    var first = fixture.start(factory);
    fixture.tickUntil(first, () -> fixture.harvests == 1);
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    fixture.loaded = false;
    var recovered = fixture.start(factory);
    for (var tick = 0; tick < 200 && !recovered.result().isDone(); tick++) {
      recovered.control().tick();
    }
    assertTrue(recovered.result().isDone());
    var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
    assertEquals(Status.Code.DEADLINE_EXCEEDED,
      ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    assertEquals(1, fixture.harvests);
  }

  @Test
  void rejectsResumingAnUnfinishedCropInAnotherDimension() {
    var fixture = new Fixture();
    var factory = fixture.factory(1);
    var first = fixture.start(factory);
    fixture.tickUntil(first, () -> fixture.harvests == 1);
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    fixture.nether = true;
    var recovered = fixture.start(factory);
    for (var tick = 0; tick < 200 && !recovered.result().isDone(); tick++) {
      recovered.control().tick();
    }
    assertTrue(recovered.result().isDone());
    var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
    assertEquals(Status.Code.FAILED_PRECONDITION,
      ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    assertEquals(1, fixture.harvests);
  }

  @ParameterizedTest
  @CsvSource({"0, true", "3, true", "3, false"})
  void preservesTheInitialSelectionAcrossRepeatedExecutions(int originalSlot, boolean restore) {
    var fixture = new Fixture();
    var factory = fixture.factory(3, restore);
    var firstInventory = mock(Inventory.class);
    when(firstInventory.getSelectedSlot()).thenReturn(originalSlot);
    var first = fixture.start(factory, firstInventory);
    fixture.tickUntil(first, () -> fixture.progress.getCurrent() == 1);
    first.control().onSuspended();
    first.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(firstInventory, never()).setSelectedSlot(anyInt());

    var secondInventory = mock(Inventory.class);
    when(secondInventory.getSelectedSlot()).thenReturn(2);
    var second = fixture.start(factory, secondInventory);
    fixture.tickUntil(second, () -> fixture.progress.getCurrent() == 2);
    second.control().onSuspended();
    second.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(secondInventory, never()).setSelectedSlot(anyInt());

    var thirdInventory = mock(Inventory.class);
    when(thirdInventory.getSelectedSlot()).thenReturn(6);
    var third = fixture.start(factory, thirdInventory);
    fixture.tickUntil(third, () -> third.result().isDone());
    third.control().onStopped(ControlStopReason.COMPLETED, null);
    if (restore) {
      verify(thirdInventory).setSelectedSlot(originalSlot);
    } else {
      verify(thirdInventory, never()).setSelectedSlot(anyInt());
    }
    assertEquals(3, fixture.harvests);
  }

  private static final class Fixture {
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    int harvests;
    boolean loaded = true;
    boolean nether;

    Fixture() {
      for (var x = 0; x < 4; x++) {
        blocks.put(new BlockPos(x, 64, 0), Blocks.SWEET_BERRY_BUSH.defaultBlockState()
          .setValue(SweetBerryBushBlock.AGE, 3));
      }
    }

    BotTaskProvider.ExecutionFactory factory(int maximumHarvests) {
      return factory(maximumHarvests, false);
    }

    BotTaskProvider.ExecutionFactory factory(int maximumHarvests, boolean restore) {
      return new FarmTaskProvider().prepare(FarmTask.newBuilder()
        .setCenter(BlockPosition.newBuilder().setY(64))
        .setRadius(4).setMaximumHarvests(maximumHarvests).setRestoreSelectedSlot(restore)
        .setCompleteWhenNoMatureCrops(true).build());
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory) {
      return start(factory, mock(Inventory.class));
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory, Inventory inventory) {
      var minecraft = mock(Minecraft.class);
      var player = mock(LocalPlayer.class);
      var level = mock(ClientLevel.class);
      var chunks = mock(ClientChunkCache.class);
      var gameMode = mock(MultiPlayerGameMode.class);
      minecraft.player = player;
      minecraft.level = level;
      minecraft.gameMode = gameMode;
      when(player.getInventory()).thenReturn(inventory);
      when(player.getEyePosition()).thenReturn(new Vec3(.5, 65.5, .5));
      when(player.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
      var held = mock(ItemStack.class);
      when(player.getMainHandItem()).thenReturn(held);
      when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
      when(level.dimension()).thenReturn(nether ? Level.NETHER : Level.OVERWORLD);
      when(level.getMinY()).thenReturn(-64);
      when(level.getMaxY()).thenReturn(320);
      when(level.getChunkSource()).thenReturn(chunks);
      when(chunks.hasChunk(anyInt(), anyInt())).thenAnswer(_ -> loaded);
      when(level.getBlockState(any())).thenAnswer(call ->
        blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
      when(gameMode.useItemOn(any(), any(), any())).thenAnswer(call -> {
        var position = ((BlockHitResult) call.getArgument(2)).getBlockPos();
        blocks.put(position, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
          .setValue(SweetBerryBushBlock.AGE, 1));
        harvests++;
        return InteractionResult.SUCCESS;
      });
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      try {
        var execution = factory.start(new BotTaskContext(null, bot, null,
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Optional.empty(),
          value -> progress = value));
        execution.control().onStarted();
        return execution;
      } catch (Exception exception) {
        throw new AssertionError(exception);
      }
    }

    void tickUntil(BotTaskExecution execution, BooleanSupplier condition) {
      for (var tick = 0; tick < 200 && !condition.getAsBoolean(); tick++) {
        execution.control().tick();
      }
      if (execution.result().isCompletedExceptionally()) {
        execution.result().join();
      }
      assertTrue(condition.getAsBoolean());
    }
  }
}
