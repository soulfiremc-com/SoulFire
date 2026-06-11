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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Writes a render frame and the exact CPU scene data used to produce it.
public final class RendererDebugDump {
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
  private static final RasterPipeline RASTER_PIPELINE = new RasterPipeline();

  private RendererDebugDump() {}

  public static Result dump(
    ClientLevel level,
    LocalPlayer player,
    int width,
    int height,
    double fov,
    int maxDistance,
    Path outputDirectory
  ) throws IOException {
    Files.createDirectories(outputDirectory);
    var texturesDirectory = outputDirectory.resolve("textures");
    var runtimeTexturesDirectory = outputDirectory.resolve("runtime-textures");
    var atlasDirectory = outputDirectory.resolve("atlases");
    Files.createDirectories(texturesDirectory);
    Files.createDirectories(runtimeTexturesDirectory);
    Files.createDirectories(atlasDirectory);

    var debugTrace = RenderDebugTrace.createForced(width, height, maxDistance, player.getYRot(), player.getXRot());
    RenderDebugTrace.bind(debugTrace);
    var renderStart = System.nanoTime();
    try {
      var camera = new Camera(player.getEyePosition(), player.getYRot(), player.getXRot(), width, height, fov, maxDistance + 32.0F);
      var ctx = RenderContext.create(level, player, camera, maxDistance);

      var blockEntityCollectStart = System.nanoTime();
      var blockEntityScene = SceneCollector.collectBlockEntities(ctx);
      var blockEntityCollectNanos = System.nanoTime() - blockEntityCollectStart;

      var worldCollectStart = System.nanoTime();
      var worldScene = WorldMeshCollector.collect(ctx);
      var worldCollectNanos = System.nanoTime() - worldCollectStart;
      debugTrace.worldCollectNanos(worldCollectNanos);

      var dynamicCollectStart = System.nanoTime();
      var dynamicScene = SceneCollector.collectEntitiesAndWeather(ctx, player);
      var dynamicCollectNanos = System.nanoTime() - dynamicCollectStart;

      var cloudCollectStart = System.nanoTime();
      var cloudScene = CloudMeshCollector.collect(ctx);
      var cloudCollectNanos = System.nanoTime() - cloudCollectStart;
      debugTrace.dynamicCollectNanos(blockEntityCollectNanos + dynamicCollectNanos + cloudCollectNanos);

      var sceneData = worldScene.merge(blockEntityScene).merge(dynamicScene).merge(cloudScene);
      var buffers = new RasterBuffers(width, height);

      var rasterStart = System.nanoTime();
      RASTER_PIPELINE.render(ctx, sceneData, buffers);
      var rasterNanos = System.nanoTime() - rasterStart;
      var totalNanos = System.nanoTime() - renderStart;
      debugTrace.rasterNanos(rasterNanos);
      debugTrace.totalNanos(totalNanos);
      debugTrace.logSummary(sceneData);

      var framePath = outputDirectory.resolve("frame.png");
      ImageIO.write(buffers.image(), "png", framePath.toFile());

      var writer = new SceneDumpWriter(texturesDirectory, runtimeTexturesDirectory, atlasDirectory, ctx, camera, fov, maxDistance);
      var sceneJson = writer.writeScene(
        worldScene,
        blockEntityScene,
        dynamicScene,
        cloudScene,
        sceneData,
        new TimingNanos(worldCollectNanos, blockEntityCollectNanos, dynamicCollectNanos, cloudCollectNanos, rasterNanos, totalNanos),
        debugTrace.snapshot()
      );
      var scenePath = outputDirectory.resolve("scene.json");
      Files.writeString(scenePath, GSON.toJson(sceneJson));

      return new Result(
        outputDirectory,
        framePath,
        scenePath,
        sceneData.totalQuadCount(),
        writer.textureCount(),
        writer.runtimeTextureCount(),
        writer.atlasCount()
      );
    } finally {
      RenderDebugTrace.unbind();
    }
  }

  public record Result(
    Path directory,
    Path frame,
    Path scene,
    int quadCount,
    int textureCount,
    int runtimeTextureCount,
    int atlasCount
  ) {}

  private record TimingNanos(
    long worldCollect,
    long blockEntityCollect,
    long dynamicCollect,
    long cloudCollect,
    long raster,
    long total
  ) {}

  private static final class SceneDumpWriter {
    private final Path texturesDirectory;
    private final Path runtimeTexturesDirectory;
    private final Path atlasDirectory;
    private final RenderContext ctx;
    private final Camera camera;
    private final double fov;
    private final int maxDistance;
    private final IdentityHashMap<RendererAssets.TextureImage, String> textureIds = new IdentityHashMap<>();
    private final Map<RenderMaterial, String> materialIds = new LinkedHashMap<>();
    private final JsonArray textures = new JsonArray();
    private final JsonArray materials = new JsonArray();
    private final JsonArray runtimeTextures = new JsonArray();
    private final JsonArray atlases = new JsonArray();

    private SceneDumpWriter(
      Path texturesDirectory,
      Path runtimeTexturesDirectory,
      Path atlasDirectory,
      RenderContext ctx,
      Camera camera,
      double fov,
      int maxDistance
    ) {
      this.texturesDirectory = texturesDirectory;
      this.runtimeTexturesDirectory = runtimeTexturesDirectory;
      this.atlasDirectory = atlasDirectory;
      this.ctx = ctx;
      this.camera = camera;
      this.fov = fov;
      this.maxDistance = maxDistance;
    }

    private JsonObject writeScene(
      SceneData worldScene,
      SceneData blockEntityScene,
      SceneData dynamicScene,
      SceneData cloudScene,
      SceneData sceneData,
      TimingNanos timing,
      RenderDebugTrace.Snapshot trace
    ) throws IOException {
      var root = new JsonObject();
      root.addProperty("createdAt", Instant.now().toString());
      root.add("camera", cameraJson());
      root.add("context", contextJson());
      root.add("timingMs", timingJson(timing));
      root.add("trace", GSON.toJsonTree(trace));
      root.add("sourceSceneCounts", sourceSceneCountsJson(worldScene, blockEntityScene, dynamicScene, cloudScene));
      root.add("sceneCounts", sceneCountsJson(sceneData));

      var passes = new JsonObject();
      passes.add("opaque", quadsJson(sceneData.opaque(), "opaque"));
      passes.add("cutout", quadsJson(sceneData.cutout(), "cutout"));
      passes.add("translucent", quadsJson(sceneData.translucent(), "translucent"));
      passes.add("terrainTranslucent", quadsJson(sceneData.terrainTranslucent(), "terrainTranslucent"));
      passes.add("translucentParticles", quadsJson(sceneData.translucentParticles(), "translucentParticles"));
      passes.add("clouds", quadsJson(sceneData.clouds(), "clouds"));
      passes.add("weather", quadsJson(sceneData.weather(), "weather"));
      root.add("passes", passes);

      writeAtlases();
      writeRuntimeTextures();
      root.add("materials", materials);
      root.add("textures", textures);
      root.add("atlases", atlases);
      root.add("runtimeTextures", runtimeTextures);
      return root;
    }

    private int textureCount() {
      return textures.size();
    }

    private int runtimeTextureCount() {
      return runtimeTextures.size();
    }

    private int atlasCount() {
      return atlases.size();
    }

    private JsonObject cameraJson() {
      var json = new JsonObject();
      json.addProperty("width", camera.width());
      json.addProperty("height", camera.height());
      json.addProperty("fov", fov);
      json.addProperty("maxDistance", maxDistance);
      json.addProperty("farPlane", camera.farPlane());
      json.addProperty("eyeX", camera.eyeX());
      json.addProperty("eyeY", camera.eyeY());
      json.addProperty("eyeZ", camera.eyeZ());
      json.addProperty("yaw", camera.yRot());
      json.addProperty("pitch", camera.xRot());
      json.add("forward", vectorJson(camera.forwardX(), camera.forwardY(), camera.forwardZ()));
      json.add("screenLeft", vectorJson(camera.screenLeftX(), camera.screenLeftY(), camera.screenLeftZ()));
      json.add("up", vectorJson(camera.upX(), camera.upY(), camera.upZ()));
      return json;
    }

    private JsonObject contextJson() {
      var player = ctx.localPlayer();
      var json = new JsonObject();
      json.addProperty("levelMinY", ctx.minY());
      json.addProperty("levelMaxY", ctx.maxY());
      json.addProperty("animationTick", ctx.animationTick());
      json.addProperty("playerName", player.getGameProfile().name());
      json.addProperty("playerUuid", player.getUUID().toString());
      json.addProperty("playerX", player.getX());
      json.addProperty("playerY", player.getY());
      json.addProperty("playerZ", player.getZ());
      json.addProperty("playerYaw", player.getYRot());
      json.addProperty("playerPitch", player.getXRot());
      return json;
    }

    private JsonObject timingJson(TimingNanos timing) {
      var json = new JsonObject();
      json.addProperty("worldCollect", nanosToMillis(timing.worldCollect()));
      json.addProperty("blockEntityCollect", nanosToMillis(timing.blockEntityCollect()));
      json.addProperty("dynamicCollect", nanosToMillis(timing.dynamicCollect()));
      json.addProperty("cloudCollect", nanosToMillis(timing.cloudCollect()));
      json.addProperty("raster", nanosToMillis(timing.raster()));
      json.addProperty("total", nanosToMillis(timing.total()));
      return json;
    }

    private JsonObject sourceSceneCountsJson(
      SceneData worldScene,
      SceneData blockEntityScene,
      SceneData dynamicScene,
      SceneData cloudScene
    ) {
      var json = new JsonObject();
      json.add("world", sceneCountsJson(worldScene));
      json.add("blockEntities", sceneCountsJson(blockEntityScene));
      json.add("entitiesAndWeather", sceneCountsJson(dynamicScene));
      json.add("clouds", sceneCountsJson(cloudScene));
      return json;
    }

    private JsonObject sceneCountsJson(SceneData sceneData) {
      var json = new JsonObject();
      json.addProperty("opaque", sceneData.opaque().length);
      json.addProperty("cutout", sceneData.cutout().length);
      json.addProperty("translucent", sceneData.translucent().length);
      json.addProperty("terrainTranslucent", sceneData.terrainTranslucent().length);
      json.addProperty("translucentParticles", sceneData.translucentParticles().length);
      json.addProperty("clouds", sceneData.clouds().length);
      json.addProperty("weather", sceneData.weather().length);
      json.addProperty("total", sceneData.totalQuadCount());
      return json;
    }

    private JsonArray quadsJson(RenderQuad[] quads, String pass) throws IOException {
      var array = new JsonArray();
      for (var i = 0; i < quads.length; i++) {
        array.add(quadJson(quads[i], pass, i));
      }
      return array;
    }

    private JsonObject quadJson(RenderQuad quad, String pass, int index) throws IOException {
      var material = quad.material();
      var uv = new float[]{
        quad.v0().u(), quad.v0().v(),
        quad.v1().u(), quad.v1().v(),
        quad.v2().u(), quad.v2().v(),
        quad.v3().u(), quad.v3().v()
      };
      var coverage = material.texture().alphaCoverage(uv);
      var centerU = (quad.v0().u() + quad.v1().u() + quad.v2().u() + quad.v3().u()) / 4.0F;
      var centerV = (quad.v0().v() + quad.v1().v() + quad.v2().v() + quad.v3().v()) / 4.0F;

      var json = new JsonObject();
      json.addProperty("pass", pass);
      json.addProperty("index", index);
      json.addProperty("material", materialId(material));
      json.add("uvBounds", uvBoundsJson(uv));
      json.add("alphaCoverage", alphaCoverageJson(coverage));
      json.addProperty("centerSampleArgb", hexArgb(material.texture().sample(centerU, centerV, ctx.animationTick())));
      json.add("vertices", verticesJson(quad));
      return json;
    }

    private JsonArray verticesJson(RenderQuad quad) {
      var vertices = new JsonArray();
      vertices.add(vertexJson(quad.v0(), quad.material()));
      vertices.add(vertexJson(quad.v1(), quad.material()));
      vertices.add(vertexJson(quad.v2(), quad.material()));
      vertices.add(vertexJson(quad.v3(), quad.material()));
      return vertices;
    }

    private JsonObject vertexJson(RenderVertex vertex, RenderMaterial material) {
      var json = new JsonObject();
      json.addProperty("x", vertex.x());
      json.addProperty("y", vertex.y());
      json.addProperty("z", vertex.z());
      json.addProperty("u", vertex.u());
      json.addProperty("v", vertex.v());
      json.addProperty("color", hexArgb(vertex.color()));
      json.addProperty("overlayColor", hexArgb(vertex.overlayColor()));
      json.addProperty("sampleArgb", hexArgb(material.texture().sample(vertex.u(), vertex.v(), ctx.animationTick())));
      return json;
    }

    private String materialId(RenderMaterial material) throws IOException {
      var existing = materialIds.get(material);
      if (existing != null) {
        return existing;
      }

      var id = "material_%04d".formatted(materialIds.size());
      materialIds.put(material, id);
      materials.add(materialJson(id, material));
      return id;
    }

    private JsonObject materialJson(String id, RenderMaterial material) throws IOException {
      var json = new JsonObject();
      json.addProperty("id", id);
      json.addProperty("texture", textureId(material.texture()));
      json.addProperty("alphaMode", material.alphaMode().name());
      json.addProperty("color", hexArgb(material.color()));
      json.addProperty("doubleSided", material.doubleSided());
      json.addProperty("depthBias", material.depthBias());
      json.addProperty("polygonOffsetFactor", material.polygonOffsetFactor());
      json.addProperty("polygonOffsetUnits", material.polygonOffsetUnits());
      json.addProperty("alphaCutoutThreshold", material.alphaCutoutThreshold());
      json.addProperty("alphaCutoutSource", material.alphaCutoutSource().name());
      json.addProperty("depthTest", material.depthTest().name());
      json.addProperty("depthWrite", material.depthWrite());
      json.add("blendState", blendStateJson(material.blendState()));
      json.add("debugFlags", materialDebugFlagsJson(material));
      json.addProperty("colorWriteMask", hexMask(material.colorWriteMask()));
      json.add("uvTransform", uvTransformJson(material.uvTransform()));
      json.addProperty("sortOnUpload", material.sortOnUpload());
      json.addProperty("sortGroup", material.sortGroup());
      json.addProperty("viewScale", material.viewScale());
      var dissolveMaskTexture = material.dissolveMaskTexture();
      if (dissolveMaskTexture != null) {
        json.addProperty("dissolveMaskTexture", textureId(dissolveMaskTexture));
      }
      return json;
    }

    private JsonObject materialDebugFlagsJson(RenderMaterial material) {
      var texture = material.texture();
      var json = new JsonObject();
      json.addProperty("blended", material.blendState().blends());
      json.addProperty("textureHasAlpha", texture.hasAlpha());
      json.addProperty("textureHasTranslucentPixels", texture.hasTranslucentPixels());
      json.addProperty("textureAlphaCanAffectCutout", material.alphaCutoutSource() == RenderMaterial.AlphaCutoutSource.TEXTURE);
      json.addProperty("usesDissolveMask", material.dissolveMaskTexture() != null);
      json.addProperty("writesAlpha", (material.colorWriteMask() & ColorTargetState.WRITE_ALPHA) != 0);
      json.addProperty(
        "writesColor",
        (material.colorWriteMask() & (ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN | ColorTargetState.WRITE_BLUE)) != 0
      );
      json.addProperty("likelyOpaqueButBlended", material.alphaMode() != RendererAssets.AlphaMode.TRANSLUCENT && material.blendState().blends());
      json.addProperty("translucentDepthWrite", material.alphaMode() == RendererAssets.AlphaMode.TRANSLUCENT && material.depthWrite());
      return json;
    }

    private JsonObject blendStateJson(RenderMaterial.BlendState blendState) {
      var json = new JsonObject();
      json.addProperty("blends", blendState.blends());
      json.addProperty("sourceColor", blendState.sourceColor().name());
      json.addProperty("destColor", blendState.destColor().name());
      json.addProperty("sourceAlpha", blendState.sourceAlpha().name());
      json.addProperty("destAlpha", blendState.destAlpha().name());
      return json;
    }

    private JsonObject uvTransformJson(RenderMaterial.UvTransform transform) {
      var json = new JsonObject();
      json.addProperty("uFromU", transform.uFromU());
      json.addProperty("uFromV", transform.uFromV());
      json.addProperty("vFromU", transform.vFromU());
      json.addProperty("vFromV", transform.vFromV());
      json.addProperty("uOffsetScale", transform.uOffsetScale());
      json.addProperty("vOffsetScale", transform.vOffsetScale());
      json.addProperty("uPeriodTicks", transform.uPeriodTicks());
      json.addProperty("vPeriodTicks", transform.vPeriodTicks());
      return json;
    }

    private String textureId(RendererAssets.TextureImage texture) throws IOException {
      var existing = textureIds.get(texture);
      if (existing != null) {
        return existing;
      }

      var id = "texture_%04d".formatted(textureIds.size());
      textureIds.put(texture, id);
      var fileName = id + ".png";
      var image = texture.toBufferedImage();
      ImageIO.write(image, "png", texturesDirectory.resolve(fileName).toFile());

      var json = textureJson(id, fileName, texture, image);
      textures.add(json);
      return id;
    }

    private JsonObject textureJson(String id, String fileName, RendererAssets.TextureImage texture, BufferedImage image) {
      var stats = alphaStats(image);
      var json = new JsonObject();
      json.addProperty("id", id);
      json.addProperty("file", "textures/" + fileName);
      json.addProperty("width", texture.width());
      json.addProperty("height", texture.height());
      json.addProperty("animated", texture.isAnimated());
      json.addProperty("animationFrameCount", texture.animationFrameCount());
      json.addProperty("animationCycleTicks", texture.animationCycleTicks());
      json.addProperty("hasAlpha", texture.hasAlpha());
      json.addProperty("hasTranslucentPixels", texture.hasTranslucentPixels());
      json.add("alphaStats", stats);
      return json;
    }

    private void writeRuntimeTextures() throws IOException {
      for (var snapshot : RendererRuntimeTextureMirror.debugSnapshots()) {
        var json = new JsonObject();
        json.addProperty("location", snapshot.location().toString());
        json.addProperty("width", snapshot.width());
        json.addProperty("height", snapshot.height());
        json.addProperty("format", snapshot.format());
        json.addProperty("hasUploadData", snapshot.hasUploadData());
        var texture = snapshot.texture();
        if (texture != null) {
          var fileName = "runtime_%04d.png".formatted(runtimeTextures.size());
          var image = texture.toBufferedImage();
          ImageIO.write(image, "png", runtimeTexturesDirectory.resolve(fileName).toFile());
          json.addProperty("file", "runtime-textures/" + fileName);
          json.add("alphaStats", alphaStats(image));
        }
        runtimeTextures.add(json);
      }
    }

    private void writeAtlases() {
      for (var location : List.of(TextureAtlas.LOCATION_BLOCKS, TextureAtlas.LOCATION_ITEMS, TextureAtlas.LOCATION_PARTICLES)) {
        atlases.add(atlasJson(location));
      }
    }

    private JsonObject atlasJson(Identifier location) {
      var json = new JsonObject();
      json.addProperty("location", location.toString());
      try {
        var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(location);
        var texture = RendererAssets.instance().textureAtlas(location);
        var fileName = location.toDebugFileName() + ".png";
        ImageIO.write(texture.toBufferedImage(), "png", atlasDirectory.resolve(fileName).toFile());
        json.addProperty("file", "atlases/" + fileName);
        json.addProperty("width", texture.width());
        json.addProperty("height", texture.height());
        json.addProperty("spriteCount", atlas.sprites.size());
        var sprites = new JsonArray();
        for (var sprite : atlas.sprites) {
          sprites.add(spriteJson(sprite, texture.width(), texture.height()));
        }
        json.add("sprites", sprites);
      } catch (Throwable t) {
        json.addProperty("error", t.getClass().getName() + ": " + t.getMessage());
      }
      return json;
    }

    private JsonObject spriteJson(TextureAtlasSprite sprite, int atlasWidth, int atlasHeight) {
      var contents = sprite.contents();
      var json = new JsonObject();
      json.addProperty("name", contents.name().toString());
      json.addProperty("x", sprite.getX());
      json.addProperty("y", sprite.getY());
      json.addProperty("width", contents.width());
      json.addProperty("height", contents.height());
      json.addProperty("u0", sprite.getU0());
      json.addProperty("u1", sprite.getU1());
      json.addProperty("v0", sprite.getV0());
      json.addProperty("v1", sprite.getV1());
      json.addProperty("atlasWidth", atlasWidth);
      json.addProperty("atlasHeight", atlasHeight);
      json.addProperty("animated", contents.isAnimated());
      json.addProperty("transparency", contents.transparency().toString());
      return json;
    }

    private JsonObject alphaStats(BufferedImage image) {
      var transparent = 0;
      var translucent = 0;
      var opaque = 0;
      for (var y = 0; y < image.getHeight(); y++) {
        for (var x = 0; x < image.getWidth(); x++) {
          var alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
          if (alpha == 0) {
            transparent++;
          } else if (alpha == 255) {
            opaque++;
          } else {
            translucent++;
          }
        }
      }

      var json = new JsonObject();
      json.addProperty("transparent", transparent);
      json.addProperty("translucent", translucent);
      json.addProperty("opaque", opaque);
      return json;
    }

    private JsonObject alphaCoverageJson(RendererAssets.TextureImage.AlphaCoverage coverage) {
      var json = new JsonObject();
      json.addProperty("hasAlpha", coverage.hasAlpha());
      json.addProperty("hasTranslucentPixels", coverage.hasTranslucentPixels());
      return json;
    }

    private JsonObject uvBoundsJson(float[] uv) {
      var minU = Float.POSITIVE_INFINITY;
      var maxU = Float.NEGATIVE_INFINITY;
      var minV = Float.POSITIVE_INFINITY;
      var maxV = Float.NEGATIVE_INFINITY;
      for (var i = 0; i + 1 < uv.length; i += 2) {
        minU = Math.min(minU, uv[i]);
        maxU = Math.max(maxU, uv[i]);
        minV = Math.min(minV, uv[i + 1]);
        maxV = Math.max(maxV, uv[i + 1]);
      }

      var json = new JsonObject();
      json.addProperty("minU", minU);
      json.addProperty("maxU", maxU);
      json.addProperty("minV", minV);
      json.addProperty("maxV", maxV);
      return json;
    }

    private JsonArray vectorJson(double x, double y, double z) {
      var json = new JsonArray();
      json.add(x);
      json.add(y);
      json.add(z);
      return json;
    }
  }

  private static String hexArgb(int color) {
    return "0x%08X".formatted(color);
  }

  private static String hexMask(int mask) {
    return "0x%X".formatted(mask);
  }

  private static long nanosToMillis(long nanos) {
    return nanos / 1_000_000L;
  }
}
