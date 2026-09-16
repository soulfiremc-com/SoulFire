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

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.soulfiremc.server.renderer.VulkanRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinCaptureGameRenderer {
  @Inject(method = {"extract", "render"}, at = @At("HEAD"), cancellable = true)
  private void demandDrivenOnly(CallbackInfo ci) {
    if (!VulkanRenderer.isCapturing()) ci.cancel();
  }

  @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
  private void requestedHandsOnly(CallbackInfo ci) {
    if (!VulkanRenderer.includeHands()) ci.cancel();
  }
  @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Runtime;availableProcessors()I"))
  private int boundChunkBuilders(int processors) {
    // Compilation and translucent sorting run synchronously, so one worker buffer suffices.
    return 1;
  }
}
