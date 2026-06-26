plugins {
    base
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(25)
}

allprojects {
  group = "com.soulfiremc"
  version = property("maven_version")!!
  description = "Advanced Minecraft Server-Stresser Tool."

  repositories {
    maven("https://repo.viaversion.com") {
      name = "ViaVersion Repository"
      content {
        includeGroup("com.viaversion")
        includeGroup("com.viaversion.mcstructs")
        includeGroup("net.raphimc")
      }
    }
    maven("https://jitpack.io") {
      name = "Jitpack Repository"
      content {
        includeGroupByRegex("com\\.github\\..*")
        excludeGroup("com.github.ben-manes.caffeine")
        includeGroup("dev.kastle.NetworkCompatible")
      }
    }
    maven("https://libraries.minecraft.net") {
      name = "Minecraft Repository"
      content {
        includeGroup("net.minecraft")
        includeGroup("com.mojang")
      }
    }
    maven("https://nexus.lucko.me/repository/hosted/") {
      name = "Lucko Nexus"
      content {
        includeGroup("me.lucko")
        includeModule("net.kyori", "adventure-text-feature-pagination")
      }
    }
    maven("https://maven.fabricmc.net") {
      name = "FabricMC"
      content {
        includeGroup("net.fabricmc")
        includeGroup("net.fabricmc.fabric-api")
      }
    }
    ivy("https://github.com/3arthqu4ke") {
      patternLayout {
        artifact("/[organisation]/releases/download/[revision]/[artifact]-[revision](-[classifier])(.[ext])")
      }
      metadataSources {
        artifact()
      }
      content {
        includeGroup("headlessmc")
      }
    }
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
      name = "Sonatype Snapshot Repository"
      mavenContent { snapshotsOnly() }
    }
    maven("https://maven.lenni0451.net/everything") {
      name = "Lenni0451 Repository"
      content {
        includeGroup("net.raphimc")
      }
    }
    maven("https://repo.opencollab.dev/maven-snapshots") {
      name = "OpenCollab Snapshot Repository"
      content {
        includeGroup("org.cloudburstmc.netty")
      }
    }
    mavenCentral()
  }
}
