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
package com.soulfiremc.mod.mixin.headless;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.soulfiremc.server.renderer.VulkanRenderer;
import net.minecraft.SystemReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MixinMinecraft {
  @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
  private void remoteFocus(CallbackInfoReturnable<Boolean> cir) {
    com.soulfiremc.server.bot.BotConnection.currentOptional().ifPresent(bot -> {
      if (bot.povInput().active()) cir.setReturnValue(true);
    });
  }

  @Inject(method = "fillSystemReport", at = @At("HEAD"), cancellable = true)
  private static void preventFillSystemReport(SystemReport report, Minecraft minecraft, LanguageManager languageManager, String launchVersion, Options options, CallbackInfoReturnable<SystemReport> cir) {
    cir.setReturnValue(report);
  }

  @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
  private void renderFrameHook(boolean tick, CallbackInfo ci) {
    var minecraft = (Minecraft) (Object) this;
    // Camera tracking updates simulation state used by lighting and fog, without drawing a frame.
    minecraft.gameRenderer.update(minecraft.getDeltaTracker());
    // There is no screen to present in headless mode, and the actual rendering is already
    // cancelled in GameRenderer. Skip the whole surface acquire/present path.
    ci.cancel();
  }

  @Inject(method = "createUserApiService", at = @At("HEAD"), cancellable = true)
  private static void createUserApiServiceHook(CallbackInfoReturnable<UserApiService> cir) {
    cir.setReturnValue(UserApiService.OFFLINE);
  }

  @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createSurface(J)Lcom/mojang/blaze3d/systems/GpuSurface;"))
  private GpuSurface noPresentationSurface(GpuDevice device, long window) {
    return null;
  }

  @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;close()V"))
  private void closePresentationSurface(GpuSurface surface) {
    // No surface exists in headless mode.
  }
  @WrapMethod(method = "runTick")
  private void serializeDeviceAccess(boolean advanceGameTime, Operation<Void> original) {
    VulkanRenderer.DEVICE_LOCK.lock();
    try {
      original.call(advanceGameTime);
    } finally {
      VulkanRenderer.DEVICE_LOCK.unlock();
    }
  }
  @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowSizeLimits(JIIII)V"))
  private void noWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
    // Captures validate texture dimensions, not operating-system window limits.
  }
}
