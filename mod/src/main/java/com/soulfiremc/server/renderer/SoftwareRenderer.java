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

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.Objects;

/// Software 3D renderer using CPU rasterization.
@UtilityClass
@Slf4j
public class SoftwareRenderer {
  private static final RasterPipeline RASTER_PIPELINE = new RasterPipeline();

  public static BufferedImage render(
    ClientLevel level,
    LocalPlayer player,
    int width,
    int height,
    double fov,
    int maxDistance
  ) {
    return renderWithResult(level, player, Options.defaults(player, width, height, fov, maxDistance)).image();
  }

  public static BufferedImage render(
    ClientLevel level,
    LocalPlayer localPlayer,
    Vec3 eyePos,
    float yRot,
    float xRot,
    int width,
    int height,
    double fov,
    int maxDistance
  ) {
    return renderWithResult(
      level,
      localPlayer,
      new Options(
        eyePos,
        yRot,
        xRot,
        width,
        height,
        fov,
        maxDistance,
        true,
        true,
        false
      )
    ).image();
  }

  public static Result renderWithResult(
    ClientLevel level,
    LocalPlayer localPlayer,
    Options options
  ) {
    var debugTrace = options.forceDebugTrace()
      ? RenderDebugTrace.createForced(options.width(), options.height(), options.maxDistance(), options.yRot(), options.xRot())
      : RenderDebugTrace.create(options.width(), options.height(), options.maxDistance(), options.yRot(), options.xRot());
    return debugTrace.call(() -> {
      var renderStart = System.nanoTime();
      var camera = new Camera(
        options.eyePos(),
        options.yRot(),
        options.xRot(),
        options.width(),
        options.height(),
        options.fov(),
        Math.max(options.maxDistance() * 4.0F, Minecraft.getInstance().options.cloudRange().get() * 16.0F)
      );
      var ctx = RenderContext.create(level, localPlayer, camera, options.maxDistance());

      var dynamicCollectNanos = 0L;

      var worldCollectStart = System.nanoTime();
      var worldScene = WorldMeshCollector.collect(ctx);
      debugTrace.worldCollectNanos(System.nanoTime() - worldCollectStart);

      var dynamicCollectStart = System.nanoTime();
      var dynamicScene = SceneCollector.collectDynamicScene(ctx, localPlayer);
      dynamicCollectNanos += System.nanoTime() - dynamicCollectStart;

      var cloudCollectStart = System.nanoTime();
      var cloudScene = CloudMeshCollector.collect(ctx);
      dynamicCollectNanos += System.nanoTime() - cloudCollectStart;
      debugTrace.dynamicCollectNanos(dynamicCollectNanos);

      var sceneData = worldScene.merge(dynamicScene).merge(cloudScene);
      var buffers = new RasterBuffers(options.width(), options.height());

      var rasterStart = System.nanoTime();
      RASTER_PIPELINE.render(ctx, sceneData, buffers);
      renderOverlays(ctx, options, sceneData, buffers);
      debugTrace.rasterNanos(System.nanoTime() - rasterStart);
      debugTrace.totalNanos(System.nanoTime() - renderStart);
      debugTrace.logSummary(sceneData);

      return new Result(buffers.opaqueImage(), debugTrace.snapshot());
    });
  }

  static void renderOverlays(
    RenderContext ctx,
    Options options,
    SceneData sceneData,
    RasterBuffers buffers
  ) {
    if (options.includeHands()) {
      var partialTick = partialTick();
      var handScene = VanillaSubmitCollector.collectHandsWithItems(ctx, partialTick);
      if (handScene.totalQuadCount() > 0) {
        var handCamera = new Camera(
          Vec3.ZERO,
          options.yRot(),
          options.xRot(),
          options.width(),
          options.height(),
          hudFov(options.fov()),
          100.0F
        );
        RASTER_PIPELINE.renderFirstPersonOverlay(handCamera, handScene, buffers, ctx.interpolatedGameTime(), RasterFogState.from(ctx));
      }
    }

    if (options.includeHands()) {
      var effects = VanillaSubmitCollector.collectScreenEffects(ctx, partialTick());
      var screenCamera = new Camera(Vec3.ZERO, 180, 0, options.width(), options.height(), hudFov(options.fov()), 100);
      RASTER_PIPELINE.renderScene(screenCamera, effects, buffers, ctx.interpolatedGameTime(), RasterFogState.from(ctx));
    }

    RASTER_PIPELINE.renderOutlines(ctx.camera(), sceneData.outlines(), buffers, ctx.interpolatedGameTime());

    if (options.includeHud()) {
      PovHudRenderer.render(ctx, buffers);
    }
  }

  private static float partialTick() {
    try {
      var deltaTracker = Minecraft.getInstance().getDeltaTracker();
      return deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(false) : 1.0F;
    } catch (Throwable _) {
      return 1.0F;
    }
  }

  private static double hudFov(double fallback) {
    try {
      var cameraState = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
      return cameraState != null && Float.isFinite(cameraState.hudFov) && cameraState.hudFov > 0.0F ? cameraState.hudFov : fallback;
    } catch (Throwable _) {
      return fallback;
    }
  }

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

  public record Result(BufferedImage image, RenderDebugTrace.Snapshot debugTrace) {}
}
