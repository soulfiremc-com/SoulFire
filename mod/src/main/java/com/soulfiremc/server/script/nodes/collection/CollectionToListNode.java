package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionToListNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.to_list")
    .displayName("Collection to List")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to convert"))
    .addOutputs(PortDefinition.listOutput("list", "List", PortType.ANY, "The collection as an ordered list"))
    .description("Converts a collection to a list")
    .icon("list")
    .color("#00BCD4")
    .addKeywords("collection", "to list", "convert")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("list", NodeValue.ofList(getCollectionInput(inputs, "collection").asList())));
  }
}
