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
package com.soulfiremc.server.script;

import com.google.gson.JsonElement;

/// Checks whether a NodeValue matches a declared PortType at runtime.
public final class NodeValueTypeChecker {

  /// Returns true if the value is compatible with the declared port type.
  /// ANY always matches. STRING always matches (coercible).
  public static boolean matches(NodeValue value, PortType type) {
    return NodeValueConversion.canConvert(value, type);
  }

  /// Returns a human-readable description of the actual type.
  public static String describeActualType(NodeValue value) {
    if (value == null || value.isNull()) {
      return "null";
    }
    return switch (value) {
      case NodeValue.Bot _ -> "Bot";
      case NodeValue.ValueList _ -> "List";
      case NodeValue.ValueSet _ -> "Set";
      case NodeValue.Json(JsonElement element) -> describeJsonType(element);
      case NodeValue.Vector3 _ -> "Vector3";
    };
  }

  private static String describeJsonType(JsonElement element) {
    if (element.isJsonPrimitive()) {
      var prim = element.getAsJsonPrimitive();
      if (prim.isNumber()) {
        return "Number";
      }
      if (prim.isBoolean()) {
        return "Boolean";
      }
      if (prim.isString()) {
        return "String";
      }
    }
    if (element.isJsonArray()) {
      return "List";
    }
    if (element.isJsonObject()) {
      return "Object";
    }
    return "Unknown";
  }

  private NodeValueTypeChecker() {}
}
