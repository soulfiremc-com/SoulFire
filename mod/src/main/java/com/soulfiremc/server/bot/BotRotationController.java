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
package com.soulfiremc.server.bot;

import com.soulfiremc.mod.access.IMouseHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/// Centralizes bot view changes and routes them through Minecraft's mouse turn path.
public final class BotRotationController {
  private static final float DEFAULT_MAX_YAW_STEP = 18.0F;
  private static final float DEFAULT_MAX_PITCH_STEP = 18.0F;
  private static final float DEFAULT_TOLERANCE = 2.0F;
  private final BotConnection connection;
  private @Nullable RotationRequest request;
  private long requestRevision;
  private long lastAppliedRequestRevision = -1L;
  private int lastAppliedPlayerTick = Integer.MIN_VALUE;

  public BotRotationController(BotConnection connection) {
    this.connection = connection;
  }

  public void lookAt(Vec3 target) {
    lookAt(target, 0.0F, 0.0F);
  }

  public void lookAt(Vec3 target, float yawOffset, float pitchOffset) {
    request = new RotationRequest(new LookAtTarget(target, yawOffset, pitchOffset), DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP, DEFAULT_TOLERANCE);
    requestRevision++;
  }

  public void lookHorizontallyAt(Vec3 target) {
    request = new RotationRequest(new HorizontalLookAtTarget(target), DEFAULT_MAX_YAW_STEP, DEFAULT_MAX_PITCH_STEP, DEFAULT_TOLERANCE);
    requestRevision++;
  }

  public void lookTo(float yaw, float pitch) {
    request = new RotationRequest(
      new FixedTarget(normalizeYaw(yaw), Mth.clamp(pitch, -90.0F, 90.0F)),
      DEFAULT_MAX_YAW_STEP,
      DEFAULT_MAX_PITCH_STEP,
      DEFAULT_TOLERANCE);
    requestRevision++;
  }

  public void clear() {
    request = null;
    requestRevision++;
  }

  public boolean isFacing(Vec3 target) {
    return isFacing(target, DEFAULT_TOLERANCE);
  }

  public boolean isFacing(Vec3 target, float tolerance) {
    var player = connection.minecraft().player;
    if (player == null) {
      return false;
    }

    return isFacing(calculateLookAtRotation(player.getEyePosition(), target), tolerance);
  }

  public boolean isFacing(float yaw, float pitch) {
    return isFacing(new RotationAngles(normalizeYaw(yaw), Mth.clamp(pitch, -90.0F, 90.0F)), DEFAULT_TOLERANCE);
  }

  public boolean isFacing(float yaw, float pitch, float tolerance) {
    return isFacing(new RotationAngles(normalizeYaw(yaw), Mth.clamp(pitch, -90.0F, 90.0F)), tolerance);
  }

  public void queueMouseMovement() {
    var activeRequest = request;
    if (activeRequest == null) {
      return;
    }

    var minecraft = connection.minecraft();
    var player = minecraft.player;
    if (player == null) {
      return;
    }

    if (lastAppliedPlayerTick == player.tickCount && lastAppliedRequestRevision == requestRevision) {
      return;
    }

    var target = activeRequest.target().resolve(player);
    var yawDelta = Mth.wrapDegrees(target.yaw() - player.getYRot());
    var pitchDelta = target.pitch() - player.getXRot();
    if (isWithinTolerance(yawDelta, pitchDelta, activeRequest.tolerance())) {
      return;
    }

    var yawStep = clampDelta(yawDelta, activeRequest.maxYawStep());
    var pitchStep = clampDelta(pitchDelta, activeRequest.maxPitchStep());
    queueMouseMovement(minecraft, yawStep, pitchStep);
    lastAppliedPlayerTick = player.tickCount;
    lastAppliedRequestRevision = requestRevision;
  }

  private boolean isFacing(RotationAngles target, float tolerance) {
    var player = connection.minecraft().player;
    if (player == null) {
      return false;
    }

    var yawDelta = Mth.wrapDegrees(target.yaw() - player.getYRot());
    var pitchDelta = target.pitch() - player.getXRot();
    return isWithinTolerance(yawDelta, pitchDelta, tolerance);
  }

  private void queueMouseMovement(Minecraft minecraft, float yawStep, float pitchStep) {
    if (!(minecraft.mouseHandler instanceof IMouseHandler mouseHandler)) {
      return;
    }

    var degreesPerMouseDelta = degreesPerMouseDelta(minecraft.options);
    if (degreesPerMouseDelta == 0.0D || !Double.isFinite(degreesPerMouseDelta)) {
      return;
    }

    var mouseDeltaX = yawStep / degreesPerMouseDelta;
    var mouseDeltaY = pitchStep / degreesPerMouseDelta;
    if (minecraft.options.invertMouseX().get()) {
      mouseDeltaX = -mouseDeltaX;
    }
    if (minecraft.options.invertMouseY().get()) {
      mouseDeltaY = -mouseDeltaY;
    }

    mouseHandler.soulfire$queueSyntheticMovement(mouseDeltaX, mouseDeltaY);
  }

  static RotationAngles calculateLookAtRotation(Vec3 eyePosition, Vec3 target) {
    var diffX = target.x - eyePosition.x;
    var diffY = target.y - eyePosition.y;
    var diffZ = target.z - eyePosition.z;
    var horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);
    var pitch = Mth.wrapDegrees((float) -(Mth.atan2(diffY, horizontalDistance) * Mth.RAD_TO_DEG));
    var yaw = Mth.wrapDegrees((float) (Mth.atan2(diffZ, diffX) * Mth.RAD_TO_DEG) - 90.0F);
    return new RotationAngles(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
  }

  static double degreesPerMouseDelta(Options options) {
    return degreesPerMouseDelta(options.sensitivity().get());
  }

  static double degreesPerMouseDelta(double sensitivity) {
    var adjustedSensitivity = sensitivity * 0.6000000238418579D + 0.20000000298023224D;
    var scale = adjustedSensitivity * adjustedSensitivity * adjustedSensitivity;
    return scale * 8.0D * 0.15D;
  }

  private static boolean isWithinTolerance(float yawDelta, float pitchDelta, float tolerance) {
    return Math.abs(yawDelta) <= tolerance && Math.abs(pitchDelta) <= tolerance;
  }

  private static float clampDelta(float value, float maxStep) {
    return Mth.clamp(value, -maxStep, maxStep);
  }

  private static float normalizeYaw(float yaw) {
    return Mth.wrapDegrees(yaw);
  }

  record RotationAngles(float yaw, float pitch) {}

  private interface RotationTarget {
    RotationAngles resolve(LocalPlayer player);
  }

  private record FixedTarget(float yaw, float pitch) implements RotationTarget {
    @Override
    public RotationAngles resolve(LocalPlayer player) {
      return new RotationAngles(yaw, pitch);
    }
  }

  private record LookAtTarget(Vec3 target, float yawOffset, float pitchOffset) implements RotationTarget {
    @Override
    public RotationAngles resolve(LocalPlayer player) {
      var rotation = calculateLookAtRotation(player.getEyePosition(), target);
      return new RotationAngles(
        normalizeYaw(rotation.yaw() + yawOffset),
        Mth.clamp(rotation.pitch() + pitchOffset, -90.0F, 90.0F));
    }
  }

  private record HorizontalLookAtTarget(Vec3 target) implements RotationTarget {
    @Override
    public RotationAngles resolve(LocalPlayer player) {
      var rotation = calculateLookAtRotation(player.getEyePosition(), target);
      return new RotationAngles(rotation.yaw(), 0.0F);
    }
  }

  private record RotationRequest(RotationTarget target, float maxYawStep, float maxPitchStep, float tolerance) {}
}
