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
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.IntStream;

/// Projects and rasterizes scene geometry into the target buffers.
public final class RasterPipeline {
  private static final int TILE_SIZE = 32;
  private static final float DEPTH_BIAS_SCALE = 1.0E-3F;

  public void render(RenderContext ctx, SceneData sceneData, RasterBuffers buffers) {
    renderSky(ctx, buffers);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.opaque(), buffers, false, RasterPassKind.OPAQUE);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.cutout(), buffers, false, RasterPassKind.CUTOUT);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.translucent(), buffers, true, RasterPassKind.TRANSLUCENT);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.clouds(), buffers, false, RasterPassKind.TRANSLUCENT);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.weather(), buffers, false, RasterPassKind.TRANSLUCENT);
  }

  public void renderSynthetic(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, int clearColor) {
    buffers.clearColor(clearColor);
    buffers.clearDepth();
    rasterPass(camera, animationTick, sceneData.opaque(), buffers, false, RasterPassKind.OPAQUE);
    rasterPass(camera, animationTick, sceneData.cutout(), buffers, false, RasterPassKind.CUTOUT);
    rasterPass(camera, animationTick, sceneData.translucent(), buffers, true, RasterPassKind.TRANSLUCENT);
    rasterPass(camera, animationTick, sceneData.clouds(), buffers, false, RasterPassKind.TRANSLUCENT);
    rasterPass(camera, animationTick, sceneData.weather(), buffers, false, RasterPassKind.TRANSLUCENT);
  }

  private void renderSky(RenderContext ctx, RasterBuffers buffers) {
    SkyRenderer.renderBackground(ctx, buffers);
    rasterPass(ctx.camera(), ctx.animationTick(), SkyRenderer.collectSkyQuads(ctx), buffers, false, RasterPassKind.UNTRACKED);
    buffers.clearDepth();
  }

  private void rasterPass(
    Camera camera,
    long animationTick,
    RenderQuad[] quads,
    RasterBuffers buffers,
    boolean sortBackToFront,
    RasterPassKind passKind
  ) {
    if (quads.length == 0) {
      return;
    }

    var triangles = projectQuads(camera, quads);
    recordTriangleCount(passKind, triangles.size());
    if (triangles.isEmpty()) {
      return;
    }
    if (sortBackToFront) {
      triangles.sort(Comparator.comparing(ProjectedTriangle::sortDepth).reversed());
    }

    var width = camera.width();
    var height = camera.height();
    var tilesX = (width + TILE_SIZE - 1) / TILE_SIZE;
    var tilesY = (height + TILE_SIZE - 1) / TILE_SIZE;
    @SuppressWarnings("unchecked")
    var bins = (ArrayList<ProjectedTriangle>[]) new ArrayList[tilesX * tilesY];
    for (var i = 0; i < bins.length; i++) {
      bins[i] = new ArrayList<>();
    }

    for (var triangle : triangles) {
      var minX = Math.max(0, (int) Math.floor(Math.min(triangle.v0().x(), Math.min(triangle.v1().x(), triangle.v2().x()))));
      var minY = Math.max(0, (int) Math.floor(Math.min(triangle.v0().y(), Math.min(triangle.v1().y(), triangle.v2().y()))));
      var maxX = Math.min(width - 1, (int) Math.ceil(Math.max(triangle.v0().x(), Math.max(triangle.v1().x(), triangle.v2().x()))));
      var maxY = Math.min(height - 1, (int) Math.ceil(Math.max(triangle.v0().y(), Math.max(triangle.v1().y(), triangle.v2().y()))));
      if (minX > maxX || minY > maxY) {
        continue;
      }

      var tileMinX = minX / TILE_SIZE;
      var tileMinY = minY / TILE_SIZE;
      var tileMaxX = maxX / TILE_SIZE;
      var tileMaxY = maxY / TILE_SIZE;
      for (var tileY = tileMinY; tileY <= tileMaxY; tileY++) {
        for (var tileX = tileMinX; tileX <= tileMaxX; tileX++) {
          bins[tileY * tilesX + tileX].add(triangle);
        }
      }
    }

    IntStream.range(0, bins.length).parallel().forEach(tileIndex -> {
      var tileX = tileIndex % tilesX;
      var tileY = tileIndex / tilesX;
      var minX = tileX * TILE_SIZE;
      var minY = tileY * TILE_SIZE;
      var maxX = Math.min(width - 1, minX + TILE_SIZE - 1);
      var maxY = Math.min(height - 1, minY + TILE_SIZE - 1);
      for (var triangle : bins[tileIndex]) {
        rasterizeTriangle(camera, animationTick, triangle, buffers, minX, minY, maxX, maxY);
      }
    });
  }

  private ArrayList<ProjectedTriangle> projectQuads(Camera camera, RenderQuad[] quads) {
    var triangles = new ArrayList<ProjectedTriangle>(quads.length * 2);
    for (var quad : quads) {
      emitProjectedTriangles(camera, quad, triangles);
    }
    return triangles;
  }

  private void emitProjectedTriangles(Camera camera, RenderQuad quad, ArrayList<ProjectedTriangle> out) {
    var viewProjection = camera.viewProjectionMatrix();
    var viewVertices = new ClipVertex[]{
      toClipVertex(viewProjection, quad.v0()),
      toClipVertex(viewProjection, quad.v1()),
      toClipVertex(viewProjection, quad.v2()),
      toClipVertex(viewProjection, quad.v3())
    };
    var sortDepth = sortDepth(camera, quad);
    var clipped = clipQuadToViewFrustum(viewVertices);
    if (clipped.length < 3) {
      return;
    }

    var projected = new ProjectedVertex[clipped.length];
    for (var i = 0; i < clipped.length; i++) {
      projected[i] = projectVertex(camera, clipped[i], quad.material().depthBias());
    }
    for (var i = 1; i < projected.length - 1; i++) {
      out.add(new ProjectedTriangle(
        projected[0],
        projected[i],
        projected[i + 1],
        quad.material(),
        sortDepth
      ));
    }
  }

  private ClipVertex[] clipQuadToViewFrustum(ClipVertex[] quad) {
    var vertices = new ArrayList<ClipVertex>(8);
    vertices.addAll(java.util.List.of(quad));

    for (var plane : ClipPlane.values()) {
      vertices = clipAgainstPlane(vertices, plane);
      if (vertices.isEmpty()) {
        return new ClipVertex[0];
      }
    }
    return vertices.toArray(ClipVertex[]::new);
  }

  private ArrayList<ClipVertex> clipAgainstPlane(ArrayList<ClipVertex> input, ClipPlane plane) {
    var output = new ArrayList<ClipVertex>(input.size() + 1);
    for (var i = 0; i < input.size(); i++) {
      var current = input.get(i);
      var next = input.get((i + 1) % input.size());
      var currentDistance = clipDistance(current, plane);
      var nextDistance = clipDistance(next, plane);
      var currentInside = currentDistance >= 0.0F;
      var nextInside = nextDistance >= 0.0F;

      if (currentInside && nextInside) {
        output.add(next);
      } else if (currentInside != nextInside) {
        var t = currentDistance / (currentDistance - nextDistance);
        output.add(interpolate(current, next, t));
        if (nextInside) {
          output.add(next);
        }
      }
    }
    return output;
  }

  private float clipDistance(ClipVertex vertex, ClipPlane plane) {
    return switch (plane) {
      case NEAR -> vertex.z() + vertex.w();
      case FAR -> vertex.w() - vertex.z();
      case LEFT -> vertex.x() + vertex.w();
      case RIGHT -> vertex.w() - vertex.x();
      case TOP -> vertex.w() - vertex.y();
      case BOTTOM -> vertex.y() + vertex.w();
    };
  }

  private ClipVertex interpolate(ClipVertex current, ClipVertex next, float t) {
    return new ClipVertex(
      current.x() + (next.x() - current.x()) * t,
      current.y() + (next.y() - current.y()) * t,
      current.z() + (next.z() - current.z()) * t,
      current.w() + (next.w() - current.w()) * t,
      current.u() + (next.u() - current.u()) * t,
      current.v() + (next.v() - current.v()) * t,
      current.a() + (next.a() - current.a()) * t,
      current.r() + (next.r() - current.r()) * t,
      current.g() + (next.g() - current.g()) * t,
      current.b() + (next.b() - current.b()) * t
    );
  }

  private ClipVertex toClipVertex(Matrix4f viewProjection, RenderVertex vertex) {
    var clip = viewProjection.transform(new Vector4f(vertex.x(), vertex.y(), vertex.z(), 1.0F));
    var color = vertex.color();
    return new ClipVertex(
      clip.x,
      clip.y,
      clip.z,
      clip.w,
      vertex.u(),
      vertex.v(),
      (color >>> 24) & 0xFF,
      (color >>> 16) & 0xFF,
      (color >>> 8) & 0xFF,
      color & 0xFF
    );
  }

  private ProjectedVertex projectVertex(Camera camera, ClipVertex vertex, float depthBias) {
    var inverseW = 1.0F / vertex.w();
    var ndcX = vertex.x() * inverseW;
    var ndcY = vertex.y() * inverseW;
    var ndcZ = vertex.z() * inverseW;
    var screenX = (ndcX * 0.5F + 0.5F) * camera.width();
    var screenY = (0.5F - ndcY * 0.5F) * camera.height();
    var biasedDepth = Math.clamp(ndcZ * 0.5F + 0.5F + depthBias * DEPTH_BIAS_SCALE, 0.0F, 1.0F);
    return new ProjectedVertex(
      screenX,
      screenY,
      biasedDepth,
      inverseW,
      vertex.u() * inverseW,
      vertex.v() * inverseW,
      vertex.a() * inverseW,
      vertex.r() * inverseW,
      vertex.g() * inverseW,
      vertex.b() * inverseW
    );
  }

  private float sortDepth(Camera camera, RenderQuad quad) {
    var x = (quad.v0().x() + quad.v1().x() + quad.v2().x() + quad.v3().x()) * 0.25F - (float) camera.eyeX();
    var y = (quad.v0().y() + quad.v1().y() + quad.v2().y() + quad.v3().y()) * 0.25F - (float) camera.eyeY();
    var z = (quad.v0().z() + quad.v1().z() + quad.v2().z() + quad.v3().z()) * 0.25F - (float) camera.eyeZ();
    return x * x + y * y + z * z;
  }

  private void rasterizeTriangle(
    Camera camera,
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    int clipMinX,
    int clipMinY,
    int clipMaxX,
    int clipMaxY
  ) {
    var v0 = triangle.v0();
    var v1 = triangle.v1();
    var v2 = triangle.v2();
    var area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
    if (Math.abs(area) < 1.0E-5F) {
      return;
    }
    var material = triangle.material();
    if (!material.doubleSided() && area <= 0.0F) {
      return;
    }
    var topLeft0 = isTopLeft(v1.x(), v1.y(), v2.x(), v2.y());
    var topLeft1 = isTopLeft(v2.x(), v2.y(), v0.x(), v0.y());
    var topLeft2 = isTopLeft(v0.x(), v0.y(), v1.x(), v1.y());

    var width = camera.width();
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
        if (!isInside(area, w0, w1, w2, topLeft0, topLeft1, topLeft2)) {
          continue;
        }

        var normalizedW0 = w0 / area;
        var normalizedW1 = w1 / area;
        var normalizedW2 = w2 / area;
        var depth = normalizedW0 * v0.depth() + normalizedW1 * v1.depth() + normalizedW2 * v2.depth();
        var rasterIndex = y * width + x;
        if (!material.depthTest().passes(depth, depthBuffer[rasterIndex])) {
          continue;
        }

        var inverseW = normalizedW0 * v0.inverseW() + normalizedW1 * v1.inverseW() + normalizedW2 * v2.inverseW();
        var u = (normalizedW0 * v0.uOverW() + normalizedW1 * v1.uOverW() + normalizedW2 * v2.uOverW()) / inverseW;
        var v = (normalizedW0 * v0.vOverW() + normalizedW1 * v1.vOverW() + normalizedW2 * v2.vOverW()) / inverseW;
        var sampled = material.texture().sample(
          material.uvTransform().u(u, v, animationTick),
          material.uvTransform().v(u, v, animationTick),
          animationTick
        );
        var vertexColor = interpolatedColor(normalizedW0, normalizedW1, normalizedW2, inverseW, v0, v1, v2);
        var color = modulate(modulate(sampled, vertexColor), material.color());
        var alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
          continue;
        }
        if (material.alphaCutoutThreshold() > 0 && alpha < material.alphaCutoutThreshold()) {
          continue;
        }

        if (material.alphaMode() != RendererAssets.AlphaMode.TRANSLUCENT) {
          if (material.depthWrite()) {
            depthBuffer[rasterIndex] = depth;
          }
          writeColor(colorBuffer, rasterIndex, forceOpaque(color), material);
          continue;
        }

        writeColor(colorBuffer, rasterIndex, color, material);
        if (material.depthWrite()) {
          depthBuffer[rasterIndex] = depth;
        }
      }
    }
  }

  private int interpolatedColor(
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

  private int colorChannel(float value) {
    return Math.clamp(Math.round(value), 0, 255);
  }

  private boolean isInside(float area, float w0, float w1, float w2, boolean topLeft0, boolean topLeft1, boolean topLeft2) {
    var epsilon = 1.0E-5F;
    if (area > 0.0F) {
      return edgeInclusive(w0, topLeft0, epsilon) && edgeInclusive(w1, topLeft1, epsilon) && edgeInclusive(w2, topLeft2, epsilon);
    }
    return edgeInclusive(-w0, topLeft0, epsilon) && edgeInclusive(-w1, topLeft1, epsilon) && edgeInclusive(-w2, topLeft2, epsilon);
  }

  private float edge(float ax, float ay, float bx, float by, float px, float py) {
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
  }

  private boolean edgeInclusive(float edgeValue, boolean topLeft, float epsilon) {
    return edgeValue > epsilon || (Math.abs(edgeValue) <= epsilon && topLeft);
  }

  private boolean isTopLeft(float ax, float ay, float bx, float by) {
    var dy = by - ay;
    var dx = bx - ax;
    return dy < 0.0F || (dy == 0.0F && dx > 0.0F);
  }

  private int modulate(int sample, int multiplier) {
    var a = ((sample >>> 24) & 0xFF) * ((multiplier >>> 24) & 0xFF) / 255;
    var r = ((sample >> 16) & 0xFF) * ((multiplier >> 16) & 0xFF) / 255;
    var g = ((sample >> 8) & 0xFF) * ((multiplier >> 8) & 0xFF) / 255;
    var b = (sample & 0xFF) * (multiplier & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private int forceOpaque(int color) {
    return 0xFF000000 | (color & 0x00FFFFFF);
  }

  private void writeColor(int[] colorBuffer, int rasterIndex, int srcColor, RenderMaterial material) {
    if (material.colorWriteMask() == ColorTargetState.WRITE_NONE) {
      return;
    }

    var dstColor = colorBuffer[rasterIndex];
    var output = material.blendState().blends() ? blend(dstColor, srcColor, material.blendState()) : srcColor;
    colorBuffer[rasterIndex] = applyColorWriteMask(dstColor, output, material.colorWriteMask());
  }

  private int applyColorWriteMask(int dstColor, int output, int writeMask) {
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

  private int blend(int dstColor, int srcColor, RenderMaterial.BlendState blendState) {
    var dstR = (dstColor >> 16) & 0xFF;
    var dstG = (dstColor >> 8) & 0xFF;
    var dstB = dstColor & 0xFF;
    var dstA = (dstColor >>> 24) & 0xFF;
    var srcA = (srcColor >>> 24) & 0xFF;
    var srcR = (srcColor >> 16) & 0xFF;
    var srcG = (srcColor >> 8) & 0xFF;
    var srcB = srcColor & 0xFF;
    var outR = blendChannel(srcR, dstR, srcR, dstR, srcA, dstA, blendState.sourceColor(), blendState.destColor());
    var outG = blendChannel(srcG, dstG, srcG, dstG, srcA, dstA, blendState.sourceColor(), blendState.destColor());
    var outB = blendChannel(srcB, dstB, srcB, dstB, srcA, dstA, blendState.sourceColor(), blendState.destColor());
    var outA = blendChannel(srcA, dstA, srcA, dstA, srcA, dstA, blendState.sourceAlpha(), blendState.destAlpha());
    return (outA << 24) | (outR << 16) | (outG << 8) | outB;
  }

  private int blendChannel(
    int srcChannel,
    int dstChannel,
    int srcColorChannel,
    int dstColorChannel,
    int srcAlpha,
    int dstAlpha,
    SourceFactor sourceFactor,
    DestFactor destFactor
  ) {
    var srcScale = sourceFactor(sourceFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha);
    var dstScale = destFactor(destFactor, srcColorChannel, dstColorChannel, srcAlpha, dstAlpha);
    return Math.clamp(Math.round(srcChannel * srcScale + dstChannel * dstScale), 0, 255);
  }

  private float sourceFactor(SourceFactor factor, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
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
      case CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR, CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA -> 0.0F;
    };
  }

  private float destFactor(DestFactor factor, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
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
      case CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR, CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA -> 0.0F;
    };
  }

  private enum ClipPlane {
    NEAR,
    FAR,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM
  }

  private void recordTriangleCount(RasterPassKind passKind, int count) {
    switch (passKind) {
      case OPAQUE -> RenderDebugTrace.current().opaqueTriangles(count);
      case CUTOUT -> RenderDebugTrace.current().cutoutTriangles(count);
      case TRANSLUCENT -> RenderDebugTrace.current().translucentTriangles(count);
      case UNTRACKED -> {
      }
    }
  }

  private enum RasterPassKind {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
    UNTRACKED
  }

  private record ClipVertex(float x, float y, float z, float w, float u, float v, float a, float r, float g, float b) {}
}
