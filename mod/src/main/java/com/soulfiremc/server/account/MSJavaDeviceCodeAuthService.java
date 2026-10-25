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
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class MSJavaDeviceCodeAuthService
  implements MCAuthService<Consumer<MsaDeviceCode>, MSJavaDeviceCodeAuthService.MSJavaDeviceCodeAuthData> {
  public static final MSJavaDeviceCodeAuthService INSTANCE = new MSJavaDeviceCodeAuthService();

  private MSJavaDeviceCodeAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSJavaDeviceCodeAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = JavaAuthManager.create(LenniHttpHelper.client(transportProxy))
        .login(DeviceCodeMsaAuthService::new, data.callback);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_DEVICE_CODE, authManager, null);
    });
  }

  @Override
  public MSJavaDeviceCodeAuthService.MSJavaDeviceCodeAuthData createData(Consumer<MsaDeviceCode> data) {
    return new MSJavaDeviceCodeAuthService.MSJavaDeviceCodeAuthData(data);
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(transportProxy);
      return AuthHelpers.fromJavaAuthManager(AuthType.MICROSOFT_JAVA_DEVICE_CODE, authManager, account);
    });
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    var authManager = ((OnlineChainJavaData) account.accountData()).getJavaAuthManager(null);
    return authManager.getMinecraftToken().isExpired()
      || authManager.getMinecraftProfile().isExpired()
      || authManager.getMinecraftPlayerCertificates().isExpired();
  }

  public record MSJavaDeviceCodeAuthData(Consumer<MsaDeviceCode> callback) {}
}
