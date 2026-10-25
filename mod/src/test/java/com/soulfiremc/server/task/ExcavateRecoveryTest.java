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
import com.soulfiremc.grpc.generated.ExcavateTask;
import com.soulfiremc.grpc.generated.ExcavateTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ExcavateRecoveryTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void keepsTheOriginalRegionAndAccountingAcrossRepeatedConnections(int maximumBlocks) throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(maximumBlocks);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var second = fixture.start(factory);
      second.control().onStopped(ControlStopReason.CANCELLED, null);
      var result = fixture.finish(fixture.start(factory));
      var expected = maximumBlocks == 0 ? 5 : maximumBlocks;
      assertEquals(expected, result.getBlocksBroken());
      assertEquals(12, result.getBlocksSkipped());
      assertEquals(0, result.getUnreachableBlocks());
      assertEquals(expected, fixture.removed);
      if (maximumBlocks > 0) {
        assertTrue(fixture.blocks.containsKey(new BlockPos(12, 64, 0)));
        assertTrue(fixture.blocks.containsKey(new BlockPos(16, 64, 0)));
      }
    }
  }

  @Test
  void reconcilesABlockClearedBeforeItsFinalControlTick() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(3);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.removed == 1);
      assertEquals(0, fixture.progress.getCurrent());
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var result = fixture.finish(fixture.start(factory));
      assertEquals(3, result.getBlocksBroken());
      assertEquals(12, result.getBlocksSkipped());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void retainsConfirmedUnreachableWorkWithoutRetryingIt() throws Exception {
    try (var fixture = new Fixture()) {
      var failedTarget = new BlockPos(0, 64, 0);
      fixture.failures.add(failedTarget);
      var factory = fixture.factory(3);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var result = fixture.finish(fixture.start(factory));
      assertEquals(2, result.getBlocksBroken());
      assertEquals(1, result.getUnreachableBlocks());
      assertEquals(1, fixture.attempts.get(failedTarget));
    }
  }

  @Test
  void waitsForRecoveredPendingChunks() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(3);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 10; tick++) recovered.control().tick();
      assertFalse(recovered.result().isDone());
      assertEquals(1, fixture.removed);
      fixture.loaded = true;
      assertEquals(3, fixture.finish(recovered).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void boundsWaitingForMissingPendingChunks() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(3);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 100; tick++) recovered.control().tick();
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.DEADLINE_EXCEEDED,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
      assertEquals(1, fixture.removed);
    }
  }

  @Test
  void completesFinishedWorkWithoutWaitingForOldChunks() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      first.control().tick();
      first.control().tick();
      first.control().tick();
      assertFalse(first.result().isDone());
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory);
      recovered.control().tick();
      assertTrue(recovered.result().isDone());
      assertEquals(1, ((ExcavateTaskResult) recovered.result().join()).getBlocksBroken());
      assertEquals(1, fixture.removed);
    }
  }

  @Test
  void rejectsRecoveryInAnotherDimension() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory(3);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.nether = true;
      var recovered = fixture.start(factory);
      recovered.control().tick();
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.FAILED_PRECONDITION,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
      assertEquals(1, fixture.removed);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final Set<BlockPos> failures = new HashSet<>();
    final Map<BlockPos, Integer> attempts = new HashMap<>();
    final MockedStatic<PathExecutor> paths = mockStatic(PathExecutor.class);
    BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    boolean loaded = true;
    boolean nether;
    int removed;

    Fixture() {
      for (var x = 0; x <= 16; x += 4) blocks.put(new BlockPos(x, 64, 0), Blocks.STONE.defaultBlockState());
      paths.when(() -> PathExecutor.createPathfinding(any(), any(), any())).thenAnswer(call -> {
        var target = ((BreakBlockPosGoal) call.getArgument(1)).goal().toBlockPos();
        var path = mock(PathExecutor.class);
        var completion = new CompletableFuture<Void>();
        when(path.isDone()).thenAnswer(_ -> completion.isDone());
        when(path.completion()).thenReturn(completion);
        when(path.progress()).thenReturn(new PathExecutor.Progress(false, 0, 1));
        doAnswer(_ -> {
          attempts.merge(target, 1, Integer::sum);
          if (failures.contains(target)) {
            completion.completeExceptionally(new IllegalStateException("Unreachable target"));
          } else {
            if (blocks.remove(target) != null) removed++;
            completion.complete(null);
          }
          return null;
        }).when(path).tick();
        return path;
      });
    }

    BotTaskProvider.ExecutionFactory factory(int maximumBlocks) {
      return new ExcavateTaskProvider().prepare(ExcavateTask.newBuilder()
        .setCornerA(BlockPosition.newBuilder().setY(64))
        .setCornerB(BlockPosition.newBuilder().setX(16).setY(64))
        .setMaximumBlocks(maximumBlocks).build());
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory) throws Exception {
      var minecraft = mock(Minecraft.class);
      var level = mock(ClientLevel.class);
      var player = mock(LocalPlayer.class);
      var chunks = mock(ClientChunkCache.class);
      when(level.dimension()).thenReturn(nether ? Level.NETHER : Level.OVERWORLD);
      when(level.getMinY()).thenReturn(-64);
      when(level.getMaxY()).thenReturn(320);
      when(level.getChunkSource()).thenReturn(chunks);
      when(chunks.hasChunk(anyInt(), anyInt())).thenAnswer(_ -> loaded);
      when(level.getSectionIndexFromSectionY(anyInt())).thenAnswer(call -> (int) call.getArgument(0) + 4);
      when(chunks.getChunk(anyInt(), anyInt(), any(), anyBoolean())).thenAnswer(call -> {
        if (!loaded) return null;
        int chunkX = call.getArgument(0), chunkZ = call.getArgument(1);
        var chunk = mock(LevelChunk.class);
        when(chunk.getSection(anyInt())).thenAnswer(sectionCall -> {
          var sectionY = (int) sectionCall.getArgument(0) - 4;
          var section = mock(LevelChunkSection.class);
          when(section.hasOnlyAir()).thenAnswer(_ -> blocks.keySet().stream().noneMatch(position ->
            position.getX() >> 4 == chunkX && position.getY() >> 4 == sectionY && position.getZ() >> 4 == chunkZ));
          when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(blockCall ->
            blocks.getOrDefault(new BlockPos((chunkX << 4) + (int) blockCall.getArgument(0),
              (sectionY << 4) + (int) blockCall.getArgument(1), (chunkZ << 4) + (int) blockCall.getArgument(2)), Blocks.AIR.defaultBlockState()));
          return section;
        });
        return chunk;
      });
      when(level.getBlockState(any())).thenAnswer(call -> blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
      when(player.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
      minecraft.level = level;
      minecraft.player = player;
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      var execution = factory.start(new BotTaskContext(null, bot, null,
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), value -> progress = value));
      execution.control().onStarted();
      return execution;
    }

    ExcavateTaskResult finish(BotTaskExecution execution) {
      until(execution, () -> execution.result().isDone());
      return (ExcavateTaskResult) execution.result().join();
    }

    void until(BotTaskExecution execution, BooleanSupplier condition) {
      for (var tick = 0; tick < 200 && !condition.getAsBoolean() && !execution.result().isDone(); tick++) execution.control().tick();
      if (execution.result().isCompletedExceptionally()) execution.result().join();
      assertTrue(condition.getAsBoolean());
    }

    @Override
    public void close() {
      paths.close();
    }
  }
}
