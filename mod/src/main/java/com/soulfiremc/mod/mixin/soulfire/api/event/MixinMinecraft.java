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

import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotPostTickEvent;
import com.soulfiremc.server.api.event.bot.BotPreTickEvent;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
  @Inject(method = "tick", at = @At("HEAD"))
  private void onTickPre(CallbackInfo ci) {
    var connection = BotConnection.currentOptional().orElse(null);
    if (connection == null) {
      return;
    }
    connection.rotationControl().beginTick();
    connection.botControl().tick();
    SoulFireAPI.postEvent(new BotPreTickEvent(connection));
  }

  @Inject(method = "tick", at = @At("RETURN"))
  private void onTickPost(CallbackInfo ci) {
    BotConnection.currentOptional()
      .ifPresent(connection -> SoulFireAPI.postEvent(new BotPostTickEvent(connection)));
  }
}
