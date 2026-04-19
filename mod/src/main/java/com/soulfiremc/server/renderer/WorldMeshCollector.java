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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
    var level = ctx.level();
    var fluid = RendererAssets.instance().fluidGeometry(fluidState, blockPos, blockState);
    if (fluid == RendererAssets.FluidGeometry.EMPTY || fluid.surfaceHeight() <= 0.0F) {
      return;
    }

    var isLava = fluidState.is(FluidTags.LAVA);
    var belowState = level.getBlockState(blockPos.relative(Direction.DOWN));
    var belowFluid = belowState.getFluidState();
    var aboveState = level.getBlockState(blockPos.relative(Direction.UP));
    var aboveFluid = aboveState.getFluidState();
    var northState = level.getBlockState(blockPos.relative(Direction.NORTH));
    var northFluid = northState.getFluidState();
    var southState = level.getBlockState(blockPos.relative(Direction.SOUTH));
    var southFluid = southState.getFluidState();
    var westState = level.getBlockState(blockPos.relative(Direction.WEST));
    var westFluid = westState.getFluidState();
    var eastState = level.getBlockState(blockPos.relative(Direction.EAST));
    var eastFluid = eastState.getFluidState();

    var renderTop = !isNeighborSameFluid(fluidState, aboveFluid);
    var renderBottom = shouldRenderFluidFace(fluidState, blockState, Direction.DOWN, belowFluid)
      && !isFaceOccludedByNeighbor(Direction.DOWN, 0.8888889F, belowState);
    var renderNorth = shouldRenderFluidFace(fluidState, blockState, Direction.NORTH, northFluid);
    var renderSouth = shouldRenderFluidFace(fluidState, blockState, Direction.SOUTH, southFluid);
    var renderWest = shouldRenderFluidFace(fluidState, blockState, Direction.WEST, westFluid);
    var renderEast = shouldRenderFluidFace(fluidState, blockState, Direction.EAST, eastFluid);
    if (!(renderTop || renderBottom || renderNorth || renderSouth || renderWest || renderEast)) {
      return;
    }

    var fluidType = fluidState.getType();
    var centerHeight = getFluidHeight(level, fluidType, blockPos, blockState, fluidState);
    float northEastHeight;
    float northWestHeight;
    float southEastHeight;
    float southWestHeight;
    if (centerHeight >= 1.0F) {
      northEastHeight = northWestHeight = southEastHeight = southWestHeight = 1.0F;
    } else {
      northEastHeight = calculateAverageHeight(level, fluidType, centerHeight, getFluidHeight(level, fluidType, blockPos.north(), northState, northFluid), getFluidHeight(level, fluidType, blockPos.east(), eastState, eastFluid), blockPos.north().east());
      northWestHeight = calculateAverageHeight(level, fluidType, centerHeight, getFluidHeight(level, fluidType, blockPos.north(), northState, northFluid), getFluidHeight(level, fluidType, blockPos.west(), westState, westFluid), blockPos.north().west());
      southEastHeight = calculateAverageHeight(level, fluidType, centerHeight, getFluidHeight(level, fluidType, blockPos.south(), southState, southFluid), getFluidHeight(level, fluidType, blockPos.east(), eastState, eastFluid), blockPos.south().east());
      southWestHeight = calculateAverageHeight(level, fluidType, centerHeight, getFluidHeight(level, fluidType, blockPos.south(), southState, southFluid), getFluidHeight(level, fluidType, blockPos.west(), westState, westFluid), blockPos.south().west());
    }

    var topInset = 0.001F;
    var bottomInset = renderBottom ? 0.001F : 0.0F;
    if (renderTop && !isFaceOccludedByNeighbor(Direction.UP, Math.min(Math.min(northWestHeight, southWestHeight), Math.min(southEastHeight, northEastHeight)), aboveState)) {
      northWestHeight -= topInset;
      southWestHeight -= topInset;
      southEastHeight -= topInset;
      northEastHeight -= topInset;

      var flow = fluidState.getFlow(level, blockPos);
      var topTexture = flow.x == 0.0 && flow.z == 0.0 ? fluid.stillTexture() : fluid.flowTexture();
      var topUv = topFaceUv(flow);
      var topColor = fluidFaceColor(ctx, fluidState, blockState, blockPos, 1.0F);
      builder.add(new RenderQuad(
        new RenderVertex(blockPos.getX(), blockPos.getY() + northWestHeight, blockPos.getZ(), topUv[0], topUv[1]),
        new RenderVertex(blockPos.getX(), blockPos.getY() + southWestHeight, blockPos.getZ() + 1.0F, topUv[2], topUv[3]),
        new RenderVertex(blockPos.getX() + 1.0F, blockPos.getY() + southEastHeight, blockPos.getZ() + 1.0F, topUv[4], topUv[5]),
        new RenderVertex(blockPos.getX() + 1.0F, blockPos.getY() + northEastHeight, blockPos.getZ(), topUv[6], topUv[7]),
        topTexture,
        fluid.alphaMode(),
        topColor,
        fluidState.shouldRenderBackwardUpFace(level, blockPos.above()),
        0.0F
      ));
    }

    if (renderBottom) {
      var bottomColor = fluidFaceColor(ctx, fluidState, blockState, blockPos, 0.5F);
      builder.add(new RenderQuad(
        new RenderVertex(blockPos.getX(), blockPos.getY() + bottomInset, blockPos.getZ() + 1.0F, 0.0F, 1.0F),
        new RenderVertex(blockPos.getX(), blockPos.getY() + bottomInset, blockPos.getZ(), 0.0F, 0.0F),
        new RenderVertex(blockPos.getX() + 1.0F, blockPos.getY() + bottomInset, blockPos.getZ(), 1.0F, 0.0F),
        new RenderVertex(blockPos.getX() + 1.0F, blockPos.getY() + bottomInset, blockPos.getZ() + 1.0F, 1.0F, 1.0F),
        fluid.stillTexture(),
        fluid.alphaMode(),
        bottomColor,
        false,
        0.0F
      ));
    }

    emitFluidSide(ctx, builder, fluidState, blockState, blockPos, Direction.NORTH, renderNorth, northWestHeight, northEastHeight, 0.0F, 0.0F, 1.0F, 0.0F, 0.8F, isLava, northState);
    emitFluidSide(ctx, builder, fluidState, blockState, blockPos, Direction.SOUTH, renderSouth, southEastHeight, southWestHeight, 1.0F, 1.0F, 0.0F, 1.0F, 0.8F, isLava, southState);
    emitFluidSide(ctx, builder, fluidState, blockState, blockPos, Direction.WEST, renderWest, southWestHeight, northWestHeight, 0.0F, 1.0F, 0.0F, 0.0F, 0.6F, isLava, westState);
    emitFluidSide(ctx, builder, fluidState, blockState, blockPos, Direction.EAST, renderEast, northEastHeight, southEastHeight, 1.0F, 0.0F, 1.0F, 1.0F, 0.6F, isLava, eastState);
  }

  private static void emitFluidSide(
    RenderContext ctx,
    SceneData.Builder builder,
    FluidState fluidState,
    BlockState blockState,
    BlockPos blockPos,
    Direction side,
    boolean shouldRender,
    float leftHeight,
    float rightHeight,
    float leftX,
    float leftZ,
    float rightX,
    float rightZ,
    float shade,
    boolean isLava,
    BlockState neighborState
  ) {
    if (!shouldRender && leftHeight <= 0.0F && rightHeight <= 0.0F) {
      return;
    }
    if (!shouldRender && isFaceOccludedByNeighbor(side, Math.max(leftHeight, rightHeight), neighborState)) {
      return;
    }

    var texture = RendererAssets.instance().fluidGeometry(fluidState, blockPos, blockState).flowTexture();
    if (!isLava) {
      var block = neighborState.getBlock();
      if (block instanceof HalfTransparentBlock || block instanceof LeavesBlock) {
        texture = RendererAssets.instance().waterOverlayTexture();
      }
    }

    var v0 = (1.0F - leftHeight) * 0.5F;
    var v1 = (1.0F - rightHeight) * 0.5F;
    var baseColor = fluidFaceColor(ctx, fluidState, blockState, blockPos, shade);
    builder.add(new RenderQuad(
      new RenderVertex(blockPos.getX() + leftX, blockPos.getY() + leftHeight, blockPos.getZ() + leftZ, 0.0F, v0),
      new RenderVertex(blockPos.getX() + rightX, blockPos.getY() + rightHeight, blockPos.getZ() + rightZ, 0.5F, v1),
      new RenderVertex(blockPos.getX() + rightX, blockPos.getY() + 0.001F, blockPos.getZ() + rightZ, 0.5F, 0.5F),
      new RenderVertex(blockPos.getX() + leftX, blockPos.getY() + 0.001F, blockPos.getZ() + leftZ, 0.0F, 0.5F),
      texture,
      fluidState.is(FluidTags.WATER) ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.OPAQUE,
      baseColor,
      texture == RendererAssets.instance().waterOverlayTexture(),
      0.0F
    ));
  }

  private static int fluidFaceColor(RenderContext ctx, FluidState fluidState, BlockState blockState, BlockPos blockPos, float shade) {
    var tintIndex = fluidState.is(FluidTags.WATER) ? 0 : -1;
    var face = RendererAssets.GeometryFace.of(
      new org.joml.Vector3f[]{
        new org.joml.Vector3f(0.0F, 0.0F, 0.0F),
        new org.joml.Vector3f(0.0F, 0.0F, 1.0F),
        new org.joml.Vector3f(1.0F, 0.0F, 1.0F),
        new org.joml.Vector3f(1.0F, 0.0F, 0.0F)
      },
      new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
      fluidState.is(FluidTags.LAVA) ? RendererAssets.instance().fluidGeometry(fluidState, blockPos, blockState).stillTexture() : RendererAssets.instance().fluidGeometry(fluidState, blockPos, blockState).stillTexture(),
      fluidState.is(FluidTags.WATER) ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.OPAQUE,
      Direction.UP,
      tintIndex,
      fluidState.is(FluidTags.LAVA) ? 15 : 0,
      false
    );
    var color = LightingCalculator.faceColor(ctx, face, blockState, blockPos);
    return LightingCalculator.brighten(color, shade);
  }

  private static float[] topFaceUv(Vec3 flow) {
    if (flow.x == 0.0 && flow.z == 0.0) {
      return new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F};
    }

    var angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
    var sin = Mth.sin(angle) * 0.25F;
    var cos = Mth.cos(angle) * 0.25F;
    return new float[]{
      0.5F + (-cos - sin), 0.5F + (-cos + sin),
      0.5F + (-cos + sin), 0.5F + (cos + sin),
      0.5F + (cos + sin), 0.5F + (cos - sin),
      0.5F + (cos - sin), 0.5F + (-cos - sin)
    };
  }

  private static boolean isNeighborSameFluid(FluidState firstState, FluidState secondState) {
    return secondState.getType().isSame(firstState.getType());
  }

  private static boolean isFaceOccludedByState(Direction face, float height, BlockState state) {
    VoxelShape shape = state.getFaceOcclusionShape(face.getOpposite());
    if (shape == Shapes.empty()) {
      return false;
    }
    if (shape == Shapes.block()) {
      var fullHeight = height == 1.0F;
      return face != Direction.UP || fullHeight;
    }

    VoxelShape fluidShape = Shapes.box(0.0, 0.0, 0.0, 1.0, height, 1.0);
    return Shapes.blockOccludes(fluidShape, shape, face);
  }

  private static boolean isFaceOccludedByNeighbor(Direction face, float height, BlockState state) {
    return isFaceOccludedByState(face, height, state);
  }

  private static boolean isFaceOccludedBySelf(BlockState state, Direction face) {
    return isFaceOccludedByState(face.getOpposite(), 1.0F, state);
  }

  private static boolean shouldRenderFluidFace(FluidState fluidState, BlockState blockState, Direction side, FluidState neighborFluid) {
    return !isFaceOccludedBySelf(blockState, side) && !isNeighborSameFluid(fluidState, neighborFluid);
  }

  private static float calculateAverageHeight(net.minecraft.client.multiplayer.ClientLevel level, Fluid fluid, float currentHeight, float height1, float height2, BlockPos pos) {
    if (height2 >= 1.0F || height1 >= 1.0F) {
      return 1.0F;
    }

    var weightedHeight = new float[2];
    if (height2 > 0.0F || height1 > 0.0F) {
      var cornerHeight = getFluidHeight(level, fluid, pos);
      if (cornerHeight >= 1.0F) {
        return 1.0F;
      }
      addWeightedHeight(weightedHeight, cornerHeight);
    }

    addWeightedHeight(weightedHeight, currentHeight);
    addWeightedHeight(weightedHeight, height2);
    addWeightedHeight(weightedHeight, height1);
    return weightedHeight[0] / weightedHeight[1];
  }

  private static void addWeightedHeight(float[] output, float height) {
    if (height >= 0.8F) {
      output[0] += height * 10.0F;
      output[1] += 10.0F;
    } else if (height >= 0.0F) {
      output[0] += height;
      output[1] += 1.0F;
    }
  }

  private static float getFluidHeight(net.minecraft.client.multiplayer.ClientLevel level, Fluid fluid, BlockPos pos) {
    var state = level.getBlockState(pos);
    return getFluidHeight(level, fluid, pos, state, state.getFluidState());
  }

  private static float getFluidHeight(net.minecraft.client.multiplayer.ClientLevel level, Fluid fluid, BlockPos pos, BlockState blockState, FluidState fluidState) {
    if (fluid.isSame(fluidState.getType())) {
      var aboveState = level.getBlockState(pos.above());
      return fluid.isSame(aboveState.getFluidState().getType()) ? 1.0F : fluidState.getOwnHeight();
    }
    return !blockState.isSolid() ? 0.0F : -1.0F;
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
