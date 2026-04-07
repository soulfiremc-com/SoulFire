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
package com.soulfiremc.server.automation;

import org.junit.jupiter.api.Test;

import com.soulfiremc.server.settings.instance.AutomationSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AutomationControlSupportTest {
  @Test
  void usesConfiguredTargetWhenPresent() {
    assertEquals(24, AutomationControlSupport.resolveTargetOverride(24, 12));
  }

  @Test
  void fallsBackToDynamicTargetWhenOverrideDisabled() {
    assertEquals(12, AutomationControlSupport.resolveTargetOverride(0, 12));
  }

  @Test
  void clampsFallbackTargetToAtLeastOne() {
    assertEquals(1, AutomationControlSupport.resolveTargetOverride(0, 0));
  }

  @Test
  void mapsQuotaTargetAliasesToSupportedRequirementKeys() {
    assertEquals(
      AutomationRequirements.BLAZE_ROD,
      AutomationControlSupport.targetQuotaOverride("rod").requirementKey());
    assertEquals(
      AutomationRequirements.ANY_BED,
      AutomationControlSupport.targetQuotaOverride("bed").requirementKey());
  }

  @Test
  void rejectsUnsupportedQuotaTargets() {
    assertThrows(
      IllegalArgumentException.class,
      () -> AutomationControlSupport.targetQuotaOverride("diamond_pickaxe"));
  }

  @Test
  void validatesBotIntSettingRanges() {
    assertDoesNotThrow(() ->
      AutomationControlSupport.validateBotIntSetting(AutomationSettings.MEMORY_SCAN_RADIUS, 48));
    assertThrows(
      IllegalArgumentException.class,
      () -> AutomationControlSupport.validateBotIntSetting(AutomationSettings.MEMORY_SCAN_RADIUS, 7));
  }
}
