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

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class ControlCommand {
  private ControlCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("control")
        .then(
          literal("forward")
            .executes(
              help(
                "Toggle walking forward for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.up(!controlState.up());
                        controlState.down(false);
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("backward")
            .executes(
              help(
                "Toggle walking backward for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.down(!controlState.down());
                        controlState.up(false);
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("left")
            .executes(
              help(
                "Toggle walking left for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.left(!controlState.left());
                        controlState.right(false);
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("right")
            .executes(
              help(
                "Toggle walking right for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.right(!controlState.right());
                        controlState.left(false);
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("jump")
            .executes(
              help(
                "Toggle jumping for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.jump(!controlState.jump());
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("sneak")
            .executes(
              help(
                "Toggle sneaking for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.shift(!controlState.shift());
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("sneak")
            .executes(
              help(
                "Toggle sneaking for selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var controlState = bot.controlState();

                        controlState.sprint(!controlState.sprint());
                      }));
                      return Command.SINGLE_SUCCESS;
                    }))))
        .then(
          literal("reset")
            .executes(
              help(
                "Resets the movement of selected bots",
                c ->
                  forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() ->
                        bot.controlState().resetAll()));
                      return Command.SINGLE_SUCCESS;
                    }))))
    );
  }
}
