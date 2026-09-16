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

import org.jetbrains.annotations.Nullable;

/// A textured vertex with packed tint and a floating-point RGB lighting multiplier.
public record RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade, int lightColor, ColorSource colorSource, int fragmentLightColor, @Nullable ClipPosition clipPosition) {
  public RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade, int lightColor, ColorSource colorSource, int fragmentLightColor) {
    this(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource, fragmentLightColor, null);
  }

  public RenderVertex withClipPosition(float x, float y, float z, float w) {
    return new RenderVertex(this.x, this.y, this.z, u, v, color, overlayColor, shade, lightColor, colorSource, fragmentLightColor, new ClipPosition(x, y, z, w));
  }

  /// Shader-expanded position. World coordinates remain available for fog and sorting.
  public record ClipPosition(float x, float y, float z, float w) {}

  public RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor, float shade, int lightColor, ColorSource colorSource) {
    this(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource, 0xFFFFFFFF);
  }

  public RenderVertex withFragmentLightColor(int fragmentLightColor) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource, fragmentLightColor, clipPosition);
  }

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
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, ColorSource.UNIFORM, fragmentLightColor, clipPosition);
  }

  float colorChannel(int shift) {
    var channel = (color >>> shift) & 255;
    // Vanilla builds uniform colors with division; UNORM vertex fetch uses a reciprocal.
    return colorSource == ColorSource.UNIFORM ? channel / 255.0F : channel * (1.0F / 255.0F);
  }

  public RenderVertex withShade(float shade) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource, fragmentLightColor, clipPosition);
  }

  public RenderVertex withLightColor(int lightColor) {
    return new RenderVertex(x, y, z, u, v, color, overlayColor, shade, lightColor, colorSource, fragmentLightColor, clipPosition);
  }

  public RenderVertex(float x, float y, float z, float u, float v, int color) {
    this(x, y, z, u, v, color, NO_OVERLAY_COLOR);
  }
  public enum ColorSource {
    VERTEX_ATTRIBUTE,
    UNIFORM
  }
}
