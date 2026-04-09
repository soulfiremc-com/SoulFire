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

public final class MapValuesNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("map.values")
    .displayName("Map Values")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.input("map", "Map", PortType.MAP, "The map whose values should be returned"))
    .addOutputs(PortDefinition.listOutput("values", "Values", PortType.ANY, "The values of the map"))
    .description("Returns the values of a map/object as a list in insertion order")
    .icon("list")
    .color("#14B8A6")
    .addKeywords("map", "values", "json", "object")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var values = CollectionNodeSupport.requireMap(inputs.getOrDefault("map", NodeValue.of(Map.of()))).asMap().values().stream()
      .map(NodeValue::fromJson)
      .toList();
    return completedMono(result("values", NodeValue.ofList(values)));
  }
}
