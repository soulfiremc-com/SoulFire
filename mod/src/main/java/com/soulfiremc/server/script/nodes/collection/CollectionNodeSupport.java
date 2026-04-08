package com.soulfiremc.server.script.nodes.collection;

import com.google.gson.JsonObject;
import com.soulfiremc.server.script.NodeValue;

import java.util.ArrayList;
import java.util.List;

final class CollectionNodeSupport {
  private CollectionNodeSupport() {}

  static boolean isSet(NodeValue value) {
    return value instanceof NodeValue.ValueSet;
  }

  static List<NodeValue> orderedItems(NodeValue value) {
    return new ArrayList<>(value.asList());
  }

  static NodeValue sameKind(NodeValue original, List<NodeValue> items) {
    return isSet(original) ? NodeValue.ofSet(items) : NodeValue.ofList(items);
  }

  static JsonObject requireMap(NodeValue value) {
    var json = value.asJsonElement();
    return json != null && json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
  }
}
