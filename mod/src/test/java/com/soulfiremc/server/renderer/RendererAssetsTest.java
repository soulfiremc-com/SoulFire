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

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererAssetsTest {
  @Test
  void terrainAnimationUsesCapturedFramesAndKeepsEarlierSnapshotsStable() {
    try (var frames = new NativeImage(2, 1, true)) {
      frames.setPixel(0, 0, 0xFFFF0000);
      frames.setPixel(1, 0, 0xFF0000FF);
      var source = RendererAssets.TextureImage.fromArgb(2, 1, new int[]{0xFFFF0000, 0xFF0000FF}, null);
      var mips = new NativeImage[]{frames};
      var first = source.withTerrainFrame(mips, 1, 1, 0, 0, 1, 1, 2, 0, 1, 0);
      assertSame(first, source.withTerrainFrame(mips, 1, 1, 0, 0, 1, 1, 2, 0, 1, 0));
      var blended = source.withTerrainFrame(mips, 1, 1, 0, 0, 1, 1, 2, 0, 1, 0.5F);
      var second = source.withTerrainFrame(mips, 1, 1, 0, 0, 1, 1, 2, 1, 0, 0);
      assertEquals(0xFFFF0000, first.sampleTerrain(0.5F, 0.5F, 6000, 0, 0, 0, 0));
      assertEquals(0xFF800080, blended.sampleTerrain(0.5F, 0.5F, 6000, 0, 0, 0, 0));
      assertEquals(0xFF0000FF, second.sampleTerrain(0.5F, 0.5F, 6000, 0, 0, 0, 0));
    }
  }

  @Test
  void terrainSamplingSelectsAndBlendsMipLevelsFromThePixelFootprint() {
    try (var base = new NativeImage(2, 2, true); var mip = new NativeImage(1, 1, true)) {
      base.fillRect(0, 0, 2, 2, 0xFFFF0000);
      mip.setPixel(0, 0, 0xFF0000FF);
      var texture = RendererAssets.TextureImage.fromArgb(2, 2,
        new int[]{-1, -1, -1, -1}, null).withTerrainFiltering(new NativeImage[]{base, mip}, 2, 2, 0, 0);

      assertEquals(0xFFFF0000, texture.sampleTerrain(0.25F, 0.25F, 0, 0.01F, 0, 0, 0.01F));
      assertEquals(0xFF0000FF, texture.sampleTerrain(0.25F, 0.25F, 0, 1, 0, 0, 1));
      // Lavapipe approximates log2(2.25) / 2 as 0.5625, giving a mip weight of 144/256.
      assertEquals(0xFF70008F, texture.sampleTerrain(0.25F, 0.25F, 0, 0.75F, 0, 0, 0));
    }
  }

  @Test
  void linearFilteringInterpolatesTexelsAndClampsSpriteEdges() {
    var texture = RendererAssets.TextureImage.fromArgb(2, 2,
      new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF000000}, null);
    var filtered = texture.withAddressMode(RendererAssets.TextureAddressMode.CLAMP_TO_EDGE).withLinearFiltering();

    // Each filter axis rounds its signed interpolation delta to nearest even.
    assertEquals(0xFF3F4040, filtered.sample(0.5F, 0.5F, 0));
    assertEquals(0xFFFF0000, filtered.sample(0.25F, 0.25F, 0));
    assertEquals(0xFFFF0000, filtered.sample(0, 0, 0));
    assertEquals(0xFF00FF00, filtered.sample(1, 0, 0));
    assertEquals(0xFF404040, texture.withLinearFiltering().sample(0, 0, 0));
    assertEquals(0xFF000000, texture.sample(0.5F, 0.5F, 0));
  }

  @Test
  void textureMetadataControlsFilteringAndEdgeAddressing() {
    var settings = new JsonObject();
    settings.addProperty("blur", true);
    settings.addProperty("clamp", true);
    var metadata = new JsonObject();
    metadata.add("texture", settings);
    var texture = RendererAssets.TextureImage.fromArgb(2, 1,
      new int[]{0xFFFF0000, 0xFF0000FF}, metadata);

    assertEquals(0xFFFF0000, texture.sample(0, 0.5F, 0));
    assertEquals(0xFF7F0080, texture.sample(0.5F, 0.5F, 0));
    assertEquals(0xFF0000FF, texture.sample(1, 0.5F, 0));
  }

  @Test
  void staticPortraitTextureUsesItsFullHeightAtEveryTick() {
    var texture = RendererAssets.TextureImage.fromArgb(2, 3,
      new int[]{0xFFFF0000, 0xFFFF0000, 0xFF00FF00, 0xFF00FF00, 0xFF0000FF, 0xFF0000FF}, null);

    for (var tick : new long[]{0, 1, 6000}) {
      assertEquals(0xFFFF0000, texture.sample(0.5F, 1.0F / 6, tick));
      assertEquals(0xFF00FF00, texture.sample(0.5F, 0.5F, tick));
      assertEquals(0xFF0000FF, texture.sample(0.5F, 5.0F / 6, tick));
    }
  }

  @Test
  void mapsVanillaChunkLayersToRasterAlphaModes() {
    assertEquals(RendererAssets.AlphaMode.OPAQUE, RendererAssets.alphaModeForVanillaLayer(ChunkSectionLayer.SOLID));
    assertEquals(RendererAssets.AlphaMode.CUTOUT, RendererAssets.alphaModeForVanillaLayer(ChunkSectionLayer.CUTOUT));
    assertEquals(RendererAssets.AlphaMode.TRANSLUCENT, RendererAssets.alphaModeForVanillaLayer(ChunkSectionLayer.TRANSLUCENT));
  }

  @Test
  void terrainQuadsApplyVanillaChunkLayerPipelineState() {
    var face = face(ChunkSectionLayer.TRANSLUCENT);
    var quad = WorldMeshCollector.toTerrainRenderQuad(face, 0.0, 0.0, 0.0, 0xFFFFFFFF, false, 0.0F);

    assertEquals(RendererAssets.AlphaMode.TRANSLUCENT, quad.material().alphaMode());
    assertEquals(RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT), quad.material().blendState());
    assertEquals(RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL, quad.material().depthTest());
    assertTrue(quad.material().depthWrite());
    assertEquals(RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD, quad.material().alphaCutoutThreshold());
  }

  @Test
  void nonTerrainQuadsDoNotApplyChunkLayerPipelineState() {
    var face = face(ChunkSectionLayer.TRANSLUCENT);
    var quad = WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, 0xFFFFFFFF, false, 0.0F);

    assertEquals(RendererAssets.AlphaMode.TRANSLUCENT, quad.material().alphaMode());
    assertFalse(quad.material().depthWrite());
  }

  @Test
  void samplerAddressModesArePreservedPerAxis() {
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
    var sampler = new FakeSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.REPEAT);

    var sampled = RendererAssets.withSampler(texture, sampler);

    assertEquals(RendererAssets.TextureAddressMode.CLAMP_TO_EDGE, sampled.addressModeU());
    assertEquals(RendererAssets.TextureAddressMode.REPEAT, sampled.addressModeV());
  }

  private static RendererAssets.GeometryFace face(ChunkSectionLayer layer) {
    return RendererAssets.GeometryFace.of(
      new Vector3f[]{
        new Vector3f(-1.0F, -1.0F, 4.0F),
        new Vector3f(-1.0F, 1.0F, 4.0F),
        new Vector3f(1.0F, 1.0F, 4.0F),
        new Vector3f(1.0F, -1.0F, 4.0F)
      },
      new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
      RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null),
      RendererAssets.alphaModeForVanillaLayer(layer),
      layer,
      null,
      -1,
      0,
      true
    );
  }

  private static final class FakeSampler extends GpuSampler {
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;

    private FakeSampler(AddressMode addressModeU, AddressMode addressModeV) {
      this.addressModeU = addressModeU;
      this.addressModeV = addressModeV;
    }

    @Override
    public AddressMode getAddressModeU() {
      return addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
      return addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
      return FilterMode.NEAREST;
    }

    @Override
    public FilterMode getMagFilter() {
      return FilterMode.NEAREST;
    }

    @Override
    public int getMaxAnisotropy() {
      return 1;
    }

    @Override
    public OptionalDouble getMaxLod() {
      return OptionalDouble.empty();
    }

    @Override
    public void close() {
    }
  }
}
