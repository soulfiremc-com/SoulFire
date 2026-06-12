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
import com.soulfiremc.mod.access.IMouseHandler;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinMouseHandler implements IMouseHandler {
  @Shadow
  private double accumulatedDX;
  @Shadow
  private double accumulatedDY;

  private double soulfire$syntheticDX;
  private double soulfire$syntheticDY;
  private boolean soulfire$handlingSyntheticMovement;

  @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
  private void grabMouseHook(CallbackInfo ci) {
    ci.cancel();
  }

  @Override
  public void soulfire$queueSyntheticMovement(double deltaX, double deltaY) {
    soulfire$syntheticDX += deltaX;
    soulfire$syntheticDY += deltaY;
  }

  @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
  private void soulfire$queueBotRotationMovement(CallbackInfo ci) {
    soulfire$handlingSyntheticMovement = false;
    BotConnection.currentOptional()
      .ifPresent(connection -> connection.rotationControl().queueMouseMovement());
    if (soulfire$syntheticDX == 0.0D && soulfire$syntheticDY == 0.0D) {
      return;
    }

    accumulatedDX += soulfire$syntheticDX;
    accumulatedDY += soulfire$syntheticDY;
    soulfire$syntheticDX = 0.0D;
    soulfire$syntheticDY = 0.0D;
    soulfire$handlingSyntheticMovement = true;
  }

  @WrapOperation(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
  private boolean soulfire$treatSyntheticMovementAsGrabbed(MouseHandler instance, Operation<Boolean> original) {
    return soulfire$handlingSyntheticMovement || original.call(instance);
  }

  @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
  private void soulfire$clearSyntheticMovementState(CallbackInfo ci) {
    soulfire$handlingSyntheticMovement = false;
  }
}
