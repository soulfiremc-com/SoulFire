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
package com.soulfiremc.mod.mixin.soulfire.botfixes;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.util.SFHelpers;
import org.spongepowered.asm.mixin.Mixin;

import java.util.UUID;

@Mixin(YggdrasilMinecraftSessionService.class)
public class MixinYggdrasilMinecraftSessionService {
  @WrapMethod(method = "joinServer")
  private void joinServer(UUID profileId, String authenticationToken, String serverId, Operation<Void> original) {
    var bot = BotConnection.current();
    var account = bot.settingsSource().stem();
    var actualProfileId = bot.accountProfileId();
    var actualAuthenticationToken = switch (account.accountData()) {
      case OnlineChainJavaData onlineChainJavaData -> onlineChainJavaData.getJavaAuthManager(bot.proxy()).getMinecraftToken().getUpToDateUnchecked().getToken();
      case OnlineSimpleJavaData onlineSimpleJavaData -> onlineSimpleJavaData.accessToken();
      case OfflineJavaData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
      case BedrockData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
    };

    original.call(actualProfileId, actualAuthenticationToken, serverId);
  }
}
