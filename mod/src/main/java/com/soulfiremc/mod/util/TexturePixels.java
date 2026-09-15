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
package com.soulfiremc.mod.util;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;

/// Reads texture channels without Java's linear-gray to sRGB color conversion.
public final class TexturePixels {
  private TexturePixels() {}

  public static int[] argb(BufferedImage image) {
    var width = image.getWidth();
    var height = image.getHeight();
    var model = image.getColorModel();
    if (model.getColorSpace().getType() != ColorSpace.TYPE_GRAY) {
      return image.getRGB(0, 0, width, height, null, 0, width);
    }

    var raster = image.getRaster();
    var pixels = new int[width * height];
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var gray = channel(raster.getSample(x, y, 0), model.getComponentSize(0));
        var alpha = model.hasAlpha() ? channel(raster.getSample(x, y, 1), model.getComponentSize(1)) : 255;
        pixels[y * width + x] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
      }
    }
    return pixels;
  }

  private static int channel(int sample, int bits) {
    // Match PNG decoding: expand sub-byte samples and retain the high byte of 16-bit samples.
    return bits >= 8 ? sample >>> (bits - 8) : sample * 255 / ((1 << bits) - 1);
  }
}
