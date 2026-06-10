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

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.stream.IntStream;

/// Vanilla-derived sky background and sky-only geometry for the software renderer.
public final class SkyRenderer {
  private static final float SKY_DISC_RADIUS = 512.0F;
  private static final float TOP_SKY_Y = 16.0F;
  private static final float DARK_DISC_Y = -4.0F;
  private static final float SUN_SIZE = 30.0F;
  private static final float MOON_SIZE = 20.0F;
  private static final float CELESTIAL_HEIGHT = 100.0F;
  private static final float END_FLASH_SIZE = 60.0F;
  private static final int STAR_COUNT = 1500;
  private static final RendererAssets.TextureImage WHITE_TEXTURE = RendererAssets.TextureImage.fromArgb(1, 1, new int[]{0xFFFFFFFF}, null);
  private static final StarQuad[] STAR_QUADS = buildStars();
  private static final Identifier SUN_TEXTURE = Identifier.withDefaultNamespace("environment/celestial/sun");
  private static final Identifier END_FLASH_TEXTURE = Identifier.withDefaultNamespace("environment/celestial/end_flash");
  private static final Identifier END_SKY_TEXTURE = Identifier.withDefaultNamespace("environment/end_sky");

  private SkyRenderer() {}

  public static void renderBackground(RenderContext ctx, RasterBuffers buffers) {
    var state = SkyState.create(ctx);
    var camera = ctx.camera();
    var pixels = buffers.colorBuffer();
    var width = camera.width();
    IntStream.range(0, camera.height()).parallel().forEach(y -> {
      var rowOffset = y * width;
      for (var x = 0; x < width; x++) {
        pixels[rowOffset + x] = sampleBackground(
          state,
          camera.sampleDirX(x, y),
          camera.sampleDirY(x, y),
          camera.sampleDirZ(x, y)
        );
      }
    });
  }

  public static RenderQuad[] collectSkyQuads(RenderContext ctx) {
    var state = SkyState.create(ctx);
    if (!state.shouldRenderSky()) {
      return new RenderQuad[0];
    }

    var quads = new ArrayList<RenderQuad>();
    if (state.skybox() == DimensionType.Skybox.END) {
      addEndSky(ctx.camera(), quads);
      addEndFlash(ctx.camera(), quads, state);
      return quads.toArray(RenderQuad[]::new);
    }

    addSunriseAndSunset(ctx.camera(), quads, state);
    addSunMoonAndStars(ctx.camera(), quads, state);
    if (state.shouldRenderDarkDisc()) {
      addDarkDisc(ctx.camera(), quads, state);
    }
    return quads.toArray(RenderQuad[]::new);
  }

  private static int sampleBackground(SkyState state, double dirX, double dirY, double dirZ) {
    if (!state.shouldRenderSky()) {
      return state.fogColor();
    }
    if (state.skybox() == DimensionType.Skybox.END) {
      return 0xFF000000;
    }

    var color = state.fogColor();
    if (dirY > 1.0E-5) {
      var t = TOP_SKY_Y / dirY;
      var localX = dirX * t;
      var localZ = dirZ * t;
      if (localX * localX + localZ * localZ <= SKY_DISC_RADIUS * SKY_DISC_RADIUS) {
        color = applySkyFog(state.skyColor(), state.fogColor(), (float) localX, TOP_SKY_Y, (float) localZ, state.skyFogEnd());
      }
    }

    if (state.shouldRenderDarkDisc() && dirY < -1.0E-5) {
      var t = DARK_DISC_Y / dirY;
      var localX = dirX * t;
      var localZ = dirZ * t;
      if (localX * localX + localZ * localZ <= SKY_DISC_RADIUS * SKY_DISC_RADIUS) {
        color = applySkyFog(0xFF000000, state.fogColor(), (float) localX, DARK_DISC_Y, (float) localZ, state.skyFogEnd());
      }
    }

    return color;
  }

  private static void addSunMoonAndStars(Camera camera, ArrayList<RenderQuad> quads, SkyState state) {
    var poseStack = new PoseStack();
    poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));

    poseStack.pushPose();
    poseStack.mulPose(Axis.XP.rotation(state.sunAngle()));
    addCelestialQuad(
      camera,
      quads,
      poseStack,
      SUN_SIZE,
      RendererAssets.instance().texture(SUN_TEXTURE),
      ARGB.white(state.rainBrightness()),
      false
    );
    poseStack.popPose();

    poseStack.pushPose();
    poseStack.mulPose(Axis.XP.rotation(state.moonAngle()));
    addCelestialQuad(
      camera,
      quads,
      poseStack,
      MOON_SIZE,
      RendererAssets.instance().texture(moonTexture(state.moonPhase())),
      ARGB.white(state.rainBrightness()),
      true
    );
    poseStack.popPose();

    if (state.starBrightness() > 0.0F) {
      poseStack.pushPose();
      poseStack.mulPose(Axis.XP.rotation(state.starAngle()));
      addStars(camera, quads, poseStack.last().pose(), state.starBrightness());
      poseStack.popPose();
    }
  }

  private static void addCelestialQuad(
    Camera camera,
    ArrayList<RenderQuad> quads,
    PoseStack poseStack,
    float size,
    RendererAssets.TextureImage texture,
    int color,
    boolean moonUv
  ) {
    poseStack.pushPose();
    poseStack.translate(0.0F, CELESTIAL_HEIGHT, 0.0F);
    poseStack.scale(size, 1.0F, size);
    var pose = poseStack.last().pose();
    var material = skyMaterial(texture, color, RenderMaterial.BlendState.from(BlendFunction.OVERLAY), 1);
    quads.add(new RenderQuad(
      skyVertex(camera, pose, -1.0F, 0.0F, -1.0F, moonUv ? 1.0F : 0.0F, moonUv ? 1.0F : 0.0F, 0xFFFFFFFF),
      skyVertex(camera, pose, 1.0F, 0.0F, -1.0F, moonUv ? 0.0F : 1.0F, moonUv ? 1.0F : 0.0F, 0xFFFFFFFF),
      skyVertex(camera, pose, 1.0F, 0.0F, 1.0F, moonUv ? 0.0F : 1.0F, moonUv ? 0.0F : 1.0F, 0xFFFFFFFF),
      skyVertex(camera, pose, -1.0F, 0.0F, 1.0F, moonUv ? 1.0F : 0.0F, moonUv ? 0.0F : 1.0F, 0xFFFFFFFF),
      material
    ));
    poseStack.popPose();
  }

  private static void addStars(Camera camera, ArrayList<RenderQuad> quads, Matrix4fc pose, float starBrightness) {
    var starColor = ARGB.colorFromFloat(starBrightness, starBrightness, starBrightness, starBrightness);
    var material = skyMaterial(WHITE_TEXTURE, starColor, RenderMaterial.BlendState.from(BlendFunction.OVERLAY), 0);
    for (var star : STAR_QUADS) {
      quads.add(new RenderQuad(
        skyVertex(camera, pose, star.v0(), 0xFFFFFFFF),
        skyVertex(camera, pose, star.v1(), 0xFFFFFFFF),
        skyVertex(camera, pose, star.v2(), 0xFFFFFFFF),
        skyVertex(camera, pose, star.v3(), 0xFFFFFFFF),
        material
      ));
    }
  }

  private static void addSunriseAndSunset(Camera camera, ArrayList<RenderQuad> quads, SkyState state) {
    var alpha = ARGB.alphaFloat(state.sunriseAndSunsetColor());
    if (alpha <= 0.001F) {
      return;
    }

    var poseStack = new PoseStack();
    poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
    poseStack.mulPose(Axis.ZP.rotationDegrees((Mth.sin(state.sunAngle()) < 0.0F ? 180.0F : 0.0F) + 90.0F));
    poseStack.scale(1.0F, 1.0F, alpha);
    var pose = poseStack.last().pose();
    var material = skyMaterial(WHITE_TEXTURE, state.sunriseAndSunsetColor(), RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT), 1);
    var center = skyVertex(camera, pose, 0.0F, 100.0F, 0.0F, 0.0F, 0.0F, ARGB.white(1.0F));

    var ring = new RenderVertex[17];
    for (var i = 0; i <= 16; i++) {
      var angle = i * (float) (Math.PI * 2.0) / 16.0F;
      var sinAngle = Mth.sin(angle);
      var cosAngle = Mth.cos(angle);
      ring[i] = skyVertex(camera, pose, sinAngle * 120.0F, cosAngle * 120.0F, -cosAngle * 40.0F, 0.0F, 0.0F, ARGB.white(0.0F));
    }

    for (var i = 0; i < 16; i++) {
      quads.add(new RenderQuad(center, ring[i], ring[i + 1], ring[i + 1], material));
    }
  }

  private static void addDarkDisc(Camera camera, ArrayList<RenderQuad> quads, SkyState state) {
    var poseStack = new PoseStack();
    poseStack.translate(0.0F, 12.0F, 0.0F);
    var pose = poseStack.last().pose();
    var material = skyMaterial(WHITE_TEXTURE, 0xFF000000, RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT), 0);
    addSkyDisc(camera, quads, pose, -16.0F, material);
  }

  private static void addSkyDisc(Camera camera, ArrayList<RenderQuad> quads, Matrix4fc pose, float y, RenderMaterial material) {
    var center = skyVertex(camera, pose, 0.0F, y, 0.0F, 0.0F, 0.0F, 0xFFFFFFFF);
    var ring = new RenderVertex[9];
    var xScale = Math.signum(y) * SKY_DISC_RADIUS;
    for (var i = 0; i < ring.length; i++) {
      var angle = (-180 + i * 45) * (float) (Math.PI / 180.0);
      ring[i] = skyVertex(camera, pose, xScale * Mth.cos(angle), y, SKY_DISC_RADIUS * Mth.sin(angle), 0.0F, 0.0F, 0xFFFFFFFF);
    }

    for (var i = 0; i < ring.length - 1; i++) {
      quads.add(new RenderQuad(center, ring[i], ring[i + 1], ring[i + 1], material));
    }
  }

  private static void addEndSky(Camera camera, ArrayList<RenderQuad> quads) {
    var texture = RendererAssets.instance().texture(END_SKY_TEXTURE);
    var material = skyMaterial(texture, -14145496, RenderMaterial.BlendState.from(BlendFunction.TRANSLUCENT), 0);
    for (var side = 0; side < 6; side++) {
      var poseStack = new PoseStack();
      switch (side) {
        case 1 -> poseStack.mulPose(Axis.XP.rotation((float) (Math.PI / 2.0)));
        case 2 -> poseStack.mulPose(Axis.XP.rotation((float) (-Math.PI / 2.0)));
        case 3 -> poseStack.mulPose(Axis.XP.rotation((float) Math.PI));
        case 4 -> poseStack.mulPose(Axis.ZP.rotation((float) (Math.PI / 2.0)));
        case 5 -> poseStack.mulPose(Axis.ZP.rotation((float) (-Math.PI / 2.0)));
        default -> {
        }
      }

      var pose = poseStack.last().pose();
      quads.add(new RenderQuad(
        skyVertex(camera, pose, -100.0F, -100.0F, -100.0F, 0.0F, 0.0F, 0xFFFFFFFF),
        skyVertex(camera, pose, -100.0F, -100.0F, 100.0F, 0.0F, 16.0F, 0xFFFFFFFF),
        skyVertex(camera, pose, 100.0F, -100.0F, 100.0F, 16.0F, 16.0F, 0xFFFFFFFF),
        skyVertex(camera, pose, 100.0F, -100.0F, -100.0F, 16.0F, 0.0F, 0xFFFFFFFF),
        material
      ));
    }
  }

  private static void addEndFlash(Camera camera, ArrayList<RenderQuad> quads, SkyState state) {
    if (state.endFlashIntensity() <= 1.0E-5F) {
      return;
    }

    var poseStack = new PoseStack();
    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.endFlashYAngle()));
    poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F - state.endFlashXAngle()));
    addCelestialQuad(
      camera,
      quads,
      poseStack,
      END_FLASH_SIZE,
      RendererAssets.instance().texture(END_FLASH_TEXTURE),
      ARGB.colorFromFloat(
        Mth.clamp(state.endFlashIntensity(), 0.0F, 1.0F),
        Mth.clamp(state.endFlashIntensity(), 0.0F, 1.0F),
        Mth.clamp(state.endFlashIntensity(), 0.0F, 1.0F),
        Mth.clamp(state.endFlashIntensity(), 0.0F, 1.0F)
      ),
      false
    );
  }

  private static RenderVertex skyVertex(Camera camera, Matrix4fc pose, Vector3f local, int color) {
    return skyVertex(camera, pose, local.x(), local.y(), local.z(), 0.0F, 0.0F, color);
  }

  private static RenderVertex skyVertex(Camera camera, Matrix4fc pose, float x, float y, float z, float u, float v, int color) {
    var transformed = pose.transformPosition(new Vector3f(x, y, z));
    return new RenderVertex(
      (float) (camera.eyeX() + transformed.x()),
      (float) (camera.eyeY() + transformed.y()),
      (float) (camera.eyeZ() + transformed.z()),
      u,
      v,
      color
    );
  }

  private static RenderMaterial skyMaterial(
    RendererAssets.TextureImage texture,
    int color,
    RenderMaterial.BlendState blendState,
    int alphaCutoutThreshold
  ) {
    return new RenderMaterial(
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      color,
      true,
      0.0F,
      0.0F,
      0.0F,
      alphaCutoutThreshold,
      RenderMaterial.DepthTest.ALWAYS_PASS,
      false,
      blendState,
      ColorTargetState.WRITE_ALL,
      RenderMaterial.UvTransform.IDENTITY
    );
  }

  private static int applySkyFog(int color, int fogColor, float x, float y, float z, float fogEnd) {
    if (fogEnd <= 0.0F) {
      return fogColor;
    }

    var spherical = (float) Math.sqrt(x * x + y * y + z * z);
    var cylindrical = Math.max((float) Math.sqrt(x * x + z * z), Math.abs(y));
    var sphericalFog = Mth.clamp(spherical / fogEnd, 0.0F, 1.0F);
    var cylindricalFog = cylindrical >= fogEnd ? 1.0F : 0.0F;
    var fogValue = Math.max(sphericalFog, cylindricalFog) * ARGB.alphaFloat(fogColor);
    return ARGB.srgbLerp(fogValue, color, ARGB.opaque(fogColor));
  }

  private static int atmosphericFogColor(RenderContext ctx) {
    var probe = ctx.environmentProbe();
    var fogColor = probe.getValue(EnvironmentAttributes.FOG_COLOR, 1.0F);
    var renderDistanceChunks = ctx.maxDistance() / 16.0F;
    if (renderDistanceChunks >= 4.0F) {
      var sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, 1.0F) * (float) (Math.PI / 180.0);
      var sunX = Mth.sin(sunAngle) > 0.0F ? -1.0F : 1.0F;
      var lookingAtSunFactor = (float) (ctx.camera().forwardX() * sunX);
      if (lookingAtSunFactor > 0.0F) {
        var sunriseColor = probe.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, 1.0F);
        var alpha = ARGB.alphaFloat(sunriseColor);
        if (alpha > 0.0F) {
          fogColor = ARGB.srgbLerp(lookingAtSunFactor * alpha, fogColor, ARGB.opaque(sunriseColor));
        }
      }
    }

    var skyColor = applyWeatherDarken(
      probe.getValue(EnvironmentAttributes.SKY_COLOR, 1.0F),
      ctx.level().getRainLevel(1.0F),
      ctx.level().getThunderLevel(1.0F)
    );
    var skyFogEnd = Math.min(probe.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, 1.0F) / 16.0F, renderDistanceChunks);
    var skyColorMixFactor = Mth.clampedLerp(skyFogEnd / 32.0F, 0.25F, 1.0F);
    skyColorMixFactor = 1.0F - (float) Math.pow(skyColorMixFactor, 0.25);
    return ARGB.opaque(ARGB.srgbLerp(skyColorMixFactor, fogColor, skyColor));
  }

  private static int applyWeatherDarken(int color, float rainLevel, float thunderLevel) {
    if (rainLevel > 0.0F) {
      color = ARGB.scaleRGB(color, 1.0F - rainLevel * 0.5F, 1.0F - rainLevel * 0.5F, 1.0F - rainLevel * 0.4F);
    }
    if (thunderLevel > 0.0F) {
      color = ARGB.scaleRGB(color, 1.0F - thunderLevel * 0.5F);
    }
    return color;
  }

  private static Identifier moonTexture(MoonPhase phase) {
    return Identifier.withDefaultNamespace("environment/celestial/moon/" + phase.getSerializedName());
  }

  private static StarQuad[] buildStars() {
    var random = RandomSource.createThreadLocalInstance(10842L);
    var quads = new ArrayList<StarQuad>();
    for (var i = 0; i < STAR_COUNT; i++) {
      var x = random.nextFloat() * 2.0F - 1.0F;
      var y = random.nextFloat() * 2.0F - 1.0F;
      var z = random.nextFloat() * 2.0F - 1.0F;
      var size = 0.15F + random.nextFloat() * 0.1F;
      var lengthSq = Mth.lengthSquared(x, y, z);
      if (lengthSq <= 0.010000001F || lengthSq >= 1.0F) {
        continue;
      }

      var center = new Vector3f(x, y, z).normalize(100.0F);
      var zRot = (float) (random.nextDouble() * Math.PI * 2.0);
      var rotation = new Matrix3f().rotateTowards(new Vector3f(center).negate(), new Vector3f(0.0F, 1.0F, 0.0F)).rotateZ(-zRot);
      quads.add(new StarQuad(
        new Vector3f(size, -size, 0.0F).mul(rotation).add(center),
        new Vector3f(size, size, 0.0F).mul(rotation).add(center),
        new Vector3f(-size, size, 0.0F).mul(rotation).add(center),
        new Vector3f(-size, -size, 0.0F).mul(rotation).add(center)
      ));
    }
    return quads.toArray(StarQuad[]::new);
  }

  private record StarQuad(Vector3f v0, Vector3f v1, Vector3f v2, Vector3f v3) {}

  private record SkyState(
    DimensionType.Skybox skybox,
    boolean shouldRenderSky,
    boolean shouldRenderDarkDisc,
    float sunAngle,
    float moonAngle,
    float starAngle,
    float rainBrightness,
    float starBrightness,
    int sunriseAndSunsetColor,
    MoonPhase moonPhase,
    int skyColor,
    int fogColor,
    float skyFogEnd,
    float endFlashIntensity,
    float endFlashXAngle,
    float endFlashYAngle
  ) {
    private static SkyState create(RenderContext ctx) {
      var probe = ctx.environmentProbe();
      var skybox = ctx.level().dimensionType().skybox();
      var shouldRenderSky = skybox != DimensionType.Skybox.NONE && !doesMobEffectBlockSky(ctx);
      var endFlashState = ctx.level().endFlashState();
      var endFlashIntensity = endFlashState != null ? endFlashState.getIntensity(1.0F) : 0.0F;
      var endFlashXAngle = endFlashState != null ? endFlashState.getXAngle() : 0.0F;
      var endFlashYAngle = endFlashState != null ? endFlashState.getYAngle() : 0.0F;
      return new SkyState(
        skybox,
        shouldRenderSky,
        shouldRenderSky && ctx.camera().eyeY() - ctx.level().getLevelData().getHorizonHeight(ctx.level()) < 0.0,
        probe.getValue(EnvironmentAttributes.SUN_ANGLE, 1.0F) * (float) (Math.PI / 180.0),
        probe.getValue(EnvironmentAttributes.MOON_ANGLE, 1.0F) * (float) (Math.PI / 180.0),
        probe.getValue(EnvironmentAttributes.STAR_ANGLE, 1.0F) * (float) (Math.PI / 180.0),
        1.0F - ctx.level().getRainLevel(1.0F),
        probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, 1.0F),
        probe.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, 1.0F),
        probe.getValue(EnvironmentAttributes.MOON_PHASE, 1.0F),
        ARGB.opaque(probe.getValue(EnvironmentAttributes.SKY_COLOR, 1.0F)),
        atmosphericFogColor(ctx),
        Math.min(ctx.maxDistance(), probe.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, 1.0F)),
        endFlashIntensity,
        endFlashXAngle,
        endFlashYAngle
      );
    }

    private static boolean doesMobEffectBlockSky(RenderContext ctx) {
      var player = ctx.localPlayer();
      return player != null && (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS));
    }
  }
}
