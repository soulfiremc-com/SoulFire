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
package com.soulfiremc.server.renderer;

import com.soulfiremc.server.util.SFPathConstants;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.WindowsLibrary;
import org.lwjgl.vulkan.LUNARGDirectDriverLoading;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDirectDriverLoadingInfoLUNARG;
import org.lwjgl.vulkan.VkDirectDriverLoadingListLUNARG;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/// Supplies native libraries to Mojang's Vulkan backend. Devices and rendering remain owned by Minecraft.
@Slf4j
public final class BundledVulkanRuntime {
  // Vulkan retains function pointers into these libraries until process shutdown.
  private static final List<SharedLibrary> LIBRARIES = new ArrayList<>();
  private static final List<Long> DRIVERS = new ArrayList<>();

  private BundledVulkanRuntime() {}

  public static void initialize() {
    if (Configuration.VULKAN_LIBRARY_NAME.get() != null) {
      // An explicit LWJGL override is useful for driver development and must retain its usual meaning.
      VK.create();
      return;
    }
    var platform = NativeRuntimeBundle.platformId(System.getProperty("os.name"), System.getProperty("os.arch"));
    try {
      var bundle = NativeRuntimeBundle.extract(BundledVulkanRuntime.class.getClassLoader(), platform,
        SFPathConstants.baseDirectory().resolve("natives/vulkan"));
      var loader = load(bundle.loader().toString());
      LIBRARIES.add(loader);
      VK.create(loader);
      addDriver(load(bundle.driver().toString()));
      if (Platform.get() == Platform.MACOSX) {
        // Match vanilla's bundled MoltenVK path while keeping lavapipe available on Macs without a usable GPU.
        try {
          addDriver(Library.loadNative(VK.class, "org.lwjgl.vulkan", "MoltenVK", true));
        } catch (UnsatisfiedLinkError e) {
          log.warn("MoltenVK could not load; bundled CPU Vulkan remains available", e);
        }
      }
      log.info("Loaded bundled headless Vulkan runtime for {}; installed GPU drivers remain available", platform);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to extract the bundled Vulkan runtime", e);
    }
  }

  private static SharedLibrary load(String path) {
    if (Platform.get() != Platform.WINDOWS) return Library.loadNative(BundledVulkanRuntime.class, "soulfire.vulkan", path);
    // Resolve transitive DLLs beside the extracted library, without changing the process search path.
    var handle = Kernel32.INSTANCE.LoadLibraryEx(path, null, 0x00000100 | 0x00001000);
    if (handle == null) throw new UnsatisfiedLinkError("Cannot load " + path + ": Windows error " + Native.getLastError());
    return new WindowsLibrary(path, Pointer.nativeValue(handle.getPointer()));
  }

  private static void addDriver(SharedLibrary library) {
    var address = library.getFunctionAddress("vk_icdGetInstanceProcAddr");
    if (address == 0) {
      library.free();
      throw new UnsatisfiedLinkError("Vulkan driver does not export vk_icdGetInstanceProcAddr");
    }
    LIBRARIES.add(library);
    DRIVERS.add(address);
  }

  public static boolean hasBundledDrivers() {
    return !DRIVERS.isEmpty();
  }

  public static void includeDrivers(VkInstanceCreateInfo info, MemoryStack stack) {
    if (!hasBundledDrivers()) return;
    var drivers = VkDirectDriverLoadingInfoLUNARG.calloc(DRIVERS.size(), stack);
    for (var index = 0; index < DRIVERS.size(); index++) {
      drivers.get(index).sType$Default().pfnGetInstanceProcAddr(DRIVERS.get(index));
    }
    var list = VkDirectDriverLoadingListLUNARG.calloc(stack).sType$Default()
      .mode(LUNARGDirectDriverLoading.VK_DIRECT_DRIVER_LOADING_MODE_INCLUSIVE_LUNARG).pDrivers(drivers);
    info.pNext(list);
  }
}
