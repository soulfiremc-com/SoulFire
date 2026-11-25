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

import java.util.Optional;

/// Simulates an arrow's flight to find a launch direction that reaches a
/// target box before any block stops it.
///
/// Arrows arc, so a steep shot can drop onto a target that no straight line
/// reaches, such as an End crystal on top of its tower.
final class ArrowTrajectory {
  /// Arrows keep this share of their speed each tick in air.
  private static final double AIR_DRAG = 0.99;
  /// Arrows lose this much vertical speed each tick.
  private static final double GRAVITY = 0.05;
  /// Arrows spawn this far below the shooter's eyes.
  static final double LAUNCH_DROP = 0.1;
  private static final int MAXIMUM_FLIGHT_TICKS = 200;
  private static final double MINIMUM_ELEVATION_DEGREES = -60;
  private static final double MAXIMUM_ELEVATION_DEGREES = 85;
  private static final double ELEVATION_STEP_DEGREES = 0.5;
  /// Spread moves an arrow a little, so the flight must pass this far inside
  /// the target box.
  private static final double TARGET_MARGIN = 0.25;

  private ArrowTrajectory() {
  }

  /// Tells whether an arrow can fly the straight segment between two points.
  @FunctionalInterface
  interface Clearance {
    boolean clear(Vec3 from, Vec3 to);
  }

  /// Returns the unit launch direction whose arrow reaches the target box
  /// with the most room for aiming error: the middle of the widest range of
  /// elevations that hit, the flattest such range on a tie. Returns empty
  /// when every flight hits a block first or misses.
  ///
  /// @param origin where the arrow spawns, `LAUNCH_DROP` below the eyes
  /// @param speed the arrow's launch speed in blocks per tick
  static Optional<Vec3> launchDirection(
    Vec3 origin,
    AABB target,
    double speed,
    Clearance clearance
  ) {
    var center = target.getCenter();
    var horizontal = Math.hypot(center.x - origin.x, center.z - origin.z);
    if (horizontal < 0.001) {
      return Optional.empty();
    }
    var directionX = (center.x - origin.x) / horizontal;
    var directionZ = (center.z - origin.z) / horizontal;
    var inner = shrink(target);
    var steps = (int) Math.round(
      (MAXIMUM_ELEVATION_DEGREES - MINIMUM_ELEVATION_DEGREES) / ELEVATION_STEP_DEGREES
    );
    var runStart = -1;
    var bestStart = -1;
    var bestLength = 0;
    for (var step = 0; step <= steps + 1; step++) {
      var hits = step <= steps
        && reaches(origin, direction(step, directionX, directionZ), speed, inner, target.minY, clearance);
      if (hits && runStart < 0) {
        runStart = step;
      } else if (!hits && runStart >= 0) {
        if (step - runStart > bestLength) {
          bestStart = runStart;
          bestLength = step - runStart;
        }
        runStart = -1;
      }
    }
    if (bestStart < 0) {
      return Optional.empty();
    }
    return Optional.of(direction(bestStart + (bestLength - 1) / 2.0, directionX, directionZ));
  }

  private static Vec3 direction(double step, double directionX, double directionZ) {
    var elevation = Math.toRadians(MINIMUM_ELEVATION_DEGREES + step * ELEVATION_STEP_DEGREES);
    var flat = Math.cos(elevation);
    return new Vec3(directionX * flat, Math.sin(elevation), directionZ * flat);
  }

  private static boolean reaches(
    Vec3 origin,
    Vec3 direction,
    double speed,
    AABB inner,
    double floor,
    Clearance clearance
  ) {
    var position = origin;
    var velocity = direction.scale(speed);
    for (var tick = 0; tick < MAXIMUM_FLIGHT_TICKS; tick++) {
      var next = position.add(velocity);
      var hit = inner.clip(position, next);
      if (hit.isPresent()) {
        return clearance.clear(position, hit.get());
      }
      if (!clearance.clear(position, next)) {
        return false;
      }
      position = next;
      velocity = velocity.scale(AIR_DRAG).subtract(0, GRAVITY, 0);
      if (velocity.y < 0 && position.y < floor) {
        return false;
      }
    }
    return false;
  }

  private static AABB shrink(AABB target) {
    var x = Math.min(TARGET_MARGIN, target.getXsize() / 2 - 0.01);
    var y = Math.min(TARGET_MARGIN, target.getYsize() / 2 - 0.01);
    var z = Math.min(TARGET_MARGIN, target.getZsize() / 2 - 0.01);
    return target.deflate(Math.max(0, x), Math.max(0, y), Math.max(0, z));
  }
}
