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
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererAssetsTest {
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

    var sampled = RendererAssets.withSamplerAddressMode(texture, sampler);

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
