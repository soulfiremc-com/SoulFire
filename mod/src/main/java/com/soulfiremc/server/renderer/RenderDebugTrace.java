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

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public final class RenderDebugTrace {
  private static final ThreadLocal<RenderDebugTrace> CURRENT = new ThreadLocal<>();
  private static final AtomicLong NEXT_ID = new AtomicLong(1L);
  private static final int SAMPLE_LIMIT = 16;
  private static final int STACKTRACE_SAMPLE_LIMIT = 8;
  private static final RenderDebugTrace DISABLED = new RenderDebugTrace(false, 0L, 0, 0, 0, 0.0F, 0.0F);

  private final boolean enabled;
  private final long renderId;
  private final int width;
  private final int height;
  private final int maxDistance;
  private final float yRot;
  private final float xRot;
  private final LongAdder chunksConsidered = new LongAdder();
  private final LongAdder chunksLoaded = new LongAdder();
  private final LongAdder sectionsVisible = new LongAdder();
  private final LongAdder sectionsMeshed = new LongAdder();
  private final LongAdder sectionCacheHits = new LongAdder();
  private final LongAdder sectionCacheMisses = new LongAdder();
  private final LongAdder blockQuads = new LongAdder();
  private final LongAdder fluidTopQuads = new LongAdder();
  private final LongAdder fluidBottomQuads = new LongAdder();
  private final LongAdder fluidSideQuads = new LongAdder();
  private final LongAdder entitiesConsidered = new LongAdder();
  private final LongAdder entitiesVisible = new LongAdder();
  private final LongAdder billboards = new LongAdder();
  private final LongAdder shadows = new LongAdder();
  private final LongAdder weatherBillboards = new LongAdder();
  private final LongAdder vanillaBlockGeometryHits = new LongAdder();
  private final LongAdder vanillaBlockGeometryFallbacks = new LongAdder();
  private final LongAdder vanillaLivingModelHits = new LongAdder();
  private final LongAdder vanillaLivingModelFallbacks = new LongAdder();
  private final LongAdder vanillaPlayerModelHits = new LongAdder();
  private final LongAdder vanillaPlayerModelFallbacks = new LongAdder();
  private final LongAdder entityTextureFallbacks = new LongAdder();
  private final LongAdder opaqueTriangles = new LongAdder();
  private final LongAdder cutoutTriangles = new LongAdder();
  private final LongAdder translucentTriangles = new LongAdder();
  private final AtomicInteger sampleCount = new AtomicInteger();
  private final AtomicInteger stacktraceSampleCount = new AtomicInteger();
  private final ConcurrentLinkedQueue<String> notableEvents = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> detailedFailures = new ConcurrentLinkedQueue<>();
  private volatile long worldCollectNanos;
  private volatile long dynamicCollectNanos;
  private volatile long rasterNanos;
  private volatile long totalNanos;

  private RenderDebugTrace(boolean enabled, long renderId, int width, int height, int maxDistance, float yRot, float xRot) {
    this.enabled = enabled;
    this.renderId = renderId;
    this.width = width;
    this.height = height;
    this.maxDistance = maxDistance;
    this.yRot = yRot;
    this.xRot = xRot;
  }

  public static RenderDebugTrace create(int width, int height, int maxDistance, float yRot, float xRot) {
    if (!isEnabled()) {
      return DISABLED;
    }
    return new RenderDebugTrace(true, NEXT_ID.getAndIncrement(), width, height, maxDistance, yRot, xRot);
  }

  public static boolean isEnabled() {
    return Boolean.getBoolean("sf.renderer.debug")
      || "true".equalsIgnoreCase(System.getenv("SF_RENDERER_DEBUG"));
  }

  public static void bind(RenderDebugTrace trace) {
    if (trace.enabled) {
      CURRENT.set(trace);
    }
  }

  public static void unbind() {
    CURRENT.remove();
  }

  public static RenderDebugTrace current() {
    var trace = CURRENT.get();
    return trace != null ? trace : DISABLED;
  }

  public void chunkConsidered() {
    if (enabled) {
      chunksConsidered.increment();
    }
  }

  public void chunkLoaded() {
    if (enabled) {
      chunksLoaded.increment();
    }
  }

  public void sectionVisible() {
    if (enabled) {
      sectionsVisible.increment();
    }
  }

  public void sectionMeshed() {
    if (enabled) {
      sectionsMeshed.increment();
    }
  }

  public void sectionCacheHit() {
    if (enabled) {
      sectionCacheHits.increment();
    }
  }

  public void sectionCacheMiss() {
    if (enabled) {
      sectionCacheMisses.increment();
    }
  }

  public void blockQuads(long count) {
    if (enabled) {
      blockQuads.add(count);
    }
  }

  public void fluidTopQuad() {
    if (enabled) {
      fluidTopQuads.increment();
    }
  }

  public void fluidBottomQuad() {
    if (enabled) {
      fluidBottomQuads.increment();
    }
  }

  public void fluidSideQuad() {
    if (enabled) {
      fluidSideQuads.increment();
    }
  }

  public void entityConsidered() {
    if (enabled) {
      entitiesConsidered.increment();
    }
  }

  public void entityVisible() {
    if (enabled) {
      entitiesVisible.increment();
    }
  }

  public void billboard() {
    if (enabled) {
      billboards.increment();
    }
  }

  public void shadow() {
    if (enabled) {
      shadows.increment();
    }
  }

  public void weatherBillboard() {
    if (enabled) {
      weatherBillboards.increment();
    }
  }

  public void vanillaBlockGeometryHit() {
    if (enabled) {
      vanillaBlockGeometryHits.increment();
    }
  }

  public void vanillaBlockGeometryFallback(String blockId) {
    if (!enabled) {
      return;
    }
    vanillaBlockGeometryFallbacks.increment();
    note("block-fallback:" + blockId);
  }

  public void vanillaBlockGeometryFallback(String blockId, Throwable throwable) {
    vanillaBlockGeometryFallback(blockId);
    note("block-fallback-reason:" + blockId + ":" + throwable.getClass().getSimpleName());
    noteFailure("block-fallback", blockId, throwable);
  }

  public void vanillaLivingModelHit(String entityType) {
    if (!enabled) {
      return;
    }
    vanillaLivingModelHits.increment();
    note("living-model:" + entityType);
  }

  public void vanillaLivingModelFallback(String entityType) {
    if (!enabled) {
      return;
    }
    vanillaLivingModelFallbacks.increment();
    note("living-fallback:" + entityType);
  }

  public void vanillaLivingModelFallback(String entityType, Throwable throwable) {
    vanillaLivingModelFallback(entityType);
    note("living-fallback-reason:" + entityType + ":" + throwable.getClass().getSimpleName());
    noteFailure("living-fallback", entityType, throwable);
  }

  public void vanillaPlayerModelHit(String playerId) {
    if (!enabled) {
      return;
    }
    vanillaPlayerModelHits.increment();
    note("player-model:" + playerId);
  }

  public void vanillaPlayerModelFallback(String playerId) {
    if (!enabled) {
      return;
    }
    vanillaPlayerModelFallbacks.increment();
    note("player-fallback:" + playerId);
  }

  public void vanillaPlayerModelFallback(String playerId, Throwable throwable) {
    vanillaPlayerModelFallback(playerId);
    note("player-fallback-reason:" + playerId + ":" + throwable.getClass().getSimpleName());
    noteFailure("player-fallback", playerId, throwable);
  }

  public void entityTextureFallback(String entityType) {
    if (!enabled) {
      return;
    }
    entityTextureFallbacks.increment();
    note("entity-texture-fallback:" + entityType);
  }

  public void entityTextureFallback(String entityType, Throwable throwable) {
    entityTextureFallback(entityType);
    note("entity-texture-fallback-reason:" + entityType + ":" + throwable.getClass().getSimpleName());
    noteFailure("entity-texture-fallback", entityType, throwable);
  }

  public void missingTexture(String textureId) {
    if (enabled) {
      note("missing-texture:" + textureId);
    }
  }

  public void missingTexture(String textureId, Throwable throwable) {
    missingTexture(textureId);
    note("missing-texture-reason:" + textureId + ":" + throwable.getClass().getSimpleName());
    noteFailure("missing-texture", textureId, throwable);
  }

  public void opaqueTriangles(long count) {
    if (enabled) {
      opaqueTriangles.add(count);
    }
  }

  public void cutoutTriangles(long count) {
    if (enabled) {
      cutoutTriangles.add(count);
    }
  }

  public void translucentTriangles(long count) {
    if (enabled) {
      translucentTriangles.add(count);
    }
  }

  public void worldCollectNanos(long nanos) {
    if (enabled) {
      worldCollectNanos = nanos;
    }
  }

  public void dynamicCollectNanos(long nanos) {
    if (enabled) {
      dynamicCollectNanos = nanos;
    }
  }

  public void rasterNanos(long nanos) {
    if (enabled) {
      rasterNanos = nanos;
    }
  }

  public void totalNanos(long nanos) {
    if (enabled) {
      totalNanos = nanos;
    }
  }

  public void logSummary(SceneData sceneData) {
    if (!enabled) {
      return;
    }
    log.info(
      "renderer-debug#{} size={}x{} dist={} yaw={} pitch={} scene[opaque={},cutout={},translucent={}] world[chunks={},loaded={},sections={},meshed={},cacheHit={},cacheMiss={}] quads[block={},fluidTop={},fluidBottom={},fluidSide={},billboard={},shadow={},weather={}] entities[seen={},visible={}] vanilla[blockHit={},blockFallback={},livingHit={},livingFallback={},playerHit={},playerFallback={},entityTextureFallback={}] raster[opaqueTris={},cutoutTris={},translucentTris={}] timeMs[world={},dynamic={},raster={},total={}]",
      renderId,
      width,
      height,
      maxDistance,
      yRot,
      xRot,
      sceneData.opaque().length,
      sceneData.cutout().length,
      sceneData.translucent().length,
      chunksConsidered.sum(),
      chunksLoaded.sum(),
      sectionsVisible.sum(),
      sectionsMeshed.sum(),
      sectionCacheHits.sum(),
      sectionCacheMisses.sum(),
      blockQuads.sum(),
      fluidTopQuads.sum(),
      fluidBottomQuads.sum(),
      fluidSideQuads.sum(),
      billboards.sum(),
      shadows.sum(),
      weatherBillboards.sum(),
      entitiesConsidered.sum(),
      entitiesVisible.sum(),
      vanillaBlockGeometryHits.sum(),
      vanillaBlockGeometryFallbacks.sum(),
      vanillaLivingModelHits.sum(),
      vanillaLivingModelFallbacks.sum(),
      vanillaPlayerModelHits.sum(),
      vanillaPlayerModelFallbacks.sum(),
      entityTextureFallbacks.sum(),
      opaqueTriangles.sum(),
      cutoutTriangles.sum(),
      translucentTriangles.sum(),
      nanosToMillis(worldCollectNanos),
      nanosToMillis(dynamicCollectNanos),
      nanosToMillis(rasterNanos),
      nanosToMillis(totalNanos)
    );

    if (!notableEvents.isEmpty()) {
      log.info("renderer-debug#{} notable={}", renderId, new ArrayList<>(notableEvents));
    }
    if (!detailedFailures.isEmpty()) {
      log.info("renderer-debug#{} detailed-failures-start", renderId);
      for (var failure : detailedFailures) {
        log.info("renderer-debug#{} {}", renderId, failure);
      }
      log.info("renderer-debug#{} detailed-failures-end", renderId);
    }
  }

  private void note(String message) {
    if (sampleCount.incrementAndGet() <= SAMPLE_LIMIT) {
      notableEvents.add(message);
    }
  }

  private void noteFailure(String category, String subject, Throwable throwable) {
    if (stacktraceSampleCount.incrementAndGet() > STACKTRACE_SAMPLE_LIMIT) {
      return;
    }

    var builder = new StringBuilder();
    builder.append(category)
      .append(" subject=")
      .append(subject)
      .append(" exception=")
      .append(throwable.getClass().getName())
      .append(": ")
      .append(throwable.getMessage());
    for (var element : throwable.getStackTrace()) {
      builder.append("\n  at ").append(element);
      if (element.getClassName().startsWith("com.soulfiremc.server.renderer")
        || element.getClassName().startsWith("net.minecraft.client")
        || element.getClassName().startsWith("net.minecraft.world")) {
        // keep a useful slice, but avoid dumping the entire VM stack for every render
        if (builder.toString().split("\n").length >= 16) {
          break;
        }
      }
    }
    detailedFailures.add(builder.toString());
  }

  private long nanosToMillis(long nanos) {
    return nanos / 1_000_000L;
  }
}
