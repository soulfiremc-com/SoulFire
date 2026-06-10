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

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class VanillaSubmitCollector implements SubmitNodeCollector, OrderedSubmitNodeCollector {
  private static final RendererAssets.TextureImage WHITE_TEXTURE = createSolidTexture(0xFFFFFFFF);
  private final RenderContext ctx;
  private final RendererAssets assets;
  private final SceneData.Builder builder = SceneData.builder();
  private final Font font;

  private VanillaSubmitCollector(RenderContext ctx) {
    this.ctx = ctx;
    this.assets = RendererAssets.instance();
    this.font = Minecraft.getInstance().font;
  }

  static void prepareEntityDispatcher(RenderContext ctx, @Nullable LocalPlayer cameraEntity) {
    var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
    var preparedCamera = new net.minecraft.client.Camera();
    var activeCameraEntity = cameraEntity != null ? cameraEntity : Minecraft.getInstance().player;
    if (activeCameraEntity == null) {
      dispatcher.resetCamera();
      return;
    }

    preparedCamera.setLevel(ctx.level());
    preparedCamera.setEntity(activeCameraEntity);
    preparedCamera.setPosition(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    preparedCamera.setRotation(ctx.camera().yRot(), ctx.camera().xRot());
    dispatcher.prepare(preparedCamera, activeCameraEntity);
  }

  static void resetEntityDispatcher() {
    Minecraft.getInstance().getEntityRenderDispatcher().resetCamera();
  }

  @Nullable
  static EntityRenderState extractEntityState(Entity entity) {
    try {
      var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
      @SuppressWarnings({"rawtypes"})
      EntityRenderer rawRenderer = dispatcher.getRenderer(entity);
      return rawRenderer != null ? rawRenderer.createRenderState(entity, 1.0F) : null;
    } catch (Throwable t) {
      return null;
    }
  }

  static SceneData collectEntity(RenderContext ctx, Entity entity, @Nullable EntityRenderState renderState) {
    var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
    @SuppressWarnings({"rawtypes"})
    EntityRenderer rawRenderer = dispatcher.getRenderer(entity);
    if (rawRenderer == null) {
      return SceneData.EMPTY;
    }

    var state = renderState != null ? renderState : extractEntityState(entity);
    if (state == null) {
      return SceneData.EMPTY;
    }

    var collector = new VanillaSubmitCollector(ctx);
    var cameraState = collector.cameraRenderState();
    var poseStack = new PoseStack();
    var renderOffset = rawRenderer.getRenderOffset(state);
    poseStack.pushPose();
    poseStack.translate(entity.getX() + renderOffset.x(), entity.getY() + renderOffset.y(), entity.getZ() + renderOffset.z());
    rawRenderer.submit(state, poseStack, collector, cameraState);
    if (state.displayFireAnimation) {
      collector.submitFlame(poseStack, state, Mth.rotationAroundAxis(Mth.Y_AXIS, cameraState.orientation, new Quaternionf()));
    }
    if (state instanceof AvatarRenderState) {
      poseStack.translate(-renderOffset.x(), -renderOffset.y(), -renderOffset.z());
    }
    if (!state.shadowPieces.isEmpty()) {
      collector.submitShadow(poseStack, state.shadowRadius, state.shadowPieces);
    }
    if (!(state instanceof AvatarRenderState)) {
      poseStack.translate(-renderOffset.x(), -renderOffset.y(), -renderOffset.z());
    }
    poseStack.popPose();
    return collector.builder.build();
  }

  static SceneData collectBlockEntity(RenderContext ctx, BlockEntity blockEntity) {
    var collector = new VanillaSubmitCollector(ctx);
    var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
    dispatcher.prepare(new Vec3(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ()));
    var renderState = dispatcher.tryExtractRenderState(blockEntity, 1.0F, null);
    if (renderState == null) {
      return SceneData.EMPTY;
    }

    var poseStack = new PoseStack();
    var blockPos = blockEntity.getBlockPos();
    poseStack.translate(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    dispatcher.submit(renderState, poseStack, collector, collector.cameraRenderState());
    return collector.builder.build();
  }

  static SceneData collectParticles(RenderContext ctx) {
    var minecraft = Minecraft.getInstance();
    var particleCamera = new net.minecraft.client.Camera();
    particleCamera.setLevel(ctx.level());
    if (minecraft.player != null) {
      particleCamera.setEntity(minecraft.player);
    }
    particleCamera.setRotation(ctx.camera().yRot(), ctx.camera().xRot());
    particleCamera.setPosition(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());

    var particlesState = new ParticlesRenderState();
    minecraft.particleEngine.extract(particlesState, createFrustum(ctx), particleCamera, 1.0F);
    if (particlesState.particles.isEmpty()) {
      return SceneData.EMPTY;
    }

    var storage = new SubmitNodeStorage();
    var collector = new VanillaSubmitCollector(ctx);
    particlesState.submit(storage, collector.cameraRenderState());
    for (var collection : storage.getSubmitsPerOrder().values()) {
      for (var particleGroupRenderer : collection.getParticleGroupRenderers()) {
        collector.submitParticleGroup(particleGroupRenderer);
      }
    }
    return collector.builder.build();
  }

  private static Frustum createFrustum(RenderContext ctx) {
    var frustum = new Frustum(ctx.camera().viewRotationMatrix(), ctx.camera().projectionMatrix());
    frustum.prepare(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    return frustum;
  }

  static SceneData collectFluid(RenderContext ctx, FluidRenderer fluidRenderer, BlockPos blockPos, BlockState blockState, FluidState fluidState) {
    var collector = new VanillaSubmitCollector(ctx);
    var output = collector.new FluidOutput(blockPos);
    fluidRenderer.tesselate(ctx.level(), blockPos, output, blockState, fluidState);
    output.flush();
    return collector.builder.build();
  }

  private CameraRenderState cameraRenderState() {
    var cameraState = new CameraRenderState();
    cameraState.blockPos = BlockPos.containing(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    cameraState.pos = new Vec3(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    cameraState.xRot = ctx.camera().xRot();
    cameraState.yRot = ctx.camera().yRot();
    cameraState.initialized = true;
    cameraState.orientation = ctx.camera().orientation();
    cameraState.cullFrustum = createFrustum(ctx);
    cameraState.projectionMatrix = new Matrix4f(ctx.camera().projectionMatrix());
    cameraState.viewRotationMatrix = new Matrix4f(ctx.camera().viewRotationMatrix());
    cameraState.depthFar = ctx.camera().farPlane();
    cameraState.entityRenderState = new CameraEntityRenderState();
    return cameraState;
  }

  @Override
  public OrderedSubmitNodeCollector order(int order) {
    return this;
  }

  @Override
  public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    for (var piece : pieces) {
      var bounds = piece.shapeBelow().bounds();
      if (bounds.maxX <= bounds.minX || bounds.maxZ <= bounds.minZ) {
        continue;
      }

      var vertices = new Vector3f[]{
        transform(poseStack, piece.relativeX() + (float) bounds.minX, piece.relativeY() + (float) bounds.maxY + 0.001F, piece.relativeZ() + (float) bounds.minZ),
        transform(poseStack, piece.relativeX() + (float) bounds.minX, piece.relativeY() + (float) bounds.maxY + 0.001F, piece.relativeZ() + (float) bounds.maxZ),
        transform(poseStack, piece.relativeX() + (float) bounds.maxX, piece.relativeY() + (float) bounds.maxY + 0.001F, piece.relativeZ() + (float) bounds.maxZ),
        transform(poseStack, piece.relativeX() + (float) bounds.maxX, piece.relativeY() + (float) bounds.maxY + 0.001F, piece.relativeZ() + (float) bounds.minZ)
      };
      addFace(vertices, WHITE_TEXTURE, RendererAssets.AlphaMode.TRANSLUCENT, (int) (piece.alpha() * 255.0F) << 24, 0, true, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F});
    }
  }

  @Override
  public void submitNameTag(
    PoseStack poseStack,
    Vec3 nameTagAttachment,
    int offset,
    Component name,
    boolean seeThrough,
    int lightCoords,
    double distanceToCameraSq,
    CameraRenderState cameraRenderState
  ) {
    if (nameTagAttachment == null) {
      return;
    }

    poseStack.pushPose();
    poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
    poseStack.mulPose(cameraRenderState.orientation);
    poseStack.scale(0.025F, -0.025F, 0.025F);
    var textColor = seeThrough ? 0xFFFFFFFF : 0xCCFFFFFF;
    var backgroundColor = seeThrough ? 0x40000000 : 0x30000000;
    addComponentQuad(
      poseStack,
      name,
      xOffset(name),
      offset,
      textColor,
      backgroundColor,
      seeThrough ? RenderMaterial.DepthTest.ALWAYS_PASS : RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL,
      !seeThrough
    );
    poseStack.popPose();
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
    addSequenceQuad(poseStack, text, x, y, color, backgroundColor, textDepthBias(displayMode), textDepthTest(displayMode), textDepthWrite(displayMode));
  }

  @Override
  public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
    var width = Math.max(0.3F, entityRenderState.boundingBoxWidth * 0.9F);
    var height = Math.max(0.5F, entityRenderState.boundingBoxHeight * 1.1F);
    var fire = assets.texture(Identifier.withDefaultNamespace("block/fire_0"));
    var vertices = new Vector3f[]{
      transform(poseStack, -width * 0.5F, 0.0F, 0.0F),
      transform(poseStack, -width * 0.5F, height, 0.0F),
      transform(poseStack, width * 0.5F, height, 0.0F),
      transform(poseStack, width * 0.5F, 0.0F, 0.0F)
    };
    addFace(vertices, fire, RendererAssets.AlphaMode.CUTOUT, 0xFFFFFFFF, 15, true, new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F});
  }

  @Override
  public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    var start = leashState.start;
    var end = leashState.end;
    if (start == null || end == null) {
      return;
    }

    var direction = new Vector3f((float) (end.x - start.x), (float) (end.y - start.y), (float) (end.z - start.z));
    if (direction.lengthSquared() < 1.0E-6F) {
      return;
    }
    direction.normalize();
    var side = new Vector3f(direction.z, 0.0F, -direction.x);
    if (side.lengthSquared() < 1.0E-6F) {
      side.set(1.0F, 0.0F, 0.0F);
    }
    side.normalize(0.02F);
    var vertices = new Vector3f[]{
      transform(poseStack, (float) start.x - side.x, (float) start.y, (float) start.z - side.z),
      transform(poseStack, (float) start.x + side.x, (float) start.y, (float) start.z + side.z),
      transform(poseStack, (float) end.x + side.x, (float) end.y, (float) end.z + side.z),
      transform(poseStack, (float) end.x - side.x, (float) end.y, (float) end.z - side.z)
    };
    addFace(vertices, WHITE_TEXTURE, RendererAssets.AlphaMode.OPAQUE, 0xFF6B4A2C, 0, true, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F});
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
      texture = textureFromRenderType(renderType);
    }
    var faceEmission = emission >= 0 && emission <= 15 ? emission : 0;
    var outlineColor = emission > 15 || emission < 0 ? emission : 0;

    var alphaMode = alphaMode(renderType, texture);
    model.setupAnim(state);
    appendModelPartGeometry(
      model.root(),
      poseStack,
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      color,
      faceEmission,
      outlineColor,
      renderType
    );
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
      texture = textureFromRenderType(renderType);
    }
    var faceEmission = emission >= 0 && emission <= 15 ? emission : 0;
    var outlineColor = emission > 15 || emission < 0 ? emission : 0;

    var alphaMode = alphaMode(renderType, texture);
    appendModelPartGeometry(
      modelPart,
      poseStack,
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      color,
      faceEmission,
      outlineColor,
      renderType
    );
  }

  @Override
  public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {
    for (var face : assets.blockGeometry(movingBlockRenderState.blockState).faces()) {
      builder.add(WorldMeshCollector.toRenderQuad(face.transformed(poseStack.last().pose()), 0.0, 0.0, 0.0, 0xFFFFFFFF, false, 0.0F));
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
    for (var part : parts) {
      for (var quad : part.getQuads(null)) {
        appendBakedQuad(quad, poseStack.last().pose(), renderType, color, tints);
      }
      for (var direction : Direction.values()) {
        for (var quad : part.getQuads(direction)) {
          appendBakedQuad(quad, poseStack.last().pose(), renderType, color, tints);
        }
      }
    }
  }

  @Override
  public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel blockStateModel, long seed, int color) {
    var parts = new ArrayList<BlockStateModelPart>();
    blockStateModel.collectParts(RandomSource.create(seed), parts);
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
    for (var quad : quads) {
      appendBakedQuad(quad, poseStack.last().pose(), quad.materialInfo() != null ? quad.materialInfo().itemRenderType() : null, color, tints);
    }
  }

  @Override
  public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
    var texture = textureFromRenderType(renderType);
    var alphaMode = alphaMode(renderType, texture);
    var consumer = new CapturingVertexConsumer(
      poseStack.last().pose(),
      renderType.mode(),
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      renderType.pipeline().getDepthStencilState()
    );
    renderer.render(poseStack.last(), consumer);
    consumer.flush();
  }

  @Override
  public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
    if (!(particleGroupRenderer instanceof QuadParticleRenderState quadParticles) || quadParticles.isEmpty()) {
      return;
    }

    for (Map.Entry<SingleQuadParticle.Layer, QuadParticleRenderState.Storage> entry : quadParticles.particles.entrySet()) {
      var layer = entry.getKey();
      var storage = entry.getValue();
      var texture = assets.textureAtlas(layer.textureAtlasLocation());
      var alphaMode = layer.translucent() ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.CUTOUT;
      storage.forEachParticle((x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, _) -> {
        var rotation = new Quaternionf(qx, qy, qz, qw);
        var worldX = x + (float) ctx.camera().eyeX();
        var worldY = y + (float) ctx.camera().eyeY();
        var worldZ = z + (float) ctx.camera().eyeZ();
        var vertices = new Vector3f[]{
          rotateParticleVertex(rotation, worldX, worldY, worldZ, size, 1.0F, -1.0F),
          rotateParticleVertex(rotation, worldX, worldY, worldZ, size, 1.0F, 1.0F),
          rotateParticleVertex(rotation, worldX, worldY, worldZ, size, -1.0F, 1.0F),
          rotateParticleVertex(rotation, worldX, worldY, worldZ, size, -1.0F, -1.0F)
        };
        addFace(vertices, texture, alphaMode, color, 0, true, new float[]{u1, v1, u1, v0, u0, v0, u0, v1});
      });
    }
  }

  private void appendModelPartGeometry(
    ModelPart modelPart,
    PoseStack poseStack,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int alphaCutoutThreshold,
    int color,
    int emission,
    int outlineColor,
    @Nullable RenderType renderType
  ) {
    modelPart.visit(poseStack, (pose, _, _, cube) -> {
      for (var polygon : cube.polygons) {
        var vertices = new Vector3f[4];
        var outlineVertices = outlineColor != 0 ? new Vector3f[4] : null;
        var uv = new float[8];
        for (var i = 0; i < polygon.vertices().length; i++) {
          var vertex = polygon.vertices()[i];
          var localX = vertex.x() / 16.0F;
          var localY = vertex.y() / 16.0F;
          var localZ = vertex.z() / 16.0F;
          vertices[i] = pose.pose().transformPosition(localX, localY, localZ, new Vector3f());
          if (outlineVertices != null) {
            outlineVertices[i] = pose.pose().transformPosition(localX * 1.03F, localY * 1.03F, localZ * 1.03F, new Vector3f());
          }
          uv[i * 2] = vertex.u();
          uv[i * 2 + 1] = vertex.v();
        }

        var face = RendererAssets.GeometryFace.of(vertices, uv, texture, alphaMode, null, -1, emission, true);
        builder.add(withDepthState(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, color, true, 0.0F, alphaCutoutThreshold), renderType));
        if (outlineVertices != null) {
          addFace(outlineVertices, WHITE_TEXTURE, RendererAssets.AlphaMode.OPAQUE, outlineColor, 0, true, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F});
        }
      }
    });
  }

  private void appendBakedQuad(BakedQuad quad, Matrix4fc poseMatrix, @Nullable RenderType renderType, int baseColor, @Nullable int[] tints) {
    if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null || quad.materialInfo().sprite().contents() == null) {
      return;
    }

    var sprite = quad.materialInfo().sprite();
    var texture = assets.texture(sprite.contents().name());
    var vertices = new Vector3f[4];
    var uv = new float[8];
    for (var i = 0; i < 4; i++) {
      Vector3fc position = quad.position(i);
      vertices[i] = poseMatrix.transformPosition(new Vector3f(position));
      var packedUv = quad.packedUV(i);
      uv[i * 2] = BakedQuadUv.localU(sprite, packedUv);
      uv[i * 2 + 1] = BakedQuadUv.localV(sprite, packedUv);
    }

    var color = baseColor;
    if (quad.materialInfo().isTinted() && tints != null) {
      var tintIndex = quad.materialInfo().tintIndex();
      if (tintIndex >= 0 && tintIndex < tints.length) {
        color = modulateColor(color, tints[tintIndex]);
      }
    }

    var effectiveRenderType = renderType != null ? renderType : quad.materialInfo().itemRenderType();
    var alphaMode = alphaMode(effectiveRenderType, texture);
    var face = RendererAssets.GeometryFace.of(
      vertices,
      uv,
      texture,
      alphaMode,
      quad.direction(),
      -1,
      quad.materialInfo().lightEmission(),
      quad.materialInfo().shade()
    );
    builder.add(withDepthState(WorldMeshCollector.toRenderQuad(
      face,
      0.0,
      0.0,
      0.0,
      color,
      true,
      0.0F,
      alphaCutoutThreshold(effectiveRenderType, alphaMode)
    ), effectiveRenderType));
  }

  @Nullable
  private RendererAssets.TextureImage textureImage(@Nullable TextureAtlasSprite sprite) {
    if (sprite == null || sprite.contents() == null || sprite.contents().name() == null) {
      return null;
    }
    return assets.texture(sprite.contents().name());
  }

  private void addComponentQuad(
    PoseStack poseStack,
    Component text,
    float x,
    float y,
    int color,
    int backgroundColor,
    RenderMaterial.DepthTest depthTest,
    boolean depthWrite
  ) {
    var width = Math.max(16, font.width(text) + 4);
    var texture = assets.textTexture(text, width, color, backgroundColor);
    addTexturedQuad(poseStack, texture.width(), texture.height(), x, y, texture, 0.0F, depthTest, depthWrite);
  }

  private void addSequenceQuad(
    PoseStack poseStack,
    FormattedCharSequence text,
    float x,
    float y,
    int color,
    int backgroundColor,
    float depthBias,
    RenderMaterial.DepthTest depthTest,
    boolean depthWrite
  ) {
    var plain = plainText(text);
    if (plain.isEmpty()) {
      return;
    }

    var width = Math.max(16, font.width(text) + 4);
    var texture = assets.textTexture(Component.literal(plain), width, color, backgroundColor);
    addTexturedQuad(poseStack, texture.width(), texture.height(), x, y, texture, depthBias, depthTest, depthWrite);
  }

  private void addTexturedQuad(PoseStack poseStack, float width, float height, float x, float y, RendererAssets.TextureImage texture) {
    addTexturedQuad(poseStack, width, height, x, y, texture, 0.0F, RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL, true);
  }

  private void addTexturedQuad(
    PoseStack poseStack,
    float width,
    float height,
    float x,
    float y,
    RendererAssets.TextureImage texture,
    float depthBias,
    RenderMaterial.DepthTest depthTest,
    boolean depthWrite
  ) {
    builder.add(withDepthTest(BillboardGeometry.textQuad(poseStack, width, height, x, y, texture, depthBias), depthTest, depthWrite));
  }

  private float textDepthBias(Font.DisplayMode displayMode) {
    return displayMode == Font.DisplayMode.POLYGON_OFFSET ? -1.0F : 0.0F;
  }

  private RenderMaterial.DepthTest textDepthTest(Font.DisplayMode displayMode) {
    return displayMode == Font.DisplayMode.SEE_THROUGH ? RenderMaterial.DepthTest.ALWAYS_PASS : RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL;
  }

  private boolean textDepthWrite(Font.DisplayMode displayMode) {
    return displayMode != Font.DisplayMode.SEE_THROUGH;
  }

  private void addFace(
    Vector3f[] vertices,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    int emission,
    boolean doubleSided,
    float[] uv
  ) {
    var face = RendererAssets.GeometryFace.of(vertices, uv, texture, alphaMode, null, -1, emission, true);
    builder.add(WorldMeshCollector.toRenderQuad(
      face,
      0.0,
      0.0,
      0.0,
      color,
      doubleSided,
      0.0F,
      RenderMaterial.defaultAlphaCutoutThreshold(alphaMode)
    ));
  }

  private void addFace(
    Vector3f[] vertices,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    int emission,
    boolean doubleSided,
    float[] uv,
    int alphaCutoutThreshold
  ) {
    var face = RendererAssets.GeometryFace.of(vertices, uv, texture, alphaMode, null, -1, emission, true);
    builder.add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, color, doubleSided, 0.0F, alphaCutoutThreshold));
  }

  private Vector3f transform(PoseStack poseStack, float x, float y, float z) {
    return poseStack.last().pose().transformPosition(new Vector3f(x, y, z));
  }

  private Vector3f rotateParticleVertex(Quaternionf rotation, float x, float y, float z, float size, float px, float py) {
    return new Vector3f(px, py, 0.0F).rotate(rotation).mul(size).add(x, y, z);
  }

  private float xOffset(Component text) {
    return -font.width(text) * 0.5F;
  }

  private RendererAssets.AlphaMode alphaMode(@Nullable RenderType renderType, RendererAssets.TextureImage texture) {
    if (renderType != null && renderType.hasBlending()) {
      return RendererAssets.AlphaMode.TRANSLUCENT;
    }
    return texture.hasAlpha() ? RendererAssets.AlphaMode.CUTOUT : RendererAssets.AlphaMode.OPAQUE;
  }

  private RenderQuad withDepthState(RenderQuad quad, @Nullable RenderType renderType) {
    if (renderType == null) {
      return quad;
    }

    return withMaterial(quad, quad.material().withDepthState(renderType.pipeline().getDepthStencilState()));
  }

  private RenderQuad withDepthTest(RenderQuad quad, RenderMaterial.DepthTest depthTest, boolean depthWrite) {
    return withMaterial(quad, quad.material().withDepthTest(depthTest, depthWrite));
  }

  private RenderQuad withMaterial(RenderQuad quad, RenderMaterial material) {
    return new RenderQuad(quad.v0(), quad.v1(), quad.v2(), quad.v3(), material);
  }

  private int alphaCutoutThreshold(@Nullable RenderType renderType, RendererAssets.AlphaMode alphaMode) {
    if (renderType == null) {
      return RenderMaterial.defaultAlphaCutoutThreshold(alphaMode);
    }

    var alphaCutout = renderType.pipeline().getShaderDefines().values().get("ALPHA_CUTOUT");
    if (alphaCutout == null) {
      return RenderMaterial.defaultAlphaCutoutThreshold(alphaMode);
    }

    try {
      return Math.clamp((int) Math.ceil(Float.parseFloat(alphaCutout) * 255.0F), 0, 255);
    } catch (NumberFormatException _) {
      return RenderMaterial.defaultAlphaCutoutThreshold(alphaMode);
    }
  }

  private int modulateColor(int left, int right) {
    var a = ((left >>> 24) & 0xFF) * ((right >>> 24) & 0xFF) / 255;
    var r = ((left >>> 16) & 0xFF) * ((right >>> 16) & 0xFF) / 255;
    var g = ((left >>> 8) & 0xFF) * ((right >>> 8) & 0xFF) / 255;
    var b = (left & 0xFF) * (right & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private String plainText(FormattedCharSequence sequence) {
    var builder = new StringBuilder();
    sequence.accept((_, _, codePoint) -> {
      builder.appendCodePoint(codePoint);
      return true;
    });
    return builder.toString();
  }

  private RendererAssets.TextureImage textureFromRenderType(@Nullable RenderType renderType) {
    if (renderType == null) {
      return WHITE_TEXTURE;
    }

    RenderSetup state = renderType.state;
    if (state == null || state.textures == null || state.textures.isEmpty()) {
      return WHITE_TEXTURE;
    }

    for (var binding : state.textures.values()) {
      var location = binding.location;
      if (location == null) {
        continue;
      }
      return assets.renderTexture(location);
    }

    return WHITE_TEXTURE;
  }

  private static RendererAssets.TextureImage createSolidTexture(int argb) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, argb);
    return RendererAssets.TextureImage.from(image, null);
  }

  private final class CapturingVertexConsumer implements VertexConsumer {
    private final Matrix4fc pose;
    private final VertexFormat.Mode mode;
    private final RendererAssets.TextureImage texture;
    private final RendererAssets.AlphaMode alphaMode;
    private final int alphaCutoutThreshold;
    @Nullable
    private final DepthStencilState depthStencilState;
    private final ArrayList<CapturedVertex> vertices = new ArrayList<>();
    private float lineWidth = 1.0F;
    private CapturedVertex current;

    private CapturingVertexConsumer(
      Matrix4fc pose,
      VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      int alphaCutoutThreshold,
      @Nullable DepthStencilState depthStencilState
    ) {
      this.pose = pose;
      this.mode = mode;
      this.texture = texture;
      this.alphaMode = alphaMode;
      this.alphaCutoutThreshold = alphaCutoutThreshold;
      this.depthStencilState = depthStencilState;
    }

    void flush() {
      switch (mode) {
        case QUADS -> {
          for (var i = 0; i + 3 < vertices.size(); i += 4) {
            emitQuad(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2), vertices.get(i + 3));
          }
        }
        case TRIANGLES -> {
          for (var i = 0; i + 2 < vertices.size(); i += 3) {
            emitTriangle(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
          }
        }
        case TRIANGLE_STRIP -> {
          for (var i = 0; i + 2 < vertices.size(); i++) {
            if ((i & 1) == 0) {
              emitTriangle(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
            } else {
              emitTriangle(vertices.get(i + 1), vertices.get(i), vertices.get(i + 2));
            }
          }
        }
        case TRIANGLE_FAN -> {
          for (var i = 1; i + 1 < vertices.size(); i++) {
            emitTriangle(vertices.getFirst(), vertices.get(i), vertices.get(i + 1));
          }
        }
        case LINES, DEBUG_LINES, DEBUG_LINE_STRIP -> {
          var stride = mode == VertexFormat.Mode.DEBUG_LINE_STRIP ? 1 : 2;
          for (var i = 0; i + 1 < vertices.size(); i += stride) {
            emitLine(vertices.get(i), vertices.get(i + 1));
          }
        }
        case POINTS -> {
          for (var vertex : vertices) {
            emitPoint(vertex);
          }
        }
      }
    }

    private void emitQuad(CapturedVertex a, CapturedVertex b, CapturedVertex c, CapturedVertex d) {
      addCapturedFace(
        new Vector3f[]{a.position(), b.position(), c.position(), d.position()},
        new int[]{a.color(), b.color(), c.color(), d.color()},
        new float[]{a.u(), a.v(), b.u(), b.v(), c.u(), c.v(), d.u(), d.v()}
      );
    }

    private void emitTriangle(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
      addCapturedFace(
        new Vector3f[]{a.position(), b.position(), c.position(), c.position()},
        new int[]{a.color(), b.color(), c.color(), c.color()},
        new float[]{a.u(), a.v(), b.u(), b.v(), c.u(), c.v(), c.u(), c.v()}
      );
    }

    private void emitLine(CapturedVertex a, CapturedVertex b) {
      var direction = new Vector3f(b.position()).sub(a.position());
      if (direction.lengthSquared() < 1.0E-6F) {
        return;
      }
      direction.normalize();
      var cameraForward = new Vector3f((float) ctx.camera().forwardX(), (float) ctx.camera().forwardY(), (float) ctx.camera().forwardZ());
      var side = new Vector3f(direction).cross(cameraForward);
      if (side.lengthSquared() < 1.0E-6F) {
        side = new Vector3f(direction).cross((float) ctx.camera().upX(), (float) ctx.camera().upY(), (float) ctx.camera().upZ());
      }
      if (side.lengthSquared() < 1.0E-6F) {
        side.set((float) ctx.camera().screenLeftX(), (float) ctx.camera().screenLeftY(), (float) ctx.camera().screenLeftZ());
      }
      side.normalize(Math.max(0.005F, lineWidth * 0.005F));
      addCapturedFace(
        new Vector3f[]{
          new Vector3f(a.position()).sub(side),
          new Vector3f(a.position()).add(side),
          new Vector3f(b.position()).add(side),
          new Vector3f(b.position()).sub(side)
        },
        new int[]{a.color(), a.color(), b.color(), b.color()},
        new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F}
      );
    }

    private void emitPoint(CapturedVertex vertex) {
      var size = 0.03F;
      var right = new Vector3f((float) ctx.camera().screenLeftX(), (float) ctx.camera().screenLeftY(), (float) ctx.camera().screenLeftZ()).normalize(size);
      var up = new Vector3f((float) ctx.camera().upX(), (float) ctx.camera().upY(), (float) ctx.camera().upZ()).normalize(size);
      addCapturedFace(
        new Vector3f[]{
          new Vector3f(vertex.position()).sub(right).sub(up),
          new Vector3f(vertex.position()).sub(right).add(up),
          new Vector3f(vertex.position()).add(right).add(up),
          new Vector3f(vertex.position()).add(right).sub(up)
        },
        new int[]{vertex.color(), vertex.color(), vertex.color(), vertex.color()},
        new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F}
      );
    }

    private void addCapturedFace(Vector3f[] positions, int[] colors, float[] uv) {
      var material = RenderMaterial.create(texture, alphaMode, 0xFFFFFFFF, true, 0.0F, alphaCutoutThreshold).withDepthState(depthStencilState);
      builder.add(new RenderQuad(
        renderVertex(positions[0], uv[0], uv[1], colors[0]),
        renderVertex(positions[1], uv[2], uv[3], colors[1]),
        renderVertex(positions[2], uv[4], uv[5], colors[2]),
        renderVertex(positions[3], uv[6], uv[7], colors[3]),
        material
      ));
    }

    private RenderVertex renderVertex(Vector3f position, float u, float v, int color) {
      return new RenderVertex(position.x(), position.y(), position.z(), u, v, color);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      current = new CapturedVertex(pose.transformPosition(new Vector3f(x, y, z)), 0xFFFFFFFF, 0.0F, 0.0F);
      vertices.add(current);
      return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      return setColor((alpha << 24) | (red << 16) | (green << 8) | blue);
    }

    @Override
    public VertexConsumer setColor(int color) {
      if (current != null) {
        current = current.withColor(color);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      if (current != null) {
        current = current.withUv(u, v);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) {
      this.lineWidth = Math.max(1.0F, width);
      return this;
    }
  }

  private final class CapturingBufferSource implements MultiBufferSource {
    private final LinkedHashMap<RenderType, CapturingVertexConsumer> consumers = new LinkedHashMap<>();

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
      return consumers.computeIfAbsent(renderType, type -> {
        var texture = textureFromRenderType(type);
        var alphaMode = alphaMode(type, texture);
        return new CapturingVertexConsumer(
          new Matrix4f(),
          type.mode(),
          texture,
          alphaMode,
          alphaCutoutThreshold(type, alphaMode),
          type.pipeline().getDepthStencilState()
        );
      });
    }

    private void flush() {
      consumers.values().forEach(CapturingVertexConsumer::flush);
      consumers.clear();
    }
  }

  private final class FluidOutput implements FluidRenderer.Output {
    private final LinkedHashMap<ChunkSectionLayer, CapturingVertexConsumer> consumers = new LinkedHashMap<>();
    private final RendererAssets.TextureImage texture = assets.textureAtlas(TextureAtlas.LOCATION_BLOCKS);
    private final Matrix4f sectionOrigin;

    private FluidOutput(BlockPos blockPos) {
      this.sectionOrigin = new Matrix4f().translation(
        SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(blockPos.getX())),
        SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(blockPos.getY())),
        SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(blockPos.getZ()))
      );
    }

    @Override
    public VertexConsumer getBuilder(ChunkSectionLayer layer) {
      return consumers.computeIfAbsent(
        layer,
        currentLayer -> new CapturingVertexConsumer(
          sectionOrigin,
          VertexFormat.Mode.QUADS,
          texture,
          currentLayer.translucent() ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.OPAQUE,
          currentLayer.translucent() ? RenderMaterial.defaultAlphaCutoutThreshold(RendererAssets.AlphaMode.TRANSLUCENT) : 0,
          new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, !currentLayer.translucent())
        )
      );
    }

    private void flush() {
      consumers.values().forEach(CapturingVertexConsumer::flush);
      consumers.clear();
    }
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
