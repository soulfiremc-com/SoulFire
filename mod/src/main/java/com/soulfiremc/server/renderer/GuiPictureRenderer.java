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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.OversizedItemRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GuiPictureRenderer {
  private GuiPictureRenderer() {}

  static BufferedImage renderItem(RenderContext ctx, TrackingItemStackRenderState state, int scale) {
    var size = 16 * scale;
    var pose = new PoseStack();
    pose.translate(size / 2.0F, size / 2.0F, 0);
    pose.scale(size, -size, size);
    var collector = new VanillaSubmitCollector(ctx, state.usesBlockLight() ? GuiLighting.BLOCK : GuiLighting.FLAT);
    state.submit(pose, collector, 0x00F000F0, OverlayTexture.NO_OVERLAY, 0);
    return rasterize(collector.buildScene(), size, size, new Matrix4f(), ctx.animationTick());
  }

  static BufferedImage render(RenderContext ctx, PictureInPictureRenderState state, int scale) {
    if (state instanceof OversizedItemRenderState) {
      try (var renderer = new OversizedItemRenderer()) {
        return render(ctx, state, scale, renderer);
      }
    }
    var renderer = Minecraft.getInstance().gameRenderer.guiRenderer.pictureInPictureRenderers.get(state.getClass());
    if (renderer == null) {
      throw new IllegalArgumentException("Unsupported GUI preview: " + state.getClass().getName());
    }
    return render(ctx, state, scale, renderer);
  }

  @SuppressWarnings("unchecked")
  private static BufferedImage render(RenderContext ctx, PictureInPictureRenderState state, int scale,
                                      PictureInPictureRenderer<?> renderer) {
    var width = Math.max(1, (state.x1() - state.x0()) * scale);
    var height = Math.max(1, (state.y1() - state.y0()) * scale);
    var pose = new PoseStack();
    pose.translate(width / 2.0F, renderer.getTranslateY(height, scale), 0);
    var modelScale = scale * state.scale();
    pose.scale(modelScale, modelScale, -modelScale);
    var lighting = state instanceof OversizedItemRenderState item
      ? (item.guiItemRenderState().itemStackRenderState().usesBlockLight() ? GuiLighting.BLOCK : GuiLighting.FLAT)
      : GuiLighting.ENTITY;
    var collector = new VanillaSubmitCollector(ctx, lighting);
    var modelView = RenderSystem.getModelViewStack();
    synchronized (modelView) {
      modelView.pushMatrix();
      try {
        modelView.identity();
        ((PictureInPictureRenderer<PictureInPictureRenderState>) renderer).renderToTexture(state, pose, collector);
        return rasterize(collector.buildScene(), width, height, new Matrix4f(modelView), ctx.animationTick());
      } finally {
        modelView.popMatrix();
      }
    }
  }

  static BufferedImage rasterize(SceneData scene, int width, int height, Matrix4f transform, long tick) {
    var buffers = new RasterBuffers(width, height);
    draw(scene.opaque(), buffers, transform, tick, true);
    draw(scene.cutout(), buffers, transform, tick, true);
    for (var quads : List.of(scene.translucent(), scene.terrainTranslucent(), scene.translucentParticles(), scene.clouds(), scene.weather())) {
      draw(quads, buffers, transform, tick, false);
    }
    return buffers.image();
  }

  private static void draw(RenderQuad[] quads, RasterBuffers buffers, Matrix4f transform, long tick, boolean depth) {
    var triangles = new ArrayList<ProjectedTriangle>();
    for (var quad : quads) {
      var a = project(quad.v0(), transform, quad.material().depthBias());
      var b = project(quad.v1(), transform, quad.material().depthBias());
      var c = project(quad.v2(), transform, quad.material().depthBias());
      var d = project(quad.v3(), transform, quad.material().depthBias());
      var sortDepth = (a.depth() + b.depth() + c.depth() + d.depth()) / 4;
      triangles.add(new ProjectedTriangle(a, b, c, quad.material(), sortDepth));
      triangles.add(new ProjectedTriangle(a, c, d, quad.material(), sortDepth));
    }
    if (!depth) {
      triangles.sort(Comparator.comparingDouble(ProjectedTriangle::sortDepth).reversed());
    }
    for (var triangle : triangles) {
      SoftwareRasterizer.rasterizeGuiItemTriangle(tick, triangle, buffers, depth);
    }
  }

  private static ProjectedVertex project(RenderVertex vertex, Matrix4f transform, float depthBias) {
    var position = transform.transformPosition(vertex.x(), vertex.y(), vertex.z(), new Vector3f());
    return new ProjectedVertex(position.x(), position.y(), (1000.0F - position.z() + depthBias) / 2000.0F, 1, vertex.u(), vertex.v(),
      (vertex.color() >>> 24) & 255, ((vertex.color() >>> 16) & 255) * vertex.shade(), ((vertex.color() >>> 8) & 255) * vertex.shade(), (vertex.color() & 255) * vertex.shade());
  }
}
