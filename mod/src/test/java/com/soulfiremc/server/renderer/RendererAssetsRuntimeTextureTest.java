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
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
  void keepsBinaryAlphaEntityTranslucentSkinsOnTranslucentPath() {
    var texture = textureWithAlpha(0);
    var renderType = RenderTypes.entityTranslucent(Identifier.withDefaultNamespace("skins/test"));

    assertEquals(
      RendererAssets.AlphaMode.TRANSLUCENT,
      VanillaSubmitCollector.alphaMode(renderType, texture, 0xFFFFFFFF)
    );
  }

  @Test
  void keepsBinaryAlphaOtherBlendedRenderTypesOnTranslucentPath() {
    var texture = textureWithAlpha(0);
    var renderType = RenderTypes.entityShadow(Identifier.withDefaultNamespace("textures/misc/shadow.png"));

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
  void reusesTextureSnapshotsUntilPixelsChange() {
    var location = Identifier.withDefaultNamespace("test/snapshot-cache");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
    try (var source = new NativeImage(2, 2, false)) {
      source.fillRect(0, 0, 2, 2, 0xFF123456);
      RendererRuntimeTextureMirror.register(location, gpuTexture, source);
      var before = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(before);
      assertSame(before, RendererRuntimeTextureMirror.texture(gpuTexture));
      source.setPixel(0, 0, 0xFFABCDEF);
      RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 0, 0, 1, 1, 0, 0);
      var after = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(after);
      assertNotSame(before, after);
      assertSame(after, RendererRuntimeTextureMirror.texture(location));
      assertEquals(0xFF123456, before.toBufferedImage().getRGB(0, 0));
      assertEquals(0xFFABCDEF, after.toBufferedImage().getRGB(0, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsLuminanceRuntimeTextureUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-luminance");
    var gpuTexture = new FakeGpuTexture(GpuFormat.R8_UNORM, 4, 4);
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
  void mirrorsPlayerSkinPixelsFromNativeImageUpload() {
    var location = Identifier.withDefaultNamespace("skins/test-nativeimage-upload");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 64, 64);
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
      assertEquals(0x40112233, image.getRGB(8, 8));
      assertEquals(0x40223344, image.getRGB(40, 8));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void nativeImageReadPreservesPixelsForVanillaSkinNormalization() throws Exception {
    var encoded = new ByteArrayOutputStream();
    var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(8, 8, 0x40112233);
    ImageIO.write(image, "png", encoded);

    var decoded = NativeImage.read(encoded.toByteArray());
    try {
      var normalized = SkinTextureDownloader.processLegacySkin(decoded, "memory:test-skin");
      decoded = null;
      try (normalized) {
        assertEquals(0xFF112233, normalized.getPixel(8, 8));
      }
    } finally {
      if (decoded != null) {
        decoded.close();
      }
    }
  }

  @Test
  void mirrorsBlankPlayerSkinUploads() {
    var location = Identifier.withDefaultNamespace("skins/test-blank-upload");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 64, 64);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try (var source = new NativeImage(64, 64, true)) {
      RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 0, 0, 64, 64, 0, 0);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      assertTrue(mirrored.isFullyTransparent());
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsBlankInitialDynamicTexturePixels() {
    var location = Identifier.withDefaultNamespace("map/test-blank-registration");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);

    try (var source = new NativeImage(2, 2, true)) {
      RendererRuntimeTextureMirror.register(location, gpuTexture, source);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      assertTrue(mirrored.isFullyTransparent());
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsMapTextureUploadAfterBlankRegistration() {
    var location = Identifier.withDefaultNamespace("map/test-upload-after-blank");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);

    try {
      try (var blank = new NativeImage(2, 2, true)) {
        RendererRuntimeTextureMirror.register(location, gpuTexture, blank);
      }

      try (var loaded = new NativeImage(2, 2, true)) {
        loaded.setPixel(1, 1, 0xFF112233);
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, loaded, 0, 0, 2, 2, 0, 0);
      }

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      assertEquals(0xFF112233, mirrored.toBufferedImage().getRGB(1, 1));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void allowsMirroredMapTextureToBecomeTransparentAfterUploadDataExists() {
    var location = Identifier.withDefaultNamespace("map/test-transparent-overwrite");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      try (var loaded = new NativeImage(2, 2, true)) {
        loaded.setPixel(0, 0, 0xFF112233);
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, loaded, 0, 0, 2, 2, 0, 0);
      }

      try (var blank = new NativeImage(2, 2, true)) {
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, blank, 0, 0, 2, 2, 0, 0);
      }

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      assertTrue(mirrored.isFullyTransparent());
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsInitialDynamicTexturePixelsOnRegistration() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-initial");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 1);

    try {
      try (var source = new NativeImage(2, 1, true)) {
        source.setPixel(0, 0, 0xFF112233);
        source.setPixel(1, 0, 0x80445566);
        RendererRuntimeTextureMirror.register(location, gpuTexture, source);
      }

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF112233, image.getRGB(0, 0));
      assertEquals(0x80445566, image.getRGB(1, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void ignoresRuntimeTextureMirrorBeforeUploadDataExists() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-empty");
    RendererRuntimeTextureMirror.register(location, new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2));

    assertNull(RendererRuntimeTextureMirror.texture(location));
  }

  @Test
  void mirrorsByteBufferRuntimeTextureUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-byte-buffer");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
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

  @Test
  void mirrorsRawByteBufferRuntimeTextureUploadsFromGpuFormat() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-raw-byte-buffer");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGB8_UNORM, 2, 1);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      var source = ByteBuffer.allocateDirect(6);
      source.put((byte) 0x10);
      source.put((byte) 0x20);
      source.put((byte) 0x30);
      source.put((byte) 0x40);
      source.put((byte) 0x50);
      source.put((byte) 0x60);
      source.flip();
      RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 0, 0, 2, 1);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF102030, image.getRGB(0, 0));
      assertEquals(0xFF405060, image.getRGB(1, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void clipsPartiallyOutOfBoundsNativeImageUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-native-clip");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      try (var source = new NativeImage(3, 1, true)) {
        source.setPixel(0, 0, 0xFF102030);
        source.setPixel(1, 0, 0xFF405060);
        source.setPixel(2, 0, 0xFF708090);
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, -1, 1, 3, 1, 0, 0);
      }

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF405060, image.getRGB(0, 1));
      assertEquals(0xFF708090, image.getRGB(1, 1));
      assertEquals(0x00000000, image.getRGB(0, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void clipsPartiallyOutOfBoundsByteBufferUploads() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-byte-buffer-clip");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      var source = ByteBuffer.allocateDirect(12);
      source.put((byte) 0x10);
      source.put((byte) 0x20);
      source.put((byte) 0x30);
      source.put((byte) 0x40);
      source.put((byte) 0x50);
      source.put((byte) 0x60);
      source.put((byte) 0x70);
      source.put((byte) 0x80);
      source.put((byte) 0x90);
      source.put((byte) 0xA0);
      source.put((byte) 0xB0);
      source.put((byte) 0xC0);
      source.flip();
      RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, NativeImage.Format.RGBA, -1, 0, 3, 1);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0x80506070, image.getRGB(0, 0));
      assertEquals(0xC090A0B0, image.getRGB(1, 0));
      assertEquals(0x00000000, image.getRGB(0, 1));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsTextureCopiesBetweenTrackedRuntimeTextures() {
    var sourceLocation = Identifier.withDefaultNamespace("test/runtime-mirror-copy-source");
    var destinationLocation = Identifier.withDefaultNamespace("test/runtime-mirror-copy-destination");
    var sourceTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 3, 1);
    var destinationTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 1);
    RendererRuntimeTextureMirror.register(sourceLocation, sourceTexture);
    RendererRuntimeTextureMirror.register(destinationLocation, destinationTexture);

    try {
      try (var source = new NativeImage(3, 1, true)) {
        source.setPixel(0, 0, 0xFF102030);
        source.setPixel(1, 0, 0xFF405060);
        source.setPixel(2, 0, 0xFF708090);
        RendererRuntimeTextureMirror.mirrorWrite(sourceTexture, source, 0, 0, 3, 1, 0, 0);
      }

      RendererRuntimeTextureMirror.mirrorCopy(sourceTexture, destinationTexture, 0, 0, 1, 0, 2, 1);

      var mirrored = RendererRuntimeTextureMirror.texture(destinationLocation);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF405060, image.getRGB(0, 0));
      assertEquals(0xFF708090, image.getRGB(1, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(sourceLocation);
      RendererRuntimeTextureMirror.unregister(destinationLocation);
    }
  }

  @Test
  void mirrorsOverlappingRuntimeTextureSelfCopiesFromOriginalPixels() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-copy-overlap");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 4, 1);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      try (var source = new NativeImage(4, 1, true)) {
        source.setPixel(0, 0, 0xFF102030);
        source.setPixel(1, 0, 0xFF405060);
        source.setPixel(2, 0, 0xFF708090);
        source.setPixel(3, 0, 0xFFA0B0C0);
        RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 0, 0, 4, 1, 0, 0);
      }

      RendererRuntimeTextureMirror.mirrorCopy(gpuTexture, gpuTexture, 1, 0, 0, 0, 3, 1);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF102030, image.getRGB(0, 0));
      assertEquals(0xFF102030, image.getRGB(1, 0));
      assertEquals(0xFF405060, image.getRGB(2, 0));
      assertEquals(0xFF708090, image.getRGB(3, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void mirrorsRuntimeTextureClears() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-clear");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 2, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      RendererRuntimeTextureMirror.mirrorClear(gpuTexture, 0xFF102030);
      RendererRuntimeTextureMirror.mirrorClear(gpuTexture, 0x80405060, 1, 0, 1, 2);

      var mirrored = RendererRuntimeTextureMirror.texture(location);
      assertNotNull(mirrored);
      var image = mirrored.toBufferedImage();
      assertEquals(0xFF102030, image.getRGB(0, 0));
      assertEquals(0x80405060, image.getRGB(1, 0));
      assertEquals(0xFF102030, image.getRGB(0, 1));
      assertEquals(0x80405060, image.getRGB(1, 1));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void nonBaseRuntimeTextureWritesAreReportedAndIgnored() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-non-base-write");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 4, 4, 2, 2);
    var trace = RenderDebugTrace.createForced(1, 1, 1, 0.0F, 0.0F);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try (var source = new NativeImage(2, 2, true)) {
      source.setPixel(0, 0, 0xFF102030);
      trace.run(() -> RendererRuntimeTextureMirror.mirrorWrite(gpuTexture, source, 1, 1, 0, 0, 2, 2, 0, 0));

      assertNull(RendererRuntimeTextureMirror.texture(location));
      assertEquals(1, trace.snapshot().runtimeTextureMirrorSkips());
      assertTrue(trace.snapshot().notableEvents().stream().anyMatch(event ->
        event.contains("runtime-texture-skip:write:")
          && event.contains("non-base-level:mip=1,layer=1")));
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void runtimeTextureDebugSnapshotsExposeTextureShape() {
    var location = Identifier.withDefaultNamespace("test/runtime-mirror-debug-shape");
    var gpuTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 4, 4, 3, 2);
    RendererRuntimeTextureMirror.register(location, gpuTexture);

    try {
      var snapshot = RendererRuntimeTextureMirror.debugSnapshots()
        .stream()
        .filter(candidate -> candidate.location().equals(location))
        .findFirst()
        .orElseThrow();

      assertEquals(4, snapshot.width());
      assertEquals(4, snapshot.height());
      assertEquals(3, snapshot.mipLevels());
      assertEquals(2, snapshot.depthOrLayers());
      assertFalse(snapshot.hasUploadData());
    } finally {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Test
  void isolatesRuntimeTextureIdsBetweenTextureManagers() {
    var location = Identifier.withDefaultNamespace("map/1");
    var firstTextureManager = new Object();
    var secondTextureManager = new Object();
    var firstTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 1, 1);
    var secondTexture = new FakeGpuTexture(GpuFormat.RGBA8_UNORM, 1, 1);

    try (
      var firstPixels = new NativeImage(1, 1, false);
      var secondPixels = new NativeImage(1, 1, false)) {
      firstPixels.setPixel(0, 0, 0xFFFF0000);
      secondPixels.setPixel(0, 0, 0xFF0000FF);
      RendererRuntimeTextureMirror.register(
        firstTextureManager,
        location,
        firstTexture,
        firstPixels);
      RendererRuntimeTextureMirror.register(
        secondTextureManager,
        location,
        secondTexture,
        secondPixels);

      assertEquals(
        0xFFFF0000,
        RendererRuntimeTextureMirror.texture(firstTextureManager, location)
          .toBufferedImage()
          .getRGB(0, 0));
      assertEquals(
        0xFF0000FF,
        RendererRuntimeTextureMirror.texture(secondTextureManager, location)
          .toBufferedImage()
          .getRGB(0, 0));
    } finally {
      RendererRuntimeTextureMirror.unregister(firstTextureManager, location);
      RendererRuntimeTextureMirror.unregister(secondTextureManager, location);
    }
  }

  private RendererAssets.TextureImage textureWithAlpha(int alpha) {
    var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xFFFFFFFF);
    image.setRGB(1, 0, (alpha << 24) | 0x00FFFFFF);
    return RendererAssets.TextureImage.from(image, null);
  }

  private static final class FakeGpuTexture extends GpuTexture {
    private boolean closed;

    private FakeGpuTexture(GpuFormat format, int width, int height) {
      this(format, width, height, 1, 1);
    }

    private FakeGpuTexture(GpuFormat format, int width, int height, int mipLevels, int depthOrLayers) {
      super(
        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
        "test runtime texture",
        format,
        width,
        height,
        depthOrLayers,
        mipLevels
      );
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
