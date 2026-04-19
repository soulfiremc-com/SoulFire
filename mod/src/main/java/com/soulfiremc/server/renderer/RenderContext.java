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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public record RenderContext(
  ClientLevel level,
  Camera camera,
  int maxDistance,
  double maxDistanceSq,
  int minY,
  int maxY,
  long animationTick,
  Set<Long> vanillaRenderedBlockEntities,
  ConcurrentMap<Long, Float> localLightCache,
  SectionMeshCache sectionMeshCache
) {
  public static RenderContext create(ClientLevel level, Camera camera, int maxDistance) {
    return new RenderContext(
      level,
      camera,
      maxDistance,
      (double) maxDistance * maxDistance,
      level.getMinY(),
      level.getMaxY(),
      level.getOverworldClockTime(),
      ConcurrentHashMap.newKeySet(),
      new ConcurrentHashMap<>(),
      SectionMeshCache.forLevel(level)
    );
  }
}
