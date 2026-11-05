import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register

fun PublishingExtension.applyMainPublishing(project: Project) {
  repositories {
    val repoName = if (project.version.toString().endsWith("SNAPSHOT")) "maven-snapshots" else "maven-releases"
    maven("https://repo.codemc.org/repository/${repoName}/") {
      name = "codemc"
      credentials {
        username = System.getenv("CODEMC_USERNAME")
        password = System.getenv("CODEMC_PASSWORD")
      }
    }
  }

  publications {
    register<MavenPublication>("mavenJava") {
      pom {
        name = "Soulfire"
        description = project.rootProject.description
        url = "https://soulfiremc.com"
        organization {
          name = "AlexProgrammerDE"
          url = "https://pistonmaster.net"
        }
        developers {
          developer {
            id = "AlexProgrammerDE"
            timezone = "Europe/Berlin"
            url = "https://pistonmaster.net"
          }
        }
        licenses {
          license {
            name = "GNU Affero General Public License v3.0"
            url = "https://www.gnu.org/licenses/agpl-3.0.html"
          }
        }
        scm {
          connection = "scm:git:https://github.com/soulfiremc-com/SoulFire.git"
          developerConnection = "scm:git:ssh://git@github.com/soulfiremc-com/SoulFire.git"
          url = "https://github.com/soulfiremc-com/SoulFire"
        }
        ciManagement {
          system = "GitHub Actions"
          url = "https://github.com/soulfiremc-com/SoulFire/actions"
        }
        issueManagement {
          system = "GitHub"
          url = "https://github.com/soulfiremc-com/SoulFire/issues"
        }
      }
    }
  }
}
