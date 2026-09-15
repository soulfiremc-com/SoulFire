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

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PovGuiRendererTest {
  @Test
  void exportedOversizedIconFitsWithoutLosingItsEdges() {
    var source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    for (var y = 8; y < 40; y++) {
      for (var x = 4; x < 60; x++) {
        source.setRGB(x, y, x < 32 ? 0xFFFF0000 : 0xFF00FF00);
      }
    }
    var image = InventoryItemIconRenderer.fitFramesToSlot(List.of(source)).getFirst();
    assertEquals(0, image.getRGB(1, 16));
    assertEquals(0xFFFF0000, image.getRGB(2, 16));
    assertEquals(0xFF00FF00, image.getRGB(29, 16));
    assertEquals(0, image.getRGB(30, 16));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void itemProjectionPreservesModelOffsetsAtGuiScale(int scale) {
    var vertex = new RenderVertex(-0.25F, 0.125F, 0, 0, 0, -1);
    var projected = InventoryItemIconRenderer.projectVertex(vertex, 0, 16 * scale, 16 * scale, 16 * scale);
    assertEquals(4 * scale, projected.x());
    assertEquals(10 * scale, projected.y());
  }

  @Test
  void itemProjectionKeepsBothSidesInsideDepthRange() {
    var near = InventoryItemIconRenderer.projectVertex(new RenderVertex(0, 0, 2, 0, 0, -1), 0, 16, 16, 16);
    var far = InventoryItemIconRenderer.projectVertex(new RenderVertex(0, 0, -2, 0, 0, -1), 0, 16, 16, 16);
    assertTrue(near.depth() > 0);
    assertTrue(near.depth() < far.depth());
    assertTrue(far.depth() < 1);
  }

  @Test
  void preparedItemStaysBelowOverlappingTooltip() {
    var state = new GuiRenderState();
    state.addItem(new GuiItemRenderState(new Matrix3x2f(), new TrackingItemStackRenderState(), 0, 0, null));
    state.addGuiElement(rect(4, 4, 12, 12, 0xFF00FF00, null));
    var images = new IdentityHashMap<GuiElementRenderState, RendererAssets.TextureImage>();
    var icon = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    icon.setRGB(0, 0, 0xFFFF0000);
    state.forEachItem(item -> PovHudRenderer.addImage(state, images, icon, item.pose(), 0, 0, 16, 16, null));
    var buffers = new RasterBuffers(16, 16);
    PovHudRenderer.renderPreparedState(state, buffers, 16, 16, 0, 0, images);
    assertEquals(0xFF00FF00, buffers.image().getRGB(8, 8));
    assertEquals(0xFFFF0000, buffers.image().getRGB(2, 2));
  }

  @Test
  void scissorUsesExclusiveRightAndBottomEdgesAtOutputScale() {
    var state = new GuiRenderState();
    state.addGuiElement(rect(0, 0, 8, 8, 0xFFFFFFFF, new ScreenRectangle(2, 2, 2, 2)));
    var buffers = new RasterBuffers(16, 16);
    PovHudRenderer.renderPreparedState(state, buffers, 8, 8, 0, 0, Map.of());
    assertEquals(0xFFFFFFFF, buffers.image().getRGB(7, 7));
    assertEquals(0, buffers.image().getRGB(8, 7));
    assertEquals(0, buffers.image().getRGB(7, 8));
  }

  @Test
  void fractionalViewportDoesNotStretchGuiPixels() {
    var state = new GuiRenderState();
    state.addGuiElement(rect(2, 2, 4, 4, -1, null));
    var buffers = new RasterBuffers(17, 17);
    PovHudRenderer.renderPreparedState(state, buffers, 8.5F, 8.5F, 0, 0, Map.of());
    assertEquals(-1, buffers.image().getRGB(7, 7));
    assertEquals(0, buffers.image().getRGB(8, 7));
    assertEquals(0, buffers.image().getRGB(7, 8));
  }

  @Test
  void blurAffectsBackgroundButLeavesForegroundSharp() {
    var state = new GuiRenderState();
    state.addGuiElement(rect(0, 0, 8, 16, 0xFFFFFFFF, null));
    state.nextStratum();
    state.blurBeforeThisStratum();
    state.addGuiElement(rect(10, 5, 14, 11, 0xFFFF0000, null));
    var buffers = new RasterBuffers(16, 16);
    buffers.clearColor(0xFF000000);
    PovHudRenderer.renderPreparedState(state, buffers, 16, 16, 0, 1, Map.of());
    var boundary = buffers.image().getRGB(8, 8) & 255;
    assertTrue(boundary > 0 && boundary < 255);
    assertEquals(0xFFFF0000, buffers.image().getRGB(10, 8));
    assertEquals(0xFFFF0000, buffers.image().getRGB(13, 8));
  }

  @Test
  void blurKeepsConstantColorIncludingEdges() {
    var buffers = new RasterBuffers(3, 2);
    buffers.clearColor(0xFF406080);
    GuiBlur.apply(buffers, 9);
    for (var color : buffers.colorBuffer()) {
      assertEquals(0xFF406080, color);
    }
  }

  @Test
  void previewProjectsOrthographicallyAndOccludesFarGeometry() {
    var scene = SceneData.builder();
    scene.add(quad(0xFF00FF00, -100));
    scene.add(quad(0xFFFF0000, -200));
    var image = GuiPictureRenderer.rasterize(scene.build(), 16, 16, new Matrix4f(), 0);
    assertEquals(0xFF00FF00, image.getRGB(4, 4));
    assertEquals(0, image.getRGB(4, 12));
  }

  @Test
  void screenUsesPipelineBlendingForInvertedCrosshair() {
    var buffers = new RasterBuffers(8, 8);
    buffers.clearColor(0xFF112233);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{-1}, null);
    var material = RenderMaterial.create(texture, RendererAssets.AlphaMode.TRANSLUCENT, -1, true, 0)
      .withPipelineState(RenderPipelines.GUI_INVERT);
    drawScreenTriangle(buffers, material);
    assertEquals(0xFFEEDDCC, buffers.image().getRGB(2, 2));
  }

  @Test
  void grayscaleGlyphUsesIntensityAsCoverage() {
    var buffers = new RasterBuffers(8, 8);
    buffers.clearColor(0xFF112233);
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFF000000}, null);
    var material = RenderMaterial.create(texture, RendererAssets.AlphaMode.TRANSLUCENT, -1, true, 0)
      .withPipelineState(RenderPipelines.GUI_TEXT_GRAYSCALE);
    drawScreenTriangle(buffers, material);
    assertEquals(0xFF112233, buffers.image().getRGB(2, 2));
  }

  private static void drawScreenTriangle(RasterBuffers buffers, RenderMaterial material) {
    SoftwareRasterizer.rasterizeScreenTriangle(0, new ProjectedTriangle(
      new ProjectedVertex(0, 0, 0, 1, 0, 0, 255, 255, 255, 255),
      new ProjectedVertex(8, 0, 0, 1, 1, 0, 255, 255, 255, 255),
      new ProjectedVertex(0, 8, 0, 1, 0, 1, 255, 255, 255, 255), material, 0), buffers, 0, 0, 7, 7);
  }

  private static RenderQuad quad(int color, float z) {
    var texture = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{color}, null);
    var material = RenderMaterial.create(texture, RendererAssets.AlphaMode.OPAQUE, -1, true, 0);
    return new RenderQuad(new RenderVertex(2, 2, z, 0, 0, -1), new RenderVertex(2, 6, z, 0, 1, -1),
      new RenderVertex(6, 6, z, 1, 1, -1), new RenderVertex(6, 2, z, 1, 0, -1), material);
  }

  private static ColoredRectangleRenderState rect(int x0, int y0, int x1, int y1, int color, ScreenRectangle clip) {
    return new ColoredRectangleRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), new Matrix3x2f(),
      x0, y0, x1, y1, color, color, clip);
  }
}
