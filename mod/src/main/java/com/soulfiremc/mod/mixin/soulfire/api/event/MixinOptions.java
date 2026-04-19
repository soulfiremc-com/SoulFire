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
package com.soulfiremc.mod.mixin.soulfire.api.event;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotClientSettingsEvent;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.Options;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Options.class)
public class MixinOptions {
  @WrapMethod(method = "buildPlayerInformation")
  private ClientInformation buildPlayerInformation(Operation<ClientInformation> original) {
    var connection = BotConnection.currentOptional().orElse(null);
    if (connection == null) {
      return original.call();
    }

    var event = new BotClientSettingsEvent(connection, original.call());
    SoulFireAPI.postEvent(event);
    return event.clientInformation();
  }
}
