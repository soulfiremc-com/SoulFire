import com.soulfiremc.gradle.PrepareVulkanRuntime
import com.soulfiremc.gradle.ValidateVulkanPublication
import com.soulfiremc.gradle.VulkanPlatforms

plugins {
  id("sf-vulkan-runtime")
  id("com.vanniktech.maven.publish.base")
}

version = providers.gradleProperty("vulkan_runtime_version").get()

val prepareVulkanRuntime = tasks.named<PrepareVulkanRuntime>("prepareVulkanRuntime")
val validateVulkanPublication = tasks.register<ValidateVulkanPublication>("validateVulkanPublication") {
  group = "verification"
  description = "Requires all supported platforms before publishing a native runtime version."
  platforms.set(prepareVulkanRuntime.flatMap { it.platforms })
}

val metadataJar = tasks.register<Jar>("metadataJar") {
  archiveBaseName.set(project.name)
  from("ARTIFACTS.md") {
    rename { "README.md" }
  }
  from(rootProject.file("LICENSE"))
  manifest.attributes("Implementation-Version" to project.version)
}
val sourcesJar = tasks.register<Jar>("sourcesJar") {
  archiveBaseName.set(project.name)
  archiveClassifier.set("sources")
  from(layout.projectDirectory) {
    include("scripts/**", "src/**", "Dockerfile", ".dockerignore", "README.md", "ARTIFACTS.md")
  }
  from(rootProject.file("LICENSE"))
}
val javadocJar = tasks.register<Jar>("javadocJar") {
  archiveBaseName.set(project.name)
  archiveClassifier.set("javadoc")
  from("ARTIFACTS.md") {
    rename { "README.md" }
  }
  from(rootProject.file("LICENSE"))
}

tasks.withType<Jar>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

publishing {
  publications.register<MavenPublication>("vulkanRuntime") {
    artifact(metadataJar)
    artifact(sourcesJar)
    artifact(javadocJar)
    VulkanPlatforms.all.forEach { platform ->
      artifact(prepareVulkanRuntime.flatMap { it.destination.file("soulfire-vulkan-$platform.jar") }) {
        classifier = platform
        builtBy(prepareVulkanRuntime, validateVulkanPublication)
      }
    }
    pom {
      name = "Soulfire Vulkan Runtime"
      description = "Vulkan loader, Mesa lavapipe, and native dependencies for headless rendering."
      url = "https://github.com/soulfiremc-com/SoulFire/tree/main/vulkan-runtime"
      licenses {
        license {
          name = "GNU Affero General Public License v3.0"
          url = "https://www.gnu.org/licenses/agpl-3.0.html"
        }
        license {
          name = "Bundled third-party licenses"
          url = "https://github.com/soulfiremc-com/SoulFire/blob/main/vulkan-runtime/ARTIFACTS.md#licenses"
        }
      }
      developers {
        developer {
          id = "AlexProgrammerDE"
          name = "AlexProgrammerDE"
          url = "https://github.com/AlexProgrammerDE"
        }
      }
      scm {
        connection = "scm:git:https://github.com/soulfiremc-com/SoulFire.git"
        developerConnection = "scm:git:ssh://git@github.com/soulfiremc-com/SoulFire.git"
        url = "https://github.com/soulfiremc-com/SoulFire"
      }
    }
  }
  repositories.maven {
    name = "staging"
    url = layout.buildDirectory.dir("publishing/staging").get().asFile.toURI()
  }
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()
}
