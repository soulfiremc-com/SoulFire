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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/// Shared lighting and color modulation helpers for the raster pipeline.
public final class LightingCalculator {
  private LightingCalculator() {}

  public static int faceColor(
    RenderContext ctx,
    RendererAssets.GeometryFace face,
    @Nullable BlockState blockState,
    @Nullable BlockPos blockPos
  ) {
    var color = 0xFFFFFFFF;
    if (blockState != null && blockPos != null && face.tintIndex() >= 0) {
      color = tint(color, RendererAssets.instance().resolveTint(ctx.level(), blockPos, blockState, face.tintIndex()));
    }

    if (blockPos == null || blockState == null) {
      return color;
    }

    var normal = faceNormal(face);
    var light = lighting(ctx, blockPos, normal.x, normal.y, normal.z, face.emission(), face.shade(), blockState);
    return brighten(color, light);
  }

  public static int emissiveColor(int tintColor, int emission) {
    if (emission <= 0) {
      return tintColor;
    }
    return brighten(tintColor, 0.35F + emission / 15.0F * 0.65F);
  }

  public static Vector3f faceNormal(RendererAssets.GeometryFace face) {
    var edgeAX = face.x()[1] - face.x()[0];
    var edgeAY = face.y()[1] - face.y()[0];
    var edgeAZ = face.z()[1] - face.z()[0];
    var edgeBX = face.x()[3] - face.x()[0];
    var edgeBY = face.y()[3] - face.y()[0];
    var edgeBZ = face.z()[3] - face.z()[0];
    var normal = new Vector3f(
      (float) (edgeAY * edgeBZ - edgeAZ * edgeBY),
      (float) (edgeAZ * edgeBX - edgeAX * edgeBZ),
      (float) (edgeAX * edgeBY - edgeAY * edgeBX)
    );
    if (normal.lengthSquared() < 1.0E-8F) {
      return new Vector3f(0.0F, 1.0F, 0.0F);
    }
    return normal.normalize();
  }

  private static float lighting(
    RenderContext ctx,
    BlockPos blockPos,
    double normalX,
    double normalY,
    double normalZ,
    int emission,
    boolean shade,
    BlockState blockState
  ) {
    if (emission > 0) {
      return 0.65F + emission / 15.0F * 0.5F;
    }

    var key = blockPos.asLong();
    var base = ctx.localLightCache().computeIfAbsent(key, _ -> {
      var skyFactor = ctx.level().canSeeSky(blockPos.above()) ? 1.0F : 0.55F;
      skyFactor *= 1.0F - ctx.level().getRainLevel(1.0F) * 0.15F;
      skyFactor *= 1.0F - ctx.level().getThunderLevel(1.0F) * 0.2F;
      var localEmission = 0;
      for (var direction : Direction.values()) {
        localEmission = Math.max(localEmission, ctx.level().getBlockState(blockPos.relative(direction)).getLightEmission());
      }
      var occlusion = blockState.propagatesSkylightDown() ? 0.0F : 0.08F;
      return 0.32F + skyFactor * 0.48F + localEmission / 15.0F * 0.45F - occlusion;
    });

    var dominant = Math.max(Math.abs(normalX), Math.max(Math.abs(normalY), Math.abs(normalZ)));
    var directional = shade
      ? (float) (
      Math.abs(normalY) == dominant
        ? normalY > 0.5 ? 1.0 : 0.62
        : Math.abs(normalZ) == dominant ? 0.88 : 0.78
    )
      : 1.0F;
    var ao = ambientOcclusion(ctx, blockPos, normalX, normalY, normalZ);
    return Mth.clamp(base * directional * ao, 0.18F, 1.35F);
  }

  private static float ambientOcclusion(RenderContext ctx, BlockPos pos, double normalX, double normalY, double normalZ) {
    var blocked = 0;
    if (Math.abs(normalY) >= Math.abs(normalX) && Math.abs(normalY) >= Math.abs(normalZ)) {
      for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
        if (!ctx.level().getBlockState(pos.relative(direction)).isAir()) {
          blocked++;
        }
      }
      if (!ctx.level().getBlockState(pos.relative(normalY > 0 ? Direction.UP : Direction.DOWN)).isAir()) {
        blocked += 2;
      }
    } else if (Math.abs(normalX) >= Math.abs(normalZ)) {
      for (var direction : List.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH)) {
        if (!ctx.level().getBlockState(pos.relative(direction)).isAir()) {
          blocked++;
        }
      }
      if (!ctx.level().getBlockState(pos.relative(normalX > 0 ? Direction.EAST : Direction.WEST)).isAir()) {
        blocked += 2;
      }
    } else {
      for (var direction : List.of(Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST)) {
        if (!ctx.level().getBlockState(pos.relative(direction)).isAir()) {
          blocked++;
        }
      }
      if (!ctx.level().getBlockState(pos.relative(normalZ > 0 ? Direction.SOUTH : Direction.NORTH)).isAir()) {
        blocked += 2;
      }
    }

    return Math.max(0.52F, 1.0F - blocked * 0.06F);
  }

  public static int tint(int color, int tint) {
    var a = (color >>> 24) & 0xFF;
    var r = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
    var g = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
    var b = (color & 0xFF) * (tint & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  public static int brighten(int color, float factor) {
    var a = (color >>> 24) & 0xFF;
    var r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * factor)));
    var g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * factor)));
    var b = Math.min(255, Math.max(0, (int) ((color & 0xFF) * factor)));
    return (a << 24) | (r << 16) | (g << 8) | b;
  }
}
