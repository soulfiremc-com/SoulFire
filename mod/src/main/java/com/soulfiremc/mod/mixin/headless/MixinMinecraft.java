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

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.SystemReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MixinMinecraft {
  @Inject(method = "fillSystemReport", at = @At("HEAD"), cancellable = true)
  private static void preventFillSystemReport(SystemReport report, Minecraft minecraft, LanguageManager languageManager, String launchVersion, Options options, CallbackInfoReturnable<SystemReport> cir) {
    cir.setReturnValue(report);
  }

  @Inject(method = "<init>", at = @At("RETURN"))
  private void closeRenderer(GameConfig arg, CallbackInfo ci) {
    ((Minecraft) (Object) this).gameRenderer.close();
  }

  @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
  private void renderFrameHook(boolean tick, CallbackInfo ci) {
    // There is no screen to present in headless mode, and the actual rendering is already
    // cancelled in GameRenderer. Skip the whole surface acquire/present path.
    ci.cancel();
  }

  @Inject(method = "createUserApiService", at = @At("HEAD"), cancellable = true)
  private static void createUserApiServiceHook(CallbackInfoReturnable<UserApiService> cir) {
    cir.setReturnValue(UserApiService.OFFLINE);
  }

  @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"), cancellable = true)
  private void updateLevelEngineHook(ClientLevel clientLevel, boolean stopSound, CallbackInfo ci) {
    ci.cancel();
  }
}
