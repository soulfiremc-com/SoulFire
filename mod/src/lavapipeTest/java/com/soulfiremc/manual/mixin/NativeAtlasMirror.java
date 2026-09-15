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
package com.soulfiremc.manual.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.soulfiremc.server.renderer.RendererRuntimeTextureMirror;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class NativeAtlasMirror {
  @Inject(method = "upload", at = @At("TAIL"))
  private void mirrorUploadedAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
    var atlas = (TextureAtlas) (Object) this;
    try (var pixels = new NativeImage(atlas.getWidth(), atlas.getHeight(), true)) {
      for (var sprite : atlas.sprites) {
        var contents = sprite.contents();
        for (var y = 0; y < contents.height(); y++) {
          for (var x = 0; x < contents.width(); x++) {
            pixels.setPixel(sprite.getX() + x, sprite.getY() + y, contents.originalImage.getPixel(x, y));
          }
        }
      }
      RendererRuntimeTextureMirror.register(atlas.location(), atlas.getTexture(), pixels);
    }
  }
}
