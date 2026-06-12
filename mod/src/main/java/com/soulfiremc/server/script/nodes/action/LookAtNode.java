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

import com.soulfiremc.server.script.*;
import net.minecraft.world.phys.Vec3;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Action node that makes the bot look at a specific position.
/// Inputs: target (Vec3 coordinates to look at)
public final class LookAtNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.look_at")
    .displayName("Look At")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.input("target", "Target", PortType.VECTOR3, "Position to look at")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Makes the bot look at a specific position in the world")
    .icon("eye")
    .color("#FF9800")
    .addKeywords("look", "face", "turn", "aim", "view")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var target = getInput(inputs, "target", Vec3.ZERO);

    runOnTickThread(runtime, bot, () -> bot.rotationControl().lookAt(target));

    return completedEmptyMono();
  }
}
