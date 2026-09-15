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
The test holds both world clocks at 6000 ticks, clears rain and thunder, and fixes entity animation time and the fractional tick.
These client-only settings keep cloud positions and lighting stable while retaining clouds in the comparison.
After 60 frames, it captures the native framebuffer and runs the complete software renderer against the same live client state.

Open `mod/build/lavapipe-test/output/inventory/comparison.png`.
The original PNG guides the fixture layout. The new native framebuffer is the reference for pixel comparisons.
The server's world, spawn location, time, weather, and the client's default skin can differ from the original screenshot.

`scene.json` records the camera position, rotation, FOV, world time, GUI scale, and render distances.
The software renderer uses the native camera's position and FOV. Its distance limit is 32 blocks; the native render distance is two chunks.
Those distance settings do not guarantee identical chunk culling at the scene boundary.

## Compare the stress scenes

The four stress views share a hardcoded stage on the client. Use a flat Overworld server with a view distance of at least three chunks.
The stage replaces nearby blocks on the client and adds an entity gallery. It does not modify the server's world.

Run one view:

```sh
./gradlew :mod:runLavapipeTest -PlavapipeScene=stress-wide
```

Run all four views in sequence:

```sh
for scene in stress-wide stress-transparency stress-entities stress-hud; do
  ./gradlew :mod:runLavapipeTest -PlavapipeScene="$scene" || break
done
```

| Scene | Camera and main features |
| --- | --- |
| `stress-wide` | Elevated view of the entity gallery, block models, beacon, portals, displays, particles, and HUD |
| `stress-transparency` | View through water, glass, stained glass, ice, slime, honey, leaves, and a portal |
| `stress-entities` | Close view of armor poses, enchanted equipment, glow, invisibility, passengers, leashes, and a guardian beam |
| `stress-hud` | Underwater view at sunset with rain, fire, freezing, pumpkin overlay, title, subtitle, effects, and HUD |

The gallery creates every registered entity type with a client factory. A remote player and an owned fishing hook cover the types that need special construction.
The stage also adds 20 posed armor stands, eight leashed sheep, a passenger stack, and block, item, and text displays.
Display transforms include nonuniform and mirrored scales. Text displays use all four billboard modes.
Particles include smoke, flame, portals, explosions, dust, block fragments, and items.

Each `scene.json` lists the actual entity, block, and particle counts. It also lists entity types without a usable factory.
These counts describe stage contents, not visibility. Dense geometry hides some objects; inspect the native image before claiming coverage of a feature.
The fixture does not cover every entity variant, container screen, display interpolation phase, or combination of effects.

The test fixes the camera, world clocks, entity ticks, particle generators, lightning seed, and light intensity.
Texture atlas animations stay on their first frame. Glint remains visible with its motion stopped.
The test freezes simulation after construction and uses the same state for both renderers.
The software distance uses the client's effective render distance, including the server's limit.
Chunk boundary culling can still differ between renderers.

These scenes are exploratory: pixel differences do not fail the task. A successful run means that both images and metrics were produced.
The original `items` and `inventory` scenes still fail on any RGB pixel difference.

## Read the results

Each scene writes its outputs under `mod/build/lavapipe-test/output/<scene>`:

| File | Contents |
| --- | --- |
| `lavapipe.png` | Vanilla Vulkan framebuffer rendered on the CPU |
| `software.png` | SoulFire's rendering of the fixture |
| `diff.png` | Absolute RGB differences, amplified eight times |
| `comparison.png` | Lavapipe, SoulFire, and diff, from left to right |
| `metrics.json` | Changed pixels, pixels with channel error above two, maximum error, and mean absolute channel error |
| `device.txt` | Graphics backend, driver, and device details |
| `scene.json` | Camera metadata and stress-stage coverage counts |
| `software-trace.json` | World scene software renderer diagnostics |

Inventory mode also reports errors inside the inventory rectangle and outside it. These regions help separate UI differences from the world and HUD.

The task rejects an unexpected backend or driver, missing output, and a blank reference image.
It has a three-minute process timeout. Stress scenes report differences; the item and inventory baselines require exact RGB parity.

For startup or connection failures, inspect `mod/build/lavapipe-test/run/logs/latest.log`.
Lavapipe's device name contains `llvmpipe`; `backendName=Vulkan` distinguishes it from the OpenGL driver.

## Scope

The default `items` fixture covers flat items, block models, special models, font rendering, and item scissor clipping at GUI scale two.
Both renderers use the same item list and coordinates. Vanilla performs its own geometry submission, shader execution, and rasterization.
The software image is captured before asynchronous framebuffer readback completes, avoiding additional world ticks during that wait.

The `inventory` fixture includes the world, nearby player, held items, inventory preview, and HUD. It requires exact RGB pixel parity.
The stress fixtures add locator markers, but these tests do not cover every container.
It also does not measure production performance or prove that native rendering works with SoulFire's concurrent bot instances.
