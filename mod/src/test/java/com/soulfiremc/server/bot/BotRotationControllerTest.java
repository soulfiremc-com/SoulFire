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
package com.soulfiremc.server.bot;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotRotationControllerTest {
  @Test
  void lookAtRotationMatchesVanillaForwardDirections() {
    var eye = Vec3.ZERO;

    assertRotation(0.0F, 0.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(0.0, 0.0, 1.0)));
    assertRotation(-90.0F, 0.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(1.0, 0.0, 0.0)));
    assertRotation(-180.0F, 0.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(0.0, 0.0, -1.0)));
    assertRotation(90.0F, 0.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(-1.0, 0.0, 0.0)));
  }

  @Test
  void lookAtRotationUsesPositivePitchForLookingDown() {
    var eye = Vec3.ZERO;

    assertEquals(-90.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(0.0, 1.0, 0.0)).pitch(), 1.0E-4F);
    assertEquals(90.0F, BotRotationController.calculateLookAtRotation(eye, new Vec3(0.0, -1.0, 0.0)).pitch(), 1.0E-4F);
  }

  @Test
  void mouseDeltaScaleMatchesVanillaSensitivityFormula() {
    assertEquals(0.15D * 8.0D * Math.pow(0.5D * 0.6000000238418579D + 0.20000000298023224D, 3.0D),
      BotRotationController.degreesPerMouseDelta(0.5D));
  }

  private static void assertRotation(float yaw, float pitch, BotRotationController.RotationAngles rotation) {
    assertEquals(yaw, rotation.yaw(), 1.0E-4F);
    assertEquals(pitch, rotation.pitch(), 1.0E-4F);
  }
}
