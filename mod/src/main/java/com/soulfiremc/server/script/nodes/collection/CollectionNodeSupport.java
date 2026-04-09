/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
