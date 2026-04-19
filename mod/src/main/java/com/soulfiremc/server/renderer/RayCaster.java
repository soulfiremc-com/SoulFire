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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

@UtilityClass
public class RayCaster {
  private static final int MAX_LAYERS = 12;
  private static final double EPSILON = 1.0E-4;

  public static int castRay(RenderContext ctx, double dirX, double dirY, double dirZ) {
    var originX = ctx.camera().eyeX();
    var originY = ctx.camera().eyeY();
    var originZ = ctx.camera().eyeZ();
    var remainingDistance = ctx.maxDistance();
    var outR = 0.0F;
    var outG = 0.0F;
    var outB = 0.0F;
    var outA = 0.0F;

    for (var layer = 0; layer < MAX_LAYERS && remainingDistance > EPSILON && outA < 0.995F; layer++) {
      var hit = traceNearest(ctx, originX, originY, originZ, dirX, dirY, dirZ, remainingDistance);
      if (hit == null) {
        var sky = SkyRenderer.sampleSky(ctx, dirX, dirY, dirZ);
        return compositeFinal(outR, outG, outB, outA, sky);
      }

      var alpha = ((hit.argb >>> 24) & 0xFF) / 255.0F;
      if (hit.alphaMode == RendererAssets.AlphaMode.CUTOUT) {
        if (alpha < 0.2F) {
          var travel = hit.distance + EPSILON;
          originX += dirX * travel;
          originY += dirY * travel;
          originZ += dirZ * travel;
          remainingDistance -= travel;
          continue;
        }
        alpha = 1.0F;
      }

      if (hit.alphaMode == RendererAssets.AlphaMode.OPAQUE) {
        alpha = 1.0F;
      }

      var inv = 1.0F - outA;
      outR += inv * alpha * ((hit.argb >> 16) & 0xFF) / 255.0F;
      outG += inv * alpha * ((hit.argb >> 8) & 0xFF) / 255.0F;
      outB += inv * alpha * (hit.argb & 0xFF) / 255.0F;
      outA += inv * alpha;

      if (hit.alphaMode == RendererAssets.AlphaMode.OPAQUE || outA >= 0.995F) {
        break;
      }

      var travel = hit.distance + EPSILON;
      originX += dirX * travel;
      originY += dirY * travel;
      originZ += dirZ * travel;
      remainingDistance -= travel;
    }

    return compositeFinal(outR, outG, outB, outA, SkyRenderer.sampleSky(ctx, dirX, dirY, dirZ));
  }

  public static double fastInvSqrt(double x) {
    var xhalf = 0.5 * x;
    var i = Double.doubleToLongBits(x);
    i = 0x5fe6eb50c7b537a9L - (i >> 1);
    x = Double.longBitsToDouble(i);
    x *= 1.5 - xhalf * x * x;
    return x;
  }

  @Nullable
  private static TraceHit traceNearest(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    TraceHit best = traceWorld(ctx, originX, originY, originZ, dirX, dirY, dirZ, maxDistance);
    best = min(best, traceSurfaces(ctx, originX, originY, originZ, dirX, dirY, dirZ, maxDistance));
    best = min(best, traceBillboards(ctx, originX, originY, originZ, dirX, dirY, dirZ, maxDistance));
    best = min(best, traceShadows(ctx, originX, originY, originZ, dirX, dirY, dirZ, maxDistance));
    return best;
  }

  @Nullable
  private static TraceHit traceWorld(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    var assets = RendererAssets.instance();
    var blockPos = new BlockPos.MutableBlockPos();
    var currentX = Mth.floor(originX);
    var currentY = Mth.floor(originY);
    var currentZ = Mth.floor(originZ);
    var stepX = dirX >= 0 ? 1 : -1;
    var stepY = dirY >= 0 ? 1 : -1;
    var stepZ = dirZ >= 0 ? 1 : -1;
    var invDirX = dirX == 0.0 ? Double.MAX_VALUE : 1.0 / dirX;
    var invDirY = dirY == 0.0 ? Double.MAX_VALUE : 1.0 / dirY;
    var invDirZ = dirZ == 0.0 ? Double.MAX_VALUE : 1.0 / dirZ;
    var deltaX = Math.abs(invDirX);
    var deltaY = Math.abs(invDirY);
    var deltaZ = Math.abs(invDirZ);
    var sideDistX = dirX >= 0 ? (currentX + 1 - originX) * deltaX : (originX - currentX) * deltaX;
    var sideDistY = dirY >= 0 ? (currentY + 1 - originY) * deltaY : (originY - currentY) * deltaY;
    var sideDistZ = dirZ >= 0 ? (currentZ + 1 - originZ) * deltaZ : (originZ - currentZ) * deltaZ;
    var segmentStart = 0.0;

    while (segmentStart <= maxDistance) {
      if (currentY < ctx.minY() || currentY > ctx.maxY()) {
        return new TraceHit(segmentStart, dirY < 0 ? RenderConstants.VOID_COLOR : SkyRenderer.sampleSky(ctx, dirX, dirY, dirZ), RendererAssets.AlphaMode.OPAQUE);
      }

      blockPos.set(currentX, currentY, currentZ);
      var segmentEnd = Math.min(maxDistance, Math.min(sideDistX, Math.min(sideDistY, sideDistZ)));
      var blockState = ctx.level().getBlockState(blockPos);
      var fluidState = blockState.getFluidState();

      TraceHit best = null;
      if (!blockState.isAir() && blockState.getBlock() != Blocks.VOID_AIR) {
        var geometry = assets.blockGeometry(blockState);
        for (var face : geometry.faces()) {
          best = min(best, intersectFace(face, originX, originY, originZ, dirX, dirY, dirZ, currentX, currentY, currentZ, segmentStart, segmentEnd, blockState, blockPos, ctx));
        }
      }

      if (!fluidState.isEmpty()) {
        best = min(best, intersectFluid(ctx, originX, originY, originZ, dirX, dirY, dirZ, segmentStart, segmentEnd, currentX, currentY, currentZ, blockState, fluidState));
      }

      if (best != null) {
        return best;
      }

      if (sideDistX <= sideDistY && sideDistX <= sideDistZ) {
        currentX += stepX;
        segmentStart = sideDistX;
        sideDistX += deltaX;
      } else if (sideDistY <= sideDistZ) {
        currentY += stepY;
        segmentStart = sideDistY;
        sideDistY += deltaY;
      } else {
        currentZ += stepZ;
        segmentStart = sideDistZ;
        sideDistZ += deltaZ;
      }
    }

    return null;
  }

  @Nullable
  private static TraceHit traceSurfaces(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    TraceHit best = null;
    for (var face : ctx.sceneData().surfaces()) {
      best = min(best, intersectFace(face, originX, originY, originZ, dirX, dirY, dirZ, 0, 0, 0, 0.0, maxDistance, null, null, ctx));
    }
    return best;
  }

  @Nullable
  private static TraceHit traceBillboards(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    TraceHit best = null;
    for (var billboard : ctx.sceneData().billboards()) {
      var face = billboardFace(ctx, billboard);
      if (face != null) {
        best = min(best, intersectBillboard(face, billboard, originX, originY, originZ, dirX, dirY, dirZ, maxDistance));
      }
    }
    return best;
  }

  @Nullable
  private static TraceHit traceShadows(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    TraceHit best = null;
    for (var shadow : ctx.sceneData().shadows()) {
      if (!rayBoxIntersects(originX, originY, originZ, dirX, dirY, dirZ, shadow.bounds(), maxDistance)) {
        continue;
      }

      var halfW = shadow.width() * 0.5;
      var halfH = shadow.height() * 0.5;
      var quad = RendererAssets.GeometryFace.of(
        new org.joml.Vector3f[]{
          new org.joml.Vector3f((float) (shadow.centerX() - halfW), (float) shadow.centerY(), (float) (shadow.centerZ() - halfH)),
          new org.joml.Vector3f((float) (shadow.centerX() - halfW), (float) shadow.centerY(), (float) (shadow.centerZ() + halfH)),
          new org.joml.Vector3f((float) (shadow.centerX() + halfW), (float) shadow.centerY(), (float) (shadow.centerZ() + halfH)),
          new org.joml.Vector3f((float) (shadow.centerX() + halfW), (float) shadow.centerY(), (float) (shadow.centerZ() - halfH))
        },
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F},
        RendererAssets.TextureImage.missing(),
        RendererAssets.AlphaMode.TRANSLUCENT,
        -1,
        0,
        false
      );
      var hit = intersectFaceRaw(quad, originX, originY, originZ, dirX, dirY, dirZ, 0.0, maxDistance);
      if (hit != null) {
        var du = hit.u - 0.5F;
        var dv = hit.v - 0.5F;
        var falloff = Math.max(0.0F, 1.0F - (float) Math.sqrt(du * du + dv * dv) * 1.7F) * shadow.strength();
        if (falloff > 0.01F) {
          best = min(best, new TraceHit(hit.distance, ((int) (falloff * 110) << 24), RendererAssets.AlphaMode.TRANSLUCENT));
        }
      }
    }
    return best;
  }

  @Nullable
  private static TraceHit intersectBillboard(
    RendererAssets.GeometryFace face,
    SceneData.BillboardData billboard,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double maxDistance) {

    var hit = intersectFaceRaw(face, originX, originY, originZ, dirX, dirY, dirZ, 0.0, maxDistance);
    if (hit == null) {
      return null;
    }

    var sampled = billboard.texture().sample(hit.u, hit.v, 0L);
    sampled = tint(sampled, billboard.tintColor());
    if (billboard.emission() > 0) {
      sampled = brighten(sampled, 0.35F + billboard.emission() / 15.0F * 0.65F);
    }
    return new TraceHit(hit.distance, sampled, billboard.alphaMode());
  }

  @Nullable
  private static RendererAssets.GeometryFace billboardFace(RenderContext ctx, SceneData.BillboardData billboard) {
    var center = new Vector3d(billboard.centerX(), billboard.centerY(), billboard.centerZ());
    var toCamera = new Vector3d(ctx.camera().eyeX() - center.x, ctx.camera().eyeY() - center.y, ctx.camera().eyeZ() - center.z);
    if (toCamera.lengthSquared() < EPSILON) {
      return null;
    }

    toCamera.normalize();
    Vector3d right;
    Vector3d up;
    if (billboard.mode() == SceneData.BillboardMode.VERTICAL) {
      right = new Vector3d(toCamera.z, 0.0, -toCamera.x);
      if (right.lengthSquared() < EPSILON) {
        right = new Vector3d(1.0, 0.0, 0.0);
      }
      right.normalize();
      up = new Vector3d(0.0, 1.0, 0.0);
    } else {
      right = new Vector3d(ctx.camera().rightX(), 0.0, ctx.camera().rightZ());
      if (right.lengthSquared() < EPSILON) {
        right = new Vector3d(1.0, 0.0, 0.0);
      }
      right.normalize();
      up = new Vector3d(ctx.camera().upX(), ctx.camera().upY(), ctx.camera().upZ()).normalize();
    }

    var halfW = billboard.width() * 0.5;
    var halfH = billboard.height() * 0.5;
    var p0 = new org.joml.Vector3f((float) (center.x - right.x * halfW - up.x * halfH), (float) (center.y - right.y * halfW - up.y * halfH), (float) (center.z - right.z * halfW - up.z * halfH));
    var p1 = new org.joml.Vector3f((float) (center.x - right.x * halfW + up.x * halfH), (float) (center.y - right.y * halfW + up.y * halfH), (float) (center.z - right.z * halfW + up.z * halfH));
    var p2 = new org.joml.Vector3f((float) (center.x + right.x * halfW + up.x * halfH), (float) (center.y + right.y * halfW + up.y * halfH), (float) (center.z + right.z * halfW + up.z * halfH));
    var p3 = new org.joml.Vector3f((float) (center.x + right.x * halfW - up.x * halfH), (float) (center.y + right.y * halfW - up.y * halfH), (float) (center.z + right.z * halfW - up.z * halfH));
    return RendererAssets.GeometryFace.of(
      new org.joml.Vector3f[]{p0, p1, p2, p3},
      new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
      billboard.texture(),
      billboard.alphaMode(),
      -1,
      billboard.emission(),
      false
    );
  }

  @Nullable
  private static TraceHit intersectFluid(
    RenderContext ctx,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double minT,
    double maxT,
    int blockX,
    int blockY,
    int blockZ,
    BlockState blockState,
    FluidState fluidState) {

    var fluid = RendererAssets.instance().fluidGeometry(fluidState, new BlockPos(blockX, blockY, blockZ), blockState);
    if (fluid == RendererAssets.FluidGeometry.EMPTY || fluid.surfaceHeight() <= 0.0F) {
      return null;
    }

    var tintIndex = fluidState.is(net.minecraft.tags.FluidTags.WATER) ? 0 : -1;
    var blockPos = new BlockPos(blockX, blockY, blockZ);
    TraceHit best = null;

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
      tintIndex,
      fluid.emission(),
      false
    );
    best = min(best, intersectFace(topFace, originX, originY, originZ, dirX, dirY, dirZ, blockX, blockY, blockZ, minT, maxT, blockState, blockPos, ctx));

    for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
      var neighbor = ctx.level().getFluidState(blockPos.relative(direction));
      if (!neighbor.isEmpty() && neighbor.getType().isSame(fluidState.getType()) && neighbor.getHeight(ctx.level(), blockPos.relative(direction)) >= fluid.surfaceHeight() - 0.02F) {
        continue;
      }

      var sideFace = switch (direction) {
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
          tintIndex,
          fluid.emission(),
          false
        );
        default -> null;
      };
      if (sideFace != null) {
        best = min(best, intersectFace(sideFace, originX, originY, originZ, dirX, dirY, dirZ, blockX, blockY, blockZ, minT, maxT, blockState, blockPos, ctx));
      }
    }
    if (best == null) {
      return null;
    }
    return new TraceHit(best.distance, brighten(best.argb, fluidState.is(net.minecraft.tags.FluidTags.LAVA) ? 1.1F : 0.85F), fluid.alphaMode());
  }

  @Nullable
  private static TraceHit intersectFace(
    RendererAssets.GeometryFace face,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    int offsetX,
    int offsetY,
    int offsetZ,
    double minT,
    double maxT,
    @Nullable BlockState blockState,
    @Nullable BlockPos blockPos,
    RenderContext ctx) {

    var hit = intersectFaceRaw(face, originX, originY, originZ, dirX, dirY, dirZ, minT, maxT, offsetX, offsetY, offsetZ);
    if (hit == null) {
      return null;
    }

    var sampled = face.texture().sample(hit.u, hit.v, ctx.animationTick());
    if (blockState != null && blockPos != null && face.tintIndex() >= 0) {
      sampled = tint(sampled, RendererAssets.instance().resolveTint(ctx.level(), blockPos, blockState, face.tintIndex()));
    }

    var light = lighting(ctx, blockPos, hit.normalY, face.emission(), face.shade(), blockState);
    sampled = brighten(sampled, light);
    return new TraceHit(hit.distance, sampled, face.alphaMode());
  }

  @Nullable
  private static FaceIntersection intersectFaceRaw(
    RendererAssets.GeometryFace face,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double minT,
    double maxT) {

    return intersectFaceRaw(face, originX, originY, originZ, dirX, dirY, dirZ, minT, maxT, 0, 0, 0);
  }

  @Nullable
  private static FaceIntersection intersectFaceRaw(
    RendererAssets.GeometryFace face,
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double minT,
    double maxT,
    int offsetX,
    int offsetY,
    int offsetZ) {

    var v0 = new Vector3d(face.x()[0] + offsetX, face.y()[0] + offsetY, face.z()[0] + offsetZ);
    var v1 = new Vector3d(face.x()[1] + offsetX, face.y()[1] + offsetY, face.z()[1] + offsetZ);
    var v2 = new Vector3d(face.x()[2] + offsetX, face.y()[2] + offsetY, face.z()[2] + offsetZ);
    var v3 = new Vector3d(face.x()[3] + offsetX, face.y()[3] + offsetY, face.z()[3] + offsetZ);

    var first = intersectTriangle(originX, originY, originZ, dirX, dirY, dirZ, minT, maxT, v0, v1, v2, face.uv(), 0, 1, 2);
    var second = intersectTriangle(originX, originY, originZ, dirX, dirY, dirZ, minT, maxT, v0, v2, v3, face.uv(), 0, 2, 3);
    var chosen = first == null ? second : second == null || first.distance <= second.distance ? first : second;
    if (chosen == null) {
      return null;
    }

    var edgeA = new Vector3d(v1).sub(v0);
    var edgeB = new Vector3d(v3).sub(v0);
    var normal = edgeA.cross(edgeB).normalize();
    return new FaceIntersection(chosen.distance, chosen.u, chosen.v, normal.y);
  }

  @Nullable
  private static TriangleIntersection intersectTriangle(
    double originX,
    double originY,
    double originZ,
    double dirX,
    double dirY,
    double dirZ,
    double minT,
    double maxT,
    Vector3d a,
    Vector3d b,
    Vector3d c,
    float[] uv,
    int uvA,
    int uvB,
    int uvC) {

    var edge1 = new Vector3d(b).sub(a);
    var edge2 = new Vector3d(c).sub(a);
    var p = new Vector3d(dirX, dirY, dirZ).cross(edge2);
    var det = edge1.dot(p);
    if (Math.abs(det) < EPSILON) {
      return null;
    }

    var invDet = 1.0 / det;
    var t = new Vector3d(originX, originY, originZ).sub(a);
    var u = t.dot(p) * invDet;
    if (u < -EPSILON || u > 1.0 + EPSILON) {
      return null;
    }

    var q = new Vector3d(t).cross(edge1);
    var v = new Vector3d(dirX, dirY, dirZ).dot(q) * invDet;
    if (v < -EPSILON || u + v > 1.0 + EPSILON) {
      return null;
    }

    var distance = edge2.dot(q) * invDet;
    if (distance < minT || distance > maxT) {
      return null;
    }

    var w = 1.0 - u - v;
    var sampleU = (float) (uv[uvA * 2] * w + uv[uvB * 2] * u + uv[uvC * 2] * v);
    var sampleV = (float) (uv[uvA * 2 + 1] * w + uv[uvB * 2 + 1] * u + uv[uvC * 2 + 1] * v);
    return new TriangleIntersection(distance, sampleU, sampleV);
  }

  private static float lighting(
    RenderContext ctx,
    @Nullable BlockPos blockPos,
    double normalY,
    int emission,
    boolean shade,
    @Nullable BlockState blockState) {

    if (emission > 0) {
      return 0.65F + emission / 15.0F * 0.5F;
    }

    if (blockPos == null) {
      return 1.0F;
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
      var occlusion = blockState != null && blockState.propagatesSkylightDown() ? 0.0F : 0.08F;
      return 0.32F + skyFactor * 0.48F + localEmission / 15.0F * 0.45F - occlusion;
    });

    var directional = shade ? (float) (normalY > 0.5 ? 1.0 : normalY < -0.5 ? 0.62 : 0.8) : 1.0F;
    var ao = ambientOcclusion(ctx, blockPos, normalY);
    return Mth.clamp(base * directional * ao, 0.18F, 1.35F);
  }

  private static float ambientOcclusion(RenderContext ctx, BlockPos pos, double normalY) {
    var blocked = 0;
    if (Math.abs(normalY) > 0.5) {
      for (var direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
        if (!ctx.level().getBlockState(pos.relative(direction)).isAir()) {
          blocked++;
        }
      }
    } else {
      if (!ctx.level().getBlockState(pos.above()).isAir()) {
        blocked++;
      }
      if (!ctx.level().getBlockState(pos.below()).isAir()) {
        blocked++;
      }
    }

    return 1.0F - blocked * 0.08F;
  }

  private static boolean rayBoxIntersects(double originX, double originY, double originZ, double dirX, double dirY, double dirZ, net.minecraft.world.phys.AABB box, double maxDistance) {
    var invDirX = dirX == 0.0 ? Double.MAX_VALUE : 1.0 / dirX;
    var invDirY = dirY == 0.0 ? Double.MAX_VALUE : 1.0 / dirY;
    var invDirZ = dirZ == 0.0 ? Double.MAX_VALUE : 1.0 / dirZ;
    var t1 = (box.minX - originX) * invDirX;
    var t2 = (box.maxX - originX) * invDirX;
    var t3 = (box.minY - originY) * invDirY;
    var t4 = (box.maxY - originY) * invDirY;
    var t5 = (box.minZ - originZ) * invDirZ;
    var t6 = (box.maxZ - originZ) * invDirZ;
    var tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
    var tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));
    return !(tMax < 0.0 || tMin > tMax || tMin > maxDistance);
  }

  private static int compositeFinal(float outR, float outG, float outB, float outA, int background) {
    var bgR = ((background >> 16) & 0xFF) / 255.0F;
    var bgG = ((background >> 8) & 0xFF) / 255.0F;
    var bgB = (background & 0xFF) / 255.0F;
    var inv = 1.0F - outA;
    return pack(outR + bgR * inv, outG + bgG * inv, outB + bgB * inv);
  }

  private static int pack(float r, float g, float b) {
    return 0xFF000000
      | (Math.min(255, Math.max(0, (int) (r * 255.0F))) << 16)
      | (Math.min(255, Math.max(0, (int) (g * 255.0F))) << 8)
      | Math.min(255, Math.max(0, (int) (b * 255.0F)));
  }

  private static int tint(int color, int tint) {
    var a = (color >>> 24) & 0xFF;
    var r = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
    var g = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
    var b = (color & 0xFF) * (tint & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private static int brighten(int color, float factor) {
    var a = (color >>> 24) & 0xFF;
    var r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * factor)));
    var g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * factor)));
    var b = Math.min(255, Math.max(0, (int) ((color & 0xFF) * factor)));
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  @Nullable
  private static TraceHit min(@Nullable TraceHit left, @Nullable TraceHit right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.distance <= right.distance ? left : right;
  }

  private record TraceHit(double distance, int argb, RendererAssets.AlphaMode alphaMode) {}

  private record TriangleIntersection(double distance, float u, float v) {}

  private record FaceIntersection(double distance, float u, float v, double normalY) {}
}
