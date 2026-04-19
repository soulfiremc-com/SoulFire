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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Quadrant;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public final class RendererAssets {
  private static final RendererAssets INSTANCE = new RendererAssets();
  private static final TextureImage MISSING_TEXTURE = TextureImage.missing();
  private static final Font DISPLAY_FONT = new Font("SansSerif", Font.BOLD, 18);
  private final ConcurrentMap<BlockState, BlockGeometry> blockGeometryCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<Identifier, BlockStateDefinition> blockStateCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<Identifier, ResolvedModel> modelCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, TextureImage> textureCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<TextKey, TextureImage> textTextureCache = new ConcurrentHashMap<>();

  private RendererAssets() {}

  public static RendererAssets instance() {
    return INSTANCE;
  }

  public BlockGeometry blockGeometry(BlockState blockState) {
    return blockGeometryCache.computeIfAbsent(blockState, this::buildBlockGeometry);
  }

  public FluidGeometry fluidGeometry(FluidState fluidState, BlockPos pos, BlockState blockState) {
    if (fluidState.isEmpty()) {
      return FluidGeometry.EMPTY;
    }

    var texturePath = fluidState.is(FluidTags.LAVA)
      ? Identifier.withDefaultNamespace("block/lava_still")
      : Identifier.withDefaultNamespace("block/water_still");
    var flowTexturePath = fluidState.is(FluidTags.LAVA)
      ? Identifier.withDefaultNamespace("block/lava_flow")
      : Identifier.withDefaultNamespace("block/water_flow");
    var texture = texture(texturePath);
    var flowTexture = texture(flowTexturePath);
    var alphaMode = fluidState.is(FluidTags.WATER) ? AlphaMode.TRANSLUCENT : AlphaMode.OPAQUE;
    var emission = fluidState.is(FluidTags.LAVA) ? 15 : 0;
    return new FluidGeometry(texture, flowTexture, alphaMode, emission, fluidState.getHeight(EmptyBlockGetterProxy.INSTANCE, pos), fluidState.getOwnHeight());
  }

  public ItemRenderModel itemRenderModel(ItemStack itemStack) {
    if (itemStack.isEmpty()) {
      return ItemRenderModel.EMPTY;
    }

    var block = Block.byItem(itemStack.getItem());
    if (block != Blocks.AIR) {
      return new ItemRenderModel(blockGeometry(block.defaultBlockState()), null);
    }

    var itemId = BuiltInRegistries.ITEM.getKey(itemStack.getItem());
    var resolvedModel = resolveModel(itemId.withPrefix("item/"));
    if (resolvedModel != null && !resolvedModel.elements.isEmpty()) {
      var baked = bakeResolvedModel(resolvedModel, BlockRenderContext.forItem(itemStack), 0, 0);
      if (!baked.faces().isEmpty()) {
        return new ItemRenderModel(new BlockGeometry(baked.faces()), null);
      }
    }

    var layer0 = resolvedModel != null ? resolveTextureReference("#layer0", resolvedModel.textures) : null;
    var texture = layer0 != null ? texture(layer0.sprite()) : texture(itemId.withPrefix("item/"));
    return new ItemRenderModel(BlockGeometry.EMPTY, new BillboardTexture(texture, AlphaMode.CUTOUT));
  }

  public TextureImage entityTexture(Entity entity) {
    if (entity instanceof AbstractClientPlayer player) {
      return playerTexture(player);
    }

    try {
      var minecraft = Minecraft.getInstance();
      EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
      @SuppressWarnings({"rawtypes", "unchecked"})
      EntityRenderer rawRenderer = dispatcher.getRenderer(entity);
      if (rawRenderer != null) {
        EntityRenderState renderState = (EntityRenderState) rawRenderer.createRenderState(entity, 1.0F);
        var method = rawRenderer.getClass().getMethod("getTextureLocation", renderState.getClass());
        var textureLocation = method.invoke(rawRenderer, renderState);
        if (textureLocation instanceof Identifier identifier) {
          return texture(identifier);
        }
      }
    } catch (Throwable t) {
      log.debug("Failed to resolve entity texture for {}", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), t);
    }

    return MISSING_TEXTURE;
  }

  public TextureImage texture(ClientAsset.Texture textureAsset) {
    if (textureAsset instanceof ClientAsset.DownloadedTexture downloadedTexture) {
      return remoteTexture(downloadedTexture.url());
    }

    return texture(textureAsset.texturePath());
  }

  public TextureImage texture(Identifier textureLocation) {
    var normalizedPath = normalizeTexturePath(textureLocation);
    return textureCache.computeIfAbsent(normalizedPath.toString(), _ -> loadTexture(normalizedPath));
  }

  public TextureImage remoteTexture(String url) {
    return textureCache.computeIfAbsent("url:" + url, _ -> {
      try {
        var connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(3_000);
        try (var stream = connection.getInputStream()) {
          return TextureImage.from(ImageIO.read(stream), null);
        }
      } catch (Throwable t) {
        log.debug("Failed to download renderer texture {}", url, t);
        return MISSING_TEXTURE;
      }
    });
  }

  public TextureImage mapTexture(byte[] colors) {
    var pixels = new int[colors.length];
    for (var i = 0; i < colors.length; i++) {
      pixels[i] = net.minecraft.world.level.material.MapColor.getColorFromPackedId(colors[i]);
    }

    return new TextureImage(128, 128, 128, 1, 1, false, new int[]{0}, pixels, true);
  }

  public TextureImage textTexture(Component component, int width, int textColor, int backgroundColor) {
    var key = new TextKey(component.getString(), width, textColor, backgroundColor);
    return textTextureCache.computeIfAbsent(key, this::renderTextTexture);
  }

  public int resolveTint(BlockState state, BlockPos pos) {
    if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
      return 0xFFFFFFFF;
    }

    return 0xFFFFFFFF;
  }

  public int resolveTint(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos, BlockState state, int tintIndex) {
    if (tintIndex < 0) {
      return 0xFFFFFFFF;
    }

    var biome = level.getBiome(pos).value();
    var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    if (blockId.contains("water")) {
      return 0xFF000000 | biome.getWaterColor();
    }
    if (blockId.contains("leaves") || blockId.contains("vine")) {
      return 0xFF000000 | biome.getFoliageColor();
    }
    if (blockId.contains("grass") || blockId.contains("fern")) {
      return 0xFF000000 | biome.getGrassColor(pos.getX(), pos.getZ());
    }

    return 0xFFFFFFFF;
  }

  public Matrix4f displayMatrix(Display.RenderState renderState, float partialTick) {
    var transformation = renderState.transformation().get(partialTick);
    return new Matrix4f(transformation.getMatrix());
  }

  public Matrix4f itemDisplayTransform(ItemDisplayContext itemDisplayContext) {
    var matrix = new Matrix4f();
    switch (itemDisplayContext) {
      case GROUND -> matrix.translate(0.0F, 0.0625F, 0.0F).scale(0.5F);
      case FIXED -> matrix.scale(0.75F);
      case GUI -> matrix.rotateY((float) Math.toRadians(180.0)).scale(0.85F);
      case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> matrix.rotateX((float) Math.toRadians(-15.0)).scale(0.7F);
      case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> matrix.rotateX((float) Math.toRadians(-10.0)).scale(0.9F);
      default -> matrix.scale(0.75F);
    }
    return matrix;
  }

  public enum EntityLod {
    NEAR,
    MEDIUM,
    FAR
  }

  public List<GeometryFace> entityModel(Entity entity, TextureImage texture, EntityLod lod) {
    if (lod == EntityLod.FAR) {
      return List.of();
    }

    if (entity instanceof AbstractClientPlayer player) {
      return buildPlayerModel(player, lod);
    }

    var yaw = (float) Math.toRadians(-entity.getYRot());
    var transform = new Matrix4f()
      .translate((float) entity.getX(), (float) entity.getY(), (float) entity.getZ())
      .rotateY(yaw);
    if (entity.getBbHeight() > entity.getBbWidth() * 1.25F) {
      return buildHumanoidApproximation(entity, texture, lod, transform);
    }
    if (entity.getBbWidth() > entity.getBbHeight() * 0.9F) {
      return buildQuadrupedApproximation(entity, texture, lod, transform);
    }
    return buildPrismApproximation(entity, texture, lod, transform);
  }

  private TextureImage playerTexture(AbstractClientPlayer player) {
    try {
      return composePlayerSprite(this.texture((ClientAsset.Texture) player.getSkin().body()), player.getSkin().model().name().equalsIgnoreCase("SLIM"));
    } catch (Throwable t) {
      log.debug("Failed to compose player sprite for {}", player.getUUID(), t);
      return MISSING_TEXTURE;
    }
  }

  private List<GeometryFace> buildPlayerModel(AbstractClientPlayer player, EntityLod lod) {
    var skin = this.texture((ClientAsset.Texture) player.getSkin().body());
    var slim = player.getSkin().model().name().equalsIgnoreCase("SLIM");
    var armWidth = slim ? 0.1875F : 0.25F;
    var faces = new ArrayList<GeometryFace>();
    var yaw = (float) Math.toRadians(-player.getYRot());
    var crouch = player.isCrouching();
    var bodyTransform = new Matrix4f()
      .translate((float) player.getX(), (float) player.getY(), (float) player.getZ())
      .rotateY(yaw);
    if (crouch) {
      bodyTransform.translate(0.0F, -0.12F, 0.0F).rotateX((float) Math.toRadians(12.0F));
    }

    var speed = (float) player.getDeltaMovement().horizontalDistance();
    var swingAmount = Math.min(1.0F, speed * 8.0F + (player.isSprinting() ? 0.25F : 0.0F));
    var swingPhase = player.tickCount * 0.55F;
    var armSwing = (float) Math.sin(swingPhase) * 0.75F * swingAmount;
    var legSwing = (float) Math.sin(swingPhase) * 0.9F * swingAmount;
    var attackAnim = player.getAttackAnim(1.0F);
    var mainArm = player.getMainArm();
    var headYawOffset = Mth.clamp(player.getYHeadRot() - player.getYRot(), -55.0F, 55.0F);
    var headPitch = Mth.clamp(player.getXRot(), -45.0F, 65.0F);

    addCuboid(
      faces,
      -0.25F, 1.25F, -0.25F,
      0.25F, 1.75F, 0.25F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.25F, 0.0F, 0.375F, 0.125F),
        new UVRect(0.125F, 0.0F, 0.25F, 0.125F),
        new UVRect(0.375F, 0.125F, 0.5F, 0.25F),
        new UVRect(0.125F, 0.125F, 0.25F, 0.25F),
        new UVRect(0.0F, 0.125F, 0.125F, 0.25F),
        new UVRect(0.25F, 0.125F, 0.375F, 0.25F)
      ),
      new Matrix4f(bodyTransform)
        .translate(0.0F, 1.5F, 0.0F)
        .rotateY((float) Math.toRadians(-headYawOffset))
        .rotateX((float) Math.toRadians(headPitch))
        .translate(0.0F, -1.5F, 0.0F),
      true
    );

    addCuboid(
      faces,
      -0.25F, 0.5F, -0.125F,
      0.25F, 1.25F, 0.125F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.25F, 0.25F, 0.3125F, 0.3125F),
        new UVRect(0.125F, 0.25F, 0.1875F, 0.3125F),
        new UVRect(0.5F, 0.25F, 0.625F, 0.4375F),
        new UVRect(0.3125F, 0.25F, 0.4375F, 0.4375F),
        new UVRect(0.25F, 0.3125F, 0.3125F, 0.5F),
        new UVRect(0.4375F, 0.3125F, 0.5F, 0.5F)
      ),
      bodyTransform,
      true
    );

    addCuboid(
      faces,
      -0.25F, 0.0F, -0.125F,
      -0.0625F, 0.75F, 0.125F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.0625F, 0.25F, 0.125F, 0.3125F),
        new UVRect(0.0F, 0.25F, 0.0625F, 0.3125F),
        new UVRect(0.1875F, 0.3125F, 0.25F, 0.5F),
        new UVRect(0.0625F, 0.3125F, 0.125F, 0.5F),
        new UVRect(0.0F, 0.3125F, 0.0625F, 0.5F),
        new UVRect(0.125F, 0.3125F, 0.1875F, 0.5F)
      ),
      pivotTransform(bodyTransform, -0.15625F, 0.75F, 0.0F, -legSwing, 0.0F, 0.0F),
      true
    );

    addCuboid(
      faces,
      0.0625F, 0.0F, -0.125F,
      0.25F, 0.75F, 0.125F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.0625F, 0.8125F, 0.125F, 0.875F),
        new UVRect(0.0F, 0.8125F, 0.0625F, 0.875F),
        new UVRect(0.1875F, 0.875F, 0.25F, 1.0F),
        new UVRect(0.0625F, 0.875F, 0.125F, 1.0F),
        new UVRect(0.0F, 0.875F, 0.0625F, 1.0F),
        new UVRect(0.125F, 0.875F, 0.1875F, 1.0F)
      ),
      pivotTransform(bodyTransform, 0.15625F, 0.75F, 0.0F, legSwing, 0.0F, 0.0F),
      true
    );

    var leftArmPitch = -armSwing;
    var rightArmPitch = armSwing;
    if (mainArm == HumanoidArm.RIGHT) {
      rightArmPitch -= attackAnim * 1.25F + (player.isUsingItem() ? 0.45F : 0.0F);
    } else {
      leftArmPitch -= attackAnim * 1.25F + (player.isUsingItem() ? 0.45F : 0.0F);
    }

    addCuboid(
      faces,
      -0.25F - armWidth, 0.5F, -0.125F,
      -0.25F, 1.25F, 0.125F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.6875F, 0.25F, 0.75F, 0.3125F),
        new UVRect(0.625F, 0.25F, 0.6875F, 0.3125F),
        new UVRect(0.8125F, 0.3125F, 0.875F, 0.5F),
        new UVRect(0.6875F, 0.3125F, 0.75F, 0.5F),
        new UVRect(0.625F, 0.3125F, 0.6875F, 0.5F),
        new UVRect(0.75F, 0.3125F, 0.8125F, 0.5F)
      ),
      pivotTransform(bodyTransform, -0.25F, 1.18F, 0.0F, leftArmPitch, 0.0F, 0.0F),
      true
    );

    addCuboid(
      faces,
      0.25F, 0.5F, -0.125F,
      0.25F + armWidth, 1.25F, 0.125F,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.3125F, 0.75F, 0.375F, 0.8125F),
        new UVRect(0.25F, 0.75F, 0.3125F, 0.8125F),
        new UVRect(0.4375F, 0.8125F, 0.5F, 1.0F),
        new UVRect(0.3125F, 0.8125F, 0.375F, 1.0F),
        new UVRect(0.25F, 0.8125F, 0.3125F, 1.0F),
        new UVRect(0.375F, 0.8125F, 0.4375F, 1.0F)
      ),
      pivotTransform(bodyTransform, 0.25F, 1.18F, 0.0F, rightArmPitch, 0.0F, 0.0F),
      true
    );

    if (lod == EntityLod.NEAR) {
      appendPlayerOuterLayers(faces, player, bodyTransform, slim, headYawOffset, headPitch, leftArmPitch, rightArmPitch, legSwing);
      appendPlayerEquipment(faces, player, bodyTransform, slim, leftArmPitch, rightArmPitch);
      appendCape(faces, player, bodyTransform);
    }

    return faces;
  }

  private List<GeometryFace> buildHumanoidApproximation(Entity entity, TextureImage texture, EntityLod lod, Matrix4f transform) {
    var faces = new ArrayList<GeometryFace>();
    var width = Math.max(0.35F, entity.getBbWidth());
    var height = Math.max(1.0F, entity.getBbHeight());
    var headHeight = Math.min(0.38F, height * 0.24F);
    var bodyHeight = height * 0.45F;
    var legHeight = height - bodyHeight - headHeight;
    var torsoWidth = width * 0.7F;
    var legWidth = torsoWidth * 0.38F;
    var armWidth = torsoWidth * 0.28F;

    addCuboid(faces, -torsoWidth * 0.5F, legHeight, -width * 0.22F, torsoWidth * 0.5F, legHeight + bodyHeight, width * 0.22F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    addCuboid(faces, -width * 0.32F, legHeight + bodyHeight, -width * 0.32F, width * 0.32F, height, width * 0.32F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    addCuboid(faces, -torsoWidth * 0.5F - armWidth, legHeight + bodyHeight * 0.1F, -width * 0.18F, -torsoWidth * 0.5F, legHeight + bodyHeight * 0.95F, width * 0.18F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    if (lod == EntityLod.NEAR) {
      addCuboid(faces, torsoWidth * 0.5F, legHeight + bodyHeight * 0.1F, -width * 0.18F, torsoWidth * 0.5F + armWidth, legHeight + bodyHeight * 0.95F, width * 0.18F,
        texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    }
    addCuboid(faces, -legWidth - 0.02F, 0.0F, -width * 0.16F, -0.02F, legHeight, width * 0.16F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    addCuboid(faces, 0.02F, 0.0F, -width * 0.16F, legWidth + 0.02F, legHeight, width * 0.16F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    return faces;
  }

  private List<GeometryFace> buildQuadrupedApproximation(Entity entity, TextureImage texture, EntityLod lod, Matrix4f transform) {
    var faces = new ArrayList<GeometryFace>();
    var width = Math.max(0.5F, entity.getBbWidth());
    var height = Math.max(0.45F, entity.getBbHeight());
    var legHeight = height * 0.4F;
    var bodyMinY = legHeight;
    var bodyMaxY = height * 0.88F;
    var bodyHalfLength = width * 0.48F;
    var bodyHalfWidth = width * 0.26F;
    addCuboid(faces, -bodyHalfWidth, bodyMinY, -bodyHalfLength, bodyHalfWidth, bodyMaxY, bodyHalfLength,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    addCuboid(faces, -bodyHalfWidth * 0.75F, bodyMaxY - height * 0.18F, bodyHalfLength - width * 0.05F, bodyHalfWidth * 0.75F, height, bodyHalfLength + width * 0.18F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    var legHalf = width * 0.08F;
    var legZ = bodyHalfLength * 0.65F;
    var legX = bodyHalfWidth * 0.72F;
    addCuboid(faces, -legX - legHalf, 0.0F, -legZ - legHalf, -legX + legHalf, legHeight, -legZ + legHalf, texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    addCuboid(faces, legX - legHalf, 0.0F, -legZ - legHalf, legX + legHalf, legHeight, -legZ + legHalf, texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    if (lod == EntityLod.NEAR) {
      addCuboid(faces, -legX - legHalf, 0.0F, legZ - legHalf, -legX + legHalf, legHeight, legZ + legHalf, texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
      addCuboid(faces, legX - legHalf, 0.0F, legZ - legHalf, legX + legHalf, legHeight, legZ + legHalf, texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    }
    return faces;
  }

  private List<GeometryFace> buildPrismApproximation(Entity entity, TextureImage texture, EntityLod lod, Matrix4f transform) {
    var faces = new ArrayList<GeometryFace>();
    var width = Math.max(0.35F, entity.getBbWidth() * 0.92F);
    var height = Math.max(0.4F, entity.getBbHeight());
    addCuboid(faces, -width * 0.5F, 0.0F, -width * 0.5F, width * 0.5F, height, width * 0.5F,
      texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    if (lod == EntityLod.NEAR && height > width * 1.2F) {
      addCuboid(faces, -width * 0.35F, height * 0.65F, -width * 0.35F, width * 0.35F, height, width * 0.35F,
        texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
    }
    return faces;
  }

  private void appendPlayerOuterLayers(
    List<GeometryFace> faces,
    AbstractClientPlayer player,
    Matrix4f bodyTransform,
    boolean slim,
    float headYawOffset,
    float headPitch,
    float leftArmPitch,
    float rightArmPitch,
    float legSwing) {

    var skin = this.texture((ClientAsset.Texture) player.getSkin().body());
    var armWidth = slim ? 0.1875F : 0.25F;
    var layerOffset = 0.018F;

    addCuboid(
      faces,
      -0.25F - layerOffset, 1.25F - layerOffset, -0.25F - layerOffset,
      0.25F + layerOffset, 1.75F + layerOffset, 0.25F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.75F, 0.0F, 0.875F, 0.125F),
        new UVRect(0.625F, 0.0F, 0.75F, 0.125F),
        new UVRect(0.875F, 0.125F, 1.0F, 0.25F),
        new UVRect(0.625F, 0.125F, 0.75F, 0.25F),
        new UVRect(0.5F, 0.125F, 0.625F, 0.25F),
        new UVRect(0.75F, 0.125F, 0.875F, 0.25F)
      ),
      new Matrix4f(bodyTransform)
        .translate(0.0F, 1.5F, 0.0F)
        .rotateY((float) Math.toRadians(-headYawOffset))
        .rotateX((float) Math.toRadians(headPitch))
        .translate(0.0F, -1.5F, 0.0F),
      true
    );

    addCuboid(
      faces,
      -0.25F - layerOffset, 0.5F - layerOffset, -0.125F - layerOffset,
      0.25F + layerOffset, 1.25F + layerOffset, 0.125F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      cuboidUvSet(
        new UVRect(0.25F, 0.5F, 0.3125F, 0.5625F),
        new UVRect(0.125F, 0.5F, 0.1875F, 0.5625F),
        new UVRect(0.5F, 0.5F, 0.625F, 0.6875F),
        new UVRect(0.3125F, 0.5F, 0.4375F, 0.6875F),
        new UVRect(0.25F, 0.5625F, 0.3125F, 0.75F),
        new UVRect(0.4375F, 0.5625F, 0.5F, 0.75F)
      ),
      bodyTransform,
      true
    );

    addCuboid(
      faces,
      -0.25F - armWidth - layerOffset, 0.5F - layerOffset, -0.125F - layerOffset,
      -0.25F + layerOffset, 1.25F + layerOffset, 0.125F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      simpleUvSet(),
      pivotTransform(bodyTransform, -0.25F, 1.18F, 0.0F, leftArmPitch, 0.0F, 0.0F),
      true
    );

    addCuboid(
      faces,
      0.25F - layerOffset, 0.5F - layerOffset, -0.125F - layerOffset,
      0.25F + armWidth + layerOffset, 1.25F + layerOffset, 0.125F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      simpleUvSet(),
      pivotTransform(bodyTransform, 0.25F, 1.18F, 0.0F, rightArmPitch, 0.0F, 0.0F),
      true
    );

    addCuboid(
      faces,
      -0.25F - layerOffset, 0.0F - layerOffset, -0.125F - layerOffset,
      -0.0625F + layerOffset, 0.75F + layerOffset, 0.125F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      simpleUvSet(),
      pivotTransform(bodyTransform, -0.15625F, 0.75F, 0.0F, -legSwing, 0.0F, 0.0F),
      true
    );

    addCuboid(
      faces,
      0.0625F - layerOffset, 0.0F - layerOffset, -0.125F - layerOffset,
      0.25F + layerOffset, 0.75F + layerOffset, 0.125F + layerOffset,
      skin,
      AlphaMode.CUTOUT,
      simpleUvSet(),
      pivotTransform(bodyTransform, 0.15625F, 0.75F, 0.0F, legSwing, 0.0F, 0.0F),
      true
    );
  }

  private void appendPlayerEquipment(
    List<GeometryFace> faces,
    AbstractClientPlayer player,
    Matrix4f bodyTransform,
    boolean slim,
    float leftArmPitch,
    float rightArmPitch) {

    appendArmorOverlay(faces, player.getItemBySlot(EquipmentSlot.HEAD), bodyTransform, -0.28F, 1.22F, -0.28F, 0.28F, 1.78F, 0.28F);
    appendArmorOverlay(faces, player.getItemBySlot(EquipmentSlot.CHEST), bodyTransform, -0.29F, 0.48F, -0.15F, 0.29F, 1.27F, 0.15F);
    appendArmorOverlay(faces, player.getItemBySlot(EquipmentSlot.LEGS), bodyTransform, -0.27F, -0.02F, -0.14F, 0.27F, 0.78F, 0.14F);
    appendArmorOverlay(faces, player.getItemBySlot(EquipmentSlot.FEET), bodyTransform, -0.27F, -0.02F, -0.14F, 0.27F, 0.2F, 0.14F);

    var leftHandX = slim ? -0.36F : -0.38F;
    var rightHandX = slim ? 0.36F : 0.38F;
    appendHeldItem(faces, player.getMainHandItem(), pivotTransform(bodyTransform, mainArmTransformX(player, rightHandX, leftHandX), 1.18F, 0.0F, mainArmPitch(player, rightArmPitch, leftArmPitch), 0.0F, 0.0F), player.getMainArm() == HumanoidArm.RIGHT ? 1.0F : -1.0F);
    appendHeldItem(faces, player.getOffhandItem(), pivotTransform(bodyTransform, offArmTransformX(player, rightHandX, leftHandX), 1.18F, 0.0F, offArmPitch(player, rightArmPitch, leftArmPitch), 0.0F, 0.0F), player.getMainArm() == HumanoidArm.RIGHT ? -1.0F : 1.0F);
  }

  private void appendArmorOverlay(List<GeometryFace> faces, ItemStack stack, Matrix4f transform, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    if (stack.isEmpty()) {
      return;
    }

    var texture = representativeTexture(itemRenderModel(stack));
    addCuboid(faces, minX, minY, minZ, maxX, maxY, maxZ, texture, AlphaMode.CUTOUT, simpleUvSet(), transform, true);
  }

  private void appendHeldItem(List<GeometryFace> faces, ItemStack stack, Matrix4f handTransform, float sideSign) {
    if (stack.isEmpty()) {
      return;
    }

    var model = itemRenderModel(stack);
    if (!model.geometry().faces().isEmpty()) {
      var itemTransform = new Matrix4f(handTransform)
        .translate(sideSign * 0.03F, 0.06F, 0.0F)
        .rotateZ((float) Math.toRadians(-18.0F * sideSign))
        .rotateX((float) Math.toRadians(-28.0F))
        .scale(0.22F)
        .translate(-0.5F, 0.0F, -0.5F);
      for (var face : model.geometry().faces()) {
        faces.add(face.transformed(itemTransform));
      }
      return;
    }

    var texture = representativeTexture(model);
    addCuboid(
      faces,
      -0.06F,
      0.0F,
      -0.01F,
      0.06F,
      0.16F,
      0.01F,
      texture,
      AlphaMode.CUTOUT,
      simpleUvSet(),
      new Matrix4f(handTransform)
        .translate(sideSign * 0.03F, 0.06F, 0.0F)
        .rotateZ((float) Math.toRadians(-20.0F * sideSign))
        .rotateX((float) Math.toRadians(-35.0F)),
      false
    );
  }

  private void appendCape(List<GeometryFace> faces, AbstractClientPlayer player, Matrix4f bodyTransform) {
    if (player.getSkin().cape() == null) {
      return;
    }

    var capeTexture = texture((ClientAsset.Texture) player.getSkin().cape());
    var capeTransform = new Matrix4f(bodyTransform)
      .translate(0.0F, 1.22F, 0.14F)
      .rotateX((float) Math.toRadians(12.0F + Math.min(18.0F, player.getDeltaMovement().horizontalDistance() * 200.0F)));
    var p0 = capeTransform.transformPosition(new Vector3f(-0.28F, 0.0F, 0.0F));
    var p1 = capeTransform.transformPosition(new Vector3f(-0.28F, -0.78F, 0.0F));
    var p2 = capeTransform.transformPosition(new Vector3f(0.28F, -0.78F, 0.0F));
    var p3 = capeTransform.transformPosition(new Vector3f(0.28F, 0.0F, 0.0F));
    faces.add(GeometryFace.of(new Vector3f[]{p0, p1, p2, p3}, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F}, capeTexture, AlphaMode.CUTOUT, -1, 0, false));
  }

  private TextureImage representativeTexture(ItemRenderModel model) {
    if (model.billboard() != null) {
      return model.billboard().texture();
    }
    if (!model.geometry().faces().isEmpty()) {
      return model.geometry().faces().getFirst().texture();
    }
    return MISSING_TEXTURE;
  }

  private float mainArmTransformX(AbstractClientPlayer player, float rightX, float leftX) {
    return player.getMainArm() == HumanoidArm.RIGHT ? rightX : leftX;
  }

  private float offArmTransformX(AbstractClientPlayer player, float rightX, float leftX) {
    return player.getMainArm() == HumanoidArm.RIGHT ? leftX : rightX;
  }

  private float mainArmPitch(AbstractClientPlayer player, float rightArmPitch, float leftArmPitch) {
    return player.getMainArm() == HumanoidArm.RIGHT ? rightArmPitch : leftArmPitch;
  }

  private float offArmPitch(AbstractClientPlayer player, float rightArmPitch, float leftArmPitch) {
    return player.getMainArm() == HumanoidArm.RIGHT ? leftArmPitch : rightArmPitch;
  }

  private Matrix4f pivotTransform(Matrix4fc base, float pivotX, float pivotY, float pivotZ, float rotX, float rotY, float rotZ) {
    return new Matrix4f(base)
      .translate(pivotX, pivotY, pivotZ)
      .rotateZ(rotZ)
      .rotateY(rotY)
      .rotateX(rotX)
      .translate(-pivotX, -pivotY, -pivotZ);
  }

  private TextureImage composePlayerSprite(TextureImage skin, boolean slim) {
    if (skin.width() < 64 || skin.height() < 64) {
      return skin;
    }

    var sprite = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
    var graphics = sprite.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    var armWidth = slim ? 3 : 4;
    drawSkinRegion(graphics, skin, 8, 8, 8, 8, 4, 0, 8, 8);
    drawSkinRegion(graphics, skin, 40, 8, 8, 8, 4, 0, 8, 8);
    drawSkinRegion(graphics, skin, 20, 20, 8, 12, 4, 8, 8, 12);
    drawSkinRegion(graphics, skin, 20, 36, 8, 12, 4, 8, 8, 12);
    drawSkinRegion(graphics, skin, 44, 20, armWidth, 12, 0, 8, armWidth, 12);
    drawSkinRegion(graphics, skin, slim ? 47 : 44, 36, armWidth, 12, 0, 8, armWidth, 12);
    drawSkinRegion(graphics, skin, 36, 52, armWidth, 12, 16 - armWidth, 8, armWidth, 12);
    drawSkinRegion(graphics, skin, slim ? 39 : 52, 52, armWidth, 12, 16 - armWidth, 8, armWidth, 12);
    drawSkinRegion(graphics, skin, 4, 20, 4, 12, 4, 20, 4, 12);
    drawSkinRegion(graphics, skin, 4, 36, 4, 12, 4, 20, 4, 12);
    drawSkinRegion(graphics, skin, 20, 52, 4, 12, 8, 20, 4, 12);
    drawSkinRegion(graphics, skin, 4, 52, 4, 12, 8, 20, 4, 12);
    graphics.dispose();
    return TextureImage.from(sprite, null);
  }

  private void drawSkinRegion(Graphics2D graphics, TextureImage skin, int srcX, int srcY, int srcWidth, int srcHeight, int dstX, int dstY, int dstWidth, int dstHeight) {
    graphics.drawImage(
      skin.toBufferedImage(),
      dstX,
      dstY,
      dstX + dstWidth,
      dstY + dstHeight,
      srcX,
      srcY,
      srcX + srcWidth,
      srcY + srcHeight,
      null
    );
  }

  private TextureImage renderTextTexture(TextKey key) {
    var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    var probeGraphics = probe.createGraphics();
    probeGraphics.setFont(DISPLAY_FONT);
    var metrics = probeGraphics.getFontMetrics();
    var lines = wrapText(key.text(), metrics, Math.max(32, key.width()));
    var height = Math.max(metrics.getHeight() * Math.max(1, lines.size()) + 8, 16);
    var width = Math.max(8 + lines.stream().mapToInt(metrics::stringWidth).max().orElse(0), 16);
    probeGraphics.dispose();

    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    graphics.setFont(DISPLAY_FONT);
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    graphics.setColor(new Color(key.backgroundColor(), true));
    graphics.fillRoundRect(0, 0, width, height, 6, 6);
    graphics.setColor(new Color(key.textColor(), true));
    var y = 4 + graphics.getFontMetrics().getAscent();
    for (var line : lines) {
      graphics.drawString(line, 4, y);
      y += graphics.getFontMetrics().getHeight();
    }
    graphics.dispose();
    return TextureImage.from(image, null);
  }

  private List<String> wrapText(String text, FontMetrics metrics, int maxWidth) {
    if (text.isBlank()) {
      return List.of(" ");
    }

    var lines = new ArrayList<String>();
    var current = new StringBuilder();
    for (var part : text.split("\\s+")) {
      if (current.isEmpty()) {
        current.append(part);
        continue;
      }

      var candidate = current + " " + part;
      if (metrics.stringWidth(candidate) <= maxWidth) {
        current.append(" ").append(part);
      } else {
        lines.add(current.toString());
        current = new StringBuilder(part);
      }
    }
    if (!current.isEmpty()) {
      lines.add(current.toString());
    }
    return lines;
  }

  private BlockGeometry buildBlockGeometry(BlockState state) {
    var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    var stateDefinition = blockStateCache.computeIfAbsent(blockId, this::loadBlockStateDefinition);
    var modelReferences = stateDefinition.select(state);
    if (modelReferences.isEmpty()) {
      return fallbackCube(state);
    }

    var context = BlockRenderContext.forBlock(state);
    var bakedFaces = new ArrayList<GeometryFace>();
    for (var modelReference : modelReferences) {
      var resolvedModel = resolveModel(modelReference.model());
      if (resolvedModel == null) {
        continue;
      }

      bakedFaces.addAll(bakeResolvedModel(resolvedModel, context, modelReference.xRotation(), modelReference.yRotation()).faces());
    }

    if (bakedFaces.isEmpty()) {
      return fallbackCube(state);
    }

    return new BlockGeometry(bakedFaces);
  }

  private BlockGeometry fallbackCube(BlockState state) {
    var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    var texture = texture(blockId.withPrefix("block/"));
    if (texture == MISSING_TEXTURE) {
      texture = textureFromParticle(state);
    }

    var faces = new ArrayList<GeometryFace>();
    var alphaMode = chooseAlphaMode(state, texture, blockId.getPath(), false);
    for (var direction : Direction.values()) {
      faces.add(createAxisAlignedFace(direction, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, new UVRect(0.0F, 0.0F, 1.0F, 1.0F), texture, alphaMode, -1, 0, true));
    }
    return new BlockGeometry(faces);
  }

  private TextureImage textureFromParticle(BlockState state) {
    var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    var resolvedModel = resolveModel(blockId.withPrefix("block/"));
    if (resolvedModel == null) {
      return MISSING_TEXTURE;
    }

    var particle = resolveTextureReference("#particle", resolvedModel.textures);
    return particle != null ? texture(particle.sprite()) : MISSING_TEXTURE;
  }

  @Nullable
  private ResolvedModel resolveModel(Identifier modelLocation) {
    var cached = modelCache.get(modelLocation);
    if (cached != null) {
      return cached;
    }

    var loaded = loadModel(modelLocation);
    if (loaded != null) {
      modelCache.putIfAbsent(modelLocation, loaded);
    }
    return loaded;
  }

  private BakedModel bakeResolvedModel(ResolvedModel resolvedModel, BlockRenderContext context, int xRotation, int yRotation) {
    var faces = new ArrayList<GeometryFace>();
    for (var element : resolvedModel.elements) {
      for (var entry : element.faces.entrySet()) {
        var facing = entry.getKey();
        var face = entry.getValue();
        var textureLocation = resolveTextureReference(face.textureRef, resolvedModel.textures);
        var texture = textureLocation != null ? texture(textureLocation.sprite()) : MISSING_TEXTURE;
        var uv = face.uv != null ? face.uv : defaultFaceUv(element.from, element.to, facing);
        var geometryFace = bakeFace(
          element,
          face,
          facing,
          uv,
          texture,
          chooseAlphaMode(
            context.state,
            texture,
            textureLocation != null ? textureLocation.sprite().getPath() : "",
            textureLocation != null && textureLocation.forceTranslucent()
          ),
          xRotation,
          yRotation
        );
        faces.add(geometryFace);
      }
    }
    return new BakedModel(faces);
  }

  private GeometryFace bakeFace(
    ModelElement element,
    FaceSpec face,
    Direction direction,
    UVRect uvRect,
    TextureImage texture,
    AlphaMode alphaMode,
    int xRotation,
    int yRotation) {

    var faceInfo = FaceInfo.fromFacing(direction);
    var from = new Vector3f(element.from).mul(1.0F / 16.0F);
    var to = new Vector3f(element.to).mul(1.0F / 16.0F);
    var vertices = new Vector3f[4];
    var uv = new float[8];
    var modelRotation = new Matrix4f()
      .translate(0.5F, 0.5F, 0.5F)
      .rotateX((float) Math.toRadians(xRotation))
      .rotateY((float) Math.toRadians(-yRotation))
      .translate(-0.5F, -0.5F, -0.5F);
    var faceRotation = Quadrant.parseJson(face.rotation);

    for (var i = 0; i < 4; i++) {
      var vertexInfo = faceInfo.getVertexInfo(i);
      var vertex = vertexInfo.select(from, to);
      applyElementRotation(vertex, element.rotation);
      modelRotation.transformPosition(vertex);
      vertices[i] = vertex;
      uv[i * 2] = getU(uvRect, faceRotation, i);
      uv[i * 2 + 1] = getV(uvRect, faceRotation, i);
    }

    return GeometryFace.of(vertices, uv, texture, alphaMode, face.tintIndex, element.lightEmission, element.shade);
  }

  private void applyElementRotation(Vector3f vertex, @Nullable ElementRotation rotation) {
    if (rotation == null || rotation.angle == 0.0F) {
      return;
    }

    var axis = switch (rotation.axis) {
      case X -> new Vector3f(1.0F, 0.0F, 0.0F);
      case Y -> new Vector3f(0.0F, 1.0F, 0.0F);
      case Z -> new Vector3f(0.0F, 0.0F, 1.0F);
    };
    var matrix = new Matrix4f().rotation((float) Math.toRadians(rotation.angle), axis);
    var scale = rotation.rescale ? computeRescale(rotation) : new Vector3f(1.0F, 1.0F, 1.0F);
    rotateVertexBy(vertex, rotation.origin, matrix, scale);
  }

  private Vector3f computeRescale(ElementRotation rotation) {
    var scale = 1.0F / Mth.cos((float) Math.toRadians(Math.abs(rotation.angle)));
    return switch (rotation.axis) {
      case X -> new Vector3f(1.0F, scale, scale);
      case Y -> new Vector3f(scale, 1.0F, scale);
      case Z -> new Vector3f(scale, scale, 1.0F);
    };
  }

  private void rotateVertexBy(Vector3f vertex, Vector3f origin, Matrix4fc transform, Vector3fc scale) {
    vertex.sub(origin);
    transform.transformPosition(vertex);
    vertex.mul(scale);
    vertex.add(origin);
  }

  private UVRect defaultFaceUv(Vector3f from, Vector3f to, Direction facing) {
    return switch (facing) {
      case DOWN -> new UVRect(from.x / 16.0F, (16.0F - to.z) / 16.0F, to.x / 16.0F, (16.0F - from.z) / 16.0F);
      case UP -> new UVRect(from.x / 16.0F, from.z / 16.0F, to.x / 16.0F, to.z / 16.0F);
      case NORTH -> new UVRect((16.0F - to.x) / 16.0F, (16.0F - to.y) / 16.0F, (16.0F - from.x) / 16.0F, (16.0F - from.y) / 16.0F);
      case SOUTH -> new UVRect(from.x / 16.0F, (16.0F - to.y) / 16.0F, to.x / 16.0F, (16.0F - from.y) / 16.0F);
      case WEST -> new UVRect(from.z / 16.0F, (16.0F - to.y) / 16.0F, to.z / 16.0F, (16.0F - from.y) / 16.0F);
      case EAST -> new UVRect((16.0F - to.z) / 16.0F, (16.0F - to.y) / 16.0F, (16.0F - from.z) / 16.0F, (16.0F - from.y) / 16.0F);
    };
  }

  private float getU(UVRect rect, Quadrant rotation, int vertexIndex) {
    var rotatedIndex = rotation.rotateVertexIndex(vertexIndex);
    return rotatedIndex == 0 || rotatedIndex == 1 ? rect.minU : rect.maxU;
  }

  private float getV(UVRect rect, Quadrant rotation, int vertexIndex) {
    var rotatedIndex = rotation.rotateVertexIndex(vertexIndex);
    return rotatedIndex == 0 || rotatedIndex == 3 ? rect.minV : rect.maxV;
  }

  private void addCuboid(
    List<GeometryFace> faces,
    float minX,
    float minY,
    float minZ,
    float maxX,
    float maxY,
    float maxZ,
    TextureImage texture,
    AlphaMode alphaMode,
    Map<Direction, UVRect> uvByFace,
    Matrix4fc transform,
    boolean shade) {

    for (var direction : Direction.values()) {
      var face = createAxisAlignedFace(
        direction,
        minX + 0.5F,
        minY,
        minZ + 0.5F,
        maxX + 0.5F,
        maxY,
        maxZ + 0.5F,
        uvByFace.getOrDefault(direction, new UVRect(0.0F, 0.0F, 1.0F, 1.0F)),
        texture,
        alphaMode,
        -1,
        0,
        shade
      );
      faces.add(face.transformed(transform));
    }
  }

  private Map<Direction, UVRect> simpleUvSet() {
    return cuboidUvSet(
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F),
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F),
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F),
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F),
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F),
      new UVRect(0.0F, 0.0F, 1.0F, 1.0F)
    );
  }

  private Map<Direction, UVRect> cuboidUvSet(UVRect down, UVRect up, UVRect north, UVRect south, UVRect west, UVRect east) {
    var faces = new HashMap<Direction, UVRect>();
    faces.put(Direction.DOWN, down);
    faces.put(Direction.UP, up);
    faces.put(Direction.NORTH, north);
    faces.put(Direction.SOUTH, south);
    faces.put(Direction.WEST, west);
    faces.put(Direction.EAST, east);
    return faces;
  }

  private GeometryFace createAxisAlignedFace(
    Direction facing,
    float minX,
    float minY,
    float minZ,
    float maxX,
    float maxY,
    float maxZ,
    UVRect uv,
    TextureImage texture,
    AlphaMode alphaMode,
    int tintIndex,
    int emission,
    boolean shade) {

    var from = new Vector3f(minX * 16.0F, minY * 16.0F, minZ * 16.0F);
    var to = new Vector3f(maxX * 16.0F, maxY * 16.0F, maxZ * 16.0F);
    return bakeFace(
      new ModelElement(from, to, Map.of(facing, new FaceSpec("#particle", uv, tintIndex, 0)), null, shade, emission),
      new FaceSpec("#particle", uv, tintIndex, 0),
      facing,
      uv,
      texture,
      alphaMode,
      0,
      0
    );
  }

  private AlphaMode chooseAlphaMode(BlockState state, TextureImage texture, String textureHint, boolean forceTranslucent) {
    if (forceTranslucent) {
      return AlphaMode.TRANSLUCENT;
    }
    if (state.getFluidState().is(FluidTags.WATER)) {
      return AlphaMode.TRANSLUCENT;
    }

    var hint = textureHint.toLowerCase(Locale.ROOT);
    if (hint.contains("glass") || hint.contains("ice") || hint.contains("portal") || hint.contains("honey") || hint.contains("slime")) {
      return AlphaMode.TRANSLUCENT;
    }
    if (hint.contains("leaves") || hint.contains("vine") || hint.contains("plant") || texture.hasAlpha()) {
      return AlphaMode.CUTOUT;
    }
    return AlphaMode.OPAQUE;
  }

  @Nullable
  private ResolvedTexture resolveTextureReference(String textureRef, Map<String, TextureBinding> textures) {
    var current = textureRef;
    var forceTranslucent = false;
    for (var i = 0; i < 8; i++) {
      if (current == null || current.isBlank()) {
        return null;
      }
      if (!current.startsWith("#")) {
        return new ResolvedTexture(Identifier.parse(current), forceTranslucent);
      }
      var binding = textures.get(current.substring(1));
      if (binding == null) {
        return null;
      }
      current = binding.target();
      forceTranslucent |= binding.forceTranslucent();
    }

    return null;
  }

  private BlockStateDefinition loadBlockStateDefinition(Identifier blockId) {
    var json = loadJson(blockId.withPrefix("blockstates/"));
    if (json == null) {
      return BlockStateDefinition.EMPTY;
    }

    var variants = new ArrayList<VariantDefinition>();
    var multipart = new ArrayList<MultipartDefinition>();
    if (json.has("variants")) {
      for (var entry : json.getAsJsonObject("variants").entrySet()) {
        variants.add(new VariantDefinition(parseVariantCondition(entry.getKey()), parseModelReferences(entry.getValue())));
      }
    }
    if (json.has("multipart")) {
      for (var part : json.getAsJsonArray("multipart")) {
        var partObject = part.getAsJsonObject();
        multipart.add(new MultipartDefinition(parseMultipartCondition(partObject.get("when")), parseModelReferences(partObject.get("apply"))));
      }
    }
    return new BlockStateDefinition(variants, multipart);
  }

  private List<ModelReference> parseModelReferences(JsonElement jsonElement) {
    if (jsonElement.isJsonArray()) {
      var references = new ArrayList<ModelReference>();
      for (var element : jsonElement.getAsJsonArray()) {
        references.add(parseSingleModelReference(element.getAsJsonObject()));
      }
      return references.isEmpty() ? List.of() : List.of(selectWeightedReference(references));
    }

    return List.of(parseSingleModelReference(jsonElement.getAsJsonObject()));
  }

  private ModelReference selectWeightedReference(List<ModelReference> references) {
    return references.stream().max((left, right) -> Integer.compare(left.weight, right.weight)).orElse(references.getFirst());
  }

  private ModelReference parseSingleModelReference(JsonObject jsonObject) {
    return new ModelReference(
      Identifier.parse(jsonObject.get("model").getAsString()),
      jsonObject.has("x") ? jsonObject.get("x").getAsInt() : 0,
      jsonObject.has("y") ? jsonObject.get("y").getAsInt() : 0,
      jsonObject.has("uvlock") && jsonObject.get("uvlock").getAsBoolean(),
      jsonObject.has("weight") ? jsonObject.get("weight").getAsInt() : 1
    );
  }

  private Condition parseVariantCondition(String key) {
    if (key == null || key.isBlank()) {
      return Condition.ALWAYS;
    }

    var expected = new LinkedHashMap<String, List<String>>();
    for (var part : key.split(",")) {
      var split = part.split("=", 2);
      if (split.length != 2) {
        continue;
      }
      expected.put(split[0], Arrays.asList(split[1].split("\\|")));
    }
    return new StateCondition(expected);
  }

  private Condition parseMultipartCondition(@Nullable JsonElement whenElement) {
    if (whenElement == null || whenElement.isJsonNull()) {
      return Condition.ALWAYS;
    }

    if (whenElement.isJsonArray()) {
      var conditions = new ArrayList<Condition>();
      for (var element : whenElement.getAsJsonArray()) {
        conditions.add(parseMultipartCondition(element));
      }
      return new AnyCondition(conditions);
    }

    var jsonObject = whenElement.getAsJsonObject();
    if (jsonObject.has("OR")) {
      var conditions = new ArrayList<Condition>();
      for (var element : jsonObject.getAsJsonArray("OR")) {
        conditions.add(parseMultipartCondition(element));
      }
      return new AnyCondition(conditions);
    }
    if (jsonObject.has("AND")) {
      var conditions = new ArrayList<Condition>();
      for (var element : jsonObject.getAsJsonArray("AND")) {
        conditions.add(parseMultipartCondition(element));
      }
      return new AllCondition(conditions);
    }

    var expected = new LinkedHashMap<String, List<String>>();
    for (var entry : jsonObject.entrySet()) {
      expected.put(entry.getKey(), Arrays.asList(entry.getValue().getAsString().split("\\|")));
    }
    return new StateCondition(expected);
  }

  @Nullable
  private ResolvedModel loadModel(Identifier modelLocation) {
    var json = loadJson(modelLocation.withPrefix("models/"));
    if (json == null) {
      return null;
    }

    ResolvedModel parent = null;
    if (json.has("parent")) {
      parent = resolveModel(Identifier.parse(json.get("parent").getAsString()));
    }

    var textures = parent != null ? new HashMap<>(parent.textures) : new HashMap<String, TextureBinding>();
    if (json.has("textures")) {
      for (var entry : json.getAsJsonObject("textures").entrySet()) {
        var textureReference = parseTextureReferenceValue(entry.getValue());
        if (textureReference != null) {
          textures.put(entry.getKey(), textureReference);
        }
      }
    }

    var ambientOcclusion = !json.has("ambientocclusion") || json.get("ambientocclusion").getAsBoolean();
    List<ModelElement> elements = parent != null ? parent.elements : List.of();
    if (json.has("elements")) {
      elements = parseModelElements(json.getAsJsonArray("elements"));
    }
    return new ResolvedModel(textures, elements, ambientOcclusion);
  }

  @Nullable
  private TextureBinding parseTextureReferenceValue(JsonElement textureElement) {
    if (textureElement == null || textureElement.isJsonNull()) {
      return null;
    }

    if (textureElement.isJsonPrimitive()) {
      return new TextureBinding(textureElement.getAsString(), false);
    }

    if (textureElement.isJsonObject()) {
      var textureObject = textureElement.getAsJsonObject();
      if (textureObject.has("sprite") && textureObject.get("sprite").isJsonPrimitive()) {
        return new TextureBinding(
          textureObject.get("sprite").getAsString(),
          textureObject.has("force_translucent") && textureObject.get("force_translucent").getAsBoolean()
        );
      }
      if (textureObject.has("texture") && textureObject.get("texture").isJsonPrimitive()) {
        return new TextureBinding(
          textureObject.get("texture").getAsString(),
          textureObject.has("force_translucent") && textureObject.get("force_translucent").getAsBoolean()
        );
      }
    }

    return null;
  }

  private List<ModelElement> parseModelElements(JsonArray array) {
    var elements = new ArrayList<ModelElement>(array.size());
    for (var element : array) {
      var json = element.getAsJsonObject();
      var from = parseVector(json.getAsJsonArray("from"));
      var to = parseVector(json.getAsJsonArray("to"));
      var faces = new HashMap<Direction, FaceSpec>();
      for (var entry : json.getAsJsonObject("faces").entrySet()) {
        var direction = Direction.byName(entry.getKey());
        if (direction == null) {
          continue;
        }

        var faceJson = entry.getValue().getAsJsonObject();
        faces.put(
          direction,
          new FaceSpec(
            faceJson.get("texture").getAsString(),
            parseUv(faceJson.getAsJsonArray("uv")),
            faceJson.has("tintindex") ? faceJson.get("tintindex").getAsInt() : -1,
            faceJson.has("rotation") ? faceJson.get("rotation").getAsInt() : 0
          )
        );
      }

      @Nullable ElementRotation rotation = null;
      if (json.has("rotation")) {
        var rotationJson = json.getAsJsonObject("rotation");
        rotation = new ElementRotation(
          parseVector(rotationJson.getAsJsonArray("origin")).mul(1.0F / 16.0F),
          Direction.Axis.byName(rotationJson.get("axis").getAsString()),
          rotationJson.get("angle").getAsFloat(),
          rotationJson.has("rescale") && rotationJson.get("rescale").getAsBoolean()
        );
      }

      elements.add(new ModelElement(
        from,
        to,
        faces,
        rotation,
        !json.has("shade") || json.get("shade").getAsBoolean(),
        json.has("light_emission") ? json.get("light_emission").getAsInt() : 0
      ));
    }

    return elements;
  }

  private Vector3f parseVector(JsonArray array) {
    return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
  }

  @Nullable
  private UVRect parseUv(@Nullable JsonArray array) {
    if (array == null) {
      return null;
    }

    return new UVRect(
      array.get(0).getAsFloat() / 16.0F,
      array.get(1).getAsFloat() / 16.0F,
      array.get(2).getAsFloat() / 16.0F,
      array.get(3).getAsFloat() / 16.0F
    );
  }

  private TextureImage loadTexture(Identifier texturePath) {
    var normalized = normalizeTexturePath(texturePath);
    var metadataPath = "assets/%s/%s.mcmeta".formatted(normalized.getNamespace(), normalized.getPath());
    try (var imageStream = Objects.requireNonNull(RendererAssets.class.getClassLoader().getResourceAsStream("assets/%s/%s".formatted(normalized.getNamespace(), normalized.getPath())))) {
      var image = ImageIO.read(imageStream);
      JsonObject metadata = null;
      try (var metadataStream = RendererAssets.class.getClassLoader().getResourceAsStream(metadataPath)) {
        if (metadataStream != null) {
          metadata = JsonParser.parseString(new String(metadataStream.readAllBytes())).getAsJsonObject();
        }
      }
      return TextureImage.from(image, metadata);
    } catch (Throwable t) {
      log.debug("Missing renderer texture {}", normalized, t);
      return MISSING_TEXTURE;
    }
  }

  @Nullable
  private JsonObject loadJson(Identifier pathWithFolder) {
    var path = "assets/%s/%s.json".formatted(pathWithFolder.getNamespace(), pathWithFolder.getPath());
    try (var stream = RendererAssets.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        return null;
      }

      return JsonParser.parseString(new String(stream.readAllBytes())).getAsJsonObject();
    } catch (Throwable t) {
      log.debug("Failed to load renderer json {}", path, t);
      return null;
    }
  }

  private Identifier normalizeTexturePath(Identifier textureLocation) {
    var path = textureLocation.getPath();
    if (path.startsWith("textures/") && path.endsWith(".png")) {
      return textureLocation;
    }
    if (path.startsWith("textures/")) {
      return textureLocation.withPath(path + ".png");
    }
    if (path.endsWith(".png")) {
      return textureLocation.withPath("textures/" + path);
    }
    return textureLocation.withPath("textures/" + path + ".png");
  }

  public enum AlphaMode {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT
  }

  public record BillboardTexture(TextureImage texture, AlphaMode alphaMode) {}

  public record ItemRenderModel(BlockGeometry geometry, @Nullable BillboardTexture billboard) {
    public static final ItemRenderModel EMPTY = new ItemRenderModel(BlockGeometry.EMPTY, null);
  }

  public record BlockGeometry(List<GeometryFace> faces) {
    public static final BlockGeometry EMPTY = new BlockGeometry(List.of());
  }

  public record FluidGeometry(TextureImage stillTexture, TextureImage flowTexture, AlphaMode alphaMode, int emission, float surfaceHeight, float ownHeight) {
    public static final FluidGeometry EMPTY = new FluidGeometry(MISSING_TEXTURE, MISSING_TEXTURE, AlphaMode.TRANSLUCENT, 0, 0.0F, 0.0F);
  }

  public record GeometryFace(
    double[] x,
    double[] y,
    double[] z,
    float[] uv,
    TextureImage texture,
    AlphaMode alphaMode,
    int tintIndex,
    int emission,
    boolean shade
  ) {
    public static GeometryFace of(
      Vector3f[] vertices,
      float[] uv,
      TextureImage texture,
      AlphaMode alphaMode,
      int tintIndex,
      int emission,
      boolean shade) {

      return new GeometryFace(
        new double[]{vertices[0].x, vertices[1].x, vertices[2].x, vertices[3].x},
        new double[]{vertices[0].y, vertices[1].y, vertices[2].y, vertices[3].y},
        new double[]{vertices[0].z, vertices[1].z, vertices[2].z, vertices[3].z},
        uv,
        texture,
        alphaMode,
        tintIndex,
        emission,
        shade
      );
    }

    public GeometryFace translated(double tx, double ty, double tz) {
      return new GeometryFace(
        new double[]{x[0] + tx, x[1] + tx, x[2] + tx, x[3] + tx},
        new double[]{y[0] + ty, y[1] + ty, y[2] + ty, y[3] + ty},
        new double[]{z[0] + tz, z[1] + tz, z[2] + tz, z[3] + tz},
        uv,
        texture,
        alphaMode,
        tintIndex,
        emission,
        shade
      );
    }

    public GeometryFace transformed(Matrix4fc matrix) {
      var vertices = new Vector3f[4];
      for (var i = 0; i < 4; i++) {
        vertices[i] = matrix.transformPosition(new Vector3f((float) x[i], (float) y[i], (float) z[i]));
      }

      return of(vertices, uv, texture, alphaMode, tintIndex, emission, shade);
    }
  }

  public static final class TextureImage {
    private final int width;
    private final int height;
    private final int frameHeight;
    private final int frameCount;
    private final int frameTime;
    private final boolean interpolate;
    private final int[] frameOrder;
    private final int[] pixels;
    private final boolean hasAlpha;
    @Nullable
    private BufferedImage bufferedImage;

    private TextureImage(
      int width,
      int height,
      int frameHeight,
      int frameCount,
      int frameTime,
      boolean interpolate,
      int[] frameOrder,
      int[] pixels,
      boolean hasAlpha) {
      this.width = width;
      this.height = height;
      this.frameHeight = frameHeight;
      this.frameCount = frameCount;
      this.frameTime = frameTime;
      this.interpolate = interpolate;
      this.frameOrder = frameOrder;
      this.pixels = pixels;
      this.hasAlpha = hasAlpha;
    }

    public static TextureImage from(@Nullable BufferedImage image, @Nullable JsonObject metadata) {
      if (image == null) {
        return missing();
      }

      var width = image.getWidth();
      var height = image.getHeight();
      var pixels = image.getRGB(0, 0, width, height, null, 0, width);
      var animation = metadata != null && metadata.has("animation") ? metadata.getAsJsonObject("animation") : null;
      var frameHeight = animation != null && animation.has("height") ? animation.get("height").getAsInt() : width;
      frameHeight = frameHeight <= 0 || frameHeight > height ? width : frameHeight;
      frameHeight = Math.max(1, frameHeight);
      var frameCount = Math.max(1, height / frameHeight);
      var frameTime = animation != null && animation.has("frametime") ? Math.max(1, animation.get("frametime").getAsInt()) : 1;
      var interpolate = animation != null && animation.has("interpolate") && animation.get("interpolate").getAsBoolean();
      var frameOrder = new int[frameCount];
      for (var i = 0; i < frameCount; i++) {
        frameOrder[i] = i;
      }
      if (animation != null && animation.has("frames")) {
        var frames = animation.getAsJsonArray("frames");
        frameOrder = new int[Math.max(1, frames.size())];
        for (var i = 0; i < frameOrder.length; i++) {
          var frame = frames.get(i);
          frameOrder[i] = frame.isJsonObject() ? frame.getAsJsonObject().get("index").getAsInt() : frame.getAsInt();
        }
      }

      var hasAlpha = Arrays.stream(pixels).anyMatch(pixel -> ((pixel >>> 24) & 0xFF) < 255);
      var textureImage = new TextureImage(width, height, frameHeight, frameCount, frameTime, interpolate, frameOrder, pixels, hasAlpha);
      textureImage.bufferedImage = image;
      return textureImage;
    }

    public static TextureImage missing() {
      var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
      for (var y = 0; y < 16; y++) {
        for (var x = 0; x < 16; x++) {
          var color = ((x / 4 + y / 4) & 1) == 0 ? 0xFFFF00FF : 0xFF000000;
          image.setRGB(x, y, color);
        }
      }
      return from(image, null);
    }

    public int sample(float u, float v, long tick) {
      var wrappedU = u - (float) Math.floor(u);
      var wrappedV = v - (float) Math.floor(v);
      var frameIndex = frameOrder[(int) ((tick / frameTime) % frameOrder.length)];
      var yOffset = frameIndex * frameHeight;
      var x = Math.min(width - 1, Math.max(0, (int) (wrappedU * width)));
      var y = Math.min(frameHeight - 1, Math.max(0, (int) (wrappedV * frameHeight))) + yOffset;
      return pixels[x + y * width];
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }

    public boolean hasAlpha() {
      return hasAlpha;
    }

    public BufferedImage toBufferedImage() {
      if (bufferedImage == null) {
        bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        bufferedImage.setRGB(0, 0, width, height, pixels, 0, width);
      }
      return bufferedImage;
    }
  }

  private interface Condition {
    Condition ALWAYS = _ -> true;

    boolean matches(BlockState state);
  }

  private record StateCondition(Map<String, List<String>> expected) implements Condition {
    @Override
    public boolean matches(BlockState state) {
      for (var entry : expected.entrySet()) {
        Property<?> property = null;
        for (var candidate : state.getProperties()) {
          if (candidate.getName().equals(entry.getKey())) {
            property = candidate;
            break;
          }
        }
        if (property == null) {
          return false;
        }

        var actual = propertyValueName(state, property);
        if (entry.getValue().stream().noneMatch(actual::equals)) {
          return false;
        }
      }
      return true;
    }
  }

  private record AnyCondition(List<Condition> children) implements Condition {
    @Override
    public boolean matches(BlockState state) {
      return children.stream().anyMatch(child -> child.matches(state));
    }
  }

  private record AllCondition(List<Condition> children) implements Condition {
    @Override
    public boolean matches(BlockState state) {
      return children.stream().allMatch(child -> child.matches(state));
    }
  }

  private record BlockStateDefinition(List<VariantDefinition> variants, List<MultipartDefinition> multipart) {
    private static final BlockStateDefinition EMPTY = new BlockStateDefinition(List.of(), List.of());

    public List<ModelReference> select(BlockState state) {
      var selected = new ArrayList<ModelReference>();
      for (var variant : variants) {
        if (variant.condition.matches(state)) {
          selected.addAll(variant.references);
        }
      }
      for (var part : multipart) {
        if (part.condition.matches(state)) {
          selected.addAll(part.references);
        }
      }
      return selected;
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String propertyValueName(BlockState state, Property<?> property) {
    return ((Property) property).getName(state.getValue((Property) property));
  }

  private record VariantDefinition(Condition condition, List<ModelReference> references) {}

  private record MultipartDefinition(Condition condition, List<ModelReference> references) {}

  private record ModelReference(Identifier model, int xRotation, int yRotation, boolean uvLock, int weight) {}

  private record TextureBinding(String target, boolean forceTranslucent) {}

  private record ResolvedTexture(Identifier sprite, boolean forceTranslucent) {}

  private record ResolvedModel(Map<String, TextureBinding> textures, List<ModelElement> elements, boolean ambientOcclusion) {}

  private record ModelElement(
    Vector3f from,
    Vector3f to,
    Map<Direction, FaceSpec> faces,
    @Nullable ElementRotation rotation,
    boolean shade,
    int lightEmission
  ) {}

  private record ElementRotation(Vector3f origin, Direction.Axis axis, float angle, boolean rescale) {}

  private record FaceSpec(String textureRef, @Nullable UVRect uv, int tintIndex, int rotation) {}

  private record UVRect(float minU, float minV, float maxU, float maxV) {}

  private record BakedModel(List<GeometryFace> faces) {}

  private record BlockRenderContext(BlockState state, @Nullable ItemStack itemStack) {
    public static BlockRenderContext forBlock(BlockState state) {
      return new BlockRenderContext(state, null);
    }

    public static BlockRenderContext forItem(ItemStack itemStack) {
      return new BlockRenderContext(Blocks.AIR.defaultBlockState(), itemStack);
    }
  }

  private record TextKey(String text, int width, int textColor, int backgroundColor) {}

  private static final class EmptyBlockGetterProxy implements net.minecraft.world.level.BlockGetter {
    private static final EmptyBlockGetterProxy INSTANCE = new EmptyBlockGetterProxy();

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
      return Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getHeight() {
      return 384;
    }

    @Override
    public int getMinY() {
      return -64;
    }
  }
}
