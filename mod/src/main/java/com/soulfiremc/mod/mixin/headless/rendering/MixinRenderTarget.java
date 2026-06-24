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

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public class MixinRenderTarget {
  @Shadow
  public int width;

  @Shadow
  public int height;

  @Inject(method = "createBuffers", at = @At("HEAD"), cancellable = true)
  private void createBuffersHook(int width, int height, CallbackInfo ci) {
    var maxTextureSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
    if (width <= 0 || height <= 0 || width <= maxTextureSize && height <= maxTextureSize) {
      return;
    }

    this.width = width;
    this.height = height;
    ci.cancel();
  }

  @Inject(method = "blitAndBlendToTexture", at = @At("HEAD"), cancellable = true)
  private void blitAndBlendToTextureHook(CallbackInfo ci) {
    ci.cancel();
  }

}
