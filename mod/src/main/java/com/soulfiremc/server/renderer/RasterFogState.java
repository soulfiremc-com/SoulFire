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

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/// Fog parameters used by the shared software raster backend.
record RasterFogState(
  boolean enabled,
  Vector4fc color,
  float environmentalStart,
  float environmentalEnd,
  float renderDistanceStart,
  float renderDistanceEnd,
  float cloudsEnd,
  float skyEnd
) {
  RasterFogState(boolean enabled, int color, float environmentalStart, float environmentalEnd,
                 float renderDistanceStart, float renderDistanceEnd) {
    this(enabled, new Vector4f(ARGB.redFloat(color), ARGB.greenFloat(color), ARGB.blueFloat(color), ARGB.alphaFloat(color)),
      environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, Float.MAX_VALUE, Float.MAX_VALUE);
  }

  static final RasterFogState DISABLED = new RasterFogState(false, 0, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

  static RasterFogState from(RenderContext ctx) {
    var minecraft = Minecraft.getInstance();
    var camera = minecraft.gameRenderer.mainCamera();
    camera.setLevel(ctx.level());
    camera.setEntity(ctx.localPlayer());
    camera.update(DeltaTracker.ONE);
    camera.setPosition(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    camera.setRotation(ctx.camera().yRot(), ctx.camera().xRot());
    camera.attributeProbe().tick(ctx.level(), camera.position());
    var fog = minecraft.gameRenderer.fogRenderer.setupFog(camera, Math.max(1, ctx.maxDistance() / 16), DeltaTracker.ONE,
      ctx.lightmapRenderState().bossOverlayWorldDarkening, ctx.level());
    return new RasterFogState(true, new Vector4f(fog.color), fog.environmentalStart, fog.environmentalEnd,
      fog.renderDistanceStart, fog.renderDistanceEnd, fog.cloudEnd, fog.skyEnd);
  }
}
