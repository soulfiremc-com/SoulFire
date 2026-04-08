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

import com.google.gson.*;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/// Sealed interface representing a value that can be passed between script nodes.
/// Values are either JSON-serializable data or runtime bot references.
public sealed interface NodeValue {

  /// Creates a NodeValue from any object, converting to the appropriate type.
  static NodeValue of(@Nullable Object value) {
    if (value == null) {
      return new Json(JsonNull.INSTANCE);
    }
    if (value instanceof NodeValue nv) {
      return nv;
    }
    if (value instanceof BotConnection bot) {
      return new Bot(bot);
    }
    if (value instanceof JsonElement json) {
      return new Json(json);
    }
    if (value instanceof Vec3 v) {
      return new Vector3(v);
    }
    if (value instanceof String s) {
      return new Json(new JsonPrimitive(s));
    }
    if (value instanceof Number n) {
      return new Json(new JsonPrimitive(n));
    }
    if (value instanceof Boolean b) {
      return new Json(new JsonPrimitive(b));
    }
    if (value instanceof List<?> list) {
      var nodeValues = list.stream().map(NodeValue::of).toList();
      var hasNonJson = nodeValues.stream().anyMatch(v -> !(v instanceof Json));
      if (hasNonJson) {
        return new ValueList(nodeValues);
      }
      var array = new JsonArray();
      for (var nv : nodeValues) {
        if (nv instanceof Json(JsonElement element)) {
          array.add(element);
        }
      }
      return new Json(array);
    }
    if (value instanceof Set<?> set) {
      var nodeValues = set.stream().map(NodeValue::of).toList();
      return ofSet(nodeValues);
    }
    if (value instanceof Map<?, ?> map) {
      var obj = new JsonObject();
      for (var entry : map.entrySet()) {
        var itemValue = of(entry.getValue());
        if (itemValue instanceof Json(JsonElement element)) {
          obj.add(entry.getKey().toString(), element);
        }
      }
      return new Json(obj);
    }
    // Fallback: convert to string
    return new Json(new JsonPrimitive(value.toString()));
  }

  /// Creates a null NodeValue.
  static NodeValue ofNull() {
    return new Json(JsonNull.INSTANCE);
  }

  /// Creates a string NodeValue.
  static NodeValue ofString(String value) {
    return new Json(new JsonPrimitive(value));
  }

  /// Creates a number NodeValue.
  static NodeValue ofNumber(Number value) {
    return new Json(new JsonPrimitive(value));
  }

  /// Creates a boolean NodeValue.
  static NodeValue ofBoolean(boolean value) {
    return new Json(new JsonPrimitive(value));
  }

  /// Creates a list NodeValue.
  /// If the list contains any non-Json values (e.g., Bot references),
  /// a ValueList is used to preserve them. Otherwise, a Json array is used.
  static NodeValue ofList(List<NodeValue> values) {
    var hasNonJson = values.stream().anyMatch(v -> !(v instanceof Json));
    if (hasNonJson) {
      return new ValueList(List.copyOf(values));
    }
    var array = new JsonArray();
    for (var value : values) {
      if (value instanceof Json(JsonElement element)) {
        array.add(element);
      }
    }
    return new Json(array);
  }

  /// Creates a set NodeValue with stable insertion order.
  static NodeValue ofSet(List<NodeValue> values) {
    var orderedValues = values.stream()
      .collect(Collectors.toCollection(LinkedHashSet::new));
    return new ValueSet(Collections.unmodifiableSet(new LinkedHashSet<>(orderedValues)));
  }

  /// Creates a bot NodeValue.
  static NodeValue ofBot(BotConnection bot) {
    return new Bot(bot);
  }

  /// Creates a vector NodeValue.
  static NodeValue ofVector3(Vec3 value) {
    return new Vector3(value);
  }

  /// Creates a NodeValue from a JsonElement.
  static NodeValue fromJson(JsonElement element) {
    return new Json(element);
  }

  /// Checks if this value is null.
  default boolean isNull() {
    return this instanceof Json(JsonElement element) && element.isJsonNull();
  }

  /// Gets this value as a string, or returns the default if not a string.
  default String asString(String defaultValue) {
    return NodeValueConversion.toStringValue(this).optionalValue().orElse(defaultValue);
  }

  /// Gets this value as a double, or returns the default if not a number.
  default double asDouble(double defaultValue) {
    return NodeValueConversion.toDouble(this).optionalValue().orElse(defaultValue);
  }

  /// Gets this value as an int, or returns the default if not a number.
  default int asInt(int defaultValue) {
    return NodeValueConversion.toDouble(this)
      .optionalValue()
      .map(Double::intValue)
      .orElse(defaultValue);
  }

  /// Gets this value as a long, or returns the default if not a number.
  default long asLong(long defaultValue) {
    return NodeValueConversion.toDouble(this)
      .optionalValue()
      .map(Double::longValue)
      .orElse(defaultValue);
  }

  /// Gets this value as a boolean, or returns the default if not a boolean.
  default boolean asBoolean(boolean defaultValue) {
    return NodeValueConversion.toBoolean(this).optionalValue().orElse(defaultValue);
  }

  /// Gets this value as a list of NodeValues.
  default List<NodeValue> asList() {
    if (this instanceof ValueList(List<NodeValue> items)) {
      return items;
    }
    if (this instanceof ValueSet(Set<NodeValue> items)) {
      return List.copyOf(items);
    }
    if (this instanceof Json(JsonElement element) && element.isJsonArray()) {
      return StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
        .map(Json::new)
        .collect(Collectors.toList());
    }
    return List.of();
  }

  /// Gets this value as a list of strings.
  default List<String> asStringList() {
    if (this instanceof Json(JsonElement element1) && element1.isJsonArray()) {
      var result = new ArrayList<String>();
      for (var element : element1.getAsJsonArray()) {
        if (element.isJsonPrimitive()) {
          result.add(element.getAsString());
        }
      }
      return result;
    }
    return List.of();
  }

  /// Gets this value as an ordered set of NodeValues.
  default Set<NodeValue> asSet() {
    if (this instanceof ValueSet(Set<NodeValue> items)) {
      return items;
    }
    if (this instanceof ValueList(List<NodeValue> items)) {
      return items.stream().collect(Collectors.toCollection(LinkedHashSet::new));
    }
    if (this instanceof Json(JsonElement element) && element.isJsonArray()) {
      return StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
        .map(Json::new)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    return Set.of();
  }

  @Nullable
  default Vec3 asVec3() {
    return NodeValueConversion.toVector3(this).optionalValue().orElse(null);
  }

  /// Gets this value as a BotConnection, or null if not a bot.
  @Nullable
  default BotConnection asBot() {
    return NodeValueConversion.toBot(this).optionalValue().orElse(null);
  }

  /// Gets the raw JsonElement if this is a Json value.
  @Nullable
  default JsonElement asJsonElement() {
    if (this instanceof Json(JsonElement element)) {
      return element;
    }
    if (this instanceof ValueSet(Set<NodeValue> items)) {
      var array = new JsonArray();
      for (var value : items) {
        var element = value.asJsonElement();
        if (element == null) {
          return null;
        }
        array.add(element);
      }
      return array;
    }
    return null;
  }

  /// Returns a short debug string showing type and truncated value.
  default String toDebugString() {
    return switch (this) {
      case Json(JsonElement el) -> {
        var str = el.toString();
        yield "Json(" + (str.length() > 50 ? str.substring(0, 50) + "..." : str) + ")";
      }
      case Bot(BotConnection bot1) -> "Bot(" + bot1.accountName() + ")";
      case ValueList(List<NodeValue> items) -> "List[" + items.size() + "]";
      case ValueSet(Set<NodeValue> items) -> "Set[" + items.size() + "]";
      case Vector3(Vec3 vec) -> "Vector3(" + vec + ")";
    };
  }

  /// JSON-serializable value (strings, numbers, booleans, arrays, objects, null).
  record Json(JsonElement element) implements NodeValue {
    @Override
    public String toString() {
      return element.toString();
    }
  }

  /// Runtime bot reference (not JSON-serializable).
  record Bot(BotConnection bot) implements NodeValue {
    @Override
    public String toString() {
      return "Bot[" + bot.accountName() + "]";
    }
  }

  record Vector3(Vec3 value) implements NodeValue {
    @Override
    public String toString() {
      return "Vector3[" + value + "]";
    }
  }

  /// List of NodeValues that may contain non-JSON values (e.g., Bot references).
  record ValueList(List<NodeValue> items) implements NodeValue {
    @Override
    public String toString() {
      return items.toString();
    }
  }

  /// Set of NodeValues that may contain non-JSON values while preserving insertion order.
  record ValueSet(Set<NodeValue> items) implements NodeValue {
    public ValueSet {
      items = Collections.unmodifiableSet(new LinkedHashSet<>(items));
    }

    @Override
    public String toString() {
      return "Set[" + items.size() + "]";
    }
  }
}
