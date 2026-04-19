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

import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.util.LenniHttpHelper;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.java.request.MinecraftProfileRequest;
import net.raphimc.minecraftauth.util.jwt.Jwt;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/// Authentication service for raw Minecraft Java access tokens.
/// Validates the token by fetching the associated profile from Mojang's API.
/// Access token accounts cannot be refreshed; they are used until expiry.
public final class MSJavaAccessTokenAuthService
  implements MCAuthService<String, MSJavaAccessTokenAuthService.MSJavaAccessTokenAuthData> {
  public static final MSJavaAccessTokenAuthService INSTANCE = new MSJavaAccessTokenAuthService();

  private MSJavaAccessTokenAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(MSJavaAccessTokenAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        var token = new MinecraftToken(data.expireTimeMs, "Bearer", data.accessToken);
        var profile = LenniHttpHelper.client(proxyData)
          .executeAndHandle(new MinecraftProfileRequest(token));
        return new MinecraftAccount(
          AuthType.MICROSOFT_JAVA_ACCESS_TOKEN,
          profile.getId(),
          profile.getName(),
          new OnlineSimpleJavaData(data.accessToken, data.expireTimeMs),
          Map.of(),
          Map.of());
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
  }

  @Override
  public MSJavaAccessTokenAuthData createData(String data) {
    var token = data.strip();
    if (token.isEmpty()) {
      throw new IllegalArgumentException("Access token is empty");
    }

    var jwt = Jwt.parse(token);
    return new MSJavaAccessTokenAuthData(token, jwt.getExpireTimeMs());
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return CompletableFuture.failedFuture(
      new UnsupportedOperationException("Access token accounts cannot be refreshed"));
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    // Access tokens cannot be refreshed, so never flag them for the refresh system.
    // If the token is actually expired, the server will reject the join request.
    return false;
  }

  public record MSJavaAccessTokenAuthData(String accessToken, long expireTimeMs) {}
}
