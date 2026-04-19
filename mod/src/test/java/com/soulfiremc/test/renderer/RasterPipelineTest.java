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
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

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

  private static RendererAssets.TextureImage solidTexture(int argb) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, argb);
    return RendererAssets.TextureImage.from(image, null);
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
