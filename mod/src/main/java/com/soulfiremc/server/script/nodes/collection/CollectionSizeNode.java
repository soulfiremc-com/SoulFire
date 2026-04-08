package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionSizeNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.size")
    .displayName("Collection Size")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to measure"))
    .addOutputs(PortDefinition.output("size", "Size", PortType.NUMBER, "The number of items in the collection"))
    .description("Returns the size of a list or set")
    .icon("hash")
    .color("#00BCD4")
    .addKeywords("collection", "size", "length", "count")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("size", getCollectionInput(inputs, "collection").asList().size()));
  }
}
