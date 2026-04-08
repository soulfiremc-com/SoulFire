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
