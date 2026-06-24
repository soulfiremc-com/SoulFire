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
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.soulfiremc.server.renderer.RendererRuntimeTextureMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandEncoder.class)
public class MixinCommandEncoder {
  /// Mirrors NativeImage texture writes. In modern Minecraft every NativeImage upload, including the
  /// full-texture overload, funnels through this method, and the raw ByteBuffer overload no longer
  /// carries a NativeImage format we could interpret, so this is the single place to capture writes.
  @Inject(
    method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
    at = @At("HEAD")
  )
  private void writeNativeImageToTextureHook(
    GpuTexture destination,
    NativeImage source,
    int mipLevel,
    int depthOrLayer,
    int destX,
    int destY,
    CallbackInfo ci) {
    if (mipLevel == 0 && depthOrLayer == 0) {
      RendererRuntimeTextureMirror.mirrorWrite(
        destination, source, destX, destY, source.getWidth(), source.getHeight(), 0, 0);
    }
  }
}
