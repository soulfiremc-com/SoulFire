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
package com.soulfiremc.manual.mixin;

import com.soulfiremc.server.renderer.LavapipeComparison;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AtmosphericFogEnvironment.class)
public class ComparisonRainFog {
  @Shadow private float rainFogMultiplier;

  @Inject(method = "updateRainFogState", at = @At("HEAD"))
  private void settleWeather(Camera camera, ClientLevel level, DeltaTracker delta, CallbackInfo ci) {
    if (!LavapipeComparison.isStressScene()) return;
    var position = camera.blockPosition();
    var skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(position);
    var skyMultiplier = Mth.clamp((skyLight - 8.0F) / 7.0F, 0.0F, 1.0F);
    var biomeMultiplier = level.getBiome(position).value().hasPrecipitation() ? 1.0F : 0.5F;
    rainFogMultiplier = level.getRainLevel(delta.getGameTimeDeltaPartialTick(false)) * skyMultiplier * biomeMultiplier;
  }
}
