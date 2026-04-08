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
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlTask;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Abstract base class for script nodes providing common utility methods.
public abstract class AbstractScriptNode implements ScriptNode {

  /// Helper method to get an input value with type casting.
  /// For non-JSON types like Vec3, extracts from the underlying JsonElement if possible.
  @SuppressWarnings("unchecked")
  protected <T> T getInput(Map<String, NodeValue> inputs, String name, T defaultValue) {
    var value = inputs.get(name);
    if (value == null || value.isNull()) {
      return defaultValue;
    }

    // Special handling for Vec3 values, including legacy JSON-backed vectors.
    if (defaultValue instanceof Vec3) {
      var vector = NodeValueConversion.toVector3(value);
      if (vector.success()) {
        return (T) vector.value();
      }
      return defaultValue;
    }

    // For bot values
    if (defaultValue instanceof BotConnection) {
      var bot = NodeValueConversion.toBot(value);
      if (bot.success()) {
        return (T) bot.value();
      }
    }

    return defaultValue;
  }

  /// Helper method to get a double input value.
  protected double getDoubleInput(Map<String, NodeValue> inputs, String name, double defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return NodeValueConversion.toDouble(value).optionalValue().orElse(defaultValue);
  }

  /// Helper method to get an int input value.
  protected int getIntInput(Map<String, NodeValue> inputs, String name, int defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return value.asInt(defaultValue);
  }

  /// Helper method to get a long input value.
  protected long getLongInput(Map<String, NodeValue> inputs, String name, long defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return value.asLong(defaultValue);
  }

  /// Helper method to get a float input value.
  protected float getFloatInput(Map<String, NodeValue> inputs, String name, float defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return (float) value.asDouble(defaultValue);
  }

  /// Helper method to get a boolean input value.
  protected boolean getBooleanInput(Map<String, NodeValue> inputs, String name, boolean defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return NodeValueConversion.toBoolean(value).optionalValue().orElse(defaultValue);
  }

  /// Helper method to get a string input value.
  protected String getStringInput(Map<String, NodeValue> inputs, String name, String defaultValue) {
    var value = inputs.get(name);
    if (value == null) {
      return defaultValue;
    }
    return NodeValueConversion.toStringValue(value).optionalValue().orElse(defaultValue);
  }

  /// Helper method to get a list input value as NodeValues.
  protected List<NodeValue> getListInput(Map<String, NodeValue> inputs, String name) {
    var value = inputs.get(name);
    if (value == null) {
      return List.of();
    }
    return value.asList();
  }

  /// Helper method to get a set input value as NodeValues.
  protected Set<NodeValue> getSetInput(Map<String, NodeValue> inputs, String name) {
    var value = inputs.get(name);
    if (value == null) {
      return Set.of();
    }
    return value.asSet();
  }

  /// Helper method to get a collection input while preserving list/set identity.
  protected NodeValue getCollectionInput(Map<String, NodeValue> inputs, String name) {
    var value = inputs.get(name);
    return value != null ? value : NodeValue.ofList(List.of());
  }

  /// Helper method to get a list of strings.
  protected List<String> getStringListInput(Map<String, NodeValue> inputs, String name) {
    var value = inputs.get(name);
    if (value == null) {
      return List.of();
    }
    return value.asStringList();
  }

  /// Helper method to get a bot from inputs.
  /// Returns null if the "bot" input is not set.
  ///
  /// @param inputs the node inputs
  /// @return the bot connection, or null if not provided
  @Nullable
  protected BotConnection getBotInput(Map<String, NodeValue> inputs) {
    var value = inputs.get("bot");
    if (value == null) {
      return null;
    }
    return value.asBot();
  }

  /// Helper method to require a bot from inputs.
  /// The bot is typically provided automatically via the execution context
  /// from an upstream trigger node or ForEachBot node.
  ///
  /// @param inputs the node inputs (includes execution context values)
  /// @return the bot connection
  /// @throws IllegalStateException if no bot is available
  protected BotConnection requireBot(Map<String, NodeValue> inputs) {
    var bot = getBotInput(inputs);
    if (bot == null) {
      throw new IllegalStateException("This node requires a bot in the execution context. "
        + "Ensure it is downstream of a bot-producing trigger (OnPreEntityTick, OnChat, etc.) "
        + "or a ForEachBot/GetBotByName node.");
    }
    return bot;
  }

  /// Helper method to get a raw JsonElement from inputs.
  @Nullable
  protected JsonElement getJsonInput(Map<String, NodeValue> inputs, String name) {
    var value = inputs.get(name);
    if (value == null) {
      return null;
    }
    return value.asJsonElement();
  }

  /// Helper method to run an action on the tick thread.
  /// When executing synchronously on the tick thread (from entity tick triggers),
  /// runs the action directly. Otherwise, defers via ControlTask.once().
  ///
  /// @param runtime the node runtime (provides sync/async context)
  /// @param bot     the bot connection
  /// @param action  the action to execute on the tick thread
  protected void runOnTickThread(NodeRuntime runtime, BotConnection bot, Runnable action) {
    if (runtime.isTickSynchronous()) {
      action.run();
    } else {
      bot.botControl().replace(ControlTask.once(getClass().getSimpleName(), action));
    }
  }

  /// Helper method to create a result map with a single value.
  protected Map<String, NodeValue> result(String key, Object value) {
    var map = new HashMap<String, NodeValue>();
    map.put(key, NodeValue.of(value));
    return map;
  }

  /// Helper method to create a result map with multiple values.
  protected Map<String, NodeValue> results(Object... keyValuePairs) {
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("Must provide key-value pairs");
    }
    var map = new HashMap<String, NodeValue>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put((String) keyValuePairs[i], NodeValue.of(keyValuePairs[i + 1]));
    }
    return map;
  }

  /// Helper method to create an empty result.
  protected Map<String, NodeValue> emptyResult() {
    return Map.of();
  }

  /// Helper method to create a completed Mono with results.
  protected Mono<Map<String, NodeValue>> completedMono(Map<String, NodeValue> results) {
    return Mono.just(results);
  }

  /// Helper method to create a completed Mono with empty results.
  protected Mono<Map<String, NodeValue>> completedEmptyMono() {
    return Mono.just(Map.of());
  }

  /// Helper method to create a delayed Mono with results.
  protected Mono<Map<String, NodeValue>> delayedMono(Duration delay, Map<String, NodeValue> results) {
    return Mono.delay(delay).thenReturn(results);
  }

  /// Helper method to create a delayed Mono with empty results.
  protected Mono<Map<String, NodeValue>> delayedEmptyMono(Duration delay) {
    return Mono.delay(delay).thenReturn(Map.of());
  }
}
