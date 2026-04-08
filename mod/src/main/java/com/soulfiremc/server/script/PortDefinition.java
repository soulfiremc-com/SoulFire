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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

/// Definition of an input or output port on a node.
/// Used to describe the node's interface for client rendering.
/// Port IDs are simple names (e.g., "interval", "message", "bot").
/// The port type is stored separately in the type() field.
@Value.Immutable
@Value.Style(stagedBuilder = true)
public interface PortDefinition {
  /// Creates an execution input port.
  static PortDefinition execIn() {
    return ImmutablePortDefinition.builder()
      .id(StandardPorts.EXEC_IN)
      .displayName("In")
      .type(PortType.EXEC)
      .description("Execution input")
      .build();
  }

  /// Creates a bot input port (optional).
  /// When connected, overrides the bot from execution context.
  static PortDefinition botIn() {
    return ImmutablePortDefinition.builder()
      .id(StandardPorts.BOT_IN)
      .displayName("Bot")
      .type(PortType.BOT)
      .description("The bot to use (auto-provided by execution context if not connected)")
      .required(false)
      .build();
  }

  /// Creates an execution output port.
  static PortDefinition execOut() {
    return ImmutablePortDefinition.builder()
      .id(StandardPorts.EXEC_OUT)
      .displayName("Out")
      .type(PortType.EXEC)
      .description("Execution output")
      .build();
  }

  /// Creates a required input port.
  /// @param id simple port name (e.g., "interval", "target")
  static PortDefinition input(String id, String displayName, PortType type, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(type)
      .description(description)
      .required(true)
      .build();
  }

  /// Creates an optional input port with a default value.
  /// @param id simple port name (e.g., "interval", "message")
  static PortDefinition inputWithDefault(String id, String displayName, PortType type, String defaultValue, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(type)
      .description(description)
      .required(false)
      .defaultValue(defaultValue)
      .build();
  }

  /// Creates an output port.
  /// @param id simple port name (e.g., "success", "count")
  static PortDefinition output(String id, String displayName, PortType type, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(type)
      .description(description)
      .build();
  }

  /// Creates a list input port with element type.
  /// @param id simple port name (e.g., "items", "targets")
  static PortDefinition listInput(String id, String displayName, PortType elementType, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.LIST)
      .description(description)
      .required(true)
      .elementType(elementType)
      .typeDescriptor(TypeDescriptor.listOf(elementType))
      .build();
  }

  /// Creates a list output port with element type.
  /// @param id simple port name (e.g., "results", "bots")
  static PortDefinition listOutput(String id, String displayName, PortType elementType, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.LIST)
      .description(description)
      .elementType(elementType)
      .typeDescriptor(TypeDescriptor.listOf(elementType))
      .build();
  }

  /// Creates a required input port with a generic type descriptor.
  /// The base PortType is derived from the descriptor's baseType().
  static PortDefinition genericInput(String id, String displayName, TypeDescriptor typeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(typeDescriptor.baseType())
      .description(description)
      .required(true)
      .typeDescriptor(typeDescriptor)
      .build();
  }

  /// Creates an output port with a generic type descriptor.
  static PortDefinition genericOutput(String id, String displayName, TypeDescriptor typeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(typeDescriptor.baseType())
      .description(description)
      .typeDescriptor(typeDescriptor)
      .build();
  }

  /// Creates a list input port with a generic element type (e.g., List<T>).
  static PortDefinition genericListInput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.LIST)
      .description(description)
      .required(true)
      .typeDescriptor(TypeDescriptor.list(elementTypeDescriptor))
      .build();
  }

  /// Creates a list output port with a generic element type (e.g., List<T>).
  static PortDefinition genericListOutput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.LIST)
      .description(description)
      .typeDescriptor(TypeDescriptor.list(elementTypeDescriptor))
      .build();
  }

  /// Creates a set input port with element type.
  static PortDefinition setInput(String id, String displayName, PortType elementType, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.SET)
      .description(description)
      .required(true)
      .elementType(elementType)
      .typeDescriptor(TypeDescriptor.setOf(elementType))
      .build();
  }

  /// Creates a set output port with element type.
  static PortDefinition setOutput(String id, String displayName, PortType elementType, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.SET)
      .description(description)
      .elementType(elementType)
      .typeDescriptor(TypeDescriptor.setOf(elementType))
      .build();
  }

  /// Creates a set input port with a generic element type (e.g., Set<T>).
  static PortDefinition genericSetInput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.SET)
      .description(description)
      .required(true)
      .typeDescriptor(TypeDescriptor.set(elementTypeDescriptor))
      .build();
  }

  /// Creates a set output port with a generic element type (e.g., Set<T>).
  static PortDefinition genericSetOutput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.SET)
      .description(description)
      .typeDescriptor(TypeDescriptor.set(elementTypeDescriptor))
      .build();
  }

  /// Creates a collection input port with a generic element type (e.g., Collection<T>).
  static PortDefinition genericCollectionInput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.COLLECTION)
      .description(description)
      .required(true)
      .typeDescriptor(TypeDescriptor.collection(elementTypeDescriptor))
      .build();
  }

  /// Creates a collection output port with a generic element type (e.g., Collection<T>).
  static PortDefinition genericCollectionOutput(String id, String displayName, TypeDescriptor elementTypeDescriptor, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(PortType.COLLECTION)
      .description(description)
      .typeDescriptor(TypeDescriptor.collection(elementTypeDescriptor))
      .build();
  }

  String id();

  String displayName();

  PortType type();

  @Value.Default
  default boolean required() {
    return false;
  }

  @Nullable
  String defaultValue();

  String description();

  @Nullable
  PortType elementType();

  /// Optional generic type descriptor for parameterized types.
  /// When present, provides richer type information than the flat type() field.
  /// For example, List<Bot> is expressed as Parameterized(LIST, [Simple(BOT)]).
  /// Type variables like T are resolved based on connections at edit time.
  @Nullable
  TypeDescriptor typeDescriptor();

  /// Returns the effective TypeDescriptor for this port.
  /// If typeDescriptor() is set, returns it. Otherwise, derives from type() and elementType().
  @Value.Lazy
  default TypeDescriptor effectiveTypeDescriptor() {
    var td = typeDescriptor();
    if (td != null) {
      return td;
    }
    // Derive from legacy fields
    if (type() == PortType.LIST && elementType() != null) {
      return TypeDescriptor.listOf(elementType());
    }
    if (type() == PortType.SET && elementType() != null) {
      return TypeDescriptor.setOf(elementType());
    }
    if (type() == PortType.COLLECTION && elementType() != null) {
      return TypeDescriptor.collectionOf(elementType());
    }
    return TypeDescriptor.simple(type());
  }

  /// Whether this input accepts multiple connections (Blender-style multi-input).
  /// When true, all connected values are collected into a list.
  /// NOTE: The reactive engine does not yet implement multi-input collection.
  @Value.Default
  default boolean multiInput() {
    return false;
  }

  /// Creates a multi-input port that accepts multiple connections.
  /// All connected values are collected into a list.
  /// @param id simple port name
  static PortDefinition multiInput(String id, String displayName, PortType type, String description) {
    return ImmutablePortDefinition.builder()
      .id(id)
      .displayName(displayName)
      .type(type)
      .description(description)
      .required(false)
      .multiInput(true)
      .build();
  }
}
