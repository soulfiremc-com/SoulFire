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
