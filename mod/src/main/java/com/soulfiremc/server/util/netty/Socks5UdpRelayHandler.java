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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.*;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

/// Implements SOCKS5 UDP ASSOCIATE (RFC 1928, command 0x03) for proxying
 /// UDP traffic through a SOCKS5 proxy. Used for Bedrock Edition connections
 /// which use RakNet over UDP instead of TCP.
 ///
 /// Opens a TCP control connection to the SOCKS5 proxy for the handshake,
 /// then wraps/unwraps UDP datagrams with the SOCKS5 UDP relay header.
@Slf4j
public class Socks5UdpRelayHandler extends ChannelDuplexHandler {
  private static final String SOCKS5_DECODER = "socks5Decoder";
  private final InetSocketAddress proxyAddress;
  private final @Nullable String username;
  private final @Nullable String password;
  private Channel tcpControlChannel;
  private InetSocketAddress relayAddress;
  private InetSocketAddress originalDestination;

  public Socks5UdpRelayHandler(InetSocketAddress proxyAddress, @Nullable String username, @Nullable String password) {
    this.proxyAddress = proxyAddress;
    this.username = username;
    this.password = password;
  }

  @Override
  public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                      SocketAddress localAddress, ChannelPromise promise) {
    originalDestination = (InetSocketAddress) remoteAddress;

    var eventLoopGroup = EventLoopGroupHolder.remote(true);
    new Bootstrap()
      .group(eventLoopGroup.eventLoopGroup())
      .channel(eventLoopGroup.channelCls())
      .handler(new ChannelInitializer<>() {
        @Override
        protected void initChannel(Channel ch) {
          ch.pipeline().addLast(Socks5ClientEncoder.DEFAULT);
          ch.pipeline().addLast(SOCKS5_DECODER, new Socks5InitialResponseDecoder());
          ch.pipeline().addLast(new Socks5TcpControlHandler(ctx, promise));
        }
      })
      .connect(proxyAddress)
      .addListener((ChannelFutureListener) future -> {
        if (future.isSuccess()) {
          tcpControlChannel = future.channel();
        } else {
          promise.tryFailure(future.cause());
        }
      });
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    switch (msg) {
      case ByteBuf buf -> {
        var header = buildSocks5UdpHeader(ctx.alloc(), originalDestination);
        ctx.write(Unpooled.wrappedBuffer(header, buf), promise);
      }
      case DatagramPacket packet -> {
        var header = buildSocks5UdpHeader(ctx.alloc(), originalDestination);
        var content = Unpooled.wrappedBuffer(header, packet.content().retain());
        ctx.write(new DatagramPacket(content, relayAddress), promise);
        packet.release();
      }
      case null, default -> super.write(ctx, msg, promise);
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    switch (msg) {
      case ByteBuf buf -> {
        skipSocks5UdpHeader(buf);
        ctx.fireChannelRead(buf);
      }
      case DatagramPacket packet -> {
        skipSocks5UdpHeader(packet.content());
        ctx.fireChannelRead(packet);
      }
      case null, default -> super.channelRead(ctx, msg);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (tcpControlChannel != null) {
      tcpControlChannel.close();
    }
    super.channelInactive(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("SOCKS5 UDP relay error", cause);
    if (tcpControlChannel != null) {
      tcpControlChannel.close();
    }
    ctx.close();
  }

  private static ByteBuf buildSocks5UdpHeader(ByteBufAllocator alloc, InetSocketAddress destination) {
    var addr = destination.getAddress().getAddress();
    var buf = alloc.buffer(4 + addr.length + 2);
    buf.writeShort(0x0000); // RSV
    buf.writeByte(0x00); // FRAG
    buf.writeByte(addr.length == 4 ? 0x01 : 0x04); // ATYP: IPv4 or IPv6
    buf.writeBytes(addr);
    buf.writeShort(destination.getPort());
    return buf;
  }

  private static void skipSocks5UdpHeader(ByteBuf buf) {
    buf.skipBytes(2); // RSV
    buf.skipBytes(1); // FRAG
    var atyp = buf.readByte();
    switch (atyp) {
      case 0x01 -> buf.skipBytes(4); // IPv4
      case 0x03 -> buf.skipBytes(buf.readUnsignedByte()); // Domain name
      case 0x04 -> buf.skipBytes(16); // IPv6
      default -> throw new IllegalStateException("Unknown SOCKS5 address type: " + atyp);
    }
    buf.skipBytes(2); // DST.PORT
  }

  /// Handles the SOCKS5 handshake over the TCP control connection
  /// using Netty's built-in SOCKS5 codec classes.
  private class Socks5TcpControlHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext datagramCtx;
    private final ChannelPromise connectPromise;

    Socks5TcpControlHandler(ChannelHandlerContext datagramCtx, ChannelPromise connectPromise) {
      this.datagramCtx = datagramCtx;
      this.connectPromise = connectPromise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      if (username != null && password != null) {
        ctx.writeAndFlush(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD));
      } else {
        ctx.writeAndFlush(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH));
      }
      super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      switch (msg) {
        case Socks5InitialResponse response -> handleInitialResponse(ctx, response);
        case Socks5PasswordAuthResponse response -> handleAuthResponse(ctx, response);
        case Socks5CommandResponse response -> handleCommandResponse(response);
        case null, default -> super.channelRead(ctx, msg);
      }
    }

    private void handleInitialResponse(ChannelHandlerContext ctx, Socks5InitialResponse response) {
      if (response.authMethod() == Socks5AuthMethod.PASSWORD && username != null && password != null) {
        ctx.pipeline().replace(SOCKS5_DECODER, SOCKS5_DECODER, new Socks5PasswordAuthResponseDecoder());
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest(username, password));
      } else if (response.authMethod() == Socks5AuthMethod.NO_AUTH) {
        sendUdpAssociate(ctx);
      } else {
        fail(ctx, new Exception("Unsupported SOCKS5 auth method: " + response.authMethod()));
      }
    }

    private void handleAuthResponse(ChannelHandlerContext ctx, Socks5PasswordAuthResponse response) {
      if (response.status() == Socks5PasswordAuthStatus.SUCCESS) {
        sendUdpAssociate(ctx);
      } else {
        fail(ctx, new Exception("SOCKS5 authentication failed: " + response.status()));
      }
    }

    private void handleCommandResponse(Socks5CommandResponse response) throws UnknownHostException {
      if (response.status() != Socks5CommandStatus.SUCCESS) {
        fail(null, new Exception("SOCKS5 UDP ASSOCIATE failed: " + response.status()));
        return;
      }

      var relay = new InetSocketAddress(response.bndAddr(), response.bndPort());

      // Proxies commonly return 0.0.0.0 as the relay address, use proxy host instead
      if (relay.getAddress() != null && relay.getAddress().isAnyLocalAddress()) {
        relay = new InetSocketAddress(proxyAddress.getAddress(), relay.getPort());
      }

      relayAddress = relay;
      log.debug("SOCKS5 UDP relay established at {}", relayAddress);

      datagramCtx.connect(relayAddress, null, connectPromise);
    }

    private void sendUdpAssociate(ChannelHandlerContext ctx) {
      ctx.pipeline().replace(SOCKS5_DECODER, SOCKS5_DECODER, new Socks5CommandResponseDecoder());
      ctx.writeAndFlush(new DefaultSocks5CommandRequest(
        Socks5CommandType.UDP_ASSOCIATE, Socks5AddressType.IPv4, "0.0.0.0", 0));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      connectPromise.tryFailure(new Exception("SOCKS5 TCP control connection closed"));
      datagramCtx.close();
      super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      fail(ctx, cause);
    }

    private void fail(@Nullable ChannelHandlerContext ctx, Throwable cause) {
      connectPromise.tryFailure(cause);
      if (ctx != null) {
        ctx.close();
      }
    }
  }
}
