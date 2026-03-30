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

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
  @Inject(method = "update", at = @At("HEAD"), cancellable = true)
  private void updateHook(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
  private void extractHook(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(method = "render", at = @At("HEAD"), cancellable = true)
  private void renderHook(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    ci.cancel();
  }

  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void tickHook(CallbackInfo ci) {
    ci.cancel();
  }

}
