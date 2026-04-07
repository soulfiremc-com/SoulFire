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
import com.soulfiremc.server.command.brigadier.EnumArgumentType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class UseItemCommand {
  private UseItemCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("useitem")
        .then(
          argument("hand", new EnumArgumentType<>(InteractionHand.class))
            .executes(
              help(
                "Makes selected bots use an item in the specified hand",
                c -> {
                  var hand = c.getArgument("hand", InteractionHand.class);

                  return forEveryBot(
                    c,
                    bot -> {
                      bot.botControl().replace(ControlTask.once(() -> {
                        var level = bot.minecraft().level;
                        var player = bot.minecraft().player;
                        var gameMode = bot.minecraft().gameMode;
                        if (level == null || player == null || gameMode == null) {
                          return;
                        }

                        var itemStack = player.getItemInHand(hand);
                        if (!itemStack.isItemEnabled(level.enabledFeatures())) {
                          return;
                        }

                        if (itemStack.isEmpty()) {
                          return;
                        }

                        if (gameMode.useItem(
                          player,
                          hand
                        ) instanceof InteractionResult.Success success) {
                          if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                            player.swing(hand);
                          }
                        }
                      }));
                      return Command.SINGLE_SUCCESS;
                    });
                }))));
  }
}
