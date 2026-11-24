package com.soulfiremc.gradle

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenerateLauncherAssetsTest {
  @TempDir
  lateinit var directory: Path

  private fun jar(name: String, entries: Map<String, ByteArray>): File {
    val file = directory.resolve(name).toFile()
    ZipOutputStream(file.outputStream()).use { output ->
      entries.forEach { (path, content) ->
        output.putNextEntry(ZipEntry(path))
        output.write(content)
        output.closeEntry()
      }
    }
    return file
  }

  private fun minecraftLibraries(vararg classifiers: String): JsonObject = JsonObject().apply {
    add("libraries", JsonArray().apply {
      classifiers.forEach { classifier ->
        add(JsonObject().apply {
          addProperty("name", "org.lwjgl:lwjgl:3.4.3:$classifier")
          add("downloads", JsonObject().apply { add("artifact", JsonObject()) })
        })
      }
    })
  }

  @Test
  fun `Minecraft native plan covers every platform independently of the build host`() {
    val metadata = minecraftLibraries(
      "natives-linux", "natives-windows", "natives-windows-arm64", "natives-macos", "natives-macos-arm64", "natives-windows-x86"
    )

    val natives = GenerateLauncherAssets.minecraftNatives(metadata)

    assertEquals(VulkanPlatforms.all.toSet(), natives.map { it.platform }.toSet())
    assertEquals(6, natives.size)
    natives.forEach { native ->
      assertEquals(native.platform, GenerateLauncherAssets.platform(File(native.path).name))
      assertEquals("3.4.3", native.name.split(':')[2])
      if (native.platform == "linux-arm64") assertNull(native.download) else assertNotNull(native.download)
    }
  }

  @Test
  fun `an explicit Linux ARM64 native keeps its Minecraft download metadata`() {
    val metadata = minecraftLibraries(
      "natives-linux", "natives-linux-arm64", "natives-windows", "natives-windows-arm64", "natives-macos", "natives-macos-arm64"
    )

    val natives = GenerateLauncherAssets.minecraftNatives(metadata)

    assertEquals(6, natives.size)
    assertNotNull(natives.single { it.platform == "linux-arm64" }.download)
  }

  @Test
  fun `platform-specific native modules do not acquire unsupported classifiers`() {
    val metadata = minecraftLibraries(
      "natives-linux", "natives-windows", "natives-windows-arm64", "natives-macos", "natives-macos-arm64"
    )
    val libraries = metadata.getAsJsonArray("libraries")
    listOf("natives-macos", "natives-macos-arm64").forEach { classifier ->
      libraries.add(JsonObject().apply {
        addProperty("name", "org.lwjgl:lwjgl-vulkan:3.4.3:$classifier")
        add("downloads", JsonObject().apply { add("artifact", JsonObject()) })
      })
    }

    val natives = GenerateLauncherAssets.minecraftNatives(metadata)

    assertEquals(setOf("macos-x86_64", "macos-arm64"), natives.filter { it.name.startsWith("org.lwjgl:lwjgl-vulkan:") }.map { it.platform }.toSet())
    assertEquals(8, natives.size)
  }

  @Test
  fun `Mojang-built natives do not acquire a Linux ARM64 classifier that Central lacks`() {
    val metadata = minecraftLibraries(
      "natives-linux", "natives-windows", "natives-windows-arm64", "natives-macos", "natives-macos-arm64"
    )
    metadata.getAsJsonArray("libraries").add(JsonObject().apply {
      addProperty("name", "org.lwjgl:lwjgl-freetype:3.4.3-mojang-2.15.0:natives-linux")
      add("downloads", JsonObject().apply { add("artifact", JsonObject()) })
    })

    val natives = GenerateLauncherAssets.minecraftNatives(metadata)

    assertEquals(listOf("linux-x86_64"), natives.filter { it.name.startsWith("org.lwjgl:lwjgl-freetype:") }.map { it.platform })
    assertEquals(7, natives.size)
  }

  @Test
  fun `incomplete Minecraft native coverage stops manifest generation`() {
    assertThrows(IllegalArgumentException::class.java) {
      GenerateLauncherAssets.minecraftNatives(minecraftLibraries("natives-linux"))
    }
  }

  @Test
  fun `nested libraries replace identical runtime artifacts without removing other versions or transitives`() {
    val bridge = jar("bridge.jar", mapOf("bridge.bin" to byteArrayOf(1, 2, 3)))
    val renamedBridge = directory.resolve("resolved-bridge.jar").toFile().also { bridge.copyTo(it) }
    val otherVersion = jar("bridge-new.jar", mapOf("bridge.bin" to byteArrayOf(4, 5, 6)))
    val transitive = jar("dependency.jar", mapOf("dependency.bin" to byteArrayOf(7, 8, 9)))
    val mod = jar("mod.jar", mapOf(
      "fabric.mod.json" to """{"jars":[{"file":"META-INF/jars/bridge.jar"}]}""".toByteArray(),
      "META-INF/jars/bridge.jar" to bridge.readBytes()
    ))

    val runtime = GenerateLauncherAssets.runtimeLibraries(
      setOf(mod, bridge, renamedBridge, otherVersion, transitive),
      mapOf(mod.name to "com.soulfiremc:mod:1.0")
    )

    assertEquals(listOf(mod, otherVersion, transitive), runtime)
  }

  @Test
  fun `only declared nested jars replace runtime artifacts`() {
    val dependency = jar("dependency.jar", mapOf("dependency.bin" to byteArrayOf(1, 2, 3)))
    val mod = jar("mod.jar", mapOf(
      "fabric.mod.json" to "{}".toByteArray(),
      "META-INF/jars/dependency.jar" to dependency.readBytes()
    ))
    val files = setOf(mod, dependency)

    assertEquals(files.toList(), GenerateLauncherAssets.runtimeLibraries(files, mapOf(mod.name to "com.soulfiremc:mod:1.0")))
  }
}
