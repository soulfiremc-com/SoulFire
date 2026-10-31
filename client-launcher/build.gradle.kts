plugins {
  `sf-launcher-distribution`
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
  classpath = files(tasks.named("uberJar"))

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-XX:StackShadowPages=32",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
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
  classpath = files(tasks.named("uberJar"))
  args = listOf(
    "--generate-flags"
  )

  val argsMutable = mutableListOf(
    "--enable-native-access=ALL-UNNAMED", // Needed for JavaExec
    "-XX:StackShadowPages=32",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
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

launcherDistribution {
  mainClass.set(projectMainClass)
}

dependencies {
  implementation(projects.buildData)
  testImplementation(libs.junit)
  testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
  val cliJar = tasks.named<Jar>("uberJar").flatMap { it.archiveFile }
  dependsOn(cliJar)
  inputs.file(cliJar)
  systemProperty("sf.cli.testJar", cliJar.get().asFile.absolutePath)
}
