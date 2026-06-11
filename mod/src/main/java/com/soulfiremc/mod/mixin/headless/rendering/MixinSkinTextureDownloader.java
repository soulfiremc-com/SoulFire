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

import com.mojang.blaze3d.platform.NativeImage;
import com.soulfiremc.server.renderer.RendererDownloadedTextureStore;
import net.minecraft.core.ClientAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(net.minecraft.client.renderer.texture.SkinTextureDownloader.class)
public class MixinSkinTextureDownloader {
  @Inject(method = "registerTextureInManager", at = @At("HEAD"))
  private void registerDownloadedTextureHook(
    ClientAsset.Texture textureId,
    NativeImage contents,
    CallbackInfoReturnable<CompletableFuture<ClientAsset.Texture>> cir
  ) {
    RendererDownloadedTextureStore.register(textureId.texturePath(), contents);
  }
}
