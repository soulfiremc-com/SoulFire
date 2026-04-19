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
import net.lenni0451.commons.httpclient.requests.impl.GetRequest;
import net.lenni0451.commons.httpclient.utils.URLWrapper;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.request.MsaAuthCodeTokenRequest;
import net.raphimc.minecraftauth.msa.service.MsaAuthService;

import java.io.IOException;
import java.util.Map;

/// MsaAuthService implementation that exchanges Microsoft Live browser cookies for an MsaToken.
/// Uses prompt=none to silently obtain an authorization code from existing session cookies,
/// then exchanges the code for tokens via the standard MsaAuthCodeTokenRequest.
public class CookieMsaAuthService extends MsaAuthService {
  private final String cookieHeader;

  public CookieMsaAuthService(HttpClient httpClient, MsaApplicationConfig applicationConfig, String cookieHeader) {
    super(httpClient, applicationConfig);
    this.cookieHeader = cookieHeader;
  }

  @Override
  public MsaToken acquireToken() throws IOException {
    var authorizeUrl = URLWrapper.ofURL(applicationConfig.getEnvironment().getAuthorizeUrl())
      .wrapQueryParameters()
      .addParameters(applicationConfig.getAuthCodeParameters())
      .addParameters(Map.of("prompt", "none"))
      .apply()
      .toURL();

    var request = new GetRequest(authorizeUrl);
    request.setHeader("Cookie", cookieHeader);
    request.setFollowRedirects(false);

    var response = httpClient.execute(request);
    var location = response.getFirstHeader("Location")
      .orElseThrow(() -> new IOException("Missing authorize redirect location"));

    var code = URLWrapper.ofURI(location)
      .wrapQueryParameters()
      .getFirstValue("code")
      .orElseThrow(() -> new IOException("Missing authorization code in redirect"));

    return httpClient.executeAndHandle(
      new MsaAuthCodeTokenRequest(applicationConfig, code));
  }
}
