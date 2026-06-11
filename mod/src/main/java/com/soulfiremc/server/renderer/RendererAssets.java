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
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Quadrant;
import com.soulfiremc.mod.access.IMinecraft;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public final class RendererAssets {
  private static final RendererAssets INSTANCE = new RendererAssets();
  private static final TextureImage MISSING_TEXTURE = TextureImage.missing();
  private static final String SKIN_TEXTURE_PREFIX = "skins/";
  private static final String CAPE_TEXTURE_PREFIX = "capes/";
  private static final String ELYTRA_TEXTURE_PREFIX = "elytra/";
  private static final Identifier GRASS_COLOR_MAP = Identifier.withDefaultNamespace("textures/colormap/grass.png");
  private static final Identifier FOLIAGE_COLOR_MAP = Identifier.withDefaultNamespace("textures/colormap/foliage.png");
  private static final Identifier DRY_FOLIAGE_COLOR_MAP = Identifier.withDefaultNamespace("textures/colormap/dry_foliage.png");
  private final ConcurrentMap<BlockState, BlockGeometry> blockGeometryCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<Identifier, ResolvedModel> modelCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, TextureImage> textureCache = new ConcurrentHashMap<>();
  private final Object vanillaColorMapLoadLock = new Object();
  private volatile boolean vanillaColorMapsLoaded;
  private volatile boolean vanillaColorMapLoadFailureLogged;

  private RendererAssets() {}

  public static RendererAssets instance() {
    return INSTANCE;
  }

  public BlockGeometry blockGeometry(BlockState blockState) {
    return blockGeometryCache.computeIfAbsent(blockState, this::buildBlockGeometry);
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
      var baked = bakeResolvedModel(resolvedModel, Blocks.AIR.defaultBlockState(), 0, 0);
      if (!baked.faces().isEmpty()) {
        return new ItemRenderModel(new BlockGeometry(baked.faces()), null);
      }
    }

    var layer0 = resolvedModel != null ? resolveTextureReference("#layer0", resolvedModel.textures) : null;
    var texture = layer0 != null ? texture(layer0.sprite()) : texture(itemId.withPrefix("item/"));
    return new ItemRenderModel(BlockGeometry.EMPTY, new BillboardTexture(texture, AlphaMode.CUTOUT));
  }

  public TextureImage texture(ClientAsset.Texture textureAsset) {
    var texturePath = textureAsset.texturePath();
    return isRuntimeClientTexturePath(texturePath) ? renderTexture(texturePath) : texture(texturePath);
  }

  public TextureImage texture(Identifier textureLocation) {
    var normalizedPath = normalizeTexturePath(textureLocation);
    return textureCache.computeIfAbsent(normalizedPath.toString(), _ -> loadTexture(normalizedPath));
  }

  public TextureImage textureAtlas(Identifier atlasLocation) {
    return textureCache.computeIfAbsent("atlas:" + atlasLocation, _ -> loadAtlasTexture(atlasLocation));
  }

  public TextureImage renderTexture(Identifier textureLocation) {
    var runtimeTexture = runtimeTexture(textureLocation);
    return runtimeTexture != null ? runtimeTexture : texture(textureLocation);
  }

  public int resolveTint(ClientLevel level, BlockPos pos, BlockState state, int tintIndex) {
    if (tintIndex < 0) {
      return 0xFFFFFFFF;
    }

    var colorMapsWereLoaded = vanillaColorMapsLoaded;
    var colorMapsReady = ensureVanillaColorMapsLoaded();
    if (!colorMapsWereLoaded && colorMapsReady) {
      level.clearTintCaches();
    }

    var tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
    if (tintSource == null) {
      return 0xFFFFFFFF;
    }

    var tint = tintSource.colorInWorld(state, level, pos);
    if (!colorMapsReady && isTransparentBlack(tint)) {
      tint = tintSource.color(state);
      if (isTransparentBlack(tint)) {
        return 0xFFFFFFFF;
      }
    }

    return opaqueTint(tint);
  }

  boolean ensureVanillaColorMapsLoaded() {
    if (vanillaColorMapsLoaded) {
      return true;
    }

    synchronized (vanillaColorMapLoadLock) {
      if (vanillaColorMapsLoaded) {
        return true;
      }

      try {
        var grass = loadColorMap(GRASS_COLOR_MAP);
        var foliage = loadColorMap(FOLIAGE_COLOR_MAP);
        var dryFoliage = loadColorMap(DRY_FOLIAGE_COLOR_MAP);
        GrassColor.init(grass);
        FoliageColor.init(foliage);
        DryFoliageColor.init(dryFoliage);
        vanillaColorMapsLoaded = true;
      } catch (Throwable t) {
        if (!vanillaColorMapLoadFailureLogged) {
          vanillaColorMapLoadFailureLogged = true;
          log.debug("Failed to load vanilla renderer color maps", t);
        }
      }

      return vanillaColorMapsLoaded;
    }
  }

  private int[] loadColorMap(Identifier location) throws IOException {
    try (var stream = openResourceStream(location)) {
      if (stream == null) {
        throw new IOException("Missing color map resource: " + location);
      }

      var image = ImageIO.read(stream);
      if (image == null) {
        throw new IOException("Invalid color map image: " + location);
      }

      var pixels = new int[image.getWidth() * image.getHeight()];
      image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
      return pixels;
    }
  }

  private boolean isTransparentBlack(int color) {
    return color == 0;
  }

  private int opaqueTint(int tint) {
    return (tint >>> 24) == 0 ? 0xFF000000 | tint : tint;
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

  private BlockGeometry buildBlockGeometry(BlockState state) {
    return buildVanillaBlockGeometry(state);
  }

  private BlockGeometry buildVanillaBlockGeometry(BlockState state) {
    try {
      var blockStateModelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
      var blockStateModel = blockStateModelSet.get(state);
      if (blockStateModel == null) {
        throw new NullPointerException("blockStateModelSet.get(state) returned null");
      }
      var blockModel = new BlockStateModelWrapper(blockStateModel, List.of(), new Matrix4f());
      var renderState = new BlockModelRenderState();
      blockModel.update(renderState, state, BlockDisplayContext.create(), 42L);
      var parts = renderState.modelParts;
      if (parts == null || parts.isEmpty()) {
        throw new NullPointerException("blockModel.update returned no parts");
      }
      var faces = new ArrayList<GeometryFace>();
      for (var part : parts) {
        if (part == null) {
          throw new NullPointerException("block model part is null");
        }
        for (var quad : part.getQuads(null)) {
          faces.add(vanillaQuadToFace(quad, null, state));
        }
        for (var direction : Direction.values()) {
          for (var quad : part.getQuads(direction)) {
            faces.add(vanillaQuadToFace(quad, direction, state));
          }
        }
      }
      if (!faces.isEmpty()) {
        RenderDebugTrace.current().vanillaBlockGeometryHit();
      }
      return faces.isEmpty() ? BlockGeometry.EMPTY : new BlockGeometry(faces);
    } catch (Throwable t) {
      RenderDebugTrace.current().vanillaBlockGeometryFallback(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), t);
      log.debug("Failed to build vanilla block geometry for {}", BuiltInRegistries.BLOCK.getKey(state.getBlock()), t);
      return BlockGeometry.EMPTY;
    }
  }

  private GeometryFace vanillaQuadToFace(BakedQuad quad, @Nullable Direction cullDirection, BlockState state) {
    if (quad == null) {
      throw new NullPointerException("vanilla quad is null");
    }
    var materialInfo = quad.materialInfo();
    if (materialInfo == null) {
      throw new NullPointerException("quad.materialInfo is null");
    }
    var sprite = materialInfo.sprite();
    if (sprite == null) {
      throw new NullPointerException("quad.materialInfo.sprite is null");
    }
    var contents = sprite.contents();
    if (contents == null) {
      throw new NullPointerException("quad.materialInfo.sprite.contents is null");
    }
    var spriteId = contents.name();
    if (spriteId == null) {
      throw new NullPointerException("quad.materialInfo.sprite.contents.name is null");
    }
    var vertices = new Vector3f[4];
    var uv = new float[8];
    for (var i = 0; i < 4; i++) {
      var position = quad.position(i);
      var packedUv = quad.packedUV(i);
      if (position == null) {
        throw new NullPointerException("quad position[" + i + "] is null");
      }
      vertices[i] = new Vector3f(position);
      uv[i * 2] = BakedQuadUv.localU(sprite, packedUv);
      uv[i * 2 + 1] = BakedQuadUv.localV(sprite, packedUv);
    }
    var texture = texture(spriteId);
    var alphaMode = chooseAlphaMode(state, texture, spriteId.getPath(), false);
    return GeometryFace.of(
      vertices,
      uv,
      texture,
      alphaMode,
      cullDirection,
      materialInfo.isTinted() ? materialInfo.tintIndex() : -1,
      materialInfo.lightEmission(),
      materialInfo.shade()
    );
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

  private BakedModel bakeResolvedModel(ResolvedModel resolvedModel, BlockState state, int xRotation, int yRotation) {
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
            state,
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

    return GeometryFace.of(vertices, uv, texture, alphaMode, direction, face.tintIndex, element.lightEmission, element.shade);
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

  private AlphaMode chooseAlphaMode(BlockState state, TextureImage texture, String textureHint, boolean forceTranslucent) {
    if (forceTranslucent) {
      return AlphaMode.TRANSLUCENT;
    }
    if (state.getFluidState().is(FluidTags.WATER)) {
      return AlphaMode.TRANSLUCENT;
    }

    var block = state.getBlock();
    if (block instanceof HalfTransparentBlock
      || block == Blocks.ICE
      || block == Blocks.FROSTED_ICE
      || block == Blocks.HONEY_BLOCK
      || block == Blocks.SLIME_BLOCK
      || block == Blocks.NETHER_PORTAL
      || block == Blocks.END_GATEWAY
      || block == Blocks.END_PORTAL
      || block == Blocks.BUBBLE_COLUMN
      || block == Blocks.TINTED_GLASS) {
      return AlphaMode.TRANSLUCENT;
    }
    if (block instanceof LeavesBlock) {
      return AlphaMode.CUTOUT;
    }

    var hint = textureHint.toLowerCase(Locale.ROOT);
    if (hint.contains("glass") || hint.contains("ice") || hint.contains("portal") || hint.contains("honey") || hint.contains("slime")) {
      return AlphaMode.TRANSLUCENT;
    }
    if (texture.hasTranslucentPixels()) {
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

    List<ModelElement> elements = parent != null ? parent.elements : List.of();
    if (json.has("elements")) {
      elements = parseModelElements(json.getAsJsonArray("elements"));
    }
    return new ResolvedModel(textures, elements);
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
    var metadataLocation = normalized.withPath(normalized.getPath() + ".mcmeta");
    try {
      var texture = Minecraft.getInstance().getTextureManager().getTexture(normalized);
      if (texture instanceof DynamicTexture dynamicTexture) {
        return TextureImage.from(nativeImageToBufferedImage(dynamicTexture.getPixels()), null);
      }
    } catch (Throwable ignored) {
    }

    try (var imageStream = openResourceStream(normalized)) {
      if (imageStream == null) {
        throw new IllegalStateException("Missing resource " + normalized);
      }
      var image = ImageIO.read(imageStream);
      JsonObject metadata = null;
      try (var metadataStream = openResourceStream(metadataLocation)) {
        if (metadataStream != null) {
          metadata = JsonParser.parseString(new String(metadataStream.readAllBytes())).getAsJsonObject();
        }
      }
      return TextureImage.from(image, metadata);
    } catch (Throwable t) {
      RenderDebugTrace.current().missingTexture(normalized.toString(), t);
      log.debug("Missing renderer texture {}", normalized, t);
      return MISSING_TEXTURE;
    }
  }

  private BufferedImage nativeImageToBufferedImage(com.mojang.blaze3d.platform.NativeImage image) {
    var buffered = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    for (var y = 0; y < image.getHeight(); y++) {
      for (var x = 0; x < image.getWidth(); x++) {
        buffered.setRGB(x, y, image.getPixel(x, y));
      }
    }
    return buffered;
  }

  @Nullable
  private TextureImage runtimeTexture(Identifier textureLocation) {
    var mirroredTexture = RendererRuntimeTextureMirror.texture(textureLocation);
    if (mirroredTexture != null) {
      return mirroredTexture;
    }

    var cachedPlayerTexture = cachedPlayerTexture(textureLocation);
    if (cachedPlayerTexture != null) {
      return cachedPlayerTexture;
    }

    try {
      var texture = Minecraft.getInstance().getTextureManager().getTexture(textureLocation);
      if (texture instanceof DynamicTexture dynamicTexture) {
        var pixels = dynamicTexture.getPixels();
        if (pixels == null) {
          return null;
        }

        var textureImage = TextureImage.from(nativeImageToBufferedImage(pixels), null);
        if (isPlayerTexture(textureLocation) && textureImage.isFullyTransparent()) {
          return null;
        }
        return textureImage;
      }
      if (texture instanceof TextureAtlas atlas) {
        return textureCache.computeIfAbsent("atlas-texture:" + textureLocation, _ -> loadAtlasTexture(atlas));
      }
    } catch (Throwable t) {
      log.debug("Failed to resolve runtime renderer texture {}", textureLocation, t);
    }

    return null;
  }

  @Nullable
  private TextureImage cachedPlayerTexture(Identifier textureLocation) {
    var prefix = playerTexturePrefix(textureLocation);
    if (prefix == null) {
      return null;
    }

    var cacheKey = "player-texture-cache:" + textureLocation;
    var cached = textureCache.get(cacheKey);
    if (cached != null) {
      return cached;
    }

    var loaded = loadCachedPlayerTexture(textureLocation, prefix);
    if (loaded == null) {
      return null;
    }

    var existing = textureCache.putIfAbsent(cacheKey, loaded);
    return existing != null ? existing : loaded;
  }

  @Nullable
  private TextureImage loadCachedPlayerTexture(Identifier textureLocation, String prefix) {
    var hash = textureLocation.getPath().substring(prefix.length());
    if (hash.isBlank() || hash.contains("/")) {
      return null;
    }

    var texturePath = playerTextureCacheDirectory().resolve(hash.substring(0, Math.min(2, hash.length()))).resolve(hash);
    if (!Files.isRegularFile(texturePath)) {
      return null;
    }

    NativeImage image = null;
    NativeImage normalized = null;
    try {
      image = NativeImage.read(Files.readAllBytes(texturePath));
      if (prefix.equals(SKIN_TEXTURE_PREFIX)) {
        normalized = SkinTextureDownloader.processLegacySkin(image, textureLocation.toString());
        image = null;
        return TextureImage.from(nativeImageToBufferedImage(normalized), null);
      }

      return TextureImage.from(nativeImageToBufferedImage(image), null);
    } catch (Throwable t) {
      log.debug("Failed to load cached renderer player texture {}", texturePath, t);
      return null;
    } finally {
      if (normalized != null) {
        normalized.close();
      }
      if (image != null) {
        image.close();
      }
    }
  }

  private Path playerTextureCacheDirectory() {
    return ((IMinecraft) Minecraft.getInstance()).soulfire$getGameConfig().location.assetDirectory.toPath().resolve("skins");
  }

  private static boolean isPlayerTexture(Identifier textureLocation) {
    return playerTexturePrefix(textureLocation) != null;
  }

  @Nullable
  private static String playerTexturePrefix(Identifier textureLocation) {
    if (!textureLocation.getNamespace().equals("minecraft")) {
      return null;
    }

    var path = textureLocation.getPath();
    if (path.startsWith(SKIN_TEXTURE_PREFIX)) {
      return SKIN_TEXTURE_PREFIX;
    }
    if (path.startsWith(CAPE_TEXTURE_PREFIX)) {
      return CAPE_TEXTURE_PREFIX;
    }
    if (path.startsWith(ELYTRA_TEXTURE_PREFIX)) {
      return ELYTRA_TEXTURE_PREFIX;
    }
    return null;
  }

  private TextureImage loadAtlasTexture(Identifier atlasLocation) {
    var runtimeTexture = runtimeTexture(atlasLocation);
    if (runtimeTexture != null) {
      return runtimeTexture;
    }

    try {
      return loadAtlasTexture(Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(atlasLocation));
    } catch (Throwable t) {
      log.debug("Failed to reconstruct atlas texture {}", atlasLocation, t);
      return MISSING_TEXTURE;
    }
  }

  private TextureImage loadAtlasTexture(TextureAtlas atlas) {
    try {
      var image = new BufferedImage(atlas.getWidth(), atlas.getHeight(), BufferedImage.TYPE_INT_ARGB);
      for (var sprite : atlas.sprites) {
        if (sprite == null || sprite.contents() == null || sprite.contents().originalImage == null) {
          continue;
        }

        drawAtlasSprite(image, sprite);
      }
      return TextureImage.from(image, null);
    } catch (Throwable t) {
      log.debug("Failed to reconstruct atlas texture {}", atlas.location(), t);
      return MISSING_TEXTURE;
    }
  }

  private void drawAtlasSprite(BufferedImage atlasImage, TextureAtlasSprite sprite) {
    var contents = sprite.contents();
    var source = contents.originalImage;
    for (var y = 0; y < contents.height(); y++) {
      for (var x = 0; x < contents.width(); x++) {
        atlasImage.setRGB(sprite.getX() + x, sprite.getY() + y, source.getPixel(x, y));
      }
    }
  }

  @Nullable
  private JsonObject loadJson(Identifier pathWithFolder) {
    var jsonLocation = pathWithFolder.withPath(pathWithFolder.getPath() + ".json");
    try (var stream = openResourceStream(jsonLocation)) {
      if (stream == null) {
        return null;
      }

      return JsonParser.parseString(new String(stream.readAllBytes())).getAsJsonObject();
    } catch (Throwable t) {
      log.debug("Failed to load renderer json {}", jsonLocation, t);
      return null;
    }
  }

  @Nullable
  private InputStream openResourceStream(Identifier location) throws IOException {
    var resourceManager = currentResourceManager();
    var resource = resourceManager.getResource(location);
    if (resource.isPresent()) {
      return resource.get().open();
    }

    return RendererAssets.class.getClassLoader().getResourceAsStream("assets/%s/%s".formatted(location.getNamespace(), location.getPath()));
  }

  private ResourceManager currentResourceManager() {
    try {
      return Minecraft.getInstance().getResourceManager();
    } catch (Throwable _) {
      return ResourceManager.Empty.INSTANCE;
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

  static boolean isRuntimeClientTexturePath(Identifier textureLocation) {
    var path = textureLocation.getPath();
    return path.startsWith("skins/")
      || path.startsWith("capes/")
      || path.startsWith("elytra/");
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

  public record GeometryFace(
    double[] x,
    double[] y,
    double[] z,
    float[] uv,
    TextureImage texture,
    AlphaMode alphaMode,
    @Nullable Direction cullDirection,
    int tintIndex,
    int emission,
    boolean shade
  ) {
    public static GeometryFace of(
      Vector3f[] vertices,
      float[] uv,
      TextureImage texture,
      AlphaMode alphaMode,
      @Nullable Direction cullDirection,
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
        cullDirection,
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

      return of(vertices, uv, texture, alphaMode, cullDirection, tintIndex, emission, shade);
    }
  }

  public static final class TextureImage {
    private final int width;
    private final int height;
    private final int frameHeight;
    private final int frameCount;
    private final int frameTime;
    private final int[] frameOrder;
    private final int[] pixels;
    private final boolean hasAlpha;
    private final boolean hasTranslucentPixels;
    @Nullable
    private BufferedImage bufferedImage;

    private TextureImage(
      int width,
      int height,
      int frameHeight,
      int frameCount,
      int frameTime,
      int[] frameOrder,
      int[] pixels,
      boolean hasAlpha,
      boolean hasTranslucentPixels) {
      this.width = width;
      this.height = height;
      this.frameHeight = frameHeight;
      this.frameCount = frameCount;
      this.frameTime = frameTime;
      this.frameOrder = frameOrder;
      this.pixels = pixels;
      this.hasAlpha = hasAlpha;
      this.hasTranslucentPixels = hasTranslucentPixels;
    }

    public static TextureImage from(@Nullable BufferedImage image, @Nullable JsonObject metadata) {
      if (image == null) {
        return missing();
      }

      var width = image.getWidth();
      var height = image.getHeight();
      var pixels = image.getRGB(0, 0, width, height, null, 0, width);
      var textureImage = fromArgb(width, height, pixels, metadata);
      textureImage.bufferedImage = image;
      return textureImage;
    }

    public static TextureImage fromArgb(int width, int height, int[] pixels, @Nullable JsonObject metadata) {
      if (width <= 0 || height <= 0 || pixels.length < width * height) {
        return missing();
      }

      pixels = Arrays.copyOf(pixels, width * height);
      var animation = metadata != null && metadata.has("animation") ? metadata.getAsJsonObject("animation") : null;
      var frameHeight = animation != null && animation.has("height") ? animation.get("height").getAsInt() : Math.min(width, height);
      frameHeight = frameHeight <= 0 || frameHeight > height ? Math.min(width, height) : frameHeight;
      frameHeight = Math.max(1, frameHeight);
      var frameCount = Math.max(1, height / frameHeight);
      var defaultFrameTime = animation != null && animation.has("frametime") ? Math.max(1, animation.get("frametime").getAsInt()) : 1;
      var frameTime = defaultFrameTime;
      var frameOrder = new int[frameCount];
      for (var i = 0; i < frameCount; i++) {
        frameOrder[i] = i;
      }
      if (animation != null && animation.has("frames")) {
        var frames = animation.getAsJsonArray("frames");
        var expandedFrameOrder = new ArrayList<Integer>(Math.max(1, frames.size()));
        for (var i = 0; i < frames.size(); i++) {
          var frame = frames.get(i);
          var frameIndex = frame.isJsonObject() ? frame.getAsJsonObject().get("index").getAsInt() : frame.getAsInt();
          var frameDuration = frame.isJsonObject() && frame.getAsJsonObject().has("time")
            ? Math.max(1, frame.getAsJsonObject().get("time").getAsInt())
            : defaultFrameTime;
          for (var duration = 0; duration < frameDuration; duration++) {
            expandedFrameOrder.add(frameIndex);
          }
        }
        if (!expandedFrameOrder.isEmpty()) {
          frameOrder = expandedFrameOrder.stream().mapToInt(Integer::intValue).toArray();
          frameTime = 1;
        }
      }

      var hasAlpha = false;
      var hasTranslucentPixels = false;
      for (var pixel : pixels) {
        var alpha = (pixel >>> 24) & 0xFF;
        if (alpha < 255) {
          hasAlpha = true;
          if (alpha > 0) {
            hasTranslucentPixels = true;
            break;
          }
        }
      }
      var textureImage = new TextureImage(width, height, frameHeight, frameCount, frameTime, frameOrder, pixels, hasAlpha, hasTranslucentPixels);
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
      if (pixels.length == 0 || width <= 0) {
        return 0;
      }

      var wrappedU = u - (float) Math.floor(u);
      var wrappedV = v - (float) Math.floor(v);
      var availableHeight = Math.max(1, pixels.length / width);
      var clampedFrameHeight = Math.clamp(frameHeight, 1, availableHeight);
      var availableFrameCount = Math.max(1, availableHeight / clampedFrameHeight);
      var frameOrderIndex = (int) ((tick / frameTime) % frameOrder.length);
      var frameIndex = Math.floorMod(frameOrder[frameOrderIndex], availableFrameCount);
      var yOffset = frameIndex * clampedFrameHeight;
      var x = Math.clamp((int) (wrappedU * width), 0, width - 1);
      var y = Math.clamp((int) (wrappedV * clampedFrameHeight), 0, clampedFrameHeight - 1) + yOffset;
      return pixels[x + y * width];
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }

    public boolean isAnimated() {
      return frameCount > 1 || frameOrder.length > 1;
    }

    public int animationFrameCount() {
      return Math.max(1, frameOrder.length);
    }

    public long animationCycleTicks() {
      return (long) animationFrameCount() * Math.max(1, frameTime);
    }

    public boolean hasAlpha() {
      return hasAlpha;
    }

    public boolean hasTranslucentPixels() {
      return hasTranslucentPixels;
    }

    public boolean isFullyTransparent() {
      for (var pixel : pixels) {
        if (((pixel >>> 24) & 0xFF) != 0) {
          return false;
        }
      }
      return true;
    }

    public AlphaCoverage alphaCoverage(float[] uv) {
      if (uv == null || uv.length < 2 || pixels.length == 0 || width <= 0) {
        return new AlphaCoverage(hasAlpha, hasTranslucentPixels);
      }

      var minU = Float.POSITIVE_INFINITY;
      var maxU = Float.NEGATIVE_INFINITY;
      var minV = Float.POSITIVE_INFINITY;
      var maxV = Float.NEGATIVE_INFINITY;
      for (var i = 0; i + 1 < uv.length; i += 2) {
        var u = uv[i];
        var v = uv[i + 1];
        if (!Float.isFinite(u) || !Float.isFinite(v)) {
          return new AlphaCoverage(hasAlpha, hasTranslucentPixels);
        }

        minU = Math.min(minU, u);
        maxU = Math.max(maxU, u);
        minV = Math.min(minV, v);
        maxV = Math.max(maxV, v);
      }

      if (minU < 0.0F || minV < 0.0F || maxU > 1.0F || maxV > 1.0F || maxU - minU > 1.0F || maxV - minV > 1.0F) {
        return new AlphaCoverage(hasAlpha, hasTranslucentPixels);
      }

      var availableHeight = Math.max(1, pixels.length / width);
      var clampedFrameHeight = Math.clamp(frameHeight, 1, availableHeight);
      var availableFrameCount = Math.max(1, availableHeight / clampedFrameHeight);
      var x0 = Math.clamp((int) Math.floor(minU * width), 0, width - 1);
      var x1 = Math.clamp((int) Math.ceil(maxU * width) - 1, x0, width - 1);
      var y0 = Math.clamp((int) Math.floor(minV * clampedFrameHeight), 0, clampedFrameHeight - 1);
      var y1 = Math.clamp((int) Math.ceil(maxV * clampedFrameHeight) - 1, y0, clampedFrameHeight - 1);
      var coveredHasAlpha = false;
      for (var frame = 0; frame < availableFrameCount; frame++) {
        var yOffset = frame * clampedFrameHeight;
        for (var y = y0; y <= y1; y++) {
          var rowOffset = (yOffset + y) * width;
          for (var x = x0; x <= x1; x++) {
            var alpha = (pixels[rowOffset + x] >>> 24) & 0xFF;
            if (alpha < 255) {
              coveredHasAlpha = true;
              if (alpha > 0) {
                return new AlphaCoverage(true, true);
              }
            }
          }
        }
      }

      return new AlphaCoverage(coveredHasAlpha, false);
    }

    public BufferedImage toBufferedImage() {
      if (bufferedImage == null) {
        bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        bufferedImage.setRGB(0, 0, width, height, pixels, 0, width);
      }
      return bufferedImage;
    }

    public record AlphaCoverage(boolean hasAlpha, boolean hasTranslucentPixels) {}
  }

  private record TextureBinding(String target, boolean forceTranslucent) {}

  private record ResolvedTexture(Identifier sprite, boolean forceTranslucent) {}

  private record ResolvedModel(Map<String, TextureBinding> textures, List<ModelElement> elements) {}

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
}
