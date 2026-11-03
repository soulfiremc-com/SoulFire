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

import java.util.List;
import java.util.Map;

/// Action node that logs a message for debugging.
/// Input: message (any), level (string)
public final class PrintNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("action.print")
    .displayName("Log message")
    .keywords(List.of("Print"))
    .category(CategoryRegistry.ACTIONS)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.input("message", "Message", PortType.ANY, "The message to log"),
      PortDefinition.inputWithDefault("level", "Level", PortType.STRING, "\"info\"", "Log level (debug, info, warn, error)")
    )
    .addOutputs(
      PortDefinition.execOut()
    )
    .description("Logs a message for debugging purposes")
    .icon("terminal")
    .color("#FF9800")
    .addKeywords("print", "log", "debug", "console", "output", "message")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    // Get the raw NodeValue since this accepts "any" type
    var messageValue = inputs.get("message");
    var level = getStringInput(inputs, "level", "info");

    // Convert message to string representation
    String messageStr;
    if (messageValue == null || messageValue.isNull()) {
      messageStr = "null";
    } else {
      // Use asString for proper conversion, falling back to toString
      messageStr = messageValue.asString(messageValue.toString());
    }

    // Log via runtime
    runtime.log(level, messageStr);

    return completedEmptyMono();
  }
}
