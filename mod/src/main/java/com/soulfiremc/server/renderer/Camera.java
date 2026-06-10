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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/// Camera state shared between scene building, culling, and rasterization.
public final class Camera {
  private static final float DEFAULT_NEAR_PLANE = net.minecraft.client.Camera.PROJECTION_Z_NEAR;

  private final int width;
  private final int height;
  private final double eyeX;
  private final double eyeY;
  private final double eyeZ;
  private final float yRot;
  private final float xRot;
  private final double forwardX;
  private final double forwardY;
  private final double forwardZ;
  private final double screenLeftX;
  private final double screenLeftY;
  private final double screenLeftZ;
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
  private final Quaternionf orientation;
  private final FrustumIntersection frustumIntersection;

  public Camera(Vec3 eyePos, float yRot, float xRot, int width, int height, double fov, float farPlane) {
    this.width = width;
    this.height = height;
    this.eyeX = eyePos.x;
    this.eyeY = eyePos.y;
    this.eyeZ = eyePos.z;
    this.yRot = yRot;
    this.xRot = xRot;
    this.nearPlane = DEFAULT_NEAR_PLANE;
    this.farPlane = farPlane;

    var yRotRad = Math.toRadians(yRot);
    var xRotRad = Math.toRadians(xRot);
    this.orientation = new Quaternionf().rotationYXZ((float) Math.PI - (float) yRotRad, -(float) xRotRad, 0.0F);
    var forward = new Vector3f(0.0F, 0.0F, -1.0F).rotate(orientation);
    var screenLeft = new Vector3f(-1.0F, 0.0F, 0.0F).rotate(orientation);
    var up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(orientation);

    this.forwardX = forward.x();
    this.forwardY = forward.y();
    this.forwardZ = forward.z();

    this.screenLeftX = screenLeft.x();
    this.screenLeftY = screenLeft.y();
    this.screenLeftZ = screenLeft.z();

    this.upX = up.x();
    this.upY = up.y();
    this.upZ = up.z();

    var fovRad = Math.toRadians(fov);
    var aspectRatio = (double) width / height;
    this.tanHalfFovY = Math.tan(fovRad / 2.0);
    this.tanHalfFovX = tanHalfFovY * aspectRatio;
    this.screenXMult = 2.0 * tanHalfFovX / width;
    this.screenYMult = 2.0 * tanHalfFovY / height;
    this.screenXOffset = tanHalfFovX;
    this.screenYOffset = tanHalfFovY;

    this.viewRotationMatrix = new Matrix4f().rotation(new Quaternionf(orientation).conjugate());
    this.viewMatrix = new Matrix4f(viewRotationMatrix).translate((float) -eyeX, (float) -eyeY, (float) -eyeZ);
    this.projectionMatrix = new Matrix4f().setPerspective((float) fovRad, (float) aspectRatio, nearPlane, farPlane);
    this.viewRotationProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewRotationMatrix);
    this.viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);
    this.frustumIntersection = new FrustumIntersection(viewRotationProjectionMatrix);
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
    var rayX = forwardX + screenX * screenLeftX + screenY * upX;
    var rayY = forwardY + screenX * screenLeftY + screenY * upY;
    var rayZ = forwardZ + screenX * screenLeftZ + screenY * upZ;
    var invLen = 1.0 / Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
    return rayX * invLen;
  }

  public double sampleDirY(int x, int y) {
    var screenX = screenXOffset - (x + 0.5) * screenXMult;
    var screenY = screenYOffset - (y + 0.5) * screenYMult;
    var rayX = forwardX + screenX * screenLeftX + screenY * upX;
    var rayY = forwardY + screenX * screenLeftY + screenY * upY;
    var rayZ = forwardZ + screenX * screenLeftZ + screenY * upZ;
    var invLen = 1.0 / Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
    return rayY * invLen;
  }

  public double sampleDirZ(int x, int y) {
    var screenX = screenXOffset - (x + 0.5) * screenXMult;
    var screenY = screenYOffset - (y + 0.5) * screenYMult;
    var rayX = forwardX + screenX * screenLeftX + screenY * upX;
    var rayY = forwardY + screenX * screenLeftY + screenY * upY;
    var rayZ = forwardZ + screenX * screenLeftZ + screenY * upZ;
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

  public float yRot() {
    return yRot;
  }

  public float xRot() {
    return xRot;
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

  public double screenLeftX() {
    return screenLeftX;
  }

  public double screenLeftY() {
    return screenLeftY;
  }

  public double screenLeftZ() {
    return screenLeftZ;
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

  public Quaternionf orientation() {
    return new Quaternionf(orientation);
  }
}
