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
package com.soulfiremc.server.script.nodes.variable;

import com.google.gson.JsonNull;
import com.soulfiremc.server.script.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Variable node that stores a value in the instance's persistent metadata (survives restarts).
@Slf4j
public final class SetPersistentInstanceVariableNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("variable.set_persistent_instance")
    .displayName("Set persistent project variable")
    .category(CategoryRegistry.VARIABLE)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.inputWithDefault("namespace", "Namespace", PortType.STRING, "\"script\"", "Variable namespace"),
      PortDefinition.input("key", "Key", PortType.STRING, "Variable key"),
      PortDefinition.input("value", "Value", PortType.ANY, "Value to store")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("success", "Success", PortType.BOOLEAN, "Whether storage succeeded")
    )
    .description("Stores persistent project metadata, which survives Soulfire server restarts.")
    .icon("save")
    .color("#10B981")
    .addKeywords("Set Persistent Instance Variable", "variable", "persistent", "instance", "save", "store", "set", "permanent")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var namespace = getStringInput(inputs, "namespace", "script");
    var key = getStringInput(inputs, "key", "");
    var value = inputs.get("value");

    if (key.isEmpty()) {
      return completedMono(result("success", false));
    }

    try {
      var jsonValue = value != null ? value.asJsonElement() : JsonNull.INSTANCE;
      if (jsonValue == null) {
        jsonValue = JsonNull.INSTANCE;
      }
      runtime.instance().persistentMetadata().set(namespace, key, jsonValue);
      return completedMono(result("success", true));
    } catch (Exception e) {
      log.warn("Failed to set persistent instance variable '{}/{}'", namespace, key, e);
      return completedMono(result("success", false));
    }
  }
}
