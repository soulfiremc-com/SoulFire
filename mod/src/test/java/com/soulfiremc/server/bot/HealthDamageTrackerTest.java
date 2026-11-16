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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HealthDamageTrackerTest {
  @Test
  void firstPacketOnlyRecordsHealth() {
    var tracker = new HealthDamageTracker();

    assertNull(tracker.update(15));
    assertEquals(new HealthDamageTracker.Damage(15, 13, 2, null), tracker.update(13));
  }

  @Test
  void reportsEachDropOnceAgainstThePreviousPacket() {
    var tracker = new HealthDamageTracker();
    tracker.update(20);

    assertEquals(new HealthDamageTracker.Damage(20, 18, 2, null), tracker.update(18));
    // Same health again (a food change): no damage
    assertNull(tracker.update(18));
    // Healing: no damage, and the next drop starts from the healed health
    assertNull(tracker.update(19));
    assertEquals(new HealthDamageTracker.Damage(19, 18, 1, null), tracker.update(18));
  }

  @Test
  void attachesTheDamageTypeReportedBeforeTheDrop() {
    var tracker = new HealthDamageTracker();
    tracker.update(20);

    tracker.recordDamageType("minecraft:freeze");
    assertEquals(new HealthDamageTracker.Damage(20, 19, 1, "minecraft:freeze"), tracker.update(19));
    // The type belongs to one drop only
    assertEquals(new HealthDamageTracker.Damage(19, 18, 1, null), tracker.update(18));
  }

  @Test
  void dropsADamageTypeWhenHealthDidNotFall() {
    var tracker = new HealthDamageTracker();
    tracker.update(20);

    // Absorption can soak damage, so the health packet shows no drop
    tracker.recordDamageType("minecraft:fall");
    assertNull(tracker.update(20));
    assertEquals(new HealthDamageTracker.Damage(20, 17, 3, null), tracker.update(17));
  }

  @Test
  void deathAndRespawn() {
    var tracker = new HealthDamageTracker();
    tracker.update(4);

    assertEquals(new HealthDamageTracker.Damage(4, 0, 4, null), tracker.update(0));
    assertNull(tracker.update(20));
  }
}
