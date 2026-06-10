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
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/// Builds raster-ready world meshes from the loaded chunk sections around the camera.
@UtilityClass
public class WorldMeshCollector {
  private static final int WHITE = 0xFFFFFFFF;

  public static SceneData collect(RenderContext ctx) {
    var builder = SceneData.builder();
    var level = ctx.level();
    var camera = ctx.camera();
    var trace = RenderDebugTrace.current();
    var chunkRadius = Mth.ceil(ctx.maxDistance() / 16.0) + 1;
    var centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(camera.eyeX()));
    var centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(camera.eyeZ()));
    var sectionMargin = 16.0;
    var probeY = Mth.floor(camera.eyeY());

    for (var chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
      for (var chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
        trace.chunkConsidered();
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

        trace.chunkLoaded();
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        for (var sectionY = chunk.getMinSectionY(); sectionY <= chunk.getMaxSectionY(); sectionY++) {
          var sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
          LevelChunkSection section = chunk.getSection(sectionIndex);
          if (section.hasOnlyAir()) {
            continue;
          }

          var minX = chunkX << 4;
          var minY = SectionPos.sectionToBlockCoord(sectionY);
          var minZ = chunkZ << 4;
          var maxX = minX + 16.0;
          var maxY = minY + 16.0;
          var maxZ = minZ + 16.0;
          if (!camera.isVisibleAabb(minX, minY, minZ, maxX, maxY, maxZ)) {
            continue;
          }

          trace.sectionVisible();
          var currentSectionY = sectionY;
          var currentSection = section;
          builder.addAll(ctx.sectionMeshCache().getOrBuild(chunk, currentSectionY, ctx.animationTick(), () -> buildSectionMesh(ctx, chunk, currentSectionY, currentSection)));
        }
      }
    }

    return builder.build();
  }

  private static SceneData buildSectionMesh(RenderContext ctx, LevelChunk chunk, int sectionY, LevelChunkSection section) {
    var trace = RenderDebugTrace.current();
    trace.sectionMeshed();
    var builder = SceneData.builder();
    var level = ctx.level();
    var assets = RendererAssets.instance();
    var fluidRenderer = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
    var originX = chunk.getPos().getMinBlockX();
    var originY = SectionPos.sectionToBlockCoord(sectionY);
    var originZ = chunk.getPos().getMinBlockZ();
    var blockPos = new BlockPos.MutableBlockPos();

    for (var localY = 0; localY < 16; localY++) {
      for (var localZ = 0; localZ < 16; localZ++) {
        for (var localX = 0; localX < 16; localX++) {
          blockPos.set(originX + localX, originY + localY, originZ + localZ);
          var blockState = section.getBlockState(localX, localY, localZ);
          var fluidState = blockState.getFluidState();
          var pureFluidBlock = !fluidState.isEmpty() && blockState.getBlock() == fluidState.createLegacyBlock().getBlock();
          if (!pureFluidBlock && !blockState.isAir() && blockState.getBlock() != Blocks.VOID_AIR && !shouldSkipStaticBlockGeometry(ctx, blockPos)) {
            for (var face : assets.blockGeometry(blockState).faces()) {
              if (!shouldEmitBlockFace(level, blockState, blockPos, face)) {
                continue;
              }
              builder.add(
                toRenderQuad(
                  face,
                  originX + localX,
                  originY + localY,
                  originZ + localZ,
                  LightingCalculator.faceColor(ctx, face, blockState, blockPos),
                  false,
                  0.0F
                )
              );
              trace.blockQuads(1L);
            }
          }

          if (!fluidState.isEmpty()) {
            builder.addAll(VanillaSubmitCollector.collectFluid(ctx, fluidRenderer, blockPos.immutable(), blockState, fluidState));
          }
        }
      }
    }

    return builder.build();
  }

  private static boolean shouldSkipStaticBlockGeometry(RenderContext ctx, BlockPos blockPos) {
    return ctx.vanillaRenderedBlockEntities().contains(blockPos.asLong());
  }

  private static boolean shouldEmitBlockFace(
    BlockGetter level,
    BlockState blockState,
    BlockPos blockPos,
    RendererAssets.GeometryFace face
  ) {
    var direction = face.cullDirection();
    if (direction == null) {
      return true;
    }

    var neighborState = level.getBlockState(blockPos.relative(direction));
    return Block.shouldRenderFace(blockState, neighborState, direction);
  }

  static RenderQuad toRenderQuad(
    RendererAssets.GeometryFace face,
    double offsetX,
    double offsetY,
    double offsetZ,
    int color,
    boolean doubleSided,
    float depthBias
  ) {
    return toRenderQuad(
      face,
      offsetX,
      offsetY,
      offsetZ,
      color,
      doubleSided,
      depthBias,
      RenderMaterial.defaultAlphaCutoutThreshold(face.alphaMode())
    );
  }

  static RenderQuad toRenderQuad(
    RendererAssets.GeometryFace face,
    double offsetX,
    double offsetY,
    double offsetZ,
    int color,
    boolean doubleSided,
    float depthBias,
    int alphaCutoutThreshold
  ) {
    return new RenderQuad(
      vertex((float) (face.x()[0] + offsetX), (float) (face.y()[0] + offsetY), (float) (face.z()[0] + offsetZ), face.uv()[0], face.uv()[1]),
      vertex((float) (face.x()[1] + offsetX), (float) (face.y()[1] + offsetY), (float) (face.z()[1] + offsetZ), face.uv()[2], face.uv()[3]),
      vertex((float) (face.x()[2] + offsetX), (float) (face.y()[2] + offsetY), (float) (face.z()[2] + offsetZ), face.uv()[4], face.uv()[5]),
      vertex((float) (face.x()[3] + offsetX), (float) (face.y()[3] + offsetY), (float) (face.z()[3] + offsetZ), face.uv()[6], face.uv()[7]),
      RenderMaterial.create(face.texture(), face.alphaMode(), color, doubleSided, depthBias, alphaCutoutThreshold)
    );
  }

  private static RenderVertex vertex(float x, float y, float z, float u, float v) {
    return new RenderVertex(x, y, z, u, v, WHITE);
  }
}
