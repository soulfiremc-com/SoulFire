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

import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/// Camera state shared between scene building, culling, and rasterization.
public final class Camera {
  private static final float DEFAULT_NEAR_PLANE = net.minecraft.client.Camera.PROJECTION_Z_NEAR;

  private final int width;
  private final int height;
  private final double eyeX;
  private final double eyeY;
  private final double eyeZ;
  private final double forwardX;
  private final double forwardY;
  private final double forwardZ;
  private final double rightX;
  private final double rightY;
  private final double rightZ;
  private final double upX;
  private final double upY;
  private final double upZ;
  private final double tanHalfFovY;
  private final double tanHalfFovX;
  private final double screenXMult;
  private final double screenYMult;
  private final double screenXOffset;
  private final double screenYOffset;
  private final float nearPlane;
  private final float farPlane;
  private final Matrix4f viewRotationMatrix;
  private final Matrix4f viewMatrix;
  private final Matrix4f projectionMatrix;
  private final Matrix4f viewRotationProjectionMatrix;
  private final Matrix4f viewProjectionMatrix;
  private final FrustumIntersection frustumIntersection;

  public Camera(Vec3 eyePos, float yRot, float xRot, int width, int height, double fov, float farPlane) {
    this.width = width;
    this.height = height;
    this.eyeX = eyePos.x;
    this.eyeY = eyePos.y;
    this.eyeZ = eyePos.z;
    this.nearPlane = DEFAULT_NEAR_PLANE;
    this.farPlane = farPlane;

    var yRotRad = Math.toRadians(yRot);
    var xRotRad = Math.toRadians(xRot);
    var cosYRot = Math.cos(yRotRad);
    var sinYRot = Math.sin(yRotRad);
    var cosXRot = Math.cos(xRotRad);
    var sinXRot = Math.sin(xRotRad);

    this.forwardX = -sinYRot * cosXRot;
    this.forwardY = -sinXRot;
    this.forwardZ = cosYRot * cosXRot;

    this.rightX = cosYRot;
    this.rightY = 0.0;
    this.rightZ = sinYRot;

    this.upX = -sinYRot * sinXRot;
    this.upY = cosXRot;
    this.upZ = cosYRot * sinXRot;

    var fovRad = Math.toRadians(fov);
    var aspectRatio = (double) width / height;
    this.tanHalfFovY = Math.tan(fovRad / 2.0);
    this.tanHalfFovX = tanHalfFovY * aspectRatio;
    this.screenXMult = 2.0 * tanHalfFovX / width;
    this.screenYMult = 2.0 * tanHalfFovY / height;
    this.screenXOffset = tanHalfFovX;
    this.screenYOffset = tanHalfFovY;

    this.viewRotationMatrix = new Matrix4f().lookAt(
      0.0F,
      0.0F,
      0.0F,
      (float) forwardX,
      (float) forwardY,
      (float) forwardZ,
      (float) upX,
      (float) upY,
      (float) upZ
    );
    this.viewMatrix = new Matrix4f(viewRotationMatrix).translate((float) -eyeX, (float) -eyeY, (float) -eyeZ);
    this.projectionMatrix = new Matrix4f().setPerspective((float) fovRad, (float) aspectRatio, nearPlane, farPlane);
    this.viewRotationProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewRotationMatrix);
    this.viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);
    this.frustumIntersection = new FrustumIntersection(viewRotationProjectionMatrix);
  }

  public float viewX(double worldX, double worldY, double worldZ) {
    var dx = worldX - eyeX;
    var dy = worldY - eyeY;
    var dz = worldZ - eyeZ;
    return (float) (dx * rightX + dy * rightY + dz * rightZ);
  }

  public float viewY(double worldX, double worldY, double worldZ) {
    var dx = worldX - eyeX;
    var dy = worldY - eyeY;
    var dz = worldZ - eyeZ;
    return (float) (dx * upX + dy * upY + dz * upZ);
  }

  public float viewZ(double worldX, double worldY, double worldZ) {
    var dx = worldX - eyeX;
    var dy = worldY - eyeY;
    var dz = worldZ - eyeZ;
    return (float) (dx * forwardX + dy * forwardY + dz * forwardZ);
  }

  public boolean isVisibleAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    return frustumIntersection.testAab(
      (float) (minX - eyeX),
      (float) (minY - eyeY),
      (float) (minZ - eyeZ),
      (float) (maxX - eyeX),
      (float) (maxY - eyeY),
      (float) (maxZ - eyeZ)
    );
  }

  public double sampleDirX(int x, int y) {
    var screenX = screenXOffset - (x + 0.5) * screenXMult;
    var screenY = screenYOffset - (y + 0.5) * screenYMult;
    var rayX = forwardX + screenX * rightX + screenY * upX;
    var rayY = forwardY + screenY * upY;
    var rayZ = forwardZ + screenX * rightZ + screenY * upZ;
    var invLen = 1.0 / Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
    return rayX * invLen;
  }

  public double sampleDirY(int x, int y) {
    var screenX = screenXOffset - (x + 0.5) * screenXMult;
    var screenY = screenYOffset - (y + 0.5) * screenYMult;
    var rayX = forwardX + screenX * rightX + screenY * upX;
    var rayY = forwardY + screenY * upY;
    var rayZ = forwardZ + screenX * rightZ + screenY * upZ;
    var invLen = 1.0 / Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
    return rayY * invLen;
  }

  public double sampleDirZ(int x, int y) {
    var screenX = screenXOffset - (x + 0.5) * screenXMult;
    var screenY = screenYOffset - (y + 0.5) * screenYMult;
    var rayX = forwardX + screenX * rightX + screenY * upX;
    var rayY = forwardY + screenY * upY;
    var rayZ = forwardZ + screenX * rightZ + screenY * upZ;
    var invLen = 1.0 / Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
    return rayZ * invLen;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public double eyeX() {
    return eyeX;
  }

  public double eyeY() {
    return eyeY;
  }

  public double eyeZ() {
    return eyeZ;
  }

  public double forwardX() {
    return forwardX;
  }

  public double forwardY() {
    return forwardY;
  }

  public double forwardZ() {
    return forwardZ;
  }

  public double rightX() {
    return rightX;
  }

  public double rightY() {
    return rightY;
  }

  public double rightZ() {
    return rightZ;
  }

  public double upX() {
    return upX;
  }

  public double upY() {
    return upY;
  }

  public double upZ() {
    return upZ;
  }

  public double tanHalfFovX() {
    return tanHalfFovX;
  }

  public double tanHalfFovY() {
    return tanHalfFovY;
  }

  public float nearPlane() {
    return nearPlane;
  }

  public float farPlane() {
    return farPlane;
  }

  public Matrix4f viewRotationMatrix() {
    return new Matrix4f(viewRotationMatrix);
  }

  public Matrix4f viewMatrix() {
    return new Matrix4f(viewMatrix);
  }

  public Matrix4f projectionMatrix() {
    return new Matrix4f(projectionMatrix);
  }

  public Matrix4f viewRotationProjectionMatrix() {
    return new Matrix4f(viewRotationProjectionMatrix);
  }

  public Matrix4f viewProjectionMatrix() {
    return new Matrix4f(viewProjectionMatrix);
  }
}
