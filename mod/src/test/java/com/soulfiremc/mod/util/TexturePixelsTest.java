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

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TexturePixelsTest {
  @Test
  void preservesGrayscalePngChannelsInsteadOfApplyingSrgbConversion() throws IOException {
    var image = new BufferedImage(256, 1, BufferedImage.TYPE_BYTE_GRAY);
    var expected = new int[256];
    for (var value = 0; value < 256; value++) {
      image.getRaster().setSample(value, 0, 0, value);
      expected[value] = 0xFF000000 | value << 16 | value << 8 | value;
    }
    assertArrayEquals(expected, decodePng(image));
  }

  @Test
  void preservesGrayscaleAlphaAndSixteenBitSamples() throws IOException {
    var model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
      Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
    var raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 3, 1, 2, null);
    var image = new BufferedImage(model, raster, false, null);
    raster.setPixel(0, 0, new int[]{0x12FF, 0x00FF});
    raster.setPixel(1, 0, new int[]{0x80FF, 0x7FFF});
    raster.setPixel(2, 0, new int[]{0xFFFF, 0xFFFF});
    assertArrayEquals(new int[]{0x00121212, 0x7F808080, 0xFFFFFFFF}, decodePng(image));
  }

  @Test
  void preservesRgbAndAlphaPngChannels() throws IOException {
    var image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
    var pixels = new int[]{0x00123456, 0x80123456, 0xFF89ABCD};
    image.setRGB(0, 0, 3, 1, pixels, 0, 3);
    assertArrayEquals(pixels, decodePng(image));
  }

  private static int[] decodePng(BufferedImage image) throws IOException {
    var encoded = new ByteArrayOutputStream();
    ImageIO.write(image, "PNG", encoded);
    return TexturePixels.argb(ImageIO.read(new ByteArrayInputStream(encoded.toByteArray())));
  }
}
