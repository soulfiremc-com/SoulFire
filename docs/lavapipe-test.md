# Compare POV GUI rendering with Lavapipe

This manual test runs Minecraft 26.2's Vulkan backend on Mesa Lavapipe, a CPU Vulkan driver.
It renders the same item fixture with vanilla and SoulFire's POV GUI renderer, then saves a pixel diff.

The test uses a separate client process and working directory. SoulFire's headless lifecycle and LWJGL interception stay disabled in that process.
Texture mirror hooks supply font and texture pixels to the POV renderer without replacing native rendering.

## Run the test

Requirements:

- Java 25 and the repository's Gradle wrapper.
- Mesa Lavapipe and a Vulkan loader.
- An X11 or Wayland display. On a machine without a display, use Xvfb.
- A local Minecraft 26.2 server with `online-mode=false` and `enforce-secure-profile=false`.

The server supplies item components and registries. The fixture does not change its blocks or inventories.
Use an isolated server with an accepted Minecraft EULA.

1. Start the local server.
2. Run the manual task:

   ```sh
   ./gradlew :mod:runLavapipeTest -PlavapipeServer=127.0.0.1:25640
   ```

   The task connects as `LavapipeTest`, captures the fixture, then closes the client.
   It does not start or stop the server.

3. Open `mod/build/lavapipe-test/output/comparison.png`.

The default Linux driver path is `/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`.
To select another installation, pass `-PlavapipeIcd=/absolute/path/to/lvp_icd.json`.

For Xvfb, run:

```sh
xvfb-run -a ./gradlew :mod:runLavapipeTest -PlavapipeServer=127.0.0.1:25640
```

The task is never invoked by `test`, `check`, or `build`. No manual-test classes are packaged in SoulFire's release jar.
Each run replaces only the test client's options and comparison artifacts under `mod/build/lavapipe-test`.

## Read the results

The output directory contains:

| File | Contents |
| --- | --- |
| `lavapipe.png` | Vanilla Vulkan framebuffer rendered on the CPU |
| `software.png` | SoulFire's POV GUI rendering of the fixture |
| `diff.png` | Absolute RGB differences, amplified eight times |
| `comparison.png` | Lavapipe, SoulFire, and diff, from left to right |
| `metrics.json` | Changed pixels, pixels with channel error above two, maximum error, and mean absolute channel error |
| `device.txt` | Graphics backend, driver, and device details |

The task rejects an unexpected backend or driver, missing output, and a blank reference image.
It has a three-minute process timeout. Pixel differences are reported without a pass threshold because this test establishes a reference.

For startup or connection failures, inspect `mod/build/lavapipe-test/run/logs/latest.log`.
Lavapipe's device name contains `llvmpipe`; `backendName=Vulkan` distinguishes it from the OpenGL driver.

## Scope

The fixture covers flat items, block models, special models, font rendering, and item scissor clipping at GUI scale two.
Both renderers use the same item list and coordinates. Vanilla performs its own geometry submission, shader execution, and rasterization.
The software image is captured before asynchronous framebuffer readback completes, avoiding additional world ticks during that wait.

This is a GUI fixture comparison. It does not reproduce a previous server screenshot or validate full-world rendering, hands, locator markers, or every container.
It also does not measure production performance or prove that native rendering works with SoulFire's concurrent bot instances.
