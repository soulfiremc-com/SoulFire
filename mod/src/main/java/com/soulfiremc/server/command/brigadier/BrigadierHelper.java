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
package com.soulfiremc.server.command.brigadier;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.database.AuditLogType;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mojang.brigadier.CommandDispatcher.ARGUMENT_SEPARATOR;

@Slf4j
public final class BrigadierHelper {
  private BrigadierHelper() {}

  public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
    return LiteralArgumentBuilder.literal(name);
  }

  public static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(
    String name, ArgumentType<T> type) {
    return RequiredArgumentBuilder.argument(name, type);
  }

  public static Command<CommandSourceStack> help(String help, Command<CommandSourceStack> command) {
    return new CommandHelpWrapper(command, CommandHelpMeta.forPublicCommand(help));
  }

  public static RedirectModifier<CommandSourceStack> helpRedirect(
    String help, RedirectModifier<CommandSourceStack> redirect) {
    return new RedirectHelpWrapper(redirect, CommandHelpMeta.forPublicCommand(help));
  }

  public static RedirectModifier<CommandSourceStack> helpSingleRedirect(
    String help, SingleRedirectModifier<CommandSourceStack> redirect) {
    return new RedirectHelpWrapper(o -> Collections.singleton(redirect.apply(o)), CommandHelpMeta.forPublicCommand(help));
  }

  public static Command<CommandSourceStack> privateCommand(Command<CommandSourceStack> command) {
    return new CommandHelpWrapper(command, CommandHelpMeta.forPrivateCommand());
  }

  public static CommandHelpInfo[] getAllUsage(
    final CommandDispatcher<CommandSourceStack> dispatcher,
    final CommandNode<CommandSourceStack> node,
    final CommandSourceStack source) {
    final var result = new ArrayList<CommandHelpInfo>();
    getAllUsage(dispatcher, node, source, result, "");
    return result.toArray(new CommandHelpInfo[0]);
  }

  private static void getAllUsage(
    final CommandDispatcher<CommandSourceStack> dispatcher,
    final CommandNode<CommandSourceStack> node,
    final CommandSourceStack source,
    final ArrayList<CommandHelpInfo> result,
    final String prefix) {
    if (!node.canUse(source)) {
      return;
    }

    if (node.getCommand() != null) {
      log.debug("Adding usage for {}", node.getUsageText());
      var helpWrapper = (BrigadierHelper.HelpCarrier) node.getCommand();
      if (!helpWrapper.helpMeta().privateCommand) {
        result.add(new CommandHelpInfo(prefix, helpWrapper.helpMeta()));
      }
    }

    if (node.getRedirect() != null) {
      log.debug("Redirecting {} to {}", node.getUsageText(), node.getRedirect().getUsageText());
      var redirectHelpWrapper = (BrigadierHelper.HelpCarrier) node.getRedirectModifier();
      if (!redirectHelpWrapper.helpMeta().privateCommand) {
        final var redirect =
          node.getRedirect() == dispatcher.getRoot()
            ? "..."
            : "-> " + node.getRedirect().getUsageText();
        result.add(
          new CommandHelpInfo(
            prefix.isEmpty()
              ? node.getUsageText() + ARGUMENT_SEPARATOR + redirect
              : prefix + ARGUMENT_SEPARATOR + redirect,
            redirectHelpWrapper.helpMeta()));
      }
    } else if (!node.getChildren().isEmpty()) {
      for (final var child : node.getChildren()) {
        getAllUsage(
          dispatcher,
          child,
          source,
          result,
          prefix.isEmpty()
            ? child.getUsageText()
            : prefix + ARGUMENT_SEPARATOR + child.getUsageText());
      }
    }
  }

  public static int forEveryInstance(
    CommandContext<CommandSourceStack> context,
    CommandFunction<InstanceManager> consumer) {
    var instances = context.getSource().getVisibleInstances();
    if (instances.isEmpty()) {
      context.getSource().source().sendWarn("No projects found.");
      return 0;
    }

    var resultSum = new AtomicInteger();
    for (var instance : instances) {
      context.getSource().source().sendInfo("--- Running command for project %s ---".formatted(instance.friendlyName()));

      instance.addAuditLog(context.getSource().source(), AuditLogType.EXECUTE_COMMAND, context.getInput());
      instance.runnableWrapper().runWrapped(() ->
        resultSum.addAndGet(consumer.run(instance)));
    }

    return resultSum.get();
  }

  public static int forEveryInstanceEnsureHasBots(
    CommandContext<CommandSourceStack> context,
    CommandFunction<InstanceManager> consumer) throws CommandSyntaxException {
    return forEveryInstance(
      context,
      instance -> {
        if (context.getSource().getInstanceVisibleBots(instance).isEmpty()) {
          context.getSource().source().sendWarn("Project %s has no connected bots.".formatted(instance.friendlyName()));
          return 0;
        }

        return consumer.run(instance);
      });
  }

  public static int forEveryBot(
    CommandContext<CommandSourceStack> context,
    CommandFunction<BotConnection> consumer) throws CommandSyntaxException {
    return forEveryInstanceEnsureHasBots(
      context,
      instance -> {
        var resultSum = new AtomicInteger();
        for (var bot : context.getSource().getInstanceVisibleBots(instance)) {
          context.getSource().source().sendInfo("--- Running command for bot %s ---".formatted(bot.accountName()));
          bot.runnableWrapper().runWrapped(
            () -> resultSum.addAndGet(consumer.run(bot)));
        }

        return resultSum.get();
      });
  }

  public static @Nullable Component toComponent(@Nullable Message message) {
    return switch (message) {
      case null -> null;
      case BrigadierComponent brigadierComponent -> brigadierComponent.component();
      default -> Component.text(message.getString());
    };
  }

  public interface CommandFunction<S> {
    int run(S subject);
  }

  public sealed interface HelpCarrier permits SingleRedirectHelpWrapper, CommandHelpWrapper, RedirectHelpWrapper {
    CommandHelpMeta helpMeta();
  }

  public record CommandHelpInfo(String command, CommandHelpMeta helpMeta) {}

  private record SingleRedirectHelpWrapper(
    SingleRedirectModifier<CommandSourceStack> command,
    CommandHelpMeta helpMeta
  ) implements SingleRedirectModifier<CommandSourceStack>, HelpCarrier {
    @Override
    public CommandSourceStack apply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      return command.apply(context);
    }
  }

  private record CommandHelpWrapper(
    Command<CommandSourceStack> command,
    CommandHelpMeta helpMeta
  )
    implements Command<CommandSourceStack>, HelpCarrier {
    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      return command.run(context);
    }
  }

  private record RedirectHelpWrapper(
    RedirectModifier<CommandSourceStack> command,
    CommandHelpMeta helpMeta
  ) implements RedirectModifier<CommandSourceStack>, HelpCarrier {
    @Override
    public Collection<CommandSourceStack> apply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      return command.apply(context);
    }
  }

  public record CommandHelpMeta(
    @Nullable String help,
    boolean privateCommand
  ) {
    public static CommandHelpMeta forPrivateCommand() {
      return new CommandHelpMeta(null, true);
    }

    public static CommandHelpMeta forPublicCommand(String help) {
      return new CommandHelpMeta(help, false);
    }
  }
}
