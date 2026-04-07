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
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.command.brigadier.EntityArgumentType;

import java.util.stream.StreamSupport;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class MimicCommand {
  private MimicCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("mimic")
        .then(argument("entity", EntityArgumentType.INSTANCE)
          .executes(
            help(
              "Makes selected bots mimic the movement of other entities",
              c -> {
                var entityMatcher = EntityArgumentType.getEntityMatcher(c, "entity");

                return forEveryBot(
                  c,
                  bot -> {
                    var level = bot.minecraft().level;
                    var player = bot.minecraft().player;
                    if (level == null || player == null) {
                      c.getSource().source().sendWarn("You must be in a world to use this command!");
                      return Command.SINGLE_SUCCESS;
                    }

                    var entity = StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
                      .filter(entityMatcher)
                      .findAny();
                    if (entity.isEmpty()) {
                      c.getSource().source().sendWarn("Entity not found!");
                      return Command.SINGLE_SUCCESS;
                    }

                    var offset = entity.get().position().subtract(player.position());
                    bot.botControl().replace(new ControlTask() {
                      @Override
                      public void tick() {
                        bot.controlState().resetAll();

                        player.setYRot(entity.get().getYRot());
                        player.setXRot(entity.get().getXRot());

                        var currentPos = player.position();
                        var newPos = entity.get().position().subtract(offset);
                        var delta = newPos.subtract(currentPos);
                        player.setPos(entity.get().position().subtract(offset));
                        player.setDeltaMovement(delta);
                      }

                      @Override
                      public boolean isDone() {
                        return false;
                      }

                      @Override
                      public ControlPriority priority() {
                        return ControlPriority.HIGH;
                      }

                      @Override
                      public String description() {
                        return "Mimic";
                      }

                      @Override
                      public void onStopped(ControlStopReason reason, Throwable cause) {
                        bot.controlState().resetAll();
                      }
                    });

                    return Command.SINGLE_SUCCESS;
                  });
              }))));
  }
}
