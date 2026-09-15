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

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class RendererRuntimeTextureMirror {
  private static final Object LOCK = new Object();
  private static final Object GLOBAL_SCOPE = new Object();
  private static final Map<GpuTexture, Identifier> TEXTURE_IDS = new IdentityHashMap<>();
  private static final Map<GpuTexture, MirroredTexture> TEXTURES = new IdentityHashMap<>();
  private static final Map<Object, Map<Identifier, GpuTexture>> SCOPED_TEXTURES =
    new IdentityHashMap<>();
  private static final Map<Object, Object> FALLBACK_SCOPES = new IdentityHashMap<>();

  private RendererRuntimeTextureMirror() {}

  public static void registerAtlas(TextureAtlas atlas, GpuTexture texture) {
    try (var pixels = new NativeImage(atlas.getWidth(), atlas.getHeight(), true)) {
      for (var sprite : atlas.sprites) {
        var contents = sprite.contents();
        var paddingX = Math.round(sprite.getU0() * atlas.getWidth()) - sprite.getX();
        var paddingY = Math.round(sprite.getV0() * atlas.getHeight()) - sprite.getY();
        // Vanilla's atlas upload extends the edge texels into the sprite's padding.
        for (var y = -paddingY; y < contents.height() + paddingY; y++) {
          for (var x = -paddingX; x < contents.width() + paddingX; x++) {
            pixels.setPixel(sprite.getX() + paddingX + x, sprite.getY() + paddingY + y,
              contents.originalImage.getPixel(Math.clamp(x, 0, contents.width() - 1), Math.clamp(y, 0, contents.height() - 1)));
          }
        }
      }
      register(atlas.location(), texture, pixels);
    }
  }

  public static void register(Identifier location, @Nullable GpuTexture texture) {
    register(GLOBAL_SCOPE, location, texture, null);
  }

  public static void register(Identifier location, @Nullable GpuTexture texture, @Nullable NativeImage initialPixels) {
    register(GLOBAL_SCOPE, location, texture, initialPixels);
  }

  public static void register(
    TextureManager textureManager,
    Identifier location,
    @Nullable GpuTexture texture) {
    register(textureManager, location, texture, null);
  }

  public static void register(
    TextureManager textureManager,
    Identifier location,
    @Nullable GpuTexture texture,
    @Nullable NativeImage initialPixels) {
    register((Object) textureManager, location, texture, initialPixels);
  }

  static void register(
    Object scope,
    Identifier location,
    @Nullable GpuTexture texture,
    @Nullable NativeImage initialPixels) {
    if (texture == null) {
      RenderDebugTrace.current().runtimeTextureMirrorSkipped("register", location + ":null-texture");
      return;
    }
    if (!texture.getFormat().hasColorAspect()) {
      RenderDebugTrace.current().runtimeTextureMirrorSkipped("register", location + ":non-color-format:" + texture.getFormat());
      return;
    }

    synchronized (LOCK) {
      var previous = SCOPED_TEXTURES
        .computeIfAbsent(scope, _ -> new HashMap<>())
        .put(location, texture);
      TEXTURE_IDS.put(texture, location);
      var mirrored = TEXTURES.compute(
        texture,
        (_, existing) -> existing != null && existing.matches(texture)
          ? existing
          : new MirroredTexture(texture));
      if (initialPixels != null) {
        mirrored.write(
          initialPixels,
          0,
          0,
          Math.min(texture.getWidth(0), initialPixels.getWidth()),
          Math.min(texture.getHeight(0), initialPixels.getHeight()),
          0,
          0
        );
      }
      if (previous != null && previous != texture) {
        removeIfUnreferenced(previous);
      }
    }
  }

  public static void unregister(Identifier location) {
    unregister(GLOBAL_SCOPE, location);
  }

  public static void unregister(TextureManager textureManager, Identifier location) {
    unregister((Object) textureManager, location);
  }

  static void unregister(Object scope, Identifier location) {
    synchronized (LOCK) {
      var scopedTextures = SCOPED_TEXTURES.get(scope);
      if (scopedTextures == null) {
        return;
      }

      var removed = scopedTextures.remove(location);
      if (scopedTextures.isEmpty()) {
        SCOPED_TEXTURES.remove(scope);
      }
      if (removed != null) {
        removeIfUnreferenced(removed);
      }
    }
  }

  public static void registerFallback(
    TextureManager textureManager,
    TextureManager fallbackTextureManager) {
    synchronized (LOCK) {
      FALLBACK_SCOPES.put(textureManager, fallbackTextureManager);
    }
  }

  public static void unregisterAll(TextureManager textureManager) {
    synchronized (LOCK) {
      var removed = SCOPED_TEXTURES.remove(textureManager);
      FALLBACK_SCOPES.remove(textureManager);
      if (removed != null) {
        removed.values().forEach(RendererRuntimeTextureMirror::removeIfUnreferenced);
      }
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
    mirrorWrite(destination, source, 0, 0, destX, destY, width, height, sourceX, sourceY);
  }

  public static void mirrorWrite(
    GpuTexture destination,
    NativeImage source,
    int mipLevel,
    int depthOrLayer,
    int destX,
    int destY,
    int width,
    int height,
    int sourceX,
    int sourceY) {
    synchronized (LOCK) {
      if (!isBaseLayer(mipLevel, depthOrLayer)) {
        skipped("write", destination, "non-base-level:mip=" + mipLevel + ",layer=" + depthOrLayer);
        return;
      }

      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        skipped("write", destination, "untracked-destination");
        return;
      }

      if (!mirrored.write(source, destX, destY, width, height, sourceX, sourceY)) {
        skipped("write", destination, "empty-region");
      }
    }
  }

  public static void mirrorWrite(
    GpuTexture destination,
    ByteBuffer source,
    int destX,
    int destY,
    int width,
    int height) {
    mirrorWrite(destination, source, 0, 0, destX, destY, width, height);
  }

  public static void mirrorWrite(
    GpuTexture destination,
    ByteBuffer source,
    int mipLevel,
    int depthOrLayer,
    int destX,
    int destY,
    int width,
    int height) {
    synchronized (LOCK) {
      if (!isBaseLayer(mipLevel, depthOrLayer)) {
        skipped("write-buffer", destination, "non-base-level:mip=" + mipLevel + ",layer=" + depthOrLayer);
        return;
      }

      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        skipped("write-buffer", destination, "untracked-destination");
        return;
      }
      if (!mirrored.supportsRawBufferWrites()) {
        skipped("write-buffer", destination, "unsupported-format:" + mirrored.format);
        return;
      }

      if (!mirrored.write(source, destX, destY, width, height)) {
        skipped("write-buffer", destination, "empty-region");
      }
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
    mirrorWrite(destination, source, format, 0, 0, destX, destY, width, height);
  }

  public static void mirrorWrite(
    GpuTexture destination,
    ByteBuffer source,
    NativeImage.Format format,
    int mipLevel,
    int depthOrLayer,
    int destX,
    int destY,
    int width,
    int height) {
    synchronized (LOCK) {
      if (!isBaseLayer(mipLevel, depthOrLayer)) {
        skipped("write-buffer", destination, "non-base-level:mip=" + mipLevel + ",layer=" + depthOrLayer);
        return;
      }

      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        skipped("write-buffer", destination, "untracked-destination");
        return;
      }

      if (!mirrored.write(source, format, destX, destY, width, height)) {
        skipped("write-buffer", destination, "empty-region");
      }
    }
  }

  public static void mirrorCopy(
    GpuTexture source,
    GpuTexture destination,
    int destX,
    int destY,
    int sourceX,
    int sourceY,
    int width,
    int height) {
    mirrorCopy(source, destination, 0, destX, destY, sourceX, sourceY, width, height);
  }

  public static void mirrorCopy(
    GpuTexture source,
    GpuTexture destination,
    int mipLevel,
    int destX,
    int destY,
    int sourceX,
    int sourceY,
    int width,
    int height) {
    synchronized (LOCK) {
      if (mipLevel != 0) {
        skipped("copy", destination, "non-base-level:mip=" + mipLevel);
        return;
      }

      var sourceMirrored = mirroredTexture(source);
      var destinationMirrored = mirroredTexture(destination);
      if (sourceMirrored == null) {
        skipped("copy", source, "untracked-source");
        return;
      }
      if (destinationMirrored == null) {
        skipped("copy", destination, "untracked-destination");
        return;
      }
      if (!sourceMirrored.hasUploadData()) {
        skipped("copy", source, "source-empty");
        return;
      }
      if (sourceMirrored.format != destinationMirrored.format) {
        skipped("copy", destination, "format-mismatch:" + sourceMirrored.format + "->" + destinationMirrored.format);
        return;
      }

      if (!destinationMirrored.copyFrom(sourceMirrored, destX, destY, sourceX, sourceY, width, height)) {
        skipped("copy", destination, "empty-region");
      }
    }
  }

  public static void mirrorClear(GpuTexture destination, int color) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        skipped("clear", destination, "untracked-destination");
        return;
      }

      if (!mirrored.clear(color, 0, 0, mirrored.width, mirrored.height)) {
        skipped("clear", destination, "empty-region");
      }
    }
  }

  public static void mirrorClear(GpuTexture destination, int color, int destX, int destY, int width, int height) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(destination);
      if (mirrored == null) {
        skipped("clear", destination, "untracked-destination");
        return;
      }

      if (!mirrored.clear(color, destX, destY, width, height)) {
        skipped("clear", destination, "empty-region");
      }
    }
  }

  @Nullable
  public static RendererAssets.TextureImage texture(Identifier location) {
    synchronized (LOCK) {
      var currentTextureManager = currentTextureManager();
      var mirrored = currentTextureManager != null
        ? mirroredTexture(currentTextureManager, location)
        : null;
      if (mirrored == null) {
        mirrored = mirroredTexture(GLOBAL_SCOPE, location);
      }
      return textureImage(mirrored);
    }
  }

  @Nullable
  public static RendererAssets.TextureImage texture(
    TextureManager textureManager,
    Identifier location) {
    return texture((Object) textureManager, location);
  }

  @Nullable
  static RendererAssets.TextureImage texture(Object scope, Identifier location) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(scope, location);
      return textureImage(mirrored);
    }
  }

  public static List<DebugTextureSnapshot> debugSnapshots() {
    synchronized (LOCK) {
      var snapshots = new ArrayList<DebugTextureSnapshot>(TEXTURES.size());
      for (var entry : TEXTURES.entrySet()) {
        var mirrored = entry.getValue();
        snapshots.add(new DebugTextureSnapshot(
          TEXTURE_IDS.get(entry.getKey()),
          mirrored.width,
          mirrored.height,
          mirrored.mipLevels,
          mirrored.depthOrLayers,
          mirrored.format.toString(),
          mirrored.hasUploadData(),
          textureImage(mirrored)
        ));
      }
      return snapshots;
    }
  }

  @Nullable
  public static RendererAssets.TextureImage texture(GpuTexture texture) {
    synchronized (LOCK) {
      var mirrored = mirroredTexture(texture);
      if (mirrored == null) {
        RenderDebugTrace.current().runtimeTextureMirrorSkipped("lookup", "untracked-texture");
        return null;
      }
      return textureImage(mirrored);
    }
  }

  public record DebugTextureSnapshot(
    Identifier location,
    int width,
    int height,
    int mipLevels,
    int depthOrLayers,
    String format,
    boolean hasUploadData,
    @Nullable RendererAssets.TextureImage texture
  ) {}

  @Nullable
  private static RendererAssets.TextureImage textureImage(MirroredTexture mirrored) {
    if (mirrored == null || !mirrored.hasUploadData()) {
      return null;
    }

    return mirrored.toTextureImage();
  }

  @Nullable
  private static MirroredTexture mirroredTexture(GpuTexture texture) {
    var mirrored = TEXTURES.get(texture);
    if (mirrored == null || !mirrored.matches(texture)) {
      if (!TEXTURE_IDS.containsKey(texture)) {
        return null;
      }
      mirrored = new MirroredTexture(texture);
      TEXTURES.put(texture, mirrored);
    }
    return mirrored;
  }

  @Nullable
  private static MirroredTexture mirroredTexture(Object initialScope, Identifier location) {
    var scope = initialScope;
    var visited = new IdentityHashMap<Object, Boolean>();
    while (scope != null && visited.put(scope, Boolean.TRUE) == null) {
      var scopedTextures = SCOPED_TEXTURES.get(scope);
      var texture = scopedTextures != null ? scopedTextures.get(location) : null;
      if (texture != null) {
        return mirroredTexture(texture);
      }
      scope = FALLBACK_SCOPES.get(scope);
    }
    return null;
  }

  @Nullable
  private static TextureManager currentTextureManager() {
    try {
      var minecraft = Minecraft.getInstance();
      return minecraft != null ? minecraft.getTextureManager() : null;
    } catch (Throwable _) {
      return null;
    }
  }

  private static void removeIfUnreferenced(GpuTexture texture) {
    for (var scopedTextures : SCOPED_TEXTURES.values()) {
      if (scopedTextures.containsValue(texture)) {
        return;
      }
    }

    TEXTURE_IDS.remove(texture);
    TEXTURES.remove(texture);
  }

  private static boolean isBaseLayer(int mipLevel, int depthOrLayer) {
    return mipLevel == 0 && depthOrLayer == 0;
  }

  private static void skipped(String operation, GpuTexture texture, String reason) {
    var location = TEXTURE_IDS.get(texture);
    var subject = location != null ? location.toString() : "untracked";
    RenderDebugTrace.current().runtimeTextureMirrorSkipped(operation, subject + ":" + reason);
  }

  private static final class MirroredTexture {
    private final int width;
    private final int height;
    private final int mipLevels;
    private final int depthOrLayers;
    private final GpuFormat format;
    private final int[] pixels;
    private boolean hasUploadData;

    private MirroredTexture(GpuTexture texture) {
      this.width = texture.getWidth(0);
      this.height = texture.getHeight(0);
      this.mipLevels = texture.getMipLevels();
      this.depthOrLayers = texture.getDepthOrLayers();
      this.format = texture.getFormat();
      this.pixels = new int[Math.max(0, width * height)];
    }

    private boolean matches(GpuTexture texture) {
      return width == texture.getWidth(0)
        && height == texture.getHeight(0)
        && mipLevels == texture.getMipLevels()
        && depthOrLayers == texture.getDepthOrLayers()
        && format == texture.getFormat();
    }

    private boolean write(
      NativeImage source,
      int destX,
      int destY,
      int width,
      int height,
      int sourceX,
      int sourceY) {
      var region = clippedRegion(destX, destY, width, height, sourceX, sourceY, source.getWidth(), source.getHeight());
      if (region == null) {
        return false;
      }
      for (var y = 0; y < region.height(); y++) {
        for (var x = 0; x < region.width(); x++) {
          pixels[region.destX() + x + (region.destY() + y) * this.width] =
            nativeImagePixel(source, region.sourceX() + x, region.sourceY() + y);
        }
      }
      hasUploadData = true;
      return true;
    }

    private boolean write(ByteBuffer source, NativeImage.Format format, int destX, int destY, int width, int height) {
      var region = clippedRegion(destX, destY, width, height, 0, 0, width, height);
      if (region == null) {
        return false;
      }
      var data = source.duplicate();
      var baseOffset = data.position();
      var components = format.components();
      for (var y = 0; y < region.height(); y++) {
        for (var x = 0; x < region.width(); x++) {
          var sourceOffset = baseOffset + (region.sourceX() + x + (region.sourceY() + y) * width) * components;
          pixels[region.destX() + x + (region.destY() + y) * this.width] = bufferPixel(data, format, sourceOffset);
        }
      }
      hasUploadData = true;
      return true;
    }

    private boolean write(ByteBuffer source, int destX, int destY, int width, int height) {
      var region = clippedRegion(destX, destY, width, height, 0, 0, width, height);
      if (region == null) {
        return false;
      }
      var data = source.duplicate();
      var baseOffset = data.position();
      var blockSize = format.blockSize();
      for (var y = 0; y < region.height(); y++) {
        for (var x = 0; x < region.width(); x++) {
          var sourceOffset = baseOffset + (region.sourceX() + x + (region.sourceY() + y) * width) * blockSize;
          pixels[region.destX() + x + (region.destY() + y) * this.width] = bufferPixel(data, format, sourceOffset);
        }
      }
      hasUploadData = true;
      return true;
    }

    private boolean copyFrom(MirroredTexture source, int destX, int destY, int sourceX, int sourceY, int width, int height) {
      var region = clippedRegion(destX, destY, width, height, sourceX, sourceY, source.width, source.height);
      if (region == null) {
        return false;
      }

      var copied = new int[region.width() * region.height()];
      for (var y = 0; y < region.height(); y++) {
        System.arraycopy(
          source.pixels,
          region.sourceX() + (region.sourceY() + y) * source.width,
          copied,
          y * region.width(),
          region.width()
        );
      }
      for (var y = 0; y < region.height(); y++) {
        System.arraycopy(
          copied,
          y * region.width(),
          pixels,
          region.destX() + (region.destY() + y) * this.width,
          region.width()
        );
      }
      hasUploadData = true;
      return true;
    }

    private boolean clear(int color, int destX, int destY, int width, int height) {
      var region = clippedRegion(destX, destY, width, height, 0, 0, width, height);
      if (region == null) {
        return false;
      }

      for (var y = 0; y < region.height(); y++) {
        var rowStart = region.destX() + (region.destY() + y) * this.width;
        Arrays.fill(pixels, rowStart, rowStart + region.width(), color);
      }
      hasUploadData = true;
      return true;
    }

    @Nullable
    private WriteRegion clippedRegion(int destX, int destY, int width, int height, int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
      if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return null;
      }

      if (destX < 0) {
        var delta = -destX;
        destX = 0;
        sourceX += delta;
        width -= delta;
      }
      if (destY < 0) {
        var delta = -destY;
        destY = 0;
        sourceY += delta;
        height -= delta;
      }
      if (sourceX < 0) {
        var delta = -sourceX;
        sourceX = 0;
        destX += delta;
        width -= delta;
      }
      if (sourceY < 0) {
        var delta = -sourceY;
        sourceY = 0;
        destY += delta;
        height -= delta;
      }

      width = Math.min(width, this.width - destX);
      height = Math.min(height, this.height - destY);
      width = Math.min(width, sourceWidth - sourceX);
      height = Math.min(height, sourceHeight - sourceY);
      if (width <= 0 || height <= 0) {
        return null;
      }

      return new WriteRegion(destX, destY, width, height, sourceX, sourceY);
    }

    private int nativeImagePixel(NativeImage source, int x, int y) {
      if (isRedFormat(format) || source.format().hasLuminance()) {
        var alpha = Byte.toUnsignedInt(source.getLuminanceOrAlpha(x, y));
        return (alpha << 24) | 0x00FFFFFF;
      }

      return source.getPixel(x, y);
    }

    private int bufferPixel(ByteBuffer source, NativeImage.Format format, int offset) {
      if (isRedFormat(this.format) || format.hasLuminance()) {
        var alpha = channel(source, format, offset, format.luminanceOrAlphaOffset());
        return (alpha << 24) | 0x00FFFFFF;
      }

      var red = channel(source, format, offset, format.luminanceOrRedOffset());
      var green = channel(source, format, offset, format.luminanceOrGreenOffset());
      var blue = channel(source, format, offset, format.luminanceOrBlueOffset());
      var alpha = format.hasLuminanceOrAlpha() ? channel(source, format, offset, format.luminanceOrAlphaOffset()) : 0xFF;
      return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int bufferPixel(ByteBuffer source, GpuFormat format, int offset) {
      var red = rawBufferChannel(source, offset, 0);
      if (format.componentCount() == 1) {
        return (red << 24) | 0x00FFFFFF;
      }

      var green = rawBufferChannel(source, offset, 1);
      var blue = format.componentCount() >= 3 ? rawBufferChannel(source, offset, 2) : 0;
      var alpha = format.componentCount() >= 4 ? rawBufferChannel(source, offset, 3) : 0xFF;
      return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private boolean supportsRawBufferWrites() {
      return format.hasColorAspect()
        && format.componentType().byteSize() == 1
        && format.componentCount() >= 1
        && format.componentCount() <= 4;
    }

    private static boolean isRedFormat(GpuFormat format) {
      return format == GpuFormat.R8_UNORM
        || format == GpuFormat.R8_SNORM
        || format == GpuFormat.R8_UINT
        || format == GpuFormat.R8_SINT;
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

    private int rawBufferChannel(ByteBuffer source, int offset, int component) {
      var byteOffset = offset + component;
      return byteOffset >= 0 && byteOffset < source.limit() ? Byte.toUnsignedInt(source.get(byteOffset)) : 0;
    }

    private boolean hasUploadData() {
      return hasUploadData;
    }

    @Nullable
    private RendererAssets.TextureImage toTextureImage() {
      return RendererAssets.TextureImage.fromArgb(width, height, pixels, null);
    }
  }

  private record WriteRegion(int destX, int destY, int width, int height, int sourceX, int sourceY) {}
}
