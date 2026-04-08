package com.soulfiremc.server.script.nodes.collection;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.Map;

public final class SetCreateNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("set.create")
    .displayName("Set Create")
    .category(CategoryRegistry.COLLECTION)
    .addInputs(PortDefinition.multiInput("items", "Items", PortType.ANY, "Items to include in the set"))
    .addOutputs(PortDefinition.setOutput("set", "Set", PortType.ANY, "The created set"))
    .description("Creates a native set from inputs while preserving first-seen order")
    .icon("git-branch-plus")
    .color("#00BCD4")
    .addKeywords("set", "create", "build", "distinct", "collection")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    return completedMono(result("set", NodeValue.ofSet(getListInput(inputs, "items"))));
  }
}
