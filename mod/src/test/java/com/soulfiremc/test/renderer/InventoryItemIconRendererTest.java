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

import com.mojang.blaze3d.vertex.PoseStack;
import com.soulfiremc.server.renderer.InventoryItemIconRenderer;
import com.soulfiremc.server.renderer.RenderMaterial;
import com.soulfiremc.server.renderer.RenderQuad;
import com.soulfiremc.server.renderer.RenderVertex;
import com.soulfiremc.server.renderer.RendererAssets;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
    assertTrue(bounds.width >= 12 || bounds.height >= 12);
  }

  @Test
  void rendersBlockItemsFromVanillaResolvedScene() throws Exception {
    var result = InventoryItemIconRenderer.render(null, null, null, itemStack(Items.SEA_LANTERN));

    assertEquals(InventoryItemIconRenderer.PNG_MIME_TYPE, result.mimeType());
    assertFalse(result.base64().isEmpty());

    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.base64())));
    assertNotNull(image);
    assertNotNull(visibleBounds(image));
  }

  @Test
  void rendersCompassFromVanillaResolvedScene() throws Exception {
    var result = InventoryItemIconRenderer.render(null, null, null, itemStack(Items.COMPASS));

    assertEquals(InventoryItemIconRenderer.PNG_MIME_TYPE, result.mimeType());
    assertFalse(result.base64().isEmpty());

    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.base64())));
    assertNotNull(image);
    assertNotNull(visibleBounds(image));
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

  @Test
  void projectsVanillaGuiYDownQuadsWithoutFlippingTexture() throws Exception {
    var texture = RendererAssets.TextureImage.fromArgb(
      2,
      2,
      new int[]{
        0xFFFF0000, 0xFFFF0000,
        0xFF0000FF, 0xFF0000FF
      },
      null
    );
    var quad = new RenderQuad(
      new RenderVertex(-0.5F, -0.5F, 0.0F, 0.0F, 0.0F, 0xFFFFFFFF),
      new RenderVertex(-0.5F, 0.5F, 0.0F, 0.0F, 1.0F, 0xFFFFFFFF),
      new RenderVertex(0.5F, 0.5F, 0.0F, 1.0F, 1.0F, 0xFFFFFFFF),
      new RenderVertex(0.5F, -0.5F, 0.0F, 1.0F, 0.0F, 0xFFFFFFFF),
      RenderMaterial.create(texture, RendererAssets.AlphaMode.OPAQUE, 0xFFFFFFFF, true, 0.0F)
    );

    var sceneClass = Class.forName("com.soulfiremc.server.renderer.InventoryItemIconRenderer$IconScene");
    var sceneConstructor = sceneClass.getDeclaredConstructor(List.class, List.class, boolean.class);
    sceneConstructor.setAccessible(true);
    var scene = sceneConstructor.newInstance(List.of(quad), List.of(texture), false);
    var renderFrame = InventoryItemIconRenderer.class.getDeclaredMethod("renderFrame", sceneClass, long.class);
    renderFrame.setAccessible(true);
    var image = (BufferedImage) renderFrame.invoke(null, scene, 0L);

    assertRedDominant(image.getRGB(image.getWidth() / 2, image.getHeight() / 2 - 4));
    assertBlueDominant(image.getRGB(image.getWidth() / 2, image.getHeight() / 2 + 4));
  }

  @Test
  void modelPartSubmitMarksFoilForSpecialItemParts() throws Exception {
    var collectorClass = Class.forName("com.soulfiremc.server.renderer.InventoryItemIconRenderer$ItemSubmitCollector");
    var constructor = collectorClass.getDeclaredConstructor();
    constructor.setAccessible(true);
    var collector = constructor.newInstance();
    var submitModelPart = collectorClass.getDeclaredMethod(
      "submitModelPart",
      ModelPart.class,
      PoseStack.class,
      net.minecraft.client.renderer.rendertype.RenderType.class,
      int.class,
      int.class,
      TextureAtlasSprite.class,
      boolean.class,
      boolean.class,
      int.class,
      net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay.class,
      int.class
    );
    submitModelPart.setAccessible(true);

    submitModelPart.invoke(
      collector,
      new ModelPart(List.of(), Map.of()),
      new PoseStack(),
      RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/chest/normal.png")),
      LightCoordsUtil.FULL_BRIGHT,
      0,
      null,
      false,
      true,
      0xFFFFFFFF,
      null,
      0
    );

    Method hasFoil = collectorClass.getDeclaredMethod("hasFoil");
    hasFoil.setAccessible(true);
    assertTrue((boolean) hasFoil.invoke(collector));
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

  private static void assertRedDominant(int color) {
    var red = (color >> 16) & 0xFF;
    var blue = color & 0xFF;
    assertTrue(red > blue, () -> "Expected red-dominant pixel, got 0x" + Integer.toHexString(color));
  }

  private static void assertBlueDominant(int color) {
    var red = (color >> 16) & 0xFF;
    var blue = color & 0xFF;
    assertTrue(blue > red, () -> "Expected blue-dominant pixel, got 0x" + Integer.toHexString(color));
  }
}
