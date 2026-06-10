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

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.Arrays;

/// Builds the same cloud cell mesh as Minecraft's CloudRenderer, but as CPU-rasterized quads.
public final class CloudMeshCollector {
  private static final Identifier CLOUD_TEXTURE = Identifier.withDefaultNamespace("environment/clouds");
  private static final float CELL_SIZE = 12.0F;
  private static final float CELL_HEIGHT = 4.0F;
  private static final int TICKS_PER_CELL = 400;
  private static final float BLOCKS_PER_SECOND = 0.6F;
  private static final RendererAssets.TextureImage WHITE_TEXTURE = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
  private static volatile TextureData textureData;

  private CloudMeshCollector() {}

  public static SceneData collect(RenderContext ctx) {
    var texture = textureData();
    if (texture == null) {
      return SceneData.EMPTY;
    }

    var cloudStatus = cloudStatus();
    if (cloudStatus == CloudStatus.OFF) {
      return SceneData.EMPTY;
    }

    var cloudColor = ctx.environmentProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, 1.0F);
    if (ARGB.alpha(cloudColor) == 0) {
      return SceneData.EMPTY;
    }

    var range = cloudRange(ctx);
    var cloudFogEnd = Math.min(range * 16.0F, ctx.environmentProbe().getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, 1.0F));
    return collect(
      ctx.camera(),
      texture,
      cloudStatus,
      cloudColor,
      ctx.environmentProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, 1.0F),
      range,
      ctx.animationTick(),
      cloudFogEnd
    );
  }

  static SceneData collect(
    Camera camera,
    TextureData texture,
    CloudStatus cloudStatus,
    int cloudColor,
    float cloudHeight,
    int range,
    long animationTick,
    float cloudFogEnd
  ) {
    if (cloudStatus == CloudStatus.OFF || ARGB.alpha(cloudColor) == 0) {
      return SceneData.EMPTY;
    }

    var relativeBottomY = cloudHeight - (float) camera.eyeY();
    var relativeTopY = relativeBottomY + CELL_HEIGHT;
    var relativeCameraPos = relativeTopY < 0.0F
      ? RelativeCameraPos.ABOVE_CLOUDS
      : relativeBottomY > 0.0F ? RelativeCameraPos.BELOW_CLOUDS : RelativeCameraPos.INSIDE_CLOUDS;
    var cloudOffset = (float) (animationTick % (texture.width() * (long) TICKS_PER_CELL));
    var cloudX = camera.eyeX() + cloudOffset * BLOCKS_PER_SECOND / 20.0F;
    var cloudZ = camera.eyeZ() + 3.96F;
    var textureWidthBlocks = texture.width() * (double) CELL_SIZE;
    var textureHeightBlocks = texture.height() * (double) CELL_SIZE;
    cloudX -= Mth.floor(cloudX / textureWidthBlocks) * textureWidthBlocks;
    cloudZ -= Mth.floor(cloudZ / textureHeightBlocks) * textureHeightBlocks;
    var cellX = Mth.floor(cloudX / CELL_SIZE);
    var cellZ = Mth.floor(cloudZ / CELL_SIZE);
    var xInCell = (float) (cloudX - cellX * CELL_SIZE);
    var zInCell = (float) (cloudZ - cellZ * CELL_SIZE);
    var builder = SceneData.builder();
    buildMesh(
      builder,
      camera,
      relativeCameraPos,
      cellX,
      cellZ,
      cloudStatus == CloudStatus.FANCY,
      Mth.ceil(range * 16.0F / CELL_SIZE),
      xInCell,
      zInCell,
      relativeBottomY,
      cloudColor,
      cloudFogEnd,
      cloudMaterial(cloudStatus == CloudStatus.FANCY),
      texture
    );
    return builder.build();
  }

  private static void buildMesh(
    SceneData.Builder builder,
    Camera camera,
    RelativeCameraPos relativePos,
    int centerCellX,
    int centerCellZ,
    boolean extrude,
    int radiusCells,
    float xInCell,
    float zInCell,
    float relativeBottomY,
    int cloudColor,
    float cloudFogEnd,
    RenderMaterial material,
    TextureData texture
  ) {
    for (var ring = 0; ring <= 2 * radiusCells; ring++) {
      for (var relativeCellX = -ring; relativeCellX <= ring; relativeCellX++) {
        var relativeCellZ = ring - Math.abs(relativeCellX);
        if (relativeCellZ < 0 || relativeCellZ > radiusCells || relativeCellX * relativeCellX + relativeCellZ * relativeCellZ > radiusCells * radiusCells) {
          continue;
        }

        if (relativeCellZ != 0) {
          tryBuildCell(builder, camera, relativePos, centerCellX, centerCellZ, extrude, relativeCellX, -relativeCellZ, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material, texture);
        }
        tryBuildCell(builder, camera, relativePos, centerCellX, centerCellZ, extrude, relativeCellX, relativeCellZ, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material, texture);
      }
    }
  }

  private static void tryBuildCell(
    SceneData.Builder builder,
    Camera camera,
    RelativeCameraPos relativePos,
    int cellX,
    int cellZ,
    boolean extrude,
    int relativeCellX,
    int relativeCellZ,
    float xInCell,
    float zInCell,
    float relativeBottomY,
    int cloudColor,
    float cloudFogEnd,
    RenderMaterial material,
    TextureData texture
  ) {
    var indexX = Math.floorMod(cellX + relativeCellX, texture.width());
    var indexZ = Math.floorMod(cellZ + relativeCellZ, texture.height());
    var cellData = texture.cells()[indexX + indexZ * texture.width()];
    if (cellData == 0L) {
      return;
    }

    if (extrude) {
      buildExtrudedCell(builder, camera, relativePos, relativeCellX, relativeCellZ, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material, cellData);
    } else {
      addFace(builder, camera, relativeCellX, relativeCellZ, Direction.DOWN, false, true, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
  }

  private static void buildExtrudedCell(
    SceneData.Builder builder,
    Camera camera,
    RelativeCameraPos relativePos,
    int x,
    int z,
    float xInCell,
    float zInCell,
    float relativeBottomY,
    int cloudColor,
    float cloudFogEnd,
    RenderMaterial material,
    long cellData
  ) {
    if (relativePos != RelativeCameraPos.BELOW_CLOUDS) {
      addFace(builder, camera, x, z, Direction.UP, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (relativePos != RelativeCameraPos.ABOVE_CLOUDS) {
      addFace(builder, camera, x, z, Direction.DOWN, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (isNorthEmpty(cellData) && z > 0) {
      addFace(builder, camera, x, z, Direction.NORTH, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (isSouthEmpty(cellData) && z < 0) {
      addFace(builder, camera, x, z, Direction.SOUTH, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (isWestEmpty(cellData) && x > 0) {
      addFace(builder, camera, x, z, Direction.WEST, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (isEastEmpty(cellData) && x < 0) {
      addFace(builder, camera, x, z, Direction.EAST, false, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
    }
    if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
      for (var direction : Direction.values()) {
        addFace(builder, camera, x, z, direction, true, false, xInCell, zInCell, relativeBottomY, cloudColor, cloudFogEnd, material);
      }
    }
  }

  private static void addFace(
    SceneData.Builder builder,
    Camera camera,
    int cellX,
    int cellZ,
    Direction direction,
    boolean insideFace,
    boolean useTopColor,
    float xInCell,
    float zInCell,
    float relativeBottomY,
    int cloudColor,
    float cloudFogEnd,
    RenderMaterial material
  ) {
    var vertices = faceVertices(direction);
    var renderVertices = new RenderVertex[4];
    for (var i = 0; i < 4; i++) {
      var vertex = vertices[insideFace ? 3 - i : i];
      var relativeX = (vertex[0] + cellX) * CELL_SIZE - xInCell;
      var relativeY = vertex[1] * CELL_HEIGHT + relativeBottomY;
      var relativeZ = (vertex[2] + cellZ) * CELL_SIZE - zInCell;
      renderVertices[i] = new RenderVertex(
        (float) (camera.eyeX() + relativeX),
        (float) (camera.eyeY() + relativeY),
        (float) (camera.eyeZ() + relativeZ),
        0.0F,
        0.0F,
        cloudVertexColor(cloudColor, useTopColor ? Direction.UP : direction, relativeX, relativeY, relativeZ, cloudFogEnd)
      );
    }

    builder.addCloud(new RenderQuad(renderVertices[0], renderVertices[1], renderVertices[2], renderVertices[3], material));
  }

  private static int cloudVertexColor(int cloudColor, Direction direction, float x, float y, float z, float cloudFogEnd) {
    var shade = faceShade(direction);
    var distance = (float) Math.sqrt(x * x + y * y + z * z);
    var fogAlpha = cloudFogEnd <= 0.0F ? 0.0F : 1.0F - Mth.clamp(distance / cloudFogEnd, 0.0F, 1.0F);
    var alpha = Math.clamp(Math.round(ARGB.alpha(cloudColor) * fogAlpha), 0, 255);
    var red = Math.clamp(Math.round(ARGB.red(cloudColor) * shade), 0, 255);
    var green = Math.clamp(Math.round(ARGB.green(cloudColor) * shade), 0, 255);
    var blue = Math.clamp(Math.round(ARGB.blue(cloudColor) * shade), 0, 255);
    return ARGB.color(alpha, red, green, blue);
  }

  private static float faceShade(Direction direction) {
    return switch (direction) {
      case DOWN -> 0.7F;
      case UP -> 1.0F;
      case NORTH, SOUTH -> 0.8F;
      case WEST, EAST -> 0.9F;
    };
  }

  private static float[][] faceVertices(Direction direction) {
    return switch (direction) {
      case DOWN -> new float[][]{{1, 0, 0}, {1, 0, 1}, {0, 0, 1}, {0, 0, 0}};
      case UP -> new float[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
      case NORTH -> new float[][]{{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}};
      case SOUTH -> new float[][]{{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}};
      case WEST -> new float[][]{{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}};
      case EAST -> new float[][]{{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}};
    };
  }

  private static RenderMaterial cloudMaterial(boolean fancyClouds) {
    return new RenderMaterial(
      WHITE_TEXTURE,
      RendererAssets.AlphaMode.TRANSLUCENT,
      0xFFFFFFFF,
      !fancyClouds,
      0.0F,
      0.0F,
      0.0F,
      0,
      RenderMaterial.AlphaCutoutSource.FINAL_COLOR,
      RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL,
      true,
      RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT),
      ColorTargetState.WRITE_ALL,
      RenderMaterial.UvTransform.IDENTITY,
      false,
      0,
      1.0F
    );
  }

  private static TextureData textureData() {
    var cached = textureData;
    if (cached != null) {
      return cached;
    }

    var loaded = buildTextureData(RendererAssets.instance().texture(CLOUD_TEXTURE));
    textureData = loaded;
    return loaded;
  }

  static TextureData buildTextureData(RendererAssets.TextureImage texture) {
    var image = texture.toBufferedImage();
    var width = image.getWidth();
    var height = image.getHeight();
    if (width <= 0 || height <= 0) {
      return null;
    }

    var cells = new long[width * height];
    for (var z = 0; z < height; z++) {
      for (var x = 0; x < width; x++) {
        var color = image.getRGB(x, z);
        if (isCellEmpty(color)) {
          continue;
        }

        var north = isCellEmpty(image.getRGB(x, Math.floorMod(z - 1, height)));
        var east = isCellEmpty(image.getRGB(Math.floorMod(x + 1, width), z));
        var south = isCellEmpty(image.getRGB(x, Math.floorMod(z + 1, height)));
        var west = isCellEmpty(image.getRGB(Math.floorMod(x - 1, width), z));
        cells[x + z * width] = packCellData(color, north, east, south, west);
      }
    }

    return new TextureData(cells, width, height);
  }

  private static CloudStatus cloudStatus() {
    try {
      return Minecraft.getInstance().options.getCloudStatus();
    } catch (Throwable _) {
      return CloudStatus.FANCY;
    }
  }

  private static int cloudRange(RenderContext ctx) {
    try {
      return Minecraft.getInstance().options.cloudRange().get();
    } catch (Throwable _) {
      return Math.max(2, Mth.ceil(ctx.maxDistance() / 16.0F));
    }
  }

  private static boolean isCellEmpty(int color) {
    return ARGB.alpha(color) < 10;
  }

  private static long packCellData(int color, boolean north, boolean east, boolean south, boolean west) {
    return (long) color << 4 | (north ? 1L : 0L) << 3 | (east ? 1L : 0L) << 2 | (south ? 1L : 0L) << 1 | (west ? 1L : 0L);
  }

  private static boolean isNorthEmpty(long cellData) {
    return (cellData >> 3 & 1L) != 0L;
  }

  private static boolean isEastEmpty(long cellData) {
    return (cellData >> 2 & 1L) != 0L;
  }

  private static boolean isSouthEmpty(long cellData) {
    return (cellData >> 1 & 1L) != 0L;
  }

  private static boolean isWestEmpty(long cellData) {
    return (cellData & 1L) != 0L;
  }

  enum RelativeCameraPos {
    ABOVE_CLOUDS,
    INSIDE_CLOUDS,
    BELOW_CLOUDS
  }

  record TextureData(long[] cells, int width, int height) {
    TextureData {
      cells = Arrays.copyOf(cells, cells.length);
    }
  }
}
