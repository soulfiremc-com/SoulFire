package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionContainsNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.contains")
    .displayName("Collection Contains")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to search in"),
      PortDefinition.genericInput("item", "Item", TypeDescriptor.typeVar("T"), "The item to search for")
    )
    .addOutputs(PortDefinition.output("result", "Result", PortType.BOOLEAN, "Whether the collection contains the item"))
    .description("Checks if a list or set contains a specific item")
    .icon("search")
    .color("#00BCD4")
    .addKeywords("collection", "contains", "includes", "has")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("result", getCollectionInput(inputs, "collection").asList().contains(inputs.get("item"))));
  }
}
