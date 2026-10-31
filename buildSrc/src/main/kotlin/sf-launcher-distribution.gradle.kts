import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.soulfiremc.gradle.LauncherDistributionExtension

plugins {
  id("sf-project-conventions")
  id("com.gradleup.shadow")
}

val launcherDistribution = extensions.create<LauncherDistributionExtension>("launcherDistribution")
val launcherAssetsDependencies = configurations.dependencyScope("launcherAssetsDependencies")
val launcherAssets = configurations.resolvable("launcherAssets") {
  extendsFrom(launcherAssetsDependencies.get())
}

dependencies {
  api(project(":bootstrap"))
  implementation(project(":j8-launcher"))
  add(launcherAssetsDependencies.name, project(path = ":launcher", configuration = "launcherAssetsElements"))
}

val projectVersion = version.toString()
tasks.jar {
  archiveClassifier.set("unshaded")
}

tasks.named<ShadowJar>("shadowJar") {
  enabled = false
}

val uberJar = tasks.register<ShadowJar>("uberJar") {
  archiveClassifier.set("")
  from(sourceSets.main.map { it.output })
  configurations = listOf(project.configurations.runtimeClasspath.get())
  from(launcherAssets)
  relocate("com.google.gson", "com.soulfiremc.launcher.internal.gson")
  exclude("META-INF/maven/**", "module-info.class", "META-INF/versions/**/module-info.class")
  manifest {
    attributes(
      "Main-Class" to launcherDistribution.mainClass,
      "Name" to "SoulFire",
      "Specification-Title" to "SoulFire",
      "Specification-Version" to projectVersion,
      "Specification-Vendor" to "AlexProgrammerDE",
      "Implementation-Title" to "SoulFire",
      "Implementation-Version" to projectVersion,
      "Implementation-Vendor" to "AlexProgrammerDE",
      "Multi-Release" to "true",
      "Add-Exports" to "java.base/jdk.internal.misc",
      "Enable-Native-Access" to "ALL-UNNAMED"
    )
  }
}

tasks.build {
  dependsOn(uberJar)
}
