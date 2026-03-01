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
package com.soulfiremc.test.utils;

import com.soulfiremc.server.pathfinding.graph.constraint.DelegatePathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import org.jspecify.annotations.NonNull;

public final class TestPathConstraint implements DelegatePathConstraint {
  public static final TestPathConstraint INSTANCE = new TestPathConstraint();

  private final PathConstraint pathConstraint = new PathConstraintImpl(
    null,
    TestLevelHeightAccessor.INSTANCE,
    false,  // allowBreakingUndiggable
    false,  // avoidDiagonalSqueeze
    true,   // avoidHarmfulEntities
    50,     // maxEnemyPenalty
    2,      // breakBlockPenalty
    5,      // placeBlockPenalty
    180,    // expireTimeout
    false,   // disablePruning
    -25,  // yawJitterMin
    25,   // yawJitterMax
    -4,   // pitchJitterMin
    4   // pitchJitterMax
  );

  private TestPathConstraint() {}

  @Override
  public @NonNull PathConstraint delegate() {
    return pathConstraint;
  }
}
