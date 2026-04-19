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
package com.soulfiremc.mod.mixin.headless.sound;

import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public class MixinSoundManager {
  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void tickHook(CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(method = "updateSource", at = @At("HEAD"), cancellable = true)
  private void updateSourceHook(Camera camera, CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(
    method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
    at = @At("HEAD"),
    cancellable = true
  )
  private void applyHook(CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
  private void reloadHook(CallbackInfo ci) {
    ci.cancel();
  }
}
