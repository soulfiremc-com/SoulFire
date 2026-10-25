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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/// Authenticates the owning client before connecting to the protected Unix socket.
final class UnixProxyHandshake {
  private UnixProxyHandshake() {}

  static void connect(Socket tcp, SocketChannel unix, SFProxy proxy, String username, String password) throws IOException {
    var clientInput = new DataInputStream(tcp.getInputStream());
    var clientOutput = tcp.getOutputStream();
    authenticate(clientInput, clientOutput, username, password);
    var replied = false;
    try {
      if (clientInput.readUnsignedByte() != 5) throw new IOException("Invalid local proxy request");
      var command = clientInput.readUnsignedByte();
      if (clientInput.readUnsignedByte() != 0) throw new IOException("Invalid local proxy request");
      var target = Address.read(clientInput);
      if (command != 1 && command != 3 || command == 3 && proxy.type() != ProxyType.SOCKS5) {
        reply(clientOutput, 7);
        replied = true;
        throw new IOException("Unsupported proxy command");
      }

      unix.connect(proxy.address());
      // These streams borrow the channel; the transport connection owns its lifetime.
      var input = new DataInputStream(Channels.newInputStream(unix));
      var output = Channels.newOutputStream(unix);
      switch (proxy.type()) {
        case SOCKS5 -> {
          var status = socks5(input, output, proxy, command, target, clientOutput);
          replied = true;
          if (status != 0) throw new IOException("Unix SOCKS5 proxy rejected the request: " + status);
        }
        case SOCKS4 -> {
          socks4(input, output, proxy, target);
          reply(clientOutput, 0);
          replied = true;
        }
        case HTTP -> {
          http(input, output, proxy, target);
          reply(clientOutput, 0);
          replied = true;
        }
      }
    } catch (IOException | RuntimeException exception) {
      if (!replied) {
        try {
          reply(clientOutput, 1);
        } catch (IOException ignored) {
          // The client may have canceled while the upstream connection was pending.
        }
      }
      throw exception;
    }
  }

  private static void authenticate(DataInputStream input, OutputStream output, String username, String password) throws IOException {
    if (input.readUnsignedByte() != 5) throw new IOException("Invalid local proxy greeting");
    var methods = read(input, input.readUnsignedByte());
    var passwordOffered = false;
    for (var method : methods) passwordOffered |= method == 2;
    output.write(new byte[]{5, passwordOffered ? (byte) 2 : (byte) 255});
    output.flush();
    if (!passwordOffered) throw new IOException("Local proxy authentication is required");
    if (input.readUnsignedByte() != 1) throw new IOException("Invalid local proxy authentication");
    var suppliedUsername = read(input, input.readUnsignedByte());
    var suppliedPassword = read(input, input.readUnsignedByte());
    var accepted = MessageDigest.isEqual(suppliedUsername, username.getBytes(StandardCharsets.UTF_8))
      & MessageDigest.isEqual(suppliedPassword, password.getBytes(StandardCharsets.UTF_8));
    output.write(new byte[]{1, accepted ? (byte) 0 : (byte) 1});
    output.flush();
    if (!accepted) throw new IOException("Local proxy authentication failed");
  }

  private static int socks5(DataInputStream input, OutputStream output, SFProxy proxy, int command, Address target, OutputStream client) throws IOException {
    var proxyUsername = proxy.username();
    var proxyPassword = proxy.password();
    output.write(proxyUsername == null ? new byte[]{5, 1, 0} : new byte[]{5, 2, 0, 2});
    output.flush();
    if (input.readUnsignedByte() != 5) throw new IOException("Invalid Unix SOCKS5 greeting");
    var method = input.readUnsignedByte();
    if (method == 2 && proxyUsername != null) {
      var username = credential(proxyUsername);
      var password = credential(proxyPassword == null ? "" : proxyPassword);
      output.write(1);
      output.write(username.length);
      output.write(username);
      output.write(password.length);
      output.write(password);
      output.flush();
      if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw new IOException("Unix SOCKS5 authentication failed");
    } else if (method != 0) {
      throw new IOException("Unsupported Unix SOCKS5 authentication method");
    }
    output.write(new byte[]{5, (byte) command, 0});
    target.write(output);
    output.flush();
    if (input.readUnsignedByte() != 5) throw new IOException("Invalid Unix SOCKS5 reply");
    var status = input.readUnsignedByte();
    if (input.readUnsignedByte() != 0) throw new IOException("Invalid Unix SOCKS5 reply");
    var bound = Address.read(input);
    client.write(new byte[]{5, (byte) status, 0});
    bound.write(client);
    client.flush();
    return status;
  }

  private static void socks4(DataInputStream input, OutputStream output, SFProxy proxy, Address target) throws IOException {
    output.write(new byte[]{4, 1, (byte) (target.port() >> 8), (byte) target.port()});
    output.write(target.type() == 1 ? target.bytes() : new byte[]{0, 0, 0, 1});
    var proxyUsername = proxy.username();
    var username = proxyUsername == null ? new byte[0] : credential(proxyUsername);
    for (var value : username) if (value == 0) throw new IOException("Invalid SOCKS4 username");
    output.write(username);
    output.write(0);
    if (target.type() != 1) {
      var host = target.host().getBytes(StandardCharsets.US_ASCII);
      for (var value : host) if (value == 0) throw new IOException("Invalid SOCKS4 destination");
      output.write(host);
      output.write(0);
    }
    output.flush();
    var response = read(input, 8);
    if (response[0] != 0 || response[1] != 90) throw new IOException("Unix SOCKS4 proxy rejected the request");
  }

  private static void http(DataInputStream input, OutputStream output, SFProxy proxy, Address target) throws IOException {
    var host = target.host();
    if (host.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '/' || c == '\\')) throw new IOException("Invalid HTTP proxy destination");
    var authority = (target.type() == 4 ? "[" + host + "]" : host) + ':' + target.port();
    var request = new StringBuilder("CONNECT ").append(authority).append(" HTTP/1.1\r\nHost: ").append(authority).append("\r\n");
    if (proxy.username() != null) {
      var credentials = proxy.username() + ':' + (proxy.password() == null ? "" : proxy.password());
      request.append("Proxy-Authorization: Basic ").append(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).append("\r\n");
    }
    output.write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
    output.flush();
    var headers = new ByteArrayOutputStream();
    var matched = 0;
    var terminator = new byte[]{13, 10, 13, 10};
    while (matched < terminator.length && headers.size() < 32 * 1024) {
      var next = input.readUnsignedByte();
      headers.write(next);
      matched = next == terminator[matched] ? matched + 1 : next == 13 ? 1 : 0;
    }
    if (matched != terminator.length) throw new IOException("Unix HTTP proxy reply headers are too large");
    var status = headers.toString(StandardCharsets.ISO_8859_1).split("\r\n", 2)[0].split(" ", 3);
    if (status.length < 2 || !status[0].matches("HTTP/1\\.[01]") || !status[1].matches("2[0-9]{2}")) {
      throw new IOException("Unix HTTP proxy rejected the request");
    }
  }

  private static byte[] credential(String value) throws IOException {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 255) throw new IOException("Proxy credential exceeds 255 bytes");
    return bytes;
  }

  private static void reply(OutputStream output, int status) throws IOException {
    output.write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0});
    output.flush();
  }

  private static byte[] read(DataInputStream input, int length) throws IOException {
    var bytes = input.readNBytes(length);
    if (bytes.length != length) throw new EOFException("Incomplete proxy handshake");
    return bytes;
  }

  private record Address(int type, byte[] bytes, int port) {
    private String host() throws IOException {
      return switch (type) {
        case 3 -> new String(bytes, StandardCharsets.US_ASCII);
        case 4 -> Inet6Address.getByAddress(null, bytes, -1).getHostAddress();
        default -> InetAddress.getByAddress(bytes).getHostAddress();
      };
    }

    private static Address read(DataInputStream input) throws IOException {
      var type = input.readUnsignedByte();
      var length = switch (type) {
        case 1 -> 4;
        case 3 -> input.readUnsignedByte();
        case 4 -> 16;
        default -> throw new IOException("Unsupported proxy address type");
      };
      if (length == 0) throw new IOException("Empty proxy destination");
      return new Address(type, UnixProxyHandshake.read(input, length), input.readUnsignedShort());
    }

    private void write(OutputStream output) throws IOException {
      output.write(type);
      if (type == 3) output.write(bytes.length);
      output.write(bytes);
      output.write(new byte[]{(byte) (port >> 8), (byte) port});
    }
  }
}
