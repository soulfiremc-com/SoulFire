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
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void textIntensitySamplesRedChannelAsGlyphCoverage() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFF40FF00}, null);
    var consumer = newTextConsumer(collector, texture);

    addVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
    addVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
    addVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
    addVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    var red = (color >> 16) & 0xFF;
    var green = (color >> 8) & 0xFF;
    var blue = color & 0xFF;
    assertTrue(red > 0, () -> "expected visible glyph coverage but was 0x" + Integer.toHexString(color));
    assertChannelNear(green, red, 3);
    assertChannelNear(blue, red, 3);
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
    assertEquals(2, scene.opaque().length);
    assertEquals(0xB2FF0000, scene.opaque()[0].v0().overlayColor());
    assertEquals(0xB2FF0000, scene.opaque()[1].v0().overlayColor());

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, scene, buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF4D00B2, 3);
  }

  @Test
  void entityOverlayIsLightmappedAfterBlendLikeVanillaShader() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFF0000FF}, null);
    var renderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test"));
    var consumer = newTextConsumer(collector, texture, renderType);
    var overlay = OverlayTexture.pack(0.0F, true);
    var light = LightCoordsUtil.pack(0, 0);

    addVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0xFFFFFFFF, light, overlay);
    addVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0xFFFFFFFF, light, overlay);
    addVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0xFFFFFFFF, light, overlay);
    addVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0xFFFFFFFF, light, overlay);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF0E0020, 3);
  }

  @Test
  void entityVerticesApplyVanillaDirectionalLightingFromNormals() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var renderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test"));
    var consumer = newTextConsumer(collector, texture, renderType);

    addEntityVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF666666, 3);
  }

  @Test
  void entityDissolveRenderTypesCaptureMaskSampler() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var renderType = RenderTypes.entityCutoutDissolve(
      Identifier.withDefaultNamespace("textures/entity/test"),
      Identifier.withDefaultNamespace("textures/entity/test_dissolve")
    );
    var consumer = newTextConsumer(collector, texture, renderType);

    addEntityVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    flush(consumer);

    var scene = sceneData(collector);
    assertTrue(scene.opaque().length > 0);
    assertTrue(scene.opaque()[0].material().dissolveMaskTexture() != null);
  }

  @Test
  void entityBackFacesUseOppositePerFaceLighting() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var renderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test"));
    var consumer = newTextConsumer(collector, texture, renderType);

    addEntityVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F);
    addEntityVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFFFFFFF, 3);
  }

  @Test
  void materialOverrideDoesNotInheritSourceEntityLighting() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var renderType = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test"));
    var materialOverride = RenderMaterial.create(texture, RendererAssets.AlphaMode.TRANSLUCENT, 0xFFFFFFFF, true, 0.0F);
    var consumer = newOverrideConsumer(collector, texture, renderType, materialOverride);

    addEntityVertex(consumer, -0.75F, 0.4F, 4.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    addEntityVertex(consumer, -0.75F, -0.4F, 4.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    addEntityVertex(consumer, 0.75F, -0.4F, 4.0F, 1.0F, 1.0F, 0.0F, -1.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    addEntityVertex(consumer, 0.75F, 0.4F, 4.0F, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F, LightCoordsUtil.pack(0, 0));
    flush(consumer);

    var scene = sceneData(collector);
    assertEquals(1, scene.translucent().length);
    assertEquals(0xFFFFFFFF, scene.translucent()[0].v0().color());
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

  @Test
  void weatherTargetRenderTypesRouteToWeatherPass() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);

    collector.submitCustomGeometry(new PoseStack(), RenderTypes.lightning(), (_, consumer) -> addTextQuad(consumer, 0x80FFFFFF));

    var scene = sceneData(collector);
    assertEquals(0, scene.translucent().length);
    assertEquals(1, scene.weather().length);
    assertTrue(scene.weather()[0].material().blendState().blends());
  }

  @Test
  void capturedVertexConsumerFlushConsumesPendingVertices() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newTextConsumer(collector, texture);

    addTextQuad(consumer, 0x80FFFFFF);
    flush(consumer);
    flush(consumer);

    assertEquals(1, sceneData(collector).translucent().length);
  }

  @Test
  void submittedParticlesUseParticleShaderThresholdAndPipelineState() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var particles = new QuadParticleRenderState();

    addParticle(particles, SingleQuadParticle.Layer.OPAQUE, 4.0F, 0x40FFFFFF, LightCoordsUtil.FULL_BRIGHT);
    addParticle(particles, SingleQuadParticle.Layer.TRANSLUCENT, 5.0F, 0xFFFFFFFF, LightCoordsUtil.pack(0, 0));
    collector.submitParticleGroup(particles);

    var scene = sceneData(collector);
    assertEquals(1, scene.cutout().length);
    assertEquals(0, scene.translucent().length);
    assertEquals(1, scene.translucentParticles().length);
    assertEquals(RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD, scene.cutout()[0].material().alphaCutoutThreshold());
    assertEquals(RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD, scene.translucentParticles()[0].material().alphaCutoutThreshold());
    assertTrue(scene.translucentParticles()[0].material().depthWrite());
    assertTrue(((scene.translucentParticles()[0].material().color() >> 16) & 0xFF) < 255);
  }

  @Test
  void capturedLinesUseScreenSpaceLineWidth() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);

    var nearWidth = renderedLineWidth(camera, texture, 4.0F, 0.8F);
    var farWidth = renderedLineWidth(camera, texture, 12.0F, 2.4F);
    assertTrue(nearWidth >= 6, () -> "expected near line to be at least 6 px wide but was " + nearWidth);
    assertTrue(farWidth >= 6, () -> "expected far line to be at least 6 px wide but was " + farWidth);
    assertTrue(Math.abs(nearWidth - farWidth) <= 2, () -> "expected stable screen-space widths but got " + nearWidth + " and " + farWidth);
  }

  @Test
  void lineFallbackUsesEachEndpointLineWidth() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newConsumer(collector, texture, RenderTypes.lines(), VertexFormat.Mode.LINES);

    addLineVertex(consumer, 0.0F, -1.0F, 6.0F, 0.0F, 0.0F, 0.0F, 2.0F);
    addLineVertex(consumer, 0.0F, 1.0F, 6.0F, 0.0F, 0.0F, 0.0F, 14.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    var firstRow = firstChangedRow(buffers, 0xFF000000);
    var lastRow = lastChangedRow(buffers, 0xFF000000);
    assertTrue(firstRow >= 0 && lastRow >= firstRow);

    var firstWidth = changedRunWidth(buffers, WIDTH / 2, Math.min(firstRow + 2, lastRow), 0xFF000000);
    var lastWidth = changedRunWidth(buffers, WIDTH / 2, Math.max(lastRow - 2, firstRow), 0xFF000000);
    var narrowWidth = Math.min(firstWidth, lastWidth);
    var wideWidth = Math.max(firstWidth, lastWidth);
    assertTrue(narrowWidth <= 5, () -> "expected one line end to stay narrow but widths were " + firstWidth + " and " + lastWidth);
    assertTrue(wideWidth >= 10, () -> "expected one line end to stay wide but widths were " + firstWidth + " and " + lastWidth);
  }

  @Test
  void linesCrossingNearPlaneAreClippedBeforeScreenExpansion() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newConsumer(collector, texture, RenderTypes.lines(), VertexFormat.Mode.LINES);

    addLineVertex(consumer, 0.0F, -0.5F, -1.0F, 0.0F, 1.0F, 0.0F, 10.0F);
    addLineVertex(consumer, 0.0F, 0.5F, 6.0F, 0.0F, 1.0F, 0.0F, 10.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    var changedPixels = countChangedPixels(buffers, 0xFF000000);
    assertTrue(changedPixels > 0, "expected visible clipped line segment");
    assertTrue(
      changedPixels < WIDTH * HEIGHT / 3,
      () -> "expected clipped line to stay bounded but changed " + changedPixels + " pixels"
    );
  }

  @Test
  void glintMaterialsUseVanillaFoilRenderState() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var method = VanillaSubmitCollector.class.getDeclaredMethod("glintMaterial", RendererAssets.TextureImage.class, RenderType.class);
    method.setAccessible(true);

    var itemGlint = (RenderMaterial) method.invoke(collector, texture, RenderTypes.glint());
    var entityGlint = (RenderMaterial) method.invoke(collector, texture, RenderTypes.entityGlint());

    assertEquals(RenderMaterial.DepthTest.EQUAL, itemGlint.depthTest());
    assertFalse(itemGlint.depthWrite());
    assertTrue(itemGlint.blendState().blends());
    assertEquals(RenderMaterial.FogMode.RGB_FADE, itemGlint.fogMode());
    assertEquals(8.0F, uvScale(itemGlint.uvTransform()), 1.0E-5F);
    assertEquals(0.5F, uvScale(entityGlint.uvTransform()), 1.0E-5F);
  }

  @Test
  void spriteBackedModelPartsExpandLocalUvsIntoAtlasSpace() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var atlasLocation = Identifier.withDefaultNamespace("textures/atlas/test.png");
    var sprite = fakeSprite(atlasLocation, 16, 16, 4, 8, 4, 4);
    var modelPart = new ModelPart(
      List.of(new ModelPart.Cube(0, 0, -8.0F, -8.0F, 4.0F, 16.0F, 16.0F, 1.0F, 0.0F, 0.0F, 0.0F, false, 64.0F, 64.0F, Set.of(Direction.NORTH))),
      Map.of()
    );

    collector.submitModelPart(
      modelPart,
      new PoseStack(),
      RenderTypes.entityCutout(atlasLocation),
      LightCoordsUtil.FULL_BRIGHT,
      OverlayTexture.NO_OVERLAY,
      sprite,
      false,
      false,
      0xFFFFFFFF,
      null,
      0
    );

    var scene = sceneData(collector);
    assertTrue(scene.opaque().length > 0);
    for (var quad : scene.opaque()) {
      assertUvInsideSprite(quad.v0(), sprite);
      assertUvInsideSprite(quad.v1(), sprite);
      assertUvInsideSprite(quad.v2(), sprite);
      assertUvInsideSprite(quad.v3(), sprite);
    }
  }

  @Test
  void specialFoilDecalPoseUsesVanillaDisplayScale() throws Exception {
    var method = VanillaSubmitCollector.class.getDeclaredMethod("specialFoilDecalPose", ItemDisplayContext.class, PoseStack.Pose.class);
    method.setAccessible(true);
    var poseStack = new PoseStack();

    var guiPose = (PoseStack.Pose) method.invoke(null, ItemDisplayContext.GUI, poseStack.last());
    var firstPersonPose = (PoseStack.Pose) method.invoke(null, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, poseStack.last());
    var thirdPersonPose = (PoseStack.Pose) method.invoke(null, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, poseStack.last());

    assertEquals(0.5F, transformedX(guiPose, 1.0F), 1.0E-6F);
    assertEquals(0.75F, transformedX(firstPersonPose, 1.0F), 1.0E-6F);
    assertEquals(1.0F, transformedX(thirdPersonPose, 1.0F), 1.0E-6F);
  }

  @Test
  void capturedPointsUseSubmittedPointSize() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newConsumer(collector, texture, RenderTypes.debugPoint(), VertexFormat.Mode.POINTS);

    consumer.addVertex(0.0F, 0.0F, 6.0F).setColor(0xFFFFFFFF).setLineWidth(10.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    var width = changedRunWidth(buffers, WIDTH / 2, HEIGHT / 2, 0xFF000000);
    assertTrue(width >= 8, () -> "expected point to use submitted size but was " + width + " px wide");
  }

  @Test
  void pointsOutsideClipVolumeAreDiscardedBeforeExpansion() throws Exception {
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var collector = newCollector(camera);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var consumer = newConsumer(collector, texture, RenderTypes.debugPoint(), VertexFormat.Mode.POINTS);

    consumer.addVertex(4.4F, 0.0F, 6.0F).setColor(0xFFFFFFFF).setLineWidth(24.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);

    assertEquals(0, countChangedPixels(buffers, 0xFF000000));
  }

  private static VertexConsumer newTextConsumer(VanillaSubmitCollector collector, RendererAssets.TextureImage texture) throws Exception {
    return newTextConsumer(collector, texture, RenderTypes.textIntensity(Identifier.withDefaultNamespace("font/test")));
  }

  private static VertexConsumer newTextConsumer(VanillaSubmitCollector collector, RendererAssets.TextureImage texture, RenderType renderType) throws Exception {
    return newConsumer(collector, texture, renderType, VertexFormat.Mode.QUADS);
  }

  private static VertexConsumer newOverrideConsumer(
    VanillaSubmitCollector collector,
    RendererAssets.TextureImage texture,
    RenderType renderType,
    RenderMaterial materialOverride
  ) throws Exception {
    return newConsumer(collector, texture, renderType, VertexFormat.Mode.QUADS, materialOverride);
  }

  private static VertexConsumer newConsumer(
    VanillaSubmitCollector collector,
    RendererAssets.TextureImage texture,
    RenderType renderType,
    VertexFormat.Mode mode
  ) throws Exception {
    return newConsumer(collector, texture, renderType, mode, null);
  }

  private static VertexConsumer newConsumer(
    VanillaSubmitCollector collector,
    RendererAssets.TextureImage texture,
    RenderType renderType,
    VertexFormat.Mode mode,
    RenderMaterial materialOverride
  ) throws Exception {
    var consumerClass = Class.forName("com.soulfiremc.server.renderer.VanillaSubmitCollector$CapturingVertexConsumer");
    var constructor = consumerClass.getDeclaredConstructor(
      VanillaSubmitCollector.class,
      Matrix4fc.class,
      VertexFormat.Mode.class,
      RendererAssets.TextureImage.class,
      RendererAssets.AlphaMode.class,
      int.class,
      RenderType.class,
      DepthStencilState.class,
      RenderMaterial.class
    );
    constructor.setAccessible(true);
    return (VertexConsumer) constructor.newInstance(
      collector,
      new Matrix4f(),
      mode,
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0,
      renderType,
      null,
      materialOverride
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

  private static void addEntityVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
    addEntityVertex(consumer, x, y, z, u, v, nx, ny, nz, LightCoordsUtil.FULL_BRIGHT);
  }

  private static void addEntityVertex(
    VertexConsumer consumer,
    float x,
    float y,
    float z,
    float u,
    float v,
    float nx,
    float ny,
    float nz,
    int light
  ) {
    consumer
      .addVertex(x, y, z)
      .setColor(0xFFFFFFFF)
      .setUv(u, v)
      .setLight(light)
      .setNormal(nx, ny, nz);
  }

  private static void addLineVertex(
    VertexConsumer consumer,
    float x,
    float y,
    float z,
    float nx,
    float ny,
    float nz,
    float lineWidth
  ) {
    consumer
      .addVertex(x, y, z)
      .setColor(0xFFFFFFFF)
      .setNormal(nx, ny, nz)
      .setLineWidth(lineWidth);
  }

  private static void addParticle(QuadParticleRenderState particles, SingleQuadParticle.Layer layer, float z, int color, int lightCoords) {
    particles.add(
      layer,
      0.0F,
      0.0F,
      z,
      0.0F,
      0.0F,
      0.0F,
      1.0F,
      1.0F,
      0.0F,
      1.0F,
      0.0F,
      1.0F,
      color,
      lightCoords
    );
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

  private static int changedRunWidth(RasterBuffers buffers, int centerX, int y, int backgroundColor) {
    var minX = centerX;
    var maxX = centerX;
    var image = buffers.image();
    while (minX > 0 && image.getRGB(minX - 1, y) != backgroundColor) {
      minX--;
    }
    while (maxX + 1 < image.getWidth() && image.getRGB(maxX + 1, y) != backgroundColor) {
      maxX++;
    }
    return maxX - minX + 1;
  }

  private static int firstChangedRow(RasterBuffers buffers, int backgroundColor) {
    var image = buffers.image();
    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) != backgroundColor) {
          return y;
        }
      }
    }
    return -1;
  }

  private static int lastChangedRow(RasterBuffers buffers, int backgroundColor) {
    var image = buffers.image();
    for (var y = image.getHeight() - 1; y >= 0; y--) {
      for (var x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) != backgroundColor) {
          return y;
        }
      }
    }
    return -1;
  }

  private static int renderedLineWidth(Camera camera, RendererAssets.TextureImage texture, float z, float halfHeight) throws Exception {
    var collector = newCollector(camera);
    var consumer = newConsumer(collector, texture, RenderTypes.lines(), VertexFormat.Mode.LINES);
    addLineVertex(consumer, 0.0F, -halfHeight, z, 0.0F, 1.0F, 0.0F, 8.0F);
    addLineVertex(consumer, 0.0F, halfHeight, z, 0.0F, 1.0F, 0.0F, 8.0F);
    flush(consumer);

    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    renderSynthetic(new RasterPipeline(), camera, sceneData(collector), buffers, 0L, 0xFF000000);
    return maxChangedRunWidth(buffers, HEIGHT / 2, 0xFF000000);
  }

  private static int maxChangedRunWidth(RasterBuffers buffers, int y, int backgroundColor) {
    var image = buffers.image();
    var maxWidth = 0;
    var currentWidth = 0;
    for (var x = 0; x < image.getWidth(); x++) {
      if (image.getRGB(x, y) == backgroundColor) {
        maxWidth = Math.max(maxWidth, currentWidth);
        currentWidth = 0;
      } else {
        currentWidth++;
      }
    }
    return Math.max(maxWidth, currentWidth);
  }

  private static float uvScale(RenderMaterial.UvTransform transform) {
    return (float) Math.hypot(transform.uFromU(), transform.vFromU());
  }

  private static float transformedX(PoseStack.Pose pose, float x) {
    return pose.pose().transformPosition(new Vector3f(x, 0.0F, 0.0F)).x();
  }

  private static TextureAtlasSprite fakeSprite(
    Identifier atlasLocation,
    int atlasWidth,
    int atlasHeight,
    int x,
    int y,
    int width,
    int height
  ) throws Exception {
    var image = new NativeImage(width, height, true);
    for (var py = 0; py < height; py++) {
      for (var px = 0; px < width; px++) {
        image.setPixel(px, py, 0xFFFFFFFF);
      }
    }
    var contents = new SpriteContents(Identifier.withDefaultNamespace("test/sprite"), new FrameSize(width, height), image);
    var constructor = TextureAtlasSprite.class.getDeclaredConstructor(
      Identifier.class,
      SpriteContents.class,
      int.class,
      int.class,
      int.class,
      int.class,
      int.class
    );
    constructor.setAccessible(true);
    return (TextureAtlasSprite) constructor.newInstance(atlasLocation, contents, atlasWidth, atlasHeight, x, y, 0);
  }

  private static void assertUvInsideSprite(RenderVertex vertex, TextureAtlasSprite sprite) {
    var epsilon = 1.0E-6F;
    assertTrue(vertex.u() >= sprite.getU0() - epsilon, () -> "expected u >= sprite u0 but was " + vertex.u());
    assertTrue(vertex.u() <= sprite.getU1() + epsilon, () -> "expected u <= sprite u1 but was " + vertex.u());
    assertTrue(vertex.v() >= sprite.getV0() - epsilon, () -> "expected v >= sprite v0 but was " + vertex.v());
    assertTrue(vertex.v() <= sprite.getV1() + epsilon, () -> "expected v <= sprite v1 but was " + vertex.v());
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
