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
import net.raphimc.minecraftauth.msa.model.MsaCredentials;
import net.raphimc.minecraftauth.msa.service.impl.CredentialsMsaAuthService;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import org.apache.commons.validator.routines.EmailValidator;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MSBedrockCredentialsAuthService
  implements MCAuthService<String, MSBedrockCredentialsAuthService.MSBedrockCredentialsAuthData> {
  public static final MSBedrockCredentialsAuthService INSTANCE = new MSBedrockCredentialsAuthService();

  private MSBedrockCredentialsAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSBedrockCredentialsAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = BedrockAuthManager.create(LenniHttpHelper.client(transportProxy), ProtocolConstants.BEDROCK_VERSION_NAME)
        .login(CredentialsMsaAuthService::new, new MsaCredentials(data.email, data.password));
      return AuthHelpers.fromBedrockAuthManager(AuthType.MICROSOFT_BEDROCK_CREDENTIALS, authManager, null);
    });
  }

  @Override
  public MSBedrockCredentialsAuthData createData(String data) {
    var split = data.split(":");

    if (split.length != 2) {
      throw new IllegalArgumentException("Invalid data!");
    }

    var email = split[0].strip();
    var password = split[1].strip();
    if (!EmailValidator.getInstance().isValid(email)) {
      throw new IllegalArgumentException("Invalid email!");
    }

    return new MSBedrockCredentialsAuthData(email, password);
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return ProxyTransport.supplyAsync(proxyData, executor, transportProxy -> {
      var authManager = ((BedrockData) account.accountData()).getBedrockAuthManager(transportProxy);
      return AuthHelpers.fromBedrockAuthManager(AuthType.MICROSOFT_BEDROCK_CREDENTIALS, authManager, account);
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

  public record MSBedrockCredentialsAuthData(String email, String password) {}
}
