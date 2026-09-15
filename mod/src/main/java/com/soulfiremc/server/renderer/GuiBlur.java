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

/// Separable box passes approximate the menu blur without a GPU framebuffer.
final class GuiBlur {
  private GuiBlur() {}

  static void apply(RasterBuffers buffers, int radius) {
    if (radius <= 0) {
      return;
    }
    var width = buffers.image().getWidth();
    var height = buffers.image().getHeight();
    radius = Math.min(radius, Math.max(width, height));
    var scratch = new int[width * height];
    for (var pass = 0; pass < 3; pass++) {
      pass(buffers.colorBuffer(), scratch, width, height, radius, true);
      pass(scratch, buffers.colorBuffer(), width, height, radius, false);
    }
  }

  private static void pass(int[] source, int[] target, int width, int height, int radius, boolean horizontal) {
    var length = horizontal ? width : height;
    var lines = horizontal ? height : width;
    var stride = horizontal ? 1 : width;
    var count = 2 * radius + 1;
    for (var line = 0; line < lines; line++) {
      var start = horizontal ? line * width : line;
      var sums = new long[4];
      for (var offset = -radius; offset <= radius; offset++) {
        accumulate(sums, source[start + Math.clamp(offset, 0, length - 1) * stride], 1);
      }
      for (var index = 0; index < length; index++) {
        var color = 0;
        for (var channel = 0; channel < 4; channel++) {
          color |= (int) (sums[channel] / count) << (channel * 8);
        }
        target[start + index * stride] = color;
        accumulate(sums, source[start + Math.clamp(index - radius, 0, length - 1) * stride], -1);
        accumulate(sums, source[start + Math.clamp(index + radius + 1, 0, length - 1) * stride], 1);
      }
    }
  }

  private static void accumulate(long[] sums, int color, int sign) {
    for (var channel = 0; channel < 4; channel++) {
      sums[channel] += sign * ((color >>> (channel * 8)) & 255);
    }
  }
}
