plugins {
  `java-base`
  id("io.freefair.javadoc-utf-8")
  id("io.freefair.aggregate-javadoc")
}

// Ensure all subprojects are evaluated before this project configures dependencies
rootProject.subprojects.forEach { subproject ->
  if (subproject.name != "javadoc" && subproject.name != "data-generator") {
    evaluationDependsOn(subproject.path)
  }
}

dependencies {
  javadocClasspath("org.projectlombok:lombok:1.18.46")
  javadocClasspath(libs.immutables.value)
  javadocClasspath(libs.immutables.gson)

  rootProject.subprojects.forEach { subproject ->
    if (subproject.name == "data-generator") {
      return@forEach
    }

    if (subproject.plugins.hasPlugin("java")) {
      javadocClasspath(subproject)
      javadoc(subproject)
    }
  }
}

// Configure mod compile classpath - mod project is already evaluated due to evaluationDependsOn
val javadocTask = tasks.named<Javadoc>("javadoc")
val modCompileClasspath = project(":mod").extensions
  .getByType<SourceSetContainer>()["main"].compileClasspath

javadocTask.configure {
  classpath = classpath.plus(modCompileClasspath)
}

val usedJavadocTool: Provider<JavadocTool> = javaToolchains.javadocToolFor {
  languageVersion = JavaLanguageVersion.of(25)
}

tasks {
  javadoc {
    dependsOn(":mod:classes")
    title = "SoulFire Javadocs"

    val opts = options as StandardJavadocDocletOptions
    opts.addStringOption("Xdoclint:none", "-quiet")
    opts.addBooleanOption("-enable-preview", true)
    opts.source = "25"

    javadocTool = usedJavadocTool
  }

  build {
    dependsOn(javadoc)
  }
}

tasks.register<Javadoc>("generateTSDoc") {
  group = "documentation"
  description = "Generates the typescript documentation for the project"

  source = tasks.javadoc.get().source
  classpath = tasks.javadoc.get().classpath
  destinationDir = rootProject.layout.buildDirectory.asFile.get().resolve("docs/typescript/headers")
  options.doclet = "com.soulfiremc.doclet.TSDoclet"
  options.docletpath = listOf(rootProject.rootDir.resolve("buildSrc/build/libs/soulfire-buildsrc.jar"))
  (options as StandardJavadocDocletOptions).addStringOption("v", project.version.toString())
}

tasks.register<Javadoc>("generatePyDoc") {
  group = "documentation"
  description = "Generates the python documentation for the project"

  source = tasks.javadoc.get().source
  classpath = tasks.javadoc.get().classpath
  destinationDir = rootProject.layout.buildDirectory.asFile.get().resolve("docs/python/headers")
  options.doclet = "com.soulfiremc.doclet.PyDoclet"
  options.docletpath = listOf(rootProject.rootDir.resolve("buildSrc/build/libs/soulfire-buildsrc.jar"))
  (options as StandardJavadocDocletOptions).addStringOption("v", project.version.toString())
}
