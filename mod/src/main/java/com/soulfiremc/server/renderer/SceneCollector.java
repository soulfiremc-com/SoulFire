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
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;

/// Collects dynamic scene primitives that are not part of the chunk-section world mesh.
@UtilityClass
public class SceneCollector {
  private static final Identifier RAIN_LOCATION = Identifier.withDefaultNamespace("textures/environment/rain.png");
  private static final Identifier SNOW_LOCATION = Identifier.withDefaultNamespace("textures/environment/snow.png");
  private static final int RAIN_TABLE_SIZE = 32;
  private static final int HALF_RAIN_TABLE_SIZE = 16;
  private static final WeatherEffectRenderer VANILLA_WEATHER = new WeatherEffectRenderer();
  private static final float[] WEATHER_COLUMN_SIZE_X = weatherColumnSizes(true);
  private static final float[] WEATHER_COLUMN_SIZE_Z = weatherColumnSizes(false);

  public static SceneData collectEntitiesAndWeather(RenderContext ctx, LocalPlayer localPlayer) {
    var level = ctx.level();
    var builder = SceneData.builder();
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
    collectWeather(level, ctx, builder);
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

  private static void collectWeather(ClientLevel level, RenderContext ctx, SceneData.Builder builder) {
    var renderState = new WeatherRenderState();
    var camera = ctx.camera();
    VANILLA_WEATHER.extractRenderState(
      level,
      Math.toIntExact(Math.floorMod(ctx.animationTick(), Integer.MAX_VALUE)),
      1.0F,
      new Vec3(camera.eyeX(), camera.eyeY(), camera.eyeZ()),
      renderState
    );
    if (renderState.intensity <= 0.0F) {
      return;
    }

    var assets = RendererAssets.instance();
    collectWeatherColumns(ctx, builder, renderState, renderState.rainColumns, assets.texture(RAIN_LOCATION), 1.0F);
    collectWeatherColumns(ctx, builder, renderState, renderState.snowColumns, assets.texture(SNOW_LOCATION), 0.8F);
  }

  private static void collectWeatherColumns(
    RenderContext ctx,
    SceneData.Builder builder,
    WeatherRenderState renderState,
    List<WeatherEffectRenderer.ColumnInstance> columns,
    RendererAssets.TextureImage texture,
    float maxAlpha
  ) {
    if (columns.isEmpty()) {
      return;
    }

    var camera = ctx.camera();
    var cameraBlockX = Mth.floor(camera.eyeX());
    var cameraBlockZ = Mth.floor(camera.eyeZ());
    var radiusSq = (float) renderState.radius * renderState.radius;
    if (radiusSq <= 0.0F) {
      return;
    }

    var minecraft = Minecraft.getInstance();
    var pipeline = minecraft != null && Minecraft.useShaderTransparency() ? RenderPipelines.WEATHER_DEPTH_WRITE : RenderPipelines.WEATHER_NO_DEPTH_WRITE;
    for (var column : columns) {
      var relativeX = (float) (column.x() + 0.5 - camera.eyeX());
      var relativeZ = (float) (column.z() + 0.5 - camera.eyeZ());
      var distanceSq = (float) Mth.lengthSquared(relativeX, relativeZ);
      var alpha = Mth.lerp(Math.min(distanceSq / radiusSq, 1.0F), maxAlpha, 0.5F) * renderState.intensity;
      if (alpha <= 0.0F) {
        continue;
      }

      var tableIndex = (column.z() - cameraBlockZ + HALF_RAIN_TABLE_SIZE) * RAIN_TABLE_SIZE + column.x() - cameraBlockX + HALF_RAIN_TABLE_SIZE;
      if (tableIndex < 0 || tableIndex >= WEATHER_COLUMN_SIZE_X.length) {
        continue;
      }

      var halfSizeX = WEATHER_COLUMN_SIZE_X[tableIndex] / 2.0F;
      var halfSizeZ = WEATHER_COLUMN_SIZE_Z[tableIndex] / 2.0F;
      var x0 = column.x() + 0.5F - halfSizeX;
      var x1 = column.x() + 0.5F + halfSizeX;
      var y0 = (float) column.bottomY();
      var y1 = (float) column.topY();
      var z0 = column.z() + 0.5F - halfSizeZ;
      var z1 = column.z() + 0.5F + halfSizeZ;
      var u0 = column.uOffset();
      var u1 = column.uOffset() + 1.0F;
      var v0 = column.bottomY() * 0.25F + column.vOffset();
      var v1 = column.topY() * 0.25F + column.vOffset();
      var color = modulateColor(ARGB.white(alpha), lightColor(ctx, column.lightCoords()));
      var material = RenderMaterial
        .create(texture, RendererAssets.AlphaMode.TRANSLUCENT, color, true, 0.0F, RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD)
        .withPipelineState(pipeline);
      builder.addWeather(new RenderQuad(
        new RenderVertex(x0, y1, z0, u0, v0, 0xFFFFFFFF),
        new RenderVertex(x1, y1, z1, u1, v0, 0xFFFFFFFF),
        new RenderVertex(x1, y0, z1, u1, v1, 0xFFFFFFFF),
        new RenderVertex(x0, y0, z0, u0, v1, 0xFFFFFFFF),
        material
      ));
      RenderDebugTrace.current().weatherBillboard();
    }
  }

  private static float[] weatherColumnSizes(boolean xAxis) {
    var sizes = new float[RAIN_TABLE_SIZE * RAIN_TABLE_SIZE];
    for (var z = 0; z < RAIN_TABLE_SIZE; z++) {
      for (var x = 0; x < RAIN_TABLE_SIZE; x++) {
        var deltaX = x - HALF_RAIN_TABLE_SIZE;
        var deltaZ = z - HALF_RAIN_TABLE_SIZE;
        var distance = Mth.length(deltaX, deltaZ);
        sizes[z * RAIN_TABLE_SIZE + x] = distance == 0.0F ? 0.0F : xAxis ? -deltaZ / distance : deltaX / distance;
      }
    }
    return sizes;
  }

  private static int lightColor(RenderContext ctx, int lightCoords) {
    if (lightCoords == LightCoordsUtil.FULL_BRIGHT) {
      return 0xFFFFFFFF;
    }

    var blockLight = LightCoordsUtil.block(lightCoords) / 15.0F;
    var skyLevel = LightCoordsUtil.sky(lightCoords);
    var skyLight = Lightmap.getBrightness(ctx.level().dimensionType(), skyLevel);
    var factor = Math.clamp(Math.max(blockLight, skyLight), 0.18F, 1.0F);
    var channel = Math.clamp(Math.round(factor * 255.0F), 0, 255);
    return 0xFF000000 | (channel << 16) | (channel << 8) | channel;
  }

  private static int modulateColor(int left, int right) {
    var a = ((left >>> 24) & 0xFF) * ((right >>> 24) & 0xFF) / 255;
    var r = ((left >>> 16) & 0xFF) * ((right >>> 16) & 0xFF) / 255;
    var g = ((left >>> 8) & 0xFF) * ((right >>> 8) & 0xFF) / 255;
    var b = (left & 0xFF) * (right & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

}
