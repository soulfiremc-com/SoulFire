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
import com.google.gson.JsonObject;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Centralized runtime coercion between NodeValue instances and declared port types.
/// This keeps validation, input materialization, and implicit conversions aligned.
public final class NodeValueConversion {
  private NodeValueConversion() {}

  /// Conversion result with an optional converted value and a failure reason.
  public record Result<T>(@Nullable T value, @Nullable String failureReason) {
    public boolean success() {
      return failureReason == null;
    }

    public Optional<T> optionalValue() {
      return Optional.ofNullable(value);
    }

    static <T> Result<T> success(T value) {
      return new Result<>(value, null);
    }

    static <T> Result<T> failure(String failureReason) {
      return new Result<>(null, failureReason);
    }
  }

  /// Whether the given value can be coerced to the target port type.
  public static boolean canConvert(@Nullable NodeValue value, PortType targetType) {
    return convert(value, targetType).success();
  }

  /// Attempts to coerce the value to the target port type.
  public static Result<?> convert(@Nullable NodeValue value, PortType targetType) {
    if (targetType == PortType.ANY || targetType == PortType.EXEC) {
      return Result.success(value);
    }
    if (targetType == PortType.STRING) {
      return toStringValue(value);
    }
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    return switch (targetType) {
      case NUMBER -> toDouble(value);
      case BOOLEAN -> toBoolean(value);
      case VECTOR3 -> toVector3(value);
      case BOT -> toBot(value);
      case LIST -> toList(value);
      case MAP -> toMap(value);
      case SET -> toSet(value);
      case COLLECTION -> toCollection(value);
      case BLOCK, ENTITY, ITEM -> toIdentifier(value);
      case ANY, EXEC, STRING -> throw new IllegalStateException("Handled above");
    };
  }

  public static Result<String> toStringValue(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.Json(var element)) {
      if (element.isJsonPrimitive()) {
        return Result.success(element.getAsString());
      }
      return Result.success(element.toString());
    }
    return Result.success(value.toString());
  }

  public static Result<Double> toDouble(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.Json(var element) && element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      if (primitive.isNumber()) {
        return Result.success(primitive.getAsDouble());
      }
      if (primitive.isBoolean()) {
        return Result.success(primitive.getAsBoolean() ? 1.0 : 0.0);
      }
      if (primitive.isString()) {
        var raw = primitive.getAsString().trim();
        try {
          return Result.success(Double.parseDouble(raw));
        } catch (NumberFormatException _) {
          return Result.failure("string '" + raw + "' is not a number");
        }
      }
    }
    return Result.failure("expected number-compatible value");
  }

  public static Result<Boolean> toBoolean(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.Json(var element) && element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      if (primitive.isBoolean()) {
        return Result.success(primitive.getAsBoolean());
      }
      if (primitive.isNumber()) {
        return Result.success(primitive.getAsDouble() != 0.0);
      }
      if (primitive.isString()) {
        var raw = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
          case "true", "1", "yes", "on" -> Result.success(true);
          case "false", "0", "no", "off" -> Result.success(false);
          default -> Result.failure("string '" + raw + "' is not a boolean");
        };
      }
    }
    return Result.failure("expected boolean-compatible value");
  }

  public static Result<Vec3> toVector3(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.Vector3(var vector)) {
      return Result.success(vector);
    }
    if (value instanceof NodeValue.ValueList(var items)) {
      return vectorFromItems(items);
    }
    if (value instanceof NodeValue.Json(var element)) {
      if (element.isJsonArray()) {
        return vectorFromItems(value.asList());
      }
      if (element.isJsonObject()) {
        return vectorFromObject(element.getAsJsonObject());
      }
    }
    return Result.failure("expected Vector3, [x, y, z], or {x, y, z}");
  }

  public static Result<BotConnection> toBot(@Nullable NodeValue value) {
    if (value instanceof NodeValue.Bot(var bot)) {
      return Result.success(bot);
    }
    return Result.failure("expected bot reference");
  }

  public static Result<List<NodeValue>> toList(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.ValueList(var items)) {
      return Result.success(items);
    }
    if (value instanceof NodeValue.Json(var element) && element.isJsonArray()) {
      return Result.success(value.asList());
    }
    return Result.failure("expected list value");
  }

  public static Result<JsonObject> toMap(@Nullable NodeValue value) {
    if (value instanceof NodeValue.Json(var element) && element.isJsonObject()) {
      return Result.success(element.getAsJsonObject());
    }
    return Result.failure("expected map/object value");
  }

  public static Result<Set<NodeValue>> toSet(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.ValueSet(var items)) {
      return Result.success(items);
    }
    if (value instanceof NodeValue.ValueList(var items)) {
      return Result.success(items.stream()
        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
    if (value instanceof NodeValue.Json(var element) && element.isJsonArray()) {
      return Result.success(value.asSet());
    }
    return Result.failure("expected set value");
  }

  public static Result<NodeValue> toCollection(@Nullable NodeValue value) {
    if (value == null || value.isNull()) {
      return Result.failure("value is null");
    }
    if (value instanceof NodeValue.ValueList || value instanceof NodeValue.ValueSet) {
      return Result.success(value);
    }
    if (value instanceof NodeValue.Json(var element) && element.isJsonArray()) {
      return Result.success(value);
    }
    return Result.failure("expected collection value");
  }

  public static Result<String> toIdentifier(@Nullable NodeValue value) {
    if (value instanceof NodeValue.Json(var element)
      && element.isJsonPrimitive()
      && element.getAsJsonPrimitive().isString()) {
      return Result.success(element.getAsString());
    }
    return Result.failure("expected string identifier");
  }

  private static Result<Vec3> vectorFromItems(List<NodeValue> items) {
    if (items.size() != 3) {
      return Result.failure("expected exactly 3 vector components, got " + items.size());
    }

    var x = toDouble(items.getFirst());
    if (!x.success()) {
      return Result.failure("invalid x component: " + x.failureReason());
    }
    var y = toDouble(items.get(1));
    if (!y.success()) {
      return Result.failure("invalid y component: " + y.failureReason());
    }
    var z = toDouble(items.get(2));
    if (!z.success()) {
      return Result.failure("invalid z component: " + z.failureReason());
    }

    return Result.success(new Vec3(x.value(), y.value(), z.value()));
  }

  private static Result<Vec3> vectorFromObject(JsonObject object) {
    if (!object.has("x") || !object.has("y") || !object.has("z")) {
      return Result.failure("expected object with x, y, and z properties");
    }

    var x = toDouble(NodeValue.fromJson(object.get("x")));
    if (!x.success()) {
      return Result.failure("invalid x component: " + x.failureReason());
    }
    var y = toDouble(NodeValue.fromJson(object.get("y")));
    if (!y.success()) {
      return Result.failure("invalid y component: " + y.failureReason());
    }
    var z = toDouble(NodeValue.fromJson(object.get("z")));
    if (!z.success()) {
      return Result.failure("invalid z component: " + z.failureReason());
    }

    return Result.success(new Vec3(x.value(), y.value(), z.value()));
  }
}
