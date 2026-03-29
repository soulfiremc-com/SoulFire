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
package com.soulfiremc.mod.mixin.soulfire.optimization;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixerBuilder;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.filefix.FileFixerUpper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(DataFixers.class)
public class MixinDataFixers {
  @Inject(method = "optimize", at = @At("HEAD"), cancellable = true)
  private static void optimize(Set<DSL.TypeReference> references, CallbackInfoReturnable<CompletableFuture<?>> cir) {
    cir.setReturnValue(CompletableFuture.completedFuture(null));
  }

  @Inject(method = "addFixers", at = @At("HEAD"), cancellable = true)
  private static void addFixers(DataFixerBuilder builder, FileFixerUpper.Builder fileFixerBuilder, CallbackInfo ci) {
    ci.cancel();
  }
}
