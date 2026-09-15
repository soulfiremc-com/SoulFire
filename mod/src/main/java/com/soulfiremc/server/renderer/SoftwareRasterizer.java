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
    var area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
    if (Math.abs(area) < 1.0E-5F) {
      return;
    }
    if (!material.doubleSided() && area <= 0.0F) {
      return;
    }

    var fragmentDepthBias = frontend == RasterFrontend.WORLD ? fragmentDepthBias(triangle, material) : 0.0F;
    var depthFogProjection = depthFogProjection(viewport, material);
    var positiveArea = area > 0.0F;
    var topLeft0 = positiveArea ? isTopLeft(v1.x(), v1.y(), v2.x(), v2.y()) : isTopLeft(v2.x(), v2.y(), v1.x(), v1.y());
    var topLeft1 = positiveArea ? isTopLeft(v2.x(), v2.y(), v0.x(), v0.y()) : isTopLeft(v0.x(), v0.y(), v2.x(), v2.y());
    var topLeft2 = positiveArea ? isTopLeft(v0.x(), v0.y(), v1.x(), v1.y()) : isTopLeft(v1.x(), v1.y(), v0.x(), v0.y());

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
        if (!isInside(positiveArea, w0, w1, w2, topLeft0, topLeft1, topLeft2)) {
          continue;
        }

        var normalizedW0 = w0 / area;
        var normalizedW1 = w1 / area;
        var normalizedW2 = w2 / area;
        var depth = normalizedW0 * v0.depth() + normalizedW1 * v1.depth() + normalizedW2 * v2.depth() + fragmentDepthBias;
        if (frontend == RasterFrontend.WORLD) {
          depth = Math.clamp(depth, 0.0F, 1.0F);
        }
        if (!Float.isFinite(depth)) {
          continue;
        }

        var rasterIndex = y * viewport.width() + x;
        if (!passesDepth(frontend, material, depth, depthBuffer[rasterIndex])) {
          continue;
        }

        var inverseW = normalizedW0 * v0.inverseW() + normalizedW1 * v1.inverseW() + normalizedW2 * v2.inverseW();
        if (!Float.isFinite(inverseW) || Math.abs(inverseW) < 1.0E-8F) {
          continue;
        }

        var u = (normalizedW0 * v0.uOverW() + normalizedW1 * v1.uOverW() + normalizedW2 * v2.uOverW()) / inverseW;
        var v = (normalizedW0 * v0.vOverW() + normalizedW1 * v1.vOverW() + normalizedW2 * v2.vOverW()) / inverseW;
        if (!Float.isFinite(u) || !Float.isFinite(v)) {
          continue;
        }

        var sampleU = frontend == RasterFrontend.WORLD ? material.uvTransform().u(u, v, animationTick) : u;
        var sampleV = frontend == RasterFrontend.WORLD ? material.uvTransform().v(u, v, animationTick) : v;
        var sampled = sampleTexture(frontend, material, sampleU, sampleV, x, y, viewport, animationTick);
        var vertexColor = interpolatedColor(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2);
        if (frontend == RasterFrontend.WORLD) {
          var dissolveMaskTexture = material.dissolveMaskTexture();
          if (dissolveMaskTexture != null) {
            var vertexAlpha = (vertexColor >>> 24) & 0xFF;
            var dissolveMaskAlpha = (dissolveMaskTexture.sample(sampleU, sampleV, animationTick) >>> 24) & 0xFF;
            if (vertexAlpha < dissolveMaskAlpha) {
              continue;
            }
            vertexColor = forceOpaque(vertexColor);
          }
        }

        var color = modulate(modulate(sampled, vertexColor), material.color());
        if (frontend == RasterFrontend.WORLD) {
          color = applyOverlay(
            color,
            interpolatedOverlayColor(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2)
          );
        }
        var alpha = (color >>> 24) & 0xFF;
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
            color = applyFog(color, fogDistance, fogDistance, fogState, material.fogMode());
          } else {
            color = applyFog(
              color,
              interpolatedFogDistance(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2, true),
              interpolatedFogDistance(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2, false),
              fogState,
              material.fogMode()
            );
          }
        }

        if (frontend == RasterFrontend.GUI_ITEM) {
          writeGuiItemFragment(colorBuffer, depthBuffer, rasterIndex, depth, color, material, guiDepthWrite);
          continue;
        }

        if (material.alphaMode() != RendererAssets.AlphaMode.TRANSLUCENT && !material.blendState().blends()) {
          if (frontend != RasterFrontend.GUI_SCREEN && material.depthWrite()) {
            depthBuffer[rasterIndex] = depth;
          }
          writeColor(colorBuffer, rasterIndex, forceOpaque(color), material);
          continue;
        }

        writeColor(colorBuffer, rasterIndex, color, material);
        if (frontend != RasterFrontend.GUI_SCREEN && material.depthWrite()) {
          depthBuffer[rasterIndex] = depth;
        }
      }
    }
  }

  private static boolean passesDepth(RasterFrontend frontend, RenderMaterial material, float incoming, float stored) {
    return switch (frontend) {
      case WORLD -> material.depthTest().passes(incoming, stored);
      case GUI_ITEM -> incoming <= stored;
      case GUI_SCREEN -> true;
    };
  }

  private static void writeGuiItemFragment(
    int[] colorBuffer,
    float[] depthBuffer,
    int rasterIndex,
    float depth,
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

  private static float fragmentDepthBias(ProjectedTriangle triangle, RenderMaterial material) {
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

  private static float depthFogDistance(float depth, DepthFogProjection projection) {
    var denominator = depth * -2.0F + 1.0F - projection.m22();
    if (!Float.isFinite(denominator) || Math.abs(denominator) <= 1.0E-8F) {
      return Float.POSITIVE_INFINITY;
    }

    var distance = -projection.m32() / denominator;
    return Float.isFinite(distance) ? distance : Float.POSITIVE_INFINITY;
  }

  private static int interpolatedColor(
    float weight0,
    float weight1,
    float weight2,
    float inverseW,
    ProjectedVertex v0,
    ProjectedVertex v1,
    ProjectedVertex v2
  ) {
    var a = colorChannel((weight0 * v0.aOverW() + weight1 * v1.aOverW() + weight2 * v2.aOverW()) / inverseW);
    var r = colorChannel((weight0 * v0.rOverW() + weight1 * v1.rOverW() + weight2 * v2.rOverW()) / inverseW);
    var g = colorChannel((weight0 * v0.gOverW() + weight1 * v1.gOverW() + weight2 * v2.gOverW()) / inverseW);
    var b = colorChannel((weight0 * v0.bOverW() + weight1 * v1.bOverW() + weight2 * v2.bOverW()) / inverseW);
    return (a << 24) | (r << 16) | (g << 8) | b;
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

  private static int applyOverlay(int color, int overlayColor) {
    var overlayAlpha = (overlayColor >>> 24) & 0xFF;
    if (overlayAlpha == 255) {
      return color;
    }

    var baseWeight = overlayAlpha / 255.0F;
    var overlayWeight = 1.0F - baseWeight;
    var r = colorChannel(((overlayColor >> 16) & 0xFF) * overlayWeight + ((color >> 16) & 0xFF) * baseWeight);
    var g = colorChannel(((overlayColor >> 8) & 0xFF) * overlayWeight + ((color >> 8) & 0xFF) * baseWeight);
    var b = colorChannel((overlayColor & 0xFF) * overlayWeight + (color & 0xFF) * baseWeight);
    return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
  }

  private static int applyFog(
    int color,
    float sphericalFogDistance,
    float cylindricalFogDistance,
    RasterFogState fogState,
    RenderMaterial.FogMode fogMode
  ) {
    if (!fogState.enabled()) {
      return color;
    }

    var rawFogAmount = Math.max(
      linearFogValue(sphericalFogDistance, fogState.environmentalStart(), fogState.environmentalEnd()),
      linearFogValue(cylindricalFogDistance, fogState.renderDistanceStart(), fogState.renderDistanceEnd())
    );
    rawFogAmount = Math.clamp(rawFogAmount, 0.0F, 1.0F);
    if (rawFogAmount <= 0.0F) {
      return color;
    }

    return switch (fogMode) {
      case NONE -> color;
      case COLOR_MIX, DEPTH_COLOR_MIX -> applyColorMixFog(color, fogState, rawFogAmount);
      case ALPHA_FADE -> multiplyChannels(color, 1.0F - rawFogAmount, true);
      case RGB_FADE -> multiplyChannels(color, 1.0F - rawFogAmount, false);
    };
  }

  private static int applyColorMixFog(int color, RasterFogState fogState, float rawFogAmount) {
    var fogAmount = Math.clamp(rawFogAmount * ARGB.alphaFloat(fogState.color()), 0.0F, 1.0F);
    if (fogAmount <= 0.0F) {
      return color;
    }

    var r = colorChannel(Mth.lerp(fogAmount, (color >> 16) & 0xFF, (fogState.color() >> 16) & 0xFF));
    var g = colorChannel(Mth.lerp(fogAmount, (color >> 8) & 0xFF, (fogState.color() >> 8) & 0xFF));
    var b = colorChannel(Mth.lerp(fogAmount, color & 0xFF, fogState.color() & 0xFF));
    return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
  }

  private static int multiplyChannels(int color, float factor, boolean includeAlpha) {
    var a = includeAlpha ? colorChannel(((color >>> 24) & 0xFF) * factor) : (color >>> 24) & 0xFF;
    var r = colorChannel(((color >> 16) & 0xFF) * factor);
    var g = colorChannel(((color >> 8) & 0xFF) * factor);
    var b = colorChannel((color & 0xFF) * factor);
    return (a << 24) | (r << 16) | (g << 8) | b;
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
      case COLOR -> sample;
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
    return Math.clamp(Math.round(value), 0, 255);
  }

  private static int modulate(int sample, int multiplier) {
    var a = ((sample >>> 24) & 0xFF) * ((multiplier >>> 24) & 0xFF) / 255;
    var r = ((sample >> 16) & 0xFF) * ((multiplier >> 16) & 0xFF) / 255;
    var g = ((sample >> 8) & 0xFF) * ((multiplier >> 8) & 0xFF) / 255;
    var b = (sample & 0xFF) * (multiplier & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
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
    var dstR = (dstColor >> 16) & 0xFF;
    var dstG = (dstColor >> 8) & 0xFF;
    var dstB = dstColor & 0xFF;
    var dstA = (dstColor >>> 24) & 0xFF;
    var srcA = (srcColor >>> 24) & 0xFF;
    var srcR = (srcColor >> 16) & 0xFF;
    var srcG = (srcColor >> 8) & 0xFF;
    var srcB = srcColor & 0xFF;
    var outR = blendChannel(srcR, dstR, srcR, dstR, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outG = blendChannel(srcG, dstG, srcG, dstG, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outB = blendChannel(srcB, dstB, srcB, dstB, srcA, dstA, blendState.sourceColor(), blendState.destColor(), false);
    var outA = blendChannel(srcA, dstA, srcA, dstA, srcA, dstA, blendState.sourceAlpha(), blendState.destAlpha(), true);
    return (outA << 24) | (outR << 16) | (outG << 8) | outB;
  }

  private static int blendChannel(
    int srcChannel,
    int dstChannel,
    int srcColorChannel,
    int dstColorChannel,
    int srcAlpha,
    int dstAlpha,
    BlendFactor sourceFactor,
    BlendFactor destFactor,
    boolean alphaChannel
  ) {
    var srcScale = sourceFactor(sourceFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha, alphaChannel);
    var dstScale = destFactor(destFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha);
    return Math.clamp(Math.round(srcChannel * srcScale + dstChannel * dstScale), 0, 255);
  }

  private static float sourceFactor(BlendFactor factor, int srcColor, int dstColor, int srcAlpha, int dstAlpha, boolean alphaChannel) {
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

  private static float destFactor(BlendFactor factor, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
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

  private static boolean isInside(boolean positiveArea, float w0, float w1, float w2, boolean topLeft0, boolean topLeft1, boolean topLeft2) {
    var epsilon = 1.0E-5F;
    if (positiveArea) {
      return edgeInclusive(w0, topLeft0, epsilon) && edgeInclusive(w1, topLeft1, epsilon) && edgeInclusive(w2, topLeft2, epsilon);
    }
    return edgeInclusive(-w0, topLeft0, epsilon) && edgeInclusive(-w1, topLeft1, epsilon) && edgeInclusive(-w2, topLeft2, epsilon);
  }

  private static float edge(float ax, float ay, float bx, float by, float px, float py) {
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
  }

  private static boolean edgeInclusive(float edgeValue, boolean topLeft, float epsilon) {
    return edgeValue > epsilon || (Math.abs(edgeValue) <= epsilon && topLeft);
  }

  private static boolean isTopLeft(float ax, float ay, float bx, float by) {
    var dy = by - ay;
    var dx = bx - ax;
    return dy < 0.0F || (dy == 0.0F && dx > 0.0F);
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
