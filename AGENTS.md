# Repository Guidelines

## Project Structure & Module Organization

SoulFire is a multi-module Gradle build targeting Java 25. `mod/` holds the Fabric-based bot logic, mixins, and resources in `mod/src/main/resources`, with tests under `mod/src/test/java`. Shared utilities live in `shared/`, protocol definitions in `proto/`, and launchers in `client-launcher/`, `dedicated-launcher/`, and `launcher/`; each publishes jars to its `build/libs`. Auxiliary assets live in `build-data/`, `scripts/`, and `config/` (IDE inspections, lint configs).

## Build, Test, and Development Commands

Use `./gradlew build` for a full compile, remap, and documentation aggregation. Run `./gradlew test` to execute the JUnit 5 suite with production classpaths. `./gradlew :mod:remapJar` emits the Fabric-ready artifact in `mod/build/libs`, while `./gradlew spotlessCheck`/`spotlessApply` enforce formatting.

## Coding Style & Naming Conventions

`.editorconfig` and Spotless enforce two-space indentation, LF endings, trimmed whitespace, and the license header in `file_header.txt`. Keep imports sorted (``, `javax|java`, then `#`), avoid wildcards, and prefer `var` for locals to match existing code. Packages stay under `com.soulfiremc`, fields use camelCase, constants use `UPPER_SNAKE_CASE`, and method names should remain descriptive verbs. Import the IntelliJ inspection profile in `config/intellij_inspections.xml` before submitting.

Use modern Java 25 features such as `var` and `try-with-resources` where possible, and prefer `List.of()`, `Map.of()`, and `Set.of()` for immutable collections. Favor `StringBuilder` for concatenation in loops, and use `Optional` to avoid nulls.
Also use `_` for unused parameters in lambdas and use `///` Markdown Javadocs instead of the old `/** */` syntax.

## Testing Guidelines

Place tests beside the module you modify, e.g., new mod behavior belongs in `mod/src/test/java`. Write JUnit 5 tests (`@Test`, `@ParameterizedTest`) that mirror production package names and run without live Minecraft servers unless explicitly mocked. Name classes `<Feature>Test` or `<Component>IT`, and fail fast on protocol regressions. Always run `./gradlew test` locally and note the command in your PR description.

## Commit & Pull Request Guidelines

Commits follow Conventional Commits (`fix:`, `feat:`, `ci:`); keep subjects under 72 characters and group logically related changes together. Reference issues in the body, add `BREAKING CHANGE:` notes when behavior shifts, and avoid drive-by reformatting. Pull requests need a clear summary, affected modules, and evidence of testing (command output or screenshots/CLI transcripts when user-facing). Request reviews early for large changes but ensure CI is green before merge.

## Fabric loom sources

After running the `./gradlew genSources` command, the generated sources jar is placed into:
`./.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-<something>/<minecraftVersion>/minecraft-merged-<something>-<minecraftVersion>-sources.jar`
