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

import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.CollectBlocksCompletionReason;
import com.soulfiremc.grpc.generated.CollectBlocksTask;
import com.soulfiremc.grpc.generated.CollectBlocksTaskResult;
import com.soulfiremc.grpc.generated.IntRange;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BlockPredictionSupport;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.CompositeGoal;
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
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectBlocksRecoveryTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void keepsConfirmedProgressAcrossRepeatedExecutions() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var second = fixture.start(factory);
      fixture.until(second, () -> fixture.progress.getCurrent() == 2);
      second.control().onStopped(ControlStopReason.CANCELLED, null);
      assertEquals(3, fixture.finish(fixture.start(factory)).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reconcilesAnAcceptedNearbyBreakBeforeItsFinalTick(boolean reconnect) throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.removed == 1);
      first.control().onSuspended();
      BotTaskExecution recovered;
      if (reconnect) {
        first.control().onStopped(ControlStopReason.CANCELLED, null);
        recovered = fixture.start(factory);
      } else {
        first.control().onResumed();
        recovered = first;
      }
      assertEquals(3, fixture.finish(recovered).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void restartsAPartialBreakWithoutCountingItAsCleared() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.removeOnTick = false;
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.attempts > 0);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.removeOnTick = true;
      assertEquals(3, fixture.finish(fixture.start(factory)).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capturesConfirmedAndPendingPathBreaks(boolean confirmed) throws Exception {
    try (var fixture = new Fixture()) {
      fixture.preferPaths = true;
      fixture.confirmPath = confirmed;
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.removed == 1);
      first.control().onSuspended();
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.confirmPath = true;
      assertEquals(3, fixture.finish(fixture.start(factory)).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void retainsObservedRejectedTargets() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.rejected.add(new SFVec3i(1, 64, 0));
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.rejectedChecks > 0);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      assertEquals(3, fixture.finish(fixture.start(factory)).getBlocksBroken());
      assertEquals(1, fixture.constructions.get(new SFVec3i(1, 64, 0)));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "1, 1, 0, 0, true",
    "1, 3, 0, 0, false",
    "1, 1, 0, 1, false",
    "1, 0, 2, 0, false",
    "2, 1, 0, 1, true",
    "2, 0, 2, 0, true"
  })
  void nearbyMiningRespectsTheSearchSphere(int radius, int x, int y, int z, boolean collect) throws Exception {
    try (var fixture = new Fixture()) {
      var target = new BlockPos(x, 64 + y, z);
      fixture.blocks.clear();
      fixture.blocks.put(target, Blocks.DIRT.defaultBlockState());
      fixture.clipY = target.getY();
      var execution = fixture.start(fixture.factory(radius, target.getY(), 1));
      var result = fixture.finish(execution);
      assertEquals(collect ? CollectBlocksCompletionReason.COLLECT_BLOCKS_COMPLETION_REASON_TARGET_REACHED
        : CollectBlocksCompletionReason.COLLECT_BLOCKS_COMPLETION_REASON_NO_MATCHING_BLOCKS, result.getReason());
      assertEquals(collect ? 1 : 0, result.getBlocksBroken());
      assertEquals(!collect, fixture.blocks.containsKey(target));
    }
  }

  @Test
  void continuesWithDryCandidatesBeyondTheSubmergedPrefix() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.avoidSubmerged = true;
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.blocks.clear();
      for (var x = -4; x < 4; x++) {
        for (var z = -4; z < 4; z++) {
          if (x != 0 || z != 0) fixture.submerged.add(new BlockPos(x, 64, z));
        }
      }
      fixture.submerged.add(new BlockPos(4, 64, 0));
      for (var position : fixture.submerged) fixture.blocks.put(position, Blocks.DIRT.defaultBlockState());
      var dry = new BlockPos(6, 64, 0);
      fixture.blocks.put(dry, Blocks.DIRT.defaultBlockState());
      var result = fixture.finish(fixture.start(factory));
      assertEquals(2, result.getBlocksBroken());
      assertEquals(2, fixture.removed);
      assertFalse(fixture.blocks.containsKey(dry));
      assertEquals(fixture.submerged, fixture.blocks.keySet());
    }
  }

  @Test
  void waitsForTheLastObservedTargetChunk() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 10; tick++) recovered.control().tick();
      assertFalse(recovered.result().isDone());
      fixture.loaded = true;
      assertEquals(3, fixture.finish(recovered).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void waitsForAnOldPredictionBeforeCountingItsBreak() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.removed == 1);
      first.control().onSuspended();
      fixture.predictionPending = true;
      first.control().onResumed();
      for (var tick = 0; tick < 10; tick++) first.control().tick();
      assertFalse(first.result().isDone());
      assertEquals(1, fixture.removed);
      fixture.predictionPending = false;
      assertEquals(3, fixture.finish(first).getBlocksBroken());
      assertEquals(3, fixture.removed);
    }
  }

  @Test
  void boundsWaitingForMissingRecoveryChunks() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.loaded = false;
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 100; tick++) recovered.control().tick();
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.DEADLINE_EXCEEDED, ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    }
  }

  @Test
  void rejectsRecoveryInAnotherDimension() throws Exception {
    try (var fixture = new Fixture()) {
      var factory = fixture.factory();
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.nether = true;
      var recovered = fixture.start(factory);
      recovered.control().tick();
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.FAILED_PRECONDITION, ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inactiveCleanupDoesNotStopAnotherOwnersMining(boolean suspended) throws Exception {
    try (var fixture = new Fixture()) {
      var execution = fixture.start(fixture.factory(), suspended);
      if (suspended) execution.control().onSuspended();
      clearInvocations(fixture.gameMode);
      execution.control().onStopped(ControlStopReason.CANCELLED, null);
      verify(fixture.gameMode, never()).stopDestroyBlock();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final Set<SFVec3i> rejected = new HashSet<>();
    final Set<BlockPos> submerged = new HashSet<>();
    final Map<SFVec3i, Integer> constructions = new HashMap<>();
    final MockedConstruction<BlockBreakAction> breaks;
    final MockedStatic<PathExecutor> paths = mockStatic(PathExecutor.class);
    final MockedStatic<BlockPredictionSupport> predictions = mockStatic(BlockPredictionSupport.class);
    BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    MultiPlayerGameMode gameMode;
    boolean removeOnTick = true;
    boolean preferPaths;
    boolean confirmPath = true;
    boolean loaded = true;
    boolean nether;
    boolean predictionPending;
    boolean avoidSubmerged;
    int clipY = 64;
    int removed;
    int attempts;
    int rejectedChecks;

    Fixture() {
      for (var x = 1; x <= 5; x++) blocks.put(new BlockPos(x, 64, 0), Blocks.DIRT.defaultBlockState());
      predictions.when(() -> BlockPredictionSupport.isClearedBreakTarget(any())).thenCallRealMethod();
      predictions.when(() -> BlockPredictionSupport.hasPendingPrediction(any(), any())).thenAnswer(_ -> predictionPending);
      breaks = mockConstruction(BlockBreakAction.class, (action, context) -> {
        var position = (SFVec3i) context.arguments().getFirst();
        constructions.merge(position, 1, Integer::sum);
        var attempted = new AtomicBoolean();
        when(action.breakAttempted()).thenAnswer(_ -> attempted.get());
        when(action.isCompleted(any())).thenAnswer(_ -> attempted.get() && !blocks.containsKey(position.toBlockPos()));
        when(action.isRejected(any())).thenAnswer(_ -> {
          if (!rejected.contains(position)) return false;
          rejectedChecks++;
          return true;
        });
        when(action.getAllowedTicks()).thenReturn(20);
        doAnswer(_ -> {
          attempts++;
          attempted.set(true);
          if (removeOnTick && blocks.remove(position.toBlockPos()) != null) removed++;
          return null;
        }).when(action).tick(any());
      });
      paths.when(() -> PathExecutor.createPathfinding(any(), any(), any())).thenAnswer(call -> {
        var target = ((CompositeGoal) call.getArgument(1)).goals().stream()
          .filter(goal -> goal instanceof BreakBlockPosGoal).map(goal -> ((BreakBlockPosGoal) goal).goal()).findFirst().orElseThrow();
        var path = mock(PathExecutor.class);
        var done = new CompletableFuture<Void>();
        var completed = new HashSet<SFVec3i>();
        var attempted = new AtomicBoolean();
        when(path.isDone()).thenAnswer(_ -> done.isDone());
        when(path.completion()).thenReturn(done);
        when(path.progress()).thenReturn(new PathExecutor.Progress(false, 0, 1));
        when(path.completedBlockBreaks()).thenAnswer(_ -> Set.copyOf(completed));
        when(path.pendingBlockBreak()).thenAnswer(_ -> attempted.get() && !completed.contains(target) ? Optional.of(target) : Optional.empty());
        doAnswer(_ -> {
          if (blocks.remove(target.toBlockPos()) != null) removed++;
          attempted.set(true);
          if (confirmPath) {
            completed.add(target);
            done.complete(null);
          }
          return null;
        }).when(path).tick();
        return path;
      });
    }

    BotTaskProvider.ExecutionFactory factory() {
      return factory(6, 64, 3);
    }

    BotTaskProvider.ExecutionFactory factory(int radius, int targetY, int count) {
      return new CollectBlocksTaskProvider().prepare(CollectBlocksTask.newBuilder()
        .addBlockIds("minecraft:dirt").setCount(count).setSearchRadius(radius).setAvoidSubmergedTargets(avoidSubmerged)
        .setTargetYRange(IntRange.newBuilder().setMinimum(targetY).setMaximum(targetY)).build());
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory) throws Exception {
      return start(factory, true);
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory, boolean admit) throws Exception {
      var minecraft = mock(Minecraft.class);
      var level = mock(ClientLevel.class);
      var chunks = mock(ClientChunkCache.class);
      var player = mock(LocalPlayer.class);
      gameMode = mock(MultiPlayerGameMode.class);
      var menu = mock(InventoryMenu.class);
      var inventoryField = LocalPlayer.class.getField("inventoryMenu");
      inventoryField.setAccessible(true);
      inventoryField.set(player, menu);
      var slotsField = InventoryMenu.class.getField("slots");
      slotsField.setAccessible(true);
      slotsField.set(menu, NonNullList.create());
      when(player.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
      when(player.getEyePosition()).thenReturn(new Vec3(.5, preferPaths ? 80.5 : 65.5, .5));
      when(level.dimension()).thenReturn(nether ? Level.NETHER : Level.OVERWORLD);
      when(level.getMinY()).thenReturn(-64);
      when(level.getMaxY()).thenReturn(320);
      when(level.getChunkSource()).thenReturn(chunks);
      when(chunks.hasChunk(anyInt(), anyInt())).thenAnswer(_ -> loaded);
      when(level.getSectionIndexFromSectionY(anyInt())).thenAnswer(call -> (int) call.getArgument(0) + 4);
      var chunkViews = new HashMap<ChunkPos, LevelChunk>();
      var sectionViews = new HashMap<BlockPos, LevelChunkSection>();
      when(chunks.getChunk(anyInt(), anyInt(), any(), anyBoolean())).thenAnswer(call -> {
        if (!loaded) return null;
        var chunkPos = new ChunkPos((int) call.getArgument(0), (int) call.getArgument(1));
        return chunkViews.computeIfAbsent(chunkPos, p -> {
          var chunk = mock(LevelChunk.class);
          when(chunk.getSection(anyInt())).thenAnswer(sectionCall -> {
            var key = new BlockPos(p.x(), (int) sectionCall.getArgument(0) - 4, p.z());
            return sectionViews.computeIfAbsent(key, k -> {
              var section = mock(LevelChunkSection.class);
              when(section.hasOnlyAir()).thenAnswer(_ -> blocks.keySet().stream().noneMatch(b ->
                b.getX() >> 4 == k.getX() && b.getY() >> 4 == k.getY() && b.getZ() >> 4 == k.getZ()));
              when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(stateCall ->
                blocks.getOrDefault(new BlockPos((k.getX() << 4) + (int) stateCall.getArgument(0),
                  (k.getY() << 4) + (int) stateCall.getArgument(1), (k.getZ() << 4) + (int) stateCall.getArgument(2)),
                  Blocks.AIR.defaultBlockState()));
              return section;
            });
          });
          return chunk;
        });
      });
      when(level.getBlockState(any())).thenAnswer(call -> blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
      when(level.getFluidState(any())).thenAnswer(call -> submerged.contains(((BlockPos) call.getArgument(0)).below())
        ? Fluids.WATER.defaultFluidState() : Fluids.EMPTY.defaultFluidState());
      when(level.clip(any())).thenAnswer(call -> {
        var to = ((ClipContext) call.getArgument(0)).getTo();
        return new BlockHitResult(to, Direction.UP, BlockPos.containing(to.x, clipY, to.z), false);
      });
      minecraft.player = player;
      minecraft.level = level;
      minecraft.gameMode = gameMode;
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      var execution = factory.start(new BotTaskContext(null, bot, null, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), value -> progress = value));
      if (admit) execution.control().onStarted();
      return execution;
    }

    CollectBlocksTaskResult finish(BotTaskExecution execution) {
      until(execution, () -> execution.result().isDone());
      return (CollectBlocksTaskResult) execution.result().join();
    }

    void until(BotTaskExecution execution, BooleanSupplier condition) {
      for (var tick = 0; tick < 300 && !condition.getAsBoolean() && !execution.result().isDone(); tick++) execution.control().tick();
      if (execution.result().isCompletedExceptionally()) execution.result().join();
      assertTrue(condition.getAsBoolean());
    }

    @Override
    public void close() {
      breaks.close();
      predictions.close();
      paths.close();
    }
  }
}
