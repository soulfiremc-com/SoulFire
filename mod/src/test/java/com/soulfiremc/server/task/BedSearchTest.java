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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedSearchTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void skipsPerBlockReadsInBedFreeSections() {
    var world = new TestWorld();
    assertNull(SleepTaskProvider.findNearestBed(world.level, new BlockPos(0, 64, 0), 8));
    assertEquals(0, world.levelReads + world.sectionReads);
  }

  @Test
  void findsTheNearestBedAcrossNegativeChunksAndVerticalSections() {
    var world = new TestWorld();
    var nearest = new BlockPos(0, 64, 0);
    world.addBed(nearest);
    world.addBed(new BlockPos(-10, 63, -1));
    assertEquals(nearest, SleepTaskProvider.findNearestBed(world.level, new BlockPos(-1, 63, -1), 12));
  }

  @Test
  void keepsTheInclusiveSphericalBoundaryAndNeverLoadsMissingChunks() {
    var world = new TestWorld();
    var boundary = new BlockPos(5, 0, 0);
    world.addBed(boundary);
    world.addBed(new BlockPos(4, 4, 0));
    world.addBed(new BlockPos(-1, 0, 0));
    world.loaded.remove(new ChunkPos(-1, 0));
    assertEquals(boundary, SleepTaskProvider.findNearestBed(world.level, BlockPos.ZERO, 5));
    verify(world.cache, never()).getChunk(anyInt(), anyInt(), any(), eq(true));
  }

  @Test
  void preservesTheOriginalZyxTieOrderAcrossSections() {
    var world = new TestWorld();
    var first = new BlockPos(15, 64, 14);
    world.addBed(first);
    world.addBed(new BlockPos(14, 62, 15));
    assertEquals(first, SleepTaskProvider.findNearestBed(world.level, new BlockPos(15, 63, 15), 3));
  }

  @Test
  void rejectsUnusedBedPaletteEntries() {
    var world = new TestWorld();
    world.stalePalettes.add(new SectionKey(0, 4, 0));
    assertNull(SleepTaskProvider.findNearestBed(world.level, new BlockPos(1, 64, 1), 2));
  }

  @Test
  void observesAddedAndRemovedBedsWithoutCachingAbsence() {
    var world = new TestWorld();
    var origin = new BlockPos(0, 64, 0);
    var bed = new BlockPos(2, 64, 0);
    assertNull(SleepTaskProvider.findNearestBed(world.level, origin, 4));
    world.addBed(bed);
    var found = SleepTaskProvider.findNearestBed(world.level, origin, 4);
    assertEquals(bed, found);
    world.blocks.remove(bed);
    world.stalePalettes.add(new SectionKey(0, 4, 0));
    assertNull(SleepTaskProvider.findNearestBed(world.level, origin, 4));
    assertEquals(bed, found);
  }

  @Test
  void clipsNegativeWorldHeightBeforeInspectingSections() {
    var world = new TestWorld();
    var valid = new BlockPos(0, -63, 0);
    world.addBed(new BlockPos(0, -65, 0));
    world.addBed(valid);
    assertEquals(valid, SleepTaskProvider.findNearestBed(world.level, new BlockPos(0, -64, 0), 4));
  }

  private record SectionKey(int x, int y, int z) {}

  private static final class TestWorld {
    final ClientLevel level = mock(ClientLevel.class);
    final ClientChunkCache cache = mock(ClientChunkCache.class);
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final Set<ChunkPos> loaded = new HashSet<>();
    final Set<SectionKey> stalePalettes = new HashSet<>();
    final Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
    final Map<SectionKey, LevelChunkSection> sections = new HashMap<>();
    int levelReads;
    int sectionReads;

    TestWorld() {
      for (var x = -2; x <= 2; x++) {
        for (var z = -2; z <= 2; z++) loaded.add(new ChunkPos(x, z));
      }
      when(level.getChunkSource()).thenReturn(cache);
      when(level.getMinY()).thenReturn(-64);
      when(level.getMaxY()).thenReturn(319);
      when(level.getSectionIndexFromSectionY(anyInt())).thenAnswer(call -> (int) call.getArgument(0) + 4);
      when(cache.hasChunk(anyInt(), anyInt())).thenAnswer(call ->
        loaded.contains(new ChunkPos(call.getArgument(0), call.getArgument(1))));
      when(cache.getChunk(anyInt(), anyInt(), any(), anyBoolean())).thenAnswer(call -> {
        var pos = new ChunkPos((int) call.getArgument(0), (int) call.getArgument(1));
        return loaded.contains(pos) ? chunks.computeIfAbsent(pos, this::chunk) : null;
      });
      when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
        levelReads++;
        var pos = (BlockPos) call.getArgument(0);
        return pos.getY() < -64 || pos.getY() > 319
          ? Blocks.AIR.defaultBlockState() : state(pos);
      });
    }

    void addBed(BlockPos pos) {
      blocks.put(pos, Blocks.BED.red().defaultBlockState());
    }

    BlockState state(BlockPos pos) {
      return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    LevelChunk chunk(ChunkPos pos) {
      var chunk = mock(LevelChunk.class);
      when(chunk.getSection(anyInt())).thenAnswer(call -> sections.computeIfAbsent(
        new SectionKey(pos.x(), (int) call.getArgument(0) - 4, pos.z()), this::section));
      return chunk;
    }

    LevelChunkSection section(SectionKey key) {
      var section = mock(LevelChunkSection.class);
      when(section.maybeHas(any())).thenAnswer(call -> {
        Predicate<BlockState> predicate = call.getArgument(0);
        return predicate.test(Blocks.AIR.defaultBlockState())
          || stalePalettes.contains(key) && predicate.test(Blocks.BED.red().defaultBlockState())
          || blocks.entrySet().stream().anyMatch(entry ->
          entry.getKey().getX() >> 4 == key.x() && entry.getKey().getY() >> 4 == key.y()
            && entry.getKey().getZ() >> 4 == key.z() && predicate.test(entry.getValue()));
      });
      when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
        sectionReads++;
        return state(new BlockPos((key.x() << 4) + (int) call.getArgument(0),
          (key.y() << 4) + (int) call.getArgument(1), (key.z() << 4) + (int) call.getArgument(2)));
      });
      return section;
    }
  }
}
