package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionToSetNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.to_set")
    .displayName("Collection to Set")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to convert"))
    .addOutputs(PortDefinition.setOutput("set", "Set", PortType.ANY, "The collection as an ordered set"))
    .description("Converts a collection to a set")
    .icon("git-branch-plus")
    .color("#00BCD4")
    .addKeywords("collection", "to set", "convert", "distinct")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("set", NodeValue.ofSet(getCollectionInput(inputs, "collection").asList())));
  }
}
