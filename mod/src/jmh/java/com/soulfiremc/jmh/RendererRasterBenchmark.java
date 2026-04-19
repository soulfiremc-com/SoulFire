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
package com.soulfiremc.jmh;

import com.soulfiremc.server.renderer.Camera;
import com.soulfiremc.server.renderer.RasterBuffers;
import com.soulfiremc.server.renderer.RasterPipeline;
import com.soulfiremc.server.renderer.RenderQuad;
import com.soulfiremc.server.renderer.RenderVertex;
import com.soulfiremc.server.renderer.RendererAssets;
import com.soulfiremc.server.renderer.SceneData;
import net.minecraft.world.phys.Vec3;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.awt.image.BufferedImage;

@State(Scope.Benchmark)
public class RendererRasterBenchmark {
  private RasterPipeline pipeline;
  private Camera camera;
  private RasterBuffers buffers;
  private SceneData sceneData;

  @Setup
  public void setup() {
    pipeline = new RasterPipeline();
    camera = new Camera(new Vec3(0.0, 0.0, 0.0), 0.0F, 0.0F, 320, 180, 70.0, 128.0F);
    buffers = new RasterBuffers(320, 180);
    sceneData = buildScene();
  }

  @Benchmark
  public int rasterizeSyntheticScene() {
    pipeline.renderSynthetic(camera, sceneData, buffers, 0L, 0xFF87CEEB);
    return buffers.colorBuffer()[buffers.colorBuffer().length / 2];
  }

  private SceneData buildScene() {
    var builder = SceneData.builder();
    var stone = solidTexture(0xFF8A8A8A);
    var leaves = solidTexture(0xAA4CAF50);
    var glass = solidTexture(0x66C6E6FF);

    for (var z = 6; z <= 26; z += 2) {
      for (var x = -8; x <= 8; x += 2) {
        for (var y = -4; y <= 4; y += 2) {
          builder.add(quad(x, y, z, 0.85F, stone, RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF));
        }
      }
    }
    for (var x = -8; x <= 8; x += 2) {
      builder.add(quad(x, -5, 10, 0.9F, leaves, RendererAssets.AlphaMode.CUTOUT, 0xFFFFFFFF));
      builder.add(quad(x + 1, 3, 14, 1.1F, glass, RendererAssets.AlphaMode.TRANSLUCENT, 0xCCFFFFFF));
    }

    return builder.build();
  }

  private static RenderQuad quad(
    float centerX,
    float centerY,
    float z,
    float halfSize,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color
  ) {
    return new RenderQuad(
      new RenderVertex(centerX - halfSize, centerY - halfSize, z, 0.0F, 1.0F),
      new RenderVertex(centerX - halfSize, centerY + halfSize, z, 0.0F, 0.0F),
      new RenderVertex(centerX + halfSize, centerY + halfSize, z, 1.0F, 0.0F),
      new RenderVertex(centerX + halfSize, centerY - halfSize, z, 1.0F, 1.0F),
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
}
