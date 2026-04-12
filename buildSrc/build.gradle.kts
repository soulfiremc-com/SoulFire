plugins {
  `kotlin-dsl`
}

repositories {
  maven("https://maven.fabricmc.net") {
    name = "FabricMC"
  }
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  // this is OK as long as the same version catalog is used in the main build and build-logic
  // see https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
  implementation(libs.gradle.plugin.shadow)
  implementation(libs.gradle.plugin.lombok)
  implementation(libs.gradle.plugin.indra.git)
  implementation(libs.gradle.plugin.loom)
  implementation(libs.gradle.plugin.spotless)
  implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.4.12")
  implementation(libs.gradle.plugin.freefair)
  implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:5.1.0")
  implementation("org.openrewrite:plugin:7.30.0")
  implementation("org.ow2.asm:asm:9.9.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}
