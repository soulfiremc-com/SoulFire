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

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

final class PovHudRenderer {
  private static final int GUI_ITEM_SIZE = 16;
  private static final RendererAssets.TextureImage WHITE_TEXTURE = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);

  private PovHudRenderer() {}

  static void render(RenderContext ctx, RasterBuffers buffers) {
    var minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.gui == null || minecraft.getWindow() == null) {
      return;
    }

    var window = minecraft.getWindow();
    window.setWidth(buffers.image().getWidth());
    window.setHeight(buffers.image().getHeight());
    window.setGuiScale(window.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode()));
    var screen = minecraft.gui.screen();
    var windowState = minecraft.gameRenderer.gameRenderState().windowRenderState;
    try {
      windowState.width = window.getWidth();
      windowState.height = window.getHeight();
      windowState.guiScale = window.getGuiScale();
      if (screen != null && (screen.width != window.getGuiScaledWidth() || screen.height != window.getGuiScaledHeight())) {
        screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
      }
      var deltaTracker = minecraft.getDeltaTracker() != null ? minecraft.getDeltaTracker() : DeltaTracker.ONE;
      var camera = minecraft.gameRenderer.mainCamera();
      camera.setLevel(ctx.level());
      camera.setEntity(ctx.localPlayer());
      camera.update(deltaTracker);
      camera.setPosition(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
      camera.setRotation(ctx.camera().yRot(), ctx.camera().xRot());
      // POV runs after resources and the bot world load; the headless loop never completes a graphical frame.
      minecraft.gui.extractRenderState(deltaTracker, minecraft.level != null, true);
      var guiState = minecraft.gameRenderer.gameRenderState().guiRenderState;
      renderState(ctx, guiState, buffers, window.getGuiScale(), minecraft.options.getMenuBackgroundBlurriness());
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to render POV GUI" + (screen == null ? " HUD" : " screen " + screen.getClass().getName()), e);
    }
  }

  static void renderState(RenderContext ctx, GuiRenderState state, RasterBuffers buffers,
                          int scale, float blurRadius) {
    var images = new IdentityHashMap<GuiElementRenderState, RendererAssets.TextureImage>();
    state.forEachPictureInPicture(pip -> {
      var image = GuiPictureRenderer.render(ctx, pip, scale);
      addImage(state, images, image, pip.pose(), pip.x0(), pip.y0(), pip.x1(), pip.y1(), pip.scissorArea());
    });
    state.forEachItem(item -> {
      var bounds = item.oversizedItemBounds();
      if (bounds != null) {
        var pip = new OversizedItemRenderState(item, bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
        addImage(state, images, GuiPictureRenderer.render(ctx, pip, scale), item.pose(),
          pip.x0(), pip.y0(), pip.x1(), pip.y1(), item.scissorArea());
      } else {
        var image = GuiPictureRenderer.renderItem(ctx, item.itemStackRenderState(), scale);
        addImage(state, images, image, item.pose(), item.x(), item.y(), item.x() + GUI_ITEM_SIZE, item.y() + GUI_ITEM_SIZE, item.scissorArea());
      }
    });
    prepareText(state);
    renderPreparedState(state, buffers, buffers.image().getWidth() / (float) scale,
      buffers.image().getHeight() / (float) scale, ctx.animationTick(), blurRadius, images);
  }

  static void prepareText(GuiRenderState state) {
    state.forEachText(text -> text.ensurePrepared().visit(new Font.GlyphVisitor() {
      @Override
      public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable renderable) {
        state.addGlyphToCurrentLayer(new GlyphRenderState(text.pose, renderable, text.scissor));
      }
    }));
  }

  static void addImage(GuiRenderState state, Map<GuiElementRenderState, RendererAssets.TextureImage> images,
                       BufferedImage image, Matrix3x2fc pose, int x0, int y0, int x1, int y1,
                       @Nullable ScreenRectangle scissor) {
    var blit = new BlitRenderState(RenderPipelines.GUI_TEXTURED, TextureSetup.noTexture(), pose,
      x0, y0, x1, y1, 0, 1, 0, 1, -1, scissor, null);
    images.put(blit, RendererAssets.TextureImage.from(image, null));
    state.addBlitToCurrentLayer(blit);
  }

  static void renderPreparedState(GuiRenderState state, RasterBuffers buffers, float width, float height,
                                   long tick, float blurRadius, Map<GuiElementRenderState, RendererAssets.TextureImage> images) {
    var geometry = new GuiGeometry(width, height, buffers.image().getWidth(), buffers.image().getHeight());
    state.forEachElement(element -> renderElement(element, geometry, buffers, tick, images), GuiRenderState.TraverseRange.BEFORE_BLUR);
    var blurred = new boolean[1];
    state.forEachElement(element -> {
      if (!blurred[0]) {
        GuiBlur.apply(buffers, Math.round(blurRadius));
        blurred[0] = true;
      }
      renderElement(element, geometry, buffers, tick, images);
    }, GuiRenderState.TraverseRange.AFTER_BLUR);
  }

  private static void renderElement(GuiElementRenderState element, GuiGeometry geometry, RasterBuffers buffers, long animationTick, Map<GuiElementRenderState, RendererAssets.TextureImage> images) {
    var texture = images.get(element);
    if (texture == null) {
      texture = texture(element.textureSetup());
    }
    if (texture == null) {
      throw new IllegalStateException("Missing CPU texture " + element.textureSetup().texure0().texture().getLabel() + " for GUI element " + element.getClass().getName());
    }

    var consumer = new GuiVertexConsumer();
    element.buildVertices(consumer);
    var vertices = consumer.vertices();
    if (vertices.size() < 3) {
      return;
    }

    var material = material(texture, element.pipeline(), vertices);
    var clip = clipRect(element.scissorArea(), geometry);
    for (var i = 0; i + 3 < vertices.size(); i += 4) {
      rasterizeQuad(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2), vertices.get(i + 3), material, clip, geometry, buffers, animationTick);
    }
  }

  @Nullable
  private static RendererAssets.TextureImage texture(TextureSetup setup) {
    var textureView = setup.texure0();
    if (textureView == null) {
      return WHITE_TEXTURE;
    }

    var texture = runtimeTexture(textureView);
    if (texture == null) {
      return null;
    }
    return RendererAssets.withSampler(texture, setup.sampler0());
  }

  @Nullable
  private static RendererAssets.TextureImage runtimeTexture(GpuTextureView textureView) {
    try {
      return RendererRuntimeTextureMirror.texture(textureView.texture());
    } catch (Throwable _) {
      return null;
    }
  }

  private static RenderMaterial material(RendererAssets.TextureImage texture, RenderPipeline pipeline, ArrayList<GuiVertex> vertices) {
    var alphaMode = alphaMode(texture, pipeline, vertices);
    return RenderMaterial
      .create(texture, alphaMode, 0xFFFFFFFF, true, 0.0F, alphaCutoutThreshold(alphaMode))
      .withPipelineState(pipeline);
  }

  private static RendererAssets.AlphaMode alphaMode(RendererAssets.TextureImage texture, RenderPipeline pipeline, ArrayList<GuiVertex> vertices) {
    if (pipeline.getColorTargetState().blendFunction().isPresent() || texture.hasTranslucentPixels()) {
      return RendererAssets.AlphaMode.TRANSLUCENT;
    }
    if (texture.hasAlpha() || hasTransparentVertexColor(vertices)) {
      return RendererAssets.AlphaMode.CUTOUT;
    }
    return RendererAssets.AlphaMode.OPAQUE;
  }

  private static boolean hasTransparentVertexColor(ArrayList<GuiVertex> vertices) {
    for (var vertex : vertices) {
      if (((vertex.color() >>> 24) & 0xFF) < 255) {
        return true;
      }
    }
    return false;
  }

  private static int alphaCutoutThreshold(RendererAssets.AlphaMode alphaMode) {
    return alphaMode == RendererAssets.AlphaMode.TRANSLUCENT ? 1 : RenderMaterial.defaultAlphaCutoutThreshold(alphaMode);
  }

  private static ClipRect clipRect(@Nullable ScreenRectangle scissor, GuiGeometry geometry) {
    if (scissor == null) {
      return new ClipRect(0, 0, geometry.targetWidth() - 1, geometry.targetHeight() - 1);
    }

    var minX = Math.max(0, (int) Math.floor(scissor.left() * geometry.scaleX()));
    var minY = Math.max(0, (int) Math.floor(scissor.top() * geometry.scaleY()));
    var maxX = Math.min(geometry.targetWidth() - 1, (int) Math.ceil(scissor.right() * geometry.scaleX()) - 1);
    var maxY = Math.min(geometry.targetHeight() - 1, (int) Math.ceil(scissor.bottom() * geometry.scaleY()) - 1);
    return new ClipRect(minX, minY, maxX, maxY);
  }

  private static void rasterizeQuad(
    GuiVertex a,
    GuiVertex b,
    GuiVertex c,
    GuiVertex d,
    RenderMaterial material,
    ClipRect clip,
    GuiGeometry geometry,
    RasterBuffers buffers,
    long animationTick
  ) {
    if (clip.minX() > clip.maxX() || clip.minY() > clip.maxY()) {
      return;
    }

    var v0 = project(a, geometry);
    var v1 = project(b, geometry);
    var v2 = project(c, geometry);
    var v3 = project(d, geometry);
    SoftwareRasterizer.rasterizeScreenTriangle(
      animationTick,
      new ProjectedTriangle(v0, v1, v2, material, 0.0F),
      buffers,
      clip.minX(),
      clip.minY(),
      clip.maxX(),
      clip.maxY()
    );
    SoftwareRasterizer.rasterizeScreenTriangle(
      animationTick,
      new ProjectedTriangle(v0, v2, v3, material, 0.0F),
      buffers,
      clip.minX(),
      clip.minY(),
      clip.maxX(),
      clip.maxY()
    );
  }

  private static ProjectedVertex project(GuiVertex vertex, GuiGeometry geometry) {
    return new ProjectedVertex(
      vertex.x() * geometry.scaleX(),
      vertex.y() * geometry.scaleY(),
      vertex.z(),
      1.0F,
      vertex.u(),
      vertex.v(),
      (vertex.color() >>> 24) & 0xFF,
      (vertex.color() >>> 16) & 0xFF,
      (vertex.color() >>> 8) & 0xFF,
      vertex.color() & 0xFF
    );
  }

  private record GuiGeometry(float guiWidth, float guiHeight, int targetWidth, int targetHeight) {
    private float scaleX() {
      return targetWidth / (float) guiWidth;
    }

    private float scaleY() {
      return targetHeight / (float) guiHeight;
    }
  }

  private record ClipRect(int minX, int minY, int maxX, int maxY) {}

  private record GuiVertex(float x, float y, float z, float u, float v, int color) {}

  private static final class GuiVertexConsumer implements VertexConsumer {
    private final ArrayList<GuiVertex> vertices = new ArrayList<>();
    @Nullable
    private MutableGuiVertex current;

    private ArrayList<GuiVertex> vertices() {
      if (current != null) {
        vertices.set(vertices.size() - 1, current.toVertex());
      }
      return vertices;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      flushCurrent();
      current = new MutableGuiVertex(x, y, z);
      vertices.add(current.toVertex());
      return this;
    }

    @Override
    public VertexConsumer addVertex(Matrix4fc matrix, float x, float y, float z) {
      var position = matrix.transformPosition(x, y, z, new Vector3f());
      return addVertex(position.x(), position.y(), position.z());
    }

    @Override
    public VertexConsumer addVertexWith2DPose(Matrix3x2fc pose, float x, float y) {
      var position = pose.transformPosition(x, y, new Vector2f());
      return addVertex(position.x(), position.y(), 0.0F);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      if (current != null) {
        current.color = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
      }
      return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
      if (current != null) {
        current.color = color;
      }
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      if (current != null) {
        current.u = u;
        current.v = v;
      }
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      return this;
    }

    private void flushCurrent() {
      if (current != null) {
        vertices.set(vertices.size() - 1, current.toVertex());
      }
    }
  }

  private static final class MutableGuiVertex {
    private final float x;
    private final float y;
    private final float z;
    private float u;
    private float v;
    private int color = 0xFFFFFFFF;

    private MutableGuiVertex(float x, float y, float z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }

    private GuiVertex toVertex() {
      return new GuiVertex(x, y, z, u, v, color);
    }
  }
}
