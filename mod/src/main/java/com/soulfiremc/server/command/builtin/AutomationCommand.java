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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.soulfiremc.server.automation.AutomationRequirements;
import com.soulfiremc.server.command.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashSet;

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
          bot.automation().startBeatMinecraft();
          c.getSource().source().sendInfo("Started automation beat mode for " + bot.accountName());
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
                bot.automation().startAcquire(target, count);
                c.getSource().source().sendInfo("Started automation get %s x%d for %s".formatted(target, count, bot.accountName()));
                return Command.SINGLE_SUCCESS;
              });
            })))));
    root.then(literal("status")
      .executes(help(
        "Shows the current automation status for selected bots",
        c -> forEveryBot(c, bot -> {
          c.getSource().source().sendInfo(bot.accountName() + ": " + bot.automation().status());
          return Command.SINGLE_SUCCESS;
        }))));
    root.then(literal("stop")
      .executes(help(
        "Stops automation for selected bots",
        c -> forEveryBot(c, bot -> {
          bot.automation().stop();
          c.getSource().source().sendInfo("Stopped automation for " + bot.accountName());
          return Command.SINGLE_SUCCESS;
        }))));
    dispatcher.register(root);
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
}
