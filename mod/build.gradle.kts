import org.gradle.api.file.FileCollection
import org.gradle.process.CommandLineArgumentProvider

plugins {
  `sf-special-publish-conventions`
  id("net.fabricmc.fabric-loom")
  alias(libs.plugins.jmh)
  alias(libs.plugins.jooq.codegen)
  id("com.gradleup.shadow")
}

private class FabricSystemLibrariesArgumentProvider(
  @get:Classpath
  val classpath: FileCollection
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val systemLibraries = classpath.files
      .asSequence()
      .filter { it.isFile && it.extension == "jar" }
      .filter { file -> SYSTEM_LIBRARY_PREFIXES.any(file.name::startsWith) }
      .joinToString(File.pathSeparator) { it.absolutePath }

    return listOf("-Dfabric.systemLibraries=$systemLibraries")
  }

  companion object {
    private val SYSTEM_LIBRARY_PREFIXES = listOf(
      "mariadb-java-client-",
      "sqlite-jdbc-"
    )
  }
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  minecraft("com.mojang:minecraft:26.2")
  implementation("net.fabricmc:fabric-loader:0.19.3")

  val viaFabricPlusNotation = "com.viaversion:viafabricplus:4.6.0"
  implementation(viaFabricPlusNotation) {
    exclude("org.lz4")
  }
  include(viaFabricPlusNotation) {
    isTransitive = false
  }

  annotationProcessor("io.github.llamalad7:mixinextras-fabric:0.5.4")

  annotationProcessor(libs.immutables.gson)
  compileOnly(libs.immutables.value)
  annotationProcessor(libs.immutables.value)

  // Reactor for reactive script execution
  api(libs.reactor.core)
  testImplementation(libs.reactor.test)

  // EvalEx for math expression evaluation in scripts
  api(libs.evalex)
  include(libs.evalex)

  annotationProcessor(libs.picoli.codegen)

  // For CLI support
  api(libs.picoli) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  api(projects.proto) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  val headlessMcNotation = "headlessmc:headlessmc-lwjgl:2.8.0:no-asm@jar"
  api(headlessMcNotation) {
    exclude("io.netty")
    exclude("org.slf4j")
  }

  testRuntimeOnly(libs.junit.launcher)
  testRuntimeOnly(libs.fabric.loader.junit)
  testImplementation(libs.junit)

  jooqCodegen(libs.jooq.codegen)
  jooqCodegen(libs.jooq.meta.extensions)
  jooqCodegen(libs.sqlite)

  api(projects.buildData)

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
  api("org.checkerframework:checker-qual:4.2.1")
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

  // For optional io_uring event loop support
  api("io.netty:netty-transport-classes-io_uring")
}

loom {
  accessWidenerPath = file("src/main/resources/soulfire.accesswidener")

  runs {
    configureEach {
      generateRunConfig = false
    }
  }
}

tasks.shadowJar {
  val mainOutputDirectories = sourceSets.main.get().output.files.map { it.toPath().toAbsolutePath().normalize() }

  dependsOn(tasks.jar)
  from(zipTree(tasks.jar.flatMap { it.archiveFile }))
  from(rootProject.layout.projectDirectory.file("LICENSE"))
  eachFile {
    val sourcePath = file.toPath().toAbsolutePath().normalize()
    if (mainOutputDirectories.any(sourcePath::startsWith)) {
      exclude()
    }
  }

  archiveClassifier.set("")
  destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
  filesMatching("META-INF/services/**") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }
  mergeServiceFiles()
  dependencies {
    exclude(dependency("io.github.llamalad7:mixinextras-fabric:.*"))
    exclude(dependency("net.fabricmc:dev-launch-injector:.*"))
    exclude(dependency("net.fabricmc:fabric-loader:.*"))
    exclude(dependency("net.fabricmc:fabric-log4j-util:.*"))
    exclude(dependency("net.fabricmc:sponge-mixin:.*"))
    exclude(dependency("org.ow2.asm:asm:.*"))
    exclude(dependency("org.ow2.asm:asm-analysis:.*"))
    exclude(dependency("org.ow2.asm:asm-commons:.*"))
    exclude(dependency("org.ow2.asm:asm-tree:.*"))
    exclude(dependency("org.ow2.asm:asm-util:.*"))
  }
}

tasks.jar {
  archiveClassifier.set("raw")
}

configurations {
  testRuntimeClasspath {
    exclude(group = "net.fabricmc", module = "fabric-log4j-util")
  }
}

tasks {
  test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("fabric.debug.disableModIds", "viafabricplus,viafabricplus-api,viafabricplus-visuals")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgumentProviders.add(FabricSystemLibrariesArgumentProvider(configurations.testRuntimeClasspath.get()))
  }

  processIncludeJars {
    dependsOn(":proto:jar")
  }

  compileJava {
    dependsOn(named("jooqCodegen"))
  }

  named("sourcesJar") {
    dependsOn(named("jooqCodegen"))
  }
}

configurations.create("mod-jar")

artifacts {
  add("mod-jar", tasks.shadowJar.flatMap { it.archiveFile }) {
    builtBy(tasks.shadowJar)
  }
}

publishing {
  publications {
    getByName<MavenPublication>("mavenJava") {
      artifact(tasks.shadowJar)
    }
  }
}

jooq {
  configuration {
    generator {
      database {
        name = "org.jooq.meta.extensions.ddl.DDLDatabase"
        properties {
          property {
            key = "scripts"
            value = "src/main/resources/db/migration/*.sql"
          }
          property {
            key = "sort"
            value = "flyway"
          }
          property {
            key = "defaultNameCase"
            value = "as_is"
          }
        }
      }
      generate {
        isDeprecated = false
        isRecords = true
        isPojos = false
        isFluentSetters = true
      }
      target {
        packageName = "com.soulfiremc.server.database.generated"
        directory = "build/generated-sources/jooq"
      }
    }
  }
}


jmh {
  warmupIterations = 2
  iterations = 2
  fork = 2
}
