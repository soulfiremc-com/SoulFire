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
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.command.brigadier.BrigadierHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.event.Level;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.help;
import static com.soulfiremc.server.command.brigadier.BrigadierHelper.literal;

public final class HelpCommand {
  private HelpCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("help")
        .executes(
          help(
            "Prints a list of all available commands",
            c -> {
              c.getSource().source().sendInfo("Available commands:");
              for (var command : BrigadierHelper.getAllUsage(dispatcher, dispatcher.getRoot(), c.getSource())) {
                c.getSource().source().sendMessage(Level.INFO,
                  Component.empty().append(Component.text(command.command())
                    .hoverEvent(HoverEvent.showText(Component.text(command.helpMeta().help())))
                    .clickEvent(ClickEvent.suggestCommand(command.command())))
                    .append(Component.text(" -> " + command.helpMeta().help())));
              }

              return Command.SINGLE_SUCCESS;
            })));
  }
}
