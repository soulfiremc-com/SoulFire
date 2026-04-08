package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionIsEmptyNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.is_empty")
    .displayName("Collection Is Empty")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to inspect"))
    .addOutputs(PortDefinition.output("result", "Result", PortType.BOOLEAN, "Whether the collection is empty"))
    .description("Checks if a list or set is empty")
    .icon("circle-off")
    .color("#00BCD4")
    .addKeywords("collection", "empty", "size")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("result", getCollectionInput(inputs, "collection").asList().isEmpty()));
  }
}
