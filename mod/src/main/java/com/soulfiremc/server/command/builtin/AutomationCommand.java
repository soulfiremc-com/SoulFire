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
import com.soulfiremc.server.automation.AutomationControlSupport;
import com.soulfiremc.server.automation.AutomationRequirements;
import com.soulfiremc.server.automation.AutomationWorldMemory;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.database.AuditLogType;
import com.soulfiremc.server.settings.instance.AutomationSettings;
import com.soulfiremc.server.util.structs.GsonInstance;
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
            return forEveryInstance(c, instance -> {
              AutomationControlSupport.applyCollaborationPreset(instance, enabled);
              var preset = enabled ? AutomationSettings.Preset.BALANCED_TEAM : AutomationSettings.Preset.INDEPENDENT_RUNNERS;
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "team-collaboration=%s preset=%s".formatted(enabled, AutomationControlSupport.formatEnumId(preset)));
              c.getSource().source().sendInfo("%s: team collaboration %s (%s)".formatted(
                instance.friendlyNameCache().get(),
                enabled ? "enabled" : "disabled",
                AutomationControlSupport.formatEnumId(preset)));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("sharedstructures")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Toggles whether bots share portal hints, structure observations, and eye-of-ender intelligence across the instance",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.SHARED_STRUCTURE_INTEL, GsonInstance.GSON.toJsonTree(enabled));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-structure-intel=" + enabled);
              c.getSource().source().sendInfo("%s: shared structure intel %s".formatted(
                instance.friendlyNameCache().get(),
                enabled ? "enabled" : "disabled"));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("sharedclaims")
      .then(argument("enabled", BoolArgumentType.bool())
        .executes(help(
          "Toggles whether bots reserve shared targets like crystals, portal frames, and exploration cells across the instance",
          c -> {
            var enabled = BoolArgumentType.getBool(c, "enabled");
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.SHARED_TARGET_CLAIMS, GsonInstance.GSON.toJsonTree(enabled));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "shared-target-claims=" + enabled);
              c.getSource().source().sendInfo("%s: shared target claims %s".formatted(
                instance.friendlyNameCache().get(),
                enabled ? "enabled" : "disabled"));
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
                AutomationControlSupport.formatEnumId(rolePolicy)));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("objective")
      .then(argument("objective", StringArgumentType.word())
        .suggests(ObjectiveOverrideSuggestionProvider.INSTANCE)
        .executes(help(
          "Forces or clears the shared automation objective for the visible instances",
          c -> {
            var objectiveOverride = parseObjectiveOverride(StringArgumentType.getString(c, "objective"));
            return forEveryInstance(c, instance -> {
              instance.updateInstanceSetting(AutomationSettings.OBJECTIVE_OVERRIDE, GsonInstance.GSON.toJsonTree(objectiveOverride.name()));
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "objective-override=" + formatEnumId(objectiveOverride));
              c.getSource().source().sendInfo("%s: automation objective override set to %s".formatted(
                instance.friendlyNameCache().get(),
                AutomationControlSupport.formatEnumId(objectiveOverride)));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("role")
      .then(argument("role", StringArgumentType.word())
        .suggests(RoleOverrideSuggestionProvider.INSTANCE)
        .executes(help(
          "Forces or clears the automation role override for the selected bots",
          c -> {
            var roleOverride = parseRoleOverride(StringArgumentType.getString(c, "role"));
            return forEveryBot(c, bot -> {
              bot.instanceManager().updateBotSetting(bot.accountProfileId(), AutomationSettings.ROLE_OVERRIDE, GsonInstance.GSON.toJsonTree(roleOverride.name()));
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "role-override=%s bot=%s".formatted(formatEnumId(roleOverride), bot.accountName()));
              c.getSource().source().sendInfo("%s: automation role override set to %s".formatted(
                bot.accountName(),
                AutomationControlSupport.formatEnumId(roleOverride)));
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
    root.then(literal("quota")
      .then(argument("target", StringArgumentType.word())
        .suggests(QuotaTargetSuggestionProvider.INSTANCE)
        .then(argument("count", IntegerArgumentType.integer(0))
          .executes(help(
            "Overrides one shared automation quota such as blaze rods, pearls, eyes, arrows, or beds. Use 0 to restore automatic team-size-based behavior.",
            c -> {
              var target = StringArgumentType.getString(c, "target");
              var count = IntegerArgumentType.getInteger(c, "count");
              return forEveryInstance(c, instance -> {
                var normalizedRequirementKey = AutomationControlSupport.setTargetOverride(instance, target, count);
                instance.addAuditLog(
                  c.getSource().source(),
                  AuditLogType.AUTOMATION_UPDATE_SETTINGS,
                  "quota-override=%s target-count=%d".formatted(normalizedRequirementKey, count));
                c.getSource().source().sendInfo("%s: automation quota %s set to %d%s".formatted(
                  instance.friendlyNameCache().get(),
                  AutomationRequirements.describe(normalizedRequirementKey),
                  count,
                  count == 0 ? " (auto)" : ""));
                return Command.SINGLE_SUCCESS;
              });
            })))));
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
    root.then(literal("memoryscanradius")
      .then(argument("radius", IntegerArgumentType.integer(8, 96))
        .executes(help(
          "Sets the automation memory scan radius for the selected bots",
          c -> {
            var radius = IntegerArgumentType.getInteger(c, "radius");
            return forEveryBot(c, bot -> {
              AutomationControlSupport.setBotIntSetting(bot.instanceManager(), bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_RADIUS, radius);
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "memory-scan-radius=%d bot=%s".formatted(radius, bot.accountName()));
              c.getSource().source().sendInfo("%s: memory scan radius set to %d".formatted(bot.accountName(), radius));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("memoryscaninterval")
      .then(argument("ticks", IntegerArgumentType.integer(1, 200))
        .executes(help(
          "Sets the automation memory scan interval for the selected bots",
          c -> {
            var ticks = IntegerArgumentType.getInteger(c, "ticks");
            return forEveryBot(c, bot -> {
              AutomationControlSupport.setBotIntSetting(bot.instanceManager(), bot.accountProfileId(), AutomationSettings.MEMORY_SCAN_INTERVAL_TICKS, ticks);
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "memory-scan-interval-ticks=%d bot=%s".formatted(ticks, bot.accountName()));
              c.getSource().source().sendInfo("%s: memory scan interval set to %d ticks".formatted(bot.accountName(), ticks));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("retreathealth")
      .then(argument("health", IntegerArgumentType.integer(1, 20))
        .executes(help(
          "Sets the automation retreat health threshold for the selected bots",
          c -> {
            var health = IntegerArgumentType.getInteger(c, "health");
            return forEveryBot(c, bot -> {
              AutomationControlSupport.setBotIntSetting(bot.instanceManager(), bot.accountProfileId(), AutomationSettings.RETREAT_HEALTH_THRESHOLD, health);
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "retreat-health-threshold=%d bot=%s".formatted(health, bot.accountName()));
              c.getSource().source().sendInfo("%s: retreat health threshold set to %d".formatted(bot.accountName(), health));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("retreatfood")
      .then(argument("food", IntegerArgumentType.integer(1, 20))
        .executes(help(
          "Sets the automation eat-food threshold for the selected bots",
          c -> {
            var food = IntegerArgumentType.getInteger(c, "food");
            return forEveryBot(c, bot -> {
              AutomationControlSupport.setBotIntSetting(bot.instanceManager(), bot.accountProfileId(), AutomationSettings.RETREAT_FOOD_THRESHOLD, food);
              bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_UPDATE_SETTINGS, "retreat-food-threshold=%d bot=%s".formatted(food, bot.accountName()));
              c.getSource().source().sendInfo("%s: retreat food threshold set to %d".formatted(bot.accountName(), food));
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
    root.then(literal("queue")
      .executes(help(
        "Shows the current automation requirement queue for selected bots",
        c -> forEveryBot(c, bot -> {
          var snapshot = bot.automation().snapshot();
          if (snapshot.queuedRequirements().isEmpty()) {
            c.getSource().source().sendInfo(bot.accountName() + ": queue empty");
            return Command.SINGLE_SUCCESS;
          }

          var queueDescription = snapshot.queuedRequirements().stream()
            .map(requirement -> "%s x%d (%s)".formatted(
              AutomationRequirements.describe(requirement.requirementKey()),
              requirement.count(),
              requirement.reason()))
            .toList();
          c.getSource().source().sendInfo(bot.accountName() + ": " + String.join(" -> ", queueDescription));
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("memorystatus")
      .executes(help(
        "Shows a compact summary of remembered automation world state for selected bots",
        c -> showMemoryStatus(c, 5)))
      .then(argument("maxEntries", IntegerArgumentType.integer(1, 20))
        .executes(help(
          "Shows remembered automation world state for selected bots with a configurable entry cap",
          c -> showMemoryStatus(c, IntegerArgumentType.getInteger(c, "maxEntries"))))));
    root.then(literal("resetmemory")
      .executes(help(
        "Clears remembered automation world state for selected bots and forces replanning",
        c -> forEveryBot(c, bot -> {
          resetMemory(bot);
          bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_RESET_MEMORY, "bot=" + bot.accountName());
          c.getSource().source().sendInfo("Reset automation memory for " + bot.accountName());
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
              + ": preset=%s collaboration=%s rolePolicy=%s objectiveOverride=%s sharedStructureIntel=%s sharedClaims=%s sharedEndEntry=%s maxEndBots=%d objective=%s bots=%d blaze=%d/%d pearls=%d/%d eyes=%d/%d arrows=%d/%d beds=%d/%d".formatted(
              formatEnumId(summary.preset()),
              summary.collaborationEnabled() ? "on" : "off",
              AutomationControlSupport.formatEnumId(summary.rolePolicy()),
              summary.objectiveOverride() == null ? "auto" : formatEnumId(summary.objectiveOverride()),
              summary.sharedStructureIntel() ? "on" : "off",
              summary.sharedTargetClaims() ? "on" : "off",
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
              var role = status.role().name().toLowerCase(Locale.ROOT) + (status.roleOverride() == null ? "" : " (forced)");
              c.getSource().source().sendInfo("  %s: role=%s, status=%s, phase=%s, dim=%s, pos=%s, deaths=%d, timeouts=%d, recoveries=%d, last=%s".formatted(
                status.accountName(),
                role,
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
    root.then(literal("coordinationstatus")
      .executes(help(
        "Shows shared automation coordination state, including shared claims, structure hints, and eye samples",
        c -> showCoordinationStatus(c, 8)))
      .then(argument("maxEntries", IntegerArgumentType.integer(1, 32))
        .executes(help(
          "Shows shared automation coordination state with a configurable entry cap per category",
          c -> showCoordinationStatus(c, IntegerArgumentType.getInteger(c, "maxEntries"))))));
    root.then(literal("releaseclaim")
      .then(argument("key", StringArgumentType.greedyString())
        .executes(help(
          "Releases one shared automation claim by exact key for the visible instances",
          c -> {
            var key = StringArgumentType.getString(c, "key");
            return forEveryInstance(c, instance -> {
              var released = instance.automationCoordinator().releaseClaim(key);
              instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_RELEASE_CLAIMS,
                "claim-key=%s released=%s".formatted(key, released));
              c.getSource().source().sendInfo("%s: claim %s %s".formatted(
                instance.friendlyNameCache().get(),
                key,
                released ? "released" : "not found"));
              return Command.SINGLE_SUCCESS;
            });
          }))));
    root.then(literal("releasebotclaims")
      .executes(help(
        "Releases shared automation claims owned by the selected bots",
        c -> forEveryBot(c, bot -> {
          var released = bot.instanceManager().automationCoordinator().releaseClaimsOwnedBy(bot.accountProfileId());
          bot.instanceManager().addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_RELEASE_CLAIMS,
            "bot=%s released=%d".formatted(bot.accountName(), released));
          c.getSource().source().sendInfo("%s: released %d automation claims".formatted(bot.accountName(), released));
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("resetcoordination")
      .executes(help(
        "Clears shared automation claims, shared structure intelligence, and eye samples for the visible instances",
        c -> forEveryInstance(c, instance -> {
          instance.automationCoordinator().resetCoordinationState();
          instance.addAuditLog(c.getSource().source(), AuditLogType.AUTOMATION_RESET_COORDINATION, "instance=" + instance.id());
          c.getSource().source().sendInfo("Reset automation coordination state for " + instance.friendlyNameCache().get());
          return Command.SINGLE_SUCCESS;
        }))));
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
      AutomationControlSupport.applyInstancePresetSettings(instance, preset);
      instance.addAuditLog(context.getSource().source(), AuditLogType.AUTOMATION_APPLY_PRESET, "preset=" + AutomationControlSupport.formatEnumId(preset));
      context.getSource().source().sendInfo("%s: applied automation preset %s".formatted(
        instance.friendlyNameCache().get(),
        AutomationControlSupport.formatEnumId(preset)));
      return Command.SINGLE_SUCCESS;
    });

    forEveryBot(context, bot -> {
      AutomationControlSupport.applyBotPresetSettings(bot.instanceManager(), bot.accountProfileId(), preset);
      context.getSource().source().sendInfo("%s: applied automation bot profile for preset %s".formatted(
        bot.accountName(),
        AutomationControlSupport.formatEnumId(preset)));
      return Command.SINGLE_SUCCESS;
    });
  }

  private static int showMemoryStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                      int maxEntries) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    return forEveryBot(context, bot -> {
      var memory = readMemorySnapshot(bot, maxEntries);
      context.getSource().source().sendInfo("%s: ticks=%d blocks=%d containers=%d entities=%d dropped=%d unreachable=%d".formatted(
        bot.accountName(),
        memory.ticks(),
        memory.rememberedBlockCount(),
        memory.rememberedContainerCount(),
        memory.rememberedEntityCount(),
        memory.rememberedDroppedItemCount(),
        memory.unreachablePositionCount()));

      if (!memory.blocks().isEmpty()) {
        context.getSource().source().sendInfo("  blocks: " + memory.blocks().stream()
          .map(block -> "%s@%d,%d,%d".formatted(
            block.state().getBlock().getName().getString(),
            block.pos().getX(),
            block.pos().getY(),
            block.pos().getZ()))
          .toList());
      }
      if (!memory.containers().isEmpty()) {
        context.getSource().source().sendInfo("  containers: " + memory.containers().stream()
          .map(container -> "%s@%d,%d,%d inspected=%s items=%d".formatted(
            container.state().getBlock().getName().getString(),
            container.pos().getX(),
            container.pos().getY(),
            container.pos().getZ(),
            container.inspected() ? "yes" : "no",
            container.totalItemCount()))
          .toList());
      }
      if (!memory.entities().isEmpty()) {
        context.getSource().source().sendInfo("  entities: " + memory.entities().stream()
          .map(entity -> "%s@%d,%d,%d".formatted(
            entity.type().toShortString(),
            (int) Math.floor(entity.position().x),
            (int) Math.floor(entity.position().y),
            (int) Math.floor(entity.position().z)))
          .toList());
      }
      if (!memory.droppedItems().isEmpty()) {
        context.getSource().source().sendInfo("  dropped: " + memory.droppedItems().stream()
          .map(item -> "%s x%d@%d,%d,%d".formatted(
            item.stack().getHoverName().getString(),
            item.stack().getCount(),
            (int) Math.floor(item.position().x),
            (int) Math.floor(item.position().y),
            (int) Math.floor(item.position().z)))
          .toList());
      }
      if (!memory.unreachablePositions().isEmpty()) {
        context.getSource().source().sendInfo("  unreachable: " + memory.unreachablePositions().stream()
          .map(pos -> "%d,%d,%d until=%d".formatted(
            pos.pos().getX(),
            pos.pos().getY(),
            pos.pos().getZ(),
            pos.untilTick()))
          .toList());
      }
      return Command.SINGLE_SUCCESS;
    });
  }

  private static int showCoordinationStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                            int maxEntries) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    return forEveryInstance(context, instance -> {
      var snapshot = instance.automationCoordinator().coordinationSnapshot(maxEntries);
      var summary = snapshot.summary();
      context.getSource().source().sendInfo("%s: objective=%s collaboration=%s sharedStructureIntel=%s sharedClaims=%s sharedBlocks=%d claims=%d eyeSamples=%d".formatted(
        instance.friendlyNameCache().get(),
        summary.objective().name().toLowerCase(Locale.ROOT),
        summary.collaborationEnabled() ? "on" : "off",
        summary.sharedStructureIntel() ? "on" : "off",
        summary.sharedTargetClaims() ? "on" : "off",
        snapshot.sharedBlockCount(),
        snapshot.claimCount(),
        snapshot.eyeSampleCount()));

      if (!snapshot.sharedCounts().isEmpty()) {
        context.getSource().source().sendInfo("  shared counts: " + snapshot.sharedCounts().stream()
          .map(count -> "%s=%d/%d".formatted(
            AutomationRequirements.describe(count.requirementKey()),
            count.currentCount(),
            count.targetCount()))
          .toList());
      }
      if (!snapshot.claims().isEmpty()) {
        context.getSource().source().sendInfo("  claims: " + snapshot.claims().stream()
          .map(claim -> {
            var target = claim.target() == null
              ? "none"
              : "%d,%d,%d".formatted(
              (int) Math.floor(claim.target().x),
              (int) Math.floor(claim.target().y),
              (int) Math.floor(claim.target().z));
            return "%s owner=%s target=%s".formatted(claim.key(), claim.ownerAccountName(), target);
          })
          .toList());
      }
      if (!snapshot.eyeSamples().isEmpty()) {
        context.getSource().source().sendInfo("  eye samples: " + snapshot.eyeSamples().stream()
          .map(sample -> "%s origin=%d,%d,%d dir=%.2f,%.2f".formatted(
            sample.accountName(),
            (int) Math.floor(sample.origin().x),
            (int) Math.floor(sample.origin().y),
            (int) Math.floor(sample.origin().z),
            sample.direction().x,
            sample.direction().z))
          .toList());
      }
      if (!snapshot.sharedBlocks().isEmpty()) {
        context.getSource().source().sendInfo("  shared blocks: " + snapshot.sharedBlocks().stream()
          .map(block -> "%s %s@%d,%d,%d by=%s".formatted(
            block.dimension().identifier(),
            block.state().getBlock().getName().getString(),
            block.pos().getX(),
            block.pos().getY(),
            block.pos().getZ(),
            block.observerAccountName()))
          .toList());
      }
      return Command.SINGLE_SUCCESS;
    });
  }

  private static AutomationWorldMemory.MemorySnapshot readMemorySnapshot(BotConnection bot, int maxEntries) {
    try {
      return bot.runnableWrapper().wrap(() -> bot.automation().memorySnapshot(maxEntries)).call();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read automation memory for " + bot.accountName(), e);
    }
  }

  private static void resetMemory(BotConnection bot) {
    try {
      bot.runnableWrapper().wrap(() -> {
        bot.automation().resetMemory();
        return null;
      }).call();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to reset automation memory for " + bot.accountName(), e);
    }
  }

  private static AutomationSettings.Preset parsePreset(String raw) {
    return AutomationSettings.Preset.valueOf(normalizeEnumId(raw));
  }

  private static AutomationSettings.RolePolicy parseRolePolicy(String raw) {
    return AutomationSettings.RolePolicy.valueOf(normalizeEnumId(raw));
  }

  private static AutomationSettings.RoleOverride parseRoleOverride(String raw) {
    return AutomationSettings.RoleOverride.valueOf(normalizeEnumId(raw));
  }

  private static AutomationSettings.ObjectiveOverride parseObjectiveOverride(String raw) {
    return AutomationSettings.ObjectiveOverride.valueOf(normalizeEnumId(raw));
  }

  private static String normalizeEnumId(String raw) {
    return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static String formatEnumId(Enum<?> value) {
    return AutomationControlSupport.formatEnumId(value);
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

  private static final class RoleOverrideSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final RoleOverrideSuggestionProvider INSTANCE = new RoleOverrideSuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      for (var roleOverride : AutomationSettings.RoleOverride.values()) {
        builder.suggest(formatEnumId(roleOverride));
      }
      return builder.buildFuture();
    }
  }

  private static final class QuotaTargetSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final QuotaTargetSuggestionProvider INSTANCE = new QuotaTargetSuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      builder.suggest("blaze_rod");
      builder.suggest("ender_pearl");
      builder.suggest("ender_eye");
      builder.suggest("arrow");
      builder.suggest("bed");
      return builder.buildFuture();
    }
  }

  private static final class ObjectiveOverrideSuggestionProvider implements SuggestionProvider<CommandSourceStack> {
    private static final ObjectiveOverrideSuggestionProvider INSTANCE = new ObjectiveOverrideSuggestionProvider();

    @Override
    public java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> getSuggestions(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
      for (var objectiveOverride : AutomationSettings.ObjectiveOverride.values()) {
        builder.suggest(formatEnumId(objectiveOverride));
      }
      return builder.buildFuture();
    }
  }
}
