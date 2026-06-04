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

import com.mojang.authlib.Environment;
import com.soulfiremc.server.account.service.TheAlteningJavaData;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.util.LenniHttpHelper;
import com.soulfiremc.server.util.structs.GsonInstance;
import net.lenni0451.commons.httpclient.constants.ContentTypes;
import net.lenni0451.commons.httpclient.constants.HttpHeaders;
import net.lenni0451.commons.httpclient.content.impl.StringContent;
import net.lenni0451.commons.httpclient.handler.ThrowingResponseHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/// Authentication service for TheAltening account tokens.
/// TheAltening exposes a Yggdrasil-compatible auth/session server pair.
public final class TheAlteningAuthService
  implements MCAuthService<String, TheAlteningAuthService.TheAlteningAuthData> {
  public static final TheAlteningAuthService INSTANCE = new TheAlteningAuthService();
  public static final String AUTH_SERVER_URL = "http://authserver.thealtening.com";
  public static final String SESSION_SERVER_URL = "http://sessionserver.thealtening.com";
  public static final Environment ENVIRONMENT = new Environment(
    SESSION_SERVER_URL,
    "https://api.minecraftservices.com",
    "https://api.minecraftservices.com",
    "PROD");
  private static final Agent MINECRAFT_AGENT = new Agent("Minecraft", 1);
  private static final String CLIENT_TOKEN = UUID.randomUUID().toString();
  private static final String AUTH_PASSWORD = "SoulFire";

  private TheAlteningAuthService() {}

  @Override
  public CompletableFuture<MinecraftAccount> login(TheAlteningAuthData data, @Nullable SFProxy proxyData, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return authenticate(data.accountToken, proxyData, null);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
  }

  @Override
  public TheAlteningAuthData createData(String data) {
    var accountToken = data.strip();
    if (accountToken.isEmpty()) {
      throw new IllegalArgumentException("TheAltening account token is empty");
    }

    return new TheAlteningAuthData(accountToken);
  }

  @Override
  public CompletableFuture<MinecraftAccount> refresh(MinecraftAccount account, @Nullable SFProxy proxyData, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        var accountData = (TheAlteningJavaData) account.accountData();
        return authenticate(accountData.accountToken(), proxyData, account);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
  }

  @Override
  public boolean isExpired(MinecraftAccount account) {
    return false;
  }

  private MinecraftAccount authenticate(String accountToken, @Nullable SFProxy proxyData, @Nullable MinecraftAccount previousAccount)
    throws IOException {
    var authRequest = new AuthenticationRequest(
      MINECRAFT_AGENT,
      accountToken,
      AUTH_PASSWORD,
      CLIENT_TOKEN,
      true);
    var jsonRequest = GsonInstance.GSON.toJson(authRequest);
    var client = LenniHttpHelper.client(proxyData);
    var request = client
      .post(AUTH_SERVER_URL + "/authenticate")
      .setContent(new StringContent(ContentTypes.APPLICATION_JSON, jsonRequest))
      .setHeader(HttpHeaders.ACCEPT, "application/json");
    var response = client.execute(request, new ThrowingResponseHandler());
    var authResponse = GsonInstance.GSON.fromJson(response.getDecodedContent().getAsString(), AuthenticationResponse.class);

    if (authResponse == null) {
      throw new IOException("TheAltening authentication returned an empty response");
    } else if (!CLIENT_TOKEN.equals(authResponse.clientToken())) {
      throw new IOException("TheAltening authentication returned a mismatched client token");
    } else if (authResponse.accessToken() == null || authResponse.accessToken().isBlank()) {
      throw new IOException("TheAltening authentication returned an empty access token");
    }

    var selectedProfile = authResponse.selectedProfile();
    if (selectedProfile == null || authResponse.availableProfiles() == null || authResponse.availableProfiles().length == 0) {
      throw new IOException("TheAltening authentication did not return a Minecraft profile");
    }

    return new MinecraftAccount(
      AuthType.THE_ALTENING,
      parseProfileId(selectedProfile.id()),
      selectedProfile.name(),
      new TheAlteningJavaData(accountToken, authResponse.accessToken()),
      previousAccount != null ? previousAccount.settings() : Map.of(),
      previousAccount != null ? previousAccount.persistentMetadata() : Map.of());
  }

  private static UUID parseProfileId(String profileId) {
    if (profileId.length() == 32) {
      return UUID.fromString("%s-%s-%s-%s-%s".formatted(
        profileId.substring(0, 8),
        profileId.substring(8, 12),
        profileId.substring(12, 16),
        profileId.substring(16, 20),
        profileId.substring(20)));
    }

    return UUID.fromString(profileId);
  }

  private record Agent(String name, int version) {}

  private record AuthenticationRequest(
    Agent agent,
    String username,
    String password,
    String clientToken,
    boolean requestUser) {}

  private record AuthenticationResponse(
    String accessToken,
    String clientToken,
    Profile[] availableProfiles,
    Profile selectedProfile) {}

  private record Profile(String id, String name) {}

  public record TheAlteningAuthData(String accountToken) {}
}
