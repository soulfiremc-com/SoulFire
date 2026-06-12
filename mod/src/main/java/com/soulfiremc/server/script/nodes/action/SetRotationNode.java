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
import reactor.core.publisher.Mono;

import java.util.Map;

/// Action node that sets the bot's rotation (yaw and pitch).
/// Inputs: yaw (degrees, -180 to 180), pitch (degrees, -90 to 90)
public final class SetRotationNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.set_rotation")
    .displayName("Set Rotation")
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("yaw", "Yaw", PortType.NUMBER, "0", "Horizontal rotation (-180 to 180)"),
      PortDefinition.inputWithDefault("pitch", "Pitch", PortType.NUMBER, "0", "Vertical rotation (-90 to 90)")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Sets the bot's rotation (yaw and pitch in degrees)")
    .icon("compass")
    .color("#FF9800")
    .addKeywords("rotation", "yaw", "pitch", "turn", "face", "angle")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var yaw = getFloatInput(inputs, "yaw", 0.0f);
    var pitch = getFloatInput(inputs, "pitch", 0.0f);

    // Clamp pitch to valid range
    pitch = Math.max(-90f, Math.min(90f, pitch));

    // Normalize yaw to -180 to 180 range
    while (yaw > 180f) {
      yaw -= 360f;
    }
    while (yaw < -180f) {
      yaw += 360f;
    }

    var finalYaw = yaw;
    var finalPitch = pitch;

    runOnTickThread(runtime, bot, () -> bot.rotationControl().lookTo(finalYaw, finalPitch));

    return completedEmptyMono();
  }
}
