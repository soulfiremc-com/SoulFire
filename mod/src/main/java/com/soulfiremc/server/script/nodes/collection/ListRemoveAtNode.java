package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class ListRemoveAtNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("list.remove_at")
    .displayName("List Remove At")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(
      PortDefinition.genericListInput("list", "List", TypeDescriptor.typeVar("T"), "The list to modify"),
      PortDefinition.input("index", "Index", PortType.NUMBER, "The index to remove")
    )
    .addOutputs(PortDefinition.genericListOutput("list", "List", TypeDescriptor.typeVar("T"), "The updated list"))
    .description("Returns a new list with the item at the given index removed")
    .icon("minus")
    .color("#00BCD4")
    .addKeywords("list", "remove", "index", "delete")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var list = new java.util.ArrayList<>(getListInput(inputs, "list"));
    var index = getIntInput(inputs, "index", -1);
    if (index >= 0 && index < list.size()) {
      list.remove(index);
    }
    return completedMono(result("list", NodeValue.ofList(list)));
  }
}
