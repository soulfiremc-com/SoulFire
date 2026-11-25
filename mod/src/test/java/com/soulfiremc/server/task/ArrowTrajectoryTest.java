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
package com.soulfiremc.server.task;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArrowTrajectoryTest {
  private static final double FULL_BOW_SPEED = 3;
  private static final Vec3 ORIGIN = new Vec3(0.5, 62.52, 0.5);
  // As in a real End: a crystal on a tower whose top is 34 blocks above the
  // bot, with a tower radius of three, twenty blocks away.
  private static final AABB TOWER = new AABB(17.5, 0, -2.5, 23.5, 97, 3.5);
  private static final AABB CRYSTAL = new AABB(19.5, 97, -0.5, 21.5, 99, 1.5);

  @Test
  void shootsFlatAtATargetInTheOpen() {
    var direction = ArrowTrajectory.launchDirection(
      ORIGIN,
      new AABB(19.7, 62, 0.2, 20.3, 63.8, 0.8),
      FULL_BOW_SPEED,
      (_, _) -> true
    ).orElseThrow();

    assertTrue(elevation(direction) < 10, "elevation " + elevation(direction));
  }

  @Test
  void dropsAnArrowOntoACrystalOverItsTowersRim() {
    var direction = ArrowTrajectory.launchDirection(
      ORIGIN,
      CRYSTAL,
      FULL_BOW_SPEED,
      ArrowTrajectoryTest::missesTower
    ).orElseThrow();

    assertTrue(elevation(direction) > 45, "elevation " + elevation(direction));
    assertTrue(lands(direction), "the chosen flight hit the tower or missed the crystal");
  }

  @Test
  void findsNoShotWhenARoofCoversTheTarget() {
    var roof = new AABB(15, 100, -6, 26, 101, 6);
    var direction = ArrowTrajectory.launchDirection(
      ORIGIN,
      CRYSTAL,
      FULL_BOW_SPEED,
      (from, to) -> missesTower(from, to) && roof.clip(from, to).isEmpty() && !roof.contains(from)
    );

    assertTrue(direction.isEmpty());
  }

  private static boolean missesTower(Vec3 from, Vec3 to) {
    return TOWER.clip(from, to).isEmpty() && !TOWER.contains(from);
  }

  private static double elevation(Vec3 direction) {
    return Math.toDegrees(Math.asin(direction.y));
  }

  // Flies the arrow as the game does: move, then 0.99 drag, then gravity.
  private static boolean lands(Vec3 direction) {
    var position = ORIGIN;
    var velocity = direction.scale(FULL_BOW_SPEED);
    for (var tick = 0; tick < 200; tick++) {
      var next = position.add(velocity);
      if (CRYSTAL.clip(position, next).isPresent()) {
        return true;
      }
      if (!missesTower(position, next)) {
        return false;
      }
      position = next;
      velocity = velocity.scale(0.99).subtract(0, 0.05, 0);
    }
    return false;
  }
}
