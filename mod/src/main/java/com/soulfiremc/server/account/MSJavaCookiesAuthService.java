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
import com.soulfiremc.server.util.LenniHttpHelper;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MSJavaCookiesAuthService
  implements MCAuthService<String, MSJavaCookiesAuthService.MSJavaCookiesAuthData> {
  public static final MSJavaCookiesAuthService INSTANCE = new MSJavaCookiesAuthService();

  private MSJavaCookiesAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSJavaCookiesAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var cookieHeader = MSLiveCookieHelper.parseCookieHeader(data.cookieInput);
      var authManager = JavaAuthManager.create(LenniHttpHelper.client(transportProxy))
        .login(CookieMsaAuthService::new, cookieHeader);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_COOKIES, authManager, null);
    });
  }

  @Override
  public MSJavaCookiesAuthData createData(String data) {
    var input = data.strip();
    if (input.isEmpty()) {
      throw new IllegalArgumentException("Cookie input is empty");
    }
    return new MSJavaCookiesAuthData(input);
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(transportProxy);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_COOKIES, authManager, account);
    });
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(null);
    return authManager.getMinecraftToken().isExpired()
      || authManager.getMinecraftProfile().isExpired()
      || authManager.getMinecraftPlayerCertificates().isExpired();
  }

  public record MSJavaCookiesAuthData(String cookieInput) {}
}
