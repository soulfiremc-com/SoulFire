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

import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

@Mixin(VulkanBackend.class)
public class MixinHeadlessVulkanBackend {
  @Shadow @Final @Mutable public static Set<String> REQUIRED_DEVICE_EXTENSIONS;

  @Inject(method = "<clinit>", at = @At("TAIL"))
  private static void removePresentationRequirement(CallbackInfo ci) {
    REQUIRED_DEVICE_EXTENSIONS = REQUIRED_DEVICE_EXTENSIONS.stream()
      .filter(extension -> !extension.equals("VK_KHR_swapchain")).collect(Collectors.toUnmodifiableSet());
  }

  @Redirect(method = {"checkBackendAvailable", "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;"},
    at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwVulkanSupported()Z"))
  private static boolean noPresentationRequired() {
    // NativeLibrariesBootstrap already checks the Vulkan loader. GLFW checks WSI support,
    // which is irrelevant to offscreen rendering on the null platform.
    return true;
  }

  @Redirect(method = "findPhysicalDevice", at = @At(value = "INVOKE",
    target = "Lorg/lwjgl/vulkan/VK12;vkEnumeratePhysicalDevices(Lorg/lwjgl/vulkan/VkInstance;Ljava/nio/IntBuffer;Lorg/lwjgl/PointerBuffer;)I"))
  private static int enumeratePreferredDevices(VkInstance instance, IntBuffer count, @Nullable PointerBuffer devices) {
    var result = VK12.vkEnumeratePhysicalDevices(instance, count, devices);
    if (result != VK12.VK_SUCCESS || devices == null) return result;

    // Sort candidates before vanilla checks their features, extensions, queues, and driver exclusions.
    // Stable ordering preserves the driver's preference between devices of the same type.
    var handles = new ArrayList<Long>(count.get(0));
    var priorities = new HashMap<Long, Integer>();
    try (var stack = MemoryStack.stackPush()) {
      var properties = VkPhysicalDeviceProperties.calloc(stack);
      for (var index = 0; index < count.get(0); index++) {
        var handle = devices.get(index);
        if (handle == 0) continue;
        VK12.vkGetPhysicalDeviceProperties(new VkPhysicalDevice(handle, instance), properties);
        var priority = switch (properties.deviceType()) {
          case VK12.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 0;
          case VK12.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 1;
          case VK12.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
          case VK12.VK_PHYSICAL_DEVICE_TYPE_CPU -> 4;
          default -> 3;
        };
        handles.add(handle);
        priorities.put(handle, priority);
      }
    }
    handles.sort(Comparator.comparingInt(priorities::get));
    for (var index = 0; index < count.get(0); index++) {
      devices.put(index, index < handles.size() ? handles.get(index) : 0L);
    }
    return result;
  }
}
