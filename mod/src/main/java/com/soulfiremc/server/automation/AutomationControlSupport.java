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
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.settings.property.IntProperty;
import com.soulfiremc.server.util.structs.GsonInstance;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AutomationControlSupport {
  private static final Map<String, TargetQuotaOverride> TARGET_QUOTA_OVERRIDES = createTargetQuotaOverrides();

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

  public static TargetQuotaOverride targetQuotaOverride(String rawRequirementKey) {
    var normalizedKey = normalizeTargetQuotaKey(rawRequirementKey);
    var targetOverride = TARGET_QUOTA_OVERRIDES.get(normalizedKey);
    if (targetOverride == null) {
      throw new IllegalArgumentException("Unsupported automation quota target: " + rawRequirementKey);
    }

    return targetOverride;
  }

  public static String setTargetOverride(InstanceManager instance,
                                         String rawRequirementKey,
                                         int targetCount) {
    var targetOverride = targetQuotaOverride(rawRequirementKey);
    validateTargetOverride(targetOverride, targetCount);
    instance.updateInstanceSetting(targetOverride.property(), GsonInstance.GSON.toJsonTree(targetCount));
    return targetOverride.requirementKey();
  }

  public static Collection<TargetQuotaOverride> targetQuotaOverrides() {
    return TARGET_QUOTA_OVERRIDES.values();
  }

  public static void setBotIntSetting(InstanceManager instance,
                                      UUID botId,
                                      IntProperty<SettingsSource.Bot> property,
                                      int value) {
    validateBotIntSetting(property, value);
    instance.updateBotSetting(botId, property, GsonInstance.GSON.toJsonTree(value));
  }

  public static void validateBotIntSetting(IntProperty<SettingsSource.Bot> property, int value) {
    if (value < property.minValue() || value > property.maxValue()) {
      throw new IllegalArgumentException("%s must be between %d and %d"
        .formatted(property.uiName(), property.minValue(), property.maxValue()));
    }
  }

  private static void validateTargetOverride(TargetQuotaOverride targetOverride, int targetCount) {
    if (targetCount < targetOverride.property().minValue() || targetCount > targetOverride.property().maxValue()) {
      throw new IllegalArgumentException("%s must be between %d and %d"
        .formatted(targetOverride.property().uiName(), targetOverride.property().minValue(), targetOverride.property().maxValue()));
    }
  }

  private static String normalizeTargetQuotaKey(String rawRequirementKey) {
    return switch (rawRequirementKey.trim().toLowerCase(Locale.ROOT)) {
      case "rod", "rods", "blaze_rod", "blaze_rods", "item:minecraft:blaze_rod" -> AutomationRequirements.BLAZE_ROD;
      case "pearl", "pearls", "ender_pearl", "ender_pearls", "item:minecraft:ender_pearl" -> AutomationRequirements.ENDER_PEARL;
      case "eye", "eyes", "ender_eye", "ender_eyes", "item:minecraft:ender_eye" -> AutomationRequirements.ENDER_EYE;
      case "arrow", "arrows", "item:minecraft:arrow" -> AutomationRequirements.ARROW;
      case "bed", "beds", "any_bed", "group:any_bed" -> AutomationRequirements.ANY_BED;
      default -> rawRequirementKey.trim().toLowerCase(Locale.ROOT);
    };
  }

  private static Map<String, TargetQuotaOverride> createTargetQuotaOverrides() {
    var overrides = new LinkedHashMap<String, TargetQuotaOverride>();
    addTargetQuotaOverride(overrides, AutomationRequirements.BLAZE_ROD, AutomationSettings.TARGET_BLAZE_RODS);
    addTargetQuotaOverride(overrides, AutomationRequirements.ENDER_PEARL, AutomationSettings.TARGET_ENDER_PEARLS);
    addTargetQuotaOverride(overrides, AutomationRequirements.ENDER_EYE, AutomationSettings.TARGET_ENDER_EYES);
    addTargetQuotaOverride(overrides, AutomationRequirements.ARROW, AutomationSettings.TARGET_ARROWS);
    addTargetQuotaOverride(overrides, AutomationRequirements.ANY_BED, AutomationSettings.TARGET_BEDS);
    return Map.copyOf(overrides);
  }

  private static void addTargetQuotaOverride(Map<String, TargetQuotaOverride> overrides,
                                             String requirementKey,
                                             IntProperty<SettingsSource.Instance> property) {
    overrides.put(requirementKey, new TargetQuotaOverride(requirementKey, property));
  }

  public record TargetQuotaOverride(
    String requirementKey,
    IntProperty<SettingsSource.Instance> property
  ) {
  }
}
