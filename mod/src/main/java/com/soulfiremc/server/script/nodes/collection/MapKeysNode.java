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

public final class MapKeysNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("map.keys")
    .displayName("Map Keys")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.input("map", "Map", PortType.MAP, "The map whose keys should be returned"))
    .addOutputs(PortDefinition.listOutput("keys", "Keys", PortType.STRING, "The keys of the map"))
    .description("Returns the keys of a map/object as a list")
    .icon("key-round")
    .color("#14B8A6")
    .addKeywords("map", "keys", "keyset", "json", "object")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var keys = CollectionNodeSupport.requireMap(inputs.getOrDefault("map", NodeValue.of(Map.of()))).keySet().stream().toList();
    return completedMono(result("keys", keys));
  }
}
