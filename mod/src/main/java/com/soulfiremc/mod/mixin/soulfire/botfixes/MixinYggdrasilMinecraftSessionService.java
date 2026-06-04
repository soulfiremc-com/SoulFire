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
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import com.soulfiremc.server.account.TheAlteningAuthService;
import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.account.service.TheAlteningJavaData;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.proxy.SFProxy;
import org.spongepowered.asm.mixin.Mixin;

import java.net.Proxy;
import java.util.UUID;

@Mixin(YggdrasilMinecraftSessionService.class)
public class MixinYggdrasilMinecraftSessionService {
  @WrapMethod(method = "joinServer")
  private void joinServer(UUID profileId, String authenticationToken, String serverId, Operation<Void> original) throws AuthenticationException {
    var bot = BotConnection.current();
    var account = bot.settingsSource().stem();
    var actualProfileId = bot.accountProfileId();
    var accountData = account.accountData();
    var actualAuthenticationToken = switch (accountData) {
      case OnlineChainJavaData onlineChainJavaData -> onlineChainJavaData.getJavaAuthManager(bot.proxy()).getMinecraftToken().getUpToDateUnchecked().getToken();
      case OnlineSimpleJavaData onlineSimpleJavaData -> onlineSimpleJavaData.accessToken();
      case TheAlteningJavaData theAlteningJavaData -> theAlteningJavaData.accessToken();
      case OfflineJavaData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
      case BedrockData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
    };

    if (accountData instanceof TheAlteningJavaData) {
      YggdrasilAuthenticationService.createOffline(toJavaProxy(bot.proxy()), TheAlteningAuthService.ENVIRONMENT)
        .createMinecraftSessionService()
        .joinServer(actualProfileId, actualAuthenticationToken, serverId);
      return;
    }

    original.call(actualProfileId, actualAuthenticationToken, serverId);
  }

  private static Proxy toJavaProxy(SFProxy proxy) {
    if (proxy == null) {
      return Proxy.NO_PROXY;
    }

    return new Proxy(
      proxy.type() == com.soulfiremc.server.proxy.ProxyType.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
      proxy.address());
  }
}
