plugins {
  `sf-project-conventions`
}

base {
  archivesName = "SoulFireCLI"
}

val projectMainClass = "com.soulfiremc.launcher.SoulFireCLIJava8Launcher"

tasks.register("runSFCLI", JavaExec::class) {
  group = "application"
  description = "Runs the SoulFire client"

  javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
  }

  mainClass = projectMainClass
  classpath = sourceSets["main"].runtimeClasspath

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-Xmx8G",
    "-XX:+EnableDynamicAgentLoading",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",
    "-XX:+UseCompactObjectHeaders",
    "-XX:+AlwaysActAsServerClassMachine",
    "-XX:+UseNUMA",
    "-XX:+UseFastUnorderedTimeStamps",
    "-XX:+UseVectorCmov",
    "-XX:+UseCriticalJavaThreadPriority",
    "-Dsf.flags.v2=true"
  )

  if (System.getProperty("idea.active") != null) {
    argsMutable += "-Dnet.kyori.ansi.colorLevel=truecolor"
  }

  jvmArgs = argsMutable

  standardInput = System.`in`

  outputs.upToDateWhen { false }
}

tasks.register("printSFCLIFlags", JavaExec::class) {
  group = "application"
  description = "Runs the SoulFire client"

  javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
  }

  mainClass = projectMainClass
  classpath = sourceSets["main"].runtimeClasspath
  args = listOf(
    "--generate-flags"
  )

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-Xmx2G",
    "-XX:+EnableDynamicAgentLoading",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",
    "-XX:+AlwaysActAsServerClassMachine",
    "-XX:+UseNUMA",
    "-XX:+UseFastUnorderedTimeStamps",
    "-XX:+UseVectorCmov",
    "-XX:+UseCriticalJavaThreadPriority",
    "-Dsf.flags.v2=true",
    "-Dsf.cliHomeDir=."
  )

  if (System.getProperty("idea.active") != null) {
    argsMutable += "-Dnet.kyori.ansi.colorLevel=truecolor"
  }

  jvmArgs = argsMutable

  standardInput = System.`in`

  val runDir = projectDir.resolve("run")
  runDir.mkdirs()
  workingDir = runDir

  outputs.upToDateWhen { false }
}

dependencies {
  libs.bundles.bom.get().forEach { api(platform(it)) }

  api(projects.launcher)

  // The java 8 launcher takes care of notifying the user if they are using an unsupported java version
  implementation(projects.j8Launcher)
}

// Capture version at configuration time for configuration cache compatibility
val projectVersionString = version.toString()
val launcherPathPattern = "launcher" + File.separator + "build" + File.separator + "libs"

// Configuration for jars to shadow vs include as libs
val shadowedJarsConfig: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  extendsFrom(configurations.runtimeClasspath.get())
}

val libJarsConfig: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  extendsFrom(configurations.runtimeClasspath.get())
}

tasks {
  val generateDependencyList = register("generateDependencyList") {
    inputs.files(configurations.runtimeClasspath)

    val outputFile = layout.buildDirectory.file("dependency-list.txt")
    outputs.file(outputFile)

    // Capture pattern inside task configuration to avoid capturing script object
    val pathPattern = launcherPathPattern
    doLast {
      val dependencies = inputs.files.files
        .filter { it.name.endsWith(".jar") }
        .filter { !it.toString().contains(pathPattern) }
        .joinToString("\n") { it.name }
      outputFile.get().asFile.writeText(dependencies)
    }
  }
  jar {
    archiveClassifier = "unshaded"

    manifest {
      attributes["Main-Class"] = projectMainClass
      attributes["Name"] = "SoulFire"
      attributes["Specification-Title"] = "SoulFire"
      attributes["Specification-Version"] = projectVersionString
      attributes["Specification-Vendor"] = "AlexProgrammerDE"
      attributes["Implementation-Title"] = "SoulFire"
      attributes["Implementation-Version"] = projectVersionString
      attributes["Implementation-Vendor"] = "AlexProgrammerDE"
      attributes["Multi-Release"] = "true"
      attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
  }
  val uberJar = register<Jar>("uberJar") {
    dependsOn(":launcher:verifyVulkanRuntime")
    val jarTask = jar
    dependsOn(jarTask)

    manifest {
      attributes["Main-Class"] = projectMainClass
      attributes["Name"] = "SoulFire"
      attributes["Specification-Title"] = "SoulFire"
      attributes["Specification-Version"] = projectVersionString
      attributes["Specification-Vendor"] = "AlexProgrammerDE"
      attributes["Implementation-Title"] = "SoulFire"
      attributes["Implementation-Version"] = projectVersionString
      attributes["Implementation-Vendor"] = "AlexProgrammerDE"
      attributes["Multi-Release"] = "true"
      attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Include content from main jar
    from(jarTask.map { it.outputs.files.map { file -> zipTree(file) } })

    // Shade launcher jars (extract and include contents)
    from(configurations.runtimeClasspath.map { config ->
      config.files
        .filter { it.name.endsWith(".jar") }
        .filter { it.toString().contains(launcherPathPattern) }
        .map { zipTree(it) }
    })

    // Include other jars as libraries
    from(configurations.runtimeClasspath.map { config ->
      config.files
        .filter { it.name.endsWith(".jar") }
        .filter { !it.toString().contains(launcherPathPattern) }
    }) {
      into("META-INF/lib")
    }

    from(generateDependencyList.map { it.outputs.files }) {
      into("META-INF")
    }
  }
  build {
    dependsOn(uberJar)
  }
}
