import java.util.zip.ZipFile

plugins {
  `sf-project-conventions`
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  api(projects.buildData)

  api(libs.bundles.classtransform)
  api(libs.reflect)

  api(libs.bundles.log4j)
  api(libs.jline)
  api(libs.jansi)
  api(libs.terminalconsoleappender)
  api(libs.disruptor)

  api("org.ow2.asm:asm:9.10.1")
  api("org.ow2.asm:asm-analysis:9.10.1")
  api("org.ow2.asm:asm-commons:9.10.1")
  api("org.ow2.asm:asm-tree:9.10.1")
  api("org.ow2.asm:asm-util:9.10.1")
  api("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
  api("net.fabricmc:fabric-loader:0.19.3")
  api("net.fabricmc:mapping-io:0.9.1")

  api("at.yawk.lz4:lz4-java:1.11.2")
  api("com.azure:azure-json:1.5.1")
  api("com.github.oshi:oshi-core:7.6.0")
  api("com.google.code.gson:gson:2.14.0")
  api("com.google.guava:failureaccess:1.0.3")
  api("com.google.guava:guava:33.7.1-jre")
  api("com.ibm.icu:icu4j:78.3")
  api("com.microsoft.azure:msal4j:1.26.0")
  api("com.mojang:authlib:9.0.75")
  api("com.mojang:blocklist:1.0.10")
  api("com.mojang:brigadier:1.3.10")
  api("com.mojang:datafixerupper:10.0.21")
  api("com.mojang:jtracy:1.0.37")
  api("com.mojang:logging:1.7.12")
  api("com.mojang:patchy:2.2.10")
  api("com.mojang:text2speech:1.19.12")
  api("commons-codec:commons-codec:1.22.1")
  api("commons-io:commons-io:2.22.0")
  api("it.unimi.dsi:fastutil:8.5.19")
  api("net.java.dev.jna:jna-platform:5.19.1")
  api("net.java.dev.jna:jna:5.19.1")
  api("net.sf.jopt-simple:jopt-simple:5.0.4")
  api("org.apache.commons:commons-compress:1.28.0")
  api("org.apache.commons:commons-lang3:3.20.0")
  api("org.jcraft:jorbis:0.0.17")
  api("org.joml:joml:1.10.9")
  api("org.jspecify:jspecify:1.0.1")
  api("org.lwjgl:lwjgl-freetype:3.4.3")
  api("org.lwjgl:lwjgl-glfw:3.4.3")
  api("org.lwjgl:lwjgl-jemalloc:3.4.3")
  api("org.lwjgl:lwjgl-openal:3.4.3")
  api("org.lwjgl:lwjgl-opengl:3.4.3")
  api("org.lwjgl:lwjgl-shaderc:3.4.3")
  api("org.lwjgl:lwjgl-spvc:3.4.3")
  api("org.lwjgl:lwjgl-stb:3.4.3")
  api("org.lwjgl:lwjgl-tinyfd:3.4.3")
  api("org.lwjgl:lwjgl-vma:3.4.3")
  api("org.lwjgl:lwjgl-vulkan:3.4.3")
  runtimeOnly("org.lwjgl:lwjgl-vulkan:3.4.3:natives-macos")
  runtimeOnly("org.lwjgl:lwjgl-vulkan:3.4.3:natives-macos-arm64")
  runtimeOnly(fileTree(rootProject.layout.buildDirectory.dir("vulkan-runtime")) { include("soulfire-vulkan-*.jar") })
  api("org.lwjgl:lwjgl:3.4.3:unsafe")
  // Real native libraries are required for headless Vulkan and texture/font decoding.
  for (platform in listOf("linux", "linux-arm64", "windows", "windows-arm64", "macos", "macos-arm64")) {
    for (module in listOf("lwjgl", "lwjgl-freetype", "lwjgl-glfw", "lwjgl-jemalloc", "lwjgl-openal", "lwjgl-shaderc", "lwjgl-spvc", "lwjgl-stb", "lwjgl-tinyfd", "lwjgl-vma")) {
      runtimeOnly("org.lwjgl:$module:3.4.3:natives-$platform")
    }
  }
  api("org.slf4j:slf4j-api:2.0.18")

  api("io.github.llamalad7:mixinextras-fabric:0.5.5")
  api("org.checkerframework:checker-qual:4.2.2")

  // Newest netty
  api("io.netty:netty-all:4.2.17.Final")
}

val modProjectName = ":mod"
evaluationDependsOn(modProjectName)
afterEvaluate {
  val modJarConfiguration = project(modProjectName).configurations.named("mod-jar")

  tasks.named<Jar>("jar") {
    from({
      modJarConfiguration.get().artifacts.files
    }) {
      into("META-INF/jars")
    }

    dependsOn(modJarConfiguration)
  }
}

// Release JARs must never silently omit a platform's CPU fallback.
val verifyVulkanRuntime = tasks.register("verifyVulkanRuntime") {
  val platforms = providers.gradleProperty("vulkanPlatforms")
    .orElse("linux-x86_64,linux-arm64,windows-x86_64,windows-arm64,macos-x86_64,macos-arm64").get().split(',')
  val directory = rootProject.layout.buildDirectory.dir("vulkan-runtime").get().asFile
  inputs.dir(directory)
  doLast {
    for (platform in platforms) {
      val artifact = directory.resolve("soulfire-vulkan-$platform.jar")
      check(artifact.isFile) {
        "Missing $artifact. Build native runtimes first; see docs/vulkan-runtime.md. For a local host-only JAR, set -PvulkanPlatforms=<platform>."
      }
      ZipFile(artifact).use { jar ->
        check(jar.getEntry("soulfire-vulkan/$platform/runtime.properties") != null) {
          "Invalid native runtime JAR: $artifact"
        }
      }
    }
  }
}
