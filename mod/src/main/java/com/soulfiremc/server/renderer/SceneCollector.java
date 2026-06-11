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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/// Collects dynamic scene primitives that are not part of the chunk-section world mesh.
@UtilityClass
public class SceneCollector {
  private static final RendererAssets.TextureImage RAIN_TEXTURE = createRainTexture();

  public static SceneData collectEntitiesAndWeather(RenderContext ctx, LocalPlayer localPlayer) {
    var level = ctx.level();
    var builder = SceneData.builder();
    var billboardBuckets = new HashMap<Long, Integer>();
    var trace = RenderDebugTrace.current();
    var options = Minecraft.getInstance().options;
    Entity.setViewScale(Mth.clamp(options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * options.entityDistanceScaling().get());

    VanillaSubmitCollector.prepareEntityDispatcher(ctx, localPlayer);
    try {
      for (var entity : level.entitiesForRendering()) {
        if (entity == localPlayer) {
          continue;
        }
        trace.entityConsidered();

        var dx = entity.getX() - ctx.camera().eyeX();
        var dy = entity.getY() - ctx.camera().eyeY();
        var dz = entity.getZ() - ctx.camera().eyeZ();
        var distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > ctx.maxDistanceSq()) {
          continue;
        }

        if (!VanillaSubmitCollector.shouldRenderEntity(ctx, entity)
          && (localPlayer == null || !entity.hasIndirectPassenger(localPlayer))) {
          continue;
        }
        trace.entityVisible();

        collectGenericEntity(ctx, entity, builder);
      }
    } finally {
      VanillaSubmitCollector.resetEntityDispatcher();
    }

    builder.addAll(VanillaSubmitCollector.collectParticles(ctx));
    collectWeather(level, ctx, builder, billboardBuckets);
    return builder.build();
  }

  private static void collectGenericEntity(RenderContext ctx, Entity entity, SceneData.Builder builder) {
    var renderState = VanillaSubmitCollector.extractEntityState(entity);
    if (renderState != null && renderState.isInvisible && !renderState.appearsGlowing()) {
      return;
    }

    var vanillaScene = VanillaSubmitCollector.collectEntity(ctx, entity, renderState);
    if (vanillaScene.totalQuadCount() > 0) {
      builder.addAll(vanillaScene);
    }
  }

  public static SceneData collectBlockEntities(RenderContext ctx) {
    var builder = SceneData.builder();
    var level = ctx.level();
    var camera = ctx.camera();
    var seen = new HashSet<BlockPos>();

    for (var blockEntity : level.getGloballyRenderedBlockEntities()) {
      collectBlockEntity(ctx, builder, seen, blockEntity);
    }

    var chunkRadius = Mth.ceil(ctx.maxDistance() / 16.0) + 1;
    var centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(camera.eyeX()));
    var centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(camera.eyeZ()));
    var sectionMargin = 16.0;
    var probeY = Mth.floor(camera.eyeY());
    for (var chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
      for (var chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
        var chunkCenterX = chunkX * 16.0 + 8.0;
        var chunkCenterZ = chunkZ * 16.0 + 8.0;
        var dx = chunkCenterX - camera.eyeX();
        var dz = chunkCenterZ - camera.eyeZ();
        if (dx * dx + dz * dz > (ctx.maxDistance() + sectionMargin) * (ctx.maxDistance() + sectionMargin)) {
          continue;
        }
        if (!level.hasChunkAt(new BlockPos(chunkX << 4, probeY, chunkZ << 4))) {
          continue;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        for (var blockEntity : chunk.getBlockEntities().values()) {
          collectBlockEntity(ctx, builder, seen, blockEntity);
        }
      }
    }
    return builder.build();
  }

  private static void collectBlockEntity(RenderContext ctx, SceneData.Builder builder, HashSet<BlockPos> seen, BlockEntity blockEntity) {
    var pos = blockEntity.getBlockPos();
    if (!seen.add(pos.immutable())) {
      return;
    }

    if (!shouldConsiderBlockEntity(blockEntity)) {
      return;
    }

    if (!isGlobalBlockEntity(ctx.level(), blockEntity)) {
      var sectionX = SectionPos.blockToSectionCoord(pos.getX());
      var sectionY = SectionPos.blockToSectionCoord(pos.getY());
      var sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
      var minX = SectionPos.sectionToBlockCoord(sectionX);
      var minY = SectionPos.sectionToBlockCoord(sectionY);
      var minZ = SectionPos.sectionToBlockCoord(sectionZ);
      if (!ctx.camera().isVisibleAabb(minX, minY, minZ, minX + 16.0, minY + 16.0, minZ + 16.0)) {
        return;
      }
    }

    var scene = VanillaSubmitCollector.collectBlockEntity(ctx, blockEntity);
    if (scene.totalQuadCount() > 0) {
      ctx.vanillaRenderedBlockEntities().add(pos.asLong());
      builder.addAll(scene);
    }
  }

  private static boolean shouldConsiderBlockEntity(BlockEntity blockEntity) {
    return Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity) != null;
  }

  private static boolean isGlobalBlockEntity(ClientLevel level, BlockEntity blockEntity) {
    return level.getGloballyRenderedBlockEntities().contains(blockEntity);
  }

  private static void collectWeather(
    ClientLevel level,
    RenderContext ctx,
    SceneData.Builder builder,
    Map<Long, Integer> billboardBuckets
  ) {
    var rain = level.getRainLevel(1.0F);
    if (rain <= 0.05F) {
      return;
    }

    var particleCount = Math.max(8, (int) (24 * rain));
    var baseSeed = Double.doubleToLongBits(ctx.camera().eyeX() * 13.0 + ctx.camera().eyeY() * 7.0 + ctx.camera().eyeZ() * 17.0) ^ level.getOverworldClockTime();
    for (var i = 0; i < particleCount; i++) {
      var seed = mix(baseSeed + i * 1_664_525L);
      var px = ctx.camera().eyeX() + ((seed & 0xFF) / 255.0 - 0.5) * 12.0;
      var py = ctx.camera().eyeY() + (((seed >> 8) & 0xFF) / 255.0 - 0.35) * 6.0;
      var pz = ctx.camera().eyeZ() + (((seed >> 16) & 0xFF) / 255.0 - 0.5) * 12.0;
      addBillboard(
        ctx,
        builder,
        billboardBuckets,
        BillboardGeometry.cameraFacingQuad(
          ctx.camera(),
          px,
          py,
          pz,
          0.06F,
          0.45F,
          RAIN_TEXTURE,
          RendererAssets.AlphaMode.TRANSLUCENT,
          0x99D8F6FF,
          0.001F
        ),
        1,
        true
      );
      RenderDebugTrace.current().weatherBillboard();
    }
  }

  private static void addBillboard(
    RenderContext ctx,
    SceneData.Builder builder,
    Map<Long, Integer> billboardBuckets,
    RenderQuad billboard,
    int priority,
    boolean limitDensity
  ) {
    if (limitDensity) {
      var centerX = (billboard.v0().x() + billboard.v1().x() + billboard.v2().x() + billboard.v3().x()) * 0.25;
      var centerY = (billboard.v0().y() + billboard.v1().y() + billboard.v2().y() + billboard.v3().y()) * 0.25;
      var centerZ = (billboard.v0().z() + billboard.v1().z() + billboard.v2().z() + billboard.v3().z()) * 0.25;
      var bucketKey = (((long) Math.floor(centerX / 3.0)) & 0xFFFFFFL) << 40
        | ((((long) Math.floor(centerY / 3.0)) & 0xFFFFL) << 24)
        | (((long) Math.floor(centerZ / 3.0)) & 0xFFFFFFL);
      var count = billboardBuckets.getOrDefault(bucketKey, 0);
      var limit = priority >= 8 ? 4 : priority >= 5 ? 3 : 2;
      if (count >= limit) {
        return;
      }
      billboardBuckets.put(bucketKey, count + 1);
    }

    if (priority == 1) {
      builder.addWeather(billboard);
    } else {
      builder.add(billboard);
    }
    RenderDebugTrace.current().billboard();
  }

  private static RendererAssets.TextureImage createRainTexture() {
    var image = new BufferedImage(4, 16, BufferedImage.TYPE_INT_ARGB);
    for (var y = 0; y < 16; y++) {
      var alpha = (int) (255 * (1.0 - y / 15.0) * 0.7);
      var color = (alpha << 24) | 0xA8ECFF;
      image.setRGB(1, y, color);
      image.setRGB(2, y, color);
    }
    return RendererAssets.TextureImage.from(image, null);
  }

  private static long mix(long input) {
    var x = input;
    x ^= x >>> 33;
    x *= 0xff51afd7ed558ccdL;
    x ^= x >>> 33;
    x *= 0xc4ceb9fe1a85ec53L;
    x ^= x >>> 33;
    return x;
  }

}
