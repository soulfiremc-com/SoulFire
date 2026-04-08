package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class CollectionRemoveNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("collection.remove")
    .displayName("Collection Remove")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericInput("collection", "Collection", TypeDescriptor.typeVar("C"), "The collection to modify"),
      PortDefinition.genericInput("item", "Item", TypeDescriptor.typeVar("T"), "The item to remove")
    )
    .addOutputs(PortDefinition.genericOutput("collection", "Collection", TypeDescriptor.typeVar("C"), "The updated collection"))
    .description("Returns a new collection with the first matching item removed")
    .icon("minus")
    .color("#00BCD4")
    .addKeywords("collection", "remove", "delete")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var collection = getCollectionInput(inputs, "collection");
    var items = CollectionNodeSupport.orderedItems(collection);
    items.remove(inputs.get("item"));
    return completedMono(result("collection", CollectionNodeSupport.sameKind(collection, items)));
  }
}
