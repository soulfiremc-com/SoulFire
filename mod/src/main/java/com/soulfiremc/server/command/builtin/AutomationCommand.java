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
package com.soulfiremc.server.command.builtin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.automation.AutomationRequirements;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.soulfiremc.server.command.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashSet;
import java.util.Locale;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class AutomationCommand {
  private AutomationCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var root = literal("automation");
    root.then(literal("beat")
      .executes(help(
        "Starts the native automation progression controller",
        c -> forEveryBot(c, bot -> {
          if (bot.automation().startBeatMinecraft()) {
            c.getSource().source().sendInfo("Started automation beat mode for " + bot.accountName());
            bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_START, "mode=beat bot=" + bot.accountName());
          } else {
            c.getSource().source().sendWarn("Automation is disabled for " + bot.accountName());
          }
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("get")
      .then(argument("target", StringArgumentType.word())
        .suggests(TargetSuggestionProvider.INSTANCE)
        .then(argument("count", IntegerArgumentType.integer(1))
          .executes(help(
            "Makes selected bots acquire an item or automation group",
            c -> {
              var target = StringArgumentType.getString(c, "target");
              var count = IntegerArgumentType.getInteger(c, "count");
              return forEveryBot(c, bot -> {
                if (bot.automation().startAcquire(target, count)) {
                  c.getSource().source().sendInfo("Started automation get %s x%d for %s".formatted(target, count, bot.accountName()));
                  bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_START, "mode=get target=%s count=%d bot=%s".formatted(target, count, bot.accountName()));
                } else {
                  c.getSource().source().sendWarn("Automation is disabled for " + bot.accountName());
                }
                return Command.SINGLE_SUCCESS;
              });
            })))));
    root.then(literal("pause")
      .executes(help(
        "Pauses automation for selected bots without clearing their current goal",
        c -> forEveryBot(c, bot -> {
          if (bot.automation().pause()) {
            c.getSource().source().sendInfo("Paused automation for " + bot.accountName());
            bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_PAUSE, "bot=" + bot.accountName());
          } else {
            c.getSource().source().sendWarn("Automation was not running for " + bot.accountName());
          }
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("resume")
      .executes(help(
        "Resumes automation for selected paused bots",
        c -> forEveryBot(c, bot -> {
          if (bot.automation().resume()) {
            c.getSource().source().sendInfo("Resumed automation for " + bot.accountName());
            bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_RESUME, "bot=" + bot.accountName());
          } else {
            c.getSource().source().sendWarn("Automation was not paused for " + bot.accountName());
          }
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("preset")
      .then(argument("preset", StringArgumentType.word())
        .suggests(PresetSuggestionProvider.INSTANCE)
        .executes(help(
          "Applies a named automation preset to the visible instances and selected bots",
          c -> {
            var preset = parsePreset(StringArgumentType.getString(c, "preset"));
            applyPreset(c, preset);
            return Command.SINGLE_SUCCESS;
          }))));
    root.then(literal("collaboration")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Toggles team orchestration for visible instances. When disabled, bots run as isolated solo automations.",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            var preset = enabled ? AutomationSettings.Preset.BALANCED_TEAM : AutomationSettings.Preset.INDEPENDENT_RUNNERS;
            return forEveryInstance(c, instance -> {
              applyInstancePresetSettings(instance, preset);
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "team-collaboration=%s preset=%s".formatted(enabled, formatEnumId(preset)));
              c.getSource().source().sendInfo("%s: team collaboration %s (%s)".formatted(
                instance.friendlyNameCache().get(),
                enabled ? "enabled" : "disabled",
                formatEnumId(preset)));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("rolepolicy")
      .then(argument("policy", StringArgumentType.word())
        .suggests(RolePolicySuggestionProvider.INSTANCE)
        .executes(help(
          "Sets the automation role policy for visible instances",
          c -> {
            var rolePolicy = parseRolePolicy(StringArgumentType.getString(c, "policy"));
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(rolePolicy.name()));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "role-policy=" + formatEnumId(rolePolicy));
              c.getSource().source().sendInfo("%s: automation role policy set to %s".formatted(
                instance.friendlyNameCache().get(),
                formatEnumId(rolePolicy)));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("sharedendentry")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Toggles shared End-entry throttling for visible instances",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(enabled));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-end-entry=" + enabled);
              c.getSource().source().sendInfo("%s: shared End entry %s".formatted(
                instance.friendlyNameCache().get(),
                enabled ? "enabled" : "disabled"));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("maxendbots")
      .then(argument("count", IntegerArgumentType.integer(1, 32))
        .executes(help(
          "Sets the maximum number of bots that may be active in the End at once",
          c -> {
            var count = IntegerArgumentType.getInteger(c, "count");
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(count));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "max-end-bots=" + count);
              c.getSource().source().sendInfo("%s: max End bots set to %d".formatted(
                instance.friendlyNameCache().get(),
                count));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("enabled")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Enables or disables automation for the selected bots",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            return forEveryBot(c, bot -> {
              bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ENABLED, GsonInstance.GSON.toJsonTree(enabled));
              if (!enabled) {
                bot.automation().stop();
              }
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "enabled=%s bot=%s".formatted(enabled, bot.accountName()));
              c.getSource().source().sendInfo("%s: automation %s".formatted(bot.accountName(), enabled ? "enabled" : "disabled"));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("deathrecovery")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Enables or disables death-recovery behavior for the selected bots",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            return forEveryBot(c, bot -> {
              bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(enabled));
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "allow-death-recovery=%s bot=%s".formatted(enabled, bot.accountName()));
              c.getSource().source().sendInfo("%s: death recovery %s".formatted(bot.accountName(), enabled ? "enabled" : "disabled"));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("status")
      .executes(help(
        "Shows the current automation status for selected bots",
        c -> forEveryBot(c, bot -> {
          c.getSource().source().sendInfo(bot.accountName() + ": " + bot.automation().status());
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("teamstatus")
      .executes(help(
        "Shows shared automation coordinator status for the current instance",
        c -> {
          for (var instance : c.getSource().getVisibleInstances()) {
            var summary = instance.automationCoordinator().teamSummary();
            var statuses = instance.automationCoordinator().botStatuses();
            if (statuses.isEmpty()) {
              c.getSource().source().sendInfo(instance.friendlyNameCache().get() + ": no shared automation state recorded yet");
              continue;
            }

            c.getSource().source().sendInfo(instance.friendlyNameCache().get()
              + ": preset=%s collaboration=%s rolePolicy=%s sharedEndEntry=%s maxEndBots=%d objective=%s bots=%d blaze=%d/%d pearls=%d/%d eyes=%d/%d arrows=%d/%d beds=%d/%d".formatted(
              formatEnumId(summary.preset()),
              summary.collaborationEnabled() ? "on" : "off",
              formatEnumId(summary.rolePolicy()),
              summary.sharedEndEntry() ? "on" : "off",
              summary.maxEndBots(),
              summary.objective().name().toLowerCase(),
              summary.activeBots(),
              summary.blazeRods(),
              summary.targetBlazeRods(),
              summary.enderPearls(),
              summary.targetEnderPearls(),
              summary.enderEyes(),
              summary.targetEnderEyes(),
              summary.arrows(),
              summary.targetArrows(),
              summary.beds(),
              summary.targetBeds()));
            for (var status : statuses) {
              var dimension = status.dimension() == null ? "unknown" : status.dimension().identifier().toString();
              var position = status.position() == null
                ? "unknown"
                : "%d,%d,%d".formatted((int) Math.floor(status.position().x), (int) Math.floor(status.position().y), (int) Math.floor(status.position().z));
              var phase = status.phase() == null ? "-" : status.phase().toLowerCase();
              var recovery = status.lastRecoveryReason() == null ? "-" : status.lastRecoveryReason();
              c.getSource().source().sendInfo("  %s: role=%s, status=%s, phase=%s, dim=%s, pos=%s, deaths=%d, timeouts=%d, recoveries=%d, last=%s".formatted(
                status.accountName(),
                status.role().name().toLowerCase(),
                status.status(),
                phase,
                dimension,
                position,
                status.deathCount(),
                status.timeoutCount(),
                status.recoveryCount(),
                recovery));
            }
          }
          return Command.SINGLE_SUCCESS;
        })));
    root.then(literal("stop")
      .executes(help(
        "Stops automation for selected bots",
        c -> forEveryBot(c, bot -> {
          bot.automation().stop();
          bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_STOP, "bot=" + bot.accountName());
          c.getSource().source().sendInfo("Stopped automation for " + bot.accountName());
          return Command.SINGLE_SUCCESS;
        }))));
    dispatcher.register(root);
  }

  private static void applyPreset(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                  AutomationSettings.Preset preset) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    forEveryInstance(context, instance -> {
      applyInstancePresetSettings(instance, preset);
      instance.addAuditLog(context.getSource().source(), AuditLogType.AUTOMATION_APPLY_PRESET, "preset=" + formatEnumId(preset));
      context.getSource().source().sendInfo("%s: applied automation preset %s".formatted(
        instance.friendlyNameCache().get(),
        formatEnumId(preset)));
      return Command.SINGLE_SUCCESS;
    });

    forEveryBot(context, bot -> {
      bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ENABLED, GsonInstance.GSON.toJsonTree(true));
      switch (preset) {
        case BALANCED_TEAM -> {
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(8));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(12));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(48));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(20));
        }
        case INDEPENDENT_RUNNERS -> {
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(7));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(10));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(40));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(24));
        }
        case CAUTIOUS_TEAM -> {
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ALLOW_DEATH_RECOVERY, GsonInstance.GSON.toJsonTree(true));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_HEALTH_THRESHOLD, GsonInstance.GSON.toJsonTree(12));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.RETREAT_FOOD_THRESHOLD, GsonInstance.GSON.toJsonTree(14));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_RADIUS, GsonInstance.GSON.toJsonTree(56));
          bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, GsonInstance.GSON.toJsonTree(16));
        }
      }
      context.getSource().source().sendInfo("%s: applied automation bot profile for preset %s".formatted(
        bot.accountName(),
        formatEnumId(preset)));
      return Command.SINGLE_SUCCESS;
    });
  }

  private static void applyInstancePresetSettings(InstanceManager instance, AutomationSettings.Preset preset) {
    instance.updateInstanceSetting(AutomationSettings.PRESET, GsonInstance.GSON.toJsonTree(preset.name()));
    switch (preset) {
      case BALANCED_TEAM -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.STATIC_TEAM.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(3));
      }
      case INDEPENDENT_RUNNERS -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.INDEPENDENT.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(false));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(10));
      }
      case CAUTIOUS_TEAM -> {
        instance.updateInstanceSetting(AutomationSettings.TEAM_COLLABORATION, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.ROLE_POLICY, GsonInstance.GSON.toJsonTree(AutomationSettings.RolePolicy.STATIC_TEAM.name()));
        instance.updateInstanceSetting(AutomationSettings.SHARED_END_ENTRY, GsonInstance.GSON.toJsonTree(true));
        instance.updateInstanceSetting(AutomationSettings.MAX_END_BOTS, GsonInstance.GSON.toJsonTree(2));
      }
    }
  }

  private static AutomationSettings.Preset parsePreset(String raw) {
    return AutomationSettings.Preset.valueOf(normalizeEnumId(raw));
  }

  private static AutomationSettings.RolePolicy parseRolePolicy(String raw) {
    return AutomationSettings.RolePolicy.valueOf(normalizeEnumId(raw));
  }

  private static String normalizeEnumId(String raw) {
    return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static String formatEnumId(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static final class TargetSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final TargetSuggestionProvider INSTANCE = new TargetSuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      var suggestions = new LinkedHashSet<String>();
      suggestions.addAll(AutomationRequirements.aliases());
      BuiltInRegistries.ITEM.listElementIds()
        .map(ResourceKey::identifier)
        .map(id -> id.getPath())
        .forEach(suggestions::add);
      suggestions.forEach(builder::suggest);
      return builder.buildFuture();
    }
  }

  private static final class PresetSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final PresetSuggestionProvider INSTANCE = new PresetSuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      for (var preset : AutomationSettings.Preset.values()) {
        builder.suggest(formatEnumId(preset));
      }
      return builder.buildFuture();
    }
  }

  private static final class RolePolicySuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final RolePolicySuggestionProvider INSTANCE = new RolePolicySuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      for (var rolePolicy : AutomationSettings.RolePolicy.values()) {
        builder.suggest(formatEnumId(rolePolicy));
      }
      return builder.buildFuture();
    }
  }
}
