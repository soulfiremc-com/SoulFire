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
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RendererDownloadedTextureStoreTest {
  @Test
  void storesDownloadedTexturePixelsByTextureLocation() {
    var location = Identifier.withDefaultNamespace("skins/downloaded");
    try (var image = new NativeImage(2, 1, false)) {
      image.setPixel(0, 0, 0xFF336699);
      image.setPixel(1, 0, 0x80445566);

      RendererDownloadedTextureStore.register(location, image);
      var texture = RendererDownloadedTextureStore.texture(location);

      assertEquals(0xFF336699, texture.sample(0.25F, 0.5F, 0));
      assertEquals(0x80445566, texture.sample(0.75F, 0.5F, 0));
    } finally {
      RendererDownloadedTextureStore.unregister(location);
    }
  }

  @Test
  void unregistersDownloadedTexturePixels() {
    var location = Identifier.withDefaultNamespace("skins/removed");
    try (var image = new NativeImage(1, 1, false)) {
      image.setPixel(0, 0, 0xFFFFFFFF);
      RendererDownloadedTextureStore.register(location, image);
    }

    RendererDownloadedTextureStore.unregister(location);

    assertNull(RendererDownloadedTextureStore.texture(location));
  }
}
