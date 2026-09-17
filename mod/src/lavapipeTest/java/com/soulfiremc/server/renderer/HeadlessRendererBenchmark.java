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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/// Opt-in benchmark of complete offscreen captures, including GPU completion and CPU readback.
final class HeadlessRendererBenchmark {
  private static final int WIDTH = 854;
  private static final int HEIGHT = 480;
  private static final int WARMUP = 30;
  private static final int SAMPLES = 60;

  private HeadlessRendererBenchmark() {}

  static void run(Minecraft minecraft, Path output, String scene, boolean isolated) throws IOException {
    if (!scene.startsWith("stress-")) throw new IllegalArgumentException("Benchmark requires a world fixture");
    if (minecraft.windowSurface() != null) throw new IllegalStateException("Benchmark must have no presentation surface");
    var device = RenderSystem.getDevice().getDeviceInfo();
    var expectedType = System.getProperty("sf.lavapipe.expectedDeviceType", "");
    if (!expectedType.isEmpty() && !device.type().name().equals(expectedType)) {
      throw new IllegalStateException("Expected " + expectedType + " but selected " + device);
    }
    if (Boolean.getBoolean("sf.lavapipe.povBenchmark")) benchmarkPov(minecraft, output);
    var camera = minecraft.gameRenderer.mainCamera();
    var options = new VulkanRenderer.Options(camera.position(), camera.yRot(), camera.xRot(), WIDTH, HEIGHT,
      camera.getFov(), minecraft.options.getEffectiveRenderDistance() * 16, !isolated, !isolated, false);
    var start = System.nanoTime();
    var image = VulkanRenderer.renderWithResult(minecraft.level, minecraft.player, options).image();
    var coldNanos = System.nanoTime() - start;
    validate(image);
    for (var index = 0; index < WARMUP; index++) {
      VulkanRenderer.renderWithResult(minecraft.level, minecraft.player, options);
    }
    var samples = new ArrayList<Double>(SAMPLES);
    for (var index = 0; index < SAMPLES; index++) {
      start = System.nanoTime();
      image = VulkanRenderer.renderWithResult(minecraft.level, minecraft.player, options).image();
      samples.add((System.nanoTime() - start) / 1_000_000.0);
    }
    validate(image);
    var sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    var report = new LinkedHashMap<String, Object>();
    report.put("scene", scene);
    report.put("width", WIDTH);
    report.put("height", HEIGHT);
    report.put("warmupCaptures", WARMUP);
    report.put("samples", SAMPLES);
    report.put("device", device.toString());
    report.put("deviceType", device.type().name());
    report.put("java", System.getProperty("java.runtime.version"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("headless", true);
    report.put("method", "Frozen world, production VulkanRenderer.renderWithResult through BufferedImage, including camera update, extraction, rendering, GPU fences and readback. Warm scene cache. Excludes startup, simulation, PNG encoding, network and switching bots.");
    report.put("coldCaptureMs", coldNanos / 1_000_000.0);
    report.put("capture", Map.of("medianMs", (sorted[SAMPLES / 2 - 1] + sorted[SAMPLES / 2]) / 2,
      "meanMs", Arrays.stream(sorted).average().orElseThrow(),
      "p95Ms", sorted[(int) Math.ceil(SAMPLES * 0.95) - 1], "samplesMs", samples));
    Files.writeString(output.resolve("headless-benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    ImageIO.write(image, "PNG", output.resolve("headless-benchmark.png").toFile());
    System.out.println("Headless benchmark " + scene + " on " + device.name() + ": median "
      + (sorted[SAMPLES / 2 - 1] + sorted[SAMPLES / 2]) / 2 + " ms");
  }

  private static void benchmarkPov(Minecraft minecraft, Path output) throws IOException {
    var report = new LinkedHashMap<String, Object>();
    for (var pipeline : new boolean[]{false, true}) {
      try (var readback = new VulkanRenderer.LiveReadback(pipeline);
           var encoder = new PovVideoEncoder(1280, 720, 60, PovVideoEncoder.Format.HIGH)) {
        var times = new ArrayList<Double>();
        var totalBytes = 0L;
        for (var i = 0; i < 90; i++) {
          var start = System.nanoTime();
          try (var frame = VulkanRenderer.renderInteractive(minecraft, 1280, 720, readback)) {
            if (frame == null) continue;
            var encoded = encoder.encode(frame.pixels(), i * 16_667L, i == 0);
            if (i >= 30) { times.add((System.nanoTime() - start) / 1_000_000.0); totalBytes += encoded.data().length; }
          }
        }
        var sorted = times.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        report.put(pipeline ? "pipelined" : "immediate", Map.of("medianMs", sorted[sorted.length / 2],
          "p95Ms", sorted[(int) (sorted.length * 0.95)], "encodedBytes", totalBytes, "encoder", encoder.name(),
          "samples", times, "extraBufferedFrames", pipeline ? 1 : 0));
      }
    }
    Files.writeString(output.resolve("pov-benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
  }

  private static void validate(BufferedImage image) {
    if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) throw new IllegalStateException("Unexpected capture size");
    var colors = new HashSet<Integer>();
    for (var y = 0; y < HEIGHT && colors.size() < 32; y++) {
      for (var x = 0; x < WIDTH && colors.size() < 32; x++) colors.add(image.getRGB(x, y));
    }
    if (colors.size() < 32) throw new IllegalStateException("Benchmark produced an empty scene");
  }
}
