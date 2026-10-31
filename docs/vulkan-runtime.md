# Bundled headless Vulkan runtime

SoulFire renders through Minecraft's Vulkan backend and LWJGL. Release JARs include a Vulkan loader and Mesa lavapipe for CPU rendering.
The runtime does not require a system Vulkan loader, Mesa installation, display server, or GPU.

Installed GPU drivers remain available. SoulFire prefers discrete GPUs, then integrated GPUs, then CPU rendering. Minecraft validates each candidate's features and extensions.
On macOS, the JAR also includes LWJGL's MoltenVK libraries for Metal hardware rendering.

## Runtime packages

Published release JARs include all six packages:

- Linux x86-64 and ARM64, built against Debian 12 glibc.
- Windows x86-64 and ARM64, with the OS version required by Java 25.
- macOS 15 or newer, on Intel and Apple Silicon.

The Java runtime and baseline OS libraries remain prerequisites. Linux packages target glibc, not Alpine's musl ABI.
Hardware rendering still requires the host's GPU driver. Containers also need explicit GPU access to use that driver.

Each package includes the loader, lavapipe, its native dependency closure, license notices, and SHA-256 hashes.
Libraries extract into the SoulFire data directory under `natives/vulkan/<platform>/<manifest-hash>`.
Extraction uses a file lock, verifies hashes, and replaces corrupt cache files before loading them.

The loader uses `VK_LUNARG_direct_driver_loading` in inclusive mode to add lavapipe alongside normal driver discovery.
This also works when running as root. It does not change Vulkan environment variables or register drivers with the OS.
Explicit `VK_DRIVER_FILES` or `VK_ICD_FILENAMES` selections use the bundled loader with only the selected driver manifests.
An explicit `org.lwjgl.vulkan.libname` override retains LWJGL's normal behavior and bypasses the bundled runtime.

## Force software rendering

If a GPU driver crashes during startup, add `-Dsf.vulkan.software=true` before `-jar` in the Java command.
This flag requires SoulFire 2.11 or newer.
SoulFire then uses only bundled lavapipe and does not discover or initialize GPU drivers.
This mode also overrides `VK_DRIVER_FILES`, `VK_ICD_FILENAMES`, and `org.lwjgl.vulkan.libname`.
CPU rendering can reduce performance and increase CPU usage.

SoulFireClient provides the same flag through **Force software rendering** in the integrated server settings.
The setting takes effect the next time the integrated server starts.

## Build for the current host

The `:vulkan-runtime` subproject owns the native sources, build script, Dockerfile, and generated packages.
The mod and launchers consume its outgoing artifacts through project dependencies.
Gradle builds the host runtime before it packages either launcher. Linux builds require Docker and use the Debian 12 builder image.
Windows builds require the MSYS2 tools listed in `.github/workflows/vulkan-runtime.yml`.
macOS builds require the Homebrew tools and Python environment listed in that workflow.

Run these commands from the repository root:

```bash
./gradlew :dedicated-launcher:uberJar
```

To build only the native package, run `./gradlew :vulkan-runtime:assemble`.
Gradle places the package in `vulkan-runtime/build/runtime` and skips native compilation when its inputs and output stay unchanged.
The first build downloads and compiles the native sources. Later builds reuse the native build directory.

Normal builds include only the host runtime. Jenkins on Linux therefore produces JARs with the Linux CPU fallback.
An all-platform release requires packages from matching Linux, Windows, and macOS hosts.

Use `-PvulkanBuildJobs=3` to limit compiler concurrency.
Use `-PvulkanPython=/path/to/python` to select Python on Windows or macOS.
Linux developers with the required native tools can use `-PvulkanUseDocker=false` to build without Docker.

The native build downloads checksum-pinned Mesa, Vulkan loader, Vulkan headers, and glslang sources.
It disables X11 and Wayland integrations and bundles non-system native dependencies.

Linux x86-64 links the required LLVM components statically and hides their internal symbols.
This removes the standalone LLVM library and its unused dependencies without disabling Vulkan features or runtime CPU detection.
The measured Linux x86-64 package decreased from 61.5 MiB to 19.3 MiB.
Linux ARM64, Windows, and macOS retain shared LLVM until equivalent native validation is complete.
The Debian x86-64 builder also installs `libclang-common-15-dev` for LLVM's static Polly archives.
Linux x86-64 builds without Docker require LLVM's static development libraries.

Windows retains Mesa's Win32 support, but SoulFire creates no native window or presentation surface.
Before packaging, a native smoke test creates a CPU device, submits work, and verifies buffer readback.

## Build an all-platform release

The reusable `vulkan-runtime.yml` workflow runs Gradle on all six platforms, caches the packages, and supplies them to build and release jobs.
Those jobs download the packages into `build/vulkan-prebuilt` and run:

```bash
./gradlew build -PvulkanPlatforms=all -PvulkanRuntimeDirectory=build/vulkan-prebuilt
```

Gradle checks each selected package before it stages the packages in `vulkan-runtime/build/runtime`.
Missing packages or packages without the platform manifest stop the build.
The input directory must differ from `vulkan-runtime/build/runtime`, which Gradle manages as an output.
Relative input directory paths start at the repository root.
Use `vulkanPlatforms` with a comma-separated platform list to select a subset from the input directory.
No Maven repository or artifact hosting service is required.

## Validate the production container

Install Pillow in your development Python environment. Supply a Minecraft 26.3 server JAR, then run:

```bash
python3 scripts/validate-vulkan-container.py \
  --jar dedicated-launcher/build/libs/SoulFireDedicated-2.10.1.jar \
  --server-jar /path/to/minecraft-server.jar
```

This opt-in test accepts the Minecraft EULA for its isolated local server.
It builds the production Dockerfile with the local JAR and checks that system Vulkan packages are absent.
It then joins two bots, captures concurrent POV images, and checks inventory PNGs and transparency.
No display socket or GPU device is passed to the container.

Results are saved under `build/vulkan-container`. Logs can contain a temporary local API token, so do not publish them unredacted.
The script stops its container and Minecraft server when validation finishes.
