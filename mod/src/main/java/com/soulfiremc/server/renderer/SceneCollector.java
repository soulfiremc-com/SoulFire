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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/// Collects dynamic scene primitives that are not part of the chunk-section world mesh.
@UtilityClass
public class SceneCollector {
  private static final RendererAssets.TextureImage RAIN_TEXTURE = createRainTexture();
  private static final RendererAssets.TextureImage SHADOW_TEXTURE = createShadowTexture();

  public static SceneData collectEntitiesAndWeather(RenderContext ctx, LocalPlayer localPlayer) {
    var level = ctx.level();
    var assets = RendererAssets.instance();
    var builder = SceneData.builder();
    var billboardBuckets = new HashMap<Long, Integer>();
    var trace = RenderDebugTrace.current();

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

      var bounds = entity.getBoundingBox();
      if (!ctx.camera().isVisibleAabb(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)) {
        continue;
      }
      trace.entityVisible();

      var lod = entityLod(distanceSq);
      collectGenericEntity(ctx, entity, assets, builder, billboardBuckets, distanceSq, lod);
    }

    builder.addAll(VanillaSubmitCollector.collectParticles(ctx));
    collectWeather(level, ctx, builder, billboardBuckets);
    return builder.build();
  }

  private static void collectGenericEntity(
    RenderContext ctx,
    Entity entity,
    RendererAssets assets,
    SceneData.Builder builder,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    RendererAssets.EntityLod lod
  ) {
    var renderState = assets.entityRenderState(entity);
    if (renderState != null && renderState.isInvisible && !renderState.appearsGlowing()) {
      return;
    }

    var renderOffset = renderState != null && renderState.passengerOffset != null ? renderState.passengerOffset : Vec3.ZERO;
    var renderX = entity.getX() + renderOffset.x;
    var renderY = entity.getY() + renderOffset.y;
    var renderZ = entity.getZ() + renderOffset.z;
    var texture = assets.entityTexture(entity);
    var vanillaScene = VanillaSubmitCollector.collectEntity(ctx, entity, renderState);
    var usedVanillaCollector = vanillaScene.totalQuadCount() > 0;
    if (usedVanillaCollector) {
      builder.addAll(vanillaScene);
    } else if (lod == RendererAssets.EntityLod.FAR) {
      addBillboard(
        ctx,
        builder,
        billboardBuckets,
        buildBillboard(
          ctx.camera(),
          renderX,
          renderY + entity.getBbHeight() * 0.5,
          renderZ,
          (float) Math.max(0.35, entity.getBbWidth() * 0.7),
          (float) Math.max(0.7, entity.getBbHeight() * 0.7),
          texture,
          RendererAssets.AlphaMode.CUTOUT,
          0xFFFFFFFF,
          0,
          BillboardMode.VERTICAL
        ),
        entity instanceof LivingEntity ? 5 : 2,
        true
      );
    } else {
      for (var face : assets.entityModel(entity, texture, lod)) {
        builder.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, 0xFFFFFFFF, true, 0.0F));
      }
    }

    if (!usedVanillaCollector) {
      addEntityLabels(ctx, assets, builder, billboardBuckets, renderState, renderX, renderY, renderZ);
      addShadow(entity, builder);
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

    var centerX = pos.getX() + 0.5;
    var centerY = pos.getY() + 0.5;
    var centerZ = pos.getZ() + 0.5;
    var dx = centerX - ctx.camera().eyeX();
    var dy = centerY - ctx.camera().eyeY();
    var dz = centerZ - ctx.camera().eyeZ();
    if (dx * dx + dy * dy + dz * dz > ctx.maxDistanceSq()) {
      return;
    }

    if (!ctx.camera().isVisibleAabb(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0)) {
      return;
    }

    var scene = VanillaSubmitCollector.collectBlockEntity(ctx, blockEntity);
    if (scene.totalQuadCount() > 0) {
      ctx.vanillaRenderedBlockEntities().add(pos.asLong());
      builder.addAll(scene);
    }
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
        buildBillboard(
          ctx.camera(),
          px,
          py,
          pz,
          0.06F,
          0.45F,
          RAIN_TEXTURE,
          RendererAssets.AlphaMode.TRANSLUCENT,
          0x99D8F6FF,
          0,
          BillboardMode.FULL
        ),
        1,
        true
      );
      RenderDebugTrace.current().weatherBillboard();
    }
  }

  private static void addShadow(Entity entity, SceneData.Builder builder) {
    var level = entity.level();
    var feet = entity.blockPosition();
    var groundY = entity.getY() - 0.02;
    var foundGround = false;
    for (var depth = 0; depth <= 8; depth++) {
      var checkPos = feet.below(depth);
      var state = level.getBlockState(checkPos);
      if (!state.isAir() && (state.isSolidRender() || !state.getCollisionShape(level, checkPos).isEmpty())) {
        groundY = checkPos.getY() + state.getCollisionShape(level, checkPos).bounds().maxY + 0.02;
        foundGround = true;
        break;
      }
    }

    if (!foundGround) {
      return;
    }

    var bounds = entity.getBoundingBox();
    var width = Math.max(0.22, entity.getBbWidth());
    var heightGap = Math.max(0.0, bounds.minY - groundY);
    var fade = (float) Math.max(0.1, 1.0 - Math.min(heightGap / 5.0, 0.85));
    var spread = 1.15 + Math.min(heightGap * 0.2, 0.8);
    var halfW = (float) (width * 0.9 * spread);
    var halfH = (float) (width * 0.75 * spread);
    builder.add(new RenderQuad(
      new RenderVertex((float) entity.getX() - halfW, (float) groundY, (float) entity.getZ() - halfH, 0.0F, 0.0F),
      new RenderVertex((float) entity.getX() - halfW, (float) groundY, (float) entity.getZ() + halfH, 0.0F, 1.0F),
      new RenderVertex((float) entity.getX() + halfW, (float) groundY, (float) entity.getZ() + halfH, 1.0F, 1.0F),
      new RenderVertex((float) entity.getX() + halfW, (float) groundY, (float) entity.getZ() - halfH, 1.0F, 0.0F),
      SHADOW_TEXTURE,
      RendererAssets.AlphaMode.TRANSLUCENT,
      Math.min(255, Math.max(0, (int) (0.45F * fade * 255.0F))) << 24,
      true,
      0.003F
    ));
    RenderDebugTrace.current().shadow();
  }

  private static void addEntityLabels(
    RenderContext ctx,
    RendererAssets assets,
    SceneData.Builder builder,
    Map<Long, Integer> billboardBuckets,
    @org.jetbrains.annotations.Nullable EntityRenderState renderState,
    double renderX,
    double renderY,
    double renderZ
  ) {
    if (renderState == null) {
      return;
    }

    var attachment = renderState.nameTagAttachment != null
      ? renderState.nameTagAttachment
      : new Vec3(0.0, Math.max(0.5, renderState.boundingBoxHeight + 0.5), 0.0);

    if (renderState.scoreText != null) {
      addEntityLabel(ctx, assets, builder, billboardBuckets, renderState.scoreText, renderX, renderY, renderZ, attachment, 0.26F);
    }
    if (renderState.nameTag != null) {
      addEntityLabel(ctx, assets, builder, billboardBuckets, renderState.nameTag, renderX, renderY, renderZ, attachment, 0.0F);
    }
  }

  private static void addEntityLabel(
    RenderContext ctx,
    RendererAssets assets,
    SceneData.Builder builder,
    Map<Long, Integer> billboardBuckets,
    Component text,
    double renderX,
    double renderY,
    double renderZ,
    Vec3 attachment,
    float verticalOffset
  ) {
    var maxWidth = Math.max(32, Math.min(256, text.getString().length() * 7 + 12));
    var texture = assets.textTexture(text, maxWidth, 0xFFFFFFFF, 0x44000000);
    var width = Math.max(0.5F, texture.width() * 0.025F);
    var height = Math.max(0.18F, texture.height() * 0.025F);
    addBillboard(
      ctx,
      builder,
      billboardBuckets,
      buildBillboard(
        ctx.camera(),
        renderX + attachment.x,
        renderY + attachment.y + verticalOffset,
        renderZ + attachment.z,
        width,
        height,
        texture,
        RendererAssets.AlphaMode.TRANSLUCENT,
        0xFFFFFFFF,
        0,
        BillboardMode.FULL
      ),
      10,
      false
    );
  }

  private static RendererAssets.EntityLod entityLod(double distanceSq) {
    if (distanceSq < 12.0 * 12.0) {
      return RendererAssets.EntityLod.NEAR;
    }
    if (distanceSq < 30.0 * 30.0) {
      return RendererAssets.EntityLod.MEDIUM;
    }
    return RendererAssets.EntityLod.FAR;
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

    builder.add(billboard);
    RenderDebugTrace.current().billboard();
  }

  private static RenderQuad buildBillboard(
    Camera camera,
    double centerX,
    double centerY,
    double centerZ,
    float width,
    float height,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int tintColor,
    int emission,
    BillboardMode mode
  ) {
    var center = new Vector3d(centerX, centerY, centerZ);
    var toCamera = new Vector3d(camera.eyeX() - center.x, camera.eyeY() - center.y, camera.eyeZ() - center.z);
    if (toCamera.lengthSquared() < 1.0E-6) {
      toCamera.set(0.0, 0.0, 1.0);
    }
    toCamera.normalize();

    Vector3d right;
    Vector3d up;
    if (mode == BillboardMode.VERTICAL) {
      right = new Vector3d(toCamera.z, 0.0, -toCamera.x);
      if (right.lengthSquared() < 1.0E-6) {
        right = new Vector3d(1.0, 0.0, 0.0);
      }
      right.normalize();
      up = new Vector3d(0.0, 1.0, 0.0);
    } else {
      right = new Vector3d(camera.rightX(), camera.rightY(), camera.rightZ());
      if (right.lengthSquared() < 1.0E-6) {
        right = new Vector3d(1.0, 0.0, 0.0);
      }
      right.normalize();
      up = new Vector3d(camera.upX(), camera.upY(), camera.upZ());
      if (up.lengthSquared() < 1.0E-6) {
        up = new Vector3d(0.0, 1.0, 0.0);
      }
      up.normalize();
    }

    var halfW = width * 0.5F;
    var halfH = height * 0.5F;
    var p0 = new Vector3f((float) (center.x - right.x * halfW - up.x * halfH), (float) (center.y - right.y * halfW - up.y * halfH), (float) (center.z - right.z * halfW - up.z * halfH));
    var p1 = new Vector3f((float) (center.x - right.x * halfW + up.x * halfH), (float) (center.y - right.y * halfW + up.y * halfH), (float) (center.z - right.z * halfW + up.z * halfH));
    var p2 = new Vector3f((float) (center.x + right.x * halfW + up.x * halfH), (float) (center.y + right.y * halfW + up.y * halfH), (float) (center.z + right.z * halfW + up.z * halfH));
    var p3 = new Vector3f((float) (center.x + right.x * halfW - up.x * halfH), (float) (center.y + right.y * halfW - up.y * halfH), (float) (center.z + right.z * halfW - up.z * halfH));
    var face = RendererAssets.GeometryFace.of(
      new Vector3f[]{p0, p1, p2, p3},
      new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
      texture,
      alphaMode,
      null,
      -1,
      emission,
      false
    );
    return WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, LightingCalculator.emissiveColor(tintColor, emission), true, 0.001F);
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

  private static RendererAssets.TextureImage createShadowTexture() {
    var size = 32;
    var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    for (var y = 0; y < size; y++) {
      for (var x = 0; x < size; x++) {
        var dx = (x + 0.5F) / size * 2.0F - 1.0F;
        var dy = (y + 0.5F) / size * 2.0F - 1.0F;
        var distance = (float) Math.sqrt(dx * dx + dy * dy);
        var alpha = Math.max(0.0F, 1.0F - distance * 1.25F);
        image.setRGB(x, y, ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
      }
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

  private enum BillboardMode {
    FULL,
    VERTICAL
  }
}
