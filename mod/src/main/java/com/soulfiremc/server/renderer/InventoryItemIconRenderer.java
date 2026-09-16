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

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

public final class InventoryItemIconRenderer {
  public static final String PNG_MIME_TYPE = "image/png";
  private InventoryItemIconRenderer() {}

  public static RenderedInventoryItemImage render(Minecraft minecraft, ClientLevel level, ItemOwner owner, ItemStack stack) {
    return render(minecraft, level, owner, stack, 0);
  }

  public static RenderedInventoryItemImage render(Minecraft minecraft, ClientLevel level, ItemOwner owner, ItemStack stack, int seed) {
    if (stack.isEmpty()) return new RenderedInventoryItemImage(PNG_MIME_TYPE, "");
    var client = minecraft == null ? Minecraft.getInstance() : minecraft;
    var image = VulkanRenderer.renderGui(client, 32, 32, 2, graphics -> {
      var state = new TrackingItemStackRenderState();
      client.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, level, owner, seed);
      var pose = new Matrix3x2f();
      var item = new GuiItemRenderState(pose, state, 0, 0, null);
      var bounds = item.oversizedItemBounds();
      if (bounds != null) {
        var fit = Math.min(16.0F / bounds.width(), 16.0F / bounds.height());
        pose.translate(8, 8).scale(fit).translate(
          -bounds.left() - bounds.width() / 2.0F,
          -bounds.top() - bounds.height() / 2.0F);
        item = new GuiItemRenderState(pose, state, 0, 0, null);
      }
      client.gameRenderer.gameRenderState().guiRenderState.addItem(item);
    });
    try (var output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", output);
      return new RenderedInventoryItemImage(PNG_MIME_TYPE, Base64.getEncoder().encodeToString(output.toByteArray()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public record RenderedInventoryItemImage(String mimeType, String base64) {}
}
