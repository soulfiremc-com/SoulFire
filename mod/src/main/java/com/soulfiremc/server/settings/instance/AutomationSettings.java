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
package com.soulfiremc.server.settings.instance;

import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.ComboProperty;
import com.soulfiremc.server.settings.property.ImmutableBooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableComboProperty;
import com.soulfiremc.server.settings.property.ImmutableIntProperty;
import com.soulfiremc.server.settings.property.BooleanProperty;
import com.soulfiremc.server.settings.property.ComboProperty.ComboOption;
import com.soulfiremc.server.settings.property.IntProperty;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AutomationSettings implements SettingsObject {
  private static final String NAMESPACE = "automation";

  public static final BooleanProperty<SettingsSource.Bot> ENABLED =
    ImmutableBooleanProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("enabled")
      .uiName("Automation Enabled")
      .description("Master switch for SoulFire-native automation on this bot")
      .defaultValue(true)
      .build();
  public static final ComboProperty<SettingsSource.Instance> PRESET =
    ImmutableComboProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("preset")
      .uiName("Automation Preset")
      .description("Named automation preset that captures the intended coordination style for this instance")
      .defaultValue(Preset.BALANCED_TEAM.name())
      .addOptions(presetOptions())
      .build();
  public static final BooleanProperty<SettingsSource.Instance> TEAM_COLLABORATION =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("team-collaboration")
      .uiName("Team Collaboration")
      .description("Allow bots in the same instance to share roles, claims, structure knowledge, and team-wide automation state. When disabled, each bot runs as an isolated solo automation.")
      .defaultValue(true)
      .build();
  public static final ComboProperty<SettingsSource.Instance> ROLE_POLICY =
    ImmutableComboProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("role-policy")
      .uiName("Role Policy")
      .description("How the automation coordinator assigns roles. Independent mode disables cross-bot orchestration even if team collaboration remains enabled.")
      .defaultValue(RolePolicy.STATIC_TEAM.name())
      .addOptions(rolePolicyOptions())
      .build();
  public static final BooleanProperty<SettingsSource.Instance> SHARED_STRUCTURE_INTEL =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("shared-structure-intel")
      .uiName("Shared Structure Intel")
      .description("Allow bots to reuse each other's observed structure hints, portal sightings, and eye-of-ender samples when team orchestration is active")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> SHARED_TARGET_CLAIMS =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("shared-target-claims")
      .uiName("Shared Target Claims")
      .description("Allow bots to reserve shared targets such as portal frames, crystals, and exploration cells so teammates avoid colliding on the same work")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> SHARED_END_ENTRY =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("shared-end-entry")
      .uiName("Shared End Entry")
      .description("Throttle End entry so the team does not send every bot through the portal at once")
      .defaultValue(true)
      .build();
  public static final IntProperty<SettingsSource.Instance> MAX_END_BOTS =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("max-end-bots")
      .uiName("Max End Bots")
      .description("Maximum number of bots that may be active in the End at the same time when shared End entry is enabled")
      .defaultValue(3)
      .minValue(1)
      .maxValue(32)
      .build();
  public static final BooleanProperty<SettingsSource.Bot> ALLOW_DEATH_RECOVERY =
    ImmutableBooleanProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-death-recovery")
      .uiName("Allow Death Recovery")
      .description("Attempt to recover dropped items and resume progression after the bot dies")
      .defaultValue(true)
      .build();
  public static final IntProperty<SettingsSource.Bot> MEMORY_SCAN_RADIUS =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("memory-scan-radius")
      .uiName("Memory Scan Radius")
      .description("Horizontal and vertical radius used for automation world-memory block scans")
      .defaultValue(48)
      .minValue(8)
      .maxValue(96)
      .build();
  public static final IntProperty<SettingsSource.Bot> MEMORY_SCAN_INTERVAL_TICKS =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("memory-scan-interval-ticks")
      .uiName("Memory Scan Interval")
      .description("How many ticks automation waits between full block-memory scans")
      .defaultValue(20)
      .minValue(1)
      .maxValue(200)
      .build();
  public static final IntProperty<SettingsSource.Bot> RETREAT_HEALTH_THRESHOLD =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("retreat-health-threshold")
      .uiName("Retreat Health Threshold")
      .description("Health level at or below which automation tries to disengage from nearby threats")
      .defaultValue(8)
      .minValue(1)
      .maxValue(20)
      .build();
  public static final IntProperty<SettingsSource.Bot> RETREAT_FOOD_THRESHOLD =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("retreat-food-threshold")
      .uiName("Eat Food Threshold")
      .description("Food level at or below which automation interrupts itself to eat if food is available")
      .defaultValue(12)
      .minValue(1)
      .maxValue(20)
      .build();

  private static ComboOption[] rolePolicyOptions() {
    return ComboProperty.optionsFromEnum(
      RolePolicy.values(),
      ComboProperty::capitalizeEnum,
      ignored -> null);
  }

  private static ComboOption[] presetOptions() {
    return ComboProperty.optionsFromEnum(
      Preset.values(),
      ComboProperty::capitalizeEnum,
      ignored -> null);
  }

  public enum RolePolicy {
    STATIC_TEAM,
    INDEPENDENT
  }

  public enum Preset {
    BALANCED_TEAM,
    INDEPENDENT_RUNNERS,
    CAUTIOUS_TEAM
  }
}
