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
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.util.MouseClickHelper;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class ClickCommand {
  private ClickCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("click")
        .then(
          literal("left")
            .executes(
              help(
                "Simulates a left mouse button click for selected bots (attack/break)",
                c -> forEveryBot(
                c,
                bot -> {
                    bot.botControl().replace(ControlTask.once(() -> {
                      var level = bot.minecraft().level;
                      var player = bot.minecraft().player;
                      var gameMode = bot.minecraft().gameMode;
                      if (level == null || player == null || gameMode == null) {
                        return;
                      }

                      MouseClickHelper.performLeftClick(player, level, gameMode);
                    }));
                    return Command.SINGLE_SUCCESS;
                  }))))
        .then(
          literal("right")
            .executes(
              help(
                "Simulates a right mouse button click for selected bots (use/interact)",
                c -> forEveryBot(
                c,
                bot -> {
                    bot.botControl().replace(ControlTask.once(() -> {
                      var level = bot.minecraft().level;
                      var player = bot.minecraft().player;
                      var gameMode = bot.minecraft().gameMode;
                      if (level == null || player == null || gameMode == null) {
                        return;
                      }

                      MouseClickHelper.performRightClick(player, level, gameMode);
                    }));
                    return Command.SINGLE_SUCCESS;
                  }))))
    );
  }
}
