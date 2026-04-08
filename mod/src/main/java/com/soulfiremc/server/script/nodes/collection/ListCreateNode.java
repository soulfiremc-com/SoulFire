package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class ListCreateNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("list.create")
    .displayName("List Create")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.multiInput("items", "Items", PortType.ANY, "Items to include in the list"))
    .addOutputs(PortDefinition.listOutput("list", "List", PortType.ANY, "The created list"))
    .description("Creates a native list from ordered inputs")
    .icon("list")
    .color("#00BCD4")
    .addKeywords("list", "create", "build", "collection")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("list", NodeValue.ofList(getListInput(inputs, "items"))));
  }
}
