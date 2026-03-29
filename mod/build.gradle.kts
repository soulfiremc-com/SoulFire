plugins {
  `sf-special-publish-conventions`
  id("net.fabricmc.fabric-loom")
  alias(libs.plugins.jmh)
  alias(libs.plugins.jooq.codegen)
}

repositories {
  maven("https://maven.parchmentmc.org") {
    name = "ParchmentMC"
    content {
      includeGroup("org.parchmentmc.data")
    }
  }
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  compileOnly(projects.shared)

  minecraft("com.mojang:minecraft:26.1")
  implementation("net.fabricmc:fabric-loader:0.18.5")

  val viaFabricPlusNotation = "com.viaversion:viafabricplus:4.5.0"
  implementation(viaFabricPlusNotation) {
    exclude("org.lz4")
  }
  include(viaFabricPlusNotation) {
    isTransitive = false
  }

  annotationProcessor("io.github.llamalad7:mixinextras-fabric:0.5.3")

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
  include(libs.picoli) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  api(projects.proto) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  include(projects.proto) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  val headlessMcNotation = "headlessmc:headlessmc-lwjgl:2.8.0:no-asm@jar"
  api(headlessMcNotation) {
    exclude("io.netty")
    exclude("org.slf4j")
  }
  include(headlessMcNotation) {
    exclude("io.netty")
    exclude("org.slf4j")
  }

  testRuntimeOnly(libs.junit.launcher)
  testImplementation(libs.junit)
  testImplementation(projects.shared)

  jmhImplementation(projects.shared)

  jooqCodegen(libs.jooq.codegen)
  jooqCodegen(libs.jooq.meta.extensions)
  jooqCodegen(libs.sqlite)
}

loom {
  accessWidenerPath = file("src/main/resources/soulfire.accesswidener")

  runs {
    configureEach {
      ideConfigGenerated(false)
    }
  }
}

configurations {
  testRuntimeClasspath {
    exclude(group = "net.fabricmc", module = "fabric-log4j-util")
  }
}

tasks {
  test {
    useJUnitPlatform()
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
  add("mod-jar", tasks.jar.flatMap { it.archiveFile }) {
    builtBy(tasks.jar)
  }
}

publishing {
  publications {
    getByName<MavenPublication>("mavenJava") {
      artifact(tasks.jar)
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
