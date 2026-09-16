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

import com.soulfiremc.mod.access.IFogEnvironmentState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
  @Redirect(method = {"computeFogColor", "setupFog"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;FOG_ENVIRONMENTS:Ljava/util/List;"))
  private List<FogEnvironment> botFogEnvironments() {
    return ((IFogEnvironmentState) Minecraft.getInstance().gameRenderer.gameRenderState()).soulfire$fogEnvironments();
  }
}
