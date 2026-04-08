package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionRetainAllNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.retain_all")
    .displayName("Collection Retain All")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to modify"),
      PortDefinition.genericInput("other", "Other", TypeDescriptor.typeVar("D"), "Items to retain")
    )
    .addOutputs(PortDefinition.genericOutput("collection", "Collection", TypeDescriptor.typeVar("C"), "The updated collection"))
    .description("Returns a new collection that keeps only items present in the other collection")
    .icon("filter")
    .color("#00BCD4")
    .addKeywords("collection", "retain all", "intersect", "filter")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var collection = getCollectionInput(inputs, "collection");
    var other = getCollectionInput(inputs, "other").asSet();
    var retained = CollectionNodeSupport.orderedItems(collection).stream()
      .filter(other::contains)
      .toList();
    return completedMono(result("collection", CollectionNodeSupport.sameKind(collection, retained)));
  }
}
