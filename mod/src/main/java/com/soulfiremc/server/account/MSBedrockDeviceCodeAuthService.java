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

import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.proxy.ProxyTransport;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.server.util.LenniHttpHelper;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class MSBedrockDeviceCodeAuthService
  implements MCAuthService<Consumer<MsaDeviceCode>, MSBedrockDeviceCodeAuthService.MSBedrockDeviceCodeAuthData> {
  public static final MSBedrockDeviceCodeAuthService INSTANCE = new MSBedrockDeviceCodeAuthService();

  private MSBedrockDeviceCodeAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSBedrockDeviceCodeAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = BedrockAuthManager.create(LenniHttpHelper.client(transportProxy), ProtocolConstants.BEDROCK_VERSION_NAME)
        .login(DeviceCodeMsaAuthService::new, data.callback);
      return AuthHelpers.fromBedrockAuthManager(AuthType.MICROSOFT_BEDROCK_DEVICE_CODE, authManager, null);
    });
  }

  @Override
  public MSBedrockDeviceCodeAuthData createData(Consumer<MsaDeviceCode> data) {
    return new MSBedrockDeviceCodeAuthData(data);
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = ((BedrockData) account.accountData()).getBedrockAuthManager(transportProxy);
      return AuthHelpers.fromBedrockAuthManager(AuthType.MICROSOFT_BEDROCK_DEVICE_CODE, authManager, account);
    });
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    var authManager = ((BedrockData) account.accountData()).getBedrockAuthManager(null);
    return authManager.getMsaToken().isExpired()
      || authManager.getXblDeviceToken().isExpired()
      || authManager.getXblUserToken().isExpired()
      || authManager.getXblTitleToken().isExpired()
      || authManager.getBedrockXstsToken().isExpired()
      || authManager.getPlayFabXstsToken().isExpired()
      || authManager.getRealmsXstsToken().isExpired()
      || authManager.getXboxLiveXstsToken().isExpired()
      || authManager.getPlayFabToken().isExpired()
      || authManager.getMinecraftSession().isExpired()
      || authManager.getMinecraftMultiplayerToken().isExpired()
      || authManager.getMinecraftCertificateChain().isExpired();
  }

  public record MSBedrockDeviceCodeAuthData(Consumer<MsaDeviceCode> callback) {}
}
