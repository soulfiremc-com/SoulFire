enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins {
  id("com.gradle.develocity") version "4.4.1"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      file("gradle/libs.versions.toml")
    }
  }
}

develocity {
  buildScan {
    val isCi = !System.getenv("CI").isNullOrEmpty()
    if (isCi) {
      termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
      termsOfUseAgree = "yes"
      tag("CI")
    }
    publishing.onlyIf { isCi }
  }
}

include(
  "build-data",
  "proto",
  "shared",
  "mod",
  "launcher",
  "j8-launcher",
  "client-launcher",
  "dedicated-launcher",
  "javadoc",
)

rootProject.name = "soulfire"
