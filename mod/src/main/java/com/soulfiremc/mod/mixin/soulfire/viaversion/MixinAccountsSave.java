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
package com.soulfiremc.mod.mixin.soulfire.viaversion;

import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.bot.BotConnection;
import com.viaversion.viafabricplus.save.impl.AccountsSave;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AccountsSave.class)
public class MixinAccountsSave {
  @Inject(method = "getBedrockAccount", at = @At("HEAD"), remap = false, cancellable = true)
  private void getBedrockAccount(CallbackInfoReturnable<BedrockAuthManager> cir) {
    var connection = BotConnection.currentOptional().orElse(null);
    if (connection == null) {
      return;
    }

    var accountData = connection.settingsSource().stem().accountData();
    if (accountData instanceof BedrockData bedrockData) {
      cir.setReturnValue(bedrockData.getBedrockAuthManager(connection.proxy()));
    }
  }
}
