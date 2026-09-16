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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(LoadingOverlay.class)
public class MixinLoadingOverlay {
  @Shadow @Final private Minecraft minecraft;
  @Shadow @Final private ReloadInstance reload;
  @Shadow @Final private Consumer<Optional<Throwable>> onFinish;

  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void finishWithoutAnimation(CallbackInfo ci) {
    if (reload.isDone()) {
      Optional<Throwable> failure;
      try { reload.checkExceptions(); failure = Optional.empty(); }
      catch (Throwable t) { failure = Optional.of(t); }
      onFinish.accept(failure);
      minecraft.gui.setOverlay(null);
    }
    ci.cancel();
  }
}
