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

import java.util.ArrayList;
import java.util.Arrays;

/// A full frame's worth of raster primitives split by render pass.
public record SceneData(RenderQuad[] opaque, RenderQuad[] cutout, RenderQuad[] translucent) {
  public static final SceneData EMPTY = new SceneData(new RenderQuad[0], new RenderQuad[0], new RenderQuad[0]);

  public static Builder builder() {
    return new Builder();
  }

  public SceneData merge(SceneData other) {
    if (this == EMPTY) {
      return other;
    }
    if (other == EMPTY) {
      return this;
    }

    return new SceneData(
      concat(opaque, other.opaque),
      concat(cutout, other.cutout),
      concat(translucent, other.translucent)
    );
  }

  public int totalQuadCount() {
    return opaque.length + cutout.length + translucent.length;
  }

  private static RenderQuad[] concat(RenderQuad[] left, RenderQuad[] right) {
    var merged = Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, merged, left.length, right.length);
    return merged;
  }

  public static final class Builder {
    private final ArrayList<RenderQuad> opaque = new ArrayList<>();
    private final ArrayList<RenderQuad> cutout = new ArrayList<>();
    private final ArrayList<RenderQuad> translucent = new ArrayList<>();

    public void add(RenderQuad quad) {
      switch (quad.alphaMode()) {
        case OPAQUE -> opaque.add(quad);
        case CUTOUT -> cutout.add(quad);
        case TRANSLUCENT -> translucent.add(quad);
      }
    }

    public void addAll(SceneData sceneData) {
      opaque.addAll(Arrays.asList(sceneData.opaque()));
      cutout.addAll(Arrays.asList(sceneData.cutout()));
      translucent.addAll(Arrays.asList(sceneData.translucent()));
    }

    public SceneData build() {
      if (opaque.isEmpty() && cutout.isEmpty() && translucent.isEmpty()) {
        return EMPTY;
      }

      return new SceneData(
        opaque.toArray(RenderQuad[]::new),
        cutout.toArray(RenderQuad[]::new),
        translucent.toArray(RenderQuad[]::new)
      );
    }
  }
}
