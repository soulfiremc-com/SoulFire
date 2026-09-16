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

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.soulfiremc.server.renderer.BundledVulkanRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
public class MixinNoOpenGL {
  @Inject(method = "loadOpenGL", at = @At("HEAD"), cancellable = true)
  private static void skipOpenGL(CallbackInfo ci) { ci.cancel(); }
  @Redirect(method = "tryLoadingVulkan", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK;create()V"))
  private static void loadBundledVulkan() {
    BundledVulkanRuntime.initialize();
  }
}
