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

import com.mojang.blaze3d.vertex.QuadInstance;
import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
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
    var assets = RendererAssets.instance();
    var minecraft = Minecraft.getInstance();
    assets.ensureVanillaColorMapsLoaded();
    var modelSet = minecraft.getModelManager().getBlockStateModelSet();
    var ambientOcclusion = minecraft.options.ambientOcclusion().get();
    var cutoutLeaves = minecraft.options.cutoutLeaves().get();
    var blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, minecraft.getBlockColors());
    var fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
    var originX = chunk.getPos().getMinBlockX();
    var originY = SectionPos.sectionToBlockCoord(sectionY);
    var originZ = chunk.getPos().getMinBlockZ();
    var blockPos = new BlockPos.MutableBlockPos();

    BlockModelLighter.enableCaching();
    try {
      for (var localY = 0; localY < 16; localY++) {
        for (var localZ = 0; localZ < 16; localZ++) {
          for (var localX = 0; localX < 16; localX++) {
            blockPos.set(originX + localX, originY + localY, originZ + localZ);
            var blockState = section.getBlockState(localX, localY, localZ);
            if (blockState.isAir()) {
              continue;
            }

            var fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty()) {
              builder.addTerrainAll(VanillaSubmitCollector.collectFluid(ctx, fluidRenderer, blockPos.immutable(), blockState, fluidState));
            }

            if (blockState.getRenderShape() == RenderShape.MODEL) {
              emitBlockModel(
                ctx,
                builder,
                blockRenderer,
                modelSet,
                blockState,
                blockPos,
                originX + localX,
                originY + localY,
                originZ + localZ,
                cutoutLeaves,
                trace
              );
            }
          }
        }
      }
    } finally {
      BlockModelLighter.clearCache();
    }

    return builder.build();
  }

  private static void emitBlockModel(
    RenderContext ctx,
    SceneData.Builder builder,
    ModelBlockRenderer blockRenderer,
    BlockStateModelSet modelSet,
    BlockState blockState,
    BlockPos blockPos,
    int x,
    int y,
    int z,
    boolean cutoutLeaves,
    RenderDebugTrace trace
  ) {
    BlockQuadOutput output = (quadX, quadY, quadZ, quad, instance) -> {
      var materialInfo = quad.materialInfo();
      var layer = materialInfo != null ? materialInfo.layer() : ChunkSectionLayer.SOLID;
      putTerrainBlockQuad(ctx, builder, quadX, quadY, quadZ, quad, instance, layer, trace);
    };
    BlockQuadOutput solidOutput = (quadX, quadY, quadZ, quad, instance) ->
      putTerrainBlockQuad(ctx, builder, quadX, quadY, quadZ, quad, instance, ChunkSectionLayer.SOLID, trace);
    var blockOutput = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? solidOutput : output;
    blockRenderer.tesselateBlock(blockOutput, x, y, z, ctx.level(), blockPos, blockState, modelSet.get(blockState), blockState.getSeed(blockPos));
  }

  private static void putTerrainBlockQuad(
    RenderContext ctx,
    SceneData.Builder builder,
    float x,
    float y,
    float z,
    BakedQuad quad,
    QuadInstance instance,
    ChunkSectionLayer layer,
    RenderDebugTrace trace
  ) {
    if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null || quad.materialInfo().sprite().contents() == null) {
      return;
    }

    var materialInfo = quad.materialInfo();
    var sprite = materialInfo.sprite();
    var texture = RendererAssets.instance().terrainTexture(sprite);
    var alphaMode = RendererAssets.alphaModeForVanillaLayer(layer);
    var material = RenderMaterial
      .create(texture, alphaMode, 0xFFFFFFFF, false, 0.0F, RenderMaterial.defaultAlphaCutoutThreshold(alphaMode))
      .withPipelineState(layer.pipeline());
    var vertices = new RenderVertex[4];
    for (var i = 0; i < 4; i++) {
      var position = quad.position(i);
      if (position == null) {
        return;
      }

      var packedUv = quad.packedUV(i);
      var color = instance.getColor(i);
      vertices[i] = new RenderVertex(
        position.x() + x,
        position.y() + y,
        position.z() + z,
        UVPair.unpackU(packedUv),
        UVPair.unpackV(packedUv),
        color
      ).withLightColor(VanillaLightmap.color(ctx, instance.getLightCoords(i), materialInfo.lightEmission()));
    }

    builder.addTerrain(new RenderQuad(vertices[0], vertices[1], vertices[2], vertices[3], material));
    trace.blockQuads(1L);
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
      RenderMaterial.defaultAlphaCutoutThreshold(face.alphaMode()),
      false
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
    return toRenderQuad(face, offsetX, offsetY, offsetZ, color, doubleSided, depthBias, alphaCutoutThreshold, false);
  }

  static RenderQuad toTerrainRenderQuad(
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
      RenderMaterial.defaultAlphaCutoutThreshold(face.alphaMode()),
      true
    );
  }

  private static RenderQuad toRenderQuad(
    RendererAssets.GeometryFace face,
    double offsetX,
    double offsetY,
    double offsetZ,
    int color,
    boolean doubleSided,
    float depthBias,
    int alphaCutoutThreshold,
    boolean applyLayerState
  ) {
    var material = RenderMaterial.create(face.texture(), face.alphaMode(), color, doubleSided, depthBias, alphaCutoutThreshold);
    if (applyLayerState && face.layer() != null) {
      material = material.withPipelineState(face.layer().pipeline());
    }

    return new RenderQuad(
      vertex((float) (face.x()[0] + offsetX), (float) (face.y()[0] + offsetY), (float) (face.z()[0] + offsetZ), face.uv()[0], face.uv()[1]),
      vertex((float) (face.x()[1] + offsetX), (float) (face.y()[1] + offsetY), (float) (face.z()[1] + offsetZ), face.uv()[2], face.uv()[3]),
      vertex((float) (face.x()[2] + offsetX), (float) (face.y()[2] + offsetY), (float) (face.z()[2] + offsetZ), face.uv()[4], face.uv()[5]),
      vertex((float) (face.x()[3] + offsetX), (float) (face.y()[3] + offsetY), (float) (face.z()[3] + offsetZ), face.uv()[6], face.uv()[7]),
      material
    );
  }

  private static RenderVertex vertex(float x, float y, float z, float u, float v) {
    return new RenderVertex(x, y, z, u, v, WHITE);
  }
}
