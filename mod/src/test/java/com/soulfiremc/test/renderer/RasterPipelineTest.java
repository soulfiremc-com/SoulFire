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
package com.soulfiremc.test.renderer;

import com.soulfiremc.server.renderer.*;
import net.minecraft.world.phys.Vec3;
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
  void nearerOpaqueQuadWinsDepthTest() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.2F, -1.2F, 6.0F, 1.2F, 1.2F, solidTexture(0xFF0000FF), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

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

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

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

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

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
      new RenderVertex(-1.0F, -1.0F, 4.0F, 0.0F, 1.0F),
      new RenderVertex(-1.0F, 1.0F, 12.0F, 0.0F, 0.0F),
      new RenderVertex(1.0F, 1.0F, 12.0F, 1.0F, 0.0F),
      new RenderVertex(1.0F, -1.0F, 4.0F, 1.0F, 1.0F),
      solidTexture(0xFFFF0000),
      RendererAssets.AlphaMode.TRANSLUCENT,
      0x80FFFFFF
    ));

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

    var upperColor = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2 - 2);
    var lowerColor = buffers.image().getRGB(WIDTH / 2, HEIGHT / 2 + 2);
    assertRedBlendedOverBlue(upperColor);
    assertRedBlendedOverBlue(lowerColor);
  }

  @Test
  void equalDepthOpaqueFragmentsUseBlaze3dLequalDepthTest() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
    scene.add(quad(-1.0F, -1.0F, 4.0F, 1.0F, 1.0F, solidTexture(0xFF00FF00), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF00FF00, 3);
  }

  @Test
  void geometryBehindFarPlaneIsClipped() {
    var pipeline = new RasterPipeline();
    var camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, WIDTH, HEIGHT, 70.0, 8.0F);
    var buffers = new RasterBuffers(WIDTH, HEIGHT);
    var scene = SceneData.builder();
    scene.add(quad(-1.0F, -1.0F, 12.0F, 1.0F, 1.0F, solidTexture(0xFFFF0000), RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

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

    pipeline.renderSynthetic(camera, scene.build(), buffers, 0L, 0xFF000000);

    assertColorNear(buffers.image().getRGB(WIDTH / 2, HEIGHT / 2), 0xFF0000FF, 3);
  }

  @Test
  void manualProjectionMatchesJomlViewRotationProjectionMatrix() {
    var camera = new Camera(new Vec3(10.0, 65.0, -4.0), 35.0F, -12.0F, WIDTH, HEIGHT, 70.0, 64.0F);
    var worldX = camera.eyeX() + camera.forwardX() * 10.0 + camera.rightX() * 1.5 + camera.upX() * 0.75;
    var worldY = camera.eyeY() + camera.forwardY() * 10.0 + camera.rightY() * 1.5 + camera.upY() * 0.75;
    var worldZ = camera.eyeZ() + camera.forwardZ() * 10.0 + camera.rightZ() * 1.5 + camera.upZ() * 0.75;

    var projected = camera.viewRotationProjectionMatrix().transformProject(new Vector3f(
      (float) (worldX - camera.eyeX()),
      (float) (worldY - camera.eyeY()),
      (float) (worldZ - camera.eyeZ())
    ));
    var matrixScreenX = (projected.x() + 1.0F) * 0.5F * WIDTH;
    var matrixScreenY = (1.0F - projected.y()) * 0.5F * HEIGHT;

    var manualNdcX = camera.viewX(worldX, worldY, worldZ) / (camera.viewZ(worldX, worldY, worldZ) * camera.tanHalfFovX());
    var manualNdcY = camera.viewY(worldX, worldY, worldZ) / (camera.viewZ(worldX, worldY, worldZ) * camera.tanHalfFovY());
    var manualScreenX = (0.5 - manualNdcX * 0.5) * WIDTH;
    var manualScreenY = (0.5 - manualNdcY * 0.5) * HEIGHT;

    assertEquals(manualScreenX, matrixScreenX, 1.0E-4);
    assertEquals(manualScreenY, matrixScreenY, 1.0E-4);
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
      new RenderVertex(minX, minY, z, 0.0F, 1.0F),
      new RenderVertex(minX, maxY, z, 0.0F, 0.0F),
      new RenderVertex(maxX, maxY, z, 1.0F, 0.0F),
      new RenderVertex(maxX, minY, z, 1.0F, 1.0F),
      texture,
      alphaMode,
      color,
      false,
      0.0F
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
      v0,
      v1,
      v2,
      v3,
      texture,
      alphaMode,
      color,
      false,
      0.0F
    );
  }

  private static RendererAssets.TextureImage solidTexture(int argb) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, argb);
    return RendererAssets.TextureImage.from(image, null);
  }

  private static void assertRedBlendedOverBlue(int color) {
    assertTrue(((color >> 16) & 0xFF) > (color & 0xFF), () -> "expected red over blue blend but was 0x" + Integer.toHexString(color));
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
