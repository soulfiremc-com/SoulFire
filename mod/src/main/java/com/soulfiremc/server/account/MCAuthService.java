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

import com.soulfiremc.grpc.generated.AccountTypeCredentials;
import com.soulfiremc.grpc.generated.AccountTypeDeviceCode;
import com.soulfiremc.grpc.generated.MinecraftAccountProto;
import com.soulfiremc.server.proxy.SFProxy;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public sealed interface MCAuthService<I, T>
  permits MSBedrockCredentialsAuthService, MSBedrockDeviceCodeAuthService, MSJavaAccessTokenAuthService, MSJavaCookiesAuthService, MSJavaCredentialsAuthService, MSJavaDeviceCodeAuthService, MSJavaRefreshTokenAuthService, OfflineAuthService, TheAlteningAuthService {
  static MCAuthService<String, ?> convertService(AccountTypeCredentials service) {
    return switch (service) {
      case MICROSOFT_JAVA_CREDENTIALS -> MSJavaCredentialsAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_CREDENTIALS -> MSBedrockCredentialsAuthService.INSTANCE;
      case OFFLINE -> OfflineAuthService.INSTANCE;
      case MICROSOFT_JAVA_REFRESH_TOKEN -> MSJavaRefreshTokenAuthService.INSTANCE;
      case MICROSOFT_JAVA_COOKIES -> MSJavaCookiesAuthService.INSTANCE;
      case MICROSOFT_JAVA_ACCESS_TOKEN -> MSJavaAccessTokenAuthService.INSTANCE;
      case THE_ALTENING -> TheAlteningAuthService.INSTANCE;
      case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized service");
    };
  }

  static MCAuthService<Consumer<MsaDeviceCode>, ?> convertService(AccountTypeDeviceCode service) {
    return switch (service) {
      case MICROSOFT_JAVA_DEVICE_CODE -> MSJavaDeviceCodeAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_DEVICE_CODE -> MSBedrockDeviceCodeAuthService.INSTANCE;
      case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized service");
    };
  }

  static MCAuthService<?, ?> convertService(MinecraftAccountProto.AccountTypeProto service) {
    return switch (service) {
      case MICROSOFT_JAVA_CREDENTIALS -> MSJavaCredentialsAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_CREDENTIALS -> MSBedrockCredentialsAuthService.INSTANCE;
      case OFFLINE -> OfflineAuthService.INSTANCE;
      case MICROSOFT_JAVA_DEVICE_CODE -> MSJavaDeviceCodeAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_DEVICE_CODE -> MSBedrockDeviceCodeAuthService.INSTANCE;
      case MICROSOFT_JAVA_REFRESH_TOKEN -> MSJavaRefreshTokenAuthService.INSTANCE;
      case MICROSOFT_JAVA_COOKIES -> MSJavaCookiesAuthService.INSTANCE;
      case MICROSOFT_JAVA_ACCESS_TOKEN -> MSJavaAccessTokenAuthService.INSTANCE;
      case THE_ALTENING -> TheAlteningAuthService.INSTANCE;
      case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized service");
    };
  }

  static MCAuthService<?, ?> convertService(AuthType service) {
    return switch (service) {
      case MICROSOFT_JAVA_CREDENTIALS -> MSJavaCredentialsAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_CREDENTIALS -> MSBedrockCredentialsAuthService.INSTANCE;
      case MICROSOFT_JAVA_DEVICE_CODE -> MSJavaDeviceCodeAuthService.INSTANCE;
      case MICROSOFT_BEDROCK_DEVICE_CODE -> MSBedrockDeviceCodeAuthService.INSTANCE;
      case OFFLINE -> OfflineAuthService.INSTANCE;
      case MICROSOFT_JAVA_REFRESH_TOKEN -> MSJavaRefreshTokenAuthService.INSTANCE;
      case MICROSOFT_JAVA_COOKIES -> MSJavaCookiesAuthService.INSTANCE;
      case MICROSOFT_JAVA_ACCESS_TOKEN -> MSJavaAccessTokenAuthService.INSTANCE;
      case THE_ALTENING -> TheAlteningAuthService.INSTANCE;
    };
  }

  CompletableFuture<MinecraftAccount> login(T data, @Nullable SFProxy proxyData, Executor executor);

  T createData(I data);

  default CompletableFuture<MinecraftAccount> createDataAndLogin(I data, @Nullable SFProxy proxyData, Executor executor) {
    return login(createData(data), proxyData, executor);
  }

  CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor);

  boolean isExpired(MinecraftAccount account);
}
