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
import com.soulfiremc.server.pathfinding.RouteFinder.GoalCompletion;
import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.BlockBreakAction;
import com.soulfiremc.server.pathfinding.execution.WorldAction;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalCompletionTest {
  @Test
  void checksTheLiveGoalInsteadOfTheSearchSnapshot() {
    var position = new AtomicReference<>(new SFVec3i(4, 64, 0));
    DynamicGoalScorer goal = () -> new PosGoal(position.get());
    var frozen = goal.snapshot();
    var completion = new GoalCompletion(new NodeState(position.get(), 0), List.of());
    assertTrue(completion.isSatisfied(goal));
    position.set(new SFVec3i(9, 64, 0));
    assertTrue(completion.isSatisfied(frozen));
    assertFalse(completion.isSatisfied(goal));
    assertTrue(new GoalCompletion(new NodeState(position.get(), 0), List.of()).isSatisfied(goal));
  }

  @Test
  void retainsTerminalTransitionActionsForCompositeGoals() {
    var position = new SFVec3i(4, 64, 0);
    var action = mock(BlockBreakAction.class);
    when(action.blockPosition()).thenReturn(position);
    var actions = new ArrayList<WorldAction>();
    actions.add(action);
    var completion = new GoalCompletion(new NodeState(position, 0), actions);
    actions.clear();
    DynamicGoalScorer moved = () -> new PosGoal(new SFVec3i(9, 64, 0));
    var goal = new CompositeGoal(Set.of(moved, new BreakBlockPosGoal(position)));
    assertTrue(completion.isSatisfied(goal));
    assertFalse(new GoalCompletion(new NodeState(position, 0), List.of()).isSatisfied(goal));
  }

  @Test
  void aCompletedAlternativeWinsOverAnUnavailableEntity() {
    var position = new SFVec3i(4, 64, 0);
    var action = mock(BlockBreakAction.class);
    when(action.blockPosition()).thenReturn(position);
    var completion = new GoalCompletion(new NodeState(position, 0), List.of(action));
    DynamicGoalScorer unavailable = () -> {
      throw Status.NOT_FOUND.asRuntimeException();
    };
    var finished = new BreakBlockPosGoal(position);
    var goals = new CompositeGoal(new LinkedHashSet<>(List.of(unavailable, finished)));
    assertTrue(completion.isSatisfied(goals));
    assertEquals(finished, goals.getFinishedGoal(completion.state(), completion.actions()));
    assertFalse(new GoalCompletion(completion.state(), List.of()).isSatisfied(goals));
    assertTrue(completion.isSatisfied(goals.snapshot()));
    var missing = new CompositeGoal(Set.of(unavailable));
    assertThrows(StatusRuntimeException.class, missing::snapshot);
    assertThrows(StatusRuntimeException.class, () -> completion.isSatisfied(missing));
  }
}
