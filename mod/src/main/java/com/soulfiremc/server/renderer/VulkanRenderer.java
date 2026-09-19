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

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.soulfiremc.mod.util.SFConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/// Serializes vanilla's shared Vulkan device and renders only explicit capture requests.
public final class VulkanRenderer {
  public static final ReentrantLock DEVICE_LOCK = new ReentrantLock(true);
  private static VulkanRenderSession session;
  private static long lastTextureTickNanos = System.nanoTime();
  private static final ScopedValue<Options> REQUEST = ScopedValue.newInstance();
  private static final ScopedValue<Boolean> INTERACTIVE = ScopedValue.newInstance();
  private static final ScopedValue<Boolean> CAPTURE = ScopedValue.newInstance();

  private VulkanRenderer() {}

  private static void activate(Minecraft minecraft) {
    tickSharedTextures();
    if (session != null && session.minecraft() == minecraft) return;
    closeSession();
    session = new VulkanRenderSession(minecraft);
  }

  private static void closeSession() {
    if (session == null) return;
    try {
      session.close();
    } finally {
      session = null;
    }
  }

  private static void tickSharedTextures() {
    // The base client has no game loop. Its shared atlases advance only when an image is requested.
    if (SFConstants.BASE_MC_INSTANCE == null) return;
    var now = System.nanoTime();
    var elapsedTicks = (now - lastTextureTickNanos) / 50_000_000L;
    if (elapsedTicks <= 0) return;
    lastTextureTickNanos += elapsedTicks * 50_000_000L;
    for (var tick = 0L; tick < Math.min(elapsedTicks, 10); tick++) {
      SFConstants.BASE_MC_INSTANCE.getTextureManager().tick();
    }
  }

  public static void release(Minecraft minecraft) {
    DEVICE_LOCK.lock();
    try {
      if (session != null && session.minecraft() == minecraft) {
        closeSession();
      }
    } finally { DEVICE_LOCK.unlock(); }
  }

  public static boolean isCapturing() { return CAPTURE.orElse(false); }
  public static boolean includeHands() { return !REQUEST.isBound() || REQUEST.get().includeHands(); }
  public static boolean includeHud() { return !REQUEST.isBound() || REQUEST.get().includeHud(); }

  public static BufferedImage render(ClientLevel level, LocalPlayer player, int width, int height, double fov, int maxDistance) {
    return renderWithResult(level, player, Options.defaults(player, width, height, fov, maxDistance)).image();
  }

  public static BufferedImage render(ClientLevel level, LocalPlayer player, Vec3 eyePos, float yRot, float xRot,
                                     int width, int height, double fov, int maxDistance) {
    return renderWithResult(level, player, new Options(eyePos, yRot, xRot, width, height, fov, maxDistance, true, true, false)).image();
  }

  /// Draws one live frame without waiting for all chunk compilation to settle.
  public static RgbaFrame renderInteractive(Minecraft minecraft, int width, int height, LiveReadback readback) {
    if (!minecraft.isSameThread()) return onGameThread(minecraft, () -> renderInteractive(minecraft, width, height, readback));
    var options = Options.defaults(minecraft.player, width, height, minecraft.options.fov().get(),
      minecraft.options.getEffectiveRenderDistance() * 16);
    DEVICE_LOCK.lock();
    try {
      return ScopedValue.where(INTERACTIVE, true).where(CAPTURE, true).where(REQUEST, options)
        .call(() -> renderWorld(minecraft, options, readback::capture));
    } finally {
      DEVICE_LOCK.unlock();
    }
  }

  public static Result renderWithResult(ClientLevel level, LocalPlayer player, Options options) {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread()) {
      return onGameThread(minecraft, () -> renderWithResult(level, player, options));
    }
    if (minecraft.level != level || minecraft.player != player) throw new IllegalArgumentException("Capture must run in the owning bot context");
    DEVICE_LOCK.lock();
    try {
      var start = System.nanoTime();
      return ScopedValue.where(CAPTURE, true).where(REQUEST, options).call(() ->
        renderWorld(minecraft, options, target -> new Result(readback(target, true), options.forceDebugTrace()
          ? new Trace(System.nanoTime() - start, RenderSystem.getDevice().getDeviceInfo().toString()) : null)));
    } finally {
      DEVICE_LOCK.unlock();
    }
  }

  private static <T> T renderWorld(Minecraft minecraft, Options options, Function<RenderTarget, T> capture) {
    activate(minecraft);
    var window = minecraft.getWindow();
    var oldWidth = window.getWidth();
    var oldHeight = window.getHeight();
    var oldDistance = minecraft.options.renderDistance().get();
    var renderer = minecraft.gameRenderer;
    var delta = minecraft.getDeltaTracker() == null ? DeltaTracker.ONE : minecraft.getDeltaTracker();
    try {
      resize(minecraft, options.width(), options.height());
      minecraft.options.renderDistance().set(Math.max(2, (options.maxDistance() + 15) / 16));
      renderer.mainCamera().setLevel(minecraft.level);
      renderer.mainCamera().setEntity(minecraft.player);
      minecraft.level.update();
      if (!INTERACTIVE.orElse(false)) renderer.mainCamera().attributeProbe().tick(minecraft.level, options.eyePos());
      renderer.lightmapRenderStateExtractor.needsUpdate = true;
      // Finish visibility updates and all requested geometry before returning an image.
      var deadline = System.nanoTime() + 30_000_000_000L;
      var settled = 0;
      do {
        renderer.update(delta);
        renderer.extract(delta, true);
        renderer.gameRenderState().optionsRenderState.chunkSectionFadeInTime = 0;
        RenderSystem.executePendingTasks();
        renderer.render();
        finishFrame(minecraft);
        if (INTERACTIVE.orElse(false)) {
          break;
        }
        var graph = minecraft.levelRenderer.sectionOcclusionGraph();
        var graphReady = (graph.fullUpdateTask == null || graph.fullUpdateTask.isDone())
          && !graph.needsFullUpdate && !graph.needsFrustumUpdate.get();
        var tracker = minecraft.levelExtractor.sectionUpdateTracker;
        var meshesReady = minecraft.levelRenderer.visibleSections().stream().allMatch(section ->
          section.sectionMesh.get() != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED
            || tracker == null || !tracker.hasAllNeighbors(minecraft.level, section.getSectionNode()));
        settled = graphReady && meshesReady && minecraft.levelRenderer.hasRenderedAllSections() ? settled + 1 : 0;
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("Timed out compiling the requested POV view: graphReady=" + graphReady
            + ", meshesReady=" + meshesReady + ", sectionsReady=" + minecraft.levelRenderer.hasRenderedAllSections()
            + ", visibleSections=" + minecraft.levelRenderer.visibleSections().size());
        }
      } while (settled < 2);
      return capture.apply(renderer.mainRenderTarget());
    } finally {
      minecraft.options.renderDistance().set(oldDistance);
      if (!INTERACTIVE.orElse(false)) resize(minecraft, oldWidth, oldHeight);
    }
  }

  /// Applies RPC camera overrides without moving or rotating the bot entity.
  public static void configureCamera(net.minecraft.client.Camera camera) {
    // Live gameplay must retain vanilla position, eye-height and sprint FOV interpolation.
    if (!REQUEST.isBound() || INTERACTIVE.orElse(false)) return;
    var options = REQUEST.get();
    camera.setPosition(options.eyePos().x, options.eyePos().y, options.eyePos().z);
    camera.setRotation(options.yRot(), options.xRot());
    camera.fov = (float) options.fov();
    camera.setupPerspective(0.05F, camera.depthFar, (float) options.fov(), options.width(), options.height());
    camera.prepareCullFrustum(camera.getViewRotationMatrix(new Matrix4f()), camera.createProjectionMatrixForCulling(), camera.position());
  }

  public static BufferedImage renderGui(Minecraft minecraft, int width, int height, int scale, Consumer<GuiGraphicsExtractor> draw) {
    if (!minecraft.isSameThread()) {
      return onGameThread(minecraft, () -> renderGui(minecraft, width, height, scale, draw));
    }
    DEVICE_LOCK.lock();
    try {
      return ScopedValue.where(CAPTURE, true).call(() -> {
        activate(minecraft);
        var window = minecraft.getWindow();
        var oldWidth = window.getWidth();
        var oldHeight = window.getHeight();
        var oldScale = window.getGuiScale();
        try {
          resize(minecraft, width, height);
          window.setGuiScale(scale);
          var renderer = minecraft.gameRenderer;
          renderer.extractWindow();
          renderer.extractOptions();
          var state = renderer.gameRenderState().guiRenderState;
          state.reset();
          state.clearColorOverride.zero();
          draw.accept(new GuiGraphicsExtractor(minecraft, state, 0, 0));
          renderer.render();
          finishFrame(minecraft);
          return readback(renderer.mainRenderTarget(), false);
        } finally {
          resize(minecraft, oldWidth, oldHeight);
          window.setGuiScale(oldScale);
        }
      });
    } finally {
      DEVICE_LOCK.unlock();
    }
  }

  private static <T> T onGameThread(Minecraft minecraft, Supplier<T> capture) {
    if (DEVICE_LOCK.isHeldByCurrentThread()) {
      throw new IllegalStateException("Cannot wait for another bot while holding the Vulkan device lock");
    }
    var future = minecraft.submit(capture);
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      future.cancel(false);
      Thread.currentThread().interrupt();
      throw new CompletionException("Interrupted while waiting for a render capture", e);
    } catch (TimeoutException e) {
      future.cancel(false);
      throw new CompletionException("The bot did not complete its render capture within 60 seconds", e);
    } catch (ExecutionException e) {
      throw new CompletionException(e.getCause());
    }
  }

  public static void resize(Minecraft minecraft, int width, int height) {
    if (width < 1 || height < 1) throw new IllegalArgumentException("Image dimensions must be positive");
    var window = minecraft.getWindow();
    if (window.getWidth() == width && window.getHeight() == height) return;
    window.setWidth(width);
    window.setHeight(height);
    window.setGuiScale(window.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode()));
    var screen = minecraft.gui.screen();
    if (screen != null) screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
  }

  private static void finishFrame(Minecraft minecraft) {
    awaitDevice();
    RenderSystem.getDynamicUniforms().reset();
    minecraft.levelRenderer.endFrame();
  }

  public static void awaitDevice() {
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    try (var fence = encoder.createFence()) {
      encoder.submit();
      if (!fence.awaitCompletion(30_000_000_000L)) throw new IllegalStateException("Vulkan capture timed out");
    }
  }

  /// Mapped RGBA rows, bottom to top. Keep the mapping alive until encoding completes.
  public record RgbaFrame(GpuBufferSlice.MappedView mapping, int width, int height)
    implements AutoCloseable {
    public ByteBuffer pixels() { return mapping.data(); }
    @Override public void close() { mapping.close(); }
  }

  /// A stream owns its staging allocation. No per-frame heap array or RGBA copy.
  public static final class LiveReadback implements AutoCloseable {
    private final boolean pipelined;
    private final GpuBuffer[] buffers;
    private final GpuFence[] fences;
    private int next;
    private boolean primed;
    public LiveReadback() { this(Boolean.getBoolean("sf.pov.pipeline-readback")); }
    public LiveReadback(boolean pipelined) {
      this.pipelined = pipelined;
      buffers = new GpuBuffer[pipelined ? 2 : 1];
      fences = new GpuFence[buffers.length];
    }
    public boolean pipelined() { return pipelined; }
    public RgbaFrame capture(RenderTarget target) {
      var texture = Objects.requireNonNull(target.getColorTexture());
      var size = Math.multiplyExact(Math.multiplyExact(target.width, target.height), 4);
      if (buffers[0] == null || buffers[0].size() != size) {
        close();
        for (var i = 0; i < buffers.length; i++) buffers[i] = RenderSystem.getDevice().createBuffer(
          () -> "SoulFire live video", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
      }
      var current = next;
      var encoder = RenderSystem.getDevice().createCommandEncoder();
      encoder.copyTextureToBuffer(texture, buffers[current], 0, () -> {}, 0);
      fences[current] = encoder.createFence();
      encoder.submit();
      next = (next + 1) % buffers.length;
      if (pipelined && !primed) { primed = true; return null; }
      var ready = pipelined ? next : current;
      if (!fences[ready].awaitCompletion(30_000_000_000L)) throw new IllegalStateException("POV readback timed out");
      fences[ready].close();
      fences[ready] = null;
      return new RgbaFrame(buffers[ready].map(true, false), target.width, target.height);
    }
    @Override public void close() {
      for (var i = 0; i < buffers.length; i++) {
        if (fences[i] != null) {
          if (!fences[i].awaitCompletion(30_000_000_000L)) throw new IllegalStateException("POV readback cleanup timed out");
          fences[i].close(); fences[i] = null;
        }
        if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; }
      }
      primed = false; next = 0;
    }
  }

  private static BufferedImage readback(RenderTarget target, boolean opaque) {
    var texture = Objects.requireNonNull(target.getColorTexture());
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    try (var buffer = RenderSystem.getDevice().createBuffer(() -> "SoulFire capture",
      GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) target.width * target.height * 4)) {
      encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
      awaitDevice();
      var image = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB);
      try (var mapped = buffer.map(true, false)) {
        var pixels = mapped.data();
        for (var y = 0; y < target.height; y++) {
          for (var x = 0; x < target.width; x++) {
            var abgr = pixels.getInt((y * target.width + x) * 4);
            var argb = (abgr & 0xFF00FF00) | (abgr & 255) << 16 | (abgr >>> 16 & 255);
            image.setRGB(x, target.height - y - 1, opaque ? argb | 0xFF000000 : argb);
          }
        }
      }
      return image;
    }
  }

  public record Trace(long totalNanos, String device) {}
  public record Result(BufferedImage image, @Nullable Trace debugTrace) {}
  public record Options(
    Vec3 eyePos,
    float yRot,
    float xRot,
    int width,
    int height,
    double fov,
    int maxDistance,
    boolean includeHands,
    boolean includeHud,
    boolean forceDebugTrace
  ) {
    public Options {
      Objects.requireNonNull(eyePos, "eyePos");
    }

    public static Options defaults(LocalPlayer player, int width, int height, double fov, int maxDistance) {
      return new Options(
        player.getEyePosition(),
        player.getYRot(),
        player.getXRot(),
        width,
        height,
        fov,
        maxDistance,
        true,
        true,
        false
      );
    }

    public Options withForceDebugTrace(boolean forceDebugTrace) {
      return new Options(eyePos, yRot, xRot, width, height, fov, maxDistance, includeHands, includeHud, forceDebugTrace);
    }
  }

}
