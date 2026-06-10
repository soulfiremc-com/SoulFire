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

import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererTintColorMapTest {
  @BeforeAll
  static void bootstrap() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void loadsVanillaColorMapsBeforeResolvingBlockTints() {
    assertTrue(RendererAssets.instance().ensureVanillaColorMapsLoaded());
    assertNotEquals(0, GrassColor.getDefaultColor());
    assertNotEquals(0, FoliageColor.get(0.5, 0.5));
    assertNotEquals(0, DryFoliageColor.get(0.5, 0.5));
  }

  @Test
  void oakLeafTintStaysGreenAfterTextureAndLightModulation() {
    assertTrue(RendererAssets.instance().ensureVanillaColorMapsLoaded());

    var leafTextureSample = 0xFFB9BCB9;
    var tinted = LightingCalculator.tint(leafTextureSample, FoliageColor.get(0.5, 0.5));
    var lit = LightingCalculator.brighten(tinted, 0.8F);

    assertNotEquals(0xFF000000, lit);
    assertTrue(green(lit) > red(lit));
    assertTrue(green(lit) > blue(lit));
  }

  private int red(int color) {
    return (color >> 16) & 0xFF;
  }

  private int green(int color) {
    return (color >> 8) & 0xFF;
  }

  private int blue(int color) {
    return color & 0xFF;
  }
}
