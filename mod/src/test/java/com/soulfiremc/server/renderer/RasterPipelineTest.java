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

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterPipelineTest {
  private static final int WIDTH = 64;
  private static final int HEIGHT = 64;

  @Test
  void depthBufferClearsToOpenGlFarPlane() {
    var buffers = new RasterBuffers(4, 3);
    var depth = buffers.depthBuffer();
    for (var i = 0; i < depth.length; i++) {
      depth[i] = 0.25F;
    }

    buffers.clearDepth();

    for (var value : depth) {
      assertEquals(1.0F, value, 0.0F);
    }
  }

  @Test
  void nonFiniteProjectedGeometryIsDropped() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(Float.NaN, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      solidTexture(0xFFFF0000),
      RendererAssets.AlphaMode.OPAQUE,
      0xFFFFFFFF
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF000000, 3);
  }

  @Test
  void nearerOpaqueQuadWinsDepthTest() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFFF0000, 3);
  }

  @Test
  void cutoutQuadSkipsFullyTransparentPixels() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0x00000000), RendererAssets.AlphaMode.CUTOUT, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF0000FF, 3);
  }

  @Test
  void translucentQuadBlendsOverOpaqueBackground() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.TRANSLUCENT, 0x80FFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    assertChannelNear((color >> 16) & 0xFF, 128, 8);
    assertChannelNear((color >> 8) & 0xFF, 0, 3);
    assertChannelNear(color & 0xFF, 127, 8);
  }

  @Test
  void translucentSortingUsesOneDepthKeyPerSourceQuad() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-0.8F, -0.8F, 8.5F, 0.8F, 0.8F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.TRANSLUCENT, 0x80FFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 12.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 12.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      solidTexture(0xFFFF0000),
      RendererAssets.AlphaMode.TRANSLUCENT,
      0x80FFFFFF
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var upperColor = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2 - 2);
    var lowerColor = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2 + 2);
    assertRedBlendedOverBlue(upperColor);
    assertRedBlendedOverBlue(lowerColor);
  }

  @Test
  void vertexColorIsInterpolatedBeforeMaterialTint() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(new RenderQuad(
      new RenderVertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F, 0xFF00FF00),
      new RenderVertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F, 0xFF00FF00),
      new RenderVertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F, 0xFFFF0000),
      new RenderVertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F, 0xFFFF0000),
      RenderMaterial.create(solidTexture(0xFFFFFFFF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F)
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var leftColor = buffers.image().getRGB(WIDTH / 2 - 7, HEIGHT / 2);
    var rightColor = buffers.image().getRGB(WIDTH / 2 + 7, HEIGHT / 2);
    assertTrue(
      ((leftColor >> 8) & 0xFF) > ((leftColor >> 16) & 0xFF),
      () -> "expected left screen sample to stay green-dominant but was 0x" + Integer.toHexString(leftColor)
    );
    assertTrue(
      ((rightColor >> 16) & 0xFF) > ((rightColor >> 8) & 0xFF),
      () -> "expected right screen sample to stay red-dominant but was 0x" + Integer.toHexString(rightColor)
    );
  }

  @Test
  void entityOverlayColorMixesAfterTextureAndVertexColor() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(new RenderQuad(
      new RenderVertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F, 0xFFFFFFFF, 0xB2FF0000),
      new RenderVertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F, 0xFFFFFFFF, 0xB2FF0000),
      new RenderVertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F, 0xFFFFFFFF, 0xB2FF0000),
      new RenderVertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F, 0xFFFFFFFF, 0xB2FF0000),
      RenderMaterial.create(solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F)
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF4D00B2, 3);
  }

  @Test
  void depthTestCanBeDisabledForSeeThroughGeometry() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 8.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 8.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 8.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 8.0F, 1.0F, 1.0F),
      materialWithDepthTest(
        RenderMaterial.create(solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.DepthTest.ALWAYS_PASS,
        false
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void disabledDepthWritesDoNotOccludeLaterGeometry() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithDepthTest(
        RenderMaterial.create(solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL,
        false
      )
    ));
    scene.add(quad(-1.0F, -1.0F, 8.0F, 1.0F, 1.0F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF0000FF, 3);
  }

  @Test
  void colorWriteMaskCanUpdateDepthWithoutReplacingColor() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      new RenderMaterial(
        solidTexture(0xFFFF0000),
        RendererAssets.AlphaMode.OPAQUE,
        0xFFFFFFFF,
        false,
        0.0F,
        0.0F,
        0.0F,
        0,
        RenderMaterial.AlphaCutoutSource.FINAL_COLOR,
        RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL,
        true,
        RenderMaterial.BlendState.REPLACE,
        ColorTargetState.WRITE_NONE,
        RenderMaterial.UvTransform.IDENTITY,
        RenderMaterial.TextureSampleMode.COLOR,
        false,
        0,
        1.0F
      )
    ));
    scene.add(quad(-1.0F, -1.0F, 8.0F, 1.0F, 1.0F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF101010);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF101010, 3);
  }

  @Test
  void lightningBlendFunctionAddsSourceOverDestination() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF000040), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithBlendState(
        RenderMaterial.create(solidTexture(0x80FF0000), RendererAssets.AlphaMode.TRANSLUCENT, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.BlendState.from(BlendFunction.LIGHTNING)
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    assertChannelNear((color >> 16) & 0xFF, 128, 3);
    assertChannelNear(color & 0xFF, 64, 3);
  }

  @Test
  void sourceAlphaSaturateUsesOpaqueAlphaScaleForAlphaChannel() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithBlendState(
        RenderMaterial.create(solidTexture(0x80FF0000), RendererAssets.AlphaMode.TRANSLUCENT, 0xFFFFFFFF, false, 0.0F),
        new RenderMaterial.BlendState(SourceFactor.SRC_ALPHA_SATURATE, DestFactor.ZERO, SourceFactor.SRC_ALPHA_SATURATE, DestFactor.ZERO)
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0x00000000);

    var alpha = (buffers.image().getRGB(WIDTH / 2, HEIGHT / 2) >>> 24) & 0xFF;
    assertChannelNear(alpha, 128, 3);
  }

  @Test
  void oneMinusConstantBlendFactorsUseDefaultZeroBlendColor() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithBlendState(
        RenderMaterial.create(solidTexture(0x80A04020), RendererAssets.AlphaMode.TRANSLUCENT, 0xFFFFFFFF, false, 0.0F),
        new RenderMaterial.BlendState(
          SourceFactor.ONE_MINUS_CONSTANT_COLOR,
          DestFactor.ZERO,
          SourceFactor.ONE_MINUS_CONSTANT_ALPHA,
          DestFactor.ZERO
        )
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0x00000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0x80A04020, 3);
  }

  @Test
  void opaqueAlphaMaterialStillUsesBlendFunction() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF000020), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithBlendState(
        RenderMaterial.create(solidTexture(0xFF101000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.BlendState.from(BlendFunction.ADDITIVE)
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF101020, 3);
  }

  @Test
  void materialUvTransformChangesSampleCoordinates() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var identityBuffers = new RasterBuffers(WIDTH, HEIGHT);
    var transformedBuffers = new RasterBuffers(WIDTH, HEIGHT);
    var texture = splitTexture(0xFFFF0000, 0xFF00FF00);
    var identityScene = SceneData.builder();
    identityScene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, texture, RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    var transformedMaterial = materialWithUvTransform(
      RenderMaterial.create(texture, RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
      new RenderMaterial.UvTransform(1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 2L, 0L)
    );
    var transformedScene = SceneData.builder();
    transformedScene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      transformedMaterial
    ));

    renderSynthetic(pipeline, camera, identityScene.build(), identityBuffers, 1L, 0xFF000000);
    renderSynthetic(pipeline, camera, transformedScene.build(), transformedBuffers, 1L, 0xFF000000);

    assertFalse(sameRgb(
      identityBuffers.image().getRGB(WIDTH / 2 - 8, HEIGHT / 2),
      transformedBuffers.image().getRGB(WIDTH / 2 - 8, HEIGHT / 2)
    ));
  }

  @Test
  void cloudPassRendersAfterGenericTranslucentGeometry() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.TRANSLUCENT, 0x80FFFFFF));
    scene.addCloud(customQuad(
      vertex(-1.0F, -1.0F, 8.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 8.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 8.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 8.0F, 1.0F, 1.0F),
      translucentMaterial(solidTexture(0xFF00FF00), false)
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    assertTrue(((color >> 8) & 0xFF) > ((color >> 16) & 0xFF));
  }

  @Test
  void orderedTranslucentMaterialsPreserveSubmissionOrder() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithSortOnUpload(translucentMaterial(solidTexture(0xFFFF0000), false), false)
    ));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 8.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 8.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 8.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 8.0F, 1.0F, 1.0F),
      materialWithSortOnUpload(translucentMaterial(solidTexture(0xFF0000FF), false), false)
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    assertTrue((color & 0xFF) > ((color >> 16) & 0xFF), () -> "expected later blue quad to remain on top but was 0x" + Integer.toHexString(color));
  }

  @Test
  void sortableTranslucentMaterialsDoNotSortAcrossUploadGroups() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithSortGroup(translucentMaterial(solidTexture(0xFFFF0000), false), 1)
    ));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 8.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 8.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 8.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 8.0F, 1.0F, 1.0F),
      materialWithSortGroup(translucentMaterial(solidTexture(0xFF0000FF), false), 2)
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    var color = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2);
    assertTrue((color & 0xFF) > ((color >> 16) & 0xFF), () -> "expected second upload group to stay later but was 0x" + Integer.toHexString(color));
  }

  @Test
  void equalDepthOpaqueFragmentsUseBlaze3dLequalDepthTest() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void equalDepthTestRequiresExactStoredDepth() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.001F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.001F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.001F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.001F, 1.0F, 1.0F),
      materialWithDepthTest(
        RenderMaterial.create(solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.DepthTest.EQUAL,
        false
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFFF0000, 3);
  }

  @Test
  void notEqualDepthTestAcceptsAnyDifferentStoredDepth() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.001F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.001F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.001F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.001F, 1.0F, 1.0F),
      materialWithDepthTest(
        RenderMaterial.create(solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.DepthTest.NOT_EQUAL,
        false
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void renderTypeRefreshesShaderAlphaCutoutThreshold() {
    var material = RenderMaterial
      .create(solidTexture(0x19FFFFFF), RendererAssets.AlphaMode.CUTOUT, 0xFFFFFFFF, false, 0.0F)
      .withRenderType(RenderTypes.solidMovingBlock());

    assertEquals(26, material.alphaCutoutThreshold());
  }

  @Test
  void entityCutoutTestsTextureAlphaBeforeTintAlpha() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    var material = RenderMaterial
      .create(solidTexture(0xFFFFFFFF), RendererAssets.AlphaMode.CUTOUT, 0x19FFFFFF, false, 0.0F)
      .withRenderType(RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/test")));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      material
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFFFFFFF, 3);
  }

  @Test
  void geometryBehindFarPlaneIsClipped() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 8.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 12.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF000000, 3);
  }

  @Test
  void cameraFrustumUsesBlaze3dViewRotationConvention() {
    var camera = new Camera(new Vec3(10.0, 65.0, -4.0), 35.0F, -12.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    assertTrue(camera.isVisibleAabb(
      camera.eyeX() + camera.forwardX() * 8.0 - 0.5,
      camera.eyeY() + camera.forwardY() * 8.0 - 0.5,
      camera.eyeZ() + camera.forwardZ() * 8.0 - 0.5,
      camera.eyeX() + camera.forwardX() * 8.0 + 0.5,
      camera.eyeY() + camera.forwardY() * 8.0 + 0.5,
      camera.eyeZ() + camera.forwardZ() * 8.0 + 0.5
    ));
    assertFalse(camera.isVisibleAabb(
      camera.eyeX() - camera.forwardX() * 8.0 - 0.5,
      camera.eyeY() - camera.forwardY() * 8.0 - 0.5,
      camera.eyeZ() - camera.forwardZ() * 8.0 - 0.5,
      camera.eyeX() - camera.forwardX() * 8.0 + 0.5,
      camera.eyeY() - camera.forwardY() * 8.0 + 0.5,
      camera.eyeZ() - camera.forwardZ() * 8.0 + 0.5
    ));
  }

  @Test
  void cutoutUsesTerrainHalfAlphaThresholdByDefault() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0x40FF0000), RendererAssets.AlphaMode.CUTOUT, 0xFFFFFFFF));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF0000FF, 3);
  }

  @Test
  void cameraOrientationMatchesBlaze3dRotationConvention() {
    var yRot = 35.0F;
    var xRot = -12.0F;
    var camera = new Camera(new Vec3(10.0, 65.0, -4.0), yRot, xRot, WIDTH, HEIGHT, 70.0, 64.0F);
    var expectedOrientation = new Quaternionf().rotationYXZ(
      (float) Math.PI - (float) Math.toRadians(yRot),
      -(float) Math.toRadians(xRot),
      0.0F
    );
    var expectedViewRotation = new Matrix4f().rotation(new Quaternionf(expectedOrientation).conjugate());

    assertQuaternionNear(camera.orientation(), expectedOrientation);
    assertMatrixNear(camera.viewRotationMatrix(), expectedViewRotation);
  }

  @Test
  void quadCrossingNearPlaneIsClippedInsteadOfDropped() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(customQuad(
      vertex(-0.4F, -0.4F, 0.01F, 0.0F, 1.0F),
      vertex(-0.4F, 0.4F, 0.01F, 0.0F, 0.0F),
      vertex(0.4F, 0.4F, 1.0F, 1.0F, 0.0F),
      vertex(0.4F, -0.4F, 1.0F, 1.0F, 1.0F),
      solidTexture(0xFFFF0000),
      RendererAssets.AlphaMode.OPAQUE,
      0xFFFFFFFF
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFFFF0000, 3);
  }

  @Test
  void textBillboardKeepsTextureOrientationFacingCamera() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var poseStack = new PoseStack();
    poseStack.translate(0.0F, 0.0F, 4.0F);
    poseStack.mulPose(camera.orientation());
    poseStack.scale(1.0F, -1.0F, 1.0F);
    var scene = SceneData.builder();
    scene.add(BillboardGeometry.textQuad(poseStack, 2.0F, 2.0F, -1.0F, -1.0F, splitTexture(0xFFFF0000, 0xFF00FF00)));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2 - 5, HEIGHT / 2), 0xFFFF0000, 3);
    assertColorNear(buffers.image().getRGB(WIDTH / 2 + 5, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void polygonOffsetTextRendersOverCoplanarSignFace() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 4.0F, 1.2F, 1.2F, solidTexture(0xFFB98B3A), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-0.8F, -0.2F, 4.0F, 0.0F, 1.0F),
      vertex(-0.8F, 0.2F, 4.0F, 0.0F, 0.0F),
      vertex(0.8F, 0.2F, 4.0F, 1.0F, 0.0F),
      vertex(0.8F, -0.2F, 4.0F, 1.0F, 1.0F),
      polygonOffsetTextMaterial()
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF201000, 3);
  }

  @Test
  void polygonOffsetTextEscapesSignFaceDepthPrecision() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 4.0F, 1.2F, 1.2F, solidTexture(0xFFB98B3A), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-0.8F, -0.2F, 4.004F, 0.0F, 1.0F),
      vertex(-0.8F, 0.2F, 4.004F, 0.0F, 0.0F),
      vertex(0.8F, 0.2F, 4.004F, 1.0F, 0.0F),
      vertex(0.8F, -0.2F, 4.004F, 1.0F, 1.0F),
      polygonOffsetTextMaterial()
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF201000, 3);
  }

  @Test
  void viewLayeringScaleMovesGeometryTowardCameraBeforeProjection() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(customQuad(
      vertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      vertex(-1.0F, 1.0F, 4.0F, 0.0F, 0.0F),
      vertex(1.0F, 1.0F, 4.0F, 1.0F, 0.0F),
      vertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      materialWithDepthTestAndViewScale(
        RenderMaterial.create(solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, false, 0.0F),
        RenderMaterial.DepthTest.LESS_THAN,
        true,
        1.0F - 1.0F / 4096.0F
      )
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void cameraFacingBillboardKeepsTextureOrientationFacingCamera() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(BillboardGeometry.cameraFacingQuad(
      camera,
      0.0,
      0.0,
      4.0,
      2.0F,
      2.0F,
      splitTexture(0xFFFF0000, 0xFF00FF00),
      RendererAssets.AlphaMode.OPAQUE,
      0xFFFFFFFF,
      0.0F
    ));

    renderSynthetic(pipeline, camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2 - 5, HEIGHT / 2), 0xFFFF0000, 3);
    assertColorNear(buffers.image().getRGB(WIDTH / 2 + 5, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void viewProjectionMatrixProjectsCameraBasisConsistently() {
    var camera = new Camera(new Vec3(10.0, 65.0, -4.0), 35.0F, -12.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var center = new Vector3f(
      (float) (camera.eyeX() + camera.forwardX() * 10.0),
      (float) (camera.eyeY() + camera.forwardY() * 10.0),
      (float) (camera.eyeZ() + camera.forwardZ() * 10.0)
    );
    var screenLeft = new Vector3f(
      (float) (center.x() + camera.screenLeftX()),
      (float) (center.y() + camera.screenLeftY()),
      (float) (center.z() + camera.screenLeftZ())
    );
    var screenUp = new Vector3f(
      (float) (center.x() + camera.upX()),
      (float) (center.y() + camera.upY()),
      (float) (center.z() + camera.upZ())
    );

    var projectedCenter = camera.viewProjectionMatrix().transformProject(center);
    var projectedLeft = camera.viewProjectionMatrix().transformProject(screenLeft);
    var projectedUp = camera.viewProjectionMatrix().transformProject(screenUp);

    assertEquals(0.0F, projectedCenter.x(), 1.0E-5F);
    assertEquals(0.0F, projectedCenter.y(), 1.0E-5F);
    assertTrue(projectedLeft.x() < projectedCenter.x());
    assertTrue(projectedUp.y() > projectedCenter.y());
  }

  private static RenderQuad quad(
    float minX,
    float minY,
    float z,
    float maxX,
    float maxY,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color
  ) {
    return new RenderQuad(
      vertex(maxX, minY, z, 1.0F, 1.0F),
      vertex(maxX, maxY, z, 1.0F, 0.0F),
      vertex(minX, maxY, z, 0.0F, 0.0F),
      vertex(minX, minY, z, 0.0F, 1.0F),
      RenderMaterial.create(texture, alphaMode, color, false, 0.0F)
    );
  }

  private static RenderQuad customQuad(
    RenderVertex v0,
    RenderVertex v1,
    RenderVertex v2,
    RenderVertex v3,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color
  ) {
    return new RenderQuad(
      v3,
      v2,
      v1,
      v0,
      RenderMaterial.create(texture, alphaMode, color, false, 0.0F)
    );
  }

  private static RenderQuad customQuad(
    RenderVertex v0,
    RenderVertex v1,
    RenderVertex v2,
    RenderVertex v3,
    RenderMaterial material
  ) {
    return new RenderQuad(v3, v2, v1, v0, material);
  }

  private static RenderVertex vertex(float x, float y, float z, float u, float v) {
    return new RenderVertex(x, y, z, u, v, 0xFFFFFFFF);
  }

  private static RendererAssets.TextureImage solidTexture(int argb) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, argb);
    return RendererAssets.TextureImage.from(image, null);
  }

  private static RendererAssets.TextureImage splitTexture(int leftArgb, int rightArgb) {
    var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, leftArgb);
    image.setRGB(1, 0, rightArgb);
    return RendererAssets.TextureImage.from(image, null);
  }

  private static void renderSynthetic(RasterPipeline pipeline, Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, int clearColor) {
    buffers.clearColor(clearColor);
    buffers.clearDepth();
    pipeline.renderScene(camera, sceneData, buffers, animationTick);
  }

  private static RenderMaterial translucentMaterial(RendererAssets.TextureImage texture, boolean depthWrite) {
    return new RenderMaterial(
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0x80FFFFFF,
      false,
      0.0F,
      0.0F,
      0.0F,
      0,
      RenderMaterial.AlphaCutoutSource.FINAL_COLOR,
      RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL,
      depthWrite,
      RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT),
      ColorTargetState.WRITE_ALL,
      RenderMaterial.UvTransform.IDENTITY,
      RenderMaterial.TextureSampleMode.COLOR,
      true,
      0,
      1.0F
    );
  }

  private static RenderMaterial materialWithDepthTest(RenderMaterial material, RenderMaterial.DepthTest depthTest, boolean depthWrite) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      depthTest,
      depthWrite,
      material.blendState(),
      material.colorWriteMask(),
      material.uvTransform(),
      material.textureSampleMode(),
      material.sortOnUpload(),
      material.sortGroup(),
      material.viewScale()
    );
  }

  private static RenderMaterial materialWithDepthTestAndViewScale(
    RenderMaterial material,
    RenderMaterial.DepthTest depthTest,
    boolean depthWrite,
    float viewScale
  ) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      depthTest,
      depthWrite,
      material.blendState(),
      material.colorWriteMask(),
      material.uvTransform(),
      material.textureSampleMode(),
      material.sortOnUpload(),
      material.sortGroup(),
      viewScale
    );
  }

  private static RenderMaterial polygonOffsetTextMaterial() {
    return RenderMaterial
      .create(solidTexture(0xFFFFFFFF), RendererAssets.AlphaMode.TRANSLUCENT, 0xFF201000, false, 0.0F)
      .withDepthState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, -1.0F, -10.0F));
  }

  private static RenderMaterial materialWithBlendState(RenderMaterial material, RenderMaterial.BlendState blendState) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      material.depthTest(),
      material.depthWrite(),
      blendState,
      material.colorWriteMask(),
      material.uvTransform(),
      material.textureSampleMode(),
      material.sortOnUpload(),
      material.sortGroup(),
      material.viewScale()
    );
  }

  private static RenderMaterial materialWithSortOnUpload(RenderMaterial material, boolean sortOnUpload) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      material.depthTest(),
      material.depthWrite(),
      material.blendState(),
      material.colorWriteMask(),
      material.uvTransform(),
      material.textureSampleMode(),
      sortOnUpload,
      material.sortGroup(),
      material.viewScale()
    );
  }

  private static RenderMaterial materialWithSortGroup(RenderMaterial material, int sortGroup) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      material.depthTest(),
      material.depthWrite(),
      material.blendState(),
      material.colorWriteMask(),
      material.uvTransform(),
      material.textureSampleMode(),
      material.sortOnUpload(),
      sortGroup,
      material.viewScale()
    );
  }

  private static RenderMaterial materialWithUvTransform(RenderMaterial material, RenderMaterial.UvTransform uvTransform) {
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      material.depthTest(),
      material.depthWrite(),
      material.blendState(),
      material.colorWriteMask(),
      uvTransform,
      material.textureSampleMode(),
      material.sortOnUpload(),
      material.sortGroup(),
      material.viewScale()
    );
  }

  private static void assertRedBlendedOverBlue(int color) {
    assertTrue(((color >> 16) & 0xFF) > (color & 0xFF), () -> "expected red over blue blend but was 0x" + Integer.toHexString(color));
  }

  private static void assertColorNear(int actual, int expected, int tolerance) {
    assertChannelNear((actual >> 16) & 0xFF, (expected >> 16) & 0xFF, tolerance);
    assertChannelNear((actual >> 8) & 0xFF, (expected >> 8) & 0xFF, tolerance);
    assertChannelNear(actual & 0xFF, expected & 0xFF, tolerance);
  }

  private static boolean sameRgb(int first, int second) {
    return (first & 0x00FFFFFF) == (second & 0x00FFFFFF);
  }

  private static void assertChannelNear(int actual, int expected, int tolerance) {
    assertTrue(Math.abs(actual - expected) <= tolerance, () -> "expected " + expected + " +/- " + tolerance + " but was " + actual);
  }

  private static void assertQuaternionNear(Quaternionf actual, Quaternionf expected) {
    assertEquals(expected.x, actual.x, 1.0E-5F);
    assertEquals(expected.y, actual.y, 1.0E-5F);
    assertEquals(expected.z, actual.z, 1.0E-5F);
    assertEquals(expected.w, actual.w, 1.0E-5F);
  }

  private static void assertMatrixNear(Matrix4f actual, Matrix4f expected) {
    for (var column = 0; column < 4; column++) {
      for (var row = 0; row < 4; row++) {
        assertEquals(expected.get(column, row), actual.get(column, row), 1.0E-5F);
      }
    }
  }
}
