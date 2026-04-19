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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.w3c.dom.Node;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public final class InventoryItemIconRenderer {
  public static final String GIF_MIME_TYPE = "image/gif";
  public static final String PNG_MIME_TYPE = "image/png";

  private static final Cache<RenderKey, RenderedInventoryItemImage> IMAGE_CACHE = Caffeine.newBuilder()
    .maximumSize(512)
    .expireAfterAccess(Duration.ofMinutes(10))
    .build();

  private static final int ICON_RENDER_SIZE = 32;
  private static final int ICON_OUTPUT_SIZE = 32;
  private static final int ICON_PADDING = 2;
  private static final float GUI_PIXELS_PER_UNIT = 32.0F;
  private static final float GUI_CENTER = 16.0F;
  private static final int MAX_GIF_FRAMES = 16;
  private static final Identifier ENCHANTED_GLINT_ITEM = Identifier.withDefaultNamespace("misc/enchanted_glint_item");

  private InventoryItemIconRenderer() {
  }

  public static RenderedInventoryItemImage render(
    @Nullable Minecraft minecraft,
    @Nullable ClientLevel level,
    @Nullable ItemOwner itemOwner,
    ItemStack itemStack
  ) {
    return render(minecraft, level, itemOwner, itemStack, 0);
  }

  public static RenderedInventoryItemImage render(
    @Nullable Minecraft minecraft,
    @Nullable ClientLevel level,
    @Nullable ItemOwner itemOwner,
    ItemStack itemStack,
    int seed
  ) {
    if (itemStack.isEmpty()) {
      return missingImage();
    }

    var resolvedState = resolveVanillaState(minecraft, level, itemOwner, itemStack, seed);
    var cacheKey = new RenderKey(
      BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString(),
      itemStack.getCount(),
      itemStack.immutableComponents(),
      freezeModelIdentity(resolvedState)
    );
    return IMAGE_CACHE.get(cacheKey, _ -> renderUncached(itemStack, resolvedState));
  }

  private static RenderedInventoryItemImage renderUncached(
    ItemStack itemStack,
    @Nullable TrackingItemStackRenderState resolvedState
  ) {
    var scene = buildScene(itemStack, resolvedState);
    if (scene == null || scene.quads().isEmpty()) {
      return missingImage();
    }

    var encodedImage = encodeScene(scene);
    return encodedImage != null ? encodedImage : missingImage();
  }

  private static @Nullable TrackingItemStackRenderState resolveVanillaState(
    @Nullable Minecraft minecraft,
    @Nullable ClientLevel level,
    @Nullable ItemOwner itemOwner,
    ItemStack itemStack,
    int seed
  ) {
    var activeMinecraft = minecraft != null ? minecraft : Minecraft.getInstance();
    if (activeMinecraft == null) {
      return null;
    }

    try {
      var resolvedLevel = level != null ? level : activeMinecraft.level;
      var resolvedOwner = itemOwner != null ? itemOwner : activeMinecraft.player;
      var renderState = new TrackingItemStackRenderState();
      activeMinecraft.getItemModelResolver().updateForTopItem(
        renderState,
        itemStack,
        ItemDisplayContext.GUI,
        resolvedLevel,
        resolvedOwner,
        seed
      );
      return renderState;
    } catch (Throwable t) {
      log.debug("Failed to resolve vanilla inventory item state for {}", BuiltInRegistries.ITEM.getKey(itemStack.getItem()), t);
      return null;
    }
  }

  private static @Nullable IconScene buildScene(
    ItemStack itemStack,
    @Nullable TrackingItemStackRenderState resolvedState
  ) {
    return resolvedState != null ? buildVanillaResolvedScene(resolvedState) : null;
  }

  private static @Nullable IconScene buildVanillaResolvedScene(TrackingItemStackRenderState renderState) {
    try {
      if (renderState.isEmpty()) {
        return null;
      }

      var poseStack = new PoseStack();
      poseStack.scale(1.0F, -1.0F, -1.0F);
      var guiItemRenderState = new GuiItemRenderState(new Matrix3x2f(), renderState, 0, 0, null);
      var oversizedBounds = guiItemRenderState.oversizedItemBounds();
      if (oversizedBounds != null) {
        var itemBoundsCenterX = (oversizedBounds.left() + oversizedBounds.right()) / 2.0F;
        var itemBoundsCenterY = (oversizedBounds.top() + oversizedBounds.bottom()) / 2.0F;
        var slotCenterX = guiItemRenderState.x() + 8.0F;
        var slotCenterY = guiItemRenderState.y() + 8.0F;
        poseStack.translate((slotCenterX - itemBoundsCenterX) / 16.0F, (itemBoundsCenterY - slotCenterY) / 16.0F, 0.0F);
      }

      var collector = new ItemSubmitCollector();
      renderState.submit(poseStack, collector, 0x00F000F0, OverlayTexture.NO_OVERLAY, 0);
      if (collector.unsupported() || collector.quads().isEmpty()) {
        return null;
      }
      return new IconScene(collector.quads(), collector.textures(), collector.hasFoil());
    } catch (Throwable t) {
      log.debug("Failed to build vanilla inventory icon scene", t);
      return null;
    }
  }

  private static void addVanillaQuad(
    List<RenderQuad> quads,
    Set<RendererAssets.TextureImage> textures,
    BakedQuad bakedQuad,
    Matrix4f transform,
    IntList tintLayers
  ) {
    var materialInfo = bakedQuad.materialInfo();
    if (materialInfo == null || materialInfo.sprite() == null || materialInfo.sprite().contents() == null) {
      return;
    }

    var sprite = materialInfo.sprite();
    var textureId = sprite.contents().name();
    if (textureId == null) {
      return;
    }

    var texture = RendererAssets.instance().texture(textureId);
    var vertices = new Vector3f[4];
    var uv = new float[8];
    for (var i = 0; i < 4; i++) {
      vertices[i] = transform.transformPosition(new Vector3f(bakedQuad.position(i)));
      var packedUv = bakedQuad.packedUV(i);
      uv[i * 2] = normalizeSpriteU(sprite, Float.intBitsToFloat((int) packedUv));
      uv[i * 2 + 1] = normalizeSpriteV(sprite, Float.intBitsToFloat((int) (packedUv >>> 32)));
    }

    var tintColor = 0xFFFFFFFF;
    if (materialInfo.isTinted()) {
      var tintIndex = materialInfo.tintIndex();
      if (tintIndex >= 0 && tintIndex < tintLayers.size()) {
        tintColor = normalizeTint(tintLayers.getInt(tintIndex));
      }
    }

    var shadedColor = applyGuiLighting(tintColor, vertices, materialInfo.shade(), materialInfo.lightEmission());
    var face = RendererAssets.GeometryFace.of(
      vertices,
      uv,
      texture,
      alphaModeForTexture(texture),
      null,
      -1,
      materialInfo.lightEmission(),
      materialInfo.shade()
    );
    quads.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, shadedColor, true, 0.0F));
    textures.add(texture);
  }

  private static @Nullable IconScene buildRendererAssetsScene(ItemStack itemStack) {
    try {
      var assets = RendererAssets.instance();
      var itemModel = assets.itemRenderModel(itemStack);
      var quads = new ArrayList<RenderQuad>();
      var textures = new LinkedHashSet<RendererAssets.TextureImage>();

      for (var face : itemModel.geometry().faces()) {
        var transformed = face.transformed(assets.itemDisplayTransform(ItemDisplayContext.GUI));
        quads.add(WorldMeshCollector.toRenderQuad(transformed, 0.0, 0.0, 0.0, 0xFFFFFFFF, true, 0.0F));
        textures.add(transformed.texture());
      }

      if (quads.isEmpty() && itemModel.billboard() != null) {
        var billboard = itemModel.billboard();
        var face = RendererAssets.GeometryFace.of(
          new Vector3f[]{
            new Vector3f(-0.5F, -0.5F, 0.0F),
            new Vector3f(-0.5F, 0.5F, 0.0F),
            new Vector3f(0.5F, 0.5F, 0.0F),
            new Vector3f(0.5F, -0.5F, 0.0F)
          },
          new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
          billboard.texture(),
          billboard.alphaMode(),
          null,
          -1,
          0,
          false
        );
        quads.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, 0xFFFFFFFF, true, 0.0F));
        textures.add(billboard.texture());
      }

      return quads.isEmpty() ? null : new IconScene(quads, List.copyOf(textures), false);
    } catch (Throwable t) {
      log.debug("Failed to build fallback inventory icon scene for {}", BuiltInRegistries.ITEM.getKey(itemStack.getItem()), t);
      return null;
    }
  }

  private static <S> void appendModelGeometry(
    List<RenderQuad> quads,
    Set<RendererAssets.TextureImage> textures,
    Model<? super S> model,
    S state,
    PoseStack poseStack,
    RendererAssets.TextureImage texture,
    int color
  ) {
    model.setupAnim(state);
    appendModelPartGeometry(quads, textures, model.root(), poseStack, texture, color);
  }

  private static void appendModelPartGeometry(
    List<RenderQuad> quads,
    Set<RendererAssets.TextureImage> textures,
    ModelPart modelPart,
    PoseStack poseStack,
    RendererAssets.TextureImage texture,
    int color
  ) {
    modelPart.visit(poseStack, (pose, _, _, cube) -> {
      for (var polygon : cube.polygons) {
        var vertices = new Vector3f[4];
        var uv = new float[8];
        for (var i = 0; i < polygon.vertices().length; i++) {
          var vertex = polygon.vertices()[i];
          vertices[i] = pose.pose().transformPosition(vertex.x() / 16.0F, vertex.y() / 16.0F, vertex.z() / 16.0F, new Vector3f());
          uv[i * 2] = vertex.u();
          uv[i * 2 + 1] = vertex.v();
        }

        var face = RendererAssets.GeometryFace.of(
          vertices,
          uv,
          texture,
          alphaModeForTexture(texture),
          null,
          -1,
          0,
          true
        );
        quads.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, color, true, 0.0F));
      }
    });
    textures.add(texture);
  }

  private static RendererAssets.TextureImage textureImage(TextureAtlasSprite sprite) {
    if (sprite == null || sprite.contents() == null || sprite.contents().name() == null) {
      return null;
    }
    return RendererAssets.instance().texture(sprite.contents().name());
  }

  private static @Nullable RenderedInventoryItemImage encodeScene(IconScene scene) {
    try {
      var frameCount = animationFrameCount(scene);
      var cycleTicks = animationCycleTicks(scene, frameCount);
      var tickStep = Math.max(1L, cycleTicks / Math.max(1, frameCount));
      var renderedFrames = new ArrayList<BufferedImage>(frameCount);

      for (var i = 0; i < frameCount; i++) {
        var tick = i * tickStep;
        renderedFrames.add(renderFrame(scene, tick));
      }

      var normalizedFrames = fitFramesToSlot(renderedFrames);

      var distinctFrames = deduplicateFrames(normalizedFrames);
      if (distinctFrames.size() <= 1) {
        return new RenderedInventoryItemImage(PNG_MIME_TYPE, toBase64PNG(distinctFrames.getFirst()));
      }

      var delayCentiseconds = (int) Math.max(1, tickStep * 5L);
      return new RenderedInventoryItemImage(GIF_MIME_TYPE, toBase64GIF(distinctFrames, delayCentiseconds));
    } catch (Throwable t) {
      log.debug("Failed to encode inventory icon scene", t);
      return null;
    }
  }

  private static BufferedImage renderFrame(IconScene scene, long animationTick) {
    var buffers = new RasterBuffers(ICON_RENDER_SIZE, ICON_RENDER_SIZE);
    buffers.clearColor(0x00000000);
    buffers.clearDepth();

    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.OPAQUE, true);
    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.CUTOUT, true);
    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.TRANSLUCENT, false);

    if (scene.hasFoil()) {
      applyFoil(buffers, animationTick);
    }

    return buffers.image();
  }

  private static List<BufferedImage> fitFramesToSlot(List<BufferedImage> frames) {
    if (frames.isEmpty()) {
      return List.of();
    }

    var union = visibleBounds(frames);
    if (union == null) {
      return frames;
    }

    var croppedWidth = Math.max(1, union.width);
    var croppedHeight = Math.max(1, union.height);
    var available = Math.max(1, ICON_OUTPUT_SIZE - ICON_PADDING * 2);
    var scale = Math.max(1.0, available / (double) Math.max(croppedWidth, croppedHeight));
    var scaledWidth = Math.max(1, (int) Math.round(croppedWidth * scale));
    var scaledHeight = Math.max(1, (int) Math.round(croppedHeight * scale));
    var targetX = (ICON_OUTPUT_SIZE - scaledWidth) / 2;
    var targetY = (ICON_OUTPUT_SIZE - scaledHeight) / 2;

    var normalized = new ArrayList<BufferedImage>(frames.size());
    for (var frame : frames) {
      var output = new BufferedImage(
        ICON_OUTPUT_SIZE,
        ICON_OUTPUT_SIZE,
        BufferedImage.TYPE_INT_ARGB
      );
      var graphics = output.createGraphics();
      graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
      );
      graphics.setRenderingHint(
        RenderingHints.KEY_RENDERING,
        RenderingHints.VALUE_RENDER_SPEED
      );
      graphics.drawImage(
        frame,
        targetX,
        targetY,
        targetX + scaledWidth,
        targetY + scaledHeight,
        union.x,
        union.y,
        union.x + croppedWidth,
        union.y + croppedHeight,
        null
      );
      graphics.dispose();
      normalized.add(output);
    }
    return normalized;
  }

  private static Rectangle visibleBounds(List<BufferedImage> frames) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;

    for (var frame : frames) {
      var bounds = visibleBounds(frame);
      if (bounds == null) {
        continue;
      }
      minX = Math.min(minX, bounds.x);
      minY = Math.min(minY, bounds.y);
      maxX = Math.max(maxX, bounds.x + bounds.width - 1);
      maxY = Math.max(maxY, bounds.y + bounds.height - 1);
    }

    if (minX == Integer.MAX_VALUE) {
      return null;
    }
    return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  private static Rectangle visibleBounds(BufferedImage image) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;

    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) == 0) {
          continue;
        }
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }

    if (minX == Integer.MAX_VALUE) {
      return null;
    }
    return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  private static void rasterPass(
    long animationTick,
    List<RenderQuad> quads,
    RasterBuffers buffers,
    RendererAssets.AlphaMode alphaMode,
    boolean writeDepth
  ) {
    var projectedTriangles = new ArrayList<ProjectedTriangle>();
    for (var quad : quads) {
      if (quad.alphaMode() != alphaMode) {
        continue;
      }
      emitProjectedTriangles(quad, projectedTriangles);
    }

    if (projectedTriangles.isEmpty()) {
      return;
    }

    if (alphaMode == RendererAssets.AlphaMode.TRANSLUCENT) {
      projectedTriangles.sort((left, right) -> Float.compare(right.sortDepth(), left.sortDepth()));
    }

    for (var triangle : projectedTriangles) {
      rasterizeTriangle(animationTick, triangle, buffers, writeDepth);
    }
  }

  private static void emitProjectedTriangles(RenderQuad quad, ArrayList<ProjectedTriangle> out) {
    var projected = new ProjectedVertex[]{
      projectVertex(quad.v0(), quad.depthBias()),
      projectVertex(quad.v1(), quad.depthBias()),
      projectVertex(quad.v2(), quad.depthBias()),
      projectVertex(quad.v3(), quad.depthBias())
    };
    var sortDepth =
      (projected[0].depth() + projected[1].depth() + projected[2].depth() + projected[3].depth()) / 4.0F;
    out.add(new ProjectedTriangle(
      projected[0],
      projected[1],
      projected[2],
      quad.texture(),
      quad.alphaMode(),
      quad.color(),
      quad.doubleSided(),
      sortDepth
    ));
    out.add(new ProjectedTriangle(
      projected[0],
      projected[2],
      projected[3],
      quad.texture(),
      quad.alphaMode(),
      quad.color(),
      quad.doubleSided(),
      sortDepth
    ));
  }

  private static ProjectedVertex projectVertex(RenderVertex vertex, float depthBias) {
    var screenX = GUI_CENTER + vertex.x() * GUI_PIXELS_PER_UNIT;
    var screenY = GUI_CENTER - vertex.y() * GUI_PIXELS_PER_UNIT;
    var depth = -vertex.z() + depthBias;
    return new ProjectedVertex(
      screenX,
      screenY,
      depth,
      1.0F,
      vertex.u(),
      vertex.v()
    );
  }

  private static void rasterizeTriangle(
    long animationTick,
    ProjectedTriangle triangle,
    RasterBuffers buffers,
    boolean writeDepth
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
    var width = buffers.image().getWidth();
    var height = buffers.image().getHeight();
    var colorBuffer = buffers.colorBuffer();
    var depthBuffer = buffers.depthBuffer();

    var minX = Math.max(0, (int) Math.floor(Math.min(v0.x(), Math.min(v1.x(), v2.x()))));
    var minY = Math.max(0, (int) Math.floor(Math.min(v0.y(), Math.min(v1.y(), v2.y()))));
    var maxX = Math.min(width - 1, (int) Math.ceil(Math.max(v0.x(), Math.max(v1.x(), v2.x()))));
    var maxY = Math.min(height - 1, (int) Math.ceil(Math.max(v0.y(), Math.max(v1.y(), v2.y()))));
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
        if (triangle.alphaMode() == RendererAssets.AlphaMode.CUTOUT && alpha < 51) {
          continue;
        }

        if (triangle.alphaMode() == RendererAssets.AlphaMode.OPAQUE) {
          if (writeDepth) {
            depthBuffer[rasterIndex] = depth;
          }
          colorBuffer[rasterIndex] = forceOpaque(color);
          continue;
        }

        if (triangle.alphaMode() == RendererAssets.AlphaMode.CUTOUT) {
          if (writeDepth) {
            depthBuffer[rasterIndex] = depth;
          }
          colorBuffer[rasterIndex] = forceOpaque(color);
          continue;
        }

        colorBuffer[rasterIndex] = blend(colorBuffer[rasterIndex], color);
        if (writeDepth) {
          depthBuffer[rasterIndex] = depth;
        }
      }
    }
  }

  private static void applyFoil(RasterBuffers buffers, long animationTick) {
    var glint = RendererAssets.instance().texture(ENCHANTED_GLINT_ITEM);
    var colors = buffers.colorBuffer();
    var width = buffers.image().getWidth();
    var height = buffers.image().getHeight();

    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var index = y * width + x;
        var base = colors[index];
        var baseAlpha = (base >>> 24) & 0xFF;
        if (baseAlpha == 0) {
          continue;
        }

        var sample = glint.sample((x * 1.5F + animationTick * 0.55F) / width, (y * 1.5F - animationTick * 0.35F) / height, animationTick);
        var sampleAlpha = ((sample >>> 24) & 0xFF) * 96 / 255;
        if (sampleAlpha == 0) {
          continue;
        }

        colors[index] = blend(base, (sampleAlpha << 24) | (sample & 0x00FFFFFF));
      }
    }
  }

  private static int animationFrameCount(IconScene scene) {
    var frameCount = scene.hasFoil() ? MAX_GIF_FRAMES : 1;
    for (var texture : scene.textures()) {
      if (texture.isAnimated()) {
        frameCount = Math.max(frameCount, texture.animationFrameCount());
      }
    }
    return Math.clamp(frameCount, 1, MAX_GIF_FRAMES);
  }

  private static long animationCycleTicks(IconScene scene, int frameCount) {
    var cycleTicks = scene.hasFoil() ? (long) frameCount * 2L : 1L;
    for (var texture : scene.textures()) {
      if (texture.isAnimated()) {
        cycleTicks = Math.max(cycleTicks, texture.animationCycleTicks());
      }
    }
    return Math.max(1L, cycleTicks);
  }

  private static List<BufferedImage> deduplicateFrames(List<BufferedImage> frames) {
    if (frames.isEmpty()) {
      return List.of(RendererAssets.TextureImage.missing().toBufferedImage());
    }

    var deduplicated = new ArrayList<BufferedImage>(frames.size());
    BufferedImage previous = null;
    for (var frame : frames) {
      if (previous == null || !samePixels(previous, frame)) {
        deduplicated.add(frame);
        previous = frame;
      }
    }
    if (deduplicated.isEmpty()) {
      deduplicated.add(frames.getFirst());
    }
    return deduplicated;
  }

  private static boolean samePixels(BufferedImage left, BufferedImage right) {
    if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
      return false;
    }

    var leftPixels = ((DataBufferInt) left.getRaster().getDataBuffer()).getData();
    var rightPixels = ((DataBufferInt) right.getRaster().getDataBuffer()).getData();
    if (leftPixels.length != rightPixels.length) {
      return false;
    }
    for (var i = 0; i < leftPixels.length; i++) {
      if (leftPixels[i] != rightPixels[i]) {
        return false;
      }
    }
    return true;
  }

  private static RenderedInventoryItemImage missingImage() {
    return new RenderedInventoryItemImage(PNG_MIME_TYPE, toBase64PNG(RendererAssets.TextureImage.missing().toBufferedImage()));
  }

  private static String toBase64PNG(BufferedImage image) {
    try (var os = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", os);
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode inventory PNG", e);
    }
  }

  private static String toBase64GIF(List<BufferedImage> frames, int delayCentiseconds) {
    var writer = ImageIO.getImageWritersBySuffix("gif").next();
    var imageType = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
    var params = writer.getDefaultWriteParam();
    String encoded;
    try (var os = new ByteArrayOutputStream();
         ImageOutputStream imageOutput = ImageIO.createImageOutputStream(os)) {
      writer.setOutput(imageOutput);
      writer.prepareWriteSequence(null);

      for (var i = 0; i < frames.size(); i++) {
        var metadata = gifMetadata(writer, imageType, params, delayCentiseconds, i == 0);
        writer.writeToSequence(new IIOImage(frames.get(i), null, metadata), params);
      }

      writer.endWriteSequence();
      imageOutput.flush();
      encoded = Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode inventory GIF", e);
    } finally {
      writer.dispose();
    }
    return encoded;
  }

  private static IIOMetadata gifMetadata(
    ImageWriter writer,
    ImageTypeSpecifier imageType,
    ImageWriteParam params,
    int delayCentiseconds,
    boolean includeLoopExtension
  ) throws IOException {
    var metadata = writer.getDefaultImageMetadata(imageType, params);
    var formatName = metadata.getNativeMetadataFormatName();
    var root = (IIOMetadataNode) metadata.getAsTree(formatName);

    var graphicControlExtension = getOrCreateChild(root, "GraphicControlExtension");
    graphicControlExtension.setAttribute("disposalMethod", "none");
    graphicControlExtension.setAttribute("userInputFlag", "FALSE");
    graphicControlExtension.setAttribute("transparentColorFlag", "TRUE");
    graphicControlExtension.setAttribute("delayTime", Integer.toString(Math.max(1, delayCentiseconds)));
    graphicControlExtension.setAttribute("transparentColorIndex", "0");

    if (includeLoopExtension) {
      var applicationExtensions = getOrCreateChild(root, "ApplicationExtensions");
      var loopExtension = new IIOMetadataNode("ApplicationExtension");
      loopExtension.setAttribute("applicationID", "NETSCAPE");
      loopExtension.setAttribute("authenticationCode", "2.0");
      loopExtension.setUserObject(new byte[]{0x01, 0x00, 0x00});
      applicationExtensions.appendChild(loopExtension);
    }

    metadata.setFromTree(formatName, root);
    return metadata;
  }

  private static IIOMetadataNode getOrCreateChild(IIOMetadataNode root, String name) {
    for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (name.equals(child.getNodeName())) {
        return (IIOMetadataNode) child;
      }
    }

    var node = new IIOMetadataNode(name);
    root.appendChild(node);
    return node;
  }

  private static int normalizeTint(int tint) {
    return (tint & 0xFF000000) == 0 ? 0xFF000000 | tint : tint;
  }

  private static float normalizeSpriteU(TextureAtlasSprite sprite, float atlasU) {
    var span = sprite.getU1() - sprite.getU0();
    if (Math.abs(span) < 1.0E-6F) {
      return 0.0F;
    }
    return Math.clamp((atlasU - sprite.getU0()) / span, 0.0F, 1.0F);
  }

  private static float normalizeSpriteV(TextureAtlasSprite sprite, float atlasV) {
    var span = sprite.getV1() - sprite.getV0();
    if (Math.abs(span) < 1.0E-6F) {
      return 0.0F;
    }
    return Math.clamp((atlasV - sprite.getV0()) / span, 0.0F, 1.0F);
  }

  private static int applyGuiLighting(int color, Vector3f[] vertices, boolean shade, int emission) {
    if (!shade || emission > 0) {
      return color;
    }

    var edge1 = new Vector3f(vertices[1]).sub(vertices[0]);
    var edge2 = new Vector3f(vertices[2]).sub(vertices[0]);
    var normal = edge1.cross(edge2);
    if (normal.lengthSquared() < 1.0E-6F) {
      return color;
    }

    normal.normalize();
    var lightDirection = new Vector3f(-0.35F, 0.7F, 1.0F).normalize();
    var brightness = Math.max(0.55F, Math.abs(normal.dot(lightDirection)));
    var r = Math.min(255, Math.round(((color >> 16) & 0xFF) * brightness));
    var g = Math.min(255, Math.round(((color >> 8) & 0xFF) * brightness));
    var b = Math.min(255, Math.round((color & 0xFF) * brightness));
    return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
  }

  private static RendererAssets.AlphaMode alphaModeForTexture(RendererAssets.TextureImage texture) {
    if (texture.hasTranslucentPixels()) {
      return RendererAssets.AlphaMode.TRANSLUCENT;
    }
    if (texture.hasAlpha()) {
      return RendererAssets.AlphaMode.CUTOUT;
    }
    return RendererAssets.AlphaMode.OPAQUE;
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

  private static int blend(int dstColor, int srcColor) {
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

  private static boolean isInside(float area, float w0, float w1, float w2, boolean topLeft0, boolean topLeft1, boolean topLeft2) {
    var epsilon = 1.0E-5F;
    if (area > 0.0F) {
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

  private static List<Object> freezeModelIdentity(@Nullable TrackingItemStackRenderState renderState) {
    if (renderState == null) {
      return List.of();
    }

    var identity = renderState.getModelIdentity();
    if (identity instanceof List<?> list) {
      return List.copyOf(list);
    }
    return identity == null ? List.of() : List.of(identity);
  }

  public record RenderedInventoryItemImage(String mimeType, String base64) {}

  private record RenderKey(
    String itemId,
    int count,
    DataComponentMap components,
    List<Object> modelIdentity
  ) {}

  private record IconScene(
    List<RenderQuad> quads,
    List<RendererAssets.TextureImage> textures,
    boolean hasFoil
  ) {}

  private record ClipVertex(float x, float y, float z, float u, float v) {}

  private record ProjectedVertex(
    float x,
    float y,
    float depth,
    float inverseDepth,
    float uOverDepth,
    float vOverDepth
  ) {}

  private record ProjectedTriangle(
    ProjectedVertex v0,
    ProjectedVertex v1,
    ProjectedVertex v2,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    boolean doubleSided,
    float sortDepth
  ) {}

  private static final class ItemSubmitCollector implements SubmitNodeCollector {
    private final ArrayList<RenderQuad> quads = new ArrayList<>();
    private final LinkedHashSet<RendererAssets.TextureImage> textures = new LinkedHashSet<>();
    private boolean unsupported;
    private boolean hasFoil;

    public List<RenderQuad> quads() {
      return quads;
    }

    public List<RendererAssets.TextureImage> textures() {
      return List.copyOf(textures);
    }

    public boolean unsupported() {
      return unsupported;
    }

    public boolean hasFoil() {
      return hasFoil;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
      return this;
    }

    @Override
    public <S> void submitModel(
      Model<? super S> model,
      S state,
      PoseStack poseStack,
      RenderType renderType,
      int light,
      int overlay,
      int color,
      TextureAtlasSprite sprite,
      int emission,
      ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
    ) {
      var texture = textureImage(sprite);
      if (texture == null) {
        texture = textureImageFromRenderType(renderType);
      }
      if (texture == null) {
        unsupported = true;
        return;
      }

      appendModelGeometry(quads, textures, model, state, poseStack, texture, color);
    }

    @Override
    public void submitModelPart(
      ModelPart modelPart,
      PoseStack poseStack,
      RenderType renderType,
      int light,
      int overlay,
      TextureAtlasSprite sprite,
      boolean invertCull,
      boolean hasTextureOffset,
      int color,
      ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
      int emission
    ) {
      var texture = textureImage(sprite);
      if (texture == null) {
        texture = textureImageFromRenderType(renderType);
      }
      if (texture == null) {
        unsupported = true;
        return;
      }

      appendModelPartGeometry(quads, textures, modelPart, poseStack, texture, color);
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(
      PoseStack poseStack,
      Vec3 position,
      int color,
      Component text,
      boolean outline,
      int backgroundColor,
      double distance,
      CameraRenderState cameraRenderState
    ) {
      unsupported = true;
    }

    @Override
    public void submitText(
      PoseStack poseStack,
      float x,
      float y,
      FormattedCharSequence text,
      boolean shadow,
      Font.DisplayMode displayMode,
      int color,
      int backgroundColor,
      int light,
      int offset
    ) {
      unsupported = true;
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
      unsupported = true;
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
      unsupported = true;
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {
      for (var face : RendererAssets.instance().blockGeometry(movingBlockRenderState.blockState).faces()) {
        var transformed = face.transformed(poseStack.last().pose());
        quads.add(WorldMeshCollector.toRenderQuad(transformed, 0.0, 0.0, 0.0, 0xFFFFFFFF, false, 0.0F));
        textures.add(transformed.texture());
      }
    }

    @Override
    public void submitBlockModel(
      PoseStack poseStack,
      RenderType renderType,
      List<BlockStateModelPart> parts,
      int[] tints,
      int light,
      int overlay,
      int color
    ) {
      var tintList = new it.unimi.dsi.fastutil.ints.IntArrayList(tints);
      for (var part : parts) {
        for (var quad : part.getQuads(null)) {
          addVanillaQuad(quads, textures, quad, new Matrix4f(poseStack.last().pose()), tintList);
        }
        for (var direction : net.minecraft.core.Direction.values()) {
          for (var quad : part.getQuads(direction)) {
            addVanillaQuad(quads, textures, quad, new Matrix4f(poseStack.last().pose()), tintList);
          }
        }
      }
    }

    @Override
    public void submitBreakingBlockModel(
      PoseStack poseStack,
      BlockStateModel blockStateModel,
      long seed,
      int color
    ) {
      var parts = new ArrayList<BlockStateModelPart>();
      blockStateModel.collectParts(net.minecraft.util.RandomSource.create(seed), parts);
      submitBlockModel(poseStack, null, parts, new int[0], 0, 0, color);
    }

    @Override
    public void submitItem(
      PoseStack poseStack,
      ItemDisplayContext displayContext,
      int light,
      int overlay,
      int color,
      int[] tints,
      List<BakedQuad> quads,
      ItemStackRenderState.FoilType foilType
    ) {
      hasFoil |= foilType != ItemStackRenderState.FoilType.NONE;
      var transform = new Matrix4f(poseStack.last().pose());
      var tintLayers = new it.unimi.dsi.fastutil.ints.IntArrayList(tints);
      for (var quad : quads) {
        addVanillaQuad(this.quads, textures, quad, transform, tintLayers);
      }
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
      var texture = textureImageFromRenderType(renderType);
      if (texture == null) {
        unsupported = true;
        return;
      }

      var consumer = new ItemCapturingVertexConsumer(new Matrix4f(poseStack.last().pose()), renderType.mode(), texture, alphaModeForTexture(texture));
      renderer.render(poseStack.last(), consumer);
      consumer.flush(quads, textures);
    }

    @Override
    public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
      unsupported = true;
    }
  }

  private static final class ItemCapturingVertexConsumer implements com.mojang.blaze3d.vertex.VertexConsumer {
    private final Matrix4fc pose;
    private final com.mojang.blaze3d.vertex.VertexFormat.Mode mode;
    private final RendererAssets.TextureImage texture;
    private final RendererAssets.AlphaMode alphaMode;
    private final ArrayList<CapturedVertex> vertices = new ArrayList<>();
    private CapturedVertex current;

    private ItemCapturingVertexConsumer(
      Matrix4fc pose,
      com.mojang.blaze3d.vertex.VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode
    ) {
      this.pose = pose;
      this.mode = mode;
      this.texture = texture;
      this.alphaMode = alphaMode;
    }

    private void flush(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures) {
      switch (mode) {
        case QUADS -> {
          for (var i = 0; i + 3 < vertices.size(); i += 4) {
            emitQuad(quads, textures, vertices.get(i), vertices.get(i + 1), vertices.get(i + 2), vertices.get(i + 3));
          }
        }
        case TRIANGLES -> {
          for (var i = 0; i + 2 < vertices.size(); i += 3) {
            emitTriangle(quads, textures, vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
          }
        }
        case TRIANGLE_STRIP, TRIANGLE_FAN -> {
          for (var i = 0; i + 2 < vertices.size(); i++) {
            emitTriangle(quads, textures, vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
          }
        }
        default -> {
        }
      }
    }

    private void emitQuad(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex a, CapturedVertex b, CapturedVertex c, CapturedVertex d) {
      var face = RendererAssets.GeometryFace.of(
        new Vector3f[]{a.position(), b.position(), c.position(), d.position()},
        new float[]{a.u(), a.v(), b.u(), b.v(), c.u(), c.v(), d.u(), d.v()},
        texture,
        alphaMode,
        null,
        -1,
        0,
        true
      );
      quads.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, a.color(), true, 0.0F));
      textures.add(texture);
    }

    private void emitTriangle(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex a, CapturedVertex b, CapturedVertex c) {
      emitQuad(quads, textures, a, b, c, c);
    }

    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
      current = new CapturedVertex(pose.transformPosition(new Vector3f(x, y, z)), 0xFFFFFFFF, 0.0F, 0.0F);
      vertices.add(current);
      return this;
    }

    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer setColor(int red, int green, int blue, int alpha) {
      return setColor((alpha << 24) | (red << 16) | (green << 8) | blue);
    }

    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer setColor(int color) {
      if (current != null) {
        current = current.withColor(color);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }

    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
      if (current != null) {
        current = current.withUv(u, v);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }

    @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
    @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
    @Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) { return this; }
  }

  private static RendererAssets.TextureImage textureImageFromRenderType(RenderType renderType) {
    RenderSetup state = renderType.state;
    if (state == null || state.textures == null || state.textures.isEmpty()) {
      return null;
    }

    for (var binding : state.textures.values()) {
      var location = binding.location;
      if (location == null) {
        continue;
      }
      if (TextureAtlas.LOCATION_BLOCKS.equals(location)
        || TextureAtlas.LOCATION_ITEMS.equals(location)
        || TextureAtlas.LOCATION_PARTICLES.equals(location)) {
        return RendererAssets.instance().textureAtlas(location);
      }
      return RendererAssets.instance().texture(location);
    }

    return null;
  }

  private record CapturedVertex(Vector3f position, int color, float u, float v) {
    private CapturedVertex withColor(int color) {
      return new CapturedVertex(position, color, u, v);
    }

    private CapturedVertex withUv(float u, float v) {
      return new CapturedVertex(position, color, u, v);
    }
  }
}
