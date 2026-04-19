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

import com.soulfiremc.server.renderer.InventoryItemIconRenderer;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class InventoryItemIconRendererTest {
  private static ItemStack itemStack(Item item) {
    return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), 1);
  }

  @BeforeAll
  static void bootstrap() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void rendersStaticItemAsPng() throws Exception {
    var result = InventoryItemIconRenderer.render(null, null, null, itemStack(Items.DIAMOND_SWORD));

    assertEquals(InventoryItemIconRenderer.PNG_MIME_TYPE, result.mimeType());
    assertFalse(result.base64().isEmpty());

    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.base64())));
    assertNotNull(image);
    assertTrue(image.getWidth() > 0);
    assertEquals(image.getWidth(), image.getHeight());

    var hasVisiblePixel = false;
    for (var y = 0; y < image.getHeight() && !hasVisiblePixel; y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) {
          hasVisiblePixel = true;
          break;
        }
      }
    }

    assertTrue(hasVisiblePixel);

    var bounds = visibleBounds(image);
    assertNotNull(bounds);
    assertTrue(bounds.width >= 24 || bounds.height >= 24);
  }

  @Test
  void rendersAnimatedBlockItemsAsGifWhenTexturesAnimate() throws Exception {
    var result = InventoryItemIconRenderer.render(null, null, null, itemStack(Items.SEA_LANTERN));

    assertEquals(InventoryItemIconRenderer.GIF_MIME_TYPE, result.mimeType());
    assertFalse(result.base64().isEmpty());

    var bytes = Base64.getDecoder().decode(result.base64());
    assertEquals('G', bytes[0]);
    assertEquals('I', bytes[1]);
    assertEquals('F', bytes[2]);

    try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReadersByFormatName("gif");
      assertTrue(readers.hasNext());
      var reader = readers.next();
      try {
        reader.setInput(imageInput);
        assertTrue(reader.getNumImages(true) > 1);
      } finally {
        reader.dispose();
      }
    }
  }

  @Test
  void rendersSpecialModelChestWithTransparentCorners() throws Exception {
    var minecraft = Minecraft.getInstance();
    Assumptions.assumeTrue(
      minecraft != null,
      "Live Minecraft instance required to exercise special-model item rendering"
    );

    var result = InventoryItemIconRenderer.render(
      minecraft,
      minecraft.level,
      minecraft.player,
      itemStack(Items.TRAPPED_CHEST)
    );

    assertEquals(InventoryItemIconRenderer.PNG_MIME_TYPE, result.mimeType());
    assertFalse(result.base64().isEmpty());

    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.base64())));
    assertNotNull(image);

    var topLeftAlpha = (image.getRGB(0, 0) >>> 24) & 0xFF;
    var bottomLeftAlpha = (image.getRGB(0, image.getHeight() - 1) >>> 24) & 0xFF;
    var centerAlpha =
      (image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >>> 24) & 0xFF;

    assertEquals(0, topLeftAlpha);
    assertEquals(0, bottomLeftAlpha);
    assertTrue(centerAlpha > 0);
  }

  private static Rectangle visibleBounds(BufferedImage image) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;

    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) == 0) {
          continue;
        }
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }

    if (minX == Integer.MAX_VALUE) {
      return null;
    }
    return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }
}
