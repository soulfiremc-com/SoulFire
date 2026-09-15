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

import org.joml.Matrix4f;
import org.joml.Vector3f;

/// Light directions used by vanilla's GUI rendering passes.
record GuiLighting(Vector3f light0, Vector3f light1) {
  static final GuiLighting FLAT = transformed(new Matrix4f().rotationY((float) (-Math.PI / 8))
    .rotateX((float) (Math.PI * 3 / 4)));
  static final GuiLighting BLOCK = transformed(new Matrix4f().scaling(1, -1, 1)
    .rotateYXZ(1.0821041F, 3.2375858F, 0)
    .rotateYXZ((float) (-Math.PI / 8), (float) (Math.PI * 3 / 4), 0));
  static final GuiLighting ENTITY = new GuiLighting(new Vector3f(0.2F, -1, 1).normalize(),
    new Vector3f(-0.2F, -1, 0).normalize());

  private static GuiLighting transformed(Matrix4f transform) {
    return new GuiLighting(transform.transformDirection(new Vector3f(0.2F, 1, -0.7F).normalize()),
      transform.transformDirection(new Vector3f(-0.2F, 1, 0.7F).normalize()));
  }
}
