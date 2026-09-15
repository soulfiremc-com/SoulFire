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

/// A textured vertex with packed tint and a floating-point RGB lighting multiplier.
public record RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade, int lightColor, ColorSource colorSource) {
  public static final int NO_OVERLAY_COLOR = 0xFFFFFFFF;

  public RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor) {
    this(x, y, z, u, v, color, overlayColor, 1.0F);
  }

  public RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade) {
    this(x, y, z, u, v, color, overlayColor, shade, 0xFFFFFFFF);
  }

  public RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade, int lightColor) {
    this(x, y, z, u, v, color, overlayColor, shade, lightColor, ColorSource.VERTEX_ATTRIBUTE);
  }

  public RenderVertex withUniformColor(int color) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, ColorSource.UNIFORM);
  }

  float colorChannel(int shift) {
    var channel = (color >>> shift) & 255;
    // Vanilla builds uniform colors with division; UNORM vertex fetch uses a reciprocal.
    return colorSource == ColorSource.UNIFORM ? channel / 255.0F : channel * (1.0F / 255.0F);
  }

  public RenderVertex withShade(float shade) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource);
  }

  public RenderVertex withLightColor(int lightColor) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource);
  }

  public RenderVertex(float x, float y, float z, float u, float v, int color) {
    this(x, y, z, u, v, color, NO_OVERLAY_COLOR);
  }
  public enum ColorSource {
    VERTEX_ATTRIBUTE,
    UNIFORM
  }
}
