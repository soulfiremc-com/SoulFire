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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EntityOutlineRendererTest {
  @Test
  void outlineDetectsSilhouetteAndPreservesSceneAlphaAndDepth() {
    var width = 15;
    var mask = new RasterBuffers(width, width);
    mask.clearColor(0);
    for (var y = 4; y <= 10; y++) {
      for (var x = 4; x <= 10; x++) {
        mask.colorBuffer()[y * width + x] = 0xFFFF0000;
      }
    }
    var edge = EntityOutlineRenderer.edges(mask.colorBuffer(), width, width);
    assertEquals(0, edge[7 * width + 7] >>> 24);
    assertEquals(255, edge[4 * width + 7] >>> 24);
    assertEquals(255, edge[3 * width + 7] >>> 24);
    assertEquals(0, edge[2 * width + 7] >>> 24);

    var target = new RasterBuffers(width, width);
    target.clearColor(0x7F102030);
    target.depthBuffer()[3 * width + 7] = 0.25;
    EntityOutlineRenderer.composite(mask, target);
    assertEquals(0x7F102030, target.colorBuffer()[0]);
    assertNotEquals(0x7F102030, target.colorBuffer()[3 * width + 7]);
    assertEquals(0x7F, target.colorBuffer()[3 * width + 7] >>> 24);
    assertEquals(0.25, target.depthBuffer()[3 * width + 7]);
  }
}
