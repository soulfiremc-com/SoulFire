plugins {
  `sf-project-conventions`
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  api(projects.buildData)

  api("org.ow2.asm:asm:9.9.1")
  api("org.ow2.asm:asm-analysis:9.9.1")
  api("org.ow2.asm:asm-commons:9.9.1")
  api("org.ow2.asm:asm-tree:9.9.1")
  api("org.ow2.asm:asm-util:9.9.1")
  api("net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7")
  api("net.fabricmc:fabric-loader:0.18.5")
  api("net.fabricmc:mapping-io:0.8.0")

  api("at.yawk.lz4:lz4-java:1.10.4")
  api("com.azure:azure-json:1.5.1")
  api("com.github.oshi:oshi-core:6.10.0")
  api("com.google.code.gson:gson:2.13.2")
  api("com.google.guava:failureaccess:1.0.3")
  api("com.google.guava:guava:33.5.0-jre")
  api("com.ibm.icu:icu4j:78.3")
  api("com.microsoft.azure:msal4j:1.24.0")
  api("com.mojang:authlib:7.0.61")
  api("com.mojang:blocklist:1.0.10")
  api("com.mojang:brigadier:1.3.10")
  api("com.mojang:datafixerupper:9.0.19")
  api("com.mojang:jtracy:1.0.37")
  api("com.mojang:logging:1.6.11")
  api("com.mojang:patchy:2.2.10")
  api("com.mojang:text2speech:1.18.11")
  api("commons-codec:commons-codec:1.21.0")
  api("commons-io:commons-io:2.21.0")
  api("it.unimi.dsi:fastutil:8.5.18")
  api("net.java.dev.jna:jna-platform:5.18.1")
  api("net.java.dev.jna:jna:5.18.1")
  api("net.sf.jopt-simple:jopt-simple:5.0.4")
  api("org.apache.commons:commons-compress:1.28.0")
  api("org.apache.commons:commons-lang3:3.20.0")
  api("org.jcraft:jorbis:0.0.17")
  api("org.joml:joml:1.10.8")
  api("org.jspecify:jspecify:1.0.0")
  api("org.lwjgl:lwjgl-freetype:3.4.1")
  api("org.lwjgl:lwjgl-glfw:3.4.1")
  api("org.lwjgl:lwjgl-jemalloc:3.4.1")
  api("org.lwjgl:lwjgl-openal:3.4.1")
  api("org.lwjgl:lwjgl-opengl:3.4.1")
  api("org.lwjgl:lwjgl-stb:3.4.1")
  api("org.lwjgl:lwjgl-tinyfd:3.4.1")
  api("org.lwjgl:lwjgl:3.4.1")
  api("org.slf4j:slf4j-api:2.0.17")

  api("io.github.llamalad7:mixinextras-fabric:0.5.3")
  api("org.checkerframework:checker-qual:3.54.0")

  api(projects.buildData)

  // Newest netty
  api("io.netty:netty-all:4.2.12.Final")

  api("io.github.classgraph:classgraph:4.8.184")

  // For microsoft account authentication
  api(libs.minecraftauth) {
    exclude("com.google.code.gson", "gson")
    exclude("org.slf4j", "slf4j-api")
  }

  // For profiling
  api(libs.spark) {
    exclude("org.ow2.asm", "asm")
  }

  // Log/Console libraries
  api(libs.bundles.log4j)
  api(libs.jline)
  api(libs.jansi)
  api(libs.bundles.ansi4j)
  api(libs.terminalconsoleappender)
  api(libs.slf4j)
  api(libs.disruptor)

  api(libs.bundles.kyori)
  api(libs.commons.validator)
  api(libs.commons.io)

  api(libs.openai)

  api(libs.guava)
  api(libs.gson)
  api(libs.fastutil)
  api(libs.caffeine)
  api(libs.jetbrains.annotations)
  api(libs.immutables.gson)

  api(libs.reflect)
  api(libs.lambdaevents)

  // For database support
  api(libs.bundles.jooq)
  api(libs.flyway.core)
  api(libs.flyway.mysql)
  api(libs.hikaricp)
  api(libs.sqlite)
  api(libs.mariadb)

  api(libs.bundles.armeria)
  api(libs.bundles.reactor.netty)

  // For mail support
  api(libs.angus)

  // For tls cert provisioning
  api(libs.acme4j)

  // For early mixins
  api(libs.bundles.classtransform)
}
