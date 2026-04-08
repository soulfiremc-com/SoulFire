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

/// The data type of a node port.
/// Used for connection validation and UI rendering.
public enum PortType {
  /// Any type - accepts all values.
  ANY,
  /// Numeric value (integer or floating point).
  NUMBER,
  /// Text string value.
  STRING,
  /// Boolean true/false value.
  BOOLEAN,
  /// 3D vector with x, y, z components.
  VECTOR3,
  /// Reference to a bot connection.
  BOT,
  /// List/array of values.
  LIST,
  /// Execution flow port (not data).
  EXEC,
  /// Block type identifier.
  BLOCK,
  /// Entity reference.
  ENTITY,
  /// Item stack reference.
  ITEM,
  /// Key-value map of entries.
  MAP,
  /// Set of unique values with stable insertion order.
  SET,
  /// Abstract collection supertype for list/set operations.
  COLLECTION
}
