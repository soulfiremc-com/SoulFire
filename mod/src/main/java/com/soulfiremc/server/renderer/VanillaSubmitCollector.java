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

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
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
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

final class VanillaSubmitCollector implements SubmitNodeCollector, OrderedSubmitNodeCollector {
  private static final Identifier ENCHANTED_GLINT_ITEM = Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png");
  private static final Identifier SHADOW_TEXTURE = Identifier.withDefaultNamespace("textures/misc/shadow.png");
  private static final int LEASH_RENDER_STEPS = 24;
  private static final float LEASH_WIDTH = 0.05F;
  private static final float LINE_SHADER_VIEW_SCALE = 1.0F - 1.0F / 256.0F;
  private static final float PACKED_NORMAL_SCALE = 127.0F;
  private static final Vector3f LEVEL_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
  private static final Vector3f LEVEL_LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
  private static final Vector3f NETHER_LEVEL_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
  private static final Vector3f NETHER_LEVEL_LIGHT_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();
  private static final Direction[] DIRECTIONS = Direction.values();
  private static final RendererAssets.TextureImage WHITE_TEXTURE = createSolidTexture(0xFFFFFFFF);
  private final RenderContext ctx;
  private final Vec3 origin;
  private final @Nullable GuiLighting guiLighting;
  private final RendererAssets assets;
  private final NavigableMap<Integer, FeatureBuckets> bucketsByOrder;
  private final SortGroupRegistry sortGroups;
  private final FeatureBuckets buckets;
  private SceneData.Builder activeBuilder;

  VanillaSubmitCollector(RenderContext ctx) {
    this(ctx, null);
  }

  VanillaSubmitCollector(RenderContext ctx, @Nullable GuiLighting guiLighting) {
    this(ctx, new TreeMap<>(), new SortGroupRegistry(), 0, guiLighting, Vec3.ZERO);
  }

  private VanillaSubmitCollector(RenderContext ctx, NavigableMap<Integer, FeatureBuckets> bucketsByOrder, SortGroupRegistry sortGroups, int order, @Nullable GuiLighting guiLighting, Vec3 origin) {
    this.ctx = ctx;
    this.origin = origin;
    this.guiLighting = guiLighting;
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
    preparedCamera.setupPerspective(0.05F, ctx.camera().farPlane(), (float) ctx.camera().fov(), ctx.camera().width(), ctx.camera().height());
    dispatcher.prepare(preparedCamera, activeCameraEntity);
  }

  static void resetEntityDispatcher() {
    Minecraft.getInstance().getEntityRenderDispatcher().resetCamera();
  }

  static boolean shouldRenderEntity(RenderContext ctx, Entity entity) {
    var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
    return dispatcher.shouldRender(entity, createFrustum(ctx), ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
  }

  static VanillaSubmitCollector cameraRelativeCollector(RenderContext ctx) {
    var origin = new Vec3(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    return new VanillaSubmitCollector(ctx, new TreeMap<>(), new SortGroupRegistry(), 0, null, origin);
  }

  void submitEntities(Iterable<Entity> entities) {
    var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
    for (var entity : entities) {
      var renderState = dispatcher.extractEntity(entity, 1.0F);
      var poseStack = new PoseStack();
      dispatcher.submit(renderState, cameraRenderState(), renderState.x - origin.x, renderState.y - origin.y, renderState.z - origin.z, poseStack, this);
    }
  }

  static SceneData collectHandsWithItems(RenderContext ctx, float partialTick) {
    var minecraft = Minecraft.getInstance();
    var player = ctx.localPlayer() != null ? ctx.localPlayer() : minecraft.player;
    if (player == null
      || ctx.cameraDetached()
      || !minecraft.options.getCameraType().isFirstPerson()
      || player.isSleeping()
      || minecraft.gui.hud.isHidden()
      || minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
      return SceneData.EMPTY;
    }

    var collector = new VanillaSubmitCollector(ctx);
    var poseStack = new PoseStack();
    poseStack.pushPose();
    try {
      poseStack.mulPose(ctx.camera().viewRotationMatrix().invert(new Matrix4f()));
      var light = minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, partialTick);
      minecraft.gameRenderer.itemInHandRenderer.submitHandsWithItems(partialTick, poseStack, collector, player, light);
    } finally {
      poseStack.popPose();
    }
    return collector.buildScene();
  }

  static SceneData collectScreenEffects(RenderContext ctx, float partialTick) {
    if (ctx.localPlayer() == null || ctx.cameraDetached()) {
      return SceneData.EMPTY;
    }
    var minecraft = Minecraft.getInstance();
    var collector = new VanillaSubmitCollector(ctx);
    minecraft.gameRenderer.screenEffectRenderer.submit(minecraft.options.getCameraType().isFirstPerson(),
      ctx.localPlayer().isSleeping(), partialTick, collector, minecraft.gui.hud.isHidden());
    return collector.buildScene();
  }

  void submitBlockEntity(BlockEntity blockEntity, @Nullable Integer crumblingProgress, boolean globallyRendered) {
    var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
    dispatcher.prepare(origin);
    var poseStack = new PoseStack();
    var blockPos = blockEntity.getBlockPos();
    poseStack.translate(blockPos.getX() - origin.x, blockPos.getY() - origin.y, blockPos.getZ() - origin.z);
    var crumblingOverlay = crumblingProgress != null ? new ModelFeatureRenderer.CrumblingOverlay(crumblingProgress, poseStack.last()) : null;
    var renderState = dispatcher.tryExtractRenderState(blockEntity, 1.0F, crumblingOverlay, globallyRendered);
    if (renderState != null) {
      dispatcher.submit(renderState, poseStack, this, cameraRenderState());
    }
  }

  static SceneData collectBreakingBlockModel(RenderContext ctx, BlockPos pos, BlockStateModel blockStateModel, long seed, int progress) {
    var collector = new VanillaSubmitCollector(ctx);
    var poseStack = new PoseStack();
    poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
    collector.submitBreakingBlockModel(poseStack, blockStateModel, seed, progress);
    return collector.buildScene();
  }

  void submitParticles() {
    var minecraft = Minecraft.getInstance();
    var particleCamera = new net.minecraft.client.Camera();
    particleCamera.setLevel(ctx.level());
    if (minecraft.player != null) {
      particleCamera.setEntity(minecraft.player);
    }
    particleCamera.setRotation(ctx.camera().yRot(), ctx.camera().xRot());
    particleCamera.setPosition(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());

    var particlesState = extractParticles(minecraft.particleEngine, createFrustum(ctx).offset(-3.0F), particleCamera);
    if (particlesState.particles.isEmpty()) {
      return;
    }

    var cameraState = cameraRenderState();
    for (var particle : particlesState.particles) {
      particle.submit(this, cameraState);
    }
  }

  static ParticlesRenderState extractParticles(ParticleEngine engine, Frustum frustum, net.minecraft.client.Camera camera) {
    var particlesState = new ParticlesRenderState();
    // Quad groups append to a cache owned by the client frame. Extract POV geometry into separate caches.
    var cachedStates = new IdentityHashMap<QuadParticleGroup, QuadParticleRenderState>();
    for (var group : engine.particles.values()) {
      if (group instanceof QuadParticleGroup quads) {
        cachedStates.put(quads, quads.particleTypeRenderState);
        quads.particleTypeRenderState = new QuadParticleRenderState();
      }
    }
    try {
      engine.extract(particlesState, frustum, camera, 1.0F);
      return particlesState;
    } finally {
      cachedStates.forEach((group, state) -> group.particleTypeRenderState = state);
    }
  }

  private static Frustum createFrustum(RenderContext ctx) {
    var frustum = new Frustum(ctx.camera().viewRotationMatrix(), ctx.camera().projectionMatrix());
    frustum.prepare(ctx.camera().eyeX(), ctx.camera().eyeY(), ctx.camera().eyeZ());
    return frustum;
  }

  static SceneData collectFluid(RenderContext ctx, FluidRenderer fluidRenderer, BlockPos blockPos, BlockState blockState, FluidState fluidState) {
    var collector = new VanillaSubmitCollector(ctx);
    var output = collector.new FluidOutput(blockPos, fluidState);
    fluidRenderer.tesselate(ctx.level(), blockPos, output, blockState, fluidState);
    output.flush();
    return collector.buildScene();
  }

  SceneData buildScene() {
    for (var orderedBuckets : bucketsByOrder.values()) {
      orderedBuckets.flushCustomGeometry(this);
      orderedBuckets.flushNameTags(this);
      orderedBuckets.flushSortedModelDraws();
    }

    var sceneData = SceneData.EMPTY;
    for (var orderedBuckets : bucketsByOrder.values()) {
      sceneData = sceneData.merge(orderedBuckets.build());
    }
    return sceneData.withOrigin(origin);
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

  private float poseOriginDistanceSq(Matrix4fc pose) {
    var position = pose.transformPosition(new Vector3f());
    var dx = (float) (position.x() - (ctx.camera().eyeX() - origin.x));
    var dy = (float) (position.y() - (ctx.camera().eyeY() - origin.y));
    var dz = (float) (position.z() - (ctx.camera().eyeZ() - origin.z));
    return dx * dx + dy * dy + dz * dz;
  }

  private FeatureStage stageForRenderType(@Nullable RenderType renderType, FeatureStage solidStage, FeatureStage translucentStage) {
    return renderType != null && renderType.hasBlending() ? translucentStage : solidStage;
  }

  private void addRenderTypeQuad(RenderQuad quad, @Nullable RenderType renderType) {
    if (renderType != null && renderType.isOutline()) {
      builder().addOutline(quad);
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
    cameraState.projectionMatrix = new Matrix4f(ctx.camera().rasterProjectionMatrix());
    cameraState.viewRotationMatrix = new Matrix4f(ctx.camera().viewRotationMatrix());
    cameraState.depthFar = ctx.camera().farPlane();
    cameraState.entityRenderState = new CameraEntityRenderState();
    return cameraState;
  }

  @Override
  public OrderedSubmitNodeCollector order(int order) {
    return buckets == bucketsByOrder.get(order) ? this : new VanillaSubmitCollector(ctx, bucketsByOrder, sortGroups, order, guiLighting, origin);
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
        renderType.primitiveTopology(),
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
        var u0 = -x0 / 2.0F / radius + 0.5F;
        var u1 = -x1 / 2.0F / radius + 0.5F;
        var v0 = -z0 / 2.0F / radius + 0.5F;
        var v1 = -z1 / 2.0F / radius + 0.5F;
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
      var distanceToCameraSq = poseOriginDistanceSq(pose);
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
      if (outlineColor != 0) {
        capturePreparedText(poseStack.last().pose(), font().prepare8xTextOutline(text, x, y, outlineColor), Font.DisplayMode.NORMAL, light);
      }
      capturePreparedText(
        poseStack.last().pose(),
        font().prepareText(text, x, y, color, outlineColor == 0 && shadow, false, outlineColor == 0 ? backgroundColor : 0),
        outlineColor == 0 ? displayMode : Font.DisplayMode.POLYGON_OFFSET,
        light
      );
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
      var renderType = Sheets.cutoutBlockItemSheet();
      var alphaMode = RendererAssets.AlphaMode.CUTOUT;
      var consumer = new CapturingVertexConsumer(
        pose.pose(),
        renderType.primitiveTopology(),
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
        renderType.primitiveTopology(),
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
    if (renderType == RenderTypes.waterMask()) {
      withStage(FeatureStage.WATER_MASK, () -> captureModelSubmit(model, state, poseStack, renderType,
        light, overlay, color, sprite, outlineColor, crumblingOverlay));
      return;
    }
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
        buckets.translucentModelDraws.submit(new SortedScene(poseOriginDistanceSq(poseStack.last().pose()), scene));
      }
      return;
    }

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
    buckets.solidModelDraws.computeIfAbsent(renderType, _ -> SceneData.builder()).addAll(scene);
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
  public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int color) {
    var minecraft = Minecraft.getInstance();
    var blockState = movingBlockRenderState.blockState;
    var model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
    var ambientOcclusion = minecraft.options.ambientOcclusion().get();
    var cutoutLeaves = minecraft.options.cutoutLeaves().get();
    var stage = model.hasMaterialFlag(1) ? FeatureStage.TRANSLUCENT_BLOCK : FeatureStage.SOLID_BLOCK;
    withStage(stage, () -> {
      var basePose = poseStack.last().copy();
      var consumers = new LinkedHashMap<RenderType, Map<TextureAtlasSprite, CapturingVertexConsumer>>();
      BlockQuadOutput output = (x, y, z, quad, instance) -> {
        var materialInfo = quad.materialInfo();
        var layer = materialInfo != null ? materialInfo.layer() : ChunkSectionLayer.SOLID;
        putMovingBlockQuad(basePose, consumers, x, y, z, quad, instance, layer, color);
      };
      BlockQuadOutput solidOutput = (x, y, z, quad, instance) -> putMovingBlockQuad(basePose, consumers, x, y, z, quad, instance, ChunkSectionLayer.SOLID, color);
      var blockOutput = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? solidOutput : output;
      var blockRenderer = new ModelBlockRenderer(ambientOcclusion, false, minecraft.getBlockColors());
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
      consumers.values().forEach(bySprite -> bySprite.values().forEach(CapturingVertexConsumer::flush));
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
            appendBakedQuad(quad, poseStack.last(), renderType, 0xFFFFFFFF, tints, light, overlay);
            appendBakedQuadOutline(quad, poseStack.last(), renderType, outlineColor, overlay);
          }
        }
        for (var quad : part.getQuads(null)) {
          appendBakedQuad(quad, poseStack.last(), renderType, 0xFFFFFFFF, tints, light, overlay);
          appendBakedQuadOutline(quad, poseStack.last(), renderType, outlineColor, overlay);
        }
      }
    });
  }

  public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel blockStateModel, long seed, int progress) {
    var parts = new ArrayList<BlockStateModelPart>();
    blockStateModel.collectParts(RandomSource.create(seed), parts);
    submitBreakingBlockModel(poseStack, parts, progress);
  }

  @Override
  public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    withStage(FeatureStage.TRANSLUCENT_BLOCK, () -> {
      if (progress < 0 || progress >= ModelBakery.DESTROY_TYPES.size() || parts.isEmpty()) {
        return;
      }

      var renderType = ModelBakery.DESTROY_TYPES.get(progress);
      var texture = textureFromRenderType(renderType);
      var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
      var consumer = new CapturingVertexConsumer(
        new Matrix4f(),
        renderType.primitiveTopology(),
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
  public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float lineWidth, boolean expanded) {
    if (shape.isEmpty()) {
      return;
    }

    withStage(stageForRenderType(renderType, FeatureStage.SOLID_CUSTOM, FeatureStage.TRANSLUCENT_CUSTOM), () -> {
      var pose = poseStack.last().copy();
      var consumer = captureConsumer(pose, renderType, color);
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
        appendBakedQuad(quad, poseStack.last(), itemRenderType, 0xFFFFFFFF, tints, light, overlay);
        appendBakedQuadOutline(quad, poseStack.last(), itemRenderType, outlineColor, overlay);
        if (foilType != ItemStackRenderState.FoilType.NONE) {
          appendBakedQuadGlint(quad, poseStack.last(), displayContext, foilType);
        }
      }
    });
  }

  @Override
  public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
    var stage = stageForRenderType(renderType, FeatureStage.SOLID_CUSTOM, FeatureStage.TRANSLUCENT_CUSTOM);
    buckets.customGeometry.computeIfAbsent(stage, _ -> new SimpleFeatureRenderPhase())
      .submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer));
  }

  private void captureCustomGeometry(FeatureBuckets target, FeatureStage stage, CustomFeatureRenderer.Submit submit) {
    withBucketStage(target, stage, () -> {
      var renderType = submit.renderType();
      var texture = textureFromRenderType(renderType);
      var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
      var consumer = new CapturingVertexConsumer(submit.pose(), renderType.primitiveTopology(), texture,
        alphaMode, alphaCutoutThreshold(renderType, alphaMode), renderType, null, null, true);
      submit.customGeometryRenderer().render(submit.pose(), consumer);
      consumer.flush();
    });
  }

  @Override
  public void submitQuadParticleGroup(QuadParticleRenderState quadParticles) {
    if (quadParticles.isEmpty()) {
      return;
    }

    var solidLayers = new IdentityHashMap<SingleQuadParticle.Layer, QuadParticleRenderState.Storage>();
    var translucentLayers = new IdentityHashMap<SingleQuadParticle.Layer, QuadParticleRenderState.Storage>();
    quadParticles.particles.forEach((layer, storage) ->
      (layer.translucent() ? translucentLayers : solidLayers).put(layer, storage));
    for (var layers : List.of(solidLayers, translucentLayers)) {
      layers.forEach(this::submitParticleLayer);
    }
  }

  private void submitParticleLayer(SingleQuadParticle.Layer layer, QuadParticleRenderState.Storage storage) {
    var texture = assets.textureAtlas(layer.textureAtlasLocation());
    var alphaMode = layer.translucent() ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.CUTOUT;
    var stage = layer.translucent() ? FeatureStage.TRANSLUCENT_PARTICLE : FeatureStage.SOLID_PARTICLE;
    withStage(stage, () -> {
      storage.forEachParticle((x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, lightCoords) -> {
        var rotation = new Quaternionf(qx, qy, qz, qw);
        var worldX = x + (float) (ctx.camera().eyeX() - origin.x);
        var worldY = y + (float) (ctx.camera().eyeY() - origin.y);
        var worldZ = z + (float) (ctx.camera().eyeZ() - origin.z);
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
            0xFFFFFFFF,
            true,
            0.0F,
            RenderMaterial.ONE_TENTH_ALPHA_CUTOUT_THRESHOLD
          )
          .withPipelineState(layer.pipeline())
          .withSortOnUpload(false);
        var uv = new float[]{u1, v1, u1, v0, u0, v0, u0, v1};
        var litColor = lightColor(lightCoords, 0, null);
        var renderVertices = new RenderVertex[4];
        for (var i = 0; i < renderVertices.length; i++) {
          var vertex = vertices[i];
          renderVertices[i] = new RenderVertex(vertex.x, vertex.y, vertex.z, uv[i * 2], uv[i * 2 + 1], color)
            .withLightColor(litColor);
        }
        var quad = new RenderQuad(renderVertices[0], renderVertices[1], renderVertices[2], renderVertices[3], material);
        if (layer.translucent()) {
          builder().addTranslucentParticle(quad);
        } else {
          builder().add(quad);
        }
      });
    });
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

    withStage(FeatureStage.TRANSLUCENT_CUSTOM, () -> {
      var consumer = captureConsumer(new Matrix4f(), RenderTypes.debugFilledBox(), 0xFFFFFFFF);
      for (var quad : group.quads()) {
        addGizmoVertex(consumer, quad.a(), quad.color());
        addGizmoVertex(consumer, quad.b(), quad.color());
        addGizmoVertex(consumer, quad.c(), quad.color());
        addGizmoVertex(consumer, quad.d(), quad.color());
      }
      consumer.flush();
    });
  }

  private void submitGizmoTriangleFans(DrawableGizmoPrimitives.Group group) {
    if (group.triangleFans().isEmpty()) {
      return;
    }

    withStage(FeatureStage.TRANSLUCENT_CUSTOM, () -> {
      for (var triangleFan : group.triangleFans()) {
        var points = triangleFan.points();
        if (points.length < 3) {
          continue;
        }

        var consumer = captureConsumer(new Matrix4f(), RenderTypes.debugTriangleFan(), triangleFan.color());
        for (var point : points) {
          addGizmoVertex(consumer, point, triangleFan.color());
        }
        consumer.flush();
      }
    });
  }

  private void submitGizmoLines(DrawableGizmoPrimitives.Group group) {
    if (group.lines().isEmpty()) {
      return;
    }

    var renderType = group.opaque() ? RenderTypes.lines() : RenderTypes.linesTranslucent();
    withStage(stageForRenderType(renderType, FeatureStage.SOLID_CUSTOM, FeatureStage.TRANSLUCENT_CUSTOM), () -> {
      var consumer = captureConsumer(new Matrix4f(), renderType, 0xFFFFFFFF);
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

        addGizmoLineVertex(consumer, line.start(), line.color(), direction, line.width());
        addGizmoLineVertex(consumer, line.end(), line.color(), direction, line.width());
      }
      consumer.flush();
    });
  }

  private void submitGizmoTexts(DrawableGizmoPrimitives.Group group, @Nullable CameraRenderState cameraRenderState) {
    if (group.texts().isEmpty() || cameraRenderState == null || !cameraRenderState.initialized) {
      return;
    }

    withStage(FeatureStage.TRANSLUCENT_TEXT, () -> {
      var poseStack = new PoseStack();
      var font = font();
      for (var text : group.texts()) {
        var style = text.style();
        poseStack.pushPose();
        try {
          poseStack.translate(text.pos().x(), text.pos().y(), text.pos().z());
          poseStack.mulPose(cameraRenderState.orientation);
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
    });
  }

  private void submitGizmoPoints(DrawableGizmoPrimitives.Group group) {
    if (group.points().isEmpty()) {
      return;
    }

    withStage(FeatureStage.SOLID_CUSTOM, () -> {
      var consumer = captureConsumer(new Matrix4f(), RenderTypes.debugPoint(), 0xFFFFFFFF);
      for (var point : group.points()) {
        addGizmoVertex(consumer, point.pos(), point.color()).setLineWidth(point.size());
      }
      consumer.flush();
    });
  }

  private CapturingVertexConsumer captureConsumer(PoseStack.Pose pose, RenderType renderType, int color) {
    var texture = textureFromRenderType(renderType);
    var alphaMode = alphaMode(renderType, texture, color);
    return new CapturingVertexConsumer(
      pose,
      renderType.primitiveTopology(),
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      renderType,
      null,
      null,
      false
    );
  }

  private CapturingVertexConsumer captureConsumer(Matrix4fc pose, RenderType renderType, int color) {
    var texture = textureFromRenderType(renderType);
    var alphaMode = alphaMode(renderType, texture, color);
    return new CapturingVertexConsumer(
      pose,
      renderType.primitiveTopology(),
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      renderType,
      null
    );
  }

  private VertexConsumer addGizmoVertex(VertexConsumer consumer, Vec3 position, int color) {
    return consumer
      .addVertex((float) position.x(), (float) position.y(), (float) position.z())
      .setColor(color);
  }

  private void addGizmoLineVertex(VertexConsumer consumer, Vec3 position, int color, Vector3f direction, float width) {
    consumer
      .addVertex((float) position.x(), (float) position.y(), (float) position.z())
      .setColor(color)
      .setNormal(direction.x(), direction.y(), direction.z())
      .setLineWidth(width);
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
    Map<RenderType, Map<TextureAtlasSprite, CapturingVertexConsumer>> consumers,
    float x,
    float y,
    float z,
    BakedQuad quad,
    QuadInstance instance,
    ChunkSectionLayer layer,
    int color
  ) {
    var pose = basePose.copy();
    pose.translate(x, y, z);
    var renderType = movingBlockRenderType(layer);
    if (color != 0 && renderType.outline().isPresent()) {
      instance.setColor(color);
      renderType = renderType.outline().orElseThrow();
    }
    movingBlockConsumer(consumers, renderType, quad.materialInfo().sprite()).putBakedQuad(pose, quad, instance);
  }

  private CapturingVertexConsumer movingBlockConsumer(Map<RenderType, Map<TextureAtlasSprite, CapturingVertexConsumer>> consumers,
                                                       RenderType renderType, TextureAtlasSprite sprite) {
    return consumers.computeIfAbsent(renderType, _ -> new LinkedHashMap<>()).computeIfAbsent(sprite, _ -> {
      var texture = textureForSprite(sprite, renderType);
      var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
      return new CapturingVertexConsumer(
        new Matrix4f(), renderType.primitiveTopology(), texture, alphaMode,
        alphaCutoutThreshold(renderType, alphaMode), renderType, null
      );
    });
  }

  private RendererAssets.TextureImage textureForSprite(TextureAtlasSprite sprite, @Nullable RenderType renderType) {
    var binding = renderType == null ? null : renderType.state.textures.get("Sampler0");
    var sampler = binding == null ? null : RendererAssets.sampler(binding.sampler());
    return sampler != null && sampler.getMaxLod().orElse(Double.POSITIVE_INFINITY) > 0
      ? assets.terrainTexture(sprite).withStandardSampling() : assets.renderTexture(sprite.atlasLocation());
  }

  private static RenderType movingBlockRenderType(ChunkSectionLayer layer) {
    return switch (layer) {
      case SOLID -> RenderTypes.solidMovingBlock();
      case CUTOUT -> RenderTypes.cutoutMovingBlock();
      case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
    };
  }

  private static void putQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, VertexConsumer consumer) {
    putQuad(pose, quad, instance, consumer, 0xFFFFFFFF);
  }

  private static void putQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, VertexConsumer consumer, int color) {
    if (quad == null || quad.materialInfo() == null) {
      return;
    }

    instance.setColor(color);
    consumer.putBakedQuad(pose, quad, instance);
  }

  private void captureRenderedGeometry(
    RenderType renderType,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    float alphaCutoutThreshold,
    @Nullable TextureAtlasSprite sprite,
    @Nullable RenderMaterial materialOverride,
    Consumer<VertexConsumer> renderer
  ) {
    // Model UVs are local to the supplied sprite, so sample its pixels directly.
    // This also preserves animated sprite frames instead of freezing the atlas copy.
    if (guiLighting != null && sprite != null) {
      texture = assets.texture(sprite.contents().name());
    }
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      renderType.primitiveTopology(),
      texture,
      alphaMode,
      alphaCutoutThreshold,
      renderType,
      null,
      materialOverride
    );
    renderer.accept(guiLighting != null ? consumer : wrapSprite(consumer, sprite));
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
      outlineRenderType.primitiveTopology(),
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
      renderType.primitiveTopology(),
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
    PoseStack.Pose pose,
    @Nullable RenderType renderType,
    int baseColor,
    @Nullable int[] tints,
    int light,
    int overlay
  ) {
    var captured = captureBakedQuad(quad, pose, renderType);
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
    var shade = directionalLight(captured.normal(), effectiveRenderType, FaceLighting.FRONT);
    renderQuad = new RenderQuad(renderQuad.v0().withShade(shade), renderQuad.v1().withShade(shade),
      renderQuad.v2().withShade(shade), renderQuad.v3().withShade(shade), renderQuad.material());
    addRenderTypeQuad(withRenderState(withOverlay(renderQuad, effectiveRenderType, overlay), effectiveRenderType), effectiveRenderType);
  }

  private void appendBakedQuadOutline(BakedQuad quad, PoseStack.Pose pose, @Nullable RenderType renderType, int outlineColor, int overlay) {
    if (outlineColor == 0) {
      return;
    }

    var captured = captureBakedQuad(quad, pose, renderType);
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
    ItemDisplayContext displayContext,
    ItemStackRenderState.FoilType foilType
  ) {
    if (quad == null || quad.materialInfo() == null) {
      return;
    }

    var glintTexture = glintTexture();
    var glintRenderType = RenderTypes.glint();
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      glintRenderType.primitiveTopology(),
      glintTexture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      alphaCutoutThreshold(glintRenderType, RendererAssets.AlphaMode.TRANSLUCENT),
      glintRenderType,
      null,
      RenderMaterial.create(glintTexture, RendererAssets.AlphaMode.TRANSLUCENT, 0xFFFFFFFF, false, 0)
        .withRenderType(glintRenderType, sortGroups.group(glintRenderType))
    );
    var output = foilType == ItemStackRenderState.FoilType.SPECIAL
      ? new SheetedDecalTextureGenerator(consumer, specialFoilDecalPose(displayContext, pose), 0.0078125F)
      : consumer;
    var instance = new QuadInstance();
    putQuad(pose, quad, instance, output);
    consumer.flush();
  }

  @Nullable
  private CapturedBakedQuad captureBakedQuad(BakedQuad quad, PoseStack.Pose pose, @Nullable RenderType renderType) {
    if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null || quad.materialInfo().sprite().contents() == null) {
      return null;
    }

    var sprite = quad.materialInfo().sprite();
    // Keep world-item UVs in atlas space through interpolation, as in the native shader.
    var texture = guiLighting == null ? textureForSprite(sprite, renderType != null ? renderType : quad.materialInfo().itemRenderType()) : assets.texture(sprite.contents().name());
    var vertices = new Vector3f[4];
    var uv = new float[8];
    var poseMatrix = pose.pose();
    for (var i = 0; i < 4; i++) {
      Vector3fc position = quad.position(i);
      vertices[i] = poseMatrix.transformPosition(position, new Vector3f());
      var packedUv = quad.packedUV(i);
      uv[i * 2] = guiLighting == null ? UVPair.unpackU(packedUv) : BakedQuadUv.localU(sprite, packedUv);
      uv[i * 2 + 1] = guiLighting == null ? UVPair.unpackV(packedUv) : BakedQuadUv.localV(sprite, packedUv);
    }

    return new CapturedBakedQuad(vertices, uv, texture, pose.transformNormal(quad.direction().getUnitVec3f(), new Vector3f()));
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
    capturePreparedText(
      pose,
      font().prepareText(text.getVisualOrderText(), x, y, color, false, false, backgroundColor),
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
    var texture = RendererRuntimeTextureMirror.texture(renderable.textureView().texture());
    if (texture == null) {
      texture = textureFromRenderType(renderType);
    }

    var alphaMode = alphaMode(renderType, texture, 0xFFFFFFFF);
    var consumer = new CapturingVertexConsumer(
      new Matrix4f(),
      renderType.primitiveTopology(),
      texture,
      alphaMode,
      alphaCutoutThreshold(renderType, alphaMode),
      renderType,
      null
    );
    renderable.render(pose, consumer, light, false);
    consumer.flush();
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
    float alphaCutoutThreshold
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
    var opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
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
    return new RenderVertex(vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v(), vertex.color(), overlayColor, vertex.shade(), vertex.lightColor(), vertex.colorSource(), vertex.fragmentLightColor());
  }

  private RenderQuad withMaterial(RenderQuad quad, RenderMaterial material) {
    return new RenderQuad(quad.v0(), quad.v1(), quad.v2(), quad.v3(), material);
  }

  private boolean doubleSided(@Nullable RenderType renderType) {
    return renderType != null && !renderType.pipeline().isCull();
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

  private RendererAssets.TextureImage glintTexture() {
    return assets.texture(ENCHANTED_GLINT_ITEM);
  }

  private float alphaCutoutThreshold(@Nullable RenderType renderType, RendererAssets.AlphaMode alphaMode) {
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

    return VanillaLightmap.color(ctx, lightCoords, emission);
  }

  private float directionalLight(Vector3f normal, @Nullable RenderType renderType, FaceLighting faceLighting) {
    if (!usesDirectionalLighting(renderType) || normal.lengthSquared() <= 1.0E-8F) {
      return 1.0F;
    }
    // Vanilla submits normals as signed normalized bytes; the shader does not normalize them again.
    var unitNormal = new Vector3f(packedNormalComponent(normal.x), packedNormalComponent(normal.y), packedNormalComponent(normal.z));
    if (faceLighting == FaceLighting.BACK) {
      unitNormal.negate();
    }
    var light0 = Math.max(0.0F, shaderDot(levelLight0(), unitNormal));
    var light1 = Math.max(0.0F, shaderDot(levelLight1(), unitNormal));
    return Math.min(1.0F, (light0 + light1) * 0.6F + 0.4F);
  }

  private static float shaderDot(Vector3f left, Vector3f right) {
    return (left.z * right.z + left.y * right.y) + left.x * right.x;
  }

  private static float packedNormalComponent(float component) {
    return (int) (Math.clamp(component, -1.0F, 1.0F) * 127.0F) * (1.0F / 127.0F);
  }

  private Vector3f levelLight0() {
    if (guiLighting != null) {
      return guiLighting.light0();
    }
    return cardinalLightType() == CardinalLighting.Type.NETHER ? NETHER_LEVEL_LIGHT_0 : LEVEL_LIGHT_0;
  }

  private Vector3f levelLight1() {
    if (guiLighting != null) {
      return guiLighting.light1();
    }
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
    if (dissolveMaskTexture != null) {
      material = material.withDissolveMaskTexture(dissolveMaskTexture);
    }

    var secondaryTexture = textureFromSampler(renderType, "Sampler1");
    return secondaryTexture != null ? material.withSecondaryTexture(secondaryTexture) : material;
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

    return renderTexture(binding);
  }

  private boolean usesLightmap(@Nullable RenderType renderType) {
    if (renderType == null) {
      return true;
    }
    var defines = shaderDefines(renderType);
    return renderType.state.useLightmap && !defines.contains("EMISSIVE") && !defines.contains("IS_SEE_THROUGH") && !defines.contains("IS_GUI");
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
      var sampler0 = state.textures.get("Sampler0");
      return sampler0 != null ? renderTexture(sampler0) : assets.renderTexture(sampler0Location);
    }

    for (var binding : state.textures.values()) {
      if (binding.location() == null) {
        continue;
      }
      return renderTexture(binding);
    }

    return WHITE_TEXTURE;
  }

  @Nullable
  private RendererAssets.TextureImage textureFromSampler(@Nullable RenderType renderType, String samplerName) {
    if (renderType == null) {
      return null;
    }

    RenderSetup state = renderType.state;
    if (state == null || state.textures == null) {
      return null;
    }

    var binding = state.textures.get(samplerName);
    if (binding == null || binding.location() == null) {
      return null;
    }

    return renderTexture(binding);
  }

  private RendererAssets.TextureImage renderTexture(RenderSetup.TextureBinding binding) {
    return RendererAssets.withSampler(assets.renderTexture(binding.location()), binding.sampler());
  }

  private static RendererAssets.TextureImage createSolidTexture(int argb) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, argb);
    return RendererAssets.TextureImage.from(image, null);
  }

  private record CapturedBakedQuad(Vector3f[] vertices, float[] uv, RendererAssets.TextureImage texture, Vector3f normal) {
  }

  private final class CapturingVertexConsumer implements VertexConsumer {
    private final Matrix4fc pose;
    @Nullable
    private final PoseStack.Pose normalPose;
    private final PrimitiveTopology mode;
    private final RendererAssets.TextureImage texture;
    private final RendererAssets.AlphaMode alphaMode;
    private final float alphaCutoutThreshold;
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
      PrimitiveTopology mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      float alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState
    ) {
      this(pose, mode, texture, alphaMode, alphaCutoutThreshold, renderType, depthStencilState, null);
    }

    private CapturingVertexConsumer(
      PoseStack.Pose pose,
      PrimitiveTopology mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      float alphaCutoutThreshold,
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
      PrimitiveTopology mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      float alphaCutoutThreshold,
      @Nullable RenderType renderType,
      @Nullable DepthStencilState depthStencilState,
      @Nullable RenderMaterial materialOverride
    ) {
      this(pose, null, mode, texture, alphaMode, alphaCutoutThreshold, renderType, depthStencilState, materialOverride, false);
    }

    private CapturingVertexConsumer(
      Matrix4fc pose,
      @Nullable PoseStack.Pose normalPose,
      PrimitiveTopology mode,
      RendererAssets.TextureImage texture,
      RendererAssets.AlphaMode alphaMode,
      float alphaCutoutThreshold,
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
                emitTriangle(vertices.get(i), vertices.get(i + 2), vertices.get(i + 1));
              }
            }
          }
          case TRIANGLE_FAN -> {
            for (var i = 1; i + 1 < vertices.size(); i++) {
              emitTriangle(vertices.get(i), vertices.get(i + 1), vertices.getFirst());
            }
          }
          case LINES, DEBUG_LINES, DEBUG_LINE_STRIP -> {
            var stride = mode == PrimitiveTopology.DEBUG_LINE_STRIP ? 1 : 2;
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
        colorA = a.color();
        colorB = a.color();
        colorC = a.color();
        lightA = a.light();
        lightB = a.light();
        lightC = a.light();
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
      if (!isUsableClip(aClip) || !isUsableClip(bClip)) {
        return;
      }

      var aSegmentOffset = lineOffset(aClip, bClip, a.lineWidth());
      var bSegmentOffset = lineOffset(aClip, bClip, b.lineWidth());
      if (!aSegmentOffset.usable() || !bSegmentOffset.usable()) {
        return;
      }

      var aOffset = lineOffset(a, aClip, shaderScale, aSegmentOffset);
      var bOffset = lineOffset(b, bClip, shaderScale, bSegmentOffset);
      var aPlus = expandedLineVertex(a, aClip, aOffset.x(), aOffset.y());
      var aMinus = expandedLineVertex(a, aClip, -aOffset.x(), -aOffset.y());
      var bPlus = expandedLineVertex(b, bClip, bOffset.x(), bOffset.y());
      var bMinus = expandedLineVertex(b, bClip, -bOffset.x(), -bOffset.y());
      addRenderTypeQuad(new RenderQuad(aPlus, aMinus, bPlus, bPlus, material), renderType);
      addRenderTypeQuad(new RenderQuad(bMinus, bPlus, aMinus, aMinus, material), renderType);
    }

    private RenderVertex expandedLineVertex(CapturedVertex vertex, Vector4f clip, float offsetX, float offsetY) {
      var inverseW = 1.0F / clip.w;
      return renderVertex(vertex.position(), new Vector3f(), vertex.u(), vertex.v(), vertex.color(), vertex.light(),
        vertex.overlayColor(), false, FaceLighting.FRONT).withClipPosition(
        (clip.x * inverseW + offsetX) * clip.w,
        (clip.y * inverseW + offsetY) * clip.w,
        clip.z * inverseW * clip.w,
        clip.w);
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
      if (usesPerFaceLighting(shadingRenderType()) && material.cullMode() == RenderMaterial.CullMode.NONE) {
        var frontMaterial = material.withCullMode(RenderMaterial.CullMode.BACK);
        var backMaterial = material.withCullMode(RenderMaterial.CullMode.FRONT);
        addRenderTypeQuad(renderQuad(positions, normals, colors, uv, lights, overlayColors, applyOverlay, frontMaterial, FaceLighting.FRONT, 0, 1, 2, 3), renderType);
        addRenderTypeQuad(renderQuad(positions, normals, colors, uv, lights, overlayColors, applyOverlay, backMaterial, FaceLighting.BACK, 0, 1, 2, 3), renderType);
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
      if (material.fogMode() == RenderMaterial.FogMode.RGB_FADE) {
        material = material.withGlintAlpha(Minecraft.getInstance().options.glintStrength().get().floatValue());
      }
      return material;
    }

    @Nullable
    private RenderType shadingRenderType() {
      return materialOverride == null ? renderType : null;
    }

    private Vector4f clipPosition(Vector3f position, float viewScale) {
      var transform = new Matrix4f(ctx.camera().rasterProjectionMatrix())
        .scale(viewScale, viewScale, viewScale).mul(ctx.camera().viewRotationMatrix());
      var x = (float) (position.x() - (ctx.camera().eyeX() - origin.x));
      var y = (float) (position.y() - (ctx.camera().eyeY() - origin.y));
      var z = (float) (position.z() - (ctx.camera().eyeZ() - origin.z));
      return new Vector4f(
        transform.m00() * x + (transform.m10() * y + (transform.m20() * z + transform.m30())),
        transform.m01() * x + (transform.m11() * y + (transform.m21() * z + transform.m31())),
        transform.m02() * x + (transform.m12() * y + (transform.m22() * z + transform.m32())),
        transform.m03() * x + (transform.m13() * y + (transform.m23() * z + transform.m33())));
    }

    private Matrix4f clipToWorld(float viewScale) {
      var view = ctx.camera().viewRotationMatrix();
      view.translate((float) (origin.x - ctx.camera().eyeX()), (float) (origin.y - ctx.camera().eyeY()), (float) (origin.z - ctx.camera().eyeZ()));
      return new Matrix4f(ctx.camera().rasterProjectionMatrix())
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
        && clip.z >= -epsilon
        && clip.z <= clip.w + epsilon;
    }

    @Nullable
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

      var offsetX = -dy * (1.0F / length) * lineWidth / ctx.camera().width();
      var offsetY = dx * (1.0F / length) * lineWidth / ctx.camera().height();
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
      if (renderType != null && renderType.pipeline().getFragmentShader().getPath().equals("core/glint")) {
        return new RenderVertex(position.x(), position.y(), position.z(), u, v, 0xFFFFFFFF);
      }
      if (usesFlatVertexColor(renderType)) {
        return new RenderVertex(position.x(), position.y(), position.z(), u, v, color)
          .withLightColor(lightColor(light, 0, renderType));
      }
      var shadingRenderType = shadingRenderType();
      return new RenderVertex(
        position.x(), position.y(), position.z(), u, v, color,
        applyOverlay ? overlayColor : RenderVertex.NO_OVERLAY_COLOR,
        directionalLight(normal, shadingRenderType, faceLighting)
      ).withFragmentLightColor(materialOverride == null ? lightColor(light, 0, shadingRenderType) : 0xFFFFFFFF);
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
          unpackPackedNormalComponent(normal.x()),
          unpackPackedNormalComponent(normal.y()),
          unpackPackedNormalComponent(normal.z())
        ));
        vertices.set(vertices.size() - 1, current);
      }
      return this;
    }

    private float unpackPackedNormalComponent(float component) {
      return packedNormalComponent(component) / PACKED_NORMAL_SCALE;
    }

    private byte packedNormalComponent(float component) {
      return (byte) ((int) (Mth.clamp(component, -1.0F, 1.0F) * PACKED_NORMAL_SCALE) & 0xFF);
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
    return renderType != null && renderType.state.useOverlay && !shaderDefines(renderType).contains("NO_OVERLAY");
  }

  private static Set<String> shaderDefines(RenderType renderType) {
    return renderType.pipeline().getShaderDefines().flags();
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
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      delegate.setLineWidth(width);
      return this;
    }
  }

  private final class FluidOutput implements FluidRenderer.Output {
    private final LinkedHashMap<ChunkSectionLayer, CapturingVertexConsumer> consumers = new LinkedHashMap<>();
    private final RendererAssets.TextureImage texture = assets.textureAtlas(TextureAtlas.LOCATION_BLOCKS);

    private final List<TextureAtlasSprite> sprites;

    private FluidOutput(BlockPos blockPos, FluidState fluidState) {
      var model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
      this.sprites = new ArrayList<>();
      sprites.add(model.stillMaterial().sprite());
      sprites.add(model.flowingMaterial().sprite());
      if (model.overlayMaterial() != null) {
        sprites.add(model.overlayMaterial().sprite());
      }
    }

    @Override
    public VertexConsumer getBuilder(ChunkSectionLayer layer) {
      return consumers.computeIfAbsent(
        layer,
        currentLayer -> new CapturingVertexConsumer(
          new Matrix4f(),
          PrimitiveTopology.QUADS,
          texture,
          currentLayer.translucent() ? RendererAssets.AlphaMode.TRANSLUCENT : RendererAssets.AlphaMode.OPAQUE,
          currentLayer.translucent() ? RenderMaterial.defaultAlphaCutoutThreshold(RendererAssets.AlphaMode.TRANSLUCENT) : 0,
          null,
          currentLayer.pipeline().getDepthStencilState()
        )
      );
    }

    private void flush() {
      consumers.forEach((layer, consumer) -> {
        var alphaMode = RendererAssets.alphaModeForVanillaLayer(layer);
        for (var i = 0; i + 3 < consumer.vertices.size(); i += 4) {
          var captured = consumer.vertices.subList(i, i + 4);
          var u = 0.0F;
          var v = 0.0F;
          for (var vertex : captured) {
            u += vertex.u() * 0.25F;
            v += vertex.v() * 0.25F;
          }
          var faceTexture = texture;
          for (var sprite : sprites) {
            if (u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1()) {
              faceTexture = assets.terrainTexture(sprite);
              break;
            }
          }
          var material = RenderMaterial.create(faceTexture, alphaMode, 0xFFFFFFFF, false, 0.0F)
            .withPipelineState(layer.pipeline());
          var vertices = new RenderVertex[4];
          for (var j = 0; j < 4; j++) {
            var vertex = captured.get(j);
            vertices[j] = new RenderVertex(vertex.position().x, vertex.position().y, vertex.position().z,
              vertex.u(), vertex.v(), vertex.color())
              .withLightColor(VanillaLightmap.color(ctx, vertex.light(), 0));
          }
          activeBuilder.add(new RenderQuad(vertices[0], vertices[1], vertices[2], vertices[3], material));
        }
      });
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

  }

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

  private record SortedScene(float distanceToCameraSq, SceneData scene) implements TranslucentSubmit {
    @Override
    public FeatureRendererType<? extends TranslucentSubmit> featureType() {
      return ModelFeatureRenderer.TYPE;
    }
  }

  private enum FaceLighting {
    FRONT,
    BACK
  }

  private enum FeatureStage {
    SOLID_MODEL,
    SOLID_FLAME,
    SOLID_LEASH,
    SOLID_ITEM,
    SOLID_BLOCK,
    SOLID_CUSTOM,
    SOLID_PARTICLE,
    TRANSLUCENT_SHADOW,
    TRANSLUCENT_MODEL,
    TRANSLUCENT_NAME_TAG,
    TRANSLUCENT_TEXT,
    TRANSLUCENT_ITEM,
    TRANSLUCENT_BLOCK,
    TRANSLUCENT_CUSTOM,
    WATER_MASK,
    TRANSLUCENT_PARTICLE
  }

  private static final class FeatureBuckets {
    private final EnumMap<FeatureStage, SceneData.Builder> builders = new EnumMap<>(FeatureStage.class);
    private final ArrayList<NameTagDraw> nameTagSeeThrough = new ArrayList<>();
    private final ArrayList<NameTagDraw> nameTagNormal = new ArrayList<>();
    private final Map<RenderType, SceneData.Builder> solidModelDraws = new HashMap<>();
    private final TranslucentFeatureRenderPhase translucentModelDraws = new TranslucentFeatureRenderPhase();
    private final EnumMap<FeatureStage, SimpleFeatureRenderPhase> customGeometry = new EnumMap<>(FeatureStage.class);

    private void flushCustomGeometry(VanillaSubmitCollector collector) {
      customGeometry.forEach((stage, phase) -> phase.sortInto((submit, _) ->
        collector.captureCustomGeometry(this, stage, (CustomFeatureRenderer.Submit) submit)));
    }

    private SceneData.Builder builder(FeatureStage stage) {
      return builders.computeIfAbsent(stage, _ -> SceneData.builder());
    }

    private SceneData build() {
      var sceneData = SceneData.EMPTY;
      for (var stage : FeatureStage.values()) {
        var builder = builders.get(stage);
        if (builder != null) {
          var scene = builder.build();
          sceneData = sceneData.merge(stage.ordinal() >= FeatureStage.TRANSLUCENT_SHADOW.ordinal()
            ? scene.inTranslucentPass() : scene);
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
      var solidTarget = builder(FeatureStage.SOLID_MODEL);
      solidModelDraws.values().forEach(draw -> solidTarget.addAll(draw.build()));
      solidModelDraws.clear();
      if (translucentModelDraws.isEmpty()) {
        return;
      }

      var target = builder(FeatureStage.TRANSLUCENT_MODEL);
      translucentModelDraws.sortInto((submit, _) -> target.addAll(((SortedScene) submit).scene()));
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
