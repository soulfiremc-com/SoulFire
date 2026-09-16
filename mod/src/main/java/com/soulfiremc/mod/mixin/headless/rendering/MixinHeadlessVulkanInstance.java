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

import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.soulfiremc.server.renderer.BundledVulkanRuntime;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.LUNARGDirectDriverLoading;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

@Mixin(VulkanInstance.class)
public class MixinHeadlessVulkanInstance {
  @Shadow @Final private Set<String> enabledExtensions;

  @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwGetRequiredInstanceExtensions()Lorg/lwjgl/PointerBuffer;"))
  private PointerBuffer noSurfaceExtensions() {
    if (BundledVulkanRuntime.hasBundledDrivers()) {
      enabledExtensions.add(LUNARGDirectDriverLoading.VK_LUNARG_DIRECT_DRIVER_LOADING_EXTENSION_NAME);
    }
    return MemoryStack.stackGet().callocPointer(0);
  }

  @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
  private int includeBundledDrivers(VkInstanceCreateInfo info, @Nullable VkAllocationCallbacks allocator, PointerBuffer instance) {
    BundledVulkanRuntime.includeDrivers(info, MemoryStack.stackGet());
    return VK12.vkCreateInstance(info, allocator, instance);
  }
}
