# Compare headless Vulkan rendering with vanilla

SoulFire uses the Minecraft Vulkan backend for POV captures and inventory images. Release JARs bundle Mesa lavapipe for CPU rendering and can also use installed GPU drivers. See [native runtime packaging](vulkan-runtime.md).
OpenGL is disabled. Production captures use offscreen textures, with no native window or presentation surface.
Device selection prefers discrete, integrated, and virtual GPUs before CPU devices. Minecraft still checks each candidate for required features and driver compatibility.

The process shares one Vulkan device and caches one bot scene. Captures run on demand, on the owning bot thread.
Switching bots releases the previous scene resources. Bots retain their simulation state while another bot owns the cached scene.
Device access is serialized, including bot ticks that can upload textures. Alternating between bots requires rebuilding their visible geometry.
Inventory endpoints return transparent PNG snapshots. Animated GIF generation from the former CPU backend is no longer available.

The manual tests compare a windowed vanilla framebuffer with a separate headless capture of the same deterministic fixture.
These tests are opt-in. The `test`, `check`, and `build` tasks do not start them.

## Requirements

- Java 25 and the repository Gradle wrapper.
- A Vulkan loader and Mesa lavapipe.
- An X11 or Wayland display for the reference capture, or Xvfb.
- An isolated Minecraft 26.2 server with an accepted Minecraft EULA.
- Server configuration: `online-mode=false`, `enforce-secure-profile=false`, and a view distance of at least three chunks.
- A free player slot for `LavapipeTest`.

Run the comparisons without other clients joining or leaving. Server chat messages can change the HUD fixture between captures.

The fixtures change blocks, entities, equipment, and dimensions on the client. They do not modify the server world.
The tests use a separate client directory under `mod/build/lavapipe-test/run`. Manual-test classes are absent from release jars.

## Run a comparison

1. Start the local server.
2. Run the comparison script:

   ```sh
   python3 scripts/compare-renderer-scenes.py --scene items --server 127.0.0.1:25640
   ```

3. Open `mod/build/lavapipe-test/output/items/comparison.png`.

The script runs the windowed reference first, then the headless capture. It removes `DISPLAY` and `WAYLAND_DISPLAY` from the headless process.
The headless test also rejects any presentation surface.

For a machine without a display, run:

```sh
xvfb-run -a python3 scripts/compare-renderer-scenes.py --scene items
```

The default ICD path is `/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`.
To select another driver, pass `--icd /absolute/path/to/driver.json` to the script.
Both processes use the selected driver, and both must report the Vulkan backend.

To run the two captures separately, use:

```sh
./gradlew :mod:runLavapipeTest -PlavapipeScene=items
./gradlew :mod:runLavapipeTest -PlavapipeScene=items -PlavapipeHeadless=true
```

Each reference run replaces `lavapipe.png`. The headless run preserves that file and replaces `headless.png` and the comparison outputs.
Without a saved reference, the headless task writes a capture only. The comparison script requires both images and comparison metrics.

## Select scenes

List the scene catalog:

```sh
python3 scripts/compare-renderer-scenes.py --list
```

Run selected scenes:

```sh
python3 scripts/compare-renderer-scenes.py --scene inventory --scene stress-wide --scene stress-hud
```

Run all scenes:

```sh
python3 scripts/compare-renderer-scenes.py
```

The catalog is `mod/src/lavapipeTest/resources/lavapipe-scenes.csv`.
It includes GUI items, inventory, entity galleries, transparency, HUD effects, dimensions, portals, fog, block entities, and particles.

The fixtures fix camera placement, world clocks, fractional ticks, and animation state.
Rain fog uses its settled weather value in both processes, since offscreen captures have no continuous frame loop.
Both test processes use submission order for unordered vanilla feature batches. This removes differences from JVM object identity hashes. Texture atlas animations remain on their first frame.
Each `scene.json` records the scene configuration and available coverage counts.
These counts describe stage contents. Objects can obscure other objects, so a count alone does not prove visible coverage.

## Read the results

Each scene writes files under `mod/build/lavapipe-test/output/<scene>`:

| File | Contents |
| --- | --- |
| `lavapipe.png` | Windowed vanilla reference |
| `headless.png` | SoulFire offscreen Vulkan capture |
| `diff.png` | Amplified color differences |
| `comparison.png` | Reference, headless capture, and diff, from left to right |
| `metrics.json` | Changed pixels and channel errors |
| `device.txt` | Graphics backend, driver, and device details |
| `scene.json` | Camera configuration and fixture details |
| `vulkan-trace.json` | World capture duration and device details |

The comparison checks all RGBA channels. Any changed pixel fails the comparison.
Inventory comparisons also report errors inside and outside the inventory rectangle.
The script retains results after individual failures and writes `mod/build/lavapipe-test/output/suite.json`.

For startup or connection errors, inspect the scene log in `mod/build/lavapipe-test/output`.
The client has a three-minute timeout. A full server prevents the fixture from loading.

These fixtures validate rendering output. Production RPC concurrency, bot switching, resource cleanup, and memory use require separate integration checks.

## Benchmark headless captures

The benchmark uses the production capture path, including GPU completion and image readback into a `BufferedImage`.
Each scene has 30 warm-up captures and 60 measured captures at 854 by 480 pixels.
Startup, bot switching, PNG encoding, and network time are excluded. The first capture is reported separately.

Start the isolated server, then specify the driver manifests installed on your machine:

```sh
python3 scripts/benchmark-headless-renderer.py \
  --driver discrete,DISCRETE=/path/to/nvidia_icd.json \
  --driver integrated,INTEGRATED=/path/to/intel_icd.json \
  --driver lavapipe,CPU=/path/to/lvp_icd.json
```

Use `--scene stress-wide`, `--scene stress-transparency`, or `--scene stress-entities` to select a scene. The default runs all three.
Join manifest paths with your platform's path separator to test selection with multiple drivers visible.
The benchmark fails if the selected device type differs from the requested type.

The runner removes display environment variables. The client also rejects a presentation surface.
These benchmarks only run when requested. Normal tests and builds do not start them.

Results are saved under `mod/build/headless-benchmark/<driver>/<scene>`, with a combined `summary.json` in the parent directory.
Each result contains the actual device, driver details, cold capture time, median, mean, p95, and all measured samples.
The saved PNG lets you check that the benchmark rendered the expected scene.

Use `--historical /path/to/benchmark-summary.json` to include earlier CPU-renderer results in the report.
Historical values are copied from that file; the script does not rerun the removed renderer.
