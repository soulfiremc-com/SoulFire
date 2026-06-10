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

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.experimental.UtilityClass;
import org.joml.Vector3d;
import org.joml.Vector3f;

/// Builds camera-facing quads whose front face and UV orientation are explicit.
@UtilityClass
public class BillboardGeometry {
  public static RenderQuad cameraFacingQuad(
    Camera camera,
    double centerX,
    double centerY,
    double centerZ,
    float width,
    float height,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    float depthBias
  ) {
    var center = new Vector3d(centerX, centerY, centerZ);
    var screenLeft = new Vector3d(camera.screenLeftX(), camera.screenLeftY(), camera.screenLeftZ());
    if (screenLeft.lengthSquared() < 1.0E-6) {
      screenLeft.set(1.0, 0.0, 0.0);
    }
    screenLeft.normalize();

    var up = new Vector3d(camera.upX(), camera.upY(), camera.upZ());
    if (up.lengthSquared() < 1.0E-6) {
      up.set(0.0, 1.0, 0.0);
    }
    up.normalize();

    var halfW = width * 0.5F;
    var halfH = height * 0.5F;
    var vertices = new Vector3f[]{
      new Vector3f((float) (center.x + screenLeft.x * halfW - up.x * halfH), (float) (center.y + screenLeft.y * halfW - up.y * halfH), (float) (center.z + screenLeft.z * halfW - up.z * halfH)),
      new Vector3f((float) (center.x + screenLeft.x * halfW + up.x * halfH), (float) (center.y + screenLeft.y * halfW + up.y * halfH), (float) (center.z + screenLeft.z * halfW + up.z * halfH)),
      new Vector3f((float) (center.x - screenLeft.x * halfW + up.x * halfH), (float) (center.y - screenLeft.y * halfW + up.y * halfH), (float) (center.z - screenLeft.z * halfW + up.z * halfH)),
      new Vector3f((float) (center.x - screenLeft.x * halfW - up.x * halfH), (float) (center.y - screenLeft.y * halfW - up.y * halfH), (float) (center.z - screenLeft.z * halfW - up.z * halfH))
    };
    var face = RendererAssets.GeometryFace.of(
      vertices,
      new float[]{0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
      texture,
      alphaMode,
      null,
      -1,
      0,
      false
    );
    return WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, color, true, depthBias);
  }

  public static RenderQuad textQuad(PoseStack poseStack, float width, float height, float x, float y, RendererAssets.TextureImage texture) {
    var vertices = new Vector3f[]{
      poseStack.last().pose().transformPosition(new Vector3f(x, y - height, 0.0F)),
      poseStack.last().pose().transformPosition(new Vector3f(x + width, y - height, 0.0F)),
      poseStack.last().pose().transformPosition(new Vector3f(x + width, y, 0.0F)),
      poseStack.last().pose().transformPosition(new Vector3f(x, y, 0.0F))
    };
    var face = RendererAssets.GeometryFace.of(
      vertices,
      new float[]{0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F},
      texture,
      RendererAssets.AlphaMode.TRANSLUCENT,
      null,
      -1,
      0,
      true
    );
    return WorldMeshCollector.toRenderQuad(face, 0.0, 0.0, 0.0, 0xFFFFFFFF, true, 0.0F);
  }
}
