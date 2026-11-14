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

import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.script.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Variable node that stores a value in the instance's session metadata (lost on restart).
@Slf4j
public final class SetSessionInstanceVariableNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("variable.set_session_instance")
    .displayName("Set project runtime variable")
    .category(CategoryRegistry.VARIABLE)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.input("key", "Key", PortType.STRING, "Variable key"),
      PortDefinition.input("value", "Value", PortType.ANY, "Value to store")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("success", "Success", PortType.BOOLEAN, "Whether storage succeeded")
    )
    .description("Stores temporary data for this project runtime. Values reset when the Soulfire server restarts.")
    .icon("upload")
    .color("#10B981")
    .addKeywords("Set Session Instance Variable", "variable", "session", "instance", "temporary", "store", "set", "memory")
    .build();

  private static final MetadataKey<ConcurrentHashMap<String, NodeValue>> SESSION_VARS_KEY =
    SessionVariableKeys.SESSION_INSTANCE_VARS_KEY;

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var key = getStringInput(inputs, "key", "");
    var value = inputs.get("value");

    if (key.isEmpty()) {
      return completedMono(result("success", false));
    }

    try {
      var sessionVars = runtime.instance().metadata().getOrSet(SESSION_VARS_KEY, ConcurrentHashMap::new);
      sessionVars.put(key, value != null ? value : NodeValue.ofNull());
      return completedMono(result("success", true));
    } catch (Exception e) {
      log.warn("Failed to set session instance variable '{}'", key, e);
      return completedMono(result("success", false));
    }
  }
}
