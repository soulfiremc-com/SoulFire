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
package com.soulfiremc.server.util;

import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.server.proxy.SFProxy;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.checkerframework.checker.nullness.qual.Nullable;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.net.URL;
import java.time.Duration;

public final class ReactorHttpHelper {
  private ReactorHttpHelper() {}

  public static URL createURL(String url) {
    try {
      return URI.create(url).toURL();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static HttpClient createReactorClient(
    @Nullable SFProxy proxyData, boolean withBody) {
    var base =
      HttpClient.create()
        .compress(true)
        .responseTimeout(Duration.ofSeconds(5))
        .headers(
          h -> {
            h.set(HttpHeaderNames.ACCEPT, "application/json;q=1.0, text/plain;q=0.9, */*;q=0.5");
            if (withBody) {
              h.set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            }

            h.set(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US,en");
            h.set(HttpHeaderNames.USER_AGENT, "SoulFire/" + BuildData.VERSION);
          });

    return proxyData == null
      ? base
      : base.proxy(
      p -> {
        var spec =
          p.type(
              switch (proxyData.type()) {
                case HTTP -> ProxyProvider.Proxy.HTTP;
                case SOCKS4 -> ProxyProvider.Proxy.SOCKS4;
                case SOCKS5 -> ProxyProvider.Proxy.SOCKS5;
              })
            .socketAddress(proxyData.address())
            .nonProxyHosts("localhost")
            .connectTimeoutMillis(20_000);

        if (proxyData.username() != null) {
          spec.username(proxyData.username());
          spec.password(_ -> proxyData.password() == null ? "" : proxyData.password());
        }
      });
  }
}
