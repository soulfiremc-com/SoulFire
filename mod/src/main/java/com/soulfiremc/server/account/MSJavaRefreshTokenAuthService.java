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
package com.soulfiremc.server.account;

import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.proxy.ProxyTransport;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.server.util.LenniHttpHelper;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MSJavaRefreshTokenAuthService
  implements MCAuthService<String, MSJavaRefreshTokenAuthService.MSJavaRefreshTokenAuthData> {
  public static final MSJavaRefreshTokenAuthService INSTANCE = new MSJavaRefreshTokenAuthService();

  private MSJavaRefreshTokenAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSJavaRefreshTokenAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = JavaAuthManager.create(LenniHttpHelper.client(transportProxy))
        .login(data.refreshToken);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_REFRESH_TOKEN, authManager, null);
    });
  }

  @Override
  public MSJavaRefreshTokenAuthData createData(String data) {
    return new MSJavaRefreshTokenAuthData(data.strip());
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(transportProxy);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_REFRESH_TOKEN, authManager, account);
    });
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(null);
    return authManager.getMinecraftToken().isExpired()
      || authManager.getMinecraftProfile().isExpired()
      || authManager.getMinecraftPlayerCertificates().isExpired();
  }

  public record MSJavaRefreshTokenAuthData(String refreshToken) {}
}
