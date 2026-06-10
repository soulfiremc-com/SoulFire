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
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererAssetsRuntimeTextureTest {
  @BeforeAll
  static void bootstrap() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void detectsDownloadedSkinTextureIdsAsRuntimeTextures() {
    assertTrue(RendererAssets.isRuntimeClientTexturePath(Identifier.withDefaultNamespace("skins/abc123")));
    assertTrue(RendererAssets.isRuntimeClientTexturePath(Identifier.withDefaultNamespace("capes/abc123")));
    assertTrue(RendererAssets.isRuntimeClientTexturePath(Identifier.withDefaultNamespace("elytra/abc123")));
  }

  @Test
  void keepsResourcePackTextureIdsResourceBacked() {
    assertFalse(RendererAssets.isRuntimeClientTexturePath(Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")));
    assertFalse(RendererAssets.isRuntimeClientTexturePath(Identifier.withDefaultNamespace("block/stone")));
  }

  @Test
  void keepsBinaryAlphaBlendedRenderTypesOnTranslucentPath() {
    var texture = textureWithAlpha(0);
    var renderType = RenderTypes.entityTranslucent(Identifier.withDefaultNamespace("skins/test"));

    assertEquals(
      RendererAssets.AlphaMode.TRANSLUCENT,
      VanillaSubmitCollector.alphaMode(renderType, texture, 0xFFFFFFFF)
    );
  }

  @Test
  void keepsPartialAlphaBlendedTexturesOnTranslucentPath() {
    var texture = textureWithAlpha(128);
    var renderType = RenderTypes.entityTranslucent(Identifier.withDefaultNamespace("skins/test"));

    assertEquals(
      RendererAssets.AlphaMode.TRANSLUCENT,
      VanillaSubmitCollector.alphaMode(renderType, texture, 0xFFFFFFFF)
    );
  }

  @Test
  void classifiesOnlyTheSubmittedUvRegion() {
    var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        image.setRGB(x, y, y < 2 ? 0xFFFFFFFF : 0x80FFFFFF);
      }
    }
    var texture = RendererAssets.TextureImage.from(image, null);
    var opaqueUv = new float[]{0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.5F, 0.0F, 0.5F};
    var translucentUv = new float[]{0.0F, 0.5F, 1.0F, 0.5F, 1.0F, 1.0F, 0.0F, 1.0F};

    assertEquals(
      RendererAssets.AlphaMode.OPAQUE,
      VanillaSubmitCollector.alphaMode(null, texture, 0xFFFFFFFF, opaqueUv)
    );
    assertEquals(
      RendererAssets.AlphaMode.CUTOUT,
      VanillaSubmitCollector.alphaMode(null, texture, 0xFFFFFFFF, translucentUv)
    );
  }

  @Test
  void mirrorsLuminanceRuntimeTextureUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-luminance");
    var gpuTexture = new FakeGpuTexture(TextureFormat.RED8, 4, 4);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try (var source = new NativeImage(NativeImage.Format.LUMINANCE, 2, 1, false)) {
      MemoryUtil.memPutByte(source.getPointer(), (byte) 0x40);
      MemoryUtil.memPutByte(source.getPointer() + 1, (byte) 0xE0);
      RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 1, 2, 2, 1, 0, 0);
    }

    var mirrored = RendererRuntimeTextureMirror.texture(location);
    assertNotNull(mirrored);
    var image = mirrored.toBufferedImage();
    assertEquals(0x40FFFFFF, image.getRGB(1, 2));
    assertEquals(0xE0FFFFFF, image.getRGB(2, 2));
    assertEquals(0x00000000, image.getRGB(0, 0));
  }

  @Test
  void normalizesMirroredPlayerSkinAlphaWithVanillaRules() {
    var location = Identifier.withDefaultNamespace("skins/test-alpha-normalization");
    var gpuTexture = new FakeGpuTexture(TextureFormat.RGBA8, 64, 64);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      try (var source = new NativeImage(64, 64, true)) {
        source.setPixel(8, 8, 0x40112233);
        source.setPixel(40, 8, 0x40223344);
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 0, 0, 64, 64, 0, 0);
      }

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF112233, image.getRGB(8, 8));
      assertEquals(0x40223344, image.getRGB(40, 8));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void ignoresRuntimeTextureMirrorBeforeUploadDataExists() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-empty");
    RendererRuntimeTextureMirror.register(location, new FakeGpuTexture(TextureFormat.RGBA8, 2, 2));

    assertNull(RendererRuntimeTextureMirror.texture(location));
  }

  @Test
  void mirrorsByteBufferRuntimeTextureUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-byte-buffer");
    var gpuTexture = new FakeGpuTexture(TextureFormat.RGBA8, 2, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    var source = ByteBuffer.allocateDirect(8);
    source.put((byte) 0x10);
    source.put((byte) 0x20);
    source.put((byte) 0x30);
    source.put((byte) 0x40);
    source.put((byte) 0x50);
    source.put((byte) 0x60);
    source.put((byte) 0x70);
    source.put((byte) 0x80);
    source.flip();
    RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, NativeImage.Format.RGBA, 0, 1, 2, 1);

    var mirrored = RendererRuntimeTextureMirror.texture(location);
    assertNotNull(mirrored);
    var image = mirrored.toBufferedImage();
    assertEquals(0x40102030, image.getRGB(0, 1));
    assertEquals(0x80506070, image.getRGB(1, 1));
    assertEquals(0x00000000, image.getRGB(0, 0));
  }

  private RendererAssets.TextureImage textureWithAlpha(int alpha) {
    var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xFFFFFFFF);
    image.setRGB(1, 0, (alpha << 24) | 0x00FFFFFF);
    return RendererAssets.TextureImage.from(image, null);
  }

  private static final class FakeGpuTexture extends GpuTexture {
    private boolean closed;

    private FakeGpuTexture(TextureFormat format, int width, int height) {
      super(GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, "test runtime texture", format, width, height, 1, 1);
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }
  }
}
