import net.ltgt.gradle.errorprone.errorprone

plugins {
  idea
  `java-library`
  id("sf-formatting-conventions")
  id("io.freefair.lombok")
  id("net.kyori.indra.git")
  id("io.freefair.javadoc-utf-8")
  id("net.ltgt.errorprone")
  id("com.github.spotbugs")
  id("org.openrewrite.rewrite")
}

rewrite {
  activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
  activeRecipe("org.openrewrite.staticanalysis.CodeCleanup")
  activeRecipe("org.openrewrite.staticanalysis.JavaApiBestPractices")
  activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
  activeRecipe("org.openrewrite.java.testing.cleanup.BestPractices")
  activeRecipe("org.openrewrite.java.migrate.UpgradeToJava25")
  isExportDatatables = true
}

spotbugs {
  ignoreFailures = true
  excludeFilter = file("${rootProject.projectDir}/buildSrc/spotbugs-exclude.xml")
}

dependencies {
  errorprone("com.google.errorprone:error_prone_core:2.49.0")
  spotbugs("com.github.spotbugs:spotbugs:4.9.8")

  rewrite("org.openrewrite.recipe:rewrite-static-analysis:2.34.0")
  rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.34.0")
  rewrite("org.openrewrite.recipe:rewrite-rewrite:0.24.2")
}

tasks {
  // Variable replacements
  processResources {
    // Capture values at configuration time for configuration cache compatibility
    val expandProperties = mapOf(
      "version" to project.version.toString(),
      "description" to (project.description ?: ""),
      "url" to "https://soulfiremc.com",
      "commit" to (indraGit.commit().orNull?.name ?: "unknown"),
      "branch" to (indraGit.branchName().orNull ?: "unknown"),
    )
    inputs.properties(expandProperties)
    filesMatching(listOf("fabric.mod.json", "soulfire-build-data.properties")) {
      expand(expandProperties)
    }
  }
  javadoc {
    title = "SoulFire Javadocs"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
  }
  withType<JavaCompile> {
    options.errorprone {
      disableWarningsInGeneratedCode = true
    }
    options.encoding = Charsets.UTF_8.name()
    options.compilerArgs.addAll(
      listOf(
        "-parameters",
        "-nowarn",
        "-Xlint:unchecked",
        "-Xlint:deprecation",
        "-Xlint:-processing"
      )
    )
    options.isFork = true
  }
  test {
    reports.junitXml.required = true
    reports.html.required = true
    useJUnitPlatform()
    maxParallelForks = Runtime.getRuntime().availableProcessors().div(2).coerceAtLeast(1)
  }
  withType<Jar> {
    from(rootProject.file("LICENSE"))
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
  withJavadocJar()
  withSourcesJar()
}

lombok {
  version = "1.18.46"
}

afterEvaluate {
  tasks.withType<Zip> {
    isZip64 = true
  }
}
