/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.renderer;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/// Opt-in, paired frame latency measurements in the manual comparison client.
public final class RendererBenchmark {
  public static final boolean ENABLED = Boolean.getBoolean("sf.lavapipe.benchmark");
  private static final int WARMUP = 30;
  private static final int SAMPLES = 60;
  private static final ArrayList<Long> NATIVE = new ArrayList<>();
  private static final ArrayList<Long> SOFTWARE = new ArrayList<>();
  private static boolean active;
  private static int pairs;
  private static long nativeStart;
  private static long nativeElapsed;

  private RendererBenchmark() {}

  public static void beginNative() {
    if (!active) return;
    awaitDevice();
    nativeStart = System.nanoTime();
  }

  public static void endNative() {
    if (!active) return;
    awaitDevice();
    nativeElapsed = System.nanoTime() - nativeStart;
  }

  private static void awaitDevice() {
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    try (var fence = encoder.createFence()) {
      encoder.submit();
      if (!fence.awaitCompletion(10_000_000_000L)) {
        throw new IllegalStateException("Vulkan benchmark fence timed out");
      }
    }
  }

  public static boolean afterFrame(Minecraft minecraft, Path output, String scene, boolean isolated) {
    if (!active) {
      if (RenderDebugTrace.isEnabled()) throw new IllegalStateException("Disable renderer debug tracing for benchmarks");
      if (scene.equals("items")) throw new IllegalArgumentException("Benchmark requires a world scene");
      active = true;
      return false;
    }
    // Drain presentation work outside the timed region before running the CPU renderer.
    awaitDevice();
    var target = minecraft.gameRenderer.mainRenderTarget();
    var camera = minecraft.gameRenderer.mainCamera();
    var options = new SoftwareRenderer.Options(camera.position(), camera.yRot(), camera.xRot(),
      target.width, target.height, camera.getFov(), minecraft.options.getEffectiveRenderDistance() * 16,
      !isolated, !isolated, false);
    var start = System.nanoTime();
    var result = SoftwareRenderer.renderWithResult(minecraft.level, minecraft.player, options);
    var elapsed = System.nanoTime() - start;
    if (result.image().getWidth() != target.width) throw new IllegalStateException("Invalid benchmark image");
    if (pairs++ >= WARMUP) {
      NATIVE.add(nativeElapsed);
      SOFTWARE.add(elapsed);
    }
    if (pairs < WARMUP + SAMPLES) return false;
    active = false;
    var report = new LinkedHashMap<String, Object>();
    report.put("scene", scene);
    report.put("width", target.width);
    report.put("height", target.height);
    report.put("warmupPairs", WARMUP);
    report.put("samples", SAMPLES);
    report.put("device", RenderSystem.getDevice().getDeviceInfo().toString());
    report.put("java", System.getProperty("java.runtime.version"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("method", "Paired frozen frames; native extraction through rendering plus submit/fence completion; software extraction through BufferedImage; warm caches, no forced trace. Excludes startup, simulation, frame limiter, presentation, PNG encoding and native readback.");
    report.put("lavapipe", stats(NATIVE));
    report.put("software", stats(SOFTWARE));
    try {
      Files.createDirectories(output);
      Files.writeString(output.resolve("benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  private static Map<String, Object> stats(ArrayList<Long> values) {
    var sorted = values.stream().mapToDouble(n -> n / 1_000_000.0).sorted().toArray();
    return Map.of("medianMs", (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2,
      "meanMs", Arrays.stream(sorted).average().orElseThrow(),
      "p95Ms", sorted[(int) Math.ceil(sorted.length * 0.95) - 1],
      "samplesMs", values.stream().map(n -> n / 1_000_000.0).toList());
  }
}
