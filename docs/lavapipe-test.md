# Compare POV rendering with Lavapipe

This manual test runs Minecraft 26.2's Vulkan backend on Mesa Lavapipe, a CPU Vulkan driver.
It renders a shared fixture with vanilla and SoulFire's renderer, then saves a pixel diff.

The test uses a separate client process and working directory. SoulFire's headless lifecycle and LWJGL interception stay disabled in that process.
Texture mirror hooks supply font and texture pixels to the POV renderer without replacing native rendering.

## Run the test

Requirements:

- Java 25 and the repository's Gradle wrapper.
- Mesa Lavapipe and a Vulkan loader.
- An X11 or Wayland display. On a machine without a display, use Xvfb.
- A local Minecraft 26.2 server with `online-mode=false` and `enforce-secure-profile=false`.

The server supplies the world, item components, and registries. The inventory fixture changes equipment and adds a nearby player on the client only.
Use an isolated server with an accepted Minecraft EULA.

1. Start the local server.
2. Run the manual task:

   ```sh
   ./gradlew :mod:runLavapipeTest -PlavapipeServer=127.0.0.1:25640
   ```

   The task connects as `LavapipeTest`, captures the fixture, then closes the client.
   It does not start or stop the server.

3. Open `mod/build/lavapipe-test/output/items/comparison.png`.

The default Linux driver path is `/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`.
To select another installation, pass `-PlavapipeIcd=/absolute/path/to/lvp_icd.json`.

For Xvfb, run:

```sh
xvfb-run -a ./gradlew :mod:runLavapipeTest -PlavapipeServer=127.0.0.1:25640
```

The task is never invoked by `test`, `check`, or `build`. No manual-test classes are packaged in SoulFire's release jar.
Each run replaces only the test client's options and comparison artifacts under `mod/build/lavapipe-test`.

## Compare the inventory scene

Use a flat local server for a scene like the Alpha HUD screenshot:

```sh
./gradlew :mod:runLavapipeTest -PlavapipeScene=inventory -PlavapipeServer=127.0.0.1:25640
```

This mode equips a diamond helmet, elytra, sword, and totem. It fills the inventory, adds a nearby `ProbeB`, and opens the inventory screen.
It also adds the Alpha HUD boss bar and fixes the inventory preview's mouse coordinates.
After 60 frames, it captures the native framebuffer and runs the complete software renderer against the same live client state.

Open `mod/build/lavapipe-test/output/inventory/comparison.png`.
The original PNG guides the fixture layout. The new native framebuffer is the reference for pixel comparisons.
The server's world, spawn location, time, weather, and the client's default skin can differ from the original screenshot.

`scene.json` records the camera position, rotation, FOV, world time, GUI scale, and render distances.
The software renderer uses the native camera's position and FOV. Its distance limit is 32 blocks; the native render distance is two chunks.
Those distance settings do not guarantee identical chunk culling at the scene boundary.

## Read the results

Outputs are separated under `mod/build/lavapipe-test/output/items` and `mod/build/lavapipe-test/output/inventory`:

| File | Contents |
| --- | --- |
| `lavapipe.png` | Vanilla Vulkan framebuffer rendered on the CPU |
| `software.png` | SoulFire's rendering of the fixture |
| `diff.png` | Absolute RGB differences, amplified eight times |
| `comparison.png` | Lavapipe, SoulFire, and diff, from left to right |
| `metrics.json` | Changed pixels, pixels with channel error above two, maximum error, and mean absolute channel error |
| `device.txt` | Graphics backend, driver, and device details |
| `scene.json` | Inventory scene camera and world metadata |
| `software-trace.json` | Inventory scene software renderer diagnostics |

Inventory mode also reports errors inside the inventory rectangle and outside it. These regions help separate UI differences from the world and HUD.

The task rejects an unexpected backend or driver, missing output, and a blank reference image.
It has a three-minute process timeout. Pixel differences are reported without a pass threshold because this test establishes a reference.

For startup or connection failures, inspect `mod/build/lavapipe-test/run/logs/latest.log`.
Lavapipe's device name contains `llvmpipe`; `backendName=Vulkan` distinguishes it from the OpenGL driver.

## Scope

The default `items` fixture covers flat items, block models, special models, font rendering, and item scissor clipping at GUI scale two.
Both renderers use the same item list and coordinates. Vanilla performs its own geometry submission, shader execution, and rasterization.
The software image is captured before asynchronous framebuffer readback completes, avoiding additional world ticks during that wait.

The `inventory` fixture includes the world, nearby player, held items, inventory preview, and HUD. It exposes differences without asserting visual parity.
Neither fixture covers all locator markers or every container.
It also does not measure production performance or prove that native rendering works with SoulFire's concurrent bot instances.
