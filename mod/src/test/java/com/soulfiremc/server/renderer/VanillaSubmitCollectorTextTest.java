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

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSubmitCollectorTextTest {
  private static final int WIDTH = 96;
  private static final int HEIGHT = 96;

  @Test
  void capturedTextRenderTypeQuadsRasterizeAfterWindingNormalization() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newTextConsumer(collector, texture);

    addGlyphVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F);
    addGlyphVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F);
    addGlyphVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F);
    addGlyphVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F);
    flush(consumer);

    var scene = sceneData(collector);
    assertTrue(scene.totalQuadCount() > 0);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    new RasterPipeline().renderSynthetic(camera, scene, buffers, 0L, 0xFF000000);

    assertTrue(countChangedPixels(buffers, 0xFF000000) > 0);
  }

  private static VertexConsumer newTextConsumer(VanillaSubmitCollector collector, RendererAssets.TextureImage texture) throws Exception {
    var consumerClass = Class.forName("com.soulfiremc.server.renderer.VanillaSubmitCollector$CapturingVertexConsumer");
    var constructor = consumerClass.getDeclaredConstructor(
      VanillaSubmitCollector.class,
      Matrix4fc.class,
      VertexFormat.Mode.class,
      RendererAssets.TextureImage.class,
      RendererAssets.AlphaMode.class,
      int.class,
      RenderType.class,
      DepthStencilState.class
    );
    constructor.setAccessible(true);
    return (VertexConsumer) constructor.newInstance(
      collector,
      new Matrix4f(),
      VertexFormat.Mode.QUADS,
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0,
      RenderTypes.textIntensity(Identifier.withDefaultNamespace("font/test")),
      null
    );
  }

  private static void addGlyphVertex(VertexConsumer consumer, float x, float y, float z, float u, float v) {
    consumer
      .addVertex(x, y, z)
      .setColor(0xFFE9D89A)
      .setUv(u, v);
  }

  private static void flush(VertexConsumer consumer) throws Exception {
    Method method = consumer.getClass().getDeclaredMethod("flush");
    method.setAccessible(true);
    method.invoke(consumer);
  }

  private static VanillaSubmitCollector newCollector(Camera camera) throws Exception {
    var constructor = VanillaSubmitCollector.class.getDeclaredConstructor(RenderContext.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new RenderContext(
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
    ));
  }

  private static SceneData sceneData(VanillaSubmitCollector collector) throws Exception {
    Field field = VanillaSubmitCollector.class.getDeclaredField("builder");
    field.setAccessible(true);
    return ((SceneData.Builder) field.get(collector)).build();
  }

  private static int countChangedPixels(RasterBuffers buffers, int backgroundColor) {
    var count = 0;
    for (var color : buffers.colorBuffer()) {
      if (color != backgroundColor) {
        count++;
      }
    }
    return count;
  }
}
