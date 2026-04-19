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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.List;

/// Builds raster-ready world meshes from the loaded chunk sections around the camera.
@UtilityClass
public class WorldMeshCollector {

  public static SceneData collect(RenderContext ctx) {
    var builder = SceneData.builder();
    var level = ctx.level();
    var camera = ctx.camera();
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

          var currentSectionY = sectionY;
          var currentSection = section;
          builder.addAll(ctx.sectionMeshCache().getOrBuild(chunk, currentSectionY, ctx.animationTick(), () -> buildSectionMesh(ctx, chunk, currentSectionY, currentSection)));
        }
      }
    }

    return builder.build();
  }

  private static SceneData buildSectionMesh(RenderContext ctx, LevelChunk chunk, int sectionY, LevelChunkSection section) {
    var builder = SceneData.builder();
    var level = ctx.level();
    var assets = RendererAssets.instance();
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
          if (!pureFluidBlock && !blockState.isAir() && blockState.getBlock() != Blocks.VOID_AIR) {
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
            }
          }

          if (!fluidState.isEmpty()) {
            emitFluidFaces(ctx, builder, blockState, fluidState, blockPos);
          }
        }
      }
    }

    return builder.build();
  }

  private static boolean shouldEmitBlockFace(
    net.minecraft.world.level.BlockGetter level,
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

  private static void emitFluidFaces(
    RenderContext ctx,
    SceneData.Builder builder,
    BlockState blockState,
    FluidState fluidState,
    BlockPos blockPos
  ) {
    var fluid = RendererAssets.instance().fluidGeometry(fluidState, blockPos, blockState);
    if (fluid == RendererAssets.FluidGeometry.EMPTY || fluid.surfaceHeight() <= 0.0F) {
      return;
    }

    var tintIndex = fluidState.is(net.minecraft.tags.FluidTags.WATER) ? 0 : -1;
    var topFace = RendererAssets.GeometryFace.of(
      new org.joml.Vector3f[]{
        new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 0.0F),
        new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 1.0F),
        new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 1.0F),
        new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 0.0F)
      },
      new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
      fluid.stillTexture(),
      fluid.alphaMode(),
      Direction.UP,
      tintIndex,
      fluid.emission(),
      false
    );
    addFluidFace(ctx, builder, topFace, blockState, blockPos);

    for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
      var neighborPos = blockPos.relative(direction);
      var neighborFluid = ctx.level().getFluidState(neighborPos);
      if (!neighborFluid.isEmpty()
        && neighborFluid.getType().isSame(fluidState.getType())
        && neighborFluid.getHeight(ctx.level(), neighborPos) >= fluid.surfaceHeight() - 0.02F) {
        continue;
      }

      RendererAssets.GeometryFace sideFace = switch (direction) {
        case NORTH -> RendererAssets.GeometryFace.of(
          new org.joml.Vector3f[]{
            new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 0.0F),
            new org.joml.Vector3f(1.0F, 0.0F, 0.0F),
            new org.joml.Vector3f(0.0F, 0.0F, 0.0F),
            new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 0.0F)
          },
          new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
          fluid.flowTexture(),
          fluid.alphaMode(),
          Direction.NORTH,
          tintIndex,
          fluid.emission(),
          false
        );
        case SOUTH -> RendererAssets.GeometryFace.of(
          new org.joml.Vector3f[]{
            new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 1.0F),
            new org.joml.Vector3f(0.0F, 0.0F, 1.0F),
            new org.joml.Vector3f(1.0F, 0.0F, 1.0F),
            new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 1.0F)
          },
          new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
          fluid.flowTexture(),
          fluid.alphaMode(),
          Direction.SOUTH,
          tintIndex,
          fluid.emission(),
          false
        );
        case WEST -> RendererAssets.GeometryFace.of(
          new org.joml.Vector3f[]{
            new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 0.0F),
            new org.joml.Vector3f(0.0F, 0.0F, 0.0F),
            new org.joml.Vector3f(0.0F, 0.0F, 1.0F),
            new org.joml.Vector3f(0.0F, fluid.surfaceHeight(), 1.0F)
          },
          new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
          fluid.flowTexture(),
          fluid.alphaMode(),
          Direction.WEST,
          tintIndex,
          fluid.emission(),
          false
        );
        case EAST -> RendererAssets.GeometryFace.of(
          new org.joml.Vector3f[]{
            new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 1.0F),
            new org.joml.Vector3f(1.0F, 0.0F, 1.0F),
            new org.joml.Vector3f(1.0F, 0.0F, 0.0F),
            new org.joml.Vector3f(1.0F, fluid.surfaceHeight(), 0.0F)
          },
          new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
          fluid.flowTexture(),
          fluid.alphaMode(),
          Direction.EAST,
          tintIndex,
          fluid.emission(),
          false
        );
        default -> null;
      };
      if (sideFace != null) {
        addFluidFace(ctx, builder, sideFace, blockState, blockPos);
      }
    }
  }

  private static void addFluidFace(
    RenderContext ctx,
    SceneData.Builder builder,
    RendererAssets.GeometryFace face,
    BlockState blockState,
    BlockPos blockPos
  ) {
    var color = LightingCalculator.faceColor(ctx, face, blockState, blockPos);
    if (blockState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
      color = LightingCalculator.brighten(color, 1.1F);
    } else {
      color = LightingCalculator.brighten(color, 0.85F);
    }
    builder.add(toRenderQuad(face, blockPos.getX(), blockPos.getY(), blockPos.getZ(), color, false, 0.0F));
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
    return new RenderQuad(
      new RenderVertex((float) (face.x()[0] + offsetX), (float) (face.y()[0] + offsetY), (float) (face.z()[0] + offsetZ), face.uv()[0], face.uv()[1]),
      new RenderVertex((float) (face.x()[1] + offsetX), (float) (face.y()[1] + offsetY), (float) (face.z()[1] + offsetZ), face.uv()[2], face.uv()[3]),
      new RenderVertex((float) (face.x()[2] + offsetX), (float) (face.y()[2] + offsetY), (float) (face.z()[2] + offsetZ), face.uv()[4], face.uv()[5]),
      new RenderVertex((float) (face.x()[3] + offsetX), (float) (face.y()[3] + offsetY), (float) (face.z()[3] + offsetZ), face.uv()[6], face.uv()[7]),
      face.texture(),
      face.alphaMode(),
      color,
      doubleSided,
      depthBias
    );
  }
}
