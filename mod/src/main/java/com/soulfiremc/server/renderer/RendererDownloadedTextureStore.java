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
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/// CPU copy of textures that vanilla's SkinManager resolved through SkinTextureDownloader.
public final class RendererDownloadedTextureStore {
  private static final Object LOCK = new Object();
  private static final Map<Identifier, RendererAssets.TextureImage> TEXTURES = new HashMap<>();

  private RendererDownloadedTextureStore() {}

  public static void register(Identifier location, NativeImage image) {
    var pixels = new int[image.getWidth() * image.getHeight()];
    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        pixels[x + y * image.getWidth()] = image.getPixel(x, y);
      }
    }

    var texture = RendererAssets.TextureImage.fromArgb(image.getWidth(), image.getHeight(), pixels, null);
    synchronized (LOCK) {
      TEXTURES.put(location, texture);
    }
  }

  public static void unregister(Identifier location) {
    synchronized (LOCK) {
      TEXTURES.remove(location);
    }
  }

  @Nullable
  public static RendererAssets.TextureImage texture(Identifier location) {
    synchronized (LOCK) {
      return TEXTURES.get(location);
    }
  }
}
