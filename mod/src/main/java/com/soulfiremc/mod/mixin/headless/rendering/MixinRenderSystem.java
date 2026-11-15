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

import com.mojang.blaze3d.systems.RenderSystem;
import com.soulfiremc.server.renderer.VulkanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public class MixinRenderSystem {
  @Inject(method = "isOnRenderThread", at = @At("HEAD"), cancellable = true)
  private static void isOnRenderThreadHook(CallbackInfoReturnable<Boolean> cir) {
    if (VulkanRenderer.claimDeviceAccess()) {
      cir.setReturnValue(true);
    }
  }

  @Inject(method = "isFrozenAtPollEvents", at = @At("HEAD"), cancellable = true)
  private static void isFrozenAtPollEventsHook(CallbackInfoReturnable<Boolean> cir) {
    // Used by keepalive to determine whether to reply
    cir.setReturnValue(false);
  }
}
