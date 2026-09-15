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

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.IntStream;

/// Projects and rasterizes scene geometry into the target buffers.
public final class RasterPipeline {
  private static final int TILE_SIZE = 32;

  public void render(RenderContext ctx, SceneData sceneData, RasterBuffers buffers) {
    renderSky(ctx, buffers);
    renderScene(ctx.camera(), sceneData, buffers, ctx.animationTick(), RasterFogState.from(ctx));
  }

  public void renderScene(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick) {
    renderScene(camera, sceneData, buffers, animationTick, RasterFogState.DISABLED);
  }

  public void renderFirstPersonOverlay(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick) {
    renderFirstPersonOverlay(camera, sceneData, buffers, animationTick, RasterFogState.DISABLED);
  }

  void renderFirstPersonOverlay(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, RasterFogState fogState) {
    if (sceneData.totalQuadCount() == 0) {
      return;
    }

    buffers.clearDepth();
    renderScene(camera, sceneData, buffers, animationTick, fogState);
  }

  void renderScene(Camera camera, SceneData sceneData, RasterBuffers buffers, long animationTick, RasterFogState fogState) {
    rasterPass(camera, animationTick, sceneData.opaque(), buffers, false, RasterPassKind.OPAQUE, fogState);
    rasterPass(camera, animationTick, sceneData.cutout(), buffers, false, RasterPassKind.CUTOUT, fogState);
    rasterPass(camera, animationTick, sceneData.translucent(), buffers, true, RasterPassKind.TRANSLUCENT, fogState);
    rasterPass(camera, animationTick, sceneData.terrainTranslucent(), buffers, true, RasterPassKind.TRANSLUCENT, fogState);
    rasterPass(camera, animationTick, sceneData.translucentParticles(), buffers, true, RasterPassKind.TRANSLUCENT, fogState);
    rasterPass(camera.atOrigin(), animationTick, sceneData.clouds(), buffers, false, RasterPassKind.TRANSLUCENT, fogState);
    rasterPass(camera, animationTick, sceneData.weather(), buffers, false, RasterPassKind.TRANSLUCENT, fogState);
  }

  private void renderSky(RenderContext ctx, RasterBuffers buffers) {
    SkyRenderer.renderBackground(ctx, buffers);
    rasterPass(ctx.camera(), ctx.animationTick(), SkyRenderer.collectSkyQuads(ctx), buffers, false, RasterPassKind.UNTRACKED, RasterFogState.DISABLED);
    buffers.clearDepth();
  }

  private void rasterPass(
    Camera camera,
    long animationTick,
    RenderQuad[] quads,
    RasterBuffers buffers,
    boolean sortBackToFront,
    RasterPassKind passKind,
    RasterFogState fogState
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
      sortSortableTranslucentRuns(triangles);
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
        SoftwareRasterizer.rasterizeWorldTriangle(camera, animationTick, triangle, buffers, minX, minY, maxX, maxY, fogState);
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
    var viewRotation = camera.viewRotationMatrix();
    var projection = camera.projectionMatrix();
    var clipTransform = new Matrix4f(projection).mul(viewRotation);
    var material = quad.material();
    var viewVertices = new ClipVertex[]{
      toClipVertex(camera, clipTransform, material.viewScale(), quad.v0()),
      toClipVertex(camera, clipTransform, material.viewScale(), quad.v1()),
      toClipVertex(camera, clipTransform, material.viewScale(), quad.v2()),
      toClipVertex(camera, clipTransform, material.viewScale(), quad.v3())
    };
    for (var vertex : viewVertices) {
      if (!isFinite(vertex)) {
        return;
      }
    }

    var sortDepth = sortDepth(camera, quad);
    emitClippedTriangle(camera, viewVertices[0], viewVertices[1], viewVertices[2], material, sortDepth, out);
    emitClippedTriangle(camera, viewVertices[2], viewVertices[3], viewVertices[0], material, sortDepth, out);
  }

  private void emitClippedTriangle(Camera camera, ClipVertex v0, ClipVertex v1, ClipVertex v2,
                                   RenderMaterial material, float sortDepth, ArrayList<ProjectedTriangle> out) {
    var clipped = clipToViewFrustum(new ClipVertex[]{v0, v1, v2});
    if (clipped.length < 3) {
      return;
    }
    var projected = new ProjectedVertex[clipped.length];
    for (var i = 0; i < clipped.length; i++) {
      projected[i] = projectVertex(camera, clipped[i], clipped[i] != v0 && clipped[i] != v1 && clipped[i] != v2);
      if (!isFinite(projected[i])) {
        return;
      }
    }
    for (var i = 1; i < projected.length - 1; i++) {
      out.add(new ProjectedTriangle(projected[0], projected[i], projected[i + 1], material, sortDepth));
    }
  }

  private void sortSortableTranslucentRuns(ArrayList<ProjectedTriangle> triangles) {
    var runStart = -1;
    var sortGroup = 0;
    for (var i = 0; i <= triangles.size(); i++) {
      var sortable = i < triangles.size() && triangles.get(i).material().sortOnUpload();
      var sameGroup = sortable && (runStart < 0 || triangles.get(i).material().sortGroup() == sortGroup);
      if (sortable && runStart < 0) {
        runStart = i;
        sortGroup = triangles.get(i).material().sortGroup();
      } else if (sortable && !sameGroup) {
        triangles.subList(runStart, i).sort(Comparator.comparing(ProjectedTriangle::sortDepth).reversed());
        runStart = i;
        sortGroup = triangles.get(i).material().sortGroup();
      } else if (!sortable && runStart >= 0) {
        triangles.subList(runStart, i).sort(Comparator.comparing(ProjectedTriangle::sortDepth).reversed());
        runStart = -1;
      }
    }
  }

  private ClipVertex[] clipToViewFrustum(ClipVertex[] quad) {
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

      if (currentInside) {
        output.add(current);
      }
      if (currentInside != nextInside) {
        if (Math.abs(currentDistance) < Math.abs(nextDistance)) {
          output.add(interpolate(current, next, currentDistance / (currentDistance - nextDistance)));
        } else {
          output.add(interpolate(next, current, nextDistance / (nextDistance - currentDistance)));
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
      current.sphericalFogDistance() + (next.sphericalFogDistance() - current.sphericalFogDistance()) * t,
      current.cylindricalFogDistance() + (next.cylindricalFogDistance() - current.cylindricalFogDistance()) * t,
      current.u() + (next.u() - current.u()) * t,
      current.v() + (next.v() - current.v()) * t,
      current.a() + (next.a() - current.a()) * t,
      current.r() + (next.r() - current.r()) * t,
      current.g() + (next.g() - current.g()) * t,
      current.b() + (next.b() - current.b()) * t,
      current.overlayA() + (next.overlayA() - current.overlayA()) * t,
      current.overlayR() + (next.overlayR() - current.overlayR()) * t,
      current.overlayG() + (next.overlayG() - current.overlayG()) * t,
      current.overlayB() + (next.overlayB() - current.overlayB()) * t
    );
  }

  private ClipVertex toClipVertex(Camera camera, Matrix4f transform, float viewScale, RenderVertex vertex) {
    var relativeX = (float) (vertex.x() - camera.eyeX());
    var relativeY = (float) (vertex.y() - camera.eyeY());
    var relativeZ = (float) (vertex.z() - camera.eyeZ());
    var sphericalFogDistance = (float) Math.sqrt(Math.fma(relativeX, relativeX, Math.fma(relativeY, relativeY, relativeZ * relativeZ)));
    var cylindricalFogDistance = Math.max((float) Math.sqrt(Math.fma(relativeX, relativeX, relativeZ * relativeZ)), Math.abs(relativeY));
    var position = new Vector4f(
      relativeX,
      relativeY,
      relativeZ,
      1.0F
    );
    if (viewScale != 1.0F) {
      position.mul(viewScale, viewScale, viewScale, 1.0F);
    }
    var clip = new Vector4f(
      Math.fma(transform.m00(), position.x, Math.fma(transform.m10(), position.y, Math.fma(transform.m20(), position.z, transform.m30()))),
      Math.fma(transform.m01(), position.x, Math.fma(transform.m11(), position.y, Math.fma(transform.m21(), position.z, transform.m31()))),
      Math.fma(transform.m02(), position.x, Math.fma(transform.m12(), position.y, Math.fma(transform.m22(), position.z, transform.m32()))),
      Math.fma(transform.m03(), position.x, Math.fma(transform.m13(), position.y, Math.fma(transform.m23(), position.z, transform.m33())))
    );
    var overlayColor = vertex.overlayColor();
    return new ClipVertex(
      clip.x,
      clip.y,
      clip.z,
      clip.w,
      sphericalFogDistance,
      cylindricalFogDistance,
      vertex.u(),
      vertex.v(),
      vertex.colorChannel(24),
      vertex.colorChannel(16) * vertex.shade() * (((vertex.lightColor() >>> 16) & 255) * (1.0F / 255.0F)),
      vertex.colorChannel(8) * vertex.shade() * (((vertex.lightColor() >>> 8) & 255) * (1.0F / 255.0F)),
      vertex.colorChannel(0) * vertex.shade() * ((vertex.lightColor() & 255) * (1.0F / 255.0F)),
      (overlayColor >>> 24) & 0xFF,
      (overlayColor >>> 16) & 0xFF,
      (overlayColor >>> 8) & 0xFF,
      overlayColor & 0xFF
    );
  }

  private ProjectedVertex projectVertex(Camera camera, ClipVertex vertex, boolean clipped) {
    var inverseW = 1.0F / vertex.w();
    var ndcX = vertex.x() * inverseW;
    var ndcY = vertex.y() * inverseW;
    var screenX = clipped ? ndcX * (camera.width() * 0.5F) + camera.width() * 0.5F : Math.fma(ndcX, camera.width() * 0.5F, camera.width() * 0.5F);
    var windowY = clipped ? ndcY * (camera.height() * 0.5F) + camera.height() * 0.5F : Math.fma(ndcY, camera.height() * 0.5F, camera.height() * 0.5F);
    var screenY = camera.height() - windowY;
    // Keep depth precision through projection instead of rounding clip Z before the divide.
    var projection = camera.projectionMatrix();
    var depth = Math.clamp((-projection.m22() + projection.m32() / (double) vertex.w()) * 0.5 + 0.5, 0.0, 1.0);
    return new ProjectedVertex(
      screenX,
      screenY,
      depth,
      inverseW,
      vertex.u() * inverseW,
      vertex.v() * inverseW,
      vertex.sphericalFogDistance() * inverseW,
      vertex.cylindricalFogDistance() * inverseW,
      vertex.a() * inverseW,
      vertex.r() * inverseW,
      vertex.g() * inverseW,
      vertex.b() * inverseW,
      vertex.overlayA() * inverseW,
      vertex.overlayR() * inverseW,
      vertex.overlayG() * inverseW,
      vertex.overlayB() * inverseW,
      windowY
    );
  }

  private boolean isFinite(ClipVertex vertex) {
    return Float.isFinite(vertex.x())
      && Float.isFinite(vertex.y())
      && Float.isFinite(vertex.z())
      && Float.isFinite(vertex.w())
      && Float.isFinite(vertex.sphericalFogDistance())
      && Float.isFinite(vertex.cylindricalFogDistance())
      && Float.isFinite(vertex.u())
      && Float.isFinite(vertex.v())
      && Float.isFinite(vertex.a())
      && Float.isFinite(vertex.r())
      && Float.isFinite(vertex.g())
      && Float.isFinite(vertex.b())
      && Float.isFinite(vertex.overlayA())
      && Float.isFinite(vertex.overlayR())
      && Float.isFinite(vertex.overlayG())
      && Float.isFinite(vertex.overlayB());
  }

  private boolean isFinite(ProjectedVertex vertex) {
    return Float.isFinite(vertex.x())
      && Float.isFinite(vertex.y())
      && Double.isFinite(vertex.depth())
      && Float.isFinite(vertex.inverseW())
      && Float.isFinite(vertex.uOverW())
      && Float.isFinite(vertex.vOverW())
      && Float.isFinite(vertex.sphericalFogDistanceOverW())
      && Float.isFinite(vertex.cylindricalFogDistanceOverW())
      && Float.isFinite(vertex.aOverW())
      && Float.isFinite(vertex.rOverW())
      && Float.isFinite(vertex.gOverW())
      && Float.isFinite(vertex.bOverW())
      && Float.isFinite(vertex.overlayAOverW())
      && Float.isFinite(vertex.overlayROverW())
      && Float.isFinite(vertex.overlayGOverW())
      && Float.isFinite(vertex.overlayBOverW());
  }

  private float sortDepth(Camera camera, RenderQuad quad) {
    var x = (quad.v0().x() + quad.v2().x()) * 0.5 - camera.eyeX();
    var y = (quad.v0().y() + quad.v2().y()) * 0.5 - camera.eyeY();
    var z = (quad.v0().z() + quad.v2().z()) * 0.5 - camera.eyeZ();
    return (float) Math.min(x * x + y * y + z * z, Float.MAX_VALUE);
  }

  private enum ClipPlane {
    RIGHT,
    LEFT,
    TOP,
    BOTTOM,
    FAR,
    NEAR
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

  private record ClipVertex(
    float x,
    float y,
    float z,
    float w,
    float sphericalFogDistance,
    float cylindricalFogDistance,
    float u,
    float v,
    float a,
    float r,
    float g,
    float b,
    float overlayA,
    float overlayR,
    float overlayG,
    float overlayB
  ) {}
}
