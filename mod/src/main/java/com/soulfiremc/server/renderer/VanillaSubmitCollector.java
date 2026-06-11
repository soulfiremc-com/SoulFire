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
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.MatrixUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.CardinalLighting;
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
import org.joml.Vector4f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

final class VanillaSubmitCollector implements SubmitNodeCollector, OrderedSubmitNodeCollector {
  private static final Identifier ENCHANTED_GLINT_ITEM = Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png");
  private static final Identifier SHADOW_TEXTURE = Identifier.withDefaultNamespace("textures/misc/shadow.png");
  private static final int GLINT_TINT = 0x99A070FF;
  private static final int LEASH_RENDER_STEPS = 24;
  private static final float LEASH_WIDTH = 0.05F;
  private static final float LINE_SHADER_VIEW_SCALE = 1.0F - 1.0F / 256.0F;
  private static final Vector3f LEVEL_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
  private static final Vector3f LEVEL_LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
  private static final Vector3f NETHER_LEVEL_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
  private static final Vector3f NETHER_LEVEL_LIGHT_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();
  private static final Direction[] DIRECTIONS = Direction.values();
  private static final RendererAssets.TextureImage WHITE_TEXTURE = createSolidTexture(0xFFFFFFFF);
  private final RenderContext ctx;
  private final RendererAssets assets;
  private final NavigableMap<Integer, FeatureBuckets> bucketsByOrder;
  private final SortGroupRegistry sortGroups;
  private final FeatureBuckets buckets;
  private SceneData.Builder activeBuilder;

  private VanillaSubmitCollector(RenderContext ctx) {
    this(ctx, new TreeMap<>(), new SortGroupRegistry(), 0);
  }

  private VanillaSubmitCollector(RenderContext ctx, NavigableMap<Integer, FeatureBuckets> bucketsByOrder, SortGroupRegistry sortGroups, int order) {
    this.ctx = ctx;
    this.assets = RendererAssets.instance();
    this.bucketsByOrder = bucketsByOrder;
    this.sortGroups = sortGroups;
    this.buckets = bucketsByOrder.computeIfAbsent(order, _ -> new FeatureBuckets());
    this.activeBuilder = this.buckets.builder(FeatureStage.SOLID_CUSTOM);
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

  static boolean shouldRenderEntity(RenderContext ctx, Entity entity) {
    try {
      var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
      return dispatcher.shouldRender(entity, createFrustum(ctx), ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    } catch (Throwable _) {
      var bounds = entity.getBoundingBox();
      return ctx.camera().isVisibleAabb(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }
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
    poseStack.translate(state.x + renderOffset.x(), state.y + renderOffset.y(), state.z + renderOffset.z());
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
    return collector.buildScene();
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
    return collector.buildScene();
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
    minecraft.particleEngine.extract(particlesState, createFrustum(ctx).offset(-3.0F), particleCamera, 1.0F);
    if (particlesState.particles.isEmpty()) {
      return SceneData.EMPTY;
    }

    var collector = new VanillaSubmitCollector(ctx);
    var cameraState = collector.cameraRenderState();
    for (var particle : particlesState.particles) {
      particle.submit(collector, cameraState);
    }
    return collector.buildScene();
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
    return collector.buildScene();
  }

  private SceneData buildScene() {
    for (var orderedBuckets : bucketsByOrder.values()) {
      orderedBuckets.flushNameTags(this);
      orderedBuckets.flushSortedModelDraws();
    }

    var sceneData = SceneData.EMPTY;
    for (var orderedBuckets : bucketsByOrder.values()) {
      sceneData = sceneData.merge(orderedBuckets.build());
    }
    return sceneData;
  }

  private SceneData.Builder builder() {
    return activeBuilder;
  }

  private void withStage(FeatureStage stage, Runnable action) {
    withBucketStage(buckets, stage, action);
  }

  private void withBucketStage(FeatureBuckets target, FeatureStage stage, Runnable action) {
    var previous = activeBuilder;
    activeBuilder = target.builder(stage);
    try {
      action.run();
    } finally {
      activeBuilder = previous;
    }
  }

  private SceneData captureScene(Runnable action) {
    var previous = activeBuilder;
    var captureBuilder = SceneData.builder();
    activeBuilder = captureBuilder;
    try {
      action.run();
    } finally {
      activeBuilder = previous;
    }
    return captureBuilder.build();
  }

  private double poseOriginDistanceSq(Matrix4fc pose) {
    var position = pose.transformPosition(new Vector3f());
    var dx = position.x() - ctx.camera().eyeX();
    var dy = position.y() - ctx.camera().eyeY();
    var dz = position.z() - ctx.camera().eyeZ();
    return dx * dx + dy * dy + dz * dz;
  }

  private FeatureStage stageForRenderType(@Nullable RenderType renderType, FeatureStage solidStage, FeatureStage translucentStage) {
    return renderType != null && renderType.hasBlending() ? translucentStage : solidStage;
  }

  private void addRenderTypeQuad(RenderQuad quad, @Nullable RenderType renderType) {
    if (renderType != null && renderType.outputTarget() == OutputTarget.WEATHER_TARGET) {
      builder().addWeather(quad);
    } else {
      builder().add(quad);
    }
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
    return buckets == bucketsByOrder.get(order) ? this : new VanillaSubmitCollector(ctx, bucketsByOrder, sortGroups, order);
  }

  @Override
  public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    withStage(FeatureStage.TRANSLUCENT_SHADOW, () -> {
      if (radius <= 0.0F || pieces.isEmpty()) {
        return;
      }

      var renderType = RenderTypes.entityShadow(SHADOW_TEXTURE);
      var texture = textureFromRenderType(renderType);
      var alphaMode = RendererAssets.AlphaMode.TRANSLUCENT;
      var consumer = new CapturingVertexConsumer(
        poseStack.last().pose(),
        renderType.mode(),
        texture,
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null
      );
      for (var piece : pieces) {
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
        var color = ARGB.white(piece.alpha());
        shadowVertex(consumer, color, x0, y, z0, u0, v0);
        shadowVertex(consumer, color, x0, y, z1, u0, v1);
        shadowVertex(consumer, color, x1, y, z1, u1, v1);
        shadowVertex(consumer, color, x1, y, z0, u1, v0);
      }
      consumer.flush();
    });
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
    try {
      poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
      poseStack.mulPose(cameraRenderState.orientation);
      poseStack.scale(0.025F, -0.025F, 0.025F);
      var pose = new Matrix4f(poseStack.last().pose());
      var x = xOffset(name);
      var backgroundColor = nameTagBackgroundColor();
      if (seeThrough) {
        buckets.nameTagSeeThrough.add(new NameTagDraw(
          pose,
          x,
          offset,
          name,
          Font.DisplayMode.SEE_THROUGH,
          lightCoords,
          0x80FFFFFF,
          backgroundColor,
          distanceToCameraSq
        ));
        buckets.nameTagNormal.add(new NameTagDraw(
          pose,
          x,
          offset,
          name,
          Font.DisplayMode.NORMAL,
          LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2),
          0xFFFFFFFF,
          0,
          distanceToCameraSq
        ));
      } else {
        buckets.nameTagNormal.add(new NameTagDraw(
          pose,
          x,
          offset,
          name,
          Font.DisplayMode.NORMAL,
          lightCoords,
          0x80FFFFFF,
          backgroundColor,
          distanceToCameraSq
        ));
      }
    } finally {
      poseStack.popPose();
    }
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
    withStage(FeatureStage.TRANSLUCENT_TEXT, () -> {
      RenderDebugTrace.current().textSubmission(
        "formatted",
        formattedText(text),
        shadow,
        displayMode.name(),
        light,
        color,
        backgroundColor,
        outlineColor
      );
      var bufferSource = new CapturingBufferSource();
      if (outlineColor != 0) {
        font().drawInBatch8xOutline(text, x, y, color, outlineColor, poseStack.last().pose(), bufferSource, light);
      } else {
        font().drawInBatch(text, x, y, color, shadow, poseStack.last().pose(), bufferSource, displayMode, backgroundColor, light);
      }
      bufferSource.flush();
    });
  }

  @Override
  public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
    withStage(FeatureStage.SOLID_FLAME, () -> {
      var scale = entityRenderState.boundingBoxWidth * 1.4F;
      if (scale <= 1.0E-6F) {
        return;
      }

      var fire0 = Minecraft.getInstance().getAtlasManager().get(ModelBakery.FIRE_0);
      var fire1 = Minecraft.getInstance().getAtlasManager().get(ModelBakery.FIRE_1);
      var pose = poseStack.last().copy();
      pose.scale(scale, scale, scale);
      var halfWidth = 0.5F;
      var height = entityRenderState.boundingBoxHeight / scale;
      var yOffset = 0.0F;
      pose.rotate(rotation);
      pose.translate(0.0F, 0.0F, 0.3F - (int) height * 0.02F);
      var zOffset = 0.0F;
      var renderType = Sheets.cutoutBlockSheet();
      var alphaMode = RendererAssets.AlphaMode.CUTOUT;
      var consumer = new CapturingVertexConsumer(
        pose.pose(),
        renderType.mode(),
        assets.textureAtlas(TextureAtlas.LOCATION_BLOCKS),
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null
      );
      var lightCoords = LightCoordsUtil.withBlock(entityRenderState.lightCoords, 15);

      for (var layer = 0; height > 0.0F; layer++) {
        var sprite = (layer & 1) == 0 ? fire0 : fire1;
        var u0 = sprite.getU0();
        var v0 = sprite.getV0();
        var u1 = sprite.getU1();
        var v1 = sprite.getV1();
        if (layer / 2 % 2 == 0) {
          var tmp = u1;
          u1 = u0;
          u0 = tmp;
        }

        fireVertex(consumer, -halfWidth, -yOffset, zOffset, u1, v1, lightCoords);
        fireVertex(consumer, halfWidth, -yOffset, zOffset, u0, v1, lightCoords);
        fireVertex(consumer, halfWidth, 1.4F - yOffset, zOffset, u0, v0, lightCoords);
        fireVertex(consumer, -halfWidth, 1.4F - yOffset, zOffset, u1, v0, lightCoords);
        height -= 0.45F;
        yOffset -= 0.45F;
        halfWidth *= 0.9F;
        zOffset -= 0.03F;
      }
      consumer.flush();
    });
  }

  @Override
  public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    withStage(FeatureStage.SOLID_LEASH, () -> {
      var start = leashState.start;
      var end = leashState.end;
      if (start == null || end == null) {
        return;
      }

      var dx = (float) (end.x - start.x);
      var dy = (float) (end.y - start.y);
      var dz = (float) (end.z - start.z);
      if (dx * dx + dy * dy + dz * dz < 1.0E-6F) {
        return;
      }

      var horizontalLengthSquared = dx * dx + dz * dz;
      var offsetFactor = horizontalLengthSquared > 1.0E-6F ? Mth.invSqrt(horizontalLengthSquared) * LEASH_WIDTH / 2.0F : 0.0F;
      var dxOffset = horizontalLengthSquared > 1.0E-6F ? dz * offsetFactor : LEASH_WIDTH / 2.0F;
      var dzOffset = horizontalLengthSquared > 1.0E-6F ? dx * offsetFactor : 0.0F;
      var pose = new Matrix4f(poseStack.last().pose());
      pose.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);
      var renderType = RenderTypes.leash();
      var alphaMode = RendererAssets.AlphaMode.OPAQUE;
      var consumer = new CapturingVertexConsumer(
        pose,
        renderType.mode(),
        textureFromRenderType(renderType),
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null
      );

      for (var step = 0; step <= LEASH_RENDER_STEPS; step++) {
        leashVertexPair(consumer, dx, dy, dz, LEASH_WIDTH, dxOffset, dzOffset, step, false, leashState);
      }
      for (var step = LEASH_RENDER_STEPS; step >= 0; step--) {
        leashVertexPair(consumer, dx, dy, dz, 0.0F, dxOffset, dzOffset, step, true, leashState);
      }
      consumer.flush();
    });
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
    int outlineColor,
    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
  ) {
    if (renderType.hasBlending()) {
      var scene = captureScene(() -> captureModelSubmit(
        model,
        state,
        poseStack,
        renderType,
        light,
        overlay,
        color,
        sprite,
        outlineColor,
        crumblingOverlay
      ));
      if (scene.totalQuadCount() > 0) {
        buckets.translucentModelDraws.add(new SortedScene(poseOriginDistanceSq(poseStack.last().pose()), scene));
      }
      return;
    }

    withStage(FeatureStage.SOLID_MODEL, () -> captureModelSubmit(
      model,
      state,
      poseStack,
      renderType,
      light,
      overlay,
      color,
      sprite,
      outlineColor,
      crumblingOverlay
    ));
  }

  private <S> void captureModelSubmit(
    Model<? super S> model,
    S state,
    PoseStack poseStack,
    RenderType renderType,
    int light,
    int overlay,
    int color,
    TextureAtlasSprite sprite,
    int outlineColor,
    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
  ) {
    var texture = textureFromRenderType(renderType);
    var alphaMode = alphaMode(renderType, texture, color);
    model.setupAnim(state);
    Consumer<VertexConsumer> renderer = consumer -> model.renderToBuffer(poseStack, consumer, light, overlay, color);
    captureRenderedGeometry(renderType, texture, alphaMode, alphaCutoutThreshold(renderType, alphaMode), sprite, null, renderer);
    captureOutlineGeometry(renderType, texture, outlineColor, sprite, renderer);
    captureCrumblingGeometry(renderType.affectsCrumbling(), crumblingOverlay, renderer);
  }

  @Override
  public void submitModelPart(
    ModelPart modelPart,
    PoseStack poseStack,
    RenderType renderType,
    int light,
    int overlay,
    TextureAtlasSprite sprite,
    boolean sheeted,
    boolean hasFoil,
    int color,
    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
    int outlineColor
  ) {
    withStage(stageForRenderType(renderType, FeatureStage.SOLID_MODEL_PART, FeatureStage.TRANSLUCENT_MODEL_PART), () -> {
      var texture = textureFromRenderType(renderType);
      var alphaMode = alphaMode(renderType, texture, color);
      Consumer<VertexConsumer> renderer = consumer -> modelPart.render(poseStack, consumer, light, overlay, color);
      captureRenderedGeometry(renderType, texture, alphaMode, alphaCutoutThreshold(renderType, alphaMode), sprite, null, renderer);
      captureOutlineGeometry(renderType, texture, outlineColor, sprite, renderer);
      captureCrumblingGeometry(true, crumblingOverlay, renderer);
      if (hasFoil) {
        var glintTexture = glintTexture();
        var glintRenderType = foilRenderType(renderType, sheeted);
        captureRenderedGeometry(
          glintRenderType,
          glintTexture,
          RendererAssets.AlphaMode.TRANSLUCENT,
          alphaCutoutThreshold(glintRenderType, RendererAssets.AlphaMode.TRANSLUCENT),
          null,
          glintMaterial(glintTexture, glintRenderType),
          consumer -> modelPart.render(poseStack, consumer, LightCoordsUtil.FULL_BRIGHT, overlay, 0xFFFFFFFF)
        );
      }
    });
  }

  @Override
  public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {
    var minecraft = Minecraft.getInstance();
    var blockState = movingBlockRenderState.blockState;
    var model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
    var optionsState = minecraft.gameRenderer.getGameRenderState().optionsRenderState;
    var stage = model.hasMaterialFlag(1) ? FeatureStage.TRANSLUCENT_BLOCK : FeatureStage.SOLID_BLOCK;
    withStage(stage, () -> {
      var basePose = poseStack.last().copy();
      var consumers = new EnumMap<ChunkSectionLayer, CapturingVertexConsumer>(ChunkSectionLayer.class);
      BlockQuadOutput output = (x, y, z, quad, instance) -> {
        var materialInfo = quad.materialInfo();
        var layer = materialInfo != null ? materialInfo.layer() : ChunkSectionLayer.SOLID;
        putMovingBlockQuad(basePose, consumers, x, y, z, quad, instance, layer);
      };
      BlockQuadOutput solidOutput = (x, y, z, quad, instance) -> putMovingBlockQuad(basePose, consumers, x, y, z, quad, instance, ChunkSectionLayer.SOLID);
      var blockOutput = ModelBlockRenderer.forceOpaque(optionsState.cutoutLeaves, blockState) ? solidOutput : output;
      var blockRenderer = new ModelBlockRenderer(optionsState.ambientOcclusion, false, minecraft.getBlockColors());
      var seed = blockState.getSeed(movingBlockRenderState.randomSeedPos);
      blockRenderer.tesselateBlock(
        blockOutput,
        0.0F,
        0.0F,
        0.0F,
        movingBlockRenderState,
        movingBlockRenderState.blockPos,
        blockState,
        model,
        seed
      );
      consumers.values().forEach(CapturingVertexConsumer::flush);
    });
  }

  @Override
  public void submitBlockModel(
    PoseStack poseStack,
    RenderType renderType,
    List<BlockStateModelPart> parts,
    int[] tints,
    int light,
    int overlay,
    int outlineColor
  ) {
    withStage(stageForRenderType(renderType, FeatureStage.SOLID_BLOCK, FeatureStage.TRANSLUCENT_BLOCK), () -> {
      for (var part : parts) {
        for (var direction : Direction.values()) {
          for (var quad : part.getQuads(direction)) {
            appendBakedQuad(quad, poseStack.last().pose(), renderType, 0xFFFFFFFF, tints, light, overlay);
            appendBakedQuadOutline(quad, poseStack.last().pose(), renderType, outlineColor, overlay);
          }
        }
        for (var quad : part.getQuads(null)) {
          appendBakedQuad(quad, poseStack.last().pose(), renderType, 0xFFFFFFFF, tints, light, overlay);
          appendBakedQuadOutline(quad, poseStack.last().pose(), renderType, outlineColor, overlay);
        }
      }
    });
  }

  @Override
  public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel blockStateModel, long seed, int progress) {
    withStage(FeatureStage.TRANSLUCENT_BLOCK, () -> {
      if (progress < 0 || progress >= ModelBakery.DESTROY_TYPES.size()) {
        return;
      }

      var parts = new ArrayList<BlockStateModelPart>();
      blockStateModel.collectParts(RandomSource.create(seed), parts);
      if (parts.isEmpty()) {
        return;
      }

      var renderType = ModelBakery.DESTROY_TYPES.get(progress);
      var texture = textureFromRenderType(renderType);
      var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
      var consumer = new CapturingVertexConsumer(
        new Matrix4f(),
        renderType.mode(),
        texture,
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null
      );
      var decalConsumer = new SheetedDecalTextureGenerator(consumer, poseStack.last(), 1.0F);
      var instance = new QuadInstance();
      instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
      instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);

      for (var part : parts) {
        putPartQuads(poseStack.last(), part, instance, decalConsumer);
      }
      consumer.flush();
    });
  }

  @Override
  public void submitItem(
    PoseStack poseStack,
    ItemDisplayContext displayContext,
    int light,
    int overlay,
    int outlineColor,
    int[] tints,
    List<BakedQuad> quads,
    ItemStackRenderState.FoilType foilType
  ) {
    var stage = itemHasTranslucency(quads) ? FeatureStage.TRANSLUCENT_ITEM : FeatureStage.SOLID_ITEM;
    withStage(stage, () -> {
      for (var quad : quads) {
        var itemRenderType = quad.materialInfo() != null ? quad.materialInfo().itemRenderType() : null;
        appendBakedQuad(quad, poseStack.last().pose(), itemRenderType, 0xFFFFFFFF, tints, light, overlay);
        appendBakedQuadOutline(quad, poseStack.last().pose(), itemRenderType, outlineColor, overlay);
        if (foilType != ItemStackRenderState.FoilType.NONE) {
          appendBakedQuadGlint(quad, poseStack.last(), itemRenderType, displayContext, foilType);
        }
      }
    });
  }

  @Override
  public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
    withStage(stageForRenderType(renderType, FeatureStage.SOLID_CUSTOM, FeatureStage.TRANSLUCENT_CUSTOM), () -> {
      var texture = textureFromRenderType(renderType);
      var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
      var submittedPose = poseStack.last().copy();
      var consumer = new CapturingVertexConsumer(
        submittedPose,
        renderType.mode(),
        texture,
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null,
        null,
        true
      );
      renderer.render(submittedPose, consumer);
      consumer.flush();
    });
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
      var stage = layer.translucent() ? FeatureStage.TRANSLUCENT_PARTICLE : FeatureStage.SOLID_PARTICLE;
      withStage(stage, () -> {
        storage.forEachParticle((x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, lightCoords) -> {
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
          var material = RenderMaterial
            .create(
              texture,
              alphaMode,
              modulateColor(color, lightColor(lightCoords, 0, null)),
              true,
              0.0F,
              RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD
            )
            .withPipelineState(layer.pipeline());
          var quad = face(vertices, new float[]{u1, v1, u1, v0, u0, v0, u0, v1}, material);
          if (layer.translucent()) {
            builder().addTranslucentParticle(quad);
          } else {
            builder().add(quad);
          }
        });
      });
    }
  }

  private static void shadowVertex(VertexConsumer consumer, int color, float x, float y, float z, float u, float v) {
    consumer
      .addVertex(x, y, z)
      .setColor(color)
      .setUv(u, v)
      .setOverlay(OverlayTexture.NO_OVERLAY)
      .setLight(LightCoordsUtil.FULL_BRIGHT)
      .setNormal(0.0F, 1.0F, 0.0F);
  }

  private static void fireVertex(VertexConsumer consumer, float x, float y, float z, float u, float v, int lightCoords) {
    consumer
      .addVertex(x, y, z)
      .setColor(0xFFFFFFFF)
      .setUv(u, v)
      .setOverlay(OverlayTexture.NO_OVERLAY)
      .setLight(lightCoords)
      .setNormal(0.0F, 1.0F, 0.0F);
  }

  private static void leashVertexPair(
    VertexConsumer consumer,
    float dx,
    float dy,
    float dz,
    float fudge,
    float dxOffset,
    float dzOffset,
    int step,
    boolean backwards,
    EntityRenderState.LeashState state
  ) {
    var progress = step / (float) LEASH_RENDER_STEPS;
    var block = (int) Mth.lerp(progress, (float) state.startBlockLight, (float) state.endBlockLight);
    var sky = (int) Mth.lerp(progress, (float) state.startSkyLight, (float) state.endSkyLight);
    var lightCoords = LightCoordsUtil.pack(block, sky);
    var colorModifier = step % 2 == (backwards ? 1 : 0) ? 0.7F : 1.0F;
    var red = 0.5F * colorModifier;
    var green = 0.4F * colorModifier;
    var blue = 0.3F * colorModifier;
    var x = dx * progress;
    var y = state.slack
      ? dy > 0.0F ? dy * progress * progress : dy - dy * (1.0F - progress) * (1.0F - progress)
      : dy * progress;
    var z = dz * progress;
    consumer.addVertex(x - dxOffset, y + fudge, z + dzOffset).setColor(red, green, blue, 1.0F).setLight(lightCoords);
    consumer.addVertex(x + dxOffset, y + LEASH_WIDTH - fudge, z - dzOffset).setColor(red, green, blue, 1.0F).setLight(lightCoords);
  }

  private static void putPartQuads(PoseStack.Pose pose, BlockStateModelPart part, QuadInstance instance, VertexConsumer consumer) {
    for (var direction : DIRECTIONS) {
      for (var quad : part.getQuads(direction)) {
        putQuad(pose, quad, instance, consumer);
      }
    }

    for (var quad : part.getQuads(null)) {
      putQuad(pose, quad, instance, consumer);
    }
  }

  private void putMovingBlockQuad(
    PoseStack.Pose basePose,
    EnumMap<ChunkSectionLayer, CapturingVertexConsumer> consumers,
    float x,
    float y,
    float z,
    BakedQuad quad,
    QuadInstance instance,
    ChunkSectionLayer layer
  ) {
    var pose = basePose.copy();
    pose.translate(x, y, z);
    putQuad(pose, quad, instance, movingBlockConsumer(consumers, layer));
  }

  private CapturingVertexConsumer movingBlockConsumer(EnumMap<ChunkSectionLayer, CapturingVertexConsumer> consumers, ChunkSectionLayer layer) {
    return consumers.computeIfAbsent(layer, currentLayer -> {
      var renderType = movingBlockRenderType(currentLayer);
      var texture = assets.textureAtlas(TextureAtlas.LOCATION_BLOCKS);
      var alphaMode = switch (currentLayer) {
        case SOLID -> RendererAssets.AlphaMode.OPAQUE;
        case CUTOUT -> RendererAssets.AlphaMode.CUTOUT;
        case TRANSLUCENT -> RendererAssets.AlphaMode.TRANSLUCENT;
      };
      return new CapturingVertexConsumer(
        new Matrix4f(),
        renderType.mode(),
        texture,
        alphaMode,
        alphaCutoutThreshold(renderType, alphaMode),
        renderType,
        null
      );
    });
  }

  private static RenderType movingBlockRenderType(ChunkSectionLayer layer) {
    return switch (layer) {
      case SOLID -> RenderTypes.solidMovingBlock();
      case CUTOUT -> RenderTypes.cutoutMovingBlock();
      case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
    };
  }

  private static void putQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, VertexConsumer consumer) {
    if (quad == null || quad.materialInfo() == null) {
      return;
    }

    instance.setColor(0xFFFFFFFF);
    consumer.putBakedQuad(pose, quad, instance);
  }

  private void captureRenderedGeometry(
    RenderType renderType,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int alphaCutoutThreshold,
    @Nullable TextureAtlasSprite sprite,
    @Nullable RenderMaterial materialOverride,
    Consumer<VertexConsumer> renderer
  ) {
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      renderType.mode(),
      texture,
      alphaMode,
      alphaCutoutThreshold,
      renderType,
      null,
      materialOverride
    );
    renderer.accept(wrapSprite(consumer, sprite));
    consumer.flush();
  }

  private void captureOutlineGeometry(
    RenderType renderType,
    RendererAssets.TextureImage texture,
    int outlineColor,
    @Nullable TextureAtlasSprite sprite,
    Consumer<VertexConsumer> renderer
  ) {
    if (outlineColor == 0 || (renderType.outline().isEmpty() && !renderType.isOutline())) {
      return;
    }

    var outlineRenderType = renderType.isOutline() ? renderType : renderType.outline().orElse(renderType);
    var alphaMode = alphaMode(outlineRenderType, texture, outlineColor);
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      outlineRenderType.mode(),
      texture,
      alphaMode,
      alphaCutoutThreshold(outlineRenderType, alphaMode),
      outlineRenderType,
      null
    );
    renderer.accept(wrapSprite(new OutlineVertexConsumer(consumer, outlineColor), sprite));
    consumer.flush();
  }

  private void captureCrumblingGeometry(
    boolean affectsCrumbling,
    @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
    Consumer<VertexConsumer> renderer
  ) {
    if (!affectsCrumbling
      || crumblingOverlay == null
      || crumblingOverlay.progress() < 0
      || crumblingOverlay.progress() >= ModelBakery.DESTROY_TYPES.size()) {
      return;
    }

    var renderType = ModelBakery.DESTROY_TYPES.get(crumblingOverlay.progress());
    var texture = textureFromRenderType(renderType);
    var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      renderType.mode(),
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      renderType,
      null
    );
    renderer.accept(new SheetedDecalTextureGenerator(consumer, crumblingOverlay.cameraPose(), 1.0F));
    consumer.flush();
  }

  private VertexConsumer wrapSprite(VertexConsumer consumer, @Nullable TextureAtlasSprite sprite) {
    return sprite != null ? sprite.wrap(consumer) : consumer;
  }

  private void appendBakedQuad(
    BakedQuad quad,
    Matrix4fc poseMatrix,
    @Nullable RenderType renderType,
    int baseColor,
    @Nullable int[] tints,
    int light,
    int overlay
  ) {
    var captured = captureBakedQuad(quad, poseMatrix);
    if (captured == null) {
      return;
    }

    var materialInfo = quad.materialInfo();
    var color = baseColor;
    if (materialInfo.isTinted() && tints != null) {
      var tintIndex = materialInfo.tintIndex();
      if (tintIndex >= 0 && tintIndex < tints.length) {
        color = modulateColor(color, tints[tintIndex]);
      }
    }
    var effectiveRenderType = renderType != null ? renderType : materialInfo.itemRenderType();
    color = modulateColor(color, lightColor(light, materialInfo.lightEmission(), effectiveRenderType));
    var alphaMode = alphaMode(effectiveRenderType, captured.texture(), color, captured.uv());
    var face = RendererAssets.GeometryFace.of(
      captured.vertices(),
      captured.uv(),
      captured.texture(),
      alphaMode,
      quad.direction(),
      -1,
      materialInfo.lightEmission(),
      materialInfo.shade()
    );
    var renderQuad = WorldMeshCollector.toRenderQuad(
      face,
      0.0,
      0.0,
      0.0,
      color,
      doubleSided(effectiveRenderType),
      0.0F,
      alphaCutoutThreshold(effectiveRenderType, alphaMode)
    );
    addRenderTypeQuad(withRenderState(withOverlay(renderQuad, effectiveRenderType, overlay), effectiveRenderType), effectiveRenderType);
  }

  private void appendBakedQuadOutline(BakedQuad quad, Matrix4fc poseMatrix, @Nullable RenderType renderType, int outlineColor, int overlay) {
    if (outlineColor == 0) {
      return;
    }

    var captured = captureBakedQuad(quad, poseMatrix);
    if (captured == null) {
      return;
    }

    var effectiveRenderType = renderType != null ? renderType : quad.materialInfo().itemRenderType();
    if (effectiveRenderType == null || (effectiveRenderType.outline().isEmpty() && !effectiveRenderType.isOutline())) {
      return;
    }

    var outlineRenderType = effectiveRenderType.isOutline() ? effectiveRenderType : effectiveRenderType.outline().orElse(effectiveRenderType);
    var alphaMode = alphaMode(outlineRenderType, captured.texture(), outlineColor, captured.uv());
    var face = RendererAssets.GeometryFace.of(
      captured.vertices(),
      captured.uv(),
      captured.texture(),
      alphaMode,
      quad.direction(),
      -1,
      0,
      false
    );
    var renderQuad = WorldMeshCollector.toRenderQuad(
      face,
      0.0,
      0.0,
      0.0,
      outlineColor,
      doubleSided(outlineRenderType),
      0.0F,
      alphaCutoutThreshold(outlineRenderType, alphaMode)
    );
    addRenderTypeQuad(withRenderState(withOverlay(renderQuad, outlineRenderType, overlay), outlineRenderType), outlineRenderType);
  }

  private void appendBakedQuadGlint(
    BakedQuad quad,
    PoseStack.Pose pose,
    @Nullable RenderType renderType,
    ItemDisplayContext displayContext,
    ItemStackRenderState.FoilType foilType
  ) {
    if (quad == null || quad.materialInfo() == null) {
      return;
    }

    var glintTexture = glintTexture();
    var glintRenderType = foilRenderType(renderType, true);
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      glintRenderType.mode(),
      glintTexture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      alphaCutoutThreshold(glintRenderType, RendererAssets.AlphaMode.TRANSLUCENT),
      glintRenderType,
      null,
      glintMaterial(glintTexture, glintRenderType)
    );
    var output = foilType == ItemStackRenderState.FoilType.SPECIAL
      ? new SheetedDecalTextureGenerator(consumer, specialFoilDecalPose(displayContext, pose), 0.0078125F)
      : consumer;
    var instance = new QuadInstance();
    putQuad(pose, quad, instance, output);
    consumer.flush();
  }

  @Nullable
  private CapturedBakedQuad captureBakedQuad(BakedQuad quad, Matrix4fc poseMatrix) {
    if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null || quad.materialInfo().sprite().contents() == null) {
      return null;
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

    return new CapturedBakedQuad(vertices, uv, texture);
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
      "component",
      text.getString(),
      false,
      displayMode.name(),
      light,
      color,
      backgroundColor,
      0
    );
    var bufferSource = new CapturingBufferSource();
    font().drawInBatch(text, x, y, color, false, pose, bufferSource, displayMode, backgroundColor, light);
    bufferSource.flush();
  }

  private void submitNameTagText(FeatureBuckets target, NameTagDraw draw) {
    withBucketStage(target, FeatureStage.TRANSLUCENT_NAME_TAG, () -> submitComponentText(
      draw.pose(),
      draw.text(),
      draw.x(),
      draw.y(),
      draw.color(),
      draw.backgroundColor(),
      draw.displayMode(),
      draw.light()
    ));
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
    builder().add(WorldMeshCollector.toRenderQuad(
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
    builder().add(WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, color, doubleSided, 0.0F, alphaCutoutThreshold));
  }

  private void addFace(Vector3f[] vertices, float[] uv, RenderMaterial material) {
    builder().add(face(vertices, uv, material));
  }

  private RenderQuad face(Vector3f[] vertices, float[] uv, RenderMaterial material) {
    return new RenderQuad(
      new RenderVertex(vertices[0].x(), vertices[0].y(), vertices[0].z(), uv[0], uv[1], 0xFFFFFFFF),
      new RenderVertex(vertices[1].x(), vertices[1].y(), vertices[1].z(), uv[2], uv[3], 0xFFFFFFFF),
      new RenderVertex(vertices[2].x(), vertices[2].y(), vertices[2].z(), uv[4], uv[5], 0xFFFFFFFF),
      new RenderVertex(vertices[3].x(), vertices[3].y(), vertices[3].z(), uv[6], uv[7], 0xFFFFFFFF),
      material
    );
  }

  private Vector3f transform(PoseStack poseStack, float x, float y, float z) {
    return poseStack.last().pose().transformPosition(new Vector3f(x, y, z));
  }

  private Vector3f rotateParticleVertex(Quaternionf rotation, float x, float y, float z, float size, float px, float py) {
    return new Vector3f(px, py, 0.0F).rotate(rotation).mul(size).add(x, y, z);
  }

  private float xOffset(Component text) {
    return -font().width(text) * 0.5F;
  }

  private int nameTagBackgroundColor() {
    var opacity = Minecraft.getInstance().gameRenderer.getGameRenderState().optionsRenderState.getBackgroundOpacity(0.25F);
    return Math.clamp((int) (opacity * 255.0F), 0, 255) << 24;
  }

  private Font font() {
    return Minecraft.getInstance().font;
  }

  private static String formattedText(FormattedCharSequence text) {
    var builder = new StringBuilder();
    text.accept((_, _, codePoint) -> {
      builder.appendCodePoint(codePoint);
      return true;
    });
    return builder.toString();
  }

  static RendererAssets.AlphaMode alphaMode(@Nullable RenderType renderType, RendererAssets.TextureImage texture, int color) {
    return alphaMode(renderType, texture, color, null);
  }

  static RendererAssets.AlphaMode alphaMode(@Nullable RenderType renderType, RendererAssets.TextureImage texture, int color, @Nullable float[] uv) {
    var coverage = uv != null ? texture.alphaCoverage(uv) : new RendererAssets.TextureImage.AlphaCoverage(texture.hasAlpha(), texture.hasTranslucentPixels());
    var alpha = (color >>> 24) & 0xFF;
    if (renderType != null && renderType.hasBlending()) {
      return RendererAssets.AlphaMode.TRANSLUCENT;
    }
    return coverage.hasAlpha() || alpha < 255 ? RendererAssets.AlphaMode.CUTOUT : RendererAssets.AlphaMode.OPAQUE;
  }

  private static RendererAssets.AlphaMode alphaMode(@Nullable RenderType renderType, RendererAssets.TextureImage texture, int[] colors, float[] uv) {
    var coverage = texture.alphaCoverage(uv);
    var hasNonOpaqueAlpha = hasNonOpaqueAlpha(colors);
    if (renderType != null && renderType.hasBlending()) {
      return RendererAssets.AlphaMode.TRANSLUCENT;
    }
    return coverage.hasAlpha() || hasNonOpaqueAlpha ? RendererAssets.AlphaMode.CUTOUT : RendererAssets.AlphaMode.OPAQUE;
  }

  @Nullable
  private static Identifier sampler0Location(RenderType renderType) {
    RenderSetup state = renderType.state;
    if (state == null || state.textures == null || state.textures.isEmpty()) {
      return null;
    }

    var sampler0 = state.textures.get("Sampler0");
    return sampler0 != null ? sampler0.location() : null;
  }

  private static boolean hasNonOpaqueAlpha(int[] colors) {
    for (var color : colors) {
      if (((color >>> 24) & 0xFF) < 255) {
        return true;
      }
    }
    return false;
  }

  private static boolean itemHasTranslucency(List<BakedQuad> quads) {
    for (var quad : quads) {
      if (quad == null || quad.materialInfo() == null || quad.materialInfo().itemRenderType() == null) {
        continue;
      }
      if (quad.materialInfo().itemRenderType().hasBlending()) {
        return true;
      }
    }
    return false;
  }

  private RenderQuad withRenderState(RenderQuad quad, @Nullable RenderType renderType) {
    if (renderType == null) {
      return quad;
    }

    return withMaterial(quad, applyExtendedRenderState(quad.material().withRenderType(renderType, sortGroups.group(renderType)), renderType));
  }

  private RenderQuad withOverlay(RenderQuad quad, @Nullable RenderType renderType, int overlay) {
    if (!usesOverlay(renderType)) {
      return quad;
    }

    var overlayColor = overlayColor(overlay & 0xFFFF, overlay >>> 16 & 0xFFFF);
    return new RenderQuad(
      withOverlay(quad.v0(), overlayColor),
      withOverlay(quad.v1(), overlayColor),
      withOverlay(quad.v2(), overlayColor),
      withOverlay(quad.v3(), overlayColor),
      quad.material()
    );
  }

  private RenderVertex withOverlay(RenderVertex vertex, int overlayColor) {
    return new RenderVertex(vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v(), vertex.color(), overlayColor);
  }

  private RenderQuad withMaterial(RenderQuad quad, RenderMaterial material) {
    return new RenderQuad(quad.v0(), quad.v1(), quad.v2(), quad.v3(), material);
  }

  private boolean doubleSided(@Nullable RenderType renderType) {
    return renderType != null && !renderType.pipeline().isCull();
  }

  private RenderType foilRenderType(@Nullable RenderType renderType, boolean sheeted) {
    if (renderType == null) {
      return sheeted ? RenderTypes.glint() : RenderTypes.entityGlint();
    }
    if (Minecraft.getInstance() == null) {
      return sheeted ? RenderTypes.glint() : RenderTypes.entityGlint();
    }

    return ItemFeatureRenderer.getFoilRenderType(renderType, sheeted);
  }

  private static PoseStack.Pose specialFoilDecalPose(ItemDisplayContext displayContext, PoseStack.Pose pose) {
    var foilDecalPose = pose.copy();
    if (displayContext == ItemDisplayContext.GUI) {
      MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.5F);
    } else if (displayContext.firstPerson()) {
      MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.75F);
    }
    return foilDecalPose;
  }

  private RenderMaterial glintMaterial(RendererAssets.TextureImage texture, RenderType renderType) {
    var material = RenderMaterial
      .create(
        texture,
        RendererAssets.AlphaMode.TRANSLUCENT,
        GLINT_TINT,
        true,
        0.0F,
        alphaCutoutThreshold(renderType, RendererAssets.AlphaMode.TRANSLUCENT)
      )
      .withPipelineState(renderType.pipeline());
    return new RenderMaterial(
      material.texture(),
      material.alphaMode(),
      material.color(),
      material.doubleSided(),
      material.depthBias(),
      material.polygonOffsetFactor(),
      material.polygonOffsetUnits(),
      material.alphaCutoutThreshold(),
      material.alphaCutoutSource(),
      material.depthTest(),
      material.depthWrite(),
      material.blendState(),
      material.colorWriteMask(),
      RenderMaterial.UvTransform.glint(glintScale(renderType)),
      material.textureSampleMode(),
      material.fogMode(),
      false,
      sortGroups.group(renderType),
      material.viewScale(),
      material.dissolveMaskTexture()
    );
  }

  private static float glintScale(RenderType renderType) {
    if (renderType == RenderTypes.entityGlint()) {
      return 0.5F;
    }
    if (renderType == RenderTypes.armorEntityGlint()) {
      return 0.16F;
    }
    return 8.0F;
  }

  private RendererAssets.TextureImage glintTexture() {
    return assets.texture(ENCHANTED_GLINT_ITEM);
  }

  private int alphaCutoutThreshold(@Nullable RenderType renderType, RendererAssets.AlphaMode alphaMode) {
    if (renderType == null) {
      return RenderMaterial.defaultAlphaCutoutThreshold(alphaMode);
    }
    return RenderMaterial.shaderAlphaCutoutThreshold(renderType, alphaMode);
  }

  private int modulateColor(int left, int right) {
    var a = ((left >>> 24) & 0xFF) * ((right >>> 24) & 0xFF) / 255;
    var r = ((left >>> 16) & 0xFF) * ((right >>> 16) & 0xFF) / 255;
    var g = ((left >>> 8) & 0xFF) * ((right >>> 8) & 0xFF) / 255;
    var b = (left & 0xFF) * (right & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private int lightColor(int lightCoords, int emission, @Nullable RenderType renderType) {
    if (!usesLightmap(renderType)) {
      return 0xFFFFFFFF;
    }

    var litCoords = LightCoordsUtil.lightCoordsWithEmission(lightCoords, Math.clamp(emission, 0, 15));
    if (litCoords == LightCoordsUtil.FULL_BRIGHT) {
      return 0xFFFFFFFF;
    }

    var blockLight = LightCoordsUtil.block(litCoords) / 15.0F;
    var skyLevel = LightCoordsUtil.sky(litCoords);
    var skyLight = ctx.level() != null ? Lightmap.getBrightness(ctx.level().dimensionType(), skyLevel) : skyLevel / 15.0F;
    var factor = Math.clamp(Math.max(blockLight, skyLight), 0.18F, 1.0F);
    var channel = Math.clamp(Math.round(factor * 255.0F), 0, 255);
    return 0xFF000000 | (channel << 16) | (channel << 8) | channel;
  }

  private int directionalLightColor(int color, Vector3f normal, @Nullable RenderType renderType, FaceLighting faceLighting) {
    if (!usesDirectionalLighting(renderType) || normal.lengthSquared() <= 1.0E-8F) {
      return color;
    }

    var unitNormal = new Vector3f(normal).normalize();
    if (faceLighting == FaceLighting.BACK) {
      unitNormal.negate();
    }
    var light0 = Math.max(0.0F, levelLight0().dot(unitNormal));
    var light1 = Math.max(0.0F, levelLight1().dot(unitNormal));
    var light = Math.min(1.0F, (light0 + light1) * 0.6F + 0.4F);
    var r = Math.clamp(Math.round(((color >>> 16) & 0xFF) * light), 0, 255);
    var g = Math.clamp(Math.round(((color >>> 8) & 0xFF) * light), 0, 255);
    var b = Math.clamp(Math.round((color & 0xFF) * light), 0, 255);
    return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
  }

  private Vector3f levelLight0() {
    return cardinalLightType() == CardinalLighting.Type.NETHER ? NETHER_LEVEL_LIGHT_0 : LEVEL_LIGHT_0;
  }

  private Vector3f levelLight1() {
    return cardinalLightType() == CardinalLighting.Type.NETHER ? NETHER_LEVEL_LIGHT_1 : LEVEL_LIGHT_1;
  }

  private CardinalLighting.Type cardinalLightType() {
    return ctx.level() != null ? ctx.level().dimensionType().cardinalLightType() : CardinalLighting.Type.DEFAULT;
  }

  private boolean usesDirectionalLighting(@Nullable RenderType renderType) {
    if (renderType == null) {
      return false;
    }

    var pipeline = renderType.pipeline();
    var shaderDefines = pipeline.getShaderDefines().flags();
    if (!shaderDefines.contains("PER_FACE_LIGHTING") && shaderDefines.contains("NO_CARDINAL_LIGHTING")) {
      return false;
    }

    var fragmentShader = pipeline.getFragmentShader().getPath();
    return fragmentShader.equals("core/entity") || fragmentShader.equals("core/item");
  }

  private boolean usesPerFaceLighting(@Nullable RenderType renderType) {
    if (renderType == null) {
      return false;
    }

    return renderType.pipeline().getShaderDefines().flags().contains("PER_FACE_LIGHTING");
  }

  private RenderMaterial applyExtendedRenderState(RenderMaterial material, @Nullable RenderType renderType) {
    var dissolveMaskTexture = dissolveMaskTexture(renderType);
    return dissolveMaskTexture != null ? material.withDissolveMaskTexture(dissolveMaskTexture) : material;
  }

  @Nullable
  private RendererAssets.TextureImage dissolveMaskTexture(@Nullable RenderType renderType) {
    if (renderType == null || !renderType.pipeline().getShaderDefines().flags().contains("DISSOLVE")) {
      return null;
    }

    RenderSetup state = renderType.state;
    if (state == null || state.textures == null) {
      return null;
    }

    var binding = state.textures.get("DissolveMaskSampler");
    if (binding == null || binding.location() == null) {
      return null;
    }

    return assets.renderTexture(binding.location());
  }

  private boolean usesLightmap(@Nullable RenderType renderType) {
    return renderType == null || renderType.state.useLightmap;
  }

  private RendererAssets.TextureImage textureFromRenderType(@Nullable RenderType renderType) {
    if (renderType == null) {
      return WHITE_TEXTURE;
    }

    RenderSetup state = renderType.state;
    if (state == null || state.textures == null || state.textures.isEmpty()) {
      return WHITE_TEXTURE;
    }

    var sampler0Location = sampler0Location(renderType);
    if (sampler0Location != null) {
      return assets.renderTexture(sampler0Location);
    }

    for (var binding : state.textures.values()) {
      var location = binding.location();
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

  private record CapturedBakedQuad(Vector3f[] vertices, float[] uv, RendererAssets.TextureImage texture) {
  }

  private final class CapturingVertexConsumer implements VertexConsumer {
    private final Matrix4fc pose;
    @Nullable
    private final PoseStack.Pose normalPose;
    private final VertexFormat.Mode mode;
    private final RendererAssets.TextureImage texture;
    private final RendererAssets.AlphaMode alphaMode;
    private final int alphaCutoutThreshold;
    @Nullable
    private final DepthStencilState depthStencilState;
    @Nullable
    private final RenderType renderType;
    @Nullable
    private final RenderMaterial materialOverride;
    private final boolean matrixVerticesBypassBasePose;
    private final ArrayList<CapturedVertex> vertices = new ArrayList<>();
    private float lineWidth = 1.0F;
    private CapturedVertex current;

    private CapturingVertexConsumer(
      Matrix4fc pose,
      VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      int alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState
    ) {
      this(pose, mode, texture, alphaMode, alphaCutoutThreshold, renderType, depthStencilState, null);
    }

    private CapturingVertexConsumer(
      PoseStack.Pose pose,
      VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      int alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState,
      @Nullable RenderMaterial materialOverride,
      boolean matrixVerticesBypassBasePose
    ) {
      this(
        pose.pose(),
        pose,
        mode,
        texture,
        alphaMode,
        alphaCutoutThreshold,
        renderType,
        depthStencilState,
        materialOverride,
        matrixVerticesBypassBasePose
      );
    }

    private CapturingVertexConsumer(
      Matrix4fc pose,
      VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      int alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState,
      @Nullable RenderMaterial materialOverride
    ) {
      this(pose, null, mode, texture, alphaMode, alphaCutoutThreshold, renderType, depthStencilState, materialOverride, false);
    }

    private CapturingVertexConsumer(
      Matrix4fc pose,
      @Nullable PoseStack.Pose normalPose,
      VertexFormat.Mode mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      int alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState,
      @Nullable RenderMaterial materialOverride,
      boolean matrixVerticesBypassBasePose
    ) {
      this.pose = pose;
      this.normalPose = normalPose;
      this.mode = mode;
      this.texture = texture;
      this.alphaMode = alphaMode;
      this.alphaCutoutThreshold = alphaCutoutThreshold;
      this.renderType = renderType;
      this.depthStencilState = depthStencilState;
      this.materialOverride = materialOverride;
      this.matrixVerticesBypassBasePose = matrixVerticesBypassBasePose;
    }

    void flush() {
      try {
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
      } finally {
        vertices.clear();
        current = null;
      }
    }

    private void emitQuad(CapturedVertex a, CapturedVertex b, CapturedVertex c, CapturedVertex d) {
      addCapturedQuad(a, b, c, d);
    }

    private void addCapturedQuad(CapturedVertex a, CapturedVertex b, CapturedVertex c, CapturedVertex d) {
      addCapturedFace(
        new Vector3f[]{a.position(), b.position(), c.position(), d.position()},
        new Vector3f[]{a.normal(), b.normal(), c.normal(), d.normal()},
        new int[]{a.color(), b.color(), c.color(), d.color()},
        new float[]{a.u(), a.v(), b.u(), b.v(), c.u(), c.v(), d.u(), d.v()},
        new int[]{a.light(), b.light(), c.light(), d.light()},
        new int[]{a.overlayColor(), b.overlayColor(), c.overlayColor(), d.overlayColor()}
      );
    }

    private void emitTriangle(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
      var colorA = a.color();
      var colorB = b.color();
      var colorC = c.color();
      var lightA = a.light();
      var lightB = b.light();
      var lightC = c.light();
      if (usesFlatVertexColor(renderType)) {
        colorA = c.color();
        colorB = c.color();
        colorC = c.color();
        lightA = c.light();
        lightB = c.light();
        lightC = c.light();
      }
      addCapturedFace(
        new Vector3f[]{a.position(), b.position(), c.position(), c.position()},
        new Vector3f[]{a.normal(), b.normal(), c.normal(), c.normal()},
        new int[]{colorA, colorB, colorC, colorC},
        new float[]{a.u(), a.v(), b.u(), b.v(), c.u(), c.v(), c.u(), c.v()},
        new int[]{lightA, lightB, lightC, lightC},
        new int[]{a.overlayColor(), b.overlayColor(), c.overlayColor(), c.overlayColor()}
      );
    }

    private void emitLine(CapturedVertex a, CapturedVertex b) {
      var material = materialForFace(
        new int[]{a.color(), a.color(), b.color(), b.color()},
        new float[]{a.u(), a.v(), a.u(), a.v(), b.u(), b.v(), b.u(), b.v()}
      );
      var shaderScale = material.viewScale() * LINE_SHADER_VIEW_SCALE;
      var aClip = clipPosition(a.position(), shaderScale);
      var bClip = clipPosition(b.position(), shaderScale);
      var line = clipLine(a, b, aClip, bClip);
      if (line == null) {
        return;
      }
      a = line.a();
      b = line.b();
      aClip = line.aClip();
      bClip = line.bClip();

      var aSegmentOffset = lineOffset(aClip, bClip, a.lineWidth());
      var bSegmentOffset = lineOffset(aClip, bClip, b.lineWidth());
      if (!aSegmentOffset.usable() || !bSegmentOffset.usable()) {
        return;
      }

      var clipToWorld = clipToWorld(shaderScale);
      var aOffset = lineOffset(a, aClip, shaderScale, aSegmentOffset);
      var bOffset = lineOffset(b, bClip, shaderScale, bSegmentOffset);
      var positions = new Vector3f[]{
        unprojectClipOffset(aClip, -aOffset.x(), -aOffset.y(), clipToWorld),
        unprojectClipOffset(aClip, aOffset.x(), aOffset.y(), clipToWorld),
        unprojectClipOffset(bClip, bOffset.x(), bOffset.y(), clipToWorld),
        unprojectClipOffset(bClip, -bOffset.x(), -bOffset.y(), clipToWorld)
      };
      for (var position : positions) {
        if (position == null) {
          return;
        }
      }

      addCapturedFace(
        positions,
        zeroNormals(),
        new int[]{a.color(), a.color(), b.color(), b.color()},
        new float[]{a.u(), a.v(), a.u(), a.v(), b.u(), b.v(), b.u(), b.v()},
        new int[]{a.light(), a.light(), b.light(), b.light()},
        new int[]{a.overlayColor(), a.overlayColor(), b.overlayColor(), b.overlayColor()},
        material
      );
    }

    private void emitPoint(CapturedVertex vertex) {
      var material = materialForFace(
        new int[]{vertex.color(), vertex.color(), vertex.color(), vertex.color()},
        new float[]{vertex.u(), vertex.v(), vertex.u(), vertex.v(), vertex.u(), vertex.v(), vertex.u(), vertex.v()}
      );
      var clip = clipPosition(vertex.position(), material.viewScale());
      if (!isInsideClipVolume(clip)) {
        return;
      }

      var clipToWorld = clipToWorld(material.viewScale());
      var halfWidth = Math.max(0.0F, vertex.lineWidth()) / ctx.camera().width();
      var halfHeight = Math.max(0.0F, vertex.lineWidth()) / ctx.camera().height();
      var positions = new Vector3f[]{
        unprojectClipOffset(clip, -halfWidth, -halfHeight, clipToWorld),
        unprojectClipOffset(clip, -halfWidth, halfHeight, clipToWorld),
        unprojectClipOffset(clip, halfWidth, halfHeight, clipToWorld),
        unprojectClipOffset(clip, halfWidth, -halfHeight, clipToWorld)
      };
      for (var position : positions) {
        if (position == null) {
          return;
        }
      }

      addCapturedFace(
        positions,
        zeroNormals(),
        new int[]{vertex.color(), vertex.color(), vertex.color(), vertex.color()},
        new float[]{vertex.u(), vertex.v(), vertex.u(), vertex.v(), vertex.u(), vertex.v(), vertex.u(), vertex.v()},
        new int[]{vertex.light(), vertex.light(), vertex.light(), vertex.light()},
        new int[]{vertex.overlayColor(), vertex.overlayColor(), vertex.overlayColor(), vertex.overlayColor()},
        material
      );
    }

    private Vector3f[] zeroNormals() {
      return new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    }

    private void addCapturedFace(
      Vector3f[] positions,
      Vector3f[] normals,
      int[] colors,
      float[] uv,
      int[] lights,
      int[] overlayColors
    ) {
      addCapturedFace(positions, normals, colors, uv, lights, overlayColors, materialForFace(colors, uv));
    }

    private void addCapturedFace(
      Vector3f[] positions,
      Vector3f[] normals,
      int[] colors,
      float[] uv,
      int[] lights,
      int[] overlayColors,
      RenderMaterial material
    ) {
      var applyOverlay = materialOverride == null && usesOverlay(renderType);
      if (usesPerFaceLighting(shadingRenderType()) && material.doubleSided()) {
        var oneSidedMaterial = material.withDoubleSided(false);
        addRenderTypeQuad(renderQuad(positions, normals, colors, uv, lights, overlayColors, applyOverlay, oneSidedMaterial, FaceLighting.FRONT, 0, 1, 2, 3), renderType);
        addRenderTypeQuad(renderQuad(positions, normals, colors, uv, lights, overlayColors, applyOverlay, oneSidedMaterial, FaceLighting.BACK, 3, 2, 1, 0), renderType);
        return;
      }

      addRenderTypeQuad(renderQuad(positions, normals, colors, uv, lights, overlayColors, applyOverlay, material, FaceLighting.FRONT, 0, 1, 2, 3), renderType);
    }

    private RenderQuad renderQuad(
      Vector3f[] positions,
      Vector3f[] normals,
      int[] colors,
      float[] uv,
      int[] lights,
      int[] overlayColors,
      boolean applyOverlay,
      RenderMaterial material,
      FaceLighting faceLighting,
      int i0,
      int i1,
      int i2,
      int i3
    ) {
      return new RenderQuad(
        renderVertex(positions[i0], normals[i0], uv[i0 * 2], uv[i0 * 2 + 1], colors[i0], lights[i0], overlayColors[i0], applyOverlay, faceLighting),
        renderVertex(positions[i1], normals[i1], uv[i1 * 2], uv[i1 * 2 + 1], colors[i1], lights[i1], overlayColors[i1], applyOverlay, faceLighting),
        renderVertex(positions[i2], normals[i2], uv[i2 * 2], uv[i2 * 2 + 1], colors[i2], lights[i2], overlayColors[i2], applyOverlay, faceLighting),
        renderVertex(positions[i3], normals[i3], uv[i3 * 2], uv[i3 * 2 + 1], colors[i3], lights[i3], overlayColors[i3], applyOverlay, faceLighting),
        material
      );
    }

    private RenderMaterial materialForFace(int[] colors, float[] uv) {
      var faceAlphaMode = renderType != null ? alphaMode(renderType, texture, colors, uv) : alphaMode;
      var faceAlphaCutoutThreshold = renderType != null ? alphaCutoutThreshold(renderType, faceAlphaMode) : alphaCutoutThreshold;
      var material = materialOverride != null
        ? materialOverride
        : RenderMaterial.create(texture, faceAlphaMode, 0xFFFFFFFF, isTextRenderType(renderType), 0.0F, faceAlphaCutoutThreshold).withDepthState(depthStencilState);
      if (materialOverride == null && renderType != null) {
        material = applyExtendedRenderState(material.withRenderType(renderType, sortGroups.group(renderType)), renderType);
      }
      return material;
    }

    @Nullable
    private RenderType shadingRenderType() {
      return materialOverride == null ? renderType : null;
    }

    private Vector4f clipPosition(Vector3f position, float viewScale) {
      var view = ctx.camera().viewRotationMatrix().transform(new Vector4f(
        (float) (position.x() - ctx.camera().eyeX()),
        (float) (position.y() - ctx.camera().eyeY()),
        (float) (position.z() - ctx.camera().eyeZ()),
        1.0F
      ));
      if (viewScale != 1.0F) {
        view.mul(viewScale, viewScale, viewScale, 1.0F);
      }
      return ctx.camera().projectionMatrix().transform(view);
    }

    private Matrix4f clipToWorld(float viewScale) {
      var view = ctx.camera().viewRotationMatrix();
      view.translate((float) -ctx.camera().eyeX(), (float) -ctx.camera().eyeY(), (float) -ctx.camera().eyeZ());
      return new Matrix4f(ctx.camera().projectionMatrix())
        .scale(viewScale, viewScale, viewScale)
        .mul(view)
        .invert();
    }

    private boolean isUsableClip(Vector4f clip) {
      return Float.isFinite(clip.x)
        && Float.isFinite(clip.y)
        && Float.isFinite(clip.z)
        && Float.isFinite(clip.w)
        && Math.abs(clip.w) > 1.0E-6F;
    }

    private boolean isInsideClipVolume(Vector4f clip) {
      if (!isUsableClip(clip)) {
        return false;
      }

      var epsilon = 1.0E-6F * Math.abs(clip.w);
      return clip.x >= -clip.w - epsilon
        && clip.x <= clip.w + epsilon
        && clip.y >= -clip.w - epsilon
        && clip.y <= clip.w + epsilon
        && clip.z >= -clip.w - epsilon
        && clip.z <= clip.w + epsilon;
    }

    @Nullable
    private ClippedLine clipLine(CapturedVertex a, CapturedVertex b, Vector4f aClip, Vector4f bClip) {
      if (!isUsableClip(aClip) || !isUsableClip(bClip)) {
        return null;
      }

      var line = new ClippedLine(a, b, aClip, bClip);
      for (var plane : ClipPlane.values()) {
        line = clipLine(line, plane);
        if (line == null) {
          return null;
        }
      }

      return isUsableClip(line.aClip()) && isUsableClip(line.bClip()) ? line : null;
    }

    @Nullable
    private ClippedLine clipLine(ClippedLine line, ClipPlane plane) {
      var aDistance = clipDistance(line.aClip(), plane);
      var bDistance = clipDistance(line.bClip(), plane);
      var aInside = aDistance >= 0.0F;
      var bInside = bDistance >= 0.0F;
      if (aInside && bInside) {
        return line;
      }
      if (!aInside && !bInside) {
        return null;
      }

      var delta = aDistance - bDistance;
      if (Math.abs(delta) <= 1.0E-8F) {
        return null;
      }

      var t = Math.clamp(aDistance / delta, 0.0F, 1.0F);
      var vertex = line.a().interpolate(line.b(), t);
      var clip = interpolateClip(line.aClip(), line.bClip(), t);
      if (aInside) {
        return new ClippedLine(line.a(), vertex, line.aClip(), clip);
      }
      return new ClippedLine(vertex, line.b(), clip, line.bClip());
    }

    private float clipDistance(Vector4f clip, ClipPlane plane) {
      return switch (plane) {
        case LEFT -> clip.x + clip.w;
        case RIGHT -> clip.w - clip.x;
        case BOTTOM -> clip.y + clip.w;
        case TOP -> clip.w - clip.y;
        case NEAR -> clip.z + clip.w;
        case FAR -> clip.w - clip.z;
      };
    }

    private Vector4f interpolateClip(Vector4f a, Vector4f b, float t) {
      return new Vector4f(
        Mth.lerp(t, a.x, b.x),
        Mth.lerp(t, a.y, b.y),
        Mth.lerp(t, a.z, b.z),
        Mth.lerp(t, a.w, b.w)
      );
    }

    private ScreenOffset lineOffset(CapturedVertex vertex, Vector4f clip, float shaderScale, ScreenOffset fallback) {
      var normal = vertex.normal();
      if (normal.lengthSquared() <= 1.0E-8F) {
        return fallback;
      }

      var normalEnd = clipPosition(new Vector3f(vertex.position()).add(normal), shaderScale);
      if (!isUsableClip(normalEnd)) {
        return fallback;
      }

      var offset = lineOffset(clip, normalEnd, vertex.lineWidth());
      return offset.usable() ? offset : fallback;
    }

    private ScreenOffset lineOffset(Vector4f startClip, Vector4f endClip, float lineWidth) {
      var startInverseW = 1.0F / startClip.w;
      var endInverseW = 1.0F / endClip.w;
      var dx = (endClip.x * endInverseW - startClip.x * startInverseW) * ctx.camera().width();
      var dy = (endClip.y * endInverseW - startClip.y * startInverseW) * ctx.camera().height();
      var length = (float) Math.sqrt(dx * dx + dy * dy);
      if (!Float.isFinite(length) || length <= 1.0E-6F) {
        return ScreenOffset.UNUSABLE;
      }

      var offsetX = -dy / length * lineWidth / ctx.camera().width();
      var offsetY = dx / length * lineWidth / ctx.camera().height();
      if (offsetX < 0.0F) {
        offsetX = -offsetX;
        offsetY = -offsetY;
      }
      return new ScreenOffset(offsetX, offsetY, true);
    }

    @Nullable
    private Vector3f unprojectClipOffset(Vector4f clip, float offsetX, float offsetY, Matrix4f clipToWorld) {
      var inverseW = 1.0F / clip.w;
      var x = (clip.x * inverseW + offsetX) * clip.w;
      var y = (clip.y * inverseW + offsetY) * clip.w;
      var world = clipToWorld.transform(new Vector4f(x, y, clip.z, clip.w));
      if (!Float.isFinite(world.x)
        || !Float.isFinite(world.y)
        || !Float.isFinite(world.z)
        || !Float.isFinite(world.w)
        || Math.abs(world.w) <= 1.0E-6F) {
        return null;
      }
      return new Vector3f(world.x / world.w, world.y / world.w, world.z / world.w);
    }

    private RenderVertex renderVertex(
      Vector3f position,
      Vector3f normal,
      float u,
      float v,
      int color,
      int light,
      int overlayColor,
      boolean applyOverlay,
      FaceLighting faceLighting
    ) {
      var shadingRenderType = shadingRenderType();
      var shadedColor = directionalLightColor(color, normal, shadingRenderType, faceLighting);
      var shadedOverlayColor = applyOverlay ? overlayColor : RenderVertex.NO_OVERLAY_COLOR;
      if (materialOverride == null) {
        var lightColor = lightColor(light, 0, shadingRenderType);
        shadedColor = modulateColor(shadedColor, lightColor);
        if (applyOverlay) {
          shadedOverlayColor = modulateColor(shadedOverlayColor, lightColor);
        }
      }
      return new RenderVertex(
        position.x(),
        position.y(),
        position.z(),
        u,
        v,
        shadedColor,
        shadedOverlayColor
      );
    }

    private void offsetCurrentPosition(float x, float y, float z) {
      if (current != null) {
        current = current.withPosition(new Vector3f(current.position()).add(x, y, z));
        vertices.set(vertices.size() - 1, current);
      }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      return addCapturedVertex(pose.transformPosition(new Vector3f(x, y, z)));
    }

    @Override
    public VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
      if (matrixVerticesBypassBasePose) {
        return addCapturedVertex(pose.transformPosition(x, y, z, new Vector3f()));
      }

      return VertexConsumer.super.addVertex(pose, x, y, z);
    }

    @Override
    public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
      return addVertex(pose.pose(), x, y, z);
    }

    private VertexConsumer addCapturedVertex(Vector3f position) {
      current = new CapturedVertex(
        position,
        0xFFFFFFFF,
        0.0F,
        0.0F,
        LightCoordsUtil.FULL_BRIGHT,
        RenderVertex.NO_OVERLAY_COLOR,
        new Vector3f(),
        lineWidth
      );
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

    @Override public VertexConsumer setUv1(int u, int v) {
      if (current != null) {
        current = current.withOverlayColor(overlayColor(u, v));
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }
    @Override public VertexConsumer setUv2(int u, int v) {
      if (current != null) {
        current = current.withLight((u & 0xFFFF) | ((v & 0xFFFF) << 16));
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }
    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      var normal = normalPose != null ? normalPose.transformNormal(x, y, z, new Vector3f()) : new Vector3f(x, y, z);
      return setCapturedNormal(normal);
    }

    @Override
    public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
      return setCapturedNormal(pose.transformNormal(x, y, z, new Vector3f()));
    }

    private VertexConsumer setCapturedNormal(Vector3f normal) {
      if (current != null) {
        current = current.withNormal(new Vector3f(
          Math.clamp(normal.x(), -1.0F, 1.0F),
          Math.clamp(normal.y(), -1.0F, 1.0F),
          Math.clamp(normal.z(), -1.0F, 1.0F)
        ));
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }
    @Override public VertexConsumer setLineWidth(float width) {
      this.lineWidth = Float.isFinite(width) ? Math.max(0.0F, width) : 1.0F;
      if (current != null) {
        current = current.withLineWidth(this.lineWidth);
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }
  }

  private boolean usesOverlay(@Nullable RenderType renderType) {
    return renderType != null && renderType.state.useOverlay;
  }

  private static int overlayColor(int u, int v) {
    var x = Math.clamp(u, 0, 15);
    var y = Math.clamp(v, 0, 15);
    if (y < 8) {
      return 0xB2FF0000;
    }

    var alpha = Math.clamp((int) ((1.0F - x / 15.0F * 0.75F) * 255.0F), 0, 255);
    return (alpha << 24) | 0x00FFFFFF;
  }

  private static int interpolateArgb(int left, int right, float t) {
    var a = interpolateChannel(left >>> 24, right >>> 24, t);
    var r = interpolateChannel(left >>> 16, right >>> 16, t);
    var g = interpolateChannel(left >>> 8, right >>> 8, t);
    var b = interpolateChannel(left, right, t);
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  private static int interpolateChannel(int left, int right, float t) {
    return Math.clamp(Math.round(Mth.lerp(t, left & 0xFF, right & 0xFF)), 0, 255);
  }

  private static boolean isTextRenderType(@Nullable RenderType renderType) {
    if (renderType == null) {
      return false;
    }

    var path = renderType.pipeline().getLocation().getPath();
    return path.startsWith("pipeline/text") || path.startsWith("pipeline/gui_text");
  }

  private static boolean usesFlatVertexColor(@Nullable RenderType renderType) {
    return renderType != null && renderType.pipeline().getFragmentShader().getPath().equals("core/rendertype_leash");
  }

  private final class OutlineVertexConsumer implements VertexConsumer {
    private static final float OUTLINE_NORMAL_OFFSET = 0.03F;
    private final CapturingVertexConsumer delegate;
    private final int color;

    private OutlineVertexConsumer(CapturingVertexConsumer delegate, int color) {
      this.delegate = delegate;
      this.color = color;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      delegate.addVertex(x, y, z).setColor(color);
      return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      delegate.setUv(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      delegate.setUv1(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      delegate.offsetCurrentPosition(x * OUTLINE_NORMAL_OFFSET, y * OUTLINE_NORMAL_OFFSET, z * OUTLINE_NORMAL_OFFSET);
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      delegate.setLineWidth(width);
      return this;
    }
  }

  private final class CapturingBufferSource implements MultiBufferSource {
    private final LinkedHashMap<RenderType, CapturingVertexConsumer> consumers = new LinkedHashMap<>();

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
      return consumers.computeIfAbsent(renderType, type -> {
        var texture = textureFromRenderType(type);
        var alphaMode = alphaMode(type, texture, 0xFFFFFFFF);
        return new CapturingVertexConsumer(
          new Matrix4f(),
          type.mode(),
          texture,
          alphaMode,
          alphaCutoutThreshold(type, alphaMode),
          type,
          null
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
          null,
          new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, !currentLayer.translucent())
        )
      );
    }

    private void flush() {
      consumers.values().forEach(CapturingVertexConsumer::flush);
      consumers.clear();
    }
  }

  private record CapturedVertex(
    Vector3f position,
    int color,
    float u,
    float v,
    int light,
    int overlayColor,
    Vector3f normal,
    float lineWidth
  ) {
    private CapturedVertex withPosition(Vector3f position) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withColor(int color) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withUv(float u, float v) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withLight(int light) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withOverlayColor(int overlayColor) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withNormal(Vector3f normal) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex withLineWidth(float lineWidth) {
      return new CapturedVertex(position, color, u, v, light, overlayColor, normal, lineWidth);
    }

    private CapturedVertex interpolate(CapturedVertex next, float t) {
      var blockLight = Math.clamp(Math.round(Mth.lerp(t, LightCoordsUtil.block(light), LightCoordsUtil.block(next.light()))), 0, 15);
      var skyLight = Math.clamp(Math.round(Mth.lerp(t, LightCoordsUtil.sky(light), LightCoordsUtil.sky(next.light()))), 0, 15);
      return new CapturedVertex(
        new Vector3f(position).lerp(next.position(), t),
        interpolateArgb(color, next.color(), t),
        Mth.lerp(t, u, next.u()),
        Mth.lerp(t, v, next.v()),
        LightCoordsUtil.pack(blockLight, skyLight),
        interpolateArgb(overlayColor, next.overlayColor(), t),
        new Vector3f(normal).lerp(next.normal(), t),
        Mth.lerp(t, lineWidth, next.lineWidth())
      );
    }
  }

  private record ClippedLine(CapturedVertex a, CapturedVertex b, Vector4f aClip, Vector4f bClip) {}

  private record ScreenOffset(float x, float y, boolean usable) {
    private static final ScreenOffset UNUSABLE = new ScreenOffset(0.0F, 0.0F, false);
  }

  private record NameTagDraw(
    Matrix4f pose,
    float x,
    int y,
    Component text,
    Font.DisplayMode displayMode,
    int light,
    int color,
    int backgroundColor,
    double distanceToCameraSq
  ) {}

  private record SortedScene(double distanceSq, SceneData scene) {}

  private enum FaceLighting {
    FRONT,
    BACK
  }

  private enum ClipPlane {
    LEFT,
    RIGHT,
    BOTTOM,
    TOP,
    NEAR,
    FAR
  }

  private enum FeatureStage {
    SOLID_MODEL,
    SOLID_MODEL_PART,
    SOLID_FLAME,
    SOLID_LEASH,
    SOLID_ITEM,
    SOLID_BLOCK,
    SOLID_CUSTOM,
    SOLID_PARTICLE,
    TRANSLUCENT_SHADOW,
    TRANSLUCENT_MODEL,
    TRANSLUCENT_MODEL_PART,
    TRANSLUCENT_NAME_TAG,
    TRANSLUCENT_TEXT,
    TRANSLUCENT_ITEM,
    TRANSLUCENT_BLOCK,
    TRANSLUCENT_CUSTOM,
    TRANSLUCENT_PARTICLE
  }

  private static final class FeatureBuckets {
    private final EnumMap<FeatureStage, SceneData.Builder> builders = new EnumMap<>(FeatureStage.class);
    private final ArrayList<NameTagDraw> nameTagSeeThrough = new ArrayList<>();
    private final ArrayList<NameTagDraw> nameTagNormal = new ArrayList<>();
    private final ArrayList<SortedScene> translucentModelDraws = new ArrayList<>();

    private SceneData.Builder builder(FeatureStage stage) {
      return builders.computeIfAbsent(stage, _ -> SceneData.builder());
    }

    private SceneData build() {
      var sceneData = SceneData.EMPTY;
      for (var stage : FeatureStage.values()) {
        var builder = builders.get(stage);
        if (builder != null) {
          sceneData = sceneData.merge(builder.build());
        }
      }
      return sceneData;
    }

    private void flushNameTags(VanillaSubmitCollector collector) {
      nameTagSeeThrough.sort(Comparator.comparingDouble(NameTagDraw::distanceToCameraSq).reversed());
      for (var draw : nameTagSeeThrough) {
        collector.submitNameTagText(this, draw);
      }
      for (var draw : nameTagNormal) {
        collector.submitNameTagText(this, draw);
      }
      nameTagSeeThrough.clear();
      nameTagNormal.clear();
    }

    private void flushSortedModelDraws() {
      if (translucentModelDraws.isEmpty()) {
        return;
      }

      translucentModelDraws.sort(Comparator.comparingDouble(SortedScene::distanceSq).reversed());
      var target = builder(FeatureStage.TRANSLUCENT_MODEL);
      for (var draw : translucentModelDraws) {
        target.addAll(draw.scene());
      }
      translucentModelDraws.clear();
    }
  }

  private static final class SortGroupRegistry {
    private final IdentityHashMap<RenderType, Integer> groups = new IdentityHashMap<>();
    private int nextGroup = 1;

    private int group(RenderType renderType) {
      return groups.computeIfAbsent(renderType, _ -> nextGroup++);
    }
  }
}
