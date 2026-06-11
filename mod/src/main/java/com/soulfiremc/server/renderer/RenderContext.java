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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public record RenderContext(
  ClientLevel level,
  LocalPlayer localPlayer,
  boolean cameraDetached,
  Camera camera,
  EnvironmentAttributeProbe environmentProbe,
  int maxDistance,
  double maxDistanceSq,
  int minY,
  int maxY,
  long animationTick,
  Set<Long> vanillaRenderedBlockEntities,
  ConcurrentMap<Long, Float> localLightCache,
  SectionMeshCache sectionMeshCache
) {
  public static RenderContext create(ClientLevel level, LocalPlayer localPlayer, Camera camera, int maxDistance) {
    var environmentProbe = new EnvironmentAttributeProbe();
    environmentProbe.tick(level, new Vec3(camera.eyeX(), camera.eyeY(), camera.eyeZ()));
    return new RenderContext(
      level,
      localPlayer,
      cameraDetached(localPlayer, camera),
      camera,
      environmentProbe,
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

  private static boolean cameraDetached(LocalPlayer localPlayer, Camera camera) {
    if (localPlayer == null) {
      return false;
    }

    var playerEye = localPlayer.getEyePosition();
    return playerEye.distanceToSqr(camera.eyeX(), camera.eyeY(), camera.eyeZ()) > 1.0E-6
      || Mth.degreesDifferenceAbs(localPlayer.getYRot(), camera.yRot()) > 1.0E-4F
      || Mth.degreesDifferenceAbs(localPlayer.getXRot(), camera.xRot()) > 1.0E-4F;
  }
}
