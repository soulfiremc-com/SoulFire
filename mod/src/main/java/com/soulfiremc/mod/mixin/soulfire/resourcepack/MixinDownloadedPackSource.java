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
package com.soulfiremc.mod.mixin.soulfire.resourcepack;

import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DownloadedPackSource.class)
public class MixinDownloadedPackSource {
  @Inject(method = "startReload", at = @At("HEAD"), cancellable = true)
  private void skipDownloadedPackReload(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
    if (BotConnection.currentOptional().isEmpty()) {
      return;
    }

    callbacks.onSuccess();
    ci.cancel();
  }

  @Inject(method = "loadRequestedPacks", at = @At("HEAD"), cancellable = true)
  private void loadRequestedPacks(List<PackReloadConfig.IdAndPath> packs, CallbackInfoReturnable<List<Pack>> cir) {
    if (BotConnection.currentOptional().isPresent()) {
      cir.setReturnValue(List.of());
    }
  }
}
