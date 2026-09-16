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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulfiremc.mod.access.ITextureManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

@Mixin(TextureManager.class)
public class MixinTextureManager implements ITextureManager {
  @Shadow
  @Final
  private Map<Identifier, AbstractTexture> byPath;

  @Unique
  private boolean soulfire$isolated;
  @Unique
  private Set<AbstractTexture> soulfire$ownedTextures =
    Collections.newSetFromMap(new IdentityHashMap<>());

  @Override
  public void soulfire$initializeBotCopy(TextureManager sharedTextureManager) {
    soulfire$isolated = true;
    soulfire$ownedTextures = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  @Inject(method = "register", at = @At("HEAD"))
  private void trackOwnedTexture(Identifier location, AbstractTexture texture, CallbackInfo ci) {
    if (soulfire$isolated && byPath.get(location) != texture) {
      soulfire$ownedTextures.add(texture);
    }
  }

  @WrapOperation(
    method = "safeClose",
    at = @At(
      value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/texture/AbstractTexture;close()V"))
  private void onlyCloseOwnedBotTextures(AbstractTexture texture, Operation<Void> original) {
    if (!soulfire$isolated || soulfire$ownedTextures.remove(texture)) {
      original.call(texture);
    }
  }
}
