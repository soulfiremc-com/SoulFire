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
package com.soulfiremc.server.renderer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/// Cache section meshes across repeated renders of the same client level.
public final class SectionMeshCache {
  private static final Map<ClientLevel, SectionMeshCache> LEVEL_CACHES = new WeakHashMap<>();

  private final ConcurrentMap<SectionKey, SectionMesh> meshes = new ConcurrentHashMap<>();

  private SectionMeshCache() {}

  public static SectionMeshCache forLevel(ClientLevel level) {
    synchronized (LEVEL_CACHES) {
      return LEVEL_CACHES.computeIfAbsent(level, _ -> new SectionMeshCache());
    }
  }

  public SceneData getOrBuild(LevelChunk chunk, int sectionY, long tick, Supplier<SceneData> builder) {
    var trace = RenderDebugTrace.current();
    var chunkPos = chunk.getPos();
    var key = new SectionKey((((long) chunkPos.getMinBlockX()) << 32) ^ (chunkPos.getMinBlockZ() & 0xFFFFFFFFL), sectionY);
    var cached = meshes.get(key);
    if (cached != null && cached.builtTick() == tick) {
      trace.sectionCacheHit();
      return cached.sceneData();
    }

    trace.sectionCacheMiss();
    var built = builder.get();
    meshes.put(key, new SectionMesh(built, tick));
    return built;
  }

  private record SectionKey(long chunkPos, int sectionY) {}
}
