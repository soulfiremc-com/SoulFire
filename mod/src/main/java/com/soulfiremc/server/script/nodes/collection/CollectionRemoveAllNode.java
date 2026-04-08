package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionRemoveAllNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.remove_all")
    .displayName("Collection Remove All")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to modify"),
      PortDefinition.genericInput("other", "Other", TypeDescriptor.typeVar("D"), "Items to remove")
    )
    .addOutputs(PortDefinition.genericOutput("collection", "Collection", TypeDescriptor.typeVar("C"), "The updated collection"))
    .description("Returns a new collection with all matching items removed")
    .icon("list-minus")
    .color("#00BCD4")
    .addKeywords("collection", "remove all", "subtract")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var collection = getCollectionInput(inputs, "collection");
    var items = CollectionNodeSupport.orderedItems(collection);
    items.removeAll(getCollectionInput(inputs, "other").asList());
    return completedMono(result("collection", CollectionNodeSupport.sameKind(collection, items)));
  }
}
