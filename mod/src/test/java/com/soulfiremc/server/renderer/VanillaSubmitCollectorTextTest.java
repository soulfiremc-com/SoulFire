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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSubmitCollectorTextTest {
  private static final int WIDTH = 96;
  private static final int HEIGHT = 96;

  @Test
  void capturedTextRenderTypeQuadsRasterizeWithVanillaVertexOrder() throws Exception {
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
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertTrue(countChangedPixels(buffers, 0xFF000000) > 0);
  }

  @Test
  void capturedTextBackgroundQuadsRasterizeAsDoubleSidedTextEffects() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newTextConsumer(collector, texture, RenderTypes.textBackground());

    addVertex(consumer, -0.75F, -0.3F, 4.0F, 0.0F, 0.0F, 0x80FFFFFF);
    addVertex(consumer, 0.75F, -0.3F, 4.0F, 0.0F, 1.0F, 0x80FFFFFF);
    addVertex(consumer, 0.75F, 0.3F, 4.0F, 1.0F, 1.0F, 0x80FFFFFF);
    addVertex(consumer, -0.75F, 0.3F, 4.0F, 1.0F, 0.0F, 0x80FFFFFF);
    flush(consumer);

    var scene = sceneData(collector);
    assertTrue(scene.totalQuadCount() > 0);
    assertTrue(scene.translucent()[0].material().doubleSided());

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertTrue(countChangedPixels(buffers, 0xFF000000) > 0);
  }

  @Test
  void capturedVerticesApplyPackedLightCoords() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newTextConsumer(collector, texture);

    addGlyphVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    addGlyphVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, LightCoordsUtil.pack(0, 0));
    addGlyphVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, LightCoordsUtil.pack(0, 0));
    addGlyphVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    flush(consumer);

    var scene = sceneData(collector);
    assertTrue(scene.totalQuadCount() > 0);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF2F2B1F, 8);
  }

  @Test
  void capturedVerticesDefaultToFullBright() throws Exception {
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
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFE9D89A, 3);
  }

  @Test
  void entityRenderTypesCaptureUv1OverlayCoordinates() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFF0000FF}, null);
    var renderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test"));
    var consumer = newTextConsumer(collector, texture, renderType);
    var overlay = OverlayTexture.pack(0.0F, true);

    addVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT, overlay);
    addVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT, overlay);
    addVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT, overlay);
    addVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT, overlay);
    flush(consumer);

    var scene = sceneData(collector);
    assertEquals(1, scene.opaque().length);
    assertEquals(0xB2FF0000, scene.opaque()[0].v0().overlayColor());

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF4D00B2, 3);
  }

  @Test
  void textShaderAlphaCutoutDiscardsSubTenthAlphaGlyphs() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newTextConsumer(collector, texture);

    addVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0x19FFFFFF);
    addVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0x19FFFFFF);
    addVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0x19FFFFFF);
    addVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0x19FFFFFF);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    assertEquals(0, countChangedPixels(buffers, 0xFF000000));
  }

  @Test
  void orderedCollectorsMergeSamePassGeometryByVanillaOrder() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var highOrderConsumer = newTextConsumer((VanillaSubmitCollector) collector.order(10), texture);
    var lowOrderConsumer = newTextConsumer((VanillaSubmitCollector) collector.order(-10), texture);

    addTextQuad(highOrderConsumer, 0x80FF0000);
    flush(highOrderConsumer);
    addTextQuad(lowOrderConsumer, 0x8000FF00);
    flush(lowOrderConsumer);

    var scene = sceneData(collector);
    assertEquals(2, scene.translucent().length);
    assertEquals(0x8000FF00, scene.translucent()[0].v0().color());
    assertEquals(0x80FF0000, scene.translucent()[1].v0().color());
  }

  private static VertexConsumer newTextConsumer(VanillaSubmitCollector collector, RendererAssets.TextureImage texture) throws Exception {
    return newTextConsumer(collector, texture, RenderTypes.textIntensity(Identifier.withDefaultNamespace("font/test")));
  }

  private static VertexConsumer newTextConsumer(VanillaSubmitCollector collector, RendererAssets.TextureImage texture, RenderType renderType) throws Exception {
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
      renderType,
      null
    );
  }

  private static void addGlyphVertex(VertexConsumer consumer, float x, float y, float z, float u, float v) {
    addVertex(consumer, x, y, z, u, v, 0xFFE9D89A);
  }

  private static void addGlyphVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, int light) {
    addVertex(consumer, x, y, z, u, v, 0xFFE9D89A, light);
  }

  private static void addTextQuad(VertexConsumer consumer, int color) {
    addVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, color);
    addVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, color);
    addVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, color);
    addVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, color);
  }

  private static void addVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, int color) {
    consumer
      .addVertex(x, y, z)
      .setColor(color)
      .setUv(u, v);
  }

  private static void addVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, int color, int light) {
    consumer
      .addVertex(x, y, z)
      .setColor(color)
      .setUv(u, v)
      .setLight(light);
  }

  private static void addVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, int color, int light, int overlay) {
    consumer
      .addVertex(x, y, z)
      .setColor(color)
      .setUv(u, v)
      .setLight(light)
      .setOverlay(overlay);
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
    Method method = VanillaSubmitCollector.class.getDeclaredMethod("buildScene");
    method.setAccessible(true);
    return (SceneData) method.invoke(collector);
  }

  private static void renderSynthetic(RasterPipeline pipeline, Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, int clearColor) {
    buffers.clearColor(clearColor);
    buffers.clearDepth();
    pipeline.renderScene(camera, sceneData, buffers, animationTick);
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

  private static void assertColorNear(int actual, int expected, int tolerance) {
    assertChannelNear((actual >> 16) & 0xFF, (expected >> 16) & 0xFF, tolerance);
    assertChannelNear((actual >> 8) & 0xFF, (expected >> 8) & 0xFF, tolerance);
    assertChannelNear(actual & 0xFF, expected & 0xFF, tolerance);
  }

  private static void assertChannelNear(int actual, int expected, int tolerance) {
    assertTrue(Math.abs(actual - expected) <= tolerance, () -> "expected " + expected + " +/- " + tolerance + " but was " + actual);
  }
}
