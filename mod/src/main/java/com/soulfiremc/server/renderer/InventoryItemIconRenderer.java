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
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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

  private static final int ICON_RENDER_SIZE = 64;
  private static final int ICON_OUTPUT_SIZE = 32;
  private static final int ICON_PADDING = 2;
  private static final float GUI_PIXELS_PER_UNIT = 16.0F;
  private static final int MAX_GIF_FRAMES = 16;
  private static final int MAX_INVENTORY_FLAME_LAYERS = 4;
  private static final int MAX_INVENTORY_SHADOW_PIECES = 8;
  private static final int INVENTORY_LEASH_COLOR = 0xFF6B4B2A;
  private static final float INVENTORY_LEASH_WIDTH = 2.0F;
  private static final Identifier ENCHANTED_GLINT_ITEM = Identifier.withDefaultNamespace("misc/enchanted_glint_item");
  private static final RendererAssets.TextureImage WHITE_TEXTURE = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);

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
      poseStack.scale(1.0F, -1.0F, 1.0F);
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
      uv[i * 2] = BakedQuadUv.localU(sprite, packedUv);
      uv[i * 2 + 1] = BakedQuadUv.localV(sprite, packedUv);
    }

    var tintColor = 0xFFFFFFFF;
    if (materialInfo.isTinted()) {
      var tintIndex = materialInfo.tintIndex();
      if (tintIndex >= 0 && tintIndex < tintLayers.size()) {
        tintColor = normalizeTint(tintLayers.getInt(tintIndex));
      }
    }

    var shadedColor = applyGuiLighting(tintColor, vertices, materialInfo.shade(), materialInfo.lightEmission());
    var renderType = materialInfo.itemRenderType();
    var alphaMode = VanillaSubmitCollector.alphaMode(renderType, texture, shadedColor, uv);
    var face = RendererAssets.GeometryFace.of(
      vertices,
      uv,
      texture,
      alphaMode,
      null,
      -1,
      materialInfo.lightEmission(),
      materialInfo.shade()
    );
    var renderQuad = WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, shadedColor, true, 0.0F);
    quads.add(withRenderType(renderQuad, renderType));
    textures.add(texture);
  }

  private static RenderQuad withRenderType(RenderQuad quad, @Nullable RenderType renderType) {
    if (renderType == null) {
      return quad;
    }

    return new RenderQuad(
      quad.v0(),
      quad.v1(),
      quad.v2(),
      quad.v3(),
      quad.material().withRenderType(renderType)
    );
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

  private static Vector3f rotateParticleVertex(Quaternionf rotation, float x, float y, float z, float size, float px, float py) {
    return new Vector3f(px, py, 0.0F).rotate(rotation).mul(size).add(x, y, z);
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
    var renderSize = Math.max(ICON_RENDER_SIZE, requiredRenderSize(scene));
    return renderFrame(scene, animationTick, renderSize, GUI_PIXELS_PER_UNIT);
  }

  private static BufferedImage renderFrame(IconScene scene, long animationTick, int renderSize, float pixelsPerUnit) {
    var buffers = new RasterBuffers(renderSize, renderSize);
    buffers.clearColor(0x00000000);
    buffers.clearDepth();

    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.OPAQUE, true, pixelsPerUnit);
    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.CUTOUT, true, pixelsPerUnit);
    rasterPass(animationTick, scene.quads(), buffers, RendererAssets.AlphaMode.TRANSLUCENT, false, pixelsPerUnit);

    if (scene.hasFoil()) {
      applyFoil(buffers, animationTick);
    }

    return buffers.image();
  }

  private static int requiredRenderSize(IconScene scene) {
    var maxExtent = 0.0F;
    for (var quad : scene.quads()) {
      maxExtent = Math.max(maxExtent, Math.abs(quad.v0().x()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v1().x()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v2().x()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v3().x()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v0().y()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v1().y()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v2().y()));
      maxExtent = Math.max(maxExtent, Math.abs(quad.v3().y()));
    }

    return Math.max(
      ICON_RENDER_SIZE,
      (int) Math.ceil(maxExtent * GUI_PIXELS_PER_UNIT * 2.0F) + ICON_PADDING * 4
    );
  }

  static List<BufferedImage> fitFramesToSlot(List<BufferedImage> frames) {
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
    var scale = available / (double) Math.max(croppedWidth, croppedHeight);
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
    boolean writeDepth,
    float pixelsPerUnit
  ) {
    var projectedTriangles = new ArrayList<ProjectedTriangle>();
    for (var quad : quads) {
      if (quad.material().alphaMode() != alphaMode) {
        continue;
      }
      emitProjectedTriangles(quad, projectedTriangles, buffers.image().getWidth(), buffers.image().getHeight(), pixelsPerUnit);
    }

    if (projectedTriangles.isEmpty()) {
      return;
    }

    if (alphaMode == RendererAssets.AlphaMode.TRANSLUCENT) {
      projectedTriangles.sort((left, right) -> Double.compare(right.sortDepth(), left.sortDepth()));
    }

    for (var triangle : projectedTriangles) {
      SoftwareRasterizer.rasterizeGuiItemTriangle(animationTick, triangle, buffers, writeDepth);
    }
  }

  private static void emitProjectedTriangles(RenderQuad quad, ArrayList<ProjectedTriangle> out, int width, int height, float pixelsPerUnit) {
    var projected = new ProjectedVertex[]{
      projectVertex(quad.v0(), quad.material().depthBias(), width, height, pixelsPerUnit),
      projectVertex(quad.v1(), quad.material().depthBias(), width, height, pixelsPerUnit),
      projectVertex(quad.v2(), quad.material().depthBias(), width, height, pixelsPerUnit),
      projectVertex(quad.v3(), quad.material().depthBias(), width, height, pixelsPerUnit)
    };
    var sortDepth =
      (projected[0].depth() + projected[1].depth() + projected[2].depth() + projected[3].depth()) / 4.0F;
    out.add(new ProjectedTriangle(
      projected[0],
      projected[1],
      projected[2],
      quad.material(),
      sortDepth
    ));
    out.add(new ProjectedTriangle(
      projected[0],
      projected[2],
      projected[3],
      quad.material(),
      sortDepth
    ));
  }

  static ProjectedVertex projectVertex(RenderVertex vertex, float depthBias, int width, int height, float pixelsPerUnit) {
    var centerX = width * 0.5F;
    var centerY = height * 0.5F;
    var screenX = centerX + vertex.x() * pixelsPerUnit;
    var screenY = centerY + vertex.y() * pixelsPerUnit;
    var depth = (1000.0F - vertex.z() + depthBias) / 2000.0F;
    return new ProjectedVertex(
      screenX,
      screenY,
      depth,
      1.0F,
      vertex.u(),
      vertex.v(),
      (vertex.color() >>> 24) & 0xFF,
      ((vertex.color() >>> 16) & 0xFF) * vertex.shade(),
      ((vertex.color() >>> 8) & 0xFF) * vertex.shade(),
      (vertex.color() & 0xFF) * vertex.shade()
    );
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

        colors[index] = SoftwareRasterizer.blendStraightAlpha(base, (sampleAlpha << 24) | (sample & 0x00FFFFFF));
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
      if (!Float.isFinite(radius) || radius <= 0.0F || pieces.isEmpty()) {
        return;
      }

      var pose = poseStack.last().pose();
      var captured = 0;
      for (var piece : pieces) {
        if (captured >= MAX_INVENTORY_SHADOW_PIECES) {
          RenderDebugTrace.current().inventoryIconIgnored("shadow-overflow");
          break;
        }
        if (piece.alpha() <= 0.0F || piece.shapeBelow().isEmpty()) {
          continue;
        }

        var bounds = piece.shapeBelow().bounds();
        if (bounds.maxX <= bounds.minX || bounds.maxZ <= bounds.minZ) {
          continue;
        }

        var x0 = piece.relativeX() + (float) bounds.minX;
        var x1 = piece.relativeX() + (float) bounds.maxX;
        var y = piece.relativeY() + (float) bounds.minY;
        var z0 = piece.relativeZ() + (float) bounds.minZ;
        var z1 = piece.relativeZ() + (float) bounds.maxZ;
        var u0 = -x0 / (2.0F * radius) + 0.5F;
        var u1 = -x1 / (2.0F * radius) + 0.5F;
        var v0 = -z0 / (2.0F * radius) + 0.5F;
        var v1 = -z1 / (2.0F * radius) + 0.5F;
        addShadowQuad(
          pose,
          new Vector3f(x0, y, z0),
          new Vector3f(x0, y, z1),
          new Vector3f(x1, y, z1),
          new Vector3f(x1, y, z0),
          new float[]{u0, v0, u0, v1, u1, v1, u1, v0},
          shadowColor(piece.alpha())
        );
        captured++;
      }
    }

    @Override
    public void submitNameTag(
      PoseStack poseStack,
      Vec3 position,
      int color,
      Component text,
      boolean outline,
      int backgroundColor,
      CameraRenderState cameraRenderState
    ) {
      if (text.getString().isEmpty()) {
        return;
      }

      var pose = new Matrix4f(poseStack.last().pose());
      pose.translate((float) position.x(), (float) position.y(), (float) position.z());
      submitComponentText(pose, text, 0.0F, 0.0F, color, backgroundColor, Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT);
    }

    @Override
    public void submitText(
      PoseStack poseStack,
      float x,
      float y,
      FormattedCharSequence text,
      boolean shadow,
      Font.DisplayMode displayMode,
      int light,
      int color,
      int backgroundColor,
      int outlineColor
    ) {
      RenderDebugTrace.current().textSubmission(
        "inventory-formatted",
        formattedText(text),
        shadow,
        displayMode.name(),
        light,
        color,
        backgroundColor,
        outlineColor
      );
      var font = fontOrNull();
      if (font == null) {
        RenderDebugTrace.current().inventoryIconIgnored("text");
        return;
      }
      if (outlineColor != 0) {
        capturePreparedText(poseStack.last().pose(), font.prepare8xTextOutline(text, x, y, outlineColor), Font.DisplayMode.NORMAL, light);
      }
      capturePreparedText(
        poseStack.last().pose(),
        font.prepareText(text, x, y, color, shadow, displayMode == Font.DisplayMode.SEE_THROUGH, backgroundColor),
        displayMode,
        light
      );
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
      if (entityRenderState == null) {
        RenderDebugTrace.current().inventoryIconIgnored("flame-state");
        return;
      }

      var scale = entityRenderState.boundingBoxWidth * 1.4F;
      if (!Float.isFinite(scale) || scale <= 1.0E-6F) {
        return;
      }

      var fire0 = fireSprite(ModelBakery.FIRE_0);
      var fire1 = fireSprite(ModelBakery.FIRE_1);
      var texture = fire0 != null || fire1 != null
        ? RendererAssets.instance().textureAtlas(TextureAtlas.LOCATION_BLOCKS)
        : WHITE_TEXTURE;
      var renderType = Sheets.cutoutBlockItemSheet();
      var pose = poseStack.last().copy();
      pose.scale(scale, scale, scale);
      if (rotation != null) {
        pose.rotate(rotation);
      }
      var height = flameHeight(entityRenderState, scale);
      pose.translate(0.0F, 0.0F, 0.3F - (int) height * 0.02F);
      var consumer = new ItemCapturingVertexConsumer(
        pose.pose(),
        renderType.primitiveTopology(),
        renderType,
        texture,
        RendererAssets.AlphaMode.CUTOUT
      );

      var halfWidth = 0.5F;
      var yOffset = 0.0F;
      var zOffset = 0.0F;
      var layers = Math.min(MAX_INVENTORY_FLAME_LAYERS, Math.max(1, (int) Math.ceil(height / 0.45F)));
      for (var layer = 0; layer < layers && height > 0.0F; layer++) {
        var sprite = (layer & 1) == 0 ? fire0 : fire1;
        if (sprite == null) {
          sprite = fire0 != null ? fire0 : fire1;
        }
        var u0 = sprite != null ? sprite.getU0() : 0.0F;
        var v0 = sprite != null ? sprite.getV0() : 0.0F;
        var u1 = sprite != null ? sprite.getU1() : 1.0F;
        var v1 = sprite != null ? sprite.getV1() : 1.0F;
        if (layer / 2 % 2 == 0) {
          var tmp = u1;
          u1 = u0;
          u0 = tmp;
        }

        fireVertex(consumer, -halfWidth, -yOffset, zOffset, u1, v1);
        fireVertex(consumer, halfWidth, -yOffset, zOffset, u0, v1);
        fireVertex(consumer, halfWidth, 1.4F - yOffset, zOffset, u0, v0);
        fireVertex(consumer, -halfWidth, 1.4F - yOffset, zOffset, u1, v0);
        height -= 0.45F;
        yOffset -= 0.45F;
        halfWidth *= 0.9F;
        zOffset -= 0.03F;
      }
      consumer.flush(quads, textures);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
      if (leashState == null || leashState.start == null || leashState.end == null) {
        RenderDebugTrace.current().inventoryIconIgnored("leash-state");
        return;
      }

      var start = leashState.start;
      var end = leashState.end;
      var dx = end.x - start.x;
      var dy = end.y - start.y;
      var dz = end.z - start.z;
      if (dx * dx + dy * dy + dz * dz <= 1.0E-8D) {
        return;
      }

      var pose = new Matrix4f(poseStack.last().pose());
      pose.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);
      var consumer = newItemConsumer(pose, RenderTypes.lines(), INVENTORY_LEASH_COLOR);
      if (leashState.slack) {
        var midpoint = start.lerp(end, 0.5D).add(0.0D, -Math.min(0.25D, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.08D), 0.0D);
        addLeashSegment(consumer, start, midpoint);
        addLeashSegment(consumer, midpoint, end);
      } else {
        addLeashSegment(consumer, start, end);
      }
      consumer.flush(quads, textures);
    }

    private void addShadowQuad(
      Matrix4fc pose,
      Vector3f a,
      Vector3f b,
      Vector3f c,
      Vector3f d,
      float[] uv,
      int color
    ) {
      quads.add(new RenderQuad(
        shadowVertex(pose, a, uv[0], uv[1]),
        shadowVertex(pose, b, uv[2], uv[3]),
        shadowVertex(pose, c, uv[4], uv[5]),
        shadowVertex(pose, d, uv[6], uv[7]),
        RenderMaterial.create(WHITE_TEXTURE, RendererAssets.AlphaMode.TRANSLUCENT, color, true, 0.0F)
      ));
      textures.add(WHITE_TEXTURE);
    }

    private static RenderVertex shadowVertex(Matrix4fc pose, Vector3f position, float u, float v) {
      var transformed = pose.transformPosition(position);
      return new RenderVertex(transformed.x(), transformed.y(), transformed.z(), u, v, 0xFFFFFFFF);
    }

    private static int shadowColor(float alpha) {
      return Math.clamp(Math.round(alpha * 255.0F), 0, 255) << 24;
    }

    private static void addLeashSegment(VertexConsumer consumer, Vec3 start, Vec3 end) {
      var direction = new Vector3f(
        (float) (end.x - start.x),
        (float) (end.y - start.y),
        (float) (end.z - start.z)
      );
      if (direction.lengthSquared() <= 1.0E-8F) {
        return;
      }

      direction.normalize();
      addLeashVertex(consumer, start, direction);
      addLeashVertex(consumer, end, direction);
    }

    private static void addLeashVertex(VertexConsumer consumer, Vec3 position, Vector3f direction) {
      consumer
        .addVertex((float) position.x(), (float) position.y(), (float) position.z())
        .setColor(INVENTORY_LEASH_COLOR)
        .setNormal(direction.x(), direction.y(), direction.z())
        .setLineWidth(INVENTORY_LEASH_WIDTH);
    }

    private static float flameHeight(EntityRenderState entityRenderState, float scale) {
      var height = entityRenderState.boundingBoxHeight;
      if (!Float.isFinite(height) || height <= 0.0F) {
        return 1.4F;
      }
      return Math.max(0.45F, height / scale);
    }

    @Nullable
    private static TextureAtlasSprite fireSprite(SpriteId spriteId) {
      var minecraft = Minecraft.getInstance();
      if (minecraft == null) {
        return null;
      }

      try {
        return minecraft.getAtlasManager().get(spriteId);
      } catch (Throwable _) {
        return null;
      }
    }

    private static void fireVertex(VertexConsumer consumer, float x, float y, float z, float u, float v) {
      consumer.addVertex(x, y, z)
        .setColor(0xFFFFFFFF)
        .setUv(u, v);
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int color) {
      for (var face : RendererAssets.instance().blockGeometry(movingBlockRenderState.blockState).faces()) {
        var transformed = face.transformed(poseStack.last().pose());
        quads.add(WorldMeshCollector.toRenderQuad(transformed, 0.0, 0.0, 0.0, color, false, 0.0F));
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
        for (var direction : net.minecraft.core.Direction.values()) {
          for (var quad : part.getQuads(direction)) {
            addVanillaQuad(quads, textures, quad, new Matrix4f(poseStack.last().pose()), tintList);
          }
        }
        for (var quad : part.getQuads(null)) {
          addVanillaQuad(quads, textures, quad, new Matrix4f(poseStack.last().pose()), tintList);
        }
      }
    }

    @Override
    public void submitBreakingBlockModel(
      PoseStack poseStack,
      List<BlockStateModelPart> parts,
      int color
    ) {
      submitBlockModel(poseStack, null, parts, new int[0], 0, 0, color);
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float lineWidth, boolean expanded) {
      if (shape.isEmpty()) {
        return;
      }

      var texture = textureImageFromRenderType(renderType);
      var alphaMode = VanillaSubmitCollector.alphaMode(renderType, texture, color);
      var consumer = new ItemCapturingVertexConsumer(poseStack.last().pose(), renderType.primitiveTopology(), renderType, texture, alphaMode);
      var normal = new Vector3f();
      shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
        normal.set((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
        if (normal.lengthSquared() <= 1.0E-8F) {
          return;
        }

        normal.normalize();
        consumer.addVertex((float) x1, (float) y1, (float) z1)
          .setColor(color)
          .setNormal(normal.x(), normal.y(), normal.z())
          .setLineWidth(lineWidth);
        consumer.addVertex((float) x2, (float) y2, (float) z2)
          .setColor(color)
          .setNormal(normal.x(), normal.y(), normal.z())
          .setLineWidth(lineWidth);
      });
      consumer.flush(quads, textures);
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
      var alphaMode = VanillaSubmitCollector.alphaMode(renderType, texture, 0xFFFFFFFF);
      var consumer = new ItemCapturingVertexConsumer(new Matrix4f(), renderType.primitiveTopology(), renderType, texture, alphaMode);
      renderer.render(poseStack.last(), consumer);
      consumer.flush(quads, textures);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState quadParticles) {
      if (quadParticles.isEmpty()) {
        return;
      }

      for (var entry : quadParticles.particles.entrySet()) {
        var layer = entry.getKey();
        var storage = entry.getValue();
        var texture = RendererAssets.instance().textureAtlas(layer.textureAtlasLocation());
        var alphaMode = layer.translucent() ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.CUTOUT;
        storage.forEachParticle((x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, lightCoords) -> {
          var rotation = new Quaternionf(qx, qy, qz, qw);
          var vertices = new Vector3f[]{
            rotateParticleVertex(rotation, x, y, z, size, 1.0F, -1.0F),
            rotateParticleVertex(rotation, x, y, z, size, 1.0F, 1.0F),
            rotateParticleVertex(rotation, x, y, z, size, -1.0F, 1.0F),
            rotateParticleVertex(rotation, x, y, z, size, -1.0F, -1.0F)
          };
          var material = RenderMaterial
            .create(
              texture,
              alphaMode,
              color,
              true,
              0.0F,
              RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD
            )
            .withPipelineState(layer.pipeline());
          quads.add(new RenderQuad(
            new RenderVertex(vertices[0].x(), vertices[0].y(), vertices[0].z(), u1, v1, 0xFFFFFFFF),
            new RenderVertex(vertices[1].x(), vertices[1].y(), vertices[1].z(), u1, v0, 0xFFFFFFFF),
            new RenderVertex(vertices[2].x(), vertices[2].y(), vertices[2].z(), u0, v0, 0xFFFFFFFF),
            new RenderVertex(vertices[3].x(), vertices[3].y(), vertices[3].z(), u0, v1, 0xFFFFFFFF),
            material
          ));
          textures.add(texture);
        });
      }
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState, boolean onTop) {
      submitGizmoQuads(group);
      submitGizmoTriangleFans(group);
      submitGizmoLines(group);
      submitGizmoTexts(group, cameraRenderState);
      submitGizmoPoints(group);
    }

    private void submitGizmoQuads(DrawableGizmoPrimitives.Group group) {
      if (group.quads().isEmpty()) {
        return;
      }

      var consumer = newItemConsumer(new Matrix4f(), RenderTypes.debugFilledBox(), 0xFFFFFFFF);
      for (var quad : group.quads()) {
        addGizmoVertex(consumer, quad.a(), quad.color());
        addGizmoVertex(consumer, quad.b(), quad.color());
        addGizmoVertex(consumer, quad.c(), quad.color());
        addGizmoVertex(consumer, quad.d(), quad.color());
      }
      consumer.flush(quads, textures);
    }

    private void submitGizmoTriangleFans(DrawableGizmoPrimitives.Group group) {
      if (group.triangleFans().isEmpty()) {
        return;
      }

      for (var triangleFan : group.triangleFans()) {
        if (triangleFan.points().length < 3) {
          continue;
        }

        var consumer = newItemConsumer(new Matrix4f(), RenderTypes.debugTriangleFan(), triangleFan.color());
        for (var point : triangleFan.points()) {
          addGizmoVertex(consumer, point, triangleFan.color());
        }
        consumer.flush(quads, textures);
      }
    }

    private void submitGizmoLines(DrawableGizmoPrimitives.Group group) {
      if (group.lines().isEmpty()) {
        return;
      }

      var renderType = group.opaque() ? RenderTypes.lines() : RenderTypes.linesTranslucent();
      var consumer = newItemConsumer(new Matrix4f(), renderType, 0xFFFFFFFF);
      var direction = new Vector3f();
      for (var line : group.lines()) {
        direction.set(
          (float) (line.end().x() - line.start().x()),
          (float) (line.end().y() - line.start().y()),
          (float) (line.end().z() - line.start().z())
        );
        if (direction.lengthSquared() <= 1.0E-8F) {
          continue;
        }

        direction.normalize();
        addGizmoLineVertex(consumer, line.start(), line.color(), direction, line.width());
        addGizmoLineVertex(consumer, line.end(), line.color(), direction, line.width());
      }
      consumer.flush(quads, textures);
    }

    private void submitGizmoPoints(DrawableGizmoPrimitives.Group group) {
      if (group.points().isEmpty()) {
        return;
      }

      var consumer = newItemConsumer(new Matrix4f(), RenderTypes.debugPoint(), 0xFFFFFFFF);
      for (var point : group.points()) {
        addGizmoVertex(consumer, point.pos(), point.color()).setLineWidth(point.size());
      }
      consumer.flush(quads, textures);
    }

    private void submitGizmoTexts(DrawableGizmoPrimitives.Group group, @Nullable CameraRenderState cameraRenderState) {
      if (group.texts().isEmpty()) {
        return;
      }

      var poseStack = new PoseStack();
      var font = fontOrNull();
      if (font == null) {
        RenderDebugTrace.current().inventoryIconIgnored("gizmo-text");
        return;
      }
      for (var text : group.texts()) {
        var style = text.style();
        if (!Float.isFinite(style.scale()) || style.scale() <= 0.0F || text.text().isEmpty()) {
          continue;
        }

        poseStack.pushPose();
        try {
          poseStack.translate(text.pos().x(), text.pos().y(), text.pos().z());
          if (cameraRenderState != null && cameraRenderState.initialized) {
            poseStack.mulPose(cameraRenderState.orientation);
          }
          poseStack.scale(style.scale() / 16.0F, -style.scale() / 16.0F, style.scale() / 16.0F);
          var x = style.adjustLeft().isEmpty()
            ? -font.width(text.text()) / 2.0F
            : (float) -style.adjustLeft().getAsDouble() / style.scale();
          capturePreparedText(
            poseStack.last().pose(),
            font.prepareText(text.text(), x, 0.0F, style.color(), false, 0),
            Font.DisplayMode.NORMAL,
            LightCoordsUtil.FULL_BRIGHT
          );
        } finally {
          poseStack.popPose();
        }
      }
    }

    private void submitComponentText(
      Matrix4f pose,
      Component text,
      float x,
      float y,
      int color,
      int backgroundColor,
      Font.DisplayMode displayMode,
      int light
    ) {
      RenderDebugTrace.current().textSubmission(
        "inventory-component",
        text.getString(),
        false,
        displayMode.name(),
        light,
        color,
        backgroundColor,
        0
      );
      var font = fontOrNull();
      if (font == null) {
        RenderDebugTrace.current().inventoryIconIgnored("name-tag");
        return;
      }
      capturePreparedText(
        pose,
        font.prepareText(text.getVisualOrderText(), x, y, color, false, displayMode == Font.DisplayMode.SEE_THROUGH, backgroundColor),
        displayMode,
        light
      );
    }

    private void capturePreparedText(Matrix4fc pose, Font.PreparedText preparedText, Font.DisplayMode displayMode, int light) {
      preparedText.visit(new Font.GlyphVisitor() {
        @Override
        public void acceptRenderable(TextRenderable renderable) {
          captureTextRenderable(pose, renderable, displayMode, light);
        }
      });
    }

    private void captureTextRenderable(Matrix4fc pose, TextRenderable renderable, Font.DisplayMode displayMode, int light) {
      var renderType = renderable.renderType(displayMode);
      var textureView = renderable.textureView();
      var texture = textureView != null ? RendererRuntimeTextureMirror.texture(textureView.texture()) : null;
      if (texture == null) {
        texture = textureImageFromRenderType(renderType);
      }

      var alphaMode = VanillaSubmitCollector.alphaMode(renderType, texture, 0xFFFFFFFF);
      var consumer = new ItemCapturingVertexConsumer(new Matrix4f(), renderType.primitiveTopology(), renderType, texture, alphaMode);
      renderable.render(pose, consumer, light, displayMode == Font.DisplayMode.SEE_THROUGH);
      consumer.flush(quads, textures);
    }

    private ItemCapturingVertexConsumer newItemConsumer(Matrix4fc pose, RenderType renderType, int color) {
      var texture = textureImageFromRenderType(renderType);
      var alphaMode = VanillaSubmitCollector.alphaMode(renderType, texture, color);
      return new ItemCapturingVertexConsumer(pose, renderType.primitiveTopology(), renderType, texture, alphaMode);
    }

    @Nullable
    private static Font fontOrNull() {
      var minecraft = Minecraft.getInstance();
      return minecraft != null ? minecraft.font : null;
    }

    private static String formattedText(FormattedCharSequence text) {
      var builder = new StringBuilder();
      text.accept((_, _, codePoint) -> {
        builder.appendCodePoint(codePoint);
        return true;
      });
      return builder.toString();
    }

    private static VertexConsumer addGizmoVertex(VertexConsumer consumer, Vec3 position, int color) {
      return consumer
        .addVertex((float) position.x(), (float) position.y(), (float) position.z())
        .setColor(color);
    }

    private static void addGizmoLineVertex(VertexConsumer consumer, Vec3 position, int color, Vector3f direction, float width) {
      consumer
        .addVertex((float) position.x(), (float) position.y(), (float) position.z())
        .setColor(color)
        .setNormal(direction.x(), direction.y(), direction.z())
        .setLineWidth(width);
    }
  }

  private static final class ItemCapturingVertexConsumer implements com.mojang.blaze3d.vertex.VertexConsumer {
    private final Matrix4fc pose;
    private final PrimitiveTopology mode;
    private final RenderType renderType;
    private final RendererAssets.TextureImage texture;
    private final RendererAssets.AlphaMode alphaMode;
    private final ArrayList<CapturedVertex> vertices = new ArrayList<>();
    private float lineWidth = 1.0F;
    private CapturedVertex current;

    private ItemCapturingVertexConsumer(
      Matrix4fc pose,
      PrimitiveTopology mode,
      RenderType renderType,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode
    ) {
      this.pose = pose;
      this.mode = mode;
      this.renderType = renderType;
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
        case TRIANGLE_STRIP -> {
          for (var i = 0; i + 2 < vertices.size(); i++) {
            if ((i & 1) == 0) {
              emitTriangle(quads, textures, vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
            } else {
              emitTriangle(quads, textures, vertices.get(i + 1), vertices.get(i), vertices.get(i + 2));
            }
          }
        }
        case TRIANGLE_FAN -> {
          for (var i = 1; i + 1 < vertices.size(); i++) {
            emitTriangle(quads, textures, vertices.getFirst(), vertices.get(i), vertices.get(i + 1));
          }
        }
        case LINES, DEBUG_LINES, DEBUG_LINE_STRIP -> {
          var stride = mode == PrimitiveTopology.DEBUG_LINE_STRIP ? 1 : 2;
          for (var i = 0; i + 1 < vertices.size(); i += stride) {
            emitLine(quads, textures, vertices.get(i), vertices.get(i + 1));
          }
        }
        case POINTS -> {
          for (var vertex : vertices) {
            emitPoint(quads, textures, vertex);
          }
        }
        default -> {
        }
      }
    }

    private void emitQuad(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex a, CapturedVertex b, CapturedVertex c, CapturedVertex d) {
      quads.add(new RenderQuad(
        renderVertex(a),
        renderVertex(b),
        renderVertex(c),
        renderVertex(d),
        RenderMaterial.create(texture, alphaMode, 0xFFFFFFFF, true, 0.0F)
          .withRenderType(renderType)
      ));
      textures.add(texture);
    }

    private void emitTriangle(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex a, CapturedVertex b, CapturedVertex c) {
      emitQuad(quads, textures, a, b, c, c);
    }

    private void emitLine(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex a, CapturedVertex b) {
      var start = a.position();
      var end = b.position();
      var dx = end.x() - start.x();
      var dy = end.y() - start.y();
      var length = (float) Math.sqrt(dx * dx + dy * dy);
      if (!Float.isFinite(length) || length <= 1.0E-6F) {
        emitPoint(quads, textures, a);
        emitPoint(quads, textures, b);
        return;
      }

      var halfWidthA = Math.max(0.0F, a.lineWidth()) / (GUI_PIXELS_PER_UNIT * 2.0F);
      var halfWidthB = Math.max(0.0F, b.lineWidth()) / (GUI_PIXELS_PER_UNIT * 2.0F);
      var perpX = -dy / length;
      var perpY = dx / length;
      emitQuad(
        quads,
        textures,
        new CapturedVertex(new Vector3f(start.x() + perpX * halfWidthA, start.y() + perpY * halfWidthA, start.z()), a.color(), a.u(), a.v(), a.lineWidth()),
        new CapturedVertex(new Vector3f(start.x() - perpX * halfWidthA, start.y() - perpY * halfWidthA, start.z()), a.color(), a.u(), a.v(), a.lineWidth()),
        new CapturedVertex(new Vector3f(end.x() - perpX * halfWidthB, end.y() - perpY * halfWidthB, end.z()), b.color(), b.u(), b.v(), b.lineWidth()),
        new CapturedVertex(new Vector3f(end.x() + perpX * halfWidthB, end.y() + perpY * halfWidthB, end.z()), b.color(), b.u(), b.v(), b.lineWidth())
      );
    }

    private void emitPoint(List<RenderQuad> quads, Set<RendererAssets.TextureImage> textures, CapturedVertex vertex) {
      var position = vertex.position();
      var halfSize = Math.max(0.0F, vertex.lineWidth()) / (GUI_PIXELS_PER_UNIT * 2.0F);
      emitQuad(
        quads,
        textures,
        new CapturedVertex(new Vector3f(position.x() - halfSize, position.y() - halfSize, position.z()), vertex.color(), vertex.u(), vertex.v(), vertex.lineWidth()),
        new CapturedVertex(new Vector3f(position.x() - halfSize, position.y() + halfSize, position.z()), vertex.color(), vertex.u(), vertex.v(), vertex.lineWidth()),
        new CapturedVertex(new Vector3f(position.x() + halfSize, position.y() + halfSize, position.z()), vertex.color(), vertex.u(), vertex.v(), vertex.lineWidth()),
        new CapturedVertex(new Vector3f(position.x() + halfSize, position.y() - halfSize, position.z()), vertex.color(), vertex.u(), vertex.v(), vertex.lineWidth())
      );
    }

    private RenderVertex renderVertex(CapturedVertex vertex) {
      var position = vertex.position();
      return new RenderVertex(position.x(), position.y(), position.z(), vertex.u(), vertex.v(), vertex.color());
    }

    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
      current = new CapturedVertex(pose.transformPosition(new Vector3f(x, y, z)), 0xFFFFFFFF, 0.0F, 0.0F, lineWidth);
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
    @Override
    public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
      lineWidth = Float.isFinite(width) ? Math.max(0.0F, width) : 1.0F;
      if (current != null) {
        current = current.withLineWidth(lineWidth);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }
  }

  private static RendererAssets.TextureImage textureImageFromRenderType(RenderType renderType) {
    RenderSetup state = renderType.state;
    if (state == null || state.textures == null || state.textures.isEmpty()) {
      return WHITE_TEXTURE;
    }

    for (var binding : state.textures.values()) {
      if (binding.location() == null) {
        continue;
      }
      return textureImage(binding);
    }

    return WHITE_TEXTURE;
  }

  private static RendererAssets.TextureImage textureImage(RenderSetup.TextureBinding binding) {
    return RendererAssets.withSampler(RendererAssets.instance().renderTexture(binding.location()), binding.sampler());
  }

  private record CapturedVertex(Vector3f position, int color, float u, float v, float lineWidth) {
    private CapturedVertex withColor(int color) {
      return new CapturedVertex(position, color, u, v, lineWidth);
    }

    private CapturedVertex withUv(float u, float v) {
      return new CapturedVertex(position, color, u, v, lineWidth);
    }

    private CapturedVertex withLineWidth(float lineWidth) {
      return new CapturedVertex(position, color, u, v, lineWidth);
    }
  }
}
