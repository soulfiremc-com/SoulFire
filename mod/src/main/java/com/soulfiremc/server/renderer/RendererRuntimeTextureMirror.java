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

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class RendererRuntimeTextureMirror {
  private static final Object LOCK = new Object();
  private static final Map<GpuTexture, Identifier> TEXTURE_IDS = new IdentityHashMap<>();
  private static final Map<Identifier, MirroredTexture> TEXTURES = new HashMap<>();

  private RendererRuntimeTextureMirror() {}

  public static void register(Identifier location, @Nullable GpuTexture texture) {
    if (texture == null || !texture.getFormat().hasColorAspect()) {
      return;
    }

    synchronized (LOCK) {
      TEXTURE_IDS.values().removeIf(location::equals);
      TEXTURE_IDS.put(texture, location);
      TEXTURES.compute(location, (_, existing) -> existing != null && existing.matches(texture) ? existing : new MirroredTexture(texture));
    }
  }

  public static void unregister(Identifier location) {
    synchronized (LOCK) {
      TEXTURE_IDS.values().removeIf(location::equals);
      TEXTURES.remove(location);
    }
  }

  public static void mirrorWrite(
    GpuTexture destination,
    NativeImage source,
    int destX,
    int destY,
    int width,
    int height,
    int sourceX,
    int sourceY) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        return;
      }

      mirrored.write(source, destX, destY, width, height, sourceX, sourceY);
    }
  }

  public static void mirrorWrite(
    GpuTexture destination,
    ByteBuffer source,
    NativeImage.Format format,
    int destX,
    int destY,
    int width,
    int height) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        return;
      }

      mirrored.write(source, format, destX, destY, width, height);
    }
  }

  @Nullable
  public static RendererAssets.TextureImage texture(Identifier location) {
    synchronized (LOCK) {
      var mirrored = TEXTURES.get(location);
      return mirrored != null && mirrored.hasUploadData() ? mirrored.toTextureImage(location) : null;
    }
  }

  private static boolean isPlayerSkin(Identifier location) {
    return location.getPath().startsWith("skins/");
  }

  @Nullable
  private static MirroredTexture mirroredTexture(GpuTexture texture) {
    var location = TEXTURE_IDS.get(texture);
    if (location == null) {
      return null;
    }

    var mirrored = TEXTURES.get(location);
    if (mirrored == null || !mirrored.matches(texture)) {
      mirrored = new MirroredTexture(texture);
      TEXTURES.put(location, mirrored);
    }
    return mirrored;
  }

  private static final class MirroredTexture {
    private final int width;
    private final int height;
    private final TextureFormat format;
    private final int[] pixels;
    private boolean hasUploadData;

    private MirroredTexture(GpuTexture texture) {
      this.width = texture.getWidth(0);
      this.height = texture.getHeight(0);
      this.format = texture.getFormat();
      this.pixels = new int[Math.max(0, width * height)];
    }

    private boolean matches(GpuTexture texture) {
      return width == texture.getWidth(0) && height == texture.getHeight(0) && format == texture.getFormat();
    }

    private void write(
      NativeImage source,
      int destX,
      int destY,
      int width,
      int height,
      int sourceX,
      int sourceY) {
      if (!hasWritableRegion(destX, destY, width, height)) {
        return;
      }

      for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
          pixels[destX + x + (destY + y) * this.width] = nativeImagePixel(source, sourceX + x, sourceY + y);
        }
      }
      hasUploadData = true;
    }

    private void write(ByteBuffer source, NativeImage.Format format, int destX, int destY, int width, int height) {
      if (!hasWritableRegion(destX, destY, width, height)) {
        return;
      }

      var data = source.duplicate();
      var baseOffset = data.position();
      var components = format.components();
      for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
          var sourceOffset = baseOffset + (x + y * width) * components;
          pixels[destX + x + (destY + y) * this.width] = bufferPixel(data, format, sourceOffset);
        }
      }
      hasUploadData = true;
    }

    private boolean hasWritableRegion(int destX, int destY, int width, int height) {
      return width > 0
        && height > 0
        && destX >= 0
        && destY >= 0
        && destX + width <= this.width
        && destY + height <= this.height;
    }

    private int nativeImagePixel(NativeImage source, int x, int y) {
      if (format == TextureFormat.RED8 || source.format().hasLuminance()) {
        var alpha = Byte.toUnsignedInt(source.getLuminanceOrAlpha(x, y));
        return (alpha << 24) | 0x00FFFFFF;
      }

      return source.getPixel(x, y);
    }

    private int bufferPixel(ByteBuffer source, NativeImage.Format format, int offset) {
      if (this.format == TextureFormat.RED8 || format.hasLuminance()) {
        var alpha = channel(source, format, offset, format.luminanceOrAlphaOffset());
        return (alpha << 24) | 0x00FFFFFF;
      }

      var red = channel(source, format, offset, format.luminanceOrRedOffset());
      var green = channel(source, format, offset, format.luminanceOrGreenOffset());
      var blue = channel(source, format, offset, format.luminanceOrBlueOffset());
      var alpha = format.hasLuminanceOrAlpha() ? channel(source, format, offset, format.luminanceOrAlphaOffset()) : 0xFF;
      return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int channel(ByteBuffer source, NativeImage.Format format, int offset, int bitOffset) {
      if (bitOffset == 0xFF) {
        return 0xFF;
      }

      var byteOffset = bitOffset / Byte.SIZE;
      if (byteOffset < 0 || byteOffset >= format.components() || offset + byteOffset >= source.limit()) {
        return 0;
      }
      return Byte.toUnsignedInt(source.get(offset + byteOffset));
    }

    private boolean hasUploadData() {
      return hasUploadData;
    }

    @Nullable
    private RendererAssets.TextureImage toTextureImage(Identifier location) {
      if (isPlayerSkin(location)) {
        return toNormalizedSkinTextureImage(location);
      }

      return RendererAssets.TextureImage.fromArgb(width, height, pixels, null);
    }

    @Nullable
    private RendererAssets.TextureImage toNormalizedSkinTextureImage(Identifier location) {
      NativeImage image = null;
      NativeImage normalized = null;
      try {
        image = toNativeImage();
        normalized = SkinTextureDownloader.processLegacySkin(image, location.toString());
        image = null;
        return textureImage(normalized);
      } catch (Throwable _) {
        return null;
      } finally {
        if (normalized != null) {
          normalized.close();
        }
        if (image != null) {
          image.close();
        }
      }
    }

    private NativeImage toNativeImage() {
      var image = new NativeImage(width, height, false);
      for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
          image.setPixel(x, y, pixels[x + y * width]);
        }
      }
      return image;
    }

    private RendererAssets.TextureImage textureImage(NativeImage image) {
      var normalizedPixels = new int[image.getWidth() * image.getHeight()];
      for (var y = 0; y < image.getHeight(); y++) {
        for (var x = 0; x < image.getWidth(); x++) {
          normalizedPixels[x + y * image.getWidth()] = image.getPixel(x, y);
        }
      }
      return RendererAssets.TextureImage.fromArgb(image.getWidth(), image.getHeight(), normalizedPixels, null);
    }
  }
}
