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
package com.soulfiremc.server.script.nodes.action;

import com.soulfiremc.server.pathfinding.SFVec3i;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.goals.PosGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.script.*;
import net.minecraft.world.phys.Vec3;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/// Action node that pathfinds the bot to a target position.
/// This is an async operation that completes when the bot reaches the destination.
/// Inputs: destination (Vec3 target coordinates)
/// Output: success (boolean)
public final class PathfindToNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.pathfind_to")
    .displayName("Go to position")
    .keywords(List.of("Pathfind To"))
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.input("destination", "Destination", PortType.VECTOR3, "Target position to pathfind to")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output(StandardPorts.EXEC_ERROR, "Error", PortType.EXEC, "Executes on failure"),
      PortDefinition.output("success", "Success", PortType.BOOLEAN, "Whether it succeeded"),
      PortDefinition.output("errorMessage", "Error Message", PortType.STRING, "Error details")
    )
    .description("Moves the bot to a target position using pathfinding")
    .icon("route")
    .color("#FF9800")
    .addKeywords("pathfind", "walk", "move", "goto", "navigate")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var destination = getInput(inputs, "destination", Vec3.ZERO);

    var goal = new PosGoal(SFVec3i.fromDouble(destination));
    var constraint = new PathConstraintImpl(bot);

    return Mono.fromFuture(PathExecutor.executePathfinding(bot, goal, constraint))
      .thenReturn(results(
        StandardPorts.EXEC_OUT, true,
        "success", true,
        "errorMessage", ""
      ))
      .onErrorResume(e -> completedMono(results(
        StandardPorts.EXEC_ERROR, true,
        "success", false,
        "errorMessage", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
      )));
  }
}
