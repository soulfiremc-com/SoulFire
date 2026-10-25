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
package com.soulfiremc.server.pathfinding.goals;

import com.soulfiremc.server.pathfinding.NodeState;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.WorldAction;
import com.soulfiremc.server.pathfinding.graph.MinecraftGraph;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Goal that is a composite of multiple goals.
/// This goal is completed if any of the goals are completed.
/// The closest goal is always chosen as the current goal.
///
/// @param goals the goals to composite
public record CompositeGoal(Set<GoalScorer> goals) implements GoalScorer {
  @Override
  public double computeScore(MinecraftGraph graph, SFVec3i blockPosition, List<WorldAction> actions) {
    return goals.stream().mapToDouble(goal -> goal.computeScore(graph, blockPosition, actions))
      .min().orElseThrow(() -> new IllegalStateException("No goals provided"));
  }

  @Override
  public boolean isFinished(NodeState state, List<WorldAction> actions) {
    return finishedGoal(state, actions).isPresent();
  }

  public GoalScorer getFinishedGoal(
    NodeState state,
    List<WorldAction> actions
  ) {
    return finishedGoal(state, actions)
      .orElseThrow(() -> new IllegalStateException("No goals finished"));
  }

  private Optional<GoalScorer> finishedGoal(NodeState state, List<WorldAction> actions) {
    RuntimeException failure = null;
    var hasAvailableGoal = false;
    for (var goal : goals) {
      try {
        if (goal.isFinished(state, actions)) return Optional.of(goal);
        hasAvailableGoal = true;
      } catch (RuntimeException exception) {
        if (failure == null) failure = exception;
      }
    }
    if (!hasAvailableGoal && failure != null) throw failure;
    return Optional.empty();
  }

  @Override
  public GoalScorer snapshot() {
    var snapshots = new HashSet<GoalScorer>();
    RuntimeException failure = null;
    for (var goal : goals) {
      try {
        snapshots.add(goal.snapshot());
      } catch (RuntimeException exception) {
        if (failure == null) failure = exception;
      }
    }
    if (snapshots.isEmpty() && failure != null) throw failure;
    return new CompositeGoal(Set.copyOf(snapshots));
  }
}
