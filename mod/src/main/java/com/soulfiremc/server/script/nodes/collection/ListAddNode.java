package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class ListAddNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("list.add")
    .displayName("List Add")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericListInput("list", "List", TypeDescriptor.typeVar("T"), "The list to extend"),
      PortDefinition.genericInput("item", "Item", TypeDescriptor.typeVar("T"), "The item to append")
    )
    .addOutputs(PortDefinition.genericListOutput("list", "List", TypeDescriptor.typeVar("T"), "The updated list"))
    .description("Returns a new list with an item appended")
    .icon("plus")
    .color("#00BCD4")
    .addKeywords("list", "add", "append")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var list = getListInput(inputs, "list");
    var updated = new java.util.ArrayList<>(list);
    updated.add(inputs.getOrDefault("item", NodeValue.ofNull()));
    return completedMono(result("list", NodeValue.ofList(updated)));
  }
}
