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
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectBlocksScanTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(ints = {-64, 80})
  void skipsPerBlockReadsInEmptySections(int y) {
    var world = new TestWorld();
    assertEquals(Set.of(), world.scan(new BlockPos(0, y, 0), 16));
    assertEquals(0, world.levelReads);
    assertEquals(0, world.sectionReads);
  }

  @Test
  void clipsNegativeSectionsToTheInclusiveSphere() {
    var world = new TestWorld();
    var expected = Set.of(new BlockPos(-19, 0, -17), new BlockPos(-17, -2, -17),
      new BlockPos(-17, 2, -17), new BlockPos(-16, 1, -16));
    for (var p : expected) world.blocks.put(p, Blocks.DIRT.defaultBlockState());
    world.blocks.put(new BlockPos(-15, 1, -17), Blocks.DIRT.defaultBlockState());
    world.blocks.put(new BlockPos(-17, -3, -17), Blocks.DIRT.defaultBlockState());
    assertEquals(expected, world.scan(new BlockPos(-17, 0, -17), 2));
  }

  @Test
  void skipsMissingChunksWithoutLoadingThem() {
    var world = new TestWorld();
    world.loaded.remove(new ChunkPos(-1, 0));
    world.blocks.put(new BlockPos(-1, 0, 0), Blocks.DIRT.defaultBlockState());
    world.blocks.put(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState());
    assertEquals(Set.of(new BlockPos(1, 0, 0)), world.scan(BlockPos.ZERO, 2));
    verify(world.cache, never()).getChunk(anyInt(), anyInt(), any(), eq(true));
  }

  @Test
  void readsLiveSectionsAndReturnsIndependentPositions() {
    var world = new TestWorld();
    var first = new BlockPos(0, 64, 0);
    var second = new BlockPos(1, 64, 0);
    world.blocks.put(first, Blocks.DIRT.defaultBlockState());
    var before = world.scan(first, 2);
    world.blocks.clear();
    world.blocks.put(second, Blocks.DIRT.defaultBlockState());
    assertEquals(Set.of(second), world.scan(first, 2));
    assertEquals(Set.of(first), before);
    world.blocks.clear();
    assertEquals(Set.of(), world.scan(first, 2));
  }

  @Test
  void appliesThePredicateWithinMixedSections() {
    var world = new TestWorld();
    world.blocks.put(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
    world.blocks.put(new BlockPos(0, 65, 0), Blocks.DIRT.defaultBlockState());
    world.blocks.put(new BlockPos(0, 66, 0), Blocks.DIRT.defaultBlockState());
    var selected = CollectBlocksTaskProvider.scanCandidates(world.level, new BlockPos(0, 64, 0), 3,
      (position, state) -> position.getY() == 65 && state.is(Blocks.DIRT));
    assertEquals(Set.of(new SFVec3i(0, 65, 0)), selected);
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
      when(level.getMinY()).thenReturn(-64);
      when(level.getMaxY()).thenReturn(320);
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

    Set<BlockPos> scan(BlockPos origin, int radius) {
      var result = new HashSet<BlockPos>();
      for (var p : CollectBlocksTaskProvider.scanCandidates(level, origin, radius,
        (_, state) -> state.is(Blocks.DIRT))) result.add(p.toBlockPos());
      return result;
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
