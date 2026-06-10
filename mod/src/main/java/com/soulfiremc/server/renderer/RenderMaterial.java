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

/// Raster state shared by all triangles emitted from one source primitive.
public record RenderMaterial(
  RendererAssets.TextureImage texture,
  RendererAssets.AlphaMode alphaMode,
  int color,
  boolean doubleSided,
  float depthBias,
  int alphaCutoutThreshold
) {
  public static RenderMaterial create(
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    boolean doubleSided,
    float depthBias
  ) {
    return new RenderMaterial(texture, alphaMode, color, doubleSided, depthBias, defaultAlphaCutoutThreshold(alphaMode));
  }

  public static int defaultAlphaCutoutThreshold(RendererAssets.AlphaMode alphaMode) {
    return switch (alphaMode) {
      case OPAQUE -> 0;
      case CUTOUT -> 128;
      case TRANSLUCENT -> 3;
    };
  }
}
