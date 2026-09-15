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

/// Applies vanilla's entity-outline edge filter and two separable blur passes.
final class EntityOutlineRenderer {
  private static final RenderMaterial.BlendState BLEND = RenderMaterial.BlendState.from(BlendFunction.ENTITY_OUTLINE_BLIT);

  private EntityOutlineRenderer() {}

  static void composite(RasterBuffers mask, RasterBuffers target) {
    var width = mask.image().getWidth();
    var height = mask.image().getHeight();
    var edge = edges(mask.colorBuffer(), width, height);
    var horizontal = blur(edge, width, height, true);
    var outline = blur(horizontal, width, height, false);
    var destination = target.colorBuffer();
    for (var i = 0; i < outline.length; i++) {
      destination[i] = SoftwareRasterizer.blend(destination[i], outline[i], BLEND);
    }
  }

  static int[] edges(int[] source, int width, int height) {
    var result = new int[source.length];
    var samples = new int[5];
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var center = source[y * width + x];
        samples[0] = center;
        samples[1] = source[y * width + Math.max(0, x - 1)];
        samples[2] = source[y * width + Math.min(width - 1, x + 1)];
        samples[3] = source[Math.max(0, y - 1) * width + x];
        samples[4] = source[Math.min(height - 1, y + 1) * width + x];
        var alpha = 0;
        for (var i = 1; i < samples.length; i++) {
          alpha += Math.abs((center >>> 24) - (samples[i] >>> 24));
        }
        var color = Math.min(255, alpha) << 24;
        for (var shift = 0; shift < 24; shift += 8) {
          var total = 0.0F;
          for (var sample : samples) {
            total += ((sample >>> shift) & 255) * ((sample >>> 24) * (1.0F / 255.0F));
          }
          color |= channel(total * 0.2F) << shift;
        }
        result[y * width + x] = color;
      }
    }
    return result;
  }

  static int[] blur(int[] source, int width, int height, boolean horizontal) {
    var flipped = new int[source.length];
    for (var y = 0; y < height; y++) {
      System.arraycopy(source, y * width, flipped, (height - y - 1) * width, width);
    }
    var texture = RendererAssets.TextureImage.fromArgb(width, height, flipped, null)
      .withAddressMode(RendererAssets.TextureAddressMode.CLAMP_TO_EDGE).withLinearFiltering();
    var result = new int[source.length];
    var texelX = 1.0F / width;
    var texelY = 1.0F / height;
    var stepX = horizontal ? texelX : 0.0F;
    var stepY = horizontal ? 0.0F : texelY;
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var u = Math.fma(texelX, x, texelX * 0.5F);
        var v = Math.fma(texelY, height - y - 1, texelY * 0.5F);
        var first = texture.sample(u - stepX * 1.5F, v - stepY * 1.5F, 0);
        var second = texture.sample(u + stepX * 0.5F, v + stepY * 0.5F, 0);
        var last = texture.sample(u + stepX * 2.0F, v + stepY * 2.0F, 0);
        var color = 0;
        for (var shift = 0; shift < 32; shift += 8) {
          var total = ((first >>> shift) & 255) * (1.0F / 255.0F)
            + ((second >>> shift) & 255) * (1.0F / 255.0F)
            + ((last >>> shift) & 255) * (1.0F / 255.0F) * 0.5F;
          color |= channel((shift == 24 ? total : total / 2.5F) * 255.0F) << shift;
        }
        result[y * width + x] = color;
      }
    }
    return result;
  }

  private static int channel(float value) {
    return Math.clamp((int) Math.rint(value), 0, 255);
  }
}
