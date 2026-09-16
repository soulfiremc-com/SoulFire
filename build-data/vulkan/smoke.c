// Exercise the packaged loader, direct driver loading, queue submission, and CPU readback.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#ifdef _WIN32
#include <windows.h>
static void *open_library(const char *path) {
  return (void *)LoadLibraryExA(path, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
}
static void *symbol(void *library, const char *name) { return (void *)GetProcAddress((HMODULE)library, name); }
#else
#include <dlfcn.h>
static void *open_library(const char *path) { return dlopen(path, RTLD_NOW | RTLD_LOCAL); }
static void *symbol(void *library, const char *name) { return dlsym(library, name); }
#endif
#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "Failed at line %d: %s\n", __LINE__, #condition); return 1; } } while (0)
#define LOAD(name) PFN_##name name = (PFN_##name)get_proc(instance, #name); CHECK(name)

int main(int argc, char **argv) {
  CHECK(argc == 3);
  void *loader = open_library(argv[1]);
  void *driver = open_library(argv[2]);
  CHECK(loader && driver);
  PFN_vkGetInstanceProcAddr get_proc = (PFN_vkGetInstanceProcAddr)symbol(loader, "vkGetInstanceProcAddr");
  CHECK(get_proc);
  VkInstance instance = VK_NULL_HANDLE;
  LOAD(vkCreateInstance);
  VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_2};
  VkDirectDriverLoadingInfoLUNARG driver_info = {
    .sType = VK_STRUCTURE_TYPE_DIRECT_DRIVER_LOADING_INFO_LUNARG,
    .pfnGetInstanceProcAddr = (PFN_vkGetInstanceProcAddrLUNARG)symbol(driver, "vk_icdGetInstanceProcAddr")
  };
  CHECK(driver_info.pfnGetInstanceProcAddr);
  VkDirectDriverLoadingListLUNARG drivers = {
    .sType = VK_STRUCTURE_TYPE_DIRECT_DRIVER_LOADING_LIST_LUNARG,
    .mode = VK_DIRECT_DRIVER_LOADING_MODE_EXCLUSIVE_LUNARG,
    .driverCount = 1, .pDrivers = &driver_info
  };
  const char *extension = VK_LUNARG_DIRECT_DRIVER_LOADING_EXTENSION_NAME;
  VkInstanceCreateInfo create = {
    .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pNext = &drivers,
    .pApplicationInfo = &app, .enabledExtensionCount = 1, .ppEnabledExtensionNames = &extension
  };
  CHECK(vkCreateInstance(&create, NULL, &instance) == VK_SUCCESS);
  LOAD(vkEnumeratePhysicalDevices);
  LOAD(vkGetPhysicalDeviceProperties);
  LOAD(vkGetPhysicalDeviceQueueFamilyProperties);
  LOAD(vkGetPhysicalDeviceMemoryProperties);
  LOAD(vkCreateDevice);
  LOAD(vkCreateBuffer);
  LOAD(vkGetBufferMemoryRequirements);
  LOAD(vkAllocateMemory);
  LOAD(vkBindBufferMemory);
  LOAD(vkCreateCommandPool);
  LOAD(vkAllocateCommandBuffers);
  LOAD(vkBeginCommandBuffer);
  LOAD(vkCmdFillBuffer);
  LOAD(vkCmdPipelineBarrier);
  LOAD(vkEndCommandBuffer);
  LOAD(vkGetDeviceQueue);
  LOAD(vkQueueSubmit);
  LOAD(vkQueueWaitIdle);
  LOAD(vkMapMemory);
  LOAD(vkUnmapMemory);
  LOAD(vkDestroyCommandPool);
  LOAD(vkDestroyBuffer);
  LOAD(vkFreeMemory);
  LOAD(vkDestroyDevice);
  LOAD(vkDestroyInstance);
  uint32_t count = 1;
  VkPhysicalDevice physical;
  CHECK(vkEnumeratePhysicalDevices(instance, &count, &physical) == VK_SUCCESS && count == 1);
  VkPhysicalDeviceProperties properties;
  vkGetPhysicalDeviceProperties(physical, &properties);
  CHECK(properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU);
  CHECK(properties.apiVersion >= VK_API_VERSION_1_2);
  VkQueueFamilyProperties queues[32];
  count = 32;
  vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, queues);
  uint32_t family = 0;
  while (family < count && !(queues[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) family++;
  CHECK(family < count);
  float priority = 1.0f;
  VkDeviceQueueCreateInfo queue_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
  VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_info};
  VkDevice device;
  CHECK(vkCreateDevice(physical, &device_info, NULL, &device) == VK_SUCCESS);
  VkBufferCreateInfo buffer_info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 1024, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
  VkBuffer buffer;
  CHECK(vkCreateBuffer(device, &buffer_info, NULL, &buffer) == VK_SUCCESS);
  VkMemoryRequirements requirements;
  vkGetBufferMemoryRequirements(device, buffer, &requirements);
  VkPhysicalDeviceMemoryProperties memory_properties;
  vkGetPhysicalDeviceMemoryProperties(physical, &memory_properties);
  uint32_t memory_type = 0;
  VkMemoryPropertyFlags flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  while (memory_type < memory_properties.memoryTypeCount &&
         (!(requirements.memoryTypeBits & (1u << memory_type)) || (memory_properties.memoryTypes[memory_type].propertyFlags & flags) != flags)) memory_type++;
  CHECK(memory_type < memory_properties.memoryTypeCount);
  VkMemoryAllocateInfo allocation = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = requirements.size, .memoryTypeIndex = memory_type};
  VkDeviceMemory memory;
  CHECK(vkAllocateMemory(device, &allocation, NULL, &memory) == VK_SUCCESS);
  CHECK(vkBindBufferMemory(device, buffer, memory, 0) == VK_SUCCESS);
  VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = family};
  VkCommandPool pool;
  CHECK(vkCreateCommandPool(device, &pool_info, NULL, &pool) == VK_SUCCESS);
  VkCommandBufferAllocateInfo commands_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
  VkCommandBuffer commands;
  CHECK(vkAllocateCommandBuffers(device, &commands_info, &commands) == VK_SUCCESS);
  VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  CHECK(vkBeginCommandBuffer(commands, &begin) == VK_SUCCESS);
  vkCmdFillBuffer(commands, buffer, 0, 1024, 0x13579bdf);
  VkBufferMemoryBarrier barrier = {
    .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
    .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
    .buffer = buffer, .size = VK_WHOLE_SIZE
  };
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &barrier, 0, NULL);
  CHECK(vkEndCommandBuffer(commands) == VK_SUCCESS);
  VkQueue queue;
  vkGetDeviceQueue(device, family, 0, &queue);
  VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &commands};
  CHECK(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS);
  CHECK(vkQueueWaitIdle(queue) == VK_SUCCESS);
  uint32_t *data;
  CHECK(vkMapMemory(device, memory, 0, 1024, 0, (void **)&data) == VK_SUCCESS);
  for (uint32_t i = 0; i < 256; i++) CHECK(data[i] == 0x13579bdf);
  vkUnmapMemory(device, memory);
  vkDestroyCommandPool(device, pool, NULL);
  vkDestroyBuffer(device, buffer, NULL);
  vkFreeMemory(device, memory, NULL);
  vkDestroyDevice(device, NULL);
  vkDestroyInstance(instance, NULL);
  printf("CPU Vulkan queue/readback passed: %s\n", properties.deviceName);
  return 0;
}
