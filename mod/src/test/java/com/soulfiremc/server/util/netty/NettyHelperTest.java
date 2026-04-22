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
package com.soulfiremc.server.util.netty;

import com.soulfiremc.server.proxy.ProxyType;
import com.soulfiremc.server.proxy.SFProxy;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.handler.codec.raknet.ProxyInboundRouter;
import org.cloudburstmc.netty.handler.codec.raknet.client.RakClientProxyRouteHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class NettyHelperTest {
  @Test
  void addProxyPlacesBedrockSocks5RelayBeforeInboundProxyRouter() {
    var proxy = new SFProxy(ProxyType.SOCKS5, new InetSocketAddress("127.0.0.1", 1080), "user", "pass");
    var parentChannel = new NioDatagramChannel();
    try {
      var rakChannel = new RakClientChannel(parentChannel);

      NettyHelper.addProxy(proxy, rakChannel, true);

      var pipelineNames = parentChannel.pipeline().names();
      var relayIndex = pipelineNames.indexOf(NettyHelper.PROXY_NAME);
      var routeIndex = pipelineNames.indexOf(RakClientProxyRouteHandler.NAME);
      var routerIndex = pipelineNames.indexOf(ProxyInboundRouter.NAME);

      assertTrue(relayIndex > routeIndex, "Relay handler should run after the RakNet route adapter");
      assertTrue(relayIndex < routerIndex, "Relay handler must unwrap inbound packets before they cross into the proxied pipeline");
    } finally {
      parentChannel.unsafe().closeForcibly();
    }
  }
}
