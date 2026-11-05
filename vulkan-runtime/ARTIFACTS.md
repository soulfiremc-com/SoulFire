# Vulkan runtime artifacts

The module publishes `com.soulfiremc:vulkan-runtime:<version>` to Maven Central.
Its version comes from `vulkan_runtime_version` in `gradle.properties`.
It has no Maven dependencies.

## Classifiers

| Classifier | Operating system | Architecture |
| --- | --- | --- |
| `linux-x86_64` | Linux | x86-64 |
| `linux-arm64` | Linux | ARM64 |
| `windows-x86_64` | Windows | x86-64 |
| `windows-arm64` | Windows | ARM64 |
| `macos-x86_64` | macOS | x86-64 |
| `macos-arm64` | macOS | ARM64 |

Each classifier contains the Vulkan loader, Mesa lavapipe, LLVM, and the native dependencies required on that platform.
The JAR without a classifier contains documentation and the project license.
The `sources` JAR contains the build scripts, Dockerfile, native smoke-test sources, and documentation.
The `javadoc` JAR contains this reference because the module has no Java API.

An example Gradle dependency selects one platform explicitly:

```kotlin
runtimeOnly("com.soulfiremc:vulkan-runtime:1.0.0:linux-x86_64")
```

## Package layout

Each platform JAR stores its files under `soulfire-vulkan/<platform>/`:

| File | Purpose |
| --- | --- |
| `runtime.properties` | Loader filename, driver filename, and SHA-256 checksums for packaged files |
| Native libraries | The loader, software renderer, and dependency libraries |
| `sources.json` | Upstream source URLs and SHA-256 checksums |
| `build-packages.txt` | Installed build packages on platforms that provide this inventory |
| `licenses/` | License notices for bundled components |

The `loader` and `driver` properties identify the native entry points.
Properties named `sha256.<relative-path>` give the checksum of each packaged file.
The runtime extracts these files together so native dependencies remain beside the loader and driver.

## Licenses

The Soulfire build scripts and native smoke test use the project's GNU AGPLv3 license.
Bundled components retain their upstream licenses, including the Mesa, Vulkan loader, LLVM, and system library licenses.
Each platform JAR includes the collected license notices under `licenses/`.
The POM's Soulfire license does not replace those component licenses.

Upstream source archives and their checksums appear in `sources.json` inside each platform JAR.
The sources JAR contains the scripts that retrieve those archives and build the runtime.
