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
package com.soulfiremc.mod.mixin.headless.rendering;

import com.soulfiremc.server.renderer.RendererDownloadedTextureStore;
import com.soulfiremc.server.renderer.RendererRuntimeTextureMirror;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class MixinTextureManager {
  @Inject(method = "register", at = @At("TAIL"))
  private void registerTextureHook(Identifier location, AbstractTexture texture, CallbackInfo ci) {
    try {
      if (texture instanceof DynamicTexture dynamicTexture) {
        RendererRuntimeTextureMirror.register(location, texture.getTexture(), dynamicTexture.getPixels());
      } else {
        RendererRuntimeTextureMirror.register(location, texture.getTexture());
      }
    } catch (IllegalStateException _) {
      RendererRuntimeTextureMirror.unregister(location);
    }
  }

  @Inject(method = "release", at = @At("HEAD"))
  private void releaseTextureHook(Identifier location, CallbackInfo ci) {
    RendererDownloadedTextureStore.unregister(location);
    RendererRuntimeTextureMirror.unregister(location);
  }
}
