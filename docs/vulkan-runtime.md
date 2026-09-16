# Bundled headless Vulkan runtime

SoulFire renders through Minecraft's Vulkan backend and LWJGL. Release JARs include a Vulkan loader and Mesa lavapipe for CPU rendering.
The runtime does not require a system Vulkan loader, Mesa installation, display server, or GPU.

Installed GPU drivers remain available. SoulFire prefers discrete GPUs, then integrated GPUs, then CPU rendering. Minecraft validates each candidate's features and extensions.
On macOS, the JAR also includes LWJGL's MoltenVK libraries for Metal hardware rendering.

## Runtime packages

The release build requires all six packages:

- Linux x86-64 and ARM64, built against Debian 12 glibc.
- Windows x86-64 and ARM64.
- macOS Intel and Apple Silicon.

The Java runtime and baseline OS libraries remain prerequisites. Linux packages target glibc, not Alpine's musl ABI.
Hardware rendering still requires the host's GPU driver. Containers also need explicit GPU access to use that driver.

Each package includes the loader, lavapipe, its native dependency closure, license notices, and SHA-256 hashes.
Libraries extract into the SoulFire data directory under `natives/vulkan/<platform>/<manifest-hash>`.
Extraction uses a file lock, verifies hashes, and replaces corrupt cache files before loading them.

The loader uses `VK_LUNARG_direct_driver_loading` in inclusive mode to add lavapipe alongside normal driver discovery.
This also works when running as root. It does not change Vulkan environment variables or register drivers with the OS.
An explicit `org.lwjgl.vulkan.libname` override retains LWJGL's normal behavior and bypasses the bundled runtime.

## Build a Linux package locally

Run these commands from the repository root:

```bash
docker build -t soulfire-vulkan-builder -f build-data/vulkan/Dockerfile .
docker run --rm -v "$PWD:/work" soulfire-vulkan-builder
./gradlew :dedicated-launcher:uberJar -PvulkanPlatforms=linux-x86_64
```

Use `linux-arm64` on an ARM64 host. The property permits a local build containing only the specified platform.
Without this property, packaging fails if any release platform is missing.

The native build downloads checksum-pinned Mesa, Vulkan loader, Vulkan headers, and glslang sources.
It disables window-system integrations and bundles non-system native dependencies.
Before packaging, a native smoke test creates a CPU device, submits work, and verifies buffer readback.

The reusable `vulkan-runtime.yml` workflow builds all six platforms, caches the packages, and supplies them to build and release jobs.
The Windows and macOS build steps also document their build-tool dependencies. These tools are not runtime requirements.

## Validate the production container

Install Pillow in your development Python environment. Supply a Minecraft 26.2 server JAR, then run:

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
