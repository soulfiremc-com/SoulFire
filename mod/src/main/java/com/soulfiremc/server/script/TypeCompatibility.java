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

import java.util.*;

/// Single source of truth for port type compatibility rules.
/// Used by both the server-side graph validator and the client-side connection check
/// (via the PortTypeMetadata proto sent in GetNodeTypesResponse).
public final class TypeCompatibility {

  /// The complete compatibility table: for each target type, which source types can connect to it.
  /// This is the authoritative definition. Both isCompatible() and buildCompatibleFromMap()
  /// derive from the same logic.
  private static final Map<PortType, Set<PortType>> COMPATIBLE_FROM = buildCompatibleFromMap();

  private static Map<PortType, Set<PortType>> buildCompatibleFromMap() {
    var map = new EnumMap<PortType, Set<PortType>>(PortType.class);
    for (var type : PortType.values()) {
      map.put(type, EnumSet.noneOf(PortType.class));
    }

    for (var target : PortType.values()) {
      if (target != PortType.EXEC && target != PortType.ANY) {
        map.get(target).add(PortType.ANY);
      }
    }

    // STRING accepts everything except EXEC.
    for (var source : PortType.values()) {
      if (source != PortType.EXEC && source != PortType.STRING) {
        map.get(PortType.STRING).add(source);
      }
    }

    // NUMBER accepts BOOLEAN and STRING
    map.get(PortType.NUMBER).addAll(EnumSet.of(PortType.STRING, PortType.BOOLEAN));

    // BOOLEAN accepts NUMBER and STRING
    map.get(PortType.BOOLEAN).addAll(EnumSet.of(PortType.NUMBER, PortType.STRING));

    // VECTOR3 accepts structured list/object values as well as typed vectors.
    map.get(PortType.VECTOR3).addAll(EnumSet.of(PortType.LIST, PortType.MAP, PortType.SET));

    // LIST and SET can coerce into each other while preserving iteration order.
    map.get(PortType.LIST).add(PortType.SET);
    map.get(PortType.SET).add(PortType.LIST);
    map.get(PortType.COLLECTION).addAll(EnumSet.of(PortType.LIST, PortType.SET, PortType.COLLECTION));
    map.get(PortType.LIST).add(PortType.COLLECTION);
    map.get(PortType.SET).add(PortType.COLLECTION);

    // ANY accepts everything
    for (var type : PortType.values()) {
      if (type != PortType.EXEC && type != PortType.ANY) {
        map.get(PortType.ANY).add(type);
      }
    }

    return Collections.unmodifiableMap(map);
  }

  /// Checks whether a source port type is compatible with a target port type.
  /// Same type always matches. Additional implicit conversions come from COMPATIBLE_FROM.
  public static boolean isCompatible(PortType source, PortType target) {
    if (source == target) {
      return true;
    }
    var compatibleSet = COMPATIBLE_FROM.get(target);
    return compatibleSet != null && compatibleSet.contains(source);
  }

  /// Checks whether a source TypeDescriptor is compatible with a target TypeDescriptor.
  /// Handles generics, type variables, and parameterized types.
  /// This is a non-mutating check (does not bind type variables).
  public static boolean isDescriptorCompatible(TypeDescriptor source, TypeDescriptor target) {
    // Use unification with a temporary bindings map
    return TypeDescriptor.unify(source, target, TypeDescriptor.newBindings());
  }

  /// Returns the compatible_from set for a given target type.
  /// Used to populate the PortTypeMetadata proto.
  public static Set<PortType> getCompatibleFrom(PortType targetType) {
    return COMPATIBLE_FROM.getOrDefault(targetType, Set.of());
  }

  private TypeCompatibility() {}
}
