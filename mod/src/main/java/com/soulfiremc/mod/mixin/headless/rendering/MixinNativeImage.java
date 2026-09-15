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
package com.soulfiremc.mod.mixin.headless.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.soulfiremc.mod.util.TexturePixels;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(NativeImage.class)
public class MixinNativeImage {
  @Inject(
    method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;",
    at = @At("HEAD"),
    cancellable = true
  )
  private static void readWithImageIo(
    NativeImage.Format format,
    ByteBuffer bytes,
    CallbackInfoReturnable<NativeImage> cir
  ) throws IOException {
    var encoded = copyRemaining(bytes);
    var image = ImageIO.read(new ByteArrayInputStream(encoded));
    if (image == null) {
      return;
    }

    var targetFormat = format != null ? format : inferredFormat(image);
    var nativeImage = new NativeImage(targetFormat, image.getWidth(), image.getHeight(), false);
    copyPixels(image, nativeImage, targetFormat);
    cir.setReturnValue(nativeImage);
  }

  private static byte[] copyRemaining(ByteBuffer bytes) {
    var source = bytes.duplicate();
    var copied = new byte[source.remaining()];
    source.get(copied);
    return copied;
  }

  private static NativeImage.Format inferredFormat(BufferedImage image) {
    return image.getColorModel().hasAlpha() ? NativeImage.Format.RGBA : NativeImage.Format.RGB;
  }

  private static void copyPixels(BufferedImage source, NativeImage target, NativeImage.Format format) {
    var pixels = TexturePixels.argb(source);
    for (var y = 0; y < source.getHeight(); y++) {
      for (var x = 0; x < source.getWidth(); x++) {
        writePixel(target, format, x, y, pixels[y * source.getWidth() + x]);
      }
    }
  }

  private static void writePixel(NativeImage target, NativeImage.Format format, int x, int y, int argb) {
    var address = target.getPointer() + ((long) x + (long) y * target.getWidth()) * format.components();
    if (format.hasLuminance()) {
      var luminance = luminance(argb);
      putChannel(address, format.luminanceOffset(), luminance);
      putChannel(address, format.alphaOffset(), (argb >>> 24) & 0xFF);
      return;
    }

    putChannel(address, format.redOffset(), (argb >>> 16) & 0xFF);
    putChannel(address, format.greenOffset(), (argb >>> 8) & 0xFF);
    putChannel(address, format.blueOffset(), argb & 0xFF);
    putChannel(address, format.alphaOffset(), (argb >>> 24) & 0xFF);
  }

  private static int luminance(int argb) {
    var red = (argb >>> 16) & 0xFF;
    var green = (argb >>> 8) & 0xFF;
    var blue = argb & 0xFF;
    return (red * 30 + green * 59 + blue * 11) / 100;
  }

  private static void putChannel(long baseAddress, int bitOffset, int value) {
    if (bitOffset != 0xFF) {
      MemoryUtil.memPutByte(baseAddress + bitOffset / Byte.SIZE, (byte) value);
    }
  }
}
