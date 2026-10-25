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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.ProxyHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NettyProxyAuthenticationIT {
  private static final byte[] REQUEST = {7, 13, 29, 41};
  private static final byte[] RESPONSE = {43, 31, 17, 11};
  private static final InetSocketAddress DESTINATION = new InetSocketAddress("127.0.0.1", 12345);

  private static Stream<Arguments> credentials() {
    return Stream.of(ProxyType.HTTP, ProxyType.SOCKS5).flatMap(type -> Stream.of(
      Arguments.of(type, null, null),
      Arguments.of(type, "proxy-user", null),
      Arguments.of(type, "proxy-user", ""),
      Arguments.of(type, "proxy-user", "proxy-password")
    ));
  }

  @ParameterizedTest
  @MethodSource("credentials")
  @Timeout(20)
  void negotiatesConfiguredCredentialsBeforeTunneling(ProxyType type, @Nullable String username, @Nullable String password) throws Exception {
    try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
         var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      listener.setSoTimeout(5_000);
      var served = worker.submit(() -> {
        try (var socket = listener.accept()) {
          socket.setSoTimeout(5_000);
          var input = new DataInputStream(socket.getInputStream());
          var output = socket.getOutputStream();
          if (type == ProxyType.HTTP) {
            var request = readHttpLine(input);
            assertEquals("CONNECT " + DESTINATION.getHostString() + ":" + DESTINATION.getPort() + " HTTP/1.1", request);
            var headers = new ArrayList<String>();
            String header;
            while (!(header = readHttpLine(input)).isEmpty()) {
              headers.add(header);
            }
            var authorization = headers.stream().filter(line -> line.regionMatches(true, 0, "proxy-authorization:", 0, 20)).toList();
            if (username == null) {
              assertTrue(authorization.isEmpty());
            } else {
              assertEquals(1, authorization.size());
              var encoded = authorization.getFirst().substring(20).trim();
              assertTrue(encoded.startsWith("Basic "));
              assertEquals(username + ':' + (password == null ? "" : password),
                new String(Base64.getDecoder().decode(encoded.substring(6)), StandardCharsets.UTF_8));
            }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          } else {
            assertEquals(5, input.readUnsignedByte());
            var methods = input.readNBytes(input.readUnsignedByte());
            var method = username == null ? 0 : 2;
            assertTrue(IntStream.range(0, methods.length).anyMatch(index -> methods[index] == method));
            output.write(new byte[]{5, (byte) method});
            output.flush();
            if (username != null) {
              assertEquals(1, input.readUnsignedByte());
              assertEquals(username, readSocksString(input));
              assertEquals(password == null ? "" : password, readSocksString(input));
              output.write(new byte[]{1, 0});
              output.flush();
            }
            assertEquals(5, input.readUnsignedByte());
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(1, input.readUnsignedByte());
            assertArrayEquals(DESTINATION.getAddress().getAddress(), input.readNBytes(4));
            assertEquals(DESTINATION.getPort(), input.readUnsignedShort());
            output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
          }
          output.flush();
          assertArrayEquals(REQUEST, input.readNBytes(REQUEST.length));
          output.write(RESPONSE);
          output.flush();
        }
        return null;
      });
      var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try {
        var received = new CompletableFuture<byte[]>();
        var proxy = new SFProxy(type, new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()), username, password);
        var channel = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel channel) {
            NettyHelper.addProxy(proxy, channel, false);
            channel.pipeline().get(ProxyHandler.class).setConnectTimeoutMillis(5_000);
            channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
              private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

              @Override
              protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                var next = new byte[message.readableBytes()];
                message.readBytes(next);
                bytes.writeBytes(next);
                if (bytes.size() >= RESPONSE.length) {
                  received.complete(bytes.toByteArray());
                }
              }

              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                received.completeExceptionally(cause);
                ctx.close();
              }
            });
          }
        }).connect(DESTINATION).sync().channel();
        try {
          channel.writeAndFlush(Unpooled.wrappedBuffer(REQUEST)).sync();
          assertArrayEquals(RESPONSE, received.get(5, TimeUnit.SECONDS));
          served.get(5, TimeUnit.SECONDS);
        } finally {
          channel.close().sync();
        }
      } finally {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }
    }
  }

  private static String readHttpLine(DataInputStream input) throws IOException {
    var line = new StringBuilder();
    int next;
    while ((next = input.read()) != -1) {
      if (next == '\n') {
        return line.toString();
      }
      if (next != '\r') {
        line.append((char) next);
      }
    }
    throw new IOException("Incomplete proxy request");
  }

  private static String readSocksString(DataInputStream input) throws IOException {
    return new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII);
  }
}
