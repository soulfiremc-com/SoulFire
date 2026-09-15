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

import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

/// CPU mirror of Mojang's lightmap shader for software-rendered geometry.
final class VanillaLightmap {
  private static final float EPSILON = 1.0E-6F;

  private VanillaLightmap() {}

  static int color(RenderContext ctx, int lightCoords, int emission) {
    var litCoords = LightCoordsUtil.lightCoordsWithEmission(lightCoords, Math.clamp(emission, 0, 15));
    var texture = ctx.lightmapTexture();
    if (texture != null) {
      var block = Math.clamp(LightCoordsUtil.smoothBlock(litCoords), 0, 240);
      var sky = Math.clamp(LightCoordsUtil.smoothSky(litCoords), 0, 240);
      return texture.sample((block + 8) / 256.0F, (sky + 8) / 256.0F, 0);
    }

    return fallbackColor(ctx, litCoords);
  }

  @Nullable
  static RendererAssets.TextureImage texture(@Nullable LightmapRenderState renderState) {
    if (renderState == null || !hasExtractedState(renderState)) {
      return null;
    }
    var pixels = new int[16 * 16];
    for (var sky = 0; sky < 16; sky++) {
      for (var block = 0; block < 16; block++) {
        pixels[sky * 16 + block] = shaderColor(renderState, LightCoordsUtil.pack(block, sky));
      }
    }
    return RendererAssets.TextureImage.fromArgb(16, 16, pixels, null)
      .withAddressMode(RendererAssets.TextureAddressMode.CLAMP_TO_EDGE).withLinearFiltering();
  }

  private static boolean hasExtractedState(LightmapRenderState renderState) {
    return renderState.blockFactor != 0.0F
      || renderState.skyFactor != 0.0F
      || !isWhite(renderState.ambientColor)
      || renderState.brightness != 0.0F
      || renderState.darknessEffectScale != 0.0F
      || renderState.nightVisionEffectIntensity != 0.0F
      || renderState.bossOverlayWorldDarkening != 0.0F;
  }

  private static boolean isWhite(@Nullable Vector3fc vector) {
    return vector != null
      && Math.abs(vector.x() - 1.0F) <= EPSILON
      && Math.abs(vector.y() - 1.0F) <= EPSILON
      && Math.abs(vector.z() - 1.0F) <= EPSILON;
  }

  private static int shaderColor(LightmapRenderState renderState, int lightCoords) {
    var blockLevel = LightCoordsUtil.block(lightCoords) / 15.0F;
    var skyLevel = LightCoordsUtil.sky(lightCoords) / 15.0F;
    var blockBrightness = brightness(blockLevel) * renderState.blockFactor;
    var skyBrightness = brightness(skyLevel) * renderState.skyFactor;

    var r = Math.max(component(renderState.ambientColor, 0), component(renderState.nightVisionColor, 0) * renderState.nightVisionEffectIntensity);
    var g = Math.max(component(renderState.ambientColor, 1), component(renderState.nightVisionColor, 1) * renderState.nightVisionEffectIntensity);
    var b = Math.max(component(renderState.ambientColor, 2), component(renderState.nightVisionColor, 2) * renderState.nightVisionEffectIntensity);

    r += component(renderState.skyLightColor, 0) * skyBrightness;
    g += component(renderState.skyLightColor, 1) * skyBrightness;
    b += component(renderState.skyLightColor, 2) * skyBrightness;

    var blockMix = 0.9F * parabolicMixFactor(blockLevel);
    r += lerp(blockMix, component(renderState.blockLightTint, 0), 1.0F) * blockBrightness;
    g += lerp(blockMix, component(renderState.blockLightTint, 1), 1.0F) * blockBrightness;
    b += lerp(blockMix, component(renderState.blockLightTint, 2), 1.0F) * blockBrightness;

    r = lerp(renderState.bossOverlayWorldDarkening, r, r * 0.7F);
    g = lerp(renderState.bossOverlayWorldDarkening, g, g * 0.6F);
    b = lerp(renderState.bossOverlayWorldDarkening, b, b * 0.6F);

    r = Math.clamp(r - renderState.darknessEffectScale, 0.0F, 1.0F);
    g = Math.clamp(g - renderState.darknessEffectScale, 0.0F, 1.0F);
    b = Math.clamp(b - renderState.darknessEffectScale, 0.0F, 1.0F);

    var notGamma = notGamma(r, g, b);
    r = lerp(renderState.brightness, r, notGamma[0]);
    g = lerp(renderState.brightness, g, notGamma[1]);
    b = lerp(renderState.brightness, b, notGamma[2]);

    return ARGB.color(255, (int) Math.rint(Math.clamp(r, 0.0F, 1.0F) * 255.0F),
      (int) Math.rint(Math.clamp(g, 0.0F, 1.0F) * 255.0F), (int) Math.rint(Math.clamp(b, 0.0F, 1.0F) * 255.0F));
  }

  private static float brightness(float level) {
    return level / (4.0F - 3.0F * level);
  }

  private static float parabolicMixFactor(float level) {
    var curve = 2.0F * level - 1.0F;
    return curve * curve;
  }

  private static float lerp(float delta, float start, float end) {
    return start + delta * (end - start);
  }

  private static float component(Vector3fc vector, int component) {
    return switch (component) {
      case 0 -> vector.x();
      case 1 -> vector.y();
      case 2 -> vector.z();
      default -> throw new IndexOutOfBoundsException(component);
    };
  }

  private static float[] notGamma(float r, float g, float b) {
    var maxComponent = Math.max(Math.max(r, g), b);
    if (maxComponent <= EPSILON) {
      return new float[]{0.0F, 0.0F, 0.0F};
    }

    var maxInverted = 1.0F - maxComponent;
    var maxScaled = 1.0F - maxInverted * maxInverted * maxInverted * maxInverted;
    var scale = maxScaled / maxComponent;
    return new float[]{r * scale, g * scale, b * scale};
  }

  private static int fallbackColor(RenderContext ctx, int lightCoords) {
    var blockLight = LightCoordsUtil.block(lightCoords) / 15.0F;
    var skyLevel = LightCoordsUtil.sky(lightCoords);
    var skyLight = ctx.level() != null ? Lightmap.getBrightness(ctx.level().dimensionType(), skyLevel) : skyLevel / 15.0F;
    var factor = Math.clamp(Math.max(blockLight, skyLight), 0.18F, 1.0F);
    var channel = Math.clamp(Math.round(factor * 255.0F), 0, 255);
    return 0xFF000000 | (channel << 16) | (channel << 8) | channel;
  }
}
