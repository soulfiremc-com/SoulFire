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

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.stream.Collectors;

@Mixin(VulkanFeatureSets.class)
public class MixinHeadlessVulkanFeatureSets {
  @Inject(method = "requiredFeatureSets", at = @At("RETURN"), cancellable = true)
  private static void removePresentationRequirement(CallbackInfoReturnable<Set<FeatureSet>> cir) {
    cir.setReturnValue(cir.getReturnValue().stream()
      .map(features -> new FeatureSet(features.name(), features.extensions().stream()
        .filter(extension -> !extension.equals("VK_KHR_swapchain"))
        .collect(Collectors.toUnmodifiableSet()), features.features(), features.condition()))
      .collect(Collectors.toSet()));
  }
}
