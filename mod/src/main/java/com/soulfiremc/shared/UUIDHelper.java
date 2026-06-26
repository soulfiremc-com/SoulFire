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
package com.soulfiremc.shared;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Optional;
import java.util.UUID;

public final class UUIDHelper {
  private UUIDHelper() {}

  public static Optional<UUID> tryParseUniqueId(@Nullable String str) {
    if (str == null || str.isBlank()) {
      return Optional.empty();
    }

    try {
      return Optional.of(UUID.fromString(str));
    } catch (IllegalArgumentException _) {
      // If we have a non-dashed UUID, we can try to convert it to dashed.
      if (str.length() == 32) {
        try {
          return Optional.of(convertToDashed(str));
        } catch (IllegalArgumentException _) {
          return Optional.empty();
        }
      }

      return Optional.empty();
    }
  }

  public static UUID convertToDashed(String noDashes) {
    var idBuff = new StringBuilder(noDashes);
    idBuff.insert(20, '-');
    idBuff.insert(16, '-');
    idBuff.insert(12, '-');
    idBuff.insert(8, '-');
    return UUID.fromString(idBuff.toString());
  }

  public static @Nullable UUID tryParseUniqueIdOrNull(@Nullable String str) {
    return tryParseUniqueId(str).orElse(null);
  }
}
