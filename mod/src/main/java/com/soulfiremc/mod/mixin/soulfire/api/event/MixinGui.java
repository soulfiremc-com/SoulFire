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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotShouldRespawnEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.util.SFHelpers;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGui {
  @Inject(method = "setScreen", at = @At("HEAD"))
  private void disconnectFailedConnection(Screen screen, CallbackInfo ci) {
    if (screen instanceof DisconnectedScreen disconnectedScreen) {
      BotConnection.currentOptional().ifPresent(connection ->
        connection.disconnect(SFHelpers.nativeToAdventure(disconnectedScreen.details.reason())));
    }
  }

  @WrapOperation(method = "setScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;shouldShowDeathScreen()Z"))
  private boolean shouldRespawnEvent(LocalPlayer instance, Operation<Boolean> original) {
    var connection = BotConnection.currentOptional().orElse(null);
    if (connection == null) {
      return original.call(instance);
    }

    var event = new BotShouldRespawnEvent(connection, !original.call(instance));
    SoulFireAPI.postEvent(event);
    return !event.shouldRespawn();
  }
}
