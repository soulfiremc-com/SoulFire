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

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaLightmapTest {
  @Test
  void fullBrightSamplesMaximumLightLevels() {
    assertEquals(0xFFFFFFFF, VanillaLightmap.color(context(lightmapState()), LightCoordsUtil.FULL_BRIGHT, 0));
  }

  @Test
  void shaderStateBrightensPackedLightLevels() {
    var ctx = context(lightmapState());

    var dark = VanillaLightmap.color(ctx, LightCoordsUtil.pack(0, 0), 0);
    var lit = VanillaLightmap.color(ctx, LightCoordsUtil.pack(15, 15), 0);

    assertTrue(ARGB.red(lit) > ARGB.red(dark));
    assertTrue(ARGB.green(lit) > ARGB.green(dark));
    assertTrue(ARGB.blue(lit) > ARGB.blue(dark));
  }

  @Test
  void smoothLightCoordinatesInterpolateBetweenAdjacentTexels() {
    var ctx = context(lightmapState());
    var dark = VanillaLightmap.color(ctx, LightCoordsUtil.pack(0, 10), 0);
    var bright = VanillaLightmap.color(ctx, LightCoordsUtil.pack(0, 11), 0);
    var smooth = VanillaLightmap.color(ctx, LightCoordsUtil.smoothPack(0, 10 * 16 + 8), 0);
    for (var shift = 0; shift < 24; shift += 8) {
      var lower = (dark >>> shift) & 255;
      var upper = (bright >>> shift) & 255;
      var interpolated = (smooth >>> shift) & 255;
      assertTrue(interpolated > lower && interpolated < upper);
    }
  }

  @Test
  void emissionRaisesBlockAndSkyLightBeforeSampling() {
    var ctx = context(lightmapState());

    var dark = VanillaLightmap.color(ctx, LightCoordsUtil.pack(0, 0), 0);
    var emitted = VanillaLightmap.color(ctx, LightCoordsUtil.pack(0, 0), 12);

    assertTrue(ARGB.red(emitted) > ARGB.red(dark));
    assertTrue(ARGB.green(emitted) > ARGB.green(dark));
    assertTrue(ARGB.blue(emitted) > ARGB.blue(dark));
  }

  private static LightmapRenderState lightmapState() {
    var state = new LightmapRenderState();
    state.blockFactor = 1.4F;
    state.blockLightTint = new Vector3f(1.0F, 0.8F, 0.6F);
    state.skyFactor = 1.0F;
    state.skyLightColor = new Vector3f(0.8F, 0.9F, 1.0F);
    state.ambientColor = new Vector3f(0.03F, 0.03F, 0.03F);
    state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
    return state;
  }

  private static RenderContext context(LightmapRenderState lightmapRenderState) {
    return new RenderContext(
      null,
      null,
      false,
      null,
      null,
      0,
      0.0,
      0,
      0,
      0L,
      lightmapRenderState,
      VanillaLightmap.texture(lightmapRenderState),
      null
    );
  }
}
