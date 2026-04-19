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

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;

/// Software 3D renderer using CPU rasterization.
@UtilityClass
@Slf4j
public class SoftwareRenderer {
  private static final RasterPipeline RASTER_PIPELINE = new RasterPipeline();

  public static BufferedImage render(
    ClientLevel level,
    LocalPlayer player,
    int width,
    int height,
    double fov,
    int maxDistance
  ) {
    return render(
      level,
      player,
      player.getEyePosition(),
      player.getYRot(),
      player.getXRot(),
      width,
      height,
      fov,
      maxDistance
    );
  }

  public static BufferedImage render(
    ClientLevel level,
    LocalPlayer localPlayer,
    Vec3 eyePos,
    float yRot,
    float xRot,
    int width,
    int height,
    double fov,
    int maxDistance
  ) {
    var debugTrace = RenderDebugTrace.create(width, height, maxDistance, yRot, xRot);
    RenderDebugTrace.bind(debugTrace);
    var renderStart = System.nanoTime();
    try {
      var camera = new Camera(eyePos, yRot, xRot, width, height, fov, maxDistance + 32.0F);
      var ctx = RenderContext.create(level, camera, maxDistance);

      var worldCollectStart = System.nanoTime();
      var worldScene = WorldMeshCollector.collect(ctx);
      debugTrace.worldCollectNanos(System.nanoTime() - worldCollectStart);

      var dynamicCollectStart = System.nanoTime();
      var dynamicScene = SceneCollector.collect(ctx, localPlayer);
      debugTrace.dynamicCollectNanos(System.nanoTime() - dynamicCollectStart);

      var sceneData = worldScene.merge(dynamicScene);
      var buffers = new RasterBuffers(width, height);

      var rasterStart = System.nanoTime();
      RASTER_PIPELINE.render(ctx, sceneData, buffers);
      debugTrace.rasterNanos(System.nanoTime() - rasterStart);
      debugTrace.totalNanos(System.nanoTime() - renderStart);
      debugTrace.logSummary(sceneData);

      return buffers.image();
    } finally {
      RenderDebugTrace.unbind();
    }
  }
}
