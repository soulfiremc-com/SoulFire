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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Registry of all available node categories.
/// Provides predefined categories and lookup methods.
public final class CategoryRegistry {
  private static final Map<String, NodeCategory> CATEGORIES = new HashMap<>();

  // Predefined category instances
  public static final NodeCategory TRIGGERS = register(
    NodeCategory.of("triggers", "Triggers", "zap", "Event triggers that start script execution", 0));
  public static final NodeCategory ACTIONS = register(
    NodeCategory.of("actions", "Actions", "play", "Nodes that perform bot actions", 1));
  public static final NodeCategory DATA = register(
    NodeCategory.of("data", "Data", "database", "Nodes that read game data", 2));
  public static final NodeCategory FLOW = register(
    NodeCategory.of("flow", "Flow Control", "git-branch", "Control execution flow and branching", 3));
  public static final NodeCategory LOGIC = register(
    NodeCategory.of("logic", "Logic", "toggle-left", "Boolean logic operations", 4));
  public static final NodeCategory MATH = register(
    NodeCategory.of("math", "Math", "calculator", "Mathematical operations", 5));
  public static final NodeCategory STRING = register(
    NodeCategory.of("string", "String", "type", "Text manipulation operations", 6));
  public static final NodeCategory COLLECTION = register(
    NodeCategory.of("collection", "Collection", "list", "Collection operations shared by lists and sets", 7));
  public static final NodeCategory LIST = register(
    NodeCategory.of("list", "List", "list", "List/array specific operations", 8));
  public static final NodeCategory CONSTANTS = register(
    NodeCategory.of("constants", "Constants", "hash", "Constant value nodes", 9));
  public static final NodeCategory UTILITY = register(
    NodeCategory.of("utility", "Utility", "tool", "Utility and conversion nodes", 10));
  public static final NodeCategory NETWORK = register(
    NodeCategory.of("network", "Network", "globe", "HTTP requests and web operations", 11));
  public static final NodeCategory AI = register(
    NodeCategory.of("ai", "AI", "brain", "AI/LLM operations", 12));
  public static final NodeCategory JSON = register(
    NodeCategory.of("json", "JSON", "braces", "JSON parsing and manipulation", 13));
  public static final NodeCategory ENCODING = register(
    NodeCategory.of("encoding", "Encoding", "lock", "Hashing, encryption, compression", 14));
  public static final NodeCategory STATE = register(
    NodeCategory.of("state", "State", "database", "Caching and state management", 15));
  public static final NodeCategory INTEGRATION = register(
    NodeCategory.of("integration", "Integration", "plug", "External service integrations", 16));
  public static final NodeCategory VARIABLE = register(
    NodeCategory.of("variable", "Variables", "variable", "Bot variable storage", 17));

  private CategoryRegistry() {
    // Prevent instantiation
  }

  /// Registers a category.
  ///
  /// @param category the category to register
  /// @return the registered category (for chaining)
  public static NodeCategory register(NodeCategory category) {
    CATEGORIES.put(category.id(), category);
    return category;
  }

  /// Gets a category by its ID, or UTILITY as fallback.
  ///
  /// @param id the category ID
  /// @return the category, or UTILITY if not found
  public static NodeCategory fromId(String id) {
    return CATEGORIES.getOrDefault(id, UTILITY);
  }

  /// Checks if a category is registered.
  ///
  /// @param id the category ID
  /// @return true if the category is registered
  public static boolean isRegistered(String id) {
    return CATEGORIES.containsKey(id);
  }

  /// Gets all registered categories sorted by sort order.
  ///
  /// @return sorted list of all categories
  public static List<NodeCategory> allSorted() {
    return CATEGORIES.values().stream()
      .sorted(Comparator.comparingInt(NodeCategory::sortOrder))
      .toList();
  }

  /// Gets the number of registered categories.
  ///
  /// @return the count of registered categories
  public static int getRegisteredCount() {
    return CATEGORIES.size();
  }
}
