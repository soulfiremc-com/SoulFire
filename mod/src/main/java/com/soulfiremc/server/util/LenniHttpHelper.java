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

import com.soulfiremc.server.proxy.SFProxy;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.executor.extra.ReactorNettyExecutor;
import net.lenni0451.commons.httpclient.proxy.ProxyHandler;
import net.lenni0451.commons.httpclient.proxy.ProxyType;
import net.raphimc.minecraftauth.MinecraftAuth;
import org.checkerframework.checker.nullness.qual.Nullable;

@Slf4j
public final class LenniHttpHelper {
  private LenniHttpHelper() {}

  public static HttpClient client(@Nullable SFProxy proxyData) {
    return MinecraftAuth.createHttpClient()
      .setProxyHandler(proxyData == null ? new ProxyHandler() : new ProxyHandler(switch (proxyData.type()) {
        case HTTP -> ProxyType.HTTP;
        case SOCKS4 -> ProxyType.SOCKS4;
        case SOCKS5 -> ProxyType.SOCKS5;
      }, proxyData.address(), proxyData.username(), proxyData.username() != null && proxyData.password() == null ? "" : proxyData.password()))
      .setExecutor(ReactorNettyExecutor::new);
  }
}
