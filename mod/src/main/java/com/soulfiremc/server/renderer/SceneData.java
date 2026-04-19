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

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/// Pre-collected scene data for software rendering.
public record SceneData(RendererAssets.GeometryFace[] surfaces, BillboardData[] billboards, ShadowData[] shadows) {
  public static final SceneData EMPTY = new SceneData(new RendererAssets.GeometryFace[0], new BillboardData[0], new ShadowData[0]);

  public enum BillboardMode {
    FULL,
    VERTICAL
  }

  public record BillboardData(
    double centerX,
    double centerY,
    double centerZ,
    double width,
    double height,
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int tintColor,
    int emission,
    BillboardMode mode,
    int priority
  ) {}

  public record ShadowData(
    AABB bounds,
    double centerX,
    double centerY,
    double centerZ,
    double width,
    double height,
    float strength,
    Direction upDirection,
    int priority
  ) {}
}
