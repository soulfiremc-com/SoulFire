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

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;

/// Fog parameters used by the shared software raster backend.
record RasterFogState(
  boolean enabled,
  int color,
  float environmentalStart,
  float environmentalEnd,
  float renderDistanceStart,
  float renderDistanceEnd,
  float cloudsEnd
) {
  RasterFogState(boolean enabled, int color, float environmentalStart, float environmentalEnd,
                 float renderDistanceStart, float renderDistanceEnd) {
    this(enabled, color, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, Float.MAX_VALUE);
  }

  static final RasterFogState DISABLED = new RasterFogState(false, 0, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

  static RasterFogState from(RenderContext ctx) {
    var probe = ctx.environmentProbe();
    var environmentalStart = probe.getValue(EnvironmentAttributes.FOG_START_DISTANCE, 1.0F);
    var environmentalEnd = probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, 1.0F);
    var rainFogMultiplier = rainFogMultiplier(ctx);
    environmentalStart -= 160.0F * rainFogMultiplier;
    var minRainFogEnd = Math.min(96.0F, environmentalEnd);
    environmentalEnd = Math.max(minRainFogEnd, environmentalEnd - 256.0F * rainFogMultiplier);

    var renderDistanceEnd = (float) ctx.maxDistance();
    var renderDistanceFogSpan = Mth.clamp(renderDistanceEnd / 10.0F, 4.0F, 64.0F);
    return new RasterFogState(
      true,
      SkyRenderer.atmosphericFogColor(ctx),
      environmentalStart,
      environmentalEnd,
      renderDistanceEnd - renderDistanceFogSpan,
      renderDistanceEnd,
      Math.min(Minecraft.getInstance().options.cloudRange().get() * 16.0F,
        probe.getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, 1.0F))
    );
  }

  private static float rainFogMultiplier(RenderContext ctx) {
    var camera = ctx.camera();
    var cameraBlockPos = BlockPos.containing(camera.eyeX(), camera.eyeY(), camera.eyeZ());
    var biome = ctx.level().getBiome(cameraBlockPos).value();
    var skyLight = ctx.level().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(cameraBlockPos);
    var skyLightMultiplier = Mth.clamp((skyLight - 8.0F) / 7.0F, 0.0F, 1.0F);
    return ctx.level().getRainLevel(1.0F) * skyLightMultiplier * (biome.hasPrecipitation() ? 1.0F : 0.5F);
  }
}
