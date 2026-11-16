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
package com.soulfiremc.server.bot;

import org.checkerframework.checker.nullness.qual.Nullable;

/// Turns the health reported by successive `ClientboundSetHealthPacket`s into damage.
///
/// It compares with the previous packet, not with the player's current health: the health also
/// comes as entity data, sometimes before this packet, so the player can already have the new value.
public final class HealthDamageTracker {
  private float lastHealth = Float.NaN;
  private @Nullable String pendingDamageType;

  /// Records the damage type of a `ClientboundDamageEventPacket` for the player. The server sends it
  /// before the health packet that reports the same damage.
  public void recordDamageType(String damageType) {
    pendingDamageType = damageType;
  }

  /// Records the health of a packet.
  ///
  /// @return the damage since the previous packet, or null if health didn't drop or this is the first packet
  public @Nullable Damage update(float health) {
    var previousHealth = lastHealth;
    var damageType = pendingDamageType;
    lastHealth = health;
    pendingDamageType = null;
    return health < previousHealth
      ? new Damage(previousHealth, health, previousHealth - health, damageType)
      : null;
  }

  /// @param damageType the damage type id, such as `minecraft:freeze`, or null when no damage event came first
  public record Damage(float previousHealth, float newHealth, float amount, @Nullable String damageType) {}
}
