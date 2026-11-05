# Contributing to Soulfire

Contributions can include bug fixes, tests, documentation, SDK improvements, and new features.
This guide explains how to prepare a change and give reviewers enough information to evaluate it.

Quick links: [environment](#prepare-your-environment), [build](#build-and-run-soulfire),
[code and SDKs](#write-a-focused-change), [tests](#verify-your-change), and [pull requests](#submit-a-pull-request).

## Choose where to start

- Read the [documentation](https://soulfiremc.com/docs) and search [existing issues](https://github.com/soulfiremc-com/SoulFire/issues), including closed issues.
- For setup or usage questions, use [support resources](SUPPORT.md).
- For a bug or feature proposal, use the [issue forms](https://github.com/soulfiremc-com/SoulFire/issues/new/choose).
- For vulnerabilities, use the private contacts in the [security policy](SECURITY.md).
- For substantial features, API changes, or architectural changes, open an issue before implementation to discuss the approach.

Small fixes and documentation corrections can go directly to a pull request. You do not need an issue for every change.
If you want to work on an existing issue, leave a comment with your approach to avoid duplicate work.

This repository contains the server, CLI, SDKs, and supporting tools.
The desktop GUI lives in [SoulFireClient](https://github.com/soulfiremc-com/SoulFireClient).
Contributors must follow the [code of conduct](CODE_OF_CONDUCT.md).
Use bots only on servers that you own or have permission to test.

## Prepare your environment

Install Git and a JDK 25. Use the checked-in Gradle wrapper instead of a separate Gradle installation.
The build selects Java 25 through its toolchain configuration.
The first build needs network access to download Gradle, Minecraft, and dependencies.

For SDK or end-to-end work, also install:

- Bun at the version in the root [package.json](package.json).
- Node.js 24, matching the main [SDK workflow](.github/workflows/sdk.yml).
- Python 3.14 or newer for the Python SDK.
- Docker for the native Linux runtime build and end-to-end scenarios.

Fork the repository if you do not have write access. Clone your fork or the upstream repository:

```bash
git clone https://github.com/soulfiremc-com/SoulFire.git
cd SoulFire
./gradlew test spotlessCheck
```

On Windows, use `gradlew.bat` for the Gradle commands in this guide.
Keep work on the branch selected for your contribution.

### Find the right module

| Location | Purpose |
| --- | --- |
| `mod/` | Bot behavior, server services, shared Java utilities, mixins, resources, and most Java tests |
| `proto/src/main/proto/` | Protobuf definitions shared by the server and SDKs |
| `sdk/typescript/`, `sdk/python/` | SDK implementations and language-specific tests |
| `launcher/` | Shared bootstrap and dependency loading |
| `client-launcher/`, `dedicated-launcher/` | CLI and dedicated server entry points and packaged JARs |
| `j8-launcher/` | Compatibility entry points that report unsupported Java versions |
| `vulkan-runtime/` | Native Vulkan loader, CPU rendering driver, and runtime packages |
| `build-data/`, `buildSrc/`, `gradle/` | Build resources, Gradle conventions, and dependency configuration |
| `e2e/` | Scenarios against a vanilla Minecraft server |
| `docs/`, `scripts/`, `config/` | Technical references, development tools, and IDE configuration |

## Build and run Soulfire

Gradle builds the host Vulkan runtime through the `:vulkan-runtime` subproject before it packages launcher JARs.
Release builds require all six supported platforms.
Read [native runtime packaging](docs/vulkan-runtime.md) for platform details and build instructions.

On Linux, install Docker, then run:

```bash
./gradlew build
```

Gradle selects the host platform automatically.
Use `./gradlew :vulkan-runtime:assemble` to build only the native package.
The package appears in `vulkan-runtime/build/runtime/`.

The packaged JARs appear in `client-launcher/build/libs/` and `dedicated-launcher/build/libs/`.
To run either development entry point, use:

```bash
./gradlew :client-launcher:runSFCLI
./gradlew :dedicated-launcher:runSFDedicated
```

For a dedicated JAR only, use `./gradlew :dedicated-launcher:uberJar`.
For the Fabric-ready mod, use `./gradlew :mod:remapJar`.
To inspect Minecraft sources, run `./gradlew genSources` and open the generated sources through your IDE.

## Write a focused change

Keep each pull request focused on one problem. Explain any behavior or compatibility changes.
Avoid unrelated formatting, dependency updates, and broad refactors.
Remove obsolete code rather than leaving placeholder implementations or compatibility shims.

### Java conventions

- Follow [.editorconfig](.editorconfig) and the existing code around your change.
- Use two spaces, LF line endings, and the license header from `file_header.txt`.
- Keep packages under `com.soulfiremc`, class names in PascalCase, fields in camelCase, and constants in UPPER_SNAKE_CASE.
- Prefer `var` for local variables and immutable collections such as `List.of()`.
- Use try-with-resources for resources that need cleanup.
- Avoid wildcard imports. Let Spotless order imports and static imports.
- Use `///` Markdown Javadocs and `_` for unused lambda parameters.
- Give methods descriptive names and keep null handling explicit.

Run `./gradlew spotlessApply` before submission. Review its diff to avoid unrelated changes.
IntelliJ users can import `config/intellij_inspections.xml` from **Settings > Editor > Inspections**.

### Protobuf and SDK changes

Edit the Protobuf definitions first. Do not edit files marked as generated.
Install workspace dependencies and regenerate both SDK bindings from the repository root:

```bash
bun install --frozen-lockfile
bun run sdk:generate
bun run sdk:lint-proto
bun run --filter @soulfiremc/sdk check
```

The install script clones Effect sources into `.repos/effect` if they are absent.
Binding generation uses the plugins in `buf.gen.yaml`, including remote plugins that require network access.
Include generated changes with their source changes.
For RPC changes, review both SDKs, permission checks, and behavior for existing clients.
The SDK workflow checks Protobuf compatibility against the pull request base branch.
Explain intentional breaking changes and the migration path in your pull request.

For Python SDK work, use a virtual environment with the development dependencies:

```bash
python3.14 -m venv sdk/python/.venv
sdk/python/.venv/bin/python -m pip install -e 'sdk/python[dev]'
sdk/python/.venv/bin/python -m ruff check sdk/python
sdk/python/.venv/bin/python -m ruff format --check sdk/python
sdk/python/.venv/bin/python -m pyright -p sdk/python
sdk/python/.venv/bin/python -m pytest sdk/python
```

On Windows, replace `sdk/python/.venv/bin/python` with `sdk/python/.venv/Scripts/python.exe`.

### Database changes

Soulfire uses Flyway migrations in `mod/src/main/resources/db/migration/`.
For schema changes, add a migration with the next unused version and the existing `V<number>__<description>.sql` naming pattern.
Do not rewrite migrations that users already applied.
Gradle generates the jOOQ classes from these migrations before Java compilation. Do not edit or commit the generated build output.
Verify both a new database and an upgrade from the previous schema for migration changes.
Explain data conversions and any backup or migration steps in the pull request.

## Verify your change

Always run the Java tests and formatting checks from the repository root:

```bash
./gradlew test spotlessCheck
```

For Java or build changes, also run `./gradlew build` after you prepare the required native runtime packages.
For SDK changes, run the relevant language checks above.
Record the exact commands and results in your pull request, including any checks that you could not run.
CI runs the Java build and additional SDK packaging, compatibility, and runtime checks where applicable.

### Unit and integration tests

Add targeted tests for changed logic and regressions where practical.
Put Java tests under the modified module's `src/test/java/`, with packages that match the production code.
Use JUnit 5 and names such as `FeatureTest` or `ComponentIT`.
Tests must run without a live Minecraft server unless the server is explicitly mocked.
Test meaningful outcomes, edge cases, and failure behavior. Avoid tests that only match source text or documentation strings.

For a faster development loop, run a specific test:

```bash
./gradlew :mod:test --tests 'com.soulfiremc.test.PathfindingTest'
```

Gradle writes HTML reports to each module's `build/reports/tests/test/index.html`.
Run the full suite before submission, even if the focused test passes.

### Check Minecraft behavior first

If a fix depends on Minecraft behavior, reproduce that behavior with the [end-to-end harness](e2e/README.md) first.
The harness uses Docker, a vanilla server, and a bot controlled through the TypeScript SDK.
Use the observed game behavior to write the unit test.
For a reproducible fix, run the same scenario against JARs without and with the change.
Add a permanent end-to-end scenario only for behavior that a unit test cannot cover.

For rendering changes, use [renderer validation](docs/lavapipe-test.md) and the container checks in [native runtime packaging](docs/vulkan-runtime.md).
Remove credentials and tokens from logs before you share them.

### Troubleshoot local checks

- For Java selection problems, compare `java -version` with `./gradlew --version` and the Java 25 toolchain requirement.
- For a missing native runtime error, prepare the named package using [native runtime packaging](docs/vulkan-runtime.md).
  A platform property cannot replace a missing package.
- For dependency download errors, inspect the failed URL and your network or proxy configuration.
  Retry the failing Gradle command with `--stacktrace` to obtain details.
- For test failures, read the module's HTML report and reproduce the failure with `--tests` before you change code.

## Submit a pull request

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) for commit messages and pull request titles:

```text
fix(pathfinding): handle blocked destinations
docs(contributing): explain local build prerequisites
```

Use a meaningful scope, or omit it if none applies. Keep subjects under 72 characters and use imperative descriptions.
For non-trivial changes, add a body that explains the motivation and important tradeoffs.
For breaking changes, include a `BREAKING CHANGE:` footer with migration guidance.
Let configured Git hooks finish. Do not bypass hooks with `--no-verify` or disable Lefthook.

Complete the pull request template with:

- The problem, resulting behavior, and affected modules.
- A related issue, if one exists. Use `Closes #123` only if the change fully resolves it.
- Exact test commands, results, and useful reproduction evidence.
- Compatibility changes, migration steps, or known limitations.

Open a draft pull request for early feedback on substantial changes.
Before requesting review, inspect the diff and remove unrelated changes, generated build output, and private data.
Update relevant documentation and examples with user-visible changes.
Respond to review comments and rerun affected checks after revisions.
Maintainers decide whether a change fits the project and is ready to merge after CI passes.

## License and community

Soulfire uses the [GNU Affero General Public License v3.0](LICENSE).
Submit only work that you have the right to contribute under the project license.
Keep existing license notices and identify the source and license of added third-party material.

Use respectful, specific feedback in issues and reviews. Questions and corrections are welcome.
For help with your first contribution, describe your goal and where you are stuck in the [Discord community](https://soulfiremc.com/discord).
