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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.IntStream;

/// Projects and rasterizes scene geometry into the target buffers.
public final class RasterPipeline {
  private static final int TILE_SIZE = 32;
  private static final int CUTOUT_ALPHA_THRESHOLD = 51;

  public void render(RenderContext ctx, SceneData sceneData, RasterBuffers buffers) {
    renderSky(ctx, buffers);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.opaque(), buffers, true, false, false);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.cutout(), buffers, true, true, false);
    rasterPass(ctx.camera(), ctx.animationTick(), sceneData.translucent(), buffers, false, false, true);
  }

  public void renderSynthetic(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, int clearColor) {
    buffers.clearColor(clearColor);
    buffers.clearDepth();
    rasterPass(camera, animationTick, sceneData.opaque(), buffers, true, false, false);
    rasterPass(camera, animationTick, sceneData.cutout(), buffers, true, true, false);
    rasterPass(camera, animationTick, sceneData.translucent(), buffers, false, false, true);
  }

  private void renderSky(RenderContext ctx, RasterBuffers buffers) {
    var camera = ctx.camera();
    var pixels = buffers.colorBuffer();
    var width = camera.width();
    IntStream.range(0, camera.height()).parallel().forEach(y -> {
      var rowOffset = y * width;
      for (var x = 0; x < width; x++) {
        pixels[rowOffset + x] = SkyRenderer.sampleSky(
          ctx,
          camera.sampleDirX(x, y),
          camera.sampleDirY(x, y),
          camera.sampleDirZ(x, y)
        );
      }
    });
    buffers.clearDepth();
  }

  private void rasterPass(
    Camera camera,
    long animationTick,
    RenderQuad[] quads,
    RasterBuffers buffers,
    boolean writeDepth,
    boolean alphaTest,
    boolean sortBackToFront
  ) {
    if (quads.length == 0) {
      return;
    }

    var triangles = projectQuads(camera, quads);
    if (sortBackToFront) {
      RenderDebugTrace.current().translucentTriangles(triangles.size());
    } else if (alphaTest) {
      RenderDebugTrace.current().cutoutTriangles(triangles.size());
    } else {
      RenderDebugTrace.current().opaqueTriangles(triangles.size());
    }
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
        rasterizeTriangle(camera, animationTick, triangle, buffers, minX, minY, maxX, maxY, writeDepth, alphaTest, sortBackToFront);
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
    var clipped = clipQuadToNearPlane(camera, quad);
    if (clipped.length < 3) {
      return;
    }

    var projected = new ProjectedVertex[clipped.length];
    var totalDepth = 0.0F;
    for (var i = 0; i < clipped.length; i++) {
      projected[i] = projectVertex(camera, clipped[i], quad.depthBias());
      totalDepth += clipped[i].z();
    }
    var sortDepth = totalDepth / clipped.length;
    for (var i = 1; i < projected.length - 1; i++) {
      out.add(new ProjectedTriangle(
        projected[0],
        projected[i],
        projected[i + 1],
        quad.texture(),
        quad.alphaMode(),
        quad.color(),
        quad.doubleSided(),
        sortDepth
      ));
    }
  }

  private ClipVertex[] clipQuadToNearPlane(Camera camera, RenderQuad quad) {
    var input = new ClipVertex[]{
      toClipVertex(camera, quad.v0()),
      toClipVertex(camera, quad.v1()),
      toClipVertex(camera, quad.v2()),
      toClipVertex(camera, quad.v3())
    };
    var output = new ArrayList<ClipVertex>(6);
    var near = camera.nearPlane();
    for (var i = 0; i < input.length; i++) {
      var current = input[i];
      var next = input[(i + 1) % input.length];
      var currentInside = current.z() >= near;
      var nextInside = next.z() >= near;

      if (currentInside && nextInside) {
        output.add(next);
      } else if (currentInside != nextInside) {
        var t = (near - current.z()) / (next.z() - current.z());
        var intersection = new ClipVertex(
          current.x() + (next.x() - current.x()) * t,
          current.y() + (next.y() - current.y()) * t,
          near,
          current.u() + (next.u() - current.u()) * t,
          current.v() + (next.v() - current.v()) * t
        );
        output.add(intersection);
        if (nextInside) {
          output.add(next);
        }
      }
    }
    return output.toArray(ClipVertex[]::new);
  }

  private ClipVertex toClipVertex(Camera camera, RenderVertex vertex) {
    return new ClipVertex(
      camera.viewX(vertex.x(), vertex.y(), vertex.z()),
      camera.viewY(vertex.x(), vertex.y(), vertex.z()),
      camera.viewZ(vertex.x(), vertex.y(), vertex.z()),
      vertex.u(),
      vertex.v()
    );
  }

  private ProjectedVertex projectVertex(Camera camera, ClipVertex vertex, float depthBias) {
    var ndcX = vertex.x() / (vertex.z() * camera.tanHalfFovX());
    var ndcY = vertex.y() / (vertex.z() * camera.tanHalfFovY());
    // SoulFire's historical camera convention maps positive camera X toward the left side of the screen.
    var screenX = (float) ((0.5 - ndcX * 0.5) * camera.width());
    var screenY = (float) ((0.5 - ndcY * 0.5) * camera.height());
    var inverseDepth = 1.0F / vertex.z();
    return new ProjectedVertex(
      screenX,
      screenY,
      vertex.z() + depthBias,
      inverseDepth,
      vertex.u() * inverseDepth,
      vertex.v() * inverseDepth
    );
  }

  private void rasterizeTriangle(
    Camera camera,
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    int clipMinX,
    int clipMinY,
    int clipMaxX,
    int clipMaxY,
    boolean writeDepth,
    boolean alphaTest,
    boolean translucent
  ) {
    var v0 = triangle.v0();
    var v1 = triangle.v1();
    var v2 = triangle.v2();
    var area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
    if (Math.abs(area) < 1.0E-5F) {
      return;
    }
    if (!triangle.doubleSided() && area <= 0.0F) {
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
        if (depth >= depthBuffer[rasterIndex]) {
          continue;
        }

        var inverseDepth = normalizedW0 * v0.inverseDepth() + normalizedW1 * v1.inverseDepth() + normalizedW2 * v2.inverseDepth();
        var u = (normalizedW0 * v0.uOverDepth() + normalizedW1 * v1.uOverDepth() + normalizedW2 * v2.uOverDepth()) / inverseDepth;
        var v = (normalizedW0 * v0.vOverDepth() + normalizedW1 * v1.vOverDepth() + normalizedW2 * v2.vOverDepth()) / inverseDepth;
        var sampled = triangle.texture().sample(u, v, animationTick);
        var color = modulate(sampled, triangle.color());
        var alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
          continue;
        }
        if (alphaTest && alpha < CUTOUT_ALPHA_THRESHOLD) {
          continue;
        }

        if (triangle.alphaMode() == RendererAssets.AlphaMode.OPAQUE || alphaTest) {
          depthBuffer[rasterIndex] = depth;
          colorBuffer[rasterIndex] = forceOpaque(color);
          continue;
        }

        if (translucent) {
          colorBuffer[rasterIndex] = blend(colorBuffer[rasterIndex], color);
          if (writeDepth) {
            depthBuffer[rasterIndex] = depth;
          }
        } else {
          if (writeDepth) {
            depthBuffer[rasterIndex] = depth;
          }
          colorBuffer[rasterIndex] = forceOpaque(color);
        }
      }
    }
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

  private int blend(int dstColor, int srcColor) {
    var dstR = (dstColor >> 16) & 0xFF;
    var dstG = (dstColor >> 8) & 0xFF;
    var dstB = dstColor & 0xFF;
    var srcA = (srcColor >>> 24) & 0xFF;
    var srcR = (srcColor >> 16) & 0xFF;
    var srcG = (srcColor >> 8) & 0xFF;
    var srcB = srcColor & 0xFF;
    var srcAlpha = srcA / 255.0F;
    var invAlpha = 1.0F - srcAlpha;
    var outR = (int) (srcR * srcAlpha + dstR * invAlpha);
    var outG = (int) (srcG * srcAlpha + dstG * invAlpha);
    var outB = (int) (srcB * srcAlpha + dstB * invAlpha);
    return 0xFF000000 | (outR << 16) | (outG << 8) | outB;
  }

  private record ClipVertex(float x, float y, float z, float u, float v) {}
}
