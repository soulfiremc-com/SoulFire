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
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Collects renderable scene primitives outside the block grid.
@UtilityClass
public class SceneCollector {
  private static final RendererAssets.TextureImage RAIN_TEXTURE = createRainTexture();

  public static SceneData collect(
    ClientLevel level,
    LocalPlayer localPlayer,
    double eyeX,
    double eyeY,
    double eyeZ,
    int maxDistance) {

    var assets = RendererAssets.instance();
    var maxDistSq = (double) maxDistance * maxDistance;
    var surfaces = new ArrayList<RendererAssets.GeometryFace>();
    var billboards = new ArrayList<SceneData.BillboardData>();
    var shadows = new ArrayList<SceneData.ShadowData>();
    var billboardBuckets = new HashMap<Long, Integer>();
    var eyePos = localPlayer.getEyePosition();
    var viewVector = localPlayer.getViewVector(1.0F);
    var entities = new ArrayList<Entity>();
    level.entitiesForRendering().forEach(entities::add);
    entities.sort((left, right) -> {
      var leftDistance = left.distanceToSqr(eyePos);
      var rightDistance = right.distanceToSqr(eyePos);
      var leftRelevance = relevanceScore(left, leftDistance, eyePos, viewVector);
      var rightRelevance = relevanceScore(right, rightDistance, eyePos, viewVector);
      var relevanceCompare = Double.compare(rightRelevance, leftRelevance);
      if (relevanceCompare != 0) {
        return relevanceCompare;
      }
      var distanceCompare = Double.compare(leftDistance, rightDistance);
      if (distanceCompare != 0) {
        return distanceCompare;
      }
      return Integer.compare(left.getId(), right.getId());
    });

    for (var entity : entities) {
      if (entity == localPlayer) {
        continue;
      }

      var dx = entity.getX() - eyeX;
      var dy = entity.getY() - eyeY;
      var dz = entity.getZ() - eyeZ;
      var distanceSq = dx * dx + dy * dy + dz * dz;
      if (distanceSq > maxDistSq) {
        continue;
      }
      if (RenderConstants.POV_READABILITY_MODE && shouldSuppressEntity(entity, eyePos, viewVector, distanceSq)) {
        continue;
      }
      var lod = entityLod(distanceSq);

      if (entity instanceof Display.BlockDisplay blockDisplay) {
        collectBlockDisplay(blockDisplay, assets, surfaces, shadows);
        continue;
      }
      if (entity instanceof Display.ItemDisplay itemDisplay) {
        collectItemDisplay(itemDisplay, assets, surfaces, billboards, shadows, billboardBuckets, distanceSq, lod, viewVector, eyePos);
        continue;
      }
      if (entity instanceof Display.TextDisplay textDisplay) {
        collectTextDisplay(textDisplay, assets, billboards, shadows, billboardBuckets, distanceSq, viewVector, eyePos);
        continue;
      }
      if (entity instanceof ItemFrame frame) {
        collectItemFrame(level, frame, assets, surfaces, billboards, billboardBuckets, distanceSq, lod, viewVector, eyePos);
        continue;
      }
      if (entity instanceof ItemEntity itemEntity) {
        collectItemEntity(itemEntity, assets, billboards, shadows, billboardBuckets, distanceSq, lod, viewVector, eyePos);
        continue;
      }

      collectGenericEntity(entity, assets, surfaces, billboards, shadows, billboardBuckets, distanceSq, lod, viewVector, eyePos);
    }

    collectWeather(level, eyeX, eyeY, eyeZ, billboards, billboardBuckets);

    return new SceneData(
      surfaces.toArray(RendererAssets.GeometryFace[]::new),
      billboards.toArray(SceneData.BillboardData[]::new),
      shadows.toArray(SceneData.ShadowData[]::new)
    );
  }

  private static void collectWeather(
    ClientLevel level,
    double eyeX,
    double eyeY,
    double eyeZ,
    ArrayList<SceneData.BillboardData> billboards,
    Map<Long, Integer> billboardBuckets) {

    var rain = level.getRainLevel(1.0F);
    if (rain <= 0.05F) {
      return;
    }

    var particleCount = Math.max(8, (int) (24 * rain));
    var baseSeed = Double.doubleToLongBits(eyeX * 13.0 + eyeY * 7.0 + eyeZ * 17.0) ^ level.getOverworldClockTime();
    for (var i = 0; i < particleCount; i++) {
      var seed = mix(baseSeed + i * 1_664_525L);
      var px = eyeX + ((seed & 0xFF) / 255.0 - 0.5) * 12.0;
      var py = eyeY + (((seed >> 8) & 0xFF) / 255.0 - 0.35) * 6.0;
      var pz = eyeZ + (((seed >> 16) & 0xFF) / 255.0 - 0.5) * 12.0;
      addBillboard(
        billboards,
        billboardBuckets,
        new SceneData.BillboardData(
        px,
        py,
        pz,
        0.06,
        0.45,
        RAIN_TEXTURE,
        RendererAssets.AlphaMode.TRANSLUCENT,
        0x99D8F6FF,
        0,
        SceneData.BillboardMode.FULL,
        1
      ),
        true,
        0.2
      );
    }
  }

  private static void collectBlockDisplay(
    Display.BlockDisplay blockDisplay,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.ShadowData> shadows) {

    var renderState = blockDisplay.renderState();
    var blockRenderState = blockDisplay.blockRenderState();
    if (renderState == null || blockRenderState == null) {
      return;
    }

    var transform = assets.displayMatrix(renderState, 1.0F)
      .translate((float) blockDisplay.getX(), (float) blockDisplay.getY(), (float) blockDisplay.getZ());
    for (var face : assets.blockGeometry(blockRenderState.blockState()).faces()) {
      surfaces.add(face.transformed(transform));
    }

    addShadow(blockDisplay, shadows);
  }

  private static void collectItemDisplay(
    Display.ItemDisplay itemDisplay,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    RendererAssets.EntityLod lod,
    net.minecraft.world.phys.Vec3 viewVector,
    net.minecraft.world.phys.Vec3 eyePos) {

    var renderState = itemDisplay.renderState();
    var itemRenderState = itemDisplay.itemRenderState();
    if (renderState == null || itemRenderState == null) {
      return;
    }

    var itemModel = assets.itemRenderModel(itemRenderState.itemStack());
    var transform = assets.displayMatrix(renderState, 1.0F)
      .mul(assets.itemDisplayTransform(itemRenderState.itemTransform()))
      .translate((float) itemDisplay.getX(), (float) itemDisplay.getY(), (float) itemDisplay.getZ());
    for (var face : itemModel.geometry().faces()) {
      surfaces.add(face.transformed(transform));
    }

    if (itemModel.billboard() != null && lod != RendererAssets.EntityLod.NEAR) {
      addBillboard(
        billboards,
        billboardBuckets,
        new SceneData.BillboardData(
        itemDisplay.getX(),
        itemDisplay.getY() + 0.5,
        itemDisplay.getZ(),
        lod == RendererAssets.EntityLod.MEDIUM ? 0.65 : 0.5,
        lod == RendererAssets.EntityLod.MEDIUM ? 0.65 : 0.5,
        itemModel.billboard().texture(),
        itemModel.billboard().alphaMode(),
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.FULL,
        lod == RendererAssets.EntityLod.MEDIUM ? 6 : 3
      ),
        distanceSq > 24 * 24,
        billboardImportance(itemDisplay.getX(), itemDisplay.getY(), itemDisplay.getZ(), eyePos, viewVector, lod == RendererAssets.EntityLod.MEDIUM ? 6 : 3)
      );
    }

    addShadow(itemDisplay, shadows);
  }

  private static void collectTextDisplay(
    Display.TextDisplay textDisplay,
    RendererAssets assets,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    net.minecraft.world.phys.Vec3 viewVector,
    net.minecraft.world.phys.Vec3 eyePos) {

    var renderState = textDisplay.renderState();
    var textRenderState = textDisplay.textRenderState();
    if (renderState == null || textRenderState == null) {
      return;
    }

    var textColor = 0xFF000000 | (textRenderState.textOpacity().get(1.0F) & 0xFF) << 24 | 0x00FFFFFF;
    var backgroundColor = textRenderState.backgroundColor().get(1.0F);
    var texture = assets.textTexture(textRenderState.text(), textRenderState.lineWidth(), textColor, backgroundColor);
    var width = Math.max(0.5, textDisplay.getBbWidth());
    var height = Math.max(0.25, textDisplay.getBbHeight() == 0.0F ? 0.35 : textDisplay.getBbHeight());
    var mode = switch (renderState.billboardConstraints()) {
      case FIXED -> SceneData.BillboardMode.VERTICAL;
      default -> SceneData.BillboardMode.FULL;
    };

    addBillboard(
      billboards,
      billboardBuckets,
      new SceneData.BillboardData(
      textDisplay.getX(),
      textDisplay.getY() + height * 0.5,
      textDisplay.getZ(),
      width,
      height,
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0xFFFFFFFF,
      0,
      mode,
      10
    ),
      distanceSq > 48 * 48,
      billboardImportance(textDisplay.getX(), textDisplay.getY(), textDisplay.getZ(), eyePos, viewVector, 10)
    );

    addShadow(textDisplay, shadows);
  }

  private static void collectItemFrame(
    ClientLevel level,
    ItemFrame frame,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.BillboardData> billboards,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    RendererAssets.EntityLod lod,
    net.minecraft.world.phys.Vec3 viewVector,
    net.minecraft.world.phys.Vec3 eyePos) {

    var item = frame.getItem();
    var mapId = item.get(DataComponents.MAP_ID);
    if (mapId != null) {
      var mapData = level.getMapData(mapId);
      if (mapData != null) {
        addBillboard(
          billboards,
          billboardBuckets,
          new SceneData.BillboardData(
          frame.getX(),
          frame.getY(),
          frame.getZ(),
          1.0,
          1.0,
          assets.mapTexture(mapData.colors),
          RendererAssets.AlphaMode.OPAQUE,
          0xFFFFFFFF,
          0,
          SceneData.BillboardMode.VERTICAL,
          9
        ),
          distanceSq > 56 * 56,
          billboardImportance(frame.getX(), frame.getY(), frame.getZ(), eyePos, viewVector, 9)
        );
        return;
      }
    }

    var itemModel = assets.itemRenderModel(item);
    var transform = new Matrix4f()
      .translate((float) frame.getX(), (float) frame.getY(), (float) frame.getZ())
      .scale(0.6F);
    for (var face : itemModel.geometry().faces()) {
      surfaces.add(face.transformed(transform));
    }
    if (itemModel.billboard() != null && lod != RendererAssets.EntityLod.NEAR) {
      addBillboard(
        billboards,
        billboardBuckets,
        new SceneData.BillboardData(
        frame.getX(),
        frame.getY(),
        frame.getZ(),
        0.8,
        0.8,
        itemModel.billboard().texture(),
        itemModel.billboard().alphaMode(),
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.VERTICAL,
        8
      ),
        distanceSq > 40 * 40,
        billboardImportance(frame.getX(), frame.getY(), frame.getZ(), eyePos, viewVector, 8)
      );
    }
  }

  private static void collectItemEntity(
    ItemEntity itemEntity,
    RendererAssets assets,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    RendererAssets.EntityLod lod,
    net.minecraft.world.phys.Vec3 viewVector,
    net.minecraft.world.phys.Vec3 eyePos) {

    var itemModel = assets.itemRenderModel(itemEntity.getItem());
    var billboard = itemModel.billboard();
    var texture = billboard != null ? billboard.texture() : assets.entityTexture(itemEntity);
    addBillboard(
      billboards,
      billboardBuckets,
      new SceneData.BillboardData(
      itemEntity.getX(),
      itemEntity.getY() + 0.15,
      itemEntity.getZ(),
      lod == RendererAssets.EntityLod.NEAR ? 0.4 : lod == RendererAssets.EntityLod.MEDIUM ? 0.28 : 0.2,
      lod == RendererAssets.EntityLod.NEAR ? 0.4 : lod == RendererAssets.EntityLod.MEDIUM ? 0.28 : 0.2,
      texture,
      billboard != null ? billboard.alphaMode() : RendererAssets.AlphaMode.CUTOUT,
      0xFFFFFFFF,
      0,
      SceneData.BillboardMode.FULL,
      lod == RendererAssets.EntityLod.NEAR ? 7 : 2
    ),
      distanceSq > 20 * 20,
      billboardImportance(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), eyePos, viewVector, lod == RendererAssets.EntityLod.NEAR ? 7 : 2)
    );

    addShadow(itemEntity, shadows);
  }

  private static void collectGenericEntity(
    Entity entity,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows,
    Map<Long, Integer> billboardBuckets,
    double distanceSq,
    RendererAssets.EntityLod lod,
    net.minecraft.world.phys.Vec3 viewVector,
    net.minecraft.world.phys.Vec3 eyePos) {

    var texture = assets.entityTexture(entity);
    if (lod == RendererAssets.EntityLod.FAR) {
      addBillboard(
        billboards,
        billboardBuckets,
        new SceneData.BillboardData(
        entity.getX(),
        entity.getY() + entity.getBbHeight() * 0.5,
        entity.getZ(),
        Math.max(0.35, entity.getBbWidth() * 0.7),
        Math.max(0.7, entity.getBbHeight() * 0.7),
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.VERTICAL,
        entity instanceof LivingEntity ? 5 : 2
      ),
        true,
        billboardImportance(entity.getX(), entity.getY(), entity.getZ(), eyePos, viewVector, entity instanceof LivingEntity ? 5 : 2)
      );
    } else {
      surfaces.addAll(assets.entityModel(entity, texture, lod));
      if (lod == RendererAssets.EntityLod.MEDIUM) {
        addBillboard(
          billboards,
          billboardBuckets,
          new SceneData.BillboardData(
            entity.getX(),
            entity.getY() + entity.getBbHeight() * 0.55,
            entity.getZ(),
            Math.max(0.3, entity.getBbWidth() * 0.45),
            Math.max(0.6, entity.getBbHeight() * 0.45),
            texture,
            RendererAssets.AlphaMode.CUTOUT,
            0x88FFFFFF,
            0,
            SceneData.BillboardMode.VERTICAL,
            4
          ),
          distanceSq > 18 * 18,
          billboardImportance(entity.getX(), entity.getY(), entity.getZ(), eyePos, viewVector, 4)
        );
      }
    }

    addShadow(entity, shadows);
  }

  private static void addShadow(Entity entity, ArrayList<SceneData.ShadowData> shadows) {
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
    var shadowBounds = new net.minecraft.world.phys.AABB(
      entity.getX() - width * spread,
      groundY - 0.1,
      entity.getZ() - width * spread,
      entity.getX() + width * spread,
      groundY + 0.15,
      entity.getZ() + width * spread
    );
    shadows.add(new SceneData.ShadowData(
      shadowBounds,
      entity.getX(),
      groundY,
      entity.getZ(),
      width * 1.8 * spread,
      width * 1.5 * spread,
      0.45F * fade,
      Direction.UP,
      0
    ));
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
    ArrayList<SceneData.BillboardData> billboards,
    Map<Long, Integer> billboardBuckets,
    SceneData.BillboardData billboard,
    boolean limitDensity,
    double importance) {

    if (limitDensity) {
      var bucketKey = (((long) Math.floor(billboard.centerX() / 3.0)) & 0xFFFFFFL) << 40
        | ((((long) Math.floor(billboard.centerY() / 3.0)) & 0xFFFFL) << 24)
        | (((long) Math.floor(billboard.centerZ() / 3.0)) & 0xFFFFFFL);
      var count = billboardBuckets.getOrDefault(bucketKey, 0);
      var limit = billboard.priority() >= 8 ? 4 : billboard.priority() >= 5 ? 3 : 2;
      if (count >= limit) {
        return;
      }
      billboardBuckets.put(bucketKey, count + 1);
    }

    billboards.add(billboard);
  }

  private static boolean shouldSuppressEntity(Entity entity, net.minecraft.world.phys.Vec3 eyePos, net.minecraft.world.phys.Vec3 viewVector, double distanceSq) {
    var alignment = alignment(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), eyePos, viewVector);
    if (distanceSq <= RenderConstants.POV_ENTITY_HIDE_RADIUS * RenderConstants.POV_ENTITY_HIDE_RADIUS) {
      return alignment < 0.985;
    }
    return distanceSq <= RenderConstants.POV_ENTITY_FADE_RADIUS * RenderConstants.POV_ENTITY_FADE_RADIUS
      && alignment < RenderConstants.POV_ENTITY_KEEP_ALIGNMENT;
  }

  private static double relevanceScore(Entity entity, double distanceSq, net.minecraft.world.phys.Vec3 eyePos, net.minecraft.world.phys.Vec3 viewVector) {
    var alignment = alignment(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), eyePos, viewVector);
    var distance = Math.sqrt(distanceSq);
    var typeBias = entity instanceof LivingEntity ? 0.35 : entity instanceof ItemFrame ? 0.25 : 0.0;
    return alignment * 2.1 + typeBias - distance * 0.035;
  }

  private static double billboardImportance(double x, double y, double z, net.minecraft.world.phys.Vec3 eyePos, net.minecraft.world.phys.Vec3 viewVector, int priority) {
    return alignment(x, y, z, eyePos, viewVector) * 2.0 + priority * 0.1;
  }

  private static double alignment(double x, double y, double z, net.minecraft.world.phys.Vec3 eyePos, net.minecraft.world.phys.Vec3 viewVector) {
    var dx = x - eyePos.x;
    var dy = y - eyePos.y;
    var dz = z - eyePos.z;
    var len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1.0E-4) {
      return 1.0;
    }
    return (dx / len) * viewVector.x + (dy / len) * viewVector.y + (dz / len) * viewVector.z;
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
