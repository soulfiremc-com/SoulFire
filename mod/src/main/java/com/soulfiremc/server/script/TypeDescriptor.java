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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A recursive type descriptor that supports generic/parameterized types.
/// Used on port definitions to express types like List<Bot>, Map<String, Number>,
/// or type variables like T that get resolved based on connections.
public sealed interface TypeDescriptor {

  // ==================== Factory Methods ====================

  /// Creates a simple (non-parameterized) type descriptor.
  static TypeDescriptor simple(PortType type) {
    return new Simple(type);
  }

  /// Creates a type variable that gets resolved from connected ports.
  /// Type variables are scoped per node: each node instance has its own bindings.
  static TypeDescriptor typeVar(String name) {
    return new TypeVariable(name);
  }

  /// Creates a parameterized List type: List<elementType>.
  static TypeDescriptor list(TypeDescriptor elementType) {
    return new Parameterized(PortType.LIST, List.of(elementType));
  }

  /// Creates a parameterized Map type: Map<keyType, valueType>.
  static TypeDescriptor map(TypeDescriptor keyType, TypeDescriptor valueType) {
    return new Parameterized(PortType.MAP, List.of(keyType, valueType));
  }

  /// Creates a parameterized Set type: Set<elementType>.
  static TypeDescriptor set(TypeDescriptor elementType) {
    return new Parameterized(PortType.SET, List.of(elementType));
  }

  /// Creates a parameterized Collection type: Collection<elementType>.
  static TypeDescriptor collection(TypeDescriptor elementType) {
    return new Parameterized(PortType.COLLECTION, List.of(elementType));
  }

  /// Convenience: List<Simple(elementType)>.
  static TypeDescriptor listOf(PortType elementType) {
    return list(simple(elementType));
  }

  /// Convenience: Map<Simple(keyType), Simple(valueType)>.
  static TypeDescriptor mapOf(PortType keyType, PortType valueType) {
    return map(simple(keyType), simple(valueType));
  }

  /// Convenience: Set<Simple(elementType)>.
  static TypeDescriptor setOf(PortType elementType) {
    return set(simple(elementType));
  }

  /// Convenience: Collection<Simple(elementType)>.
  static TypeDescriptor collectionOf(PortType elementType) {
    return collection(simple(elementType));
  }

  // ==================== Instance Methods ====================

  /// Returns the base PortType for backward compatibility.
  /// Simple returns its type, Parameterized returns its base, TypeVariable returns ANY.
  PortType baseType();

  /// Returns a human-readable representation like "List<Bot>" or "T".
  String displayString();

  /// Resolves type variables using the given bindings.
  /// Returns a new TypeDescriptor with all known variables replaced.
  TypeDescriptor resolve(Map<String, TypeDescriptor> bindings);

  /// Checks whether this descriptor contains any type variables.
  boolean hasTypeVariables();

  // ==================== Unification ====================

  /// Attempts to unify two type descriptors, updating bindings for type variables.
  /// Returns true if unification succeeds, false if the types are incompatible.
  ///
  /// Unification rules:
  /// - TypeVariable unifies with anything (binds the variable)
  /// - Simple(ANY) unifies with anything
  /// - Simple(A) unifies with Simple(B) if A == B or compatible via TypeCompatibility
  /// - Parameterized(base, params) unifies with Parameterized(base2, params2)
  ///   if base == base2 and all params unify pairwise
  static boolean unify(TypeDescriptor a, TypeDescriptor b, Map<String, TypeDescriptor> bindings) {
    // Resolve any already-bound variables first
    var resolvedA = a.resolve(bindings);
    var resolvedB = b.resolve(bindings);

    // TypeVariable on either side: bind it
    if (resolvedA instanceof TypeVariable(String nameA)) {
      bindings.put(nameA, resolvedB);
      return true;
    }
    if (resolvedB instanceof TypeVariable(String nameB)) {
      bindings.put(nameB, resolvedA);
      return true;
    }

    // ANY on either side matches everything
    if (resolvedA instanceof Simple(PortType typeA) && typeA == PortType.ANY) {
      return true;
    }
    if (resolvedB instanceof Simple(PortType typeB) && typeB == PortType.ANY) {
      return true;
    }

    // Simple vs Simple
    if (resolvedA instanceof Simple(PortType typeA) && resolvedB instanceof Simple(PortType typeB)) {
      return TypeCompatibility.isCompatible(typeA, typeB);
    }

    // Parameterized vs Parameterized
    if (resolvedA instanceof Parameterized(PortType baseA, List<TypeDescriptor> paramsA)
      && resolvedB instanceof Parameterized(PortType baseB, List<TypeDescriptor> paramsB)) {
      if (baseA != baseB || paramsA.size() != paramsB.size()) {
        return false;
      }
      for (var i = 0; i < paramsA.size(); i++) {
        if (!unify(paramsA.get(i), paramsB.get(i), bindings)) {
          return false;
        }
      }
      return true;
    }

    // Simple LIST vs Parameterized LIST (backward compat: unparameterized list matches any list)
    if (resolvedA instanceof Simple(PortType typeA) && typeA == PortType.LIST
      && resolvedB instanceof Parameterized(PortType baseB, List<TypeDescriptor> _) && baseB == PortType.LIST) {
      return true;
    }
    if (resolvedB instanceof Simple(PortType typeB) && typeB == PortType.LIST
      && resolvedA instanceof Parameterized(PortType baseA, List<TypeDescriptor> _) && baseA == PortType.LIST) {
      return true;
    }

    // Simple MAP vs Parameterized MAP (same backward compat)
    if (resolvedA instanceof Simple(PortType typeA) && typeA == PortType.MAP
      && resolvedB instanceof Parameterized(PortType baseB, List<TypeDescriptor> _) && baseB == PortType.MAP) {
      return true;
    }
    if (resolvedB instanceof Simple(PortType typeB) && typeB == PortType.MAP
      && resolvedA instanceof Parameterized(PortType baseA, List<TypeDescriptor> _) && baseA == PortType.MAP) {
      return true;
    }

    // Simple SET vs Parameterized SET (same backward compat)
    if (resolvedA instanceof Simple(PortType typeA) && typeA == PortType.SET
      && resolvedB instanceof Parameterized(PortType baseB, List<TypeDescriptor> _) && baseB == PortType.SET) {
      return true;
    }
    if (resolvedB instanceof Simple(PortType typeB) && typeB == PortType.SET
      && resolvedA instanceof Parameterized(PortType baseA, List<TypeDescriptor> _) && baseA == PortType.SET) {
      return true;
    }

    // COLLECTION unifies with concrete LIST/SET families as a parent type.
    if (resolvedA instanceof Simple(PortType typeA) && typeA == PortType.COLLECTION
      && resolvedB instanceof Parameterized(PortType baseB, List<TypeDescriptor> _) && (baseB == PortType.COLLECTION || baseB == PortType.LIST || baseB == PortType.SET)) {
      return true;
    }
    if (resolvedB instanceof Simple(PortType typeB) && typeB == PortType.COLLECTION
      && resolvedA instanceof Parameterized(PortType baseA, List<TypeDescriptor> _) && (baseA == PortType.COLLECTION || baseA == PortType.LIST || baseA == PortType.SET)) {
      return true;
    }

    return false;
  }

  /// Creates a fresh bindings map for unification.
  static Map<String, TypeDescriptor> newBindings() {
    return new HashMap<>();
  }

  // ==================== Record Types ====================

  /// A simple, non-parameterized type (e.g., NUMBER, STRING, BOT).
  record Simple(PortType type) implements TypeDescriptor {
    @Override
    public PortType baseType() {
      return type;
    }

    @Override
    public String displayString() {
      return type.name();
    }

    @Override
    public TypeDescriptor resolve(Map<String, TypeDescriptor> bindings) {
      return this;
    }

    @Override
    public boolean hasTypeVariables() {
      return false;
    }
  }

  /// A parameterized type like List<T>, Set<T>, or Map<K, V>.
  /// The base is the container type (LIST, SET, MAP), params are the type arguments.
  record Parameterized(PortType base, List<TypeDescriptor> params) implements TypeDescriptor {
    public Parameterized {
      params = List.copyOf(params);
    }

    @Override
    public PortType baseType() {
      return base;
    }

    @Override
    public String displayString() {
      var paramStr = params.stream()
        .map(TypeDescriptor::displayString)
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
      return base.name() + "<" + paramStr + ">";
    }

    @Override
    public TypeDescriptor resolve(Map<String, TypeDescriptor> bindings) {
      var resolvedParams = params.stream()
        .map(p -> p.resolve(bindings))
        .toList();
      return new Parameterized(base, resolvedParams);
    }

    @Override
    public boolean hasTypeVariables() {
      return params.stream().anyMatch(TypeDescriptor::hasTypeVariables);
    }
  }

  /// A type variable that gets bound during type inference.
  /// Variable names are scoped per node (e.g., "T", "K", "V").
  record TypeVariable(String name) implements TypeDescriptor {
    @Override
    public PortType baseType() {
      return PortType.ANY;
    }

    @Override
    public String displayString() {
      return name;
    }

    @Override
    public TypeDescriptor resolve(Map<String, TypeDescriptor> bindings) {
      var bound = bindings.get(name);
      if (bound != null) {
        // Recursively resolve in case the bound value also has variables
        return bound.resolve(bindings);
      }
      return this;
    }

    @Override
    public boolean hasTypeVariables() {
      return true;
    }
  }
}
