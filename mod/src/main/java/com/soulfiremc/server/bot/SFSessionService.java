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
package com.soulfiremc.server.bot;

import com.mojang.util.UndashedUuid;
import com.soulfiremc.server.account.service.BedrockData;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.account.service.OnlineChainJavaData;
import com.soulfiremc.server.account.service.OnlineSimpleJavaData;
import com.soulfiremc.server.util.ReactorHttpHelper;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.netty.handler.codec.http.HttpStatusClass;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.netty.ByteBufFlux;

import java.net.URI;

@RequiredArgsConstructor
public final class SFSessionService {
  private static final URI MOJANG_JOIN_URI =
    URI.create("https://sessionserver.mojang.com/session/minecraft/join");
  private final BotConnection botConnection;

  public void joinServer(String serverId) {
    var account = botConnection.settingsSource().stem();
    var joinEndpoint = switch (account.authType()) {
      case MICROSOFT_JAVA_CREDENTIALS, MICROSOFT_JAVA_DEVICE_CODE, MICROSOFT_JAVA_REFRESH_TOKEN, MICROSOFT_JAVA_COOKIES, MICROSOFT_JAVA_ACCESS_TOKEN -> MOJANG_JOIN_URI;
      case OFFLINE, MICROSOFT_BEDROCK_CREDENTIALS, MICROSOFT_BEDROCK_DEVICE_CODE -> throw new IllegalArgumentException("Server does not support auth type: " + account.authType());
    };
    var authenticationToken = switch (account.accountData()) {
      case OnlineChainJavaData onlineChainJavaData -> onlineChainJavaData.getJavaAuthManager(botConnection.proxy()).getMinecraftToken().getUpToDateUnchecked().getToken();
      case OnlineSimpleJavaData onlineSimpleJavaData -> onlineSimpleJavaData.accessToken();
      case OfflineJavaData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
      case BedrockData ignored -> throw new IllegalArgumentException("Invalid auth type: " + account.authType());
    };

    ReactorHttpHelper.createReactorClient(botConnection.proxy(), true)
      .post()
      .uri(joinEndpoint)
      .send(
        ByteBufFlux.fromString(
          Flux.just(
            GsonInstance.GSON.toJson(
              new JoinServerRequest(
                authenticationToken,
                UndashedUuid.toString(botConnection.accountProfileId()),
                serverId)))))
      .responseSingle(
        (res, content) -> {
          if (res.status().codeClass() != HttpStatusClass.SUCCESS) {
            throw new JoinServerException(res.status().code());
          }

          return content.asString();
        })
      .block();
  }

  public static final class JoinServerException extends RuntimeException {
    private final int statusCode;

    private JoinServerException(int statusCode) {
      super("Failed to join server: HTTP " + statusCode);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  @SuppressWarnings("unused") // Used by GSON
  @AllArgsConstructor
  private static class JoinServerRequest {
    private String accessToken;
    private String selectedProfile;
    private String serverId;
  }
}
