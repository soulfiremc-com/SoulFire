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

import lombok.experimental.UtilityClass;

/// Constants used throughout the rendering system.
@UtilityClass
public class RenderConstants {
  /// Default render width in pixels.
  public static final int DEFAULT_WIDTH = 854;

  /// Default render height in pixels.
  public static final int DEFAULT_HEIGHT = 480;

  /// Default field of view in degrees.
  public static final double DEFAULT_FOV = 70.0;

  /// Sky color (light blue).
  public static final int SKY_COLOR = 0xFF87CEEB;

  /// Void color (dark blue).
  public static final int VOID_COLOR = 0xFF1A1A2E;

  /// Entity hitbox color (red).
  public static final int ENTITY_HITBOX_COLOR = 0xFFFF0000;
}
