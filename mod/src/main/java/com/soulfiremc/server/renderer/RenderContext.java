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

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public record RenderContext(
  ClientLevel level,
  LocalPlayer localPlayer,
  boolean cameraDetached,
  Camera camera,
  EnvironmentAttributeProbe environmentProbe,
  int maxDistance,
  double maxDistanceSq,
  int minY,
  int maxY,
  long animationTick,
  Set<Long> vanillaRenderedBlockEntities,
  LightmapRenderState lightmapRenderState,
  SectionMeshCache sectionMeshCache
) {
  public static RenderContext create(ClientLevel level, LocalPlayer localPlayer, Camera camera, int maxDistance) {
    var environmentProbe = new EnvironmentAttributeProbe();
    environmentProbe.tick(level, new Vec3(camera.eyeX(), camera.eyeY(), camera.eyeZ()));
    var lightmapRenderState = createLightmapRenderState(level, localPlayer, environmentProbe);
    return new RenderContext(
      level,
      localPlayer,
      cameraDetached(localPlayer, camera),
      camera,
      environmentProbe,
      maxDistance,
      (double) maxDistance * maxDistance,
      level.getMinY(),
      level.getMaxY(),
      level.getOverworldClockTime(),
      ConcurrentHashMap.newKeySet(),
      lightmapRenderState,
      SectionMeshCache.forLevel(level)
    );
  }

  private static LightmapRenderState createLightmapRenderState(ClientLevel level, LocalPlayer localPlayer, EnvironmentAttributeProbe environmentProbe) {
    var minecraft = Minecraft.getInstance();
    var renderState = new LightmapRenderState();
    renderState.blockFactor = 1.4F;
    renderState.blockLightTint = ARGB.vector3fFromRGB24(environmentProbe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, 1.0F));
    renderState.skyFactor = environmentProbe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1.0F);

    var endFlashState = level.endFlashState();
    if (endFlashState != null && !minecraft.options.hideLightningFlash().get()) {
      var intensity = endFlashState.getIntensity(1.0F);
      renderState.skyFactor += minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog() ? intensity / 3.0F : intensity;
    }

    renderState.skyLightColor = ARGB.vector3fFromRGB24(environmentProbe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, 1.0F));
    renderState.ambientColor = ARGB.vector3fFromRGB24(environmentProbe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 1.0F));
    var brightnessOption = minecraft.options.gamma().get().floatValue();
    var darknessEffectScaleOption = minecraft.options.darknessEffectScale().get().floatValue();
    var player = localPlayer != null ? localPlayer : minecraft.player;
    var darknessEffectBrightnessModifier = player != null
      ? player.getEffectBlendFactor(MobEffects.DARKNESS, 1.0F) * darknessEffectScaleOption
      : 0.0F;
    renderState.brightness = Math.max(0.0F, brightnessOption - darknessEffectBrightnessModifier);
    renderState.darknessEffectScale = player != null
      ? darknessScale(player.tickCount, darknessEffectBrightnessModifier) * darknessEffectScaleOption
      : 0.0F;
    if (player != null && player.hasEffect(MobEffects.NIGHT_VISION)) {
      renderState.nightVisionEffectIntensity = GameRenderer.nightVisionScale(player, 1.0F);
    } else if (player != null && player.getWaterVision() > 0.0F && player.hasEffect(MobEffects.CONDUIT_POWER)) {
      renderState.nightVisionEffectIntensity = player.getWaterVision();
    }
    renderState.nightVisionColor = ARGB.vector3fFromRGB24(environmentProbe.getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, 1.0F));
    renderState.bossOverlayWorldDarkening = minecraft.gui.hud.getBossOverlay().shouldDarkenScreen() ? 1.0F : 0.0F;
    return renderState;
  }

  private static float darknessScale(int tickCount, float darknessGamma) {
    var darkness = 0.45F * darknessGamma;
    return Math.max(0.0F, Mth.cos((tickCount - 1.0F) * (float) Math.PI * 0.025F) * darkness);
  }

  private static boolean cameraDetached(LocalPlayer localPlayer, Camera camera) {
    if (localPlayer == null) {
      return false;
    }

    var playerEye = localPlayer.getEyePosition();
    return playerEye.distanceToSqr(camera.eyeX(), camera.eyeY(), camera.eyeZ()) > 1.0E-6
      || Mth.degreesDifferenceAbs(localPlayer.getYRot(), camera.yRot()) > 1.0E-4F
      || Mth.degreesDifferenceAbs(localPlayer.getXRot(), camera.xRot()) > 1.0E-4F;
  }
}
