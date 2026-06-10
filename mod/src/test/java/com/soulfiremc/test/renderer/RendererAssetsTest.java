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
package com.soulfiremc.test.renderer;

import com.soulfiremc.server.renderer.RendererAssets;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class RendererAssetsTest {
  @BeforeAll
  static void bootstrap() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void loadsBundledBlockTexture() {
    var texture = RendererAssets.instance().texture(Identifier.withDefaultNamespace("block/stone"));
    assertTrue(texture.width() > 0);
    assertTrue(texture.height() > 0);
  }

  @Test
  void loadsSandTextureWithVisiblePixelVariation() {
    var texture = RendererAssets.instance().texture(Identifier.withDefaultNamespace("block/sand"));
    var image = texture.toBufferedImage();
    var colors = new HashSet<Integer>();
    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        colors.add(image.getRGB(x, y) & 0x00FFFFFF);
      }
    }

    assertTrue(colors.size() > 1);
  }

  @Test
  void resolvesVanillaBlockGeometryWithoutCrashing() {
    var assets = RendererAssets.instance();
    var stoneGeometry = assertDoesNotThrow(() -> assets.blockGeometry(Blocks.STONE.defaultBlockState()));
    var slabGeometry = assertDoesNotThrow(() -> assets.blockGeometry(Blocks.STONE_SLAB.defaultBlockState()));
    var glassGeometry = assets.blockGeometry(Blocks.GLASS.defaultBlockState());
    assertNotNull(stoneGeometry);
    assertNotNull(slabGeometry);
    assertNotNull(glassGeometry);
    if (!glassGeometry.faces().isEmpty()) {
      assertTrue(glassGeometry.faces().stream().anyMatch(face -> face.alphaMode() == RendererAssets.AlphaMode.TRANSLUCENT));
    }
  }

  @Test
  void vanillaBlockGeometryPreservesSpriteLocalUvRange() {
    var geometry = RendererAssets.instance().blockGeometry(Blocks.STONE.defaultBlockState());
    Assumptions.assumeFalse(
      geometry.faces().isEmpty(),
      "Live vanilla model geometry required to verify baked quad UV extraction"
    );

    assertTrue(geometry.faces().stream().anyMatch(face -> uvRange(face.uv(), 0) > 0.9F && uvRange(face.uv(), 1) > 0.9F));
  }

  @Test
  void vanillaLeafGeometryKeepsCutoutTintMetadata() {
    var geometry = RendererAssets.instance().blockGeometry(Blocks.OAK_LEAVES.defaultBlockState());
    Assumptions.assumeFalse(
      geometry.faces().isEmpty(),
      "Live vanilla model geometry required to verify foliage tint extraction"
    );

    assertTrue(geometry.faces().stream().anyMatch(face -> face.alphaMode() == RendererAssets.AlphaMode.CUTOUT));
    assertTrue(geometry.faces().stream().anyMatch(face -> face.tintIndex() >= 0));
  }

  @Test
  void resolvesTextRenderAssets() {
    var assets = RendererAssets.instance();
    var textTexture = assets.textTexture(Component.literal("SoulFire"), 96, 0xFFFFFFFF, 0x66000000);
    assertTrue(textTexture.width() > 0);
    assertEquals(9, textTexture.height());
  }

  @Test
  void samplesWideNonAnimatedTextureWithoutOverflow() {
    var image = new BufferedImage(8, 1, BufferedImage.TYPE_INT_ARGB);
    for (var x = 0; x < image.getWidth(); x++) {
      image.setRGB(x, 0, 0xFF000000 | x);
    }

    var texture = RendererAssets.TextureImage.from(image, null);
    assertDoesNotThrow(() -> texture.sample(0.25F, 0.75F, 0L));
    assertEquals(0xFF000002, texture.sample(0.25F, 0.75F, 0L));
  }

  private float uvRange(float[] uv, int axis) {
    var min = Float.POSITIVE_INFINITY;
    var max = Float.NEGATIVE_INFINITY;
    for (var i = axis; i < uv.length; i += 2) {
      min = Math.min(min, uv[i]);
      max = Math.max(max, uv[i]);
    }

    return max - min;
  }
}
