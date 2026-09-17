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

  private static void validateLiveCamera(Minecraft minecraft, Path output) throws IOException {
    var player = minecraft.player;
    var position = player.position();
    var pose = player.getPose();
    var sprinting = player.isSprinting();
    var fovEffects = minecraft.options.fovEffectScale().get();
    minecraft.options.fovEffectScale().set(1.0);
    player.setSprinting(false);
    var timer = minecraft.deltaTracker;
    var partial = new float[]{0};
    var samples = new ArrayList<Map<String, Object>>();
    minecraft.deltaTracker = new net.minecraft.client.DeltaTracker.Timer(20, 0, _ -> 50) {
      @Override public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) { return partial[0]; }
    };
    LavapipeComparison.validatingMotion = true;
    try (var readback = new VulkanRenderer.LiveReadback(false)) {
      var camera = minecraft.gameRenderer.mainCamera();
      camera.setLevel(minecraft.level);
      camera.setEntity(player);
      for (var i = 0; i < 20; i++) camera.tick();
      player.setOldPosAndRot();
      player.setPos(position.add(0.8, 0, 0));
      player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
      player.setSprinting(true);
      camera.tick();
      var firstX = Double.NaN;
      var firstFov = Float.NaN;
      for (var fraction : new float[]{0, 0.5F, 0.9F}) {
        partial[0] = fraction;
        camera.update(minecraft.deltaTracker);
        var expectedPosition = camera.position();
        var expectedFov = camera.getFov();
        try (var frame = VulkanRenderer.renderInteractive(minecraft, 854, 480, readback)) {
          if (frame == null || camera.position().distanceTo(expectedPosition) > 0.000001
            || Math.abs(camera.getFov() - expectedFov) > 0.000001) {
            throw new IllegalStateException("Live capture replaced vanilla camera interpolation at " + fraction);
          }
        }
        if (fraction == 0) {
          firstX = camera.position().x;
          firstFov = camera.getFov();
        } else {
          if (camera.position().x <= firstX) throw new IllegalStateException("Live camera did not interpolate movement");
          if (camera.getFov() <= firstFov) throw new IllegalStateException("Live camera did not interpolate sprint FOV");
        }
        samples.add(Map.of("partialTick", fraction, "position", camera.position(), "fov", camera.getFov()));
      }
      Files.writeString(output.resolve("live-camera.json"), new GsonBuilder().setPrettyPrinting().create().toJson(samples));
    } finally {
      LavapipeComparison.validatingMotion = false;
      minecraft.deltaTracker = timer;
      player.setPos(position);
      player.setOldPosAndRot();
      player.setPose(pose);
      player.setSprinting(sprinting);
      minecraft.options.fovEffectScale().set(fovEffects);
      for (var i = 0; i < 20; i++) minecraft.gameRenderer.mainCamera().tick();
    }
  }

  private static void benchmarkPov(Minecraft minecraft, Path output) throws IOException {
    validateLiveCamera(minecraft, output);
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
