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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// Owns the transport resources used by one bot or proxy request.
/// Unix proxies use an authenticated loopback SOCKS5 endpoint. The endpoint authenticates
/// its owner and negotiates the configured upstream protocol and credentials over Unix.
public final class ProxyTransport implements AutoCloseable {
  private final @Nullable SFProxy proxy;
  private final @Nullable UnixBridge bridge;

  private ProxyTransport(@Nullable SFProxy proxy, @Nullable UnixBridge bridge) {
    this.proxy = proxy;
    this.bridge = bridge;
  }

  public static ProxyTransport open(@Nullable SFProxy proxy) {
    if (proxy == null || !(proxy.address() instanceof UnixDomainSocketAddress address)) {
      return new ProxyTransport(proxy, null);
    }
    try {
      var bridge = new UnixBridge(proxy);
      return new ProxyTransport(new SFProxy(ProxyType.SOCKS5, bridge.address(), bridge.username, bridge.password), bridge);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to open Unix proxy transport for " + address.getPath(), exception);
    }
  }

  public @Nullable SFProxy proxy() {
    return proxy;
  }

  /// Cancellation and exceptional completion close the route even if the worker is still running.
  public static <T> CompletableFuture<T> supplyAsync(@Nullable SFProxy proxy, Executor executor, Operation<T> operation) {
    var result = new CompletableFuture<T>();
    var owned = new AtomicReference<ProxyTransport>();
    var admission = new Object();
    result.whenComplete((_, _) -> {
      ProxyTransport transport;
      synchronized (admission) {
        transport = owned.getAndSet(null);
      }
      if (transport != null) transport.close();
    });
    try {
      executor.execute(() -> {
        try {
          ProxyTransport transport;
          synchronized (admission) {
            if (result.isDone()) return;
            transport = open(proxy);
            owned.set(transport);
          }
          T value;
          try (transport) {
            if (result.isDone()) return;
            value = operation.execute(transport.proxy());
          }
          result.complete(value);
        } catch (Throwable throwable) {
          result.completeExceptionally(throwable);
        }
      });
    } catch (RuntimeException exception) {
      result.completeExceptionally(exception);
    }
    return result;
  }

  @Override
  public void close() {
    if (bridge != null) bridge.close();
  }

  @FunctionalInterface
  public interface Operation<T> {
    T execute(@Nullable SFProxy proxy) throws Exception;
  }

  @Slf4j
  private static final class UnixBridge implements AutoCloseable {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_AUTHENTICATING = 64;
    private final SFProxy upstream;
    private final UnixDomainSocketAddress destination;
    private final String username = UUID.randomUUID().toString();
    private final String password = UUID.randomUUID().toString();
    private final ServerSocket listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Connection> connections = new HashSet<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger authenticating = new AtomicInteger();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    private UnixBridge(SFProxy upstream) throws IOException {
      this.upstream = upstream;
      this.destination = (UnixDomainSocketAddress) upstream.address();
      listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      executor.execute(this::accept);
    }

    private InetSocketAddress address() {
      return new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort());
    }

    private void accept() {
      while (!closing.get()) {
        try {
          var tcp = listener.accept();
          if (authenticating.incrementAndGet() > MAX_AUTHENTICATING) {
            authenticating.decrementAndGet();
            closeSocket(tcp);
            continue;
          }
          SocketChannel unix;
          try {
            unix = SocketChannel.open(StandardProtocolFamily.UNIX);
          } catch (Throwable throwable) {
            closeSocket(tcp);
            authenticating.decrementAndGet();
            throw throwable;
          }
          var connection = new Connection(tcp, unix);
          synchronized (connections) {
            if (!closing.get()) connections.add(connection);
          }
          if (closing.get()) {
            connection.close();
            continue;
          }
          try {
            executor.execute(connection::connect);
          } catch (RejectedExecutionException exception) {
            connection.close();
          }
        } catch (IOException | UnsupportedOperationException exception) {
          if (!closing.get()) log.debug("Unix proxy transport failed for {}", destination.getPath(), exception);
        }
      }
    }

    @Override
    public void close() {
      if (!closing.compareAndSet(false, true)) {
        terminated.join();
        return;
      }
      try {
        closeSocket(listener);
        List<Connection> pending;
        synchronized (connections) {
          pending = List.copyOf(connections);
        }
        pending.forEach(Connection::close);
        executor.close();
      } finally {
        terminated.complete(null);
      }
    }

    private static void closeSocket(Closeable socket) {
      try {
        socket.close();
      } catch (IOException exception) {
        log.debug("Error closing Unix proxy transport socket", exception);
      }
    }

    private final class Connection implements AutoCloseable {
      private final Socket tcp;
      private final SocketChannel unix;
      private final AtomicBoolean closed = new AtomicBoolean();
      private final AtomicInteger directions = new AtomicInteger(2);
      private final AtomicBoolean authenticationPending = new AtomicBoolean(true);

      private Connection(Socket tcp, SocketChannel unix) {
        this.tcp = tcp;
        this.unix = unix;
      }

      private void connect() {
        try {
          tcp.setSoTimeout(5_000);
          UnixProxyHandshake.connect(tcp, unix, upstream, username, password);
          releaseAuthenticationSlot();
          tcp.setSoTimeout(0);
          if (closed.get()) return;
          executor.execute(this::readUnix);
          var input = tcp.getInputStream();
          var buffer = ByteBuffer.allocate(BUFFER_SIZE);
          int read;
          while ((read = input.read(buffer.array())) != -1) {
            buffer.clear().limit(read);
            while (buffer.hasRemaining()) unix.write(buffer);
          }
          unix.shutdownOutput();
        } catch (IOException | RejectedExecutionException exception) {
          if (!closed.get()) log.debug("Unix proxy connection failed for {}", destination.getPath(), exception);
          close();
        } finally {
          finishDirection();
        }
      }

      private void readUnix() {
        try {
          var output = tcp.getOutputStream();
          var buffer = ByteBuffer.allocate(BUFFER_SIZE);
          int read;
          while ((read = unix.read(buffer)) != -1) {
            output.write(buffer.array(), 0, read);
            buffer.clear();
          }
          tcp.shutdownOutput();
        } catch (IOException exception) {
          close();
        } finally {
          finishDirection();
        }
      }

      private void finishDirection() {
        if (directions.decrementAndGet() == 0) close();
      }

      private void releaseAuthenticationSlot() {
        if (authenticationPending.compareAndSet(true, false)) authenticating.decrementAndGet();
      }

      @Override
      public void close() {
        if (!closed.compareAndSet(false, true)) return;
        releaseAuthenticationSlot();
        closeSocket(tcp);
        closeSocket(unix);
        synchronized (connections) {
          connections.remove(this);
        }
      }
    }
  }
}
