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

import com.soulfiremc.server.util.netty.Socks5UdpRelayHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(20)
final class ProxyTransportIT {
  @TempDir
  private Path directory;

  @Test
  void copiesMultipleBuffersAndPreservesHalfClosureBeforeCompleting() throws Exception {
    var address = UnixDomainSocketAddress.of(directory.resolve("half.sock"));
    var payload = new byte[256 * 1024];
    RandomGeneratorFactory.of("L64X128MixRandom").create(42).nextBytes(payload);
    var endpoint = new AtomicReference<InetSocketAddress>();
    try (var worker = Executors.newVirtualThreadPerTaskExecutor();
         var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address)) {
      var served = worker.submit(() -> {
        try (var socket = acceptProxy(listener)) {
          var received = Channels.newInputStream(socket).readAllBytes();
          assertArrayEquals(payload, received);
          var response = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(received));
          while (response.hasRemaining()) socket.write(response);
          socket.shutdownOutput();
        }
        return null;
      });
      var result = ProxyTransport.supplyAsync(proxy(address), worker, effective -> {
        endpoint.set((InetSocketAddress) effective.address());
        try (var socket = connect(Objects.requireNonNull(effective))) {
          socket.getOutputStream().write(payload);
          socket.shutdownOutput();
          return socket.getInputStream().readAllBytes();
        }
      }).orTimeout(8, TimeUnit.SECONDS);
      assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), result.get(5, TimeUnit.SECONDS));
      served.get(5, TimeUnit.SECONDS);
      assertClosed(endpoint.get());
    }
  }

  @Test
  void cancellationClosesAcceptedSocketsAndListenerWhileWorkerIsRunning() throws Exception {
    var address = UnixDomainSocketAddress.of(directory.resolve("cancel.sock"));
    var endpoint = new AtomicReference<InetSocketAddress>();
    var connected = new CountDownLatch(1);
    try (var worker = Executors.newVirtualThreadPerTaskExecutor();
         var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address)) {
      var accepted = worker.submit(() -> acceptProxy(listener));
      var result = ProxyTransport.supplyAsync(proxy(address), worker, effective -> {
        endpoint.set((InetSocketAddress) effective.address());
        try (var socket = connect(Objects.requireNonNull(effective))) {
          connected.countDown();
          return socket.getInputStream().read();
        }
      }).orTimeout(8, TimeUnit.SECONDS);
      try (var remote = accepted.get(5, TimeUnit.SECONDS)) {
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertTrue(result.cancel(false));
        var eof = worker.submit(() -> remote.read(ByteBuffer.allocate(1)));
        assertEquals(-1, eof.get(5, TimeUnit.SECONDS));
        assertClosed(endpoint.get());
      }
    }
  }

  @Test
  void exceptionalOperationClosesListenerBeforePublishingFailure() throws Exception {
    var endpoint = new AtomicReference<InetSocketAddress>();
    var failure = new IOException();
    try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = ProxyTransport.supplyAsync(proxy(UnixDomainSocketAddress.of(directory.resolve("unused.sock"))), worker, effective -> {
        endpoint.set((InetSocketAddress) effective.address());
        throw failure;
      });
      assertEquals(failure, assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
      assertClosed(endpoint.get());
    }
  }

  @Test
  void cancellationBeforeExecutorAdmissionDoesNotStartTheOperation() {
    var queued = new AtomicReference<Runnable>();
    var calls = new AtomicInteger();
    var result = ProxyTransport.supplyAsync(proxy(UnixDomainSocketAddress.of(directory.resolve("queued.sock"))), queued::set, _ -> calls.incrementAndGet());
    assertTrue(result.cancel(false));
    queued.get().run();
    assertEquals(0, calls.get());
  }

  @Test
  void anonymousAndIncorrectCredentialsNeverOpenTheUnixSocket() throws Exception {
    var address = UnixDomainSocketAddress.of(directory.resolve("private.sock"));
    try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address);
         var route = ProxyTransport.open(proxy(address))) {
      listener.configureBlocking(false);
      var endpoint = (InetSocketAddress) Objects.requireNonNull(route.proxy()).address();
      try (var socket = connect(endpoint)) {
        socket.getOutputStream().write(new byte[]{5, 1, 0});
        assertArrayEquals(new byte[]{5, (byte) 255}, socket.getInputStream().readNBytes(2));
        assertEquals(-1, socket.getInputStream().read());
        assertNull(listener.accept());
      }
      try (var socket = connect(endpoint)) {
        var input = socket.getInputStream();
        var output = socket.getOutputStream();
        output.write(new byte[]{5, 1, 2});
        assertArrayEquals(new byte[]{5, 2}, input.readNBytes(2));
        output.write(new byte[]{1, 1, 'x', 1, 'y'});
        assertArrayEquals(new byte[]{1, 1}, input.readNBytes(2));
        assertEquals(-1, input.read());
        assertNull(listener.accept());
      }
    }
  }

  @Test
  void missingUnixSocketClosesTheClientWithoutDirectFallback() throws Exception {
    InetSocketAddress endpoint;
    try (var route = ProxyTransport.open(proxy(UnixDomainSocketAddress.of(directory.resolve("missing.sock"))))) {
      endpoint = (InetSocketAddress) Objects.requireNonNull(route.proxy()).address();
      assertThrows(IOException.class, () -> connect(Objects.requireNonNull(route.proxy())));
    }
    assertClosed(endpoint);
  }

  @Test
  void negotiatesUnixUdpAssociationAndReleasesItsControlConnection() throws Exception {
    var address = UnixDomainSocketAddress.of(directory.resolve("udp.sock"));
    var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try (var worker = Executors.newVirtualThreadPerTaskExecutor();
         var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address);
         var relay = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
         var transport = ProxyTransport.open(proxy(address))) {
      relay.setSoTimeout(5_000);
      var accepted = worker.submit(listener::accept);
      var received = new CompletableFuture<byte[]>();
      var channel = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(new ChannelInitializer<NioDatagramChannel>() {
        @Override
        protected void initChannel(NioDatagramChannel channel) {
          channel.pipeline().addLast(new Socks5UdpRelayHandler((InetSocketAddress) Objects.requireNonNull(transport.proxy()).address(), transport.proxy().username(), transport.proxy().password()));
          channel.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, io.netty.channel.socket.DatagramPacket packet) {
              var bytes = new byte[packet.content().readableBytes()];
              packet.content().readBytes(bytes);
              received.complete(bytes);
            }
          });
        }
      }).register().sync().channel();
      try {
        var connection = channel.connect(new InetSocketAddress("127.0.0.1", 19132));
        try (var remote = accepted.get(5, TimeUnit.SECONDS);
             var input = new DataInputStream(Channels.newInputStream(remote));
             var output = Channels.newOutputStream(remote)) {
          assertArrayEquals(new byte[]{5, 1, 0}, input.readNBytes(3));
          output.write(new byte[]{5, 0});
          output.flush();
          assertArrayEquals(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}, input.readNBytes(10));
          var port = relay.getLocalPort();
          output.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, (byte) (port >> 8), (byte) port});
          output.flush();
          assertTrue(connection.await(5, TimeUnit.SECONDS));
          assertTrue(connection.isSuccess(), () -> String.valueOf(connection.cause()));
          var payload = new byte[]{7, 13, 29, 41};
          channel.writeAndFlush(Unpooled.wrappedBuffer(payload)).sync();
          var packet = new DatagramPacket(new byte[128], 128);
          relay.receive(packet);
          assertArrayEquals(new byte[]{0, 0, 0, 1, 127, 0, 0, 1, 74, (byte) 188, 7, 13, 29, 41}, java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
          relay.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
          assertArrayEquals(payload, received.get(5, TimeUnit.SECONDS));
          channel.close().sync();
          assertEquals(-1, worker.submit(() -> {
            return input.read();
          }).get(5, TimeUnit.SECONDS));
        }
      } finally {
        channel.close().sync();
      }
    } finally {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  private static SFProxy proxy(UnixDomainSocketAddress address) {
    return new SFProxy(ProxyType.SOCKS5, address, null, null);
  }

  private static SocketChannel acceptProxy(ServerSocketChannel listener) throws IOException {
    var socket = listener.accept();
    try {
      var input = new DataInputStream(Channels.newInputStream(socket));
      var output = Channels.newOutputStream(socket);
      assertArrayEquals(new byte[]{5, 1, 0}, input.readNBytes(3));
      output.write(new byte[]{5, 0});
      output.flush();
      assertArrayEquals(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, 0, 80}, input.readNBytes(10));
      output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
      output.flush();
      return socket;
    } catch (Throwable throwable) {
      socket.close();
      throw throwable;
    }
  }

  private static Socket connect(SFProxy proxy) throws IOException {
    var socket = connect((InetSocketAddress) proxy.address());
    try {
      var input = socket.getInputStream();
      var output = socket.getOutputStream();
      output.write(new byte[]{5, 1, 2});
      output.flush();
      assertArrayEquals(new byte[]{5, 2}, input.readNBytes(2));
      var username = Objects.requireNonNull(proxy.username()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var password = Objects.requireNonNull(proxy.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      output.write(new byte[]{1, (byte) username.length});
      output.write(username);
      output.write(password.length);
      output.write(password);
      output.flush();
      assertArrayEquals(new byte[]{1, 0}, input.readNBytes(2));
      output.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, 0, 80});
      output.flush();
      var reply = input.readNBytes(10);
      if (reply.length != 10 || reply[1] != 0) throw new IOException("Proxy refused the test connection");
      return socket;
    } catch (Throwable throwable) {
      socket.close();
      throw throwable;
    }
  }

  private static Socket connect(InetSocketAddress address) throws IOException {
    var socket = new Socket();
    try {
      socket.connect(address, 2_000);
      socket.setSoTimeout(5_000);
      return socket;
    } catch (Throwable throwable) {
      socket.close();
      throw throwable;
    }
  }

  private static void assertClosed(InetSocketAddress address) {
    assertThrows(IOException.class, () -> {
      try (var socket = connect(address)) {
        socket.getInputStream().read();
      }
    });
  }
}
