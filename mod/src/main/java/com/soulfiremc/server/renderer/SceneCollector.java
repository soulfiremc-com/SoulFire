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
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;

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

    for (var entity : level.entitiesForRendering()) {
      if (entity == localPlayer) {
        continue;
      }

      var dx = entity.getX() - eyeX;
      var dy = entity.getY() - eyeY;
      var dz = entity.getZ() - eyeZ;
      if (dx * dx + dy * dy + dz * dz > maxDistSq) {
        continue;
      }

      if (entity instanceof Display.BlockDisplay blockDisplay) {
        collectBlockDisplay(blockDisplay, assets, surfaces, shadows);
        continue;
      }
      if (entity instanceof Display.ItemDisplay itemDisplay) {
        collectItemDisplay(itemDisplay, assets, surfaces, billboards, shadows);
        continue;
      }
      if (entity instanceof Display.TextDisplay textDisplay) {
        collectTextDisplay(textDisplay, assets, billboards, shadows);
        continue;
      }
      if (entity instanceof ItemFrame frame) {
        collectItemFrame(level, frame, assets, surfaces, billboards);
        continue;
      }
      if (entity instanceof ItemEntity itemEntity) {
        collectItemEntity(itemEntity, assets, billboards, shadows);
        continue;
      }

      collectGenericEntity(entity, assets, surfaces, billboards, shadows);
    }

    collectWeather(level, eyeX, eyeY, eyeZ, billboards);

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
    ArrayList<SceneData.BillboardData> billboards) {

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
      billboards.add(new SceneData.BillboardData(
        px,
        py,
        pz,
        0.06,
        0.45,
        RAIN_TEXTURE,
        RendererAssets.AlphaMode.TRANSLUCENT,
        0x99D8F6FF,
        0,
        SceneData.BillboardMode.FULL
      ));
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
    ArrayList<SceneData.ShadowData> shadows) {

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

    if (itemModel.billboard() != null) {
      billboards.add(new SceneData.BillboardData(
        itemDisplay.getX(),
        itemDisplay.getY() + 0.5,
        itemDisplay.getZ(),
        0.75,
        0.75,
        itemModel.billboard().texture(),
        itemModel.billboard().alphaMode(),
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.FULL
      ));
    }

    addShadow(itemDisplay, shadows);
  }

  private static void collectTextDisplay(
    Display.TextDisplay textDisplay,
    RendererAssets assets,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows) {

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

    billboards.add(new SceneData.BillboardData(
      textDisplay.getX(),
      textDisplay.getY() + height * 0.5,
      textDisplay.getZ(),
      width,
      height,
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0xFFFFFFFF,
      0,
      mode
    ));

    addShadow(textDisplay, shadows);
  }

  private static void collectItemFrame(
    ClientLevel level,
    ItemFrame frame,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.BillboardData> billboards) {

    var item = frame.getItem();
    var mapId = item.get(DataComponents.MAP_ID);
    if (mapId != null) {
      var mapData = level.getMapData(mapId);
      if (mapData != null) {
        billboards.add(new SceneData.BillboardData(
          frame.getX(),
          frame.getY(),
          frame.getZ(),
          1.0,
          1.0,
          assets.mapTexture(mapData.colors),
          RendererAssets.AlphaMode.OPAQUE,
          0xFFFFFFFF,
          0,
          SceneData.BillboardMode.VERTICAL
        ));
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
    if (itemModel.billboard() != null) {
      billboards.add(new SceneData.BillboardData(
        frame.getX(),
        frame.getY(),
        frame.getZ(),
        0.8,
        0.8,
        itemModel.billboard().texture(),
        itemModel.billboard().alphaMode(),
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.VERTICAL
      ));
    }
  }

  private static void collectItemEntity(
    ItemEntity itemEntity,
    RendererAssets assets,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows) {

    var itemModel = assets.itemRenderModel(itemEntity.getItem());
    var billboard = itemModel.billboard();
    var texture = billboard != null ? billboard.texture() : assets.entityTexture(itemEntity);
    billboards.add(new SceneData.BillboardData(
      itemEntity.getX(),
      itemEntity.getY() + 0.15,
      itemEntity.getZ(),
      0.4,
      0.4,
      texture,
      billboard != null ? billboard.alphaMode() : RendererAssets.AlphaMode.CUTOUT,
      0xFFFFFFFF,
      0,
      SceneData.BillboardMode.FULL
    ));

    addShadow(itemEntity, shadows);
  }

  private static void collectGenericEntity(
    Entity entity,
    RendererAssets assets,
    ArrayList<RendererAssets.GeometryFace> surfaces,
    ArrayList<SceneData.BillboardData> billboards,
    ArrayList<SceneData.ShadowData> shadows) {

    var texture = assets.entityTexture(entity);
    if (entity instanceof net.minecraft.client.player.AbstractClientPlayer) {
      billboards.add(new SceneData.BillboardData(
        entity.getX(),
        entity.getY() + entity.getBbHeight() * 0.5,
        entity.getZ(),
        Math.max(0.5, entity.getBbWidth()),
        Math.max(1.0, entity.getBbHeight()),
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        0xFFFFFFFF,
        0,
        SceneData.BillboardMode.VERTICAL
      ));
    } else {
      var bounds = entity.getBoundingBox();
      for (var direction : Direction.values()) {
        surfaces.add(createEntityFace(bounds, direction, texture));
      }
    }

    addShadow(entity, shadows);
  }

  private static RendererAssets.GeometryFace createEntityFace(AABB bounds, Direction direction, RendererAssets.TextureImage texture) {
    return switch (direction) {
      case DOWN -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.maxZ)
        },
        new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
      case UP -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.minZ)
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
      case NORTH -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.minZ)
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
      case SOUTH -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ)
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
      case WEST -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.minX, (float) bounds.maxY, (float) bounds.maxZ)
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
      case EAST -> RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.maxZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.minY, (float) bounds.minZ),
          new org.joml.Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.minZ)
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        texture,
        RendererAssets.AlphaMode.CUTOUT,
        -1,
        0,
        true
      );
    };
  }

  private static void addShadow(Entity entity, ArrayList<SceneData.ShadowData> shadows) {
    var bounds = entity.getBoundingBox();
    var width = Math.max(0.25, entity.getBbWidth());
    var shadowBounds = new AABB(
      entity.getX() - width,
      entity.getY() - 2.0,
      entity.getZ() - width,
      entity.getX() + width,
      entity.getY() + 0.25,
      entity.getZ() + width
    );
    shadows.add(new SceneData.ShadowData(
      shadowBounds,
      entity.getX(),
      bounds.minY + 0.02,
      entity.getZ(),
      width * 1.5,
      width * 1.5,
      0.35F,
      Direction.UP
    ));
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
