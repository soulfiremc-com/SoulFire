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

import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcavateRegionTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(ints = {-64, 80})
  void skipsBlockReadsInEmptySections(int minimumY) {
    var world = new TestWorld();
    var region = ExcavateTaskProvider.selectRegion(world.level, BlockPos.ZERO,
      new BlockPos(0, minimumY, 0), new BlockPos(31, minimumY + 31, 31), 0);
    assertTrue(region.selected().isEmpty());
    assertEquals(32768, region.skipped());
    assertFalse(region.limited());
    assertEquals(0, world.levelReads + world.sectionReads);
    verify(world.cache, never()).getChunk(anyInt(), anyInt(), any(), eq(true));
  }

  @Test
  void clipsMixedSectionsAndPreservesUndiggableAndReplaceableCounts() {
    var world = new TestWorld();
    var stone = new BlockPos(-1, -1, -1);
    world.blocks.put(stone, Blocks.STONE.defaultBlockState());
    world.blocks.put(BlockPos.ZERO, Blocks.BEDROCK.defaultBlockState());
    world.blocks.put(new BlockPos(1, 1, 1), Blocks.WATER.defaultBlockState());
    world.blocks.put(new BlockPos(2, 2, 2), Blocks.SHORT_GRASS.defaultBlockState());
    world.blocks.put(new BlockPos(18, 18, 18), Blocks.STONE.defaultBlockState());
    var region = ExcavateTaskProvider.selectRegion(world.level, BlockPos.ZERO,
      new BlockPos(-2, -2, -2), new BlockPos(17, 17, 17), 0);
    assertEquals(List.of(SFVec3i.fromInt(stone)), region.selected());
    assertEquals(Set.copyOf(region.selected()), region.diggable());
    assertEquals(7999, region.skipped());
  }

  @Test
  void preservesNearestSelectionAndXyzTiesAcrossSectionBoundaries() {
    var world = new TestWorld();
    var ordered = List.of(new SFVec3i(14, 64, 15), new SFVec3i(15, 64, 16),
      new SFVec3i(15, 65, 15), new SFVec3i(16, 64, 15));
    for (var position : ordered) world.blocks.put(position.toBlockPos(), Blocks.STONE.defaultBlockState());
    var region = ExcavateTaskProvider.selectRegion(world.level, new BlockPos(15, 64, 15),
      new BlockPos(14, 64, 14), new BlockPos(16, 65, 16), 2);
    assertEquals(ordered.subList(0, 2), region.selected());
    assertEquals(Set.copyOf(ordered), region.diggable());
    assertEquals(14, region.skipped());
    assertTrue(region.limited());
  }

  @Test
  void observesSectionChangesWithoutCachingAbsenceOrMutablePositions() {
    var world = new TestWorld();
    var position = new BlockPos(1, 64, 1);
    var from = new BlockPos(0, 64, 0);
    var to = new BlockPos(2, 64, 2);
    assertTrue(ExcavateTaskProvider.selectRegion(world.level, from, from, to, 0).selected().isEmpty());
    world.blocks.put(position, Blocks.STONE.defaultBlockState());
    var first = ExcavateTaskProvider.selectRegion(world.level, from, from, to, 0);
    world.blocks.remove(position);
    assertTrue(ExcavateTaskProvider.selectRegion(world.level, from, from, to, 0).selected().isEmpty());
    assertEquals(List.of(SFVec3i.fromInt(position)), first.selected());
    assertEquals(8, first.skipped());
  }

  @Test
  void rejectsMissingChunksEvenWhenTheRegionWouldBeEmpty() {
    var world = new TestWorld();
    world.loaded.remove(new ChunkPos(-1, 0));
    var failure = assertThrows(StatusRuntimeException.class, () ->
      ExcavateTaskProvider.selectRegion(world.level, BlockPos.ZERO,
        new BlockPos(-1, 64, 0), new BlockPos(1, 64, 0), 0));
    assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    verify(world.cache, never()).getChunk(anyInt(), anyInt(), any(), eq(true));
  }

  private record SectionKey(int x, int y, int z) {}

  private static final class TestWorld {
    final ClientLevel level = mock(ClientLevel.class);
    final ClientChunkCache cache = mock(ClientChunkCache.class);
    final Set<ChunkPos> loaded = new HashSet<>();
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
    final Map<SectionKey, LevelChunkSection> sections = new HashMap<>();
    int levelReads;
    int sectionReads;

    TestWorld() {
      for (var x = -2; x <= 2; x++) for (var z = -2; z <= 2; z++) loaded.add(new ChunkPos(x, z));
      when(level.dimension()).thenReturn(Level.OVERWORLD);
      when(level.getChunkSource()).thenReturn(cache);
      when(level.getSectionIndexFromSectionY(anyInt())).thenAnswer(call -> (int) call.getArgument(0) + 4);
      when(cache.hasChunk(anyInt(), anyInt())).thenAnswer(call -> loaded.contains(new ChunkPos(call.getArgument(0), call.getArgument(1))));
      when(cache.getChunk(anyInt(), anyInt(), any(), anyBoolean())).thenAnswer(call -> {
        var position = new ChunkPos((int) call.getArgument(0), (int) call.getArgument(1));
        return loaded.contains(position) ? chunks.computeIfAbsent(position, this::chunk) : null;
      });
      when(level.getBlockState(any())).thenAnswer(call -> {
        levelReads++;
        return blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState());
      });
    }

    LevelChunk chunk(ChunkPos position) {
      var chunk = mock(LevelChunk.class);
      when(chunk.getSection(anyInt())).thenAnswer(call -> sections.computeIfAbsent(
        new SectionKey(position.x(), (int) call.getArgument(0) - 4, position.z()), this::section));
      return chunk;
    }

    LevelChunkSection section(SectionKey key) {
      var section = mock(LevelChunkSection.class);
      when(section.hasOnlyAir()).thenAnswer(_ -> blocks.entrySet().stream().noneMatch(entry ->
        !entry.getValue().isAir() && entry.getKey().getX() >> 4 == key.x()
          && entry.getKey().getY() >> 4 == key.y() && entry.getKey().getZ() >> 4 == key.z()));
      when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
        sectionReads++;
        var position = new BlockPos((key.x() << 4) + (int) call.getArgument(0),
          (key.y() << 4) + (int) call.getArgument(1), (key.z() << 4) + (int) call.getArgument(2));
        return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
      });
      return section;
    }
  }
}
