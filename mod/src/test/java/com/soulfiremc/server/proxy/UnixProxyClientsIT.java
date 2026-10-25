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

import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.soulfiremc.server.util.LenniHttpHelper;
import com.soulfiremc.server.util.ReactorHttpHelper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock("java.net.Authenticator")
@Timeout(20)
final class UnixProxyClientsIT {
  @TempDir
  private Path directory;

  enum Client {
    JDK, REACTOR, AUTH
  }

  private static Stream<Arguments> clients() {
    return Stream.of(ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.SOCKS4).flatMap(type ->
      Stream.of(Client.values()).flatMap(client -> Stream.concat(
        Stream.of(Arguments.of(type, client, null, null), Arguments.of(type, client, "proxy-user", null)),
        type == ProxyType.SOCKS4 ? Stream.empty() : Stream.of(Arguments.of(type, client, "proxy-user", "proxy-password")))));
  }

  @ParameterizedTest
  @MethodSource("clients")
  void sendsAuthenticatedHttpThroughUnixProxy(ProxyType type, Client kind, @Nullable String username, @Nullable String password) throws Exception {
    var previous = Authenticator.getDefault();
    Authenticator.setDefault(new ProxyAuthenticator());
    try (var server = new ProxyServer(directory.resolve("proxy.sock"), type, username, password);
         var transport = ProxyTransport.open(new SFProxy(type, server.address, username, password))) {
      var proxy = transport.proxy();
      var uri = URI.create("http://session.invalid/profile");
      switch (kind) {
        case JDK -> {
          var client = MinecraftClient.unauthenticated(ProxyAuthenticator.createProxy(proxy));
          assertEquals("proxied", client.get(uri.toURL(), String.class));
        }
        case REACTOR -> assertEquals("\"proxied\"", ReactorHttpHelper.createReactorClient(proxy, false)
          .get().uri(uri).responseSingle((_, body) -> body.asString()).block(Duration.ofSeconds(5)));
        case AUTH -> {
          var client = LenniHttpHelper.client(proxy).setReadTimeout(2_000).setConnectTimeout(2_000);
          client.getRetryConfig().setMaxConnectRetries(0);
          assertEquals("\"proxied\"", client.execute(client.get(uri.toString())).getContent().getAsString());
        }
      }
    } finally {
      Authenticator.setDefault(previous);
    }
  }

  private static final class ProxyServer implements AutoCloseable {
    private final UnixDomainSocketAddress address;
    private final ServerSocketChannel listener;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<SocketChannel> sockets = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Future<?>> tasks = new ConcurrentLinkedQueue<>();
    private final ProxyType type;
    private final @Nullable String username;
    private final String password;
    private volatile boolean closed;

    private ProxyServer(Path path, ProxyType type, @Nullable String username, @Nullable String password) throws IOException {
      this.type = type;
      this.username = username;
      this.password = password == null ? "" : password;
      address = UnixDomainSocketAddress.of(path);
      listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address);
      executor.execute(() -> {
        while (!closed) {
          try {
            var socket = listener.accept();
            sockets.add(socket);
            if (closed) {
              socket.close();
              continue;
            }
            tasks.add(executor.submit(() -> {
              serve(socket);
              return null;
            }));
          } catch (IOException exception) {
            if (!closed) throw new java.io.UncheckedIOException(exception);
          }
        }
      });
    }

    private void serve(SocketChannel socket) throws IOException {
      try (socket) {
        var input = new DataInputStream(Channels.newInputStream(socket));
        var output = Channels.newOutputStream(socket);
        if (type == ProxyType.HTTP) {
          var request = readHttpRequest(input);
          var supplied = request.headers().get("proxy-authorization");
          var expected = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
          if (username == null ? supplied != null : !expected.equals(supplied)) {
            output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"qa\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return;
          }
          if (request.line().startsWith("CONNECT ")) {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            readHttpRequest(input);
          }
        } else if (type == ProxyType.SOCKS4) {
          negotiateSocks4(input, output);
          readHttpRequest(input);
        } else {
          negotiateSocks(input, output);
          readHttpRequest(input);
        }
        var body = "\"proxied\"".getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
          + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
      } finally {
        sockets.remove(socket);
      }
    }

    private void negotiateSocks(DataInputStream input, OutputStream output) throws IOException {
      assertEquals(5, input.readUnsignedByte());
      var methods = input.readNBytes(input.readUnsignedByte());
      var method = username == null ? 0 : 2;
      assertTrue(java.util.stream.IntStream.range(0, methods.length).anyMatch(index -> methods[index] == method));
      output.write(new byte[]{5, (byte) method});
      output.flush();
      if (username != null) {
        assertEquals(1, input.readUnsignedByte());
        assertEquals(username, readSocksString(input));
        assertEquals(password, readSocksString(input));
        output.write(new byte[]{1, 0});
        output.flush();
      }
      assertEquals(5, input.readUnsignedByte());
      assertEquals(1, input.readUnsignedByte());
      input.readUnsignedByte();
      input.skipNBytes(switch (input.readUnsignedByte()) {
        case 1 -> 4;
        case 3 -> input.readUnsignedByte();
        case 4 -> 16;
        default -> throw new IOException("Unsupported proxy destination");
      });
      assertEquals(80, input.readUnsignedShort());
      output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
      output.flush();
    }

    private void negotiateSocks4(DataInputStream input, OutputStream output) throws IOException {
      assertEquals(4, input.readUnsignedByte());
      assertEquals(1, input.readUnsignedByte());
      assertEquals(80, input.readUnsignedShort());
      var address = input.readNBytes(4);
      assertEquals(username == null ? "" : username, readCString(input));
      if (java.util.Arrays.equals(address, new byte[]{0, 0, 0, 1})) {
        assertEquals("session.invalid", readCString(input));
      }
      output.write(new byte[]{0, 90, 0, 0, 127, 0, 0, 1});
      output.flush();
    }

    @Override
    public void close() throws Exception {
      closed = true;
      listener.close();
      for (var socket : sockets) socket.close();
      executor.close();
      for (var task : tasks) task.get();
    }
  }

  private record HttpRequest(String line, Map<String, String> headers) {}

  private static HttpRequest readHttpRequest(DataInputStream input) throws IOException {
    var line = readLine(input);
    var headers = new HashMap<String, String>();
    String header;
    while (!(header = readLine(input)).isEmpty()) {
      var separator = header.indexOf(':');
      headers.put(header.substring(0, separator).toLowerCase(Locale.ROOT), header.substring(separator + 1).trim());
    }
    return new HttpRequest(line, headers);
  }

  private static String readSocksString(DataInputStream input) throws IOException {
    return new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII);
  }

  private static String readCString(DataInputStream input) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    for (var i = 0; i <= 255; i++) {
      var next = input.readUnsignedByte();
      if (next == 0) return bytes.toString(StandardCharsets.UTF_8);
      bytes.write(next);
    }
    throw new IOException("Unterminated SOCKS4 string");
  }

  private static String readLine(DataInputStream input) throws IOException {
    var line = new StringBuilder();
    int next;
    while ((next = input.read()) != -1) {
      if (next == '\n') return line.toString();
      if (next != '\r') line.append((char) next);
    }
    throw new IOException("Incomplete HTTP request");
  }
}
