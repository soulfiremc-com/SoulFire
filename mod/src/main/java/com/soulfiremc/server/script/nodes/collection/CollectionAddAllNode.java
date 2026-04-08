package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionAddAllNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.add_all")
    .displayName("Collection Add All")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to extend"),
      PortDefinition.genericInput("other", "Other", TypeDescriptor.typeVar("D"), "The collection whose items should be added")
    )
    .addOutputs(PortDefinition.genericOutput("collection", "Collection", TypeDescriptor.typeVar("C"), "The updated collection"))
    .description("Returns a new collection with all items from another collection added")
    .icon("list-plus")
    .color("#00BCD4")
    .addKeywords("collection", "add all", "concat", "merge")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var collection = getCollectionInput(inputs, "collection");
    var items = CollectionNodeSupport.orderedItems(collection);
    items.addAll(getCollectionInput(inputs, "other").asList());
    return completedMono(result("collection", CollectionNodeSupport.sameKind(collection, items)));
  }
}
