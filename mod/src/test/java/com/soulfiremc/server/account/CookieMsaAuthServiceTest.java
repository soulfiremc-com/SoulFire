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

import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.HttpResponse;
import net.lenni0451.commons.httpclient.handler.HttpResponseHandler;
import net.lenni0451.commons.httpclient.requests.HttpContentRequest;
import net.lenni0451.commons.httpclient.requests.HttpRequest;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class CookieMsaAuthServiceTest {
  private static final String DESKTOP_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";

  @Test
  void usesDesktopRedirectUriForSilentCookieCodeFlow() throws IOException {
    var httpClient = new CapturingHttpClient();
    var service = new CookieMsaAuthService(
      httpClient,
      new MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH),
      "MSPAuth=auth");

    var token = service.acquireToken();

    assertEquals("access-token", token.getAccessToken());
    assertNotNull(httpClient.authorizeRequest);
    var authorizeParameters = parseForm(httpClient.authorizeRequest.getURL().getQuery());
    assertEquals(MsaConstants.JAVA_TITLE_ID, authorizeParameters.get("client_id"));
    assertEquals(MsaConstants.SCOPE_TITLE_AUTH, authorizeParameters.get("scope"));
    assertEquals(DESKTOP_REDIRECT_URI, authorizeParameters.get("redirect_uri"));
    assertEquals("none", authorizeParameters.get("prompt"));
    assertEquals("MSPAuth=auth", httpClient.authorizeRequest.getFirstHeader("Cookie").orElseThrow());
    assertEquals(HttpRequest.FollowRedirects.IGNORE, httpClient.authorizeRequest.getFollowRedirects());

    var tokenRequest = assertInstanceOf(HttpContentRequest.class, httpClient.tokenRequest);
    var content = tokenRequest.getContent();
    assertNotNull(content);
    var tokenForm = parseForm(content.getAsString());
    assertEquals(MsaConstants.JAVA_TITLE_ID, tokenForm.get("client_id"));
    assertEquals(MsaConstants.SCOPE_TITLE_AUTH, tokenForm.get("scope"));
    assertEquals(DESKTOP_REDIRECT_URI, tokenForm.get("redirect_uri"));
    assertEquals("authorization_code", tokenForm.get("grant_type"));
    assertEquals("auth-code", tokenForm.get("code"));
  }

  private static Map<String, String> parseForm(String query) {
    return List.of(query.split("&")).stream()
      .map(entry -> entry.split("=", 2))
      .collect(Collectors.toMap(
        entry -> decode(entry[0]),
        entry -> entry.length == 2 ? decode(entry[1]) : ""));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static final class CapturingHttpClient extends HttpClient {
    private HttpRequest authorizeRequest;
    private HttpRequest tokenRequest;

    @Override
    public HttpResponse execute(HttpRequest request) {
      authorizeRequest = request;
      return new HttpResponse(
        request.getURL(),
        302,
        new byte[0],
        Map.of("Location", List.of(DESKTOP_REDIRECT_URI + "?code=auth-code&lc=1033")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HttpRequest & HttpResponseHandler<R>, R> R executeAndHandle(T requestAndHandler) {
      tokenRequest = requestAndHandler;
      return (R) new MsaToken(System.currentTimeMillis() + 60_000, "access-token", "refresh-token");
    }
  }
}
