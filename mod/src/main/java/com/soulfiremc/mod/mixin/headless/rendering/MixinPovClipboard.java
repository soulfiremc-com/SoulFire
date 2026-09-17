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

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Each headless client owns its clipboard. Never use the server desktop clipboard.
@Mixin(KeyboardHandler.class)
public class MixinPovClipboard {
  @Unique private String soulfire$clipboard = "";
  @Inject(method = "getClipboard", at = @At("HEAD"), cancellable = true)
  private void getClipboard(CallbackInfoReturnable<String> cir) { cir.setReturnValue(soulfire$clipboard); }
  @Inject(method = "setClipboard", at = @At("HEAD"), cancellable = true)
  private void setClipboard(String value, CallbackInfo ci) {
    soulfire$clipboard = value.substring(0, Math.min(value.length(), 16_384));
    ci.cancel();
  }
}
