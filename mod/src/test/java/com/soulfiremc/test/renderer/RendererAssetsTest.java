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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

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
  void resolvesTextRenderAssets() {
    var assets = RendererAssets.instance();
    var textTexture = assets.textTexture(Component.literal("SoulFire"), 96, 0xFFFFFFFF, 0x66000000);
    assertTrue(textTexture.width() > 0);
    assertTrue(textTexture.height() > 0);
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
}
