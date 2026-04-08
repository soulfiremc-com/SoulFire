package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionAddNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.add")
    .displayName("Collection Add")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to extend"),
      PortDefinition.genericInput("item", "Item", TypeDescriptor.typeVar("T"), "The item to add")
    )
    .addOutputs(PortDefinition.genericOutput("collection", "Collection", TypeDescriptor.typeVar("C"), "The updated collection"))
    .description("Returns a new collection with the item added")
    .icon("plus")
    .color("#00BCD4")
    .addKeywords("collection", "add", "append", "insert")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var collection = getCollectionInput(inputs, "collection");
    var items = CollectionNodeSupport.orderedItems(collection);
    items.add(inputs.getOrDefault("item", NodeValue.ofNull()));
    return completedMono(result("collection", CollectionNodeSupport.sameKind(collection, items)));
  }
}
