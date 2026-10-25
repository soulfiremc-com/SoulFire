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
package com.soulfiremc.server.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soulfiremc.server.bot.BotConnection;
import org.jspecify.annotations.Nullable;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

public final class ProxyAuthenticator extends Authenticator {
  // Weak keys use identity: equal endpoints can have different credentials.
  // Do not expire live entries or retain their Proxy keys from the values.
  private static final Cache<Proxy, ProxyAuthenticator> AUTHENTICATORS = Caffeine.newBuilder()
    .weakKeys()
    .build();
  private static final ScopedValue<Proxy> REQUEST_PROXY = ScopedValue.newInstance();
  private final @Nullable SFProxy proxy;

  public ProxyAuthenticator() {
    this.proxy = null;
  }

  private ProxyAuthenticator(SFProxy proxy) {
    this.proxy = proxy;
  }

  public static Proxy createProxy(@Nullable SFProxy proxy) {
    if (proxy == null) {
      return Proxy.NO_PROXY;
    }

    var javaProxy = new Proxy(proxy.type() == ProxyType.HTTP ? Proxy.Type.HTTP : Proxy.Type.SOCKS, proxy.address());
    AUTHENTICATORS.put(javaProxy, new ProxyAuthenticator(proxy));
    return javaProxy;
  }

  public static @Nullable Authenticator forProxy(Proxy proxy) {
    return AUTHENTICATORS.getIfPresent(proxy);
  }

  public static <T, X extends Throwable> T withProxy(Proxy proxy, ScopedValue.CallableOp<T, X> operation) throws X {
    return ScopedValue.where(REQUEST_PROXY, proxy).call(operation);
  }

  @Override
  protected @Nullable PasswordAuthentication getPasswordAuthentication() {
    var requestedProxy = proxy;
    if (requestedProxy == null) {
      if (REQUEST_PROXY.isBound()) {
        var authenticator = AUTHENTICATORS.getIfPresent(REQUEST_PROXY.get());
        requestedProxy = authenticator == null ? null : authenticator.proxy;
      } else {
        requestedProxy = BotConnection.currentOptional().map(BotConnection::transportProxy).orElse(null);
      }
    }

    if (requestedProxy == null || requestedProxy.username() == null
      || !(requestedProxy.address() instanceof InetSocketAddress address)) {
      return null;
    }

    var proxyRequest = switch (requestedProxy.type()) {
      // The JDK's SOCKS5 callback uses the overload whose requestor type is SERVER.
      case SOCKS5 -> "SOCKS5".equalsIgnoreCase(getRequestingProtocol());
      case HTTP -> getRequestorType() == RequestorType.PROXY
        && ("http".equalsIgnoreCase(getRequestingProtocol()) || "https".equalsIgnoreCase(getRequestingProtocol()));
      case SOCKS4 -> false;
    };
    if (!proxyRequest || getRequestingPort() != address.getPort()
      || (!address.getHostString().equalsIgnoreCase(getRequestingHost())
      && !address.getAddress().equals(getRequestingSite()))) {
      return null;
    }

    return new PasswordAuthentication(requestedProxy.username(),
      requestedProxy.password() == null ? new char[0] : requestedProxy.password().toCharArray());
  }
}
