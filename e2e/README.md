# End-to-end scenarios

Runs Soulfire against a real vanilla Minecraft server: a void world in Docker, Soulfire started
from a jar, and one offline-mode bot. Each test builds its scene with server commands (RCON),
drives the bot through the TypeScript SDK, and checks the outcome on the server.

Use it to see what the game really does before writing a unit test, and to show that a fix works:
run the same test on a jar without the fix and on one with it.

## Running

Needs Docker, Bun, Node.js 24, and Java 25 (`java` on `PATH`, or `SOULFIRE_E2E_JAVA`).
Gradle builds the host [native runtime package](../docs/vulkan-runtime.md) before it packages the launcher JAR.

```bash
./gradlew :dedicated-launcher:uberJar          # the jar the tests run by default
bun install && bun run --filter @soulfiremc/sdk build
cd e2e
bun run test                                    # every test in tests/
bun run test breed-cows                         # only those named
SOULFIRE_E2E_JAR=/path/to/other.jar bun run test breed-cows
```

The first run downloads the server and generates the world. The container (`soulfire-e2e`, port 25566) keeps running between runs; `--fresh` replaces it, `--stop` removes it at the end. Each
run writes `soulfire.log` and `minecraft.log` to `runs/<time>/`.

## Writing a test

A test is a file in `tests/` that exports `{ name, run }`. `run` gets the bot and `rcon`, and
throws when a check fails. `walk-straight.ts` is the smallest example.

- The world has no terrain, time, weather, mobs or random ticks: only what tests build. Build
  your scene away from the other tests' coordinates, and clear it first.
- `placeBot` teleports the bot and waits until Soulfire has the chunks around it.
- Check the result on the server (`execute if block`, `data get entity`, `serverPosition`),
  not only what the bot reports.
