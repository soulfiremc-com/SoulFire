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
package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class SetCreateNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("set.create")
    .displayName("Set Create")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.multiInput("items", "Items", PortType.ANY, "Items to include in the set"))
    .addOutputs(PortDefinition.setOutput("set", "Set", PortType.ANY, "The created set"))
    .description("Creates a native set from inputs while preserving first-seen order")
    .icon("git-branch-plus")
    .color("#00BCD4")
    .addKeywords("set", "create", "build", "distinct", "collection")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("set", NodeValue.ofSet(getListInput(inputs, "items"))));
  }
}
