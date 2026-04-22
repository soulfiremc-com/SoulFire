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

import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.proxy.SFProxy;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.handler.codec.raknet.client.RakClientProxyRouteHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class NettyHelper {
  public static final String PROXY_NAME = "sf_proxy";

  private NettyHelper() {
  }

  public static void addProxy(SFProxy proxy, Channel channel, boolean isBedrock) {
    var pipeline = channel.pipeline();
    switch (proxy.type()) {
      case HTTP -> {
        if (proxy.username() != null && proxy.password() != null) {
          pipeline.addLast(PROXY_NAME, new HttpProxyHandler(proxy.address(), proxy.username(), proxy.password()));
        } else {
          pipeline.addLast(PROXY_NAME, new HttpProxyHandler(proxy.address()));
        }
      }
      case SOCKS4 -> {
        if (proxy.username() != null) {
          pipeline.addLast(PROXY_NAME, new Socks4ProxyHandler(proxy.address(), proxy.username()));
        } else {
          pipeline.addLast(PROXY_NAME, new Socks4ProxyHandler(proxy.address()));
        }
      }
      case SOCKS5 -> {
        if (isBedrock) {
          if (channel instanceof RakClientChannel rakChannel) {
            rakChannel.rakPipeline().addAfter(RakClientProxyRouteHandler.NAME, PROXY_NAME, new Socks5UdpRelayHandler(
              (InetSocketAddress) proxy.address(), proxy.username(), proxy.password()));
          } else {
            throw new IllegalStateException("Expected RakClientChannel for Bedrock connection, but got: " + channel.getClass());
          }
        } else if (proxy.username() != null && proxy.password() != null) {
          pipeline.addLast(PROXY_NAME, new Socks5ProxyHandler(proxy.address(), proxy.username(), proxy.password()));
        } else {
          pipeline.addLast(PROXY_NAME, new Socks5ProxyHandler(proxy.address()));
        }
      }
      default -> throw new UnsupportedOperationException("Unsupported proxy type: " + proxy.type());
    }
  }

  public static void addRunnableWrapper(String prefix, SoulFireScheduler.RunnableWrapper runnableWrapper, Channel channel) {
    var pipeline = channel.pipeline();
    pipeline.addLast(prefix + "sf_runnable_wrapper", new ChannelDuplexHandler() {
      // Inbound
      @Override
      public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelRegistered(ctx));
      }

      @Override
      public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelUnregistered(ctx));
      }

      @Override
      public void channelActive(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelActive(ctx));
      }

      @Override
      public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelInactive(ctx));
      }

      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelRead(ctx, msg));
      }

      @Override
      public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelReadComplete(ctx));
      }

      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.userEventTriggered(ctx, evt));
      }

      @Override
      public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.channelWritabilityChanged(ctx));
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.exceptionCaught(ctx, cause));
      }

      // Outbound
      @Override
      public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.bind(ctx, localAddress, promise));
      }

      @Override
      public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.connect(ctx, remoteAddress, localAddress, promise));
      }

      @Override
      public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.disconnect(ctx, promise));
      }

      @Override
      public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.close(ctx, promise));
      }

      @Override
      public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.deregister(ctx, promise));
      }

      @Override
      public void read(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.read(ctx));
      }

      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.write(ctx, msg, promise));
      }

      @Override
      public void flush(ChannelHandlerContext ctx) throws Exception {
        runnableWrapper.runWrappedWithException(() -> super.flush(ctx));
      }
    });
  }
}
