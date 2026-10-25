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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
final class Socks5UdpRelayHandlerIT {
  private static final InetSocketAddress DESTINATION = new InetSocketAddress("127.0.0.1", 19132);
  private static final byte[] REQUEST = {7, 13, 29, 41};
  private static final byte[] RESPONSE = {43, 31, 17, 11};

  private static Stream<Arguments> credentials() {
    return Stream.of(
      Arguments.of(null, null),
      Arguments.of("proxy-user", null),
      Arguments.of("proxy-user", ""),
      Arguments.of("proxy-user", "proxy-password")
    );
  }

  @ParameterizedTest
  @MethodSource("credentials")
  void authenticatesAndRelaysDatagrams(@Nullable String username, @Nullable String password) throws Exception {
    try (var session = new ProxySession(username, password)) {
      session.readAssociationRequest(username, password);
      session.replyToAssociation(0);
      assertTrue(session.connection.await(5, TimeUnit.SECONDS));
      assertTrue(session.connection.isSuccess(), () -> String.valueOf(session.connection.cause()));
      session.channel.writeAndFlush(Unpooled.wrappedBuffer(REQUEST)).sync();
      var packet = new DatagramPacket(new byte[256], 256);
      session.relay.receive(packet);
      var received = new DataInputStream(new ByteArrayInputStream(packet.getData(), 0, packet.getLength()));
      assertEquals(0, received.readUnsignedShort());
      assertEquals(0, received.readUnsignedByte());
      assertEquals(1, received.readUnsignedByte());
      assertArrayEquals(DESTINATION.getAddress().getAddress(), received.readNBytes(4));
      assertEquals(DESTINATION.getPort(), received.readUnsignedShort());
      assertArrayEquals(REQUEST, received.readAllBytes());
      var response = new byte[10 + RESPONSE.length];
      System.arraycopy(packet.getData(), 0, response, 0, 10);
      System.arraycopy(RESPONSE, 0, response, 10, RESPONSE.length);
      session.relay.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
      assertArrayEquals(RESPONSE, session.received.get(5, TimeUnit.SECONDS));
      session.channel.close().sync();
      session.assertControlClosed();
    }
  }

  @Test
  void closesControlSocketWhenUnboundDatagramChannelCloses() throws Exception {
    try (var session = new ProxySession(null, null)) {
      session.readAssociationRequest(null, null);
      assertFalse(session.channel.isActive());
      session.channel.close().sync();
      session.assertControlClosed();
      assertTrue(session.connection.await(5, TimeUnit.SECONDS));
      assertFalse(session.connection.isSuccess());
    }
  }

  @Test
  void closesControlSocketWhenPendingConnectionIsCanceled() throws Exception {
    try (var session = new ProxySession(null, null)) {
      session.readAssociationRequest(null, null);
      assertTrue(session.connection.cancel(false));
      session.assertControlClosed();
      assertTrue(session.channel.closeFuture().await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void closesControlSocketWhenAssociationIsRejected() throws Exception {
    try (var session = new ProxySession(null, null)) {
      session.readAssociationRequest(null, null);
      session.replyToAssociation(7);
      assertTrue(session.connection.await(5, TimeUnit.SECONDS));
      assertFalse(session.connection.isSuccess());
      session.assertControlClosed();
      assertTrue(session.channel.closeFuture().await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void closesDatagramChannelWhenEstablishedControlConnectionCloses() throws Exception {
    try (var session = new ProxySession(null, null)) {
      session.readAssociationRequest(null, null);
      session.replyToAssociation(0);
      assertTrue(session.connection.await(5, TimeUnit.SECONDS));
      assertTrue(session.connection.isSuccess());
      session.control().close();
      assertTrue(session.channel.closeFuture().await(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void establishesRakNetSessionAndExchangesReliableDataThroughRelay(boolean malformedReplies) throws Exception {
    var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    var request = new byte[]{(byte) 0xfe, 7, 13, 29};
    var response = new byte[]{(byte) 0xfe, 31, 17, 11};
    var received = new CompletableFuture<byte[]>();
    try {
      var server = new ServerBootstrap().group(group)
        .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
        .childHandler(new ChannelInitializer<RakChildChannel>() {
          @Override
          protected void initChannel(RakChildChannel channel) {
            channel.pipeline().addLast(new SimpleChannelInboundHandler<RakMessage>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                var bytes = new byte[message.content().readableBytes()];
                message.content().readBytes(bytes);
                received.complete(bytes);
                ctx.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(response)));
              }
            });
          }
        }).bind("127.0.0.1", 0).sync().channel();
      try (var session = new ProxySession("proxy-user", null, (InetSocketAddress) server.localAddress());
           var upstream = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
        session.readAssociationRequest("proxy-user", null);
        var running = new AtomicBoolean(true);
        var client = new AtomicReference<SocketAddress>();
        var injected = new AtomicBoolean();
        var outbound = session.worker.submit(() -> {
          pump(session.relay, running, packet -> {
            client.set(packet.getSocketAddress());
            var input = new DataInputStream(new ByteArrayInputStream(packet.getData(), packet.getOffset(), packet.getLength()));
            assertArrayEquals(new byte[]{0, 0, 0, 1, 127, 0, 0, 1}, input.readNBytes(8));
            assertEquals(((InetSocketAddress) server.localAddress()).getPort(), input.readUnsignedShort());
            upstream.send(new DatagramPacket(packet.getData(), 10, packet.getLength() - 10, server.localAddress()));
          });
          return null;
        });
        var inbound = session.worker.submit(() -> {
          pump(upstream, running, packet -> {
            var bytes = new byte[10 + packet.getLength()];
            bytes[3] = 1;
            System.arraycopy(new byte[]{127, 0, 0, 1}, 0, bytes, 4, 4);
            var port = ((InetSocketAddress) server.localAddress()).getPort();
            bytes[8] = (byte) (port >> 8);
            bytes[9] = (byte) port;
            System.arraycopy(packet.getData(), packet.getOffset(), bytes, 10, packet.getLength());
            if (malformedReplies && injected.compareAndSet(false, true)) {
              session.relay.send(new DatagramPacket(new byte[]{0, 0, 0, 1}, 4, client.get()));
              var fragment = bytes.clone();
              fragment[2] = 1;
              session.relay.send(new DatagramPacket(fragment, fragment.length, client.get()));
              var reserved = bytes.clone();
              reserved[0] = 1;
              session.relay.send(new DatagramPacket(reserved, reserved.length, client.get()));
            }
            session.relay.send(new DatagramPacket(bytes, bytes.length, client.get()));
          });
          return null;
        });
        try {
          session.replyToAssociation(0);
          assertTrue(session.connection.await(5, TimeUnit.SECONDS));
          assertTrue(session.connection.isSuccess(), () -> String.valueOf(session.connection.cause()));
          session.channel.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(request))).sync();
          assertArrayEquals(request, received.get(5, TimeUnit.SECONDS));
          assertArrayEquals(response, session.received.get(5, TimeUnit.SECONDS));
          session.channel.close().sync();
          session.assertControlClosed();
        } finally {
          running.set(false);
          upstream.close();
          session.relay.close();
          outbound.get(5, TimeUnit.SECONDS);
          inbound.get(5, TimeUnit.SECONDS);
        }
      } finally {
        server.close().sync();
      }
    } finally {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  private static void pump(DatagramSocket socket, AtomicBoolean running, DatagramConsumer consumer) throws IOException {
    socket.setSoTimeout(1_000);
    while (running.get()) {
      try {
        var packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        consumer.accept(packet);
      } catch (SocketTimeoutException ignored) {
        // Check whether the other direction has finished.
      } catch (SocketException exception) {
        if (running.get()) {
          throw exception;
        }
      }
    }
  }

  @FunctionalInterface
  private interface DatagramConsumer {
    void accept(DatagramPacket packet) throws IOException;
  }

  private static final class ProxySession implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    private final DatagramSocket relay = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final Future<Socket> accepted;
    private final CompletableFuture<byte[]> received = new CompletableFuture<>();
    private final Channel channel;
    private final ChannelFuture connection;

    private ProxySession(@Nullable String username, @Nullable String password) throws Exception {
      this(username, password, null);
    }

    private ProxySession(@Nullable String username, @Nullable String password, @Nullable InetSocketAddress rakDestination) throws Exception {
      listener.setSoTimeout(5_000);
      relay.setSoTimeout(5_000);
      accepted = worker.submit(() -> {
        var socket = listener.accept();
        socket.setSoTimeout(5_000);
        return socket;
      });
      var bootstrap = new Bootstrap().group(group);
      if (rakDestination == null) {
        bootstrap.channel(NioDatagramChannel.class);
      } else {
        bootstrap.channelFactory(RakChannelFactory.client(NioDatagramChannel.class));
        bootstrap.option(RakChannelOption.RAK_CONNECT_TIMEOUT, 5_000L);
      }
      channel = bootstrap.handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel channel) {
          var proxy = new SFProxy(ProxyType.SOCKS5, new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()), username, password);
          if (channel instanceof RakClientChannel rakChannel) {
            NettyHelper.addProxy(proxy, rakChannel, true);
          } else {
            channel.pipeline().addLast(new Socks5UdpRelayHandler((InetSocketAddress) proxy.address(), proxy.username(), proxy.password()));
          }
          channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBufHolder>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBufHolder packet) {
              var bytes = new byte[packet.content().readableBytes()];
              packet.content().readBytes(bytes);
              received.complete(bytes);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
              received.completeExceptionally(cause);
              ctx.close();
            }
          });
        }
      }).register().sync().channel();
      connection = channel.connect(rakDestination == null ? DESTINATION : rakDestination);
    }

    private Socket control() throws Exception {
      return accepted.get(5, TimeUnit.SECONDS);
    }

    private void readAssociationRequest(@Nullable String username, @Nullable String password) throws Exception {
      var input = new DataInputStream(control().getInputStream());
      var output = control().getOutputStream();
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
      assertArrayEquals(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}, input.readNBytes(10));
    }

    private void replyToAssociation(int status) throws Exception {
      var port = relay.getLocalPort();
      control().getOutputStream().write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, (byte) (port >> 8), (byte) port});
      control().getOutputStream().flush();
    }

    private void assertControlClosed() throws Exception {
      control().setSoTimeout(2_000);
      assertEquals(-1, control().getInputStream().read());
    }

    @Override
    public void close() throws Exception {
      channel.close().sync();
      connection.cancel(false);
      relay.close();
      listener.close();
      worker.close();
      if (accepted.isDone() && !accepted.isCancelled()) {
        try {
          accepted.get().close();
        } catch (ExecutionException ignored) {
          // Closing the listener interrupts an outstanding accept during cleanup.
        }
      }
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  private static String readSocksString(DataInputStream input) throws IOException {
    return new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII);
  }
}
