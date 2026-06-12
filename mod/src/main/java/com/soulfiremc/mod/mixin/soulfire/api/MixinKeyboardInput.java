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
package com.soulfiremc.mod.mixin.soulfire.api;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyboardInput.class)
public class MixinKeyboardInput {
  @WrapOperation(method = "tick", at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/world/entity/player/Input;"))
  private Input soulfireUpdatePlayerMoveState(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint, Operation<Input> original) {
    var connection = BotConnection.currentOptional().orElse(null);
    if (connection == null) {
      return original.call(forward, backward, left, right, jump, shift, sprint);
    }

    var controlState = connection.controlState();
    return new Input(
      controlState.up(),
      controlState.down(),
      controlState.left(),
      controlState.right(),
      controlState.jump(),
      controlState.shift(),
      controlState.sprint()
    );
  }
}
