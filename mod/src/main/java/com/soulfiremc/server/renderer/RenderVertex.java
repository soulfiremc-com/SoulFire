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

/// A single textured world-space vertex used by the software rasterizer.
public record RenderVertex(float x, float y, float z, float u, float v, int color, int overlayColor) {
  public static final int NO_OVERLAY_COLOR = 0xFFFFFFFF;

  public RenderVertex(float x, float y, float z, float u, float v, int color) {
    this(x, y, z, u, v, color, NO_OVERLAY_COLOR);
  }
}
