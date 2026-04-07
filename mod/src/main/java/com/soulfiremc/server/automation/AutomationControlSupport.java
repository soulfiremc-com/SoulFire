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

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.util.structs.GsonInstance;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

public final class AutomationControlSupport {
  private AutomationControlSupport() {
  }

  public static void applyPreset(InstanceManager instance,
                                 Collection<UUID> botIds,
                                 AutomationSettings.Preset preset) {
    applyInstancePresetSettings(instance, preset);
    for (var botId : botIds) {
      applyBotPresetSettings(instance, botId, preset);
    }
  }

  public static void applyCollaborationPreset(InstanceManager instance, boolean enabled) {
    applyInstancePresetSettings(instance, enabled
      ? AutomationSettings.Preset.BALANCED_TEAM
      : AutomationSettings.Preset.INDEPENDENT_RUNNERS);
  }

  public static void applyInstancePresetSettings(InstanceManager instance, AutomationSettings.Preset preset) {
    instance.updateInstanceSetting(AutomationSettings.PRESET, GsonInstance.GSON.toJsonTree(preset.name()));
    instance.updateInstanceSetting(AutomationSettings.OBJECTIVE_OVERRIDE, GsonInstance.GSON.toJsonTree(AutomationSettings.ObjectiveOverride.AUTO.name()));
    instance.updateInstanceSetting(AutomationSettings.TARGET_BLAZE_RODS, GsonInstance.GSON.toJsonTree(0));
    instance.updateInstanceSetting(AutomationSettings.TARGET_ENDER_PEARLS, GsonInstance.GSON.toJsonTree(0));
    instance.updateInstanceSetting(AutomationSettings.TARGET_ENDER_EYES, GsonInstance.GSON.toJsonTree(0));
    instance.updateInstanceSetting(AutomationSettings.TARGET_ARROWS, GsonInstance.GSON.toJsonTree(0));
    instance.updateInstanceSetting(AutomationSettings.TARGET_BEDS, GsonInstance.GSON.toJsonTree(0));
    switch (preset) {
      case BALANCED_TEAM -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.STATIC_TEAM.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_STRUCTURE_INTEL, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.SHARED_TARGET_CLAIMS, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(3));
      }
      case INDEPENDENT_RUNNERS -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.INDEPENDENT.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_STRUCTURE_INTEL, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.SHARED_TARGET_CLAIMS, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(10));
      }
      case CAUTIOUS_TEAM -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.STATIC_TEAM.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_STRUCTURE_INTEL, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.SHARED_TARGET_CLAIMS, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(2));
      }
    }
  }

  public static void applyBotPresetSettings(InstanceManager instance,
                                            UUID botId,
                                            AutomationSettings.Preset preset) {
    instance.updateBotSetting(botId, AutomationSettings.ENABLED, GsonInstance.GSON.toJsonTree(true));
    instance.updateBotSetting(botId, AutomationSettings.ROLE_OVERRIDE, GsonInstance.GSON.toJsonTree(AutomationSettings.RoleOverride.AUTO.name()));
    switch (preset) {
      case BALANCED_TEAM -> {
        instance.updateBotSetting(botId, AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(8));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(12));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(48));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(20));
      }
      case INDEPENDENT_RUNNERS -> {
        instance.updateBotSetting(botId, AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(7));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(10));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(40));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(24));
      }
      case CAUTIOUS_TEAM -> {
        instance.updateBotSetting(botId, AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(12));
        instance.updateBotSetting(botId, AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(14));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(56));
        instance.updateBotSetting(botId, AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(16));
      }
    }
  }

  public static String formatEnumId(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static int resolveTargetOverride(int configuredTarget, int dynamicTarget) {
    if (configuredTarget > 0) {
      return configuredTarget;
    }

    return Math.max(1, dynamicTarget);
  }
}
