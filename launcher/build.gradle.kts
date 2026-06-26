plugins {
  `sf-project-conventions`
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  api(projects.buildData)

  api("org.ow2.asm:asm:9.10.1")
  api("org.ow2.asm:asm-analysis:9.10.1")
  api("org.ow2.asm:asm-commons:9.10.1")
  api("org.ow2.asm:asm-tree:9.10.1")
  api("org.ow2.asm:asm-util:9.10.1")
  api("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
  api("net.fabricmc:fabric-loader:0.19.3")
  api("net.fabricmc:mapping-io:0.8.0")

  api("at.yawk.lz4:lz4-java:1.11.0")
  api("com.azure:azure-json:1.5.1")
  api("com.github.oshi:oshi-core:7.3.1")
  api("com.google.code.gson:gson:2.14.0")
  api("com.google.guava:failureaccess:1.0.3")
  api("com.google.guava:guava:33.6.0-jre")
  api("com.ibm.icu:icu4j:78.3")
  api("com.microsoft.azure:msal4j:1.25.0")
  api("com.mojang:authlib:9.0.75")
  api("com.mojang:blocklist:1.0.10")
  api("com.mojang:brigadier:1.3.10")
  api("com.mojang:datafixerupper:10.0.21")
  api("com.mojang:jtracy:1.0.37")
  api("com.mojang:logging:1.7.12")
  api("com.mojang:patchy:2.2.10")
  api("com.mojang:text2speech:1.19.12")
  api("commons-codec:commons-codec:1.22.0")
  api("commons-io:commons-io:2.22.0")
  api("it.unimi.dsi:fastutil:8.5.18")
  api("net.java.dev.jna:jna-platform:5.19.1")
  api("net.java.dev.jna:jna:5.19.1")
  api("net.sf.jopt-simple:jopt-simple:5.0.4")
  api("org.apache.commons:commons-compress:1.28.0")
  api("org.apache.commons:commons-lang3:3.20.0")
  api("org.jcraft:jorbis:0.0.17")
  api("org.joml:joml:1.10.9")
  api("org.jspecify:jspecify:1.0.0")
  api("org.lwjgl:lwjgl-freetype:3.4.1")
  api("org.lwjgl:lwjgl-glfw:3.4.1")
  api("org.lwjgl:lwjgl-jemalloc:3.4.1")
  api("org.lwjgl:lwjgl-openal:3.4.1")
  api("org.lwjgl:lwjgl-opengl:3.4.1")
  api("org.lwjgl:lwjgl-shaderc:3.4.1")
  api("org.lwjgl:lwjgl-spvc:3.4.1")
  api("org.lwjgl:lwjgl-stb:3.4.1")
  api("org.lwjgl:lwjgl-tinyfd:3.4.1")
  api("org.lwjgl:lwjgl-vma:3.4.1")
  api("org.lwjgl:lwjgl-vulkan:3.4.1")
  api("org.lwjgl:lwjgl:3.4.1:unsafe")
  api("org.slf4j:slf4j-api:2.0.18")

  api("io.github.llamalad7:mixinextras-fabric:0.5.4")
  api("org.checkerframework:checker-qual:4.2.0")

  // Newest netty
  api("io.netty:netty-all:4.2.15.Final")
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
