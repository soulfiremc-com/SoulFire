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

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/// Shared projected-triangle raster backend for world and GUI item render frontends.
final class SoftwareRasterizer {
  private static final float POLYGON_OFFSET_UNIT_DEPTH = 1.0E-5F;
  private static final float[][] END_PORTAL_COLORS = {
    {0.022087F, 0.098399F, 0.110818F},
    {0.011892F, 0.095924F, 0.089485F},
    {0.027636F, 0.101689F, 0.100326F},
    {0.046564F, 0.109883F, 0.114838F},
    {0.064901F, 0.117696F, 0.097189F},
    {0.063761F, 0.086895F, 0.123646F},
    {0.084817F, 0.111994F, 0.166380F},
    {0.097489F, 0.154120F, 0.091064F},
    {0.106152F, 0.131144F, 0.195191F},
    {0.097721F, 0.110188F, 0.187229F},
    {0.133516F, 0.138278F, 0.148582F},
    {0.070006F, 0.243332F, 0.235792F},
    {0.196766F, 0.142899F, 0.214696F},
    {0.047281F, 0.315338F, 0.321970F},
    {0.204675F, 0.390010F, 0.302066F},
    {0.080955F, 0.314821F, 0.661491F}
  };

  private SoftwareRasterizer() {}

  static void rasterizeWorldTriangle(
    Camera camera,
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    int clipMinX,
    int clipMinY,
    int clipMaxX,
    int clipMaxY,
    RasterFogState fogState
  ) {
    var projection = camera.projectionMatrix();
    rasterizeTriangle(
      animationTick,
      triangle,
      buffers,
      clipMinX,
      clipMinY,
      clipMaxX,
      clipMaxY,
      new Viewport(camera.width(), camera.height(), projection.m22(), projection.m32()),
      fogState,
      RasterFrontend.WORLD,
      false
    );
  }

  static void rasterizeGuiItemTriangle(
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    boolean writeDepth
  ) {
    var width = buffers.image().getWidth();
    var height = buffers.image().getHeight();
    rasterizeTriangle(
      animationTick,
      triangle,
      buffers,
      0,
      0,
      width - 1,
      height - 1,
      new Viewport(width, height, 0.0F, 0.0F),
      RasterFogState.DISABLED,
      RasterFrontend.GUI_ITEM,
      writeDepth
    );
  }

  static void rasterizeScreenTriangle(
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    int clipMinX,
    int clipMinY,
    int clipMaxX,
    int clipMaxY
  ) {
    var width = buffers.image().getWidth();
    var height = buffers.image().getHeight();
    rasterizeTriangle(
      animationTick,
      triangle,
      buffers,
      clipMinX,
      clipMinY,
      clipMaxX,
      clipMaxY,
      new Viewport(width, height, 0.0F, 0.0F),
      RasterFogState.DISABLED,
      RasterFrontend.GUI_SCREEN,
      false
    );
  }

  static int blendStraightAlpha(int dstColor, int srcColor) {
    var dstA = ((dstColor >>> 24) & 0xFF) / 255.0F;
    var srcA = ((srcColor >>> 24) & 0xFF) / 255.0F;
    var outA = srcA + dstA * (1.0F - srcA);
    if (outA <= 0.0F) {
      return 0;
    }

    var dstR = (dstColor >> 16) & 0xFF;
    var dstG = (dstColor >> 8) & 0xFF;
    var dstB = dstColor & 0xFF;
    var srcR = (srcColor >> 16) & 0xFF;
    var srcG = (srcColor >> 8) & 0xFF;
    var srcB = srcColor & 0xFF;

    var outR = Math.round((srcR * srcA + dstR * dstA * (1.0F - srcA)) / outA);
    var outG = Math.round((srcG * srcA + dstG * dstA * (1.0F - srcA)) / outA);
    var outB = Math.round((srcB * srcA + dstB * dstA * (1.0F - srcA)) / outA);
    var outAlpha = Math.round(outA * 255.0F);
    return (outAlpha << 24) | (outR << 16) | (outG << 8) | outB;
  }

  private static void rasterizeTriangle(
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    int clipMinX,
    int clipMinY,
    int clipMaxX,
    int clipMaxY,
    Viewport viewport,
    RasterFogState fogState,
    RasterFrontend frontend,
    boolean guiDepthWrite
  ) {
    var v0 = triangle.v0();
    var v1 = triangle.v1();
    var v2 = triangle.v2();
    var material = triangle.material();
    var fragmentLighting = hasFragmentLighting(v0) || hasFragmentLighting(v1) || hasFragmentLighting(v2);
    var area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
    if (Math.abs(area) < 1.0E-5F) {
      return;
    }
    if (!material.doubleSided() && area <= 0.0F) {
      return;
    }

    var planeHeight = viewport.height();
    var planes = frontend != RasterFrontend.GUI_ITEM ? new AttributePlane[]{
      AttributePlane.of(v0, v1, v2, v0.inverseW(), v1.inverseW(), v2.inverseW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.uOverW(), v1.uOverW(), v2.uOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.vOverW(), v1.vOverW(), v2.vOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.aOverW(), v1.aOverW(), v2.aOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.rOverW(), v1.rOverW(), v2.rOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.gOverW(), v1.gOverW(), v2.gOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.bOverW(), v1.bOverW(), v2.bOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.sphericalFogDistanceOverW(), v1.sphericalFogDistanceOverW(), v2.sphericalFogDistanceOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.cylindricalFogDistanceOverW(), v1.cylindricalFogDistanceOverW(), v2.cylindricalFogDistanceOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.lightROverW(), v1.lightROverW(), v2.lightROverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.lightGOverW(), v1.lightGOverW(), v2.lightGOverW(), planeHeight),
      AttributePlane.of(v0, v1, v2, v0.lightBOverW(), v1.lightBOverW(), v2.lightBOverW(), planeHeight)
    } : null;
    var depthPlane = frontend == RasterFrontend.WORLD
      ? AttributePlane.of(v0, v1, v2, (float) (1.0 - v0.depth()), (float) (1.0 - v1.depth()), (float) (1.0 - v2.depth()), viewport.height()) : null;
    var fragmentDepthBias = frontend == RasterFrontend.WORLD ? fragmentDepthBias(triangle, material) : 0.0F;
    var depthFogProjection = depthFogProjection(viewport, material);
    // GPU raster coverage uses fixed-point subpixel coordinates; interpolation retains the original plane.
    var x0 = (int) Math.rint(v0.x() * 256.0F);
    var y0 = frontend != RasterFrontend.WORLD ? (int) Math.rint(v0.y() * 256.0F)
      : viewport.height() * 256 - (int) Math.rint(v0.interpolationY() * 256.0F);
    var x1 = (int) Math.rint(v1.x() * 256.0F);
    var y1 = frontend != RasterFrontend.WORLD ? (int) Math.rint(v1.y() * 256.0F)
      : viewport.height() * 256 - (int) Math.rint(v1.interpolationY() * 256.0F);
    var x2 = (int) Math.rint(v2.x() * 256.0F);
    var y2 = frontend != RasterFrontend.WORLD ? (int) Math.rint(v2.y() * 256.0F)
      : viewport.height() * 256 - (int) Math.rint(v2.interpolationY() * 256.0F);
    var coverageArea = fixedEdge(x0, y0, x1, y1, x2, y2);
    if (coverageArea == 0) {
      return;
    }
    var positiveArea = coverageArea > 0;
    // Native framebuffer rows run opposite to the output image. Its top edge becomes the image's bottom edge.
    var topLeft0 = positiveArea ? isInclusiveEdge(x1, y1, x2, y2, frontend == RasterFrontend.WORLD) : isInclusiveEdge(x2, y2, x1, y1, frontend == RasterFrontend.WORLD);
    var topLeft1 = positiveArea ? isInclusiveEdge(x2, y2, x0, y0, frontend == RasterFrontend.WORLD) : isInclusiveEdge(x0, y0, x2, y2, frontend == RasterFrontend.WORLD);
    var topLeft2 = positiveArea ? isInclusiveEdge(x0, y0, x1, y1, frontend == RasterFrontend.WORLD) : isInclusiveEdge(x1, y1, x0, y0, frontend == RasterFrontend.WORLD);

    var colorBuffer = buffers.colorBuffer();
    var depthBuffer = buffers.depthBuffer();
    var minX = Math.max(clipMinX, (int) Math.floor(Math.min(v0.x(), Math.min(v1.x(), v2.x()))));
    var minY = Math.max(clipMinY, (int) Math.floor(Math.min(v0.y(), Math.min(v1.y(), v2.y()))));
    var maxX = Math.min(clipMaxX, (int) Math.ceil(Math.max(v0.x(), Math.max(v1.x(), v2.x()))));
    var maxY = Math.min(clipMaxY, (int) Math.ceil(Math.max(v0.y(), Math.max(v1.y(), v2.y()))));
    if (minX > maxX || minY > maxY) {
      return;
    }

    for (var y = minY; y <= maxY; y++) {
      for (var x = minX; x <= maxX; x++) {
        var sampleX = x + 0.5F;
        var sampleY = y + 0.5F;
        var w0 = edge(v1.x(), v1.y(), v2.x(), v2.y(), sampleX, sampleY);
        var w1 = edge(v2.x(), v2.y(), v0.x(), v0.y(), sampleX, sampleY);
        var w2 = edge(v0.x(), v0.y(), v1.x(), v1.y(), sampleX, sampleY);
        if (!isInside(positiveArea, fixedEdge(x1, y1, x2, y2, x * 256L + 128, y * 256L + 128), fixedEdge(x2, y2, x0, y0, x * 256L + 128, y * 256L + 128), fixedEdge(x0, y0, x1, y1, x * 256L + 128, y * 256L + 128), topLeft0, topLeft1, topLeft2)) {
          continue;
        }

        var normalizedW0 = w0 / area;
        var normalizedW1 = w1 / area;
        var normalizedW2 = w2 / area;
        var depth = (depthPlane == null ? Math.fma(normalizedW1, v1.depth() - v0.depth(), Math.fma(normalizedW2, v2.depth() - v0.depth(), v0.depth())) : 1.0 - depthPlane.at(x, y)) + fragmentDepthBias;
        if (frontend == RasterFrontend.WORLD) {
          depth = Math.clamp(depth, 0.0F, 1.0F);
        }
        if (!Double.isFinite(depth)) {
          continue;
        }

        var rasterIndex = y * viewport.width() + x;
        if (!passesDepth(frontend, material, depth, depthBuffer[rasterIndex])) {
          continue;
        }

        var inverseW = planes != null ? planes[0].at(x, y) : Math.fma(normalizedW1, v1.inverseW() - v0.inverseW(), Math.fma(normalizedW2, v2.inverseW() - v0.inverseW(), v0.inverseW()));
        if (!Float.isFinite(inverseW) || Math.abs(inverseW) < 1.0E-8F) {
          continue;
        }

        var u = planes != null ? planes[1].at(x, y) * (1.0F / inverseW) : Math.fma(normalizedW1, v1.uOverW() - v0.uOverW(), Math.fma(normalizedW2, v2.uOverW() - v0.uOverW(), v0.uOverW())) / inverseW;
        var v = planes != null ? planes[2].at(x, y) * (1.0F / inverseW) : Math.fma(normalizedW1, v1.vOverW() - v0.vOverW(), Math.fma(normalizedW2, v2.vOverW() - v0.vOverW(), v0.vOverW())) / inverseW;
        if (!Float.isFinite(u) || !Float.isFinite(v)) {
          continue;
        }

        var sampleU = u;
        var sampleV = v;
        int sampled;
        if (frontend == RasterFrontend.WORLD && material.texture().usesTerrainFiltering()) {
          var leftW = 1.0F / planes[0].at(x & ~1, y);
          var rightW = 1.0F / planes[0].at(x | 1, y);
          var topW = 1.0F / planes[0].at(x, y & ~1);
          var bottomW = 1.0F / planes[0].at(x, y | 1);
          var duDx = planes[1].at(x | 1, y) * rightW - planes[1].at(x & ~1, y) * leftW;
          var duDy = planes[1].at(x, y & ~1) * topW - planes[1].at(x, y | 1) * bottomW;
          var dvDx = planes[2].at(x | 1, y) * rightW - planes[2].at(x & ~1, y) * leftW;
          var dvDy = planes[2].at(x, y & ~1) * topW - planes[2].at(x, y | 1) * bottomW;
          sampled = material.texture().sampleTerrain(sampleU, sampleV, animationTick, duDx, duDy, dvDx, dvDy);
        } else {
          sampled = sampleTexture(frontend, material, sampleU, sampleV, x, y, viewport, animationTick);
        }
        if (material.textureSampleMode() == RenderMaterial.TextureSampleMode.OUTLINE) {
          if ((sampled >>> 24) == 0) {
            continue;
          }
          sampled = 0xFFFFFFFF;
        }
        if (frontend == RasterFrontend.GUI_SCREEN) {
          writeGuiScreenFragment(colorBuffer, rasterIndex, sampled, material, normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2);
          continue;
        }
        var dissolveMask = frontend == RasterFrontend.WORLD ? material.dissolveMaskTexture() : null;
        if (dissolveMask != null) {
          var vertexAlpha = (normalizedW0 * v0.aOverW() + normalizedW1 * v1.aOverW() + normalizedW2 * v2.aOverW()) / inverseW;
          if (vertexAlpha * 255.0F < (dissolveMask.sample(sampleU, sampleV, animationTick) >>> 24)) {
            continue;
          }
        }
        var color = planes != null ? new FragmentColor(
          modulateChannel((sampled >>> 16) & 255, (material.color() >>> 16) & 255, planes[4].at(x, y) * (1.0F / inverseW)),
          modulateChannel((sampled >>> 8) & 255, (material.color() >>> 8) & 255, planes[5].at(x, y) * (1.0F / inverseW)),
          modulateChannel(sampled & 255, material.color() & 255, planes[6].at(x, y) * (1.0F / inverseW)),
          modulateChannel(sampled >>> 24, material.color() >>> 24, dissolveMask != null ? 1.0F : planes[3].at(x, y) * (1.0F / inverseW))) : modulateFragment(sampled, material.color(), normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2, dissolveMask != null, frontend == RasterFrontend.WORLD);
        if (frontend == RasterFrontend.WORLD) {
          color = applyOverlay(
            color,
            interpolatedOverlayColor(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2)
          );
          if (fragmentLighting) {
            color = new FragmentColor(color.r() * (planes[9].at(x, y) * (1.0F / inverseW)),
              color.g() * (planes[10].at(x, y) * (1.0F / inverseW)),
              color.b() * (planes[11].at(x, y) * (1.0F / inverseW)), color.a());
          }
        }
        var alpha = color.a() * 255.0F;
        if (alpha == 0) {
          continue;
        }
        var alphaCutoutValue = frontend == RasterFrontend.WORLD && material.alphaCutoutSource() == RenderMaterial.AlphaCutoutSource.TEXTURE
          ? (sampled >>> 24) & 0xFF
          : alpha;
        if (material.alphaCutoutThreshold() > 0 && alphaCutoutValue < material.alphaCutoutThreshold()) {
          continue;
        }

        if (frontend == RasterFrontend.WORLD && material.fogMode() != RenderMaterial.FogMode.NONE) {
          if (material.fogMode() == RenderMaterial.FogMode.DEPTH_COLOR_MIX && depthFogProjection != null) {
            var fogDistance = depthFogDistance(depth, depthFogProjection);
            color = applyFog(color, fogDistance, fogDistance, fogState, material.fogMode(), material.glintAlpha());
          } else {
            color = applyFog(
              color,
              planes[7].at(x, y) * (1.0F / inverseW),
              planes[8].at(x, y) * (1.0F / inverseW),
              fogState,
              material.fogMode(),
              material.glintAlpha()
            );
          }
        }

        if (frontend == RasterFrontend.GUI_ITEM) {
          writeGuiItemFragment(colorBuffer, depthBuffer, rasterIndex, depth, color.packed(), material, guiDepthWrite);
          continue;
        }

        if (material.alphaMode() != RendererAssets.AlphaMode.TRANSLUCENT && !material.blendState().blends()) {
          if (frontend != RasterFrontend.GUI_SCREEN && material.depthWrite()) {
            depthBuffer[rasterIndex] = depth;
          }
          writeColor(colorBuffer, rasterIndex, forceOpaque(color.packed()), material);
          continue;
        }

        writeColor(colorBuffer, rasterIndex, color.packed(), material);
        if (frontend != RasterFrontend.GUI_SCREEN && material.depthWrite()) {
          depthBuffer[rasterIndex] = depth;
        }
      }
    }
  }

  private static void writeGuiScreenFragment(int[] buffer, int index, int sample, RenderMaterial material,
                                            float w0, float w1, float w2, float inverseW,
                                            ProjectedVertex v0, ProjectedVertex v1, ProjectedVertex v2) {
    var tint = material.color();
    var alpha = (sample >>> 24) * ((tint >>> 24) / 255.0F)
      * ((w0 * v0.aOverW() + w1 * v1.aOverW() + w2 * v2.aOverW()) / inverseW / 255.0F);
    if (alpha <= 0 || alpha < material.alphaCutoutThreshold()) {
      return;
    }
    var red = ((sample >>> 16) & 255) * (((tint >>> 16) & 255) / 255.0F)
      * ((w0 * v0.rOverW() + w1 * v1.rOverW() + w2 * v2.rOverW()) / inverseW / 255.0F);
    var green = ((sample >>> 8) & 255) * (((tint >>> 8) & 255) / 255.0F)
      * ((w0 * v0.gOverW() + w1 * v1.gOverW() + w2 * v2.gOverW()) / inverseW / 255.0F);
    var blue = (sample & 255) * ((tint & 255) / 255.0F)
      * ((w0 * v0.bOverW() + w1 * v1.bOverW() + w2 * v2.bOverW()) / inverseW / 255.0F);
    var destination = buffer[index];
    var output = material.blendState().blends()
      ? blend(destination, red, green, blue, alpha, material.blendState())
      : (colorChannel(alpha) << 24) | (colorChannel(red) << 16) | (colorChannel(green) << 8) | colorChannel(blue);
    buffer[index] = applyColorWriteMask(destination, output, material.colorWriteMask());
  }

  private static boolean passesDepth(RasterFrontend frontend, RenderMaterial material, double incoming, double stored) {
    return switch (frontend) {
      case WORLD -> material.depthTest().passes(incoming, stored);
      case GUI_ITEM -> incoming <= stored;
      case GUI_SCREEN -> true;
    };
  }

  private static void writeGuiItemFragment(
    int[] colorBuffer,
    double[] depthBuffer,
    int rasterIndex,
    double depth,
    int color,
    RenderMaterial material,
    boolean writeDepth
  ) {
    if (material.alphaMode() == RendererAssets.AlphaMode.OPAQUE || material.alphaMode() == RendererAssets.AlphaMode.CUTOUT) {
      if (writeDepth) {
        depthBuffer[rasterIndex] = depth;
      }
      colorBuffer[rasterIndex] = forceOpaque(color);
      return;
    }

    colorBuffer[rasterIndex] = blendStraightAlpha(colorBuffer[rasterIndex], color);
    if (writeDepth) {
      depthBuffer[rasterIndex] = depth;
    }
  }

  private static double fragmentDepthBias(ProjectedTriangle triangle, RenderMaterial material) {
    var bias = material.depthBias() + material.polygonOffsetUnits() * POLYGON_OFFSET_UNIT_DEPTH;
    var factor = material.polygonOffsetFactor();
    if (factor == 0.0F) {
      return bias;
    }

    var v0 = triangle.v0();
    var v1 = triangle.v1();
    var v2 = triangle.v2();
    var x1 = v1.x() - v0.x();
    var y1 = v1.y() - v0.y();
    var z1 = v1.depth() - v0.depth();
    var x2 = v2.x() - v0.x();
    var y2 = v2.y() - v0.y();
    var z2 = v2.depth() - v0.depth();
    var denominator = x1 * y2 - x2 * y1;
    if (Math.abs(denominator) < 1.0E-5F) {
      return bias;
    }

    var dzDx = (z1 * y2 - z2 * y1) / denominator;
    var dzDy = (x1 * z2 - x2 * z1) / denominator;
    return bias + Math.max(Math.abs(dzDx), Math.abs(dzDy)) * factor;
  }

  @Nullable
  private static DepthFogProjection depthFogProjection(Viewport viewport, RenderMaterial material) {
    if (material.fogMode() != RenderMaterial.FogMode.DEPTH_COLOR_MIX) {
      return null;
    }

    return new DepthFogProjection(viewport.projectionM22(), viewport.projectionM32());
  }

  private static float depthFogDistance(double depth, DepthFogProjection projection) {
    var denominator = depth * -2.0F + 1.0F - projection.m22();
    if (!Double.isFinite(denominator) || Math.abs(denominator) <= 1.0E-8F) {
      return Float.POSITIVE_INFINITY;
    }

    var distance = -projection.m32() / denominator;
    return Double.isFinite(distance) ? (float) distance : Float.POSITIVE_INFINITY;
  }

  private static float interpolatedFogDistance(
    float weight0,
    float weight1,
    float weight2,
    float inverseW,
    ProjectedVertex v0,
    ProjectedVertex v1,
    ProjectedVertex v2,
    boolean spherical
  ) {
    if (spherical) {
      return (weight0 * v0.sphericalFogDistanceOverW() + weight1 * v1.sphericalFogDistanceOverW() + weight2 * v2.sphericalFogDistanceOverW()) / inverseW;
    }
    return (weight0 * v0.cylindricalFogDistanceOverW() + weight1 * v1.cylindricalFogDistanceOverW() + weight2 * v2.cylindricalFogDistanceOverW()) / inverseW;
  }

  private static boolean hasFragmentLighting(ProjectedVertex vertex) {
    return vertex.lightROverW() != vertex.inverseW()
      || vertex.lightGOverW() != vertex.inverseW()
      || vertex.lightBOverW() != vertex.inverseW();
  }

  private static int interpolatedOverlayColor(
    float weight0,
    float weight1,
    float weight2,
    float inverseW,
    ProjectedVertex v0,
    ProjectedVertex v1,
    ProjectedVertex v2
  ) {
    var a = colorChannel((weight0 * v0.overlayAOverW() + weight1 * v1.overlayAOverW() + weight2 * v2.overlayAOverW()) / inverseW);
    var r = colorChannel((weight0 * v0.overlayROverW() + weight1 * v1.overlayROverW() + weight2 * v2.overlayROverW()) / inverseW);
    var g = colorChannel((weight0 * v0.overlayGOverW() + weight1 * v1.overlayGOverW() + weight2 * v2.overlayGOverW()) / inverseW);
    var b = colorChannel((weight0 * v0.overlayBOverW() + weight1 * v1.overlayBOverW() + weight2 * v2.overlayBOverW()) / inverseW);
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private static FragmentColor applyOverlay(FragmentColor color, int overlayColor) {
    var overlayAlpha = (overlayColor >>> 24) & 0xFF;
    if (overlayAlpha == 255) {
      return color;
    }

    var baseWeight = overlayAlpha / 255.0F;
    var overlayWeight = 1.0F - baseWeight;
    var r = ((overlayColor >> 16) & 0xFF) * (1.0F / 255.0F) * overlayWeight + color.r() * baseWeight;
    var g = ((overlayColor >> 8) & 0xFF) * (1.0F / 255.0F) * overlayWeight + color.g() * baseWeight;
    var b = (overlayColor & 0xFF) * (1.0F / 255.0F) * overlayWeight + color.b() * baseWeight;
    return new FragmentColor(r, g, b, color.a());
  }

  private static FragmentColor applyFog(
    FragmentColor color,
    float sphericalFogDistance,
    float cylindricalFogDistance,
    RasterFogState fogState,
    RenderMaterial.FogMode fogMode,
    float glintAlpha
  ) {
    if (!fogState.enabled() && fogMode != RenderMaterial.FogMode.RGB_FADE) {
      return color;
    }

    if (fogMode == RenderMaterial.FogMode.CLOUD_ALPHA) {
      var opacity = 1.0F - linearFogValue(sphericalFogDistance, 0, fogState.cloudsEnd());
      return new FragmentColor(color.r(), color.g(), color.b(), color.a() * opacity);
    }
    var rawFogAmount = Math.max(
      linearFogValue(sphericalFogDistance, fogState.environmentalStart(), fogState.environmentalEnd()),
      linearFogValue(cylindricalFogDistance, fogState.renderDistanceStart(), fogState.renderDistanceEnd())
    );
    rawFogAmount = Math.clamp(rawFogAmount, 0.0F, 1.0F);
    if (rawFogAmount <= 0.0F && fogMode != RenderMaterial.FogMode.RGB_FADE) {
      return color;
    }

    return switch (fogMode) {
      case NONE, CLOUD_ALPHA -> color;
      case COLOR_MIX, DEPTH_COLOR_MIX -> applyColorMixFog(color, fogState, rawFogAmount);
      case ALPHA_FADE -> multiplyChannels(color, 1.0F - rawFogAmount, true);
      case RGB_FADE -> multiplyChannels(color, (1.0F - rawFogAmount) * glintAlpha, false);
    };
  }

  private static FragmentColor applyColorMixFog(FragmentColor color, RasterFogState fogState, float rawFogAmount) {
    var fogAmount = Math.clamp(rawFogAmount * fogState.color().w(), 0.0F, 1.0F);
    if (fogAmount <= 0.0F) {
      return color;
    }

    // Preserve GLSL mix evaluation: rewriting this as start + t * (end - start) changes rounding.
    var r = color.r() * (1.0F - fogAmount) + fogState.color().x() * fogAmount;
    var g = color.g() * (1.0F - fogAmount) + fogState.color().y() * fogAmount;
    var b = color.b() * (1.0F - fogAmount) + fogState.color().z() * fogAmount;
    return new FragmentColor(r, g, b, color.a());
  }

  private static FragmentColor multiplyChannels(FragmentColor color, float factor, boolean includeAlpha) {
    var a = includeAlpha ? color.a() * factor : color.a();
    var r = color.r() * factor;
    var g = color.g() * factor;
    var b = color.b() * factor;
    return new FragmentColor(r, g, b, a);
  }

  private static float linearFogValue(float distance, float start, float end) {
    if (distance <= start) {
      return 0.0F;
    }
    if (distance >= end) {
      return 1.0F;
    }
    return (distance - start) / (end - start);
  }

  private static int sampleTexture(
    RasterFrontend frontend,
    RenderMaterial material,
    float u,
    float v,
    int x,
    int y,
    Viewport viewport,
    long animationTick
  ) {
    var sample = material.texture().sample(u, v, animationTick);
    if (frontend == RasterFrontend.GUI_ITEM) {
      return sample;
    }

    return switch (material.textureSampleMode()) {
      case COLOR, OUTLINE -> sample;
      case INTENSITY -> {
        var intensity = (sample >> 16) & 0xFF;
        yield (intensity << 24) | (intensity << 16) | (intensity << 8) | intensity;
      }
      case END_PORTAL -> sampleEndPortal(material, x, y, viewport, animationTick);
    };
  }

  private static int sampleEndPortal(RenderMaterial material, int x, int y, Viewport viewport, long animationTick) {
    var projectedU = (x + 0.5F) / viewport.width();
    var projectedV = 1.0F - (y + 0.5F) / viewport.height();
    var baseSample = material.texture().sample(projectedU, projectedV, animationTick);
    var r = textureChannel(baseSample, 16) * END_PORTAL_COLORS[0][0];
    var g = textureChannel(baseSample, 8) * END_PORTAL_COLORS[0][1];
    var b = textureChannel(baseSample, 0) * END_PORTAL_COLORS[0][2];
    var secondaryTexture = material.secondaryTexture();
    if (secondaryTexture != null) {
      var gameTime = Math.floorMod(animationTick, 24000L) / 24000.0F;
      var layerCount = Math.min(material.portalLayers(), END_PORTAL_COLORS.length);
      for (var layerIndex = 0; layerIndex < layerCount; layerIndex++) {
        var layerCoord = endPortalLayerCoord(projectedU, projectedV, layerIndex + 1, gameTime);
        var layerSample = secondaryTexture.sample(layerCoord.u(), layerCoord.v(), animationTick);
        var layerColor = END_PORTAL_COLORS[layerIndex];
        r += textureChannel(layerSample, 16) * layerColor[0];
        g += textureChannel(layerSample, 8) * layerColor[1];
        b += textureChannel(layerSample, 0) * layerColor[2];
      }
    }

    return 0xFF000000
      | (colorChannel(r * 255.0F) << 16)
      | (colorChannel(g * 255.0F) << 8)
      | colorChannel(b * 255.0F);
  }

  private static float textureChannel(int color, int shift) {
    return ((color >> shift) & 0xFF) / 255.0F;
  }

  private static TextureCoord endPortalLayerCoord(float u, float v, int layer, float gameTime) {
    var layerFloat = (float) layer;
    var angle = (float) Math.toRadians((layerFloat * layerFloat * 4321.0F + layerFloat * 9.0F) * 2.0F);
    var sin = (float) Math.sin(angle);
    var cos = (float) Math.cos(angle);
    var scale = (4.5F - layerFloat / 4.0F) * 2.0F;
    var rotatedU = (u * cos - v * sin) * scale;
    var rotatedV = (u * sin + v * cos) * scale;
    var translatedU = rotatedU + 17.0F / layerFloat;
    var translatedV = rotatedV + (2.0F + layerFloat / 1.5F) * (gameTime * 1.5F);
    return new TextureCoord(translatedU * 0.5F + 0.25F, translatedV * 0.5F + 0.25F);
  }

  private static int colorChannel(float value) {
    return Math.clamp((int) Math.rint(value), 0, 255);
  }

  private static FragmentColor modulateFragment(int sample, int tint, float w0, float w1, float w2, float inverseW,
                                      ProjectedVertex v0, ProjectedVertex v1, ProjectedVertex v2, boolean opaqueVertex, boolean normalized) {
    var vertexScale = normalized ? 1.0F : 1.0F / 255.0F;
    // The shader multiplies interpolated lighting and texture channels before UNORM framebuffer conversion.
    var a = modulateChannel(sample >>> 24, tint >>> 24, opaqueVertex ? 1.0F : vertexScale * (w0 * v0.aOverW() + w1 * v1.aOverW() + w2 * v2.aOverW()) / inverseW);
    var r = modulateChannel((sample >>> 16) & 255, (tint >>> 16) & 255, vertexScale * (w0 * v0.rOverW() + w1 * v1.rOverW() + w2 * v2.rOverW()) / inverseW);
    var g = modulateChannel((sample >>> 8) & 255, (tint >>> 8) & 255, vertexScale * (w0 * v0.gOverW() + w1 * v1.gOverW() + w2 * v2.gOverW()) / inverseW);
    var b = modulateChannel(sample & 255, tint & 255, vertexScale * (w0 * v0.bOverW() + w1 * v1.bOverW() + w2 * v2.bOverW()) / inverseW);
    return new FragmentColor(r, g, b, a);
  }

  private static float modulateChannel(int sample, int tint, float vertex) {
    return (sample * (1.0F / 255.0F)) * (vertex * (tint * (1.0F / 255.0F)));
  }

  private record AttributePlane(float dx, float dy, float origin, int height) {
    static AttributePlane of(ProjectedVertex v0, ProjectedVertex v1, ProjectedVertex v2, float a0, float a1, float a2, int height) {
      var area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
      if (area > 0) {
        var swapVertex = v1;
        v1 = v2;
        v2 = swapVertex;
        var swapAttribute = a1;
        a1 = a2;
        a2 = swapAttribute;
      }
      var y0 = v0.interpolationY();
      var y1 = v1.interpolationY();
      var y2 = v2.interpolationY();
      var x01 = v0.x() - v1.x();
      var y01 = y0 - y1;
      var x20 = v2.x() - v0.x();
      var y20 = y2 - y0;
      var inverseArea = 1.0F / (x01 * y20 - y01 * x20);
      var a01 = a0 - a1;
      var a20 = a2 - a0;
      var dx = a01 * (y20 * inverseArea) - a20 * (y01 * inverseArea);
      var dy = a20 * (x01 * inverseArea) - a01 * (x20 * inverseArea);
      var origin = a0 - (dx * (v0.x() - 0.5F) + dy * (y0 - 0.5F));
      return new AttributePlane(dx, dy, origin, height);
    }

    float at(int x, int y) {
      return Math.fma(dy, height - y - 1, Math.fma(dx, x, origin));
    }
  }

  private record FragmentColor(float r, float g, float b, float a) {
    int packed() {
      return (colorChannel(a * 255.0F) << 24) | (colorChannel(r * 255.0F) << 16) | (colorChannel(g * 255.0F) << 8) | colorChannel(b * 255.0F);
    }
  }

  private static int forceOpaque(int color) {
    return 0xFF000000 | (color & 0x00FFFFFF);
  }

  private static void writeColor(int[] colorBuffer, int rasterIndex, int srcColor, RenderMaterial material) {
    if (material.colorWriteMask() == ColorTargetState.WRITE_NONE) {
      return;
    }

    var dstColor = colorBuffer[rasterIndex];
    var output = material.blendState().blends() ? blend(dstColor, srcColor, material.blendState()) : srcColor;
    colorBuffer[rasterIndex] = applyColorWriteMask(dstColor, output, material.colorWriteMask());
  }

  private static int applyColorWriteMask(int dstColor, int output, int writeMask) {
    var color = dstColor;
    if ((writeMask & ColorTargetState.WRITE_ALPHA) != 0) {
      color = (color & 0x00FFFFFF) | (output & 0xFF000000);
    }
    if ((writeMask & ColorTargetState.WRITE_RED) != 0) {
      color = (color & 0xFF00FFFF) | (output & 0x00FF0000);
    }
    if ((writeMask & ColorTargetState.WRITE_GREEN) != 0) {
      color = (color & 0xFFFF00FF) | (output & 0x0000FF00);
    }
    if ((writeMask & ColorTargetState.WRITE_BLUE) != 0) {
      color = (color & 0xFFFFFF00) | (output & 0x000000FF);
    }
    return color;
  }

  private static int blend(int dstColor, int srcColor, RenderMaterial.BlendState blendState) {
    return blend(dstColor, (srcColor >>> 16) & 255, (srcColor >>> 8) & 255, srcColor & 255, srcColor >>> 24, blendState);
  }

  private static int blend(int dstColor, float srcR, float srcG, float srcB, float srcA, RenderMaterial.BlendState blendState) {
    srcR = colorChannel(srcR);
    srcG = colorChannel(srcG);
    srcB = colorChannel(srcB);
    srcA = colorChannel(srcA);
    var dstR = (dstColor >>> 16) & 255;
    var dstG = (dstColor >>> 8) & 255;
    var dstB = dstColor & 255;
    var dstA = dstColor >>> 24;
    var outR = blendChannel(srcR, dstR, srcR, dstR, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outG = blendChannel(srcG, dstG, srcG, dstG, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outB = blendChannel(srcB, dstB, srcB, dstB, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outA = blendChannel(srcA, dstA, srcA, dstA, srcA, dstA, blendState.sourceAlpha(), blendState.destAlpha(), true);
    return (outA << 24) | (outR << 16) | (outG << 8) | outB;
  }

  private static int blendChannel(
    float srcChannel,
    float dstChannel,
    float srcColorChannel,
    float dstColorChannel,
    float srcAlpha,
    float dstAlpha,
    BlendFactor sourceFactor,
    BlendFactor destFactor,
    boolean alphaChannel
  ) {
    var srcScale = sourceFactor(sourceFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha, alphaChannel);
    var dstScale = destFactor(destFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha);
    return Math.clamp((int) Math.rint(srcChannel * srcScale) + (int) Math.rint(dstChannel * dstScale), 0, 255);
  }

  private static float sourceFactor(BlendFactor factor, float srcColor, float dstColor, float srcAlpha, float dstAlpha, boolean alphaChannel) {
    return switch (factor) {
      case ZERO -> 0.0F;
      case ONE -> 1.0F;
      case SRC_COLOR -> srcColor / 255.0F;
      case ONE_MINUS_SRC_COLOR -> 1.0F - srcColor / 255.0F;
      case DST_COLOR -> dstColor / 255.0F;
      case ONE_MINUS_DST_COLOR -> 1.0F - dstColor / 255.0F;
      case SRC_ALPHA -> srcAlpha / 255.0F;
      case ONE_MINUS_SRC_ALPHA -> 1.0F - srcAlpha / 255.0F;
      case DST_ALPHA -> dstAlpha / 255.0F;
      case ONE_MINUS_DST_ALPHA -> 1.0F - dstAlpha / 255.0F;
      case SRC_ALPHA_SATURATE -> alphaChannel ? 1.0F : Math.min(srcAlpha / 255.0F, 1.0F - dstAlpha / 255.0F);
      case CONSTANT_COLOR, CONSTANT_ALPHA -> 0.0F;
      case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> 1.0F;
    };
  }

  private static float destFactor(BlendFactor factor, float srcColor, float dstColor, float srcAlpha, float dstAlpha) {
    return switch (factor) {
      case ZERO -> 0.0F;
      case ONE -> 1.0F;
      case SRC_COLOR -> srcColor / 255.0F;
      case ONE_MINUS_SRC_COLOR -> 1.0F - srcColor / 255.0F;
      case DST_COLOR -> dstColor / 255.0F;
      case ONE_MINUS_DST_COLOR -> 1.0F - dstColor / 255.0F;
      case SRC_ALPHA -> srcAlpha / 255.0F;
      case ONE_MINUS_SRC_ALPHA -> 1.0F - srcAlpha / 255.0F;
      case DST_ALPHA -> dstAlpha / 255.0F;
      case ONE_MINUS_DST_ALPHA -> 1.0F - dstAlpha / 255.0F;
      case SRC_ALPHA_SATURATE -> Math.min(srcAlpha / 255.0F, 1.0F - dstAlpha / 255.0F);
      case CONSTANT_COLOR, CONSTANT_ALPHA -> 0.0F;
      case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> 1.0F;
    };
  }

  private static boolean isInside(boolean positiveArea, long w0, long w1, long w2, boolean topLeft0, boolean topLeft1, boolean topLeft2) {
    if (positiveArea) {
      return edgeInclusive(w0, topLeft0) && edgeInclusive(w1, topLeft1) && edgeInclusive(w2, topLeft2);
    }
    return edgeInclusive(-w0, topLeft0) && edgeInclusive(-w1, topLeft1) && edgeInclusive(-w2, topLeft2);
  }

  private static long fixedEdge(long ax, long ay, long bx, long by, long px, long py) {
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
  }

  private static float edge(float ax, float ay, float bx, float by, float px, float py) {
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
  }

  private static boolean edgeInclusive(long edgeValue, boolean topLeft) {
    return edgeValue > 0 || (edgeValue == 0 && topLeft);
  }

  private static boolean isInclusiveEdge(long ax, long ay, long bx, long by, boolean bottomEdge) {
    var dy = by - ay;
    var dx = bx - ax;
    return dy > 0 || (dy == 0 && (bottomEdge ? dx > 0 : dx < 0));
  }

  private enum RasterFrontend {
    WORLD,
    GUI_ITEM,
    GUI_SCREEN
  }

  private record Viewport(int width, int height, float projectionM22, float projectionM32) {}

  private record DepthFogProjection(float m22, float m32) {}

  private record TextureCoord(float u, float v) {}
}
