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

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneCollectorTest {
  @Test
  void weatherColumnSizeTableDoesNotExposeCenterNaNs() throws Exception {
    var xMethod = SceneCollector.class.getDeclaredMethod("weatherColumnSizes", boolean.class);
    xMethod.setAccessible(true);

    var xSizes = (float[]) xMethod.invoke(null, true);
    var zSizes = (float[]) xMethod.invoke(null, false);
    var centerIndex = 16 * 32 + 16;

    assertEquals(0.0F, xSizes[centerIndex], 0.0F);
    assertEquals(0.0F, zSizes[centerIndex], 0.0F);
    for (var index = 0; index < xSizes.length; index++) {
      var currentIndex = index;
      assertTrue(Float.isFinite(xSizes[index]), () -> "expected finite x size at " + currentIndex);
      assertTrue(Float.isFinite(zSizes[index]), () -> "expected finite z size at " + currentIndex);
    }
  }

  @Test
  void skippedWeatherColumnDoesNotAbortLaterColumns() throws Exception {
    var method = SceneCollector.class.getDeclaredMethod(
      "collectWeatherColumns",
      RenderContext.class,
      SceneData.Builder.class,
      WeatherRenderState.class,
      List.class,
      RendererAssets.TextureImage.class,
      float.class
    );
    method.setAccessible(true);

    var camera = new Camera(new Vec3(0.5, 2.0, 0.5), 0.0F, 0.0F, 64, 64, 70.0, 64.0F);
    var ctx = new RenderContext(
      null,
      null,
      camera,
      null,
      64,
      64.0 * 64.0,
      0,
      256,
      0L,
      ConcurrentHashMap.newKeySet(),
      new ConcurrentHashMap<>(),
      null
    );
    var renderState = new WeatherRenderState();
    renderState.intensity = 1.0F;
    renderState.radius = 4;
    var columns = List.of(
      new WeatherEffectRenderer.ColumnInstance(0, 0, 0, 4, 0.0F, 0.0F, LightCoordsUtil.FULL_BRIGHT),
      new WeatherEffectRenderer.ColumnInstance(1, 0, 0, 4, 0.0F, 0.0F, LightCoordsUtil.FULL_BRIGHT)
    );
    var builder = SceneData.builder();
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);

    method.invoke(null, ctx, builder, renderState, columns, texture, 0.0F);

    var weather = builder.build().weather();
    assertEquals(1, weather.length);
    assertFinite(weather[0]);
  }

  private static void assertFinite(RenderQuad quad) {
    assertFinite(quad.v0());
    assertFinite(quad.v1());
    assertFinite(quad.v2());
    assertFinite(quad.v3());
  }

  private static void assertFinite(RenderVertex vertex) {
    assertTrue(Float.isFinite(vertex.x()));
    assertTrue(Float.isFinite(vertex.y()));
    assertTrue(Float.isFinite(vertex.z()));
    assertTrue(Float.isFinite(vertex.u()));
    assertTrue(Float.isFinite(vertex.v()));
  }
}
