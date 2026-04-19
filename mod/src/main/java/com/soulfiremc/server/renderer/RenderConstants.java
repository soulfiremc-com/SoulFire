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

  /// Near clip used for non-world scene primitives such as entities and billboards.
  public static final double SCENE_NEAR_CLIP = 0.28;

  /// Enable close-entity suppression to keep POV renders readable in crowded hubs.
  public static final boolean POV_READABILITY_MODE =
    Boolean.parseBoolean(System.getProperty("sf.renderer.readability-mode", "true"));

  /// Entities intersecting this camera radius are candidates for suppression.
  public static final double POV_ENTITY_HIDE_RADIUS = 0.85;

  /// Entities inside this radius may be faded or downweighted instead of rendered fully.
  public static final double POV_ENTITY_FADE_RADIUS = 1.45;

  /// Minimum alignment with the camera forward vector before very close entities are kept.
  public static final double POV_ENTITY_KEEP_ALIGNMENT = 0.78;

  /// Sky color (light blue).
  public static final int SKY_COLOR = 0xFF87CEEB;

  /// Void color (dark blue).
  public static final int VOID_COLOR = 0xFF1A1A2E;

  /// Entity hitbox color (red).
  public static final int ENTITY_HITBOX_COLOR = 0xFFFF0000;
}
