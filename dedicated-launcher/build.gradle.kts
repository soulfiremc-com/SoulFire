plugins {
  `sf-launcher-distribution`
}

base {
  archivesName = "SoulFireDedicated"
}

val projectMainClass = "com.soulfiremc.launcher.SoulFireDedicatedJava8Launcher"

tasks.register("runSFDedicated", JavaExec::class) {
  group = "application"
  description = "Runs the SoulFire dedicated server"

  javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
  }

  mainClass = projectMainClass
  classpath = files(tasks.named("uberJar"))

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-XX:StackShadowPages=32",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "-Xmx8G",
    "-XX:+EnableDynamicAgentLoading",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",
    "-XX:+UseStringDeduplication",
    "-XX:+UseCompactObjectHeaders",
    "-XX:+AlwaysActAsServerClassMachine",
    "-XX:+UseNUMA",
    "-XX:+UseFastUnorderedTimeStamps",
    "-XX:+UseVectorCmov",
    "-XX:+UseCriticalJavaThreadPriority",
    "-Dsf.flags.v2=true"
  )

  if (providers.systemProperty("os.name").get().startsWith("Mac")) {
    argsMutable += "-XstartOnFirstThread"
  }

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

tasks.register("dumpSFOpenAPI", JavaExec::class) {
  group = "application"
  description = "Runs the SoulFire dedicated server and dumps the OpenAPI schema"

  javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
  }

  mainClass = projectMainClass
  classpath = files(tasks.named("uberJar"))

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-XX:StackShadowPages=32",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "-Xmx8G",
    "-XX:+EnableDynamicAgentLoading",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",
    "-XX:+UseStringDeduplication",
    "-XX:+UseCompactObjectHeaders",
    "-XX:+AlwaysActAsServerClassMachine",
    "-XX:+UseNUMA",
    "-XX:+UseFastUnorderedTimeStamps",
    "-XX:+UseVectorCmov",
    "-XX:+UseCriticalJavaThreadPriority",
    "-Dsf.flags.v2=true",
    "-Dsf.dumpOpenApi=true"
  )

  if (providers.systemProperty("os.name").get().startsWith("Mac")) {
    argsMutable += "-XstartOnFirstThread"
  }

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

launcherDistribution {
  mainClass.set(projectMainClass)
}
