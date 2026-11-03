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

import java.util.ArrayList;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class OnlineCommand {
  private OnlineCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("online")
        .executes(
          help(
            "Show connected bots in the selected projects",
            c ->
              forEveryInstanceEnsureHasBots(
                c,
                instanceManager -> {
                  var online = new ArrayList<String>();
                  for (var bot : c.getSource().getInstanceVisibleBots(instanceManager)) {
                    if (bot.minecraft().player != null) {
                      online.add(bot.accountName());
                    }
                  }

                  c.getSource().source()
                    .sendInfo(
                      online.size() + " bots online: " + String.join(", ", online));
                  return Command.SINGLE_SUCCESS;
                }))));
  }
}
