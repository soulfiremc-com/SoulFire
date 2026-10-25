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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Socks5UdpRelayHandlerTest {
  private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 1080);
  private static final byte[] PAYLOAD = {7, 13, 29, 41};

  private static Stream<byte[]> validHeaders() {
    var ipv6 = new byte[22];
    ipv6[3] = 4;
    ipv6[19] = 1;
    var domain = new byte[262];
    domain[3] = 3;
    domain[4] = (byte) 255;
    Arrays.fill(domain, 5, 260, (byte) 'a');
    return Stream.of(
      new byte[]{0, 0, 0, 1, 127, 0, 0, 1, 74, (byte) 188},
      ipv6,
      new byte[]{0, 0, 0, 3, 3, 'b', 'o', 't', 74, (byte) 188},
      domain
    );
  }

  private static Stream<byte[]> invalidPackets() {
    var shortHeaders = validHeaders().flatMap(header -> Arrays.stream(new int[]{0, 1, 2, 3, 4, header.length - 3, header.length - 2, header.length - 1})
      .distinct().mapToObj(length -> Arrays.copyOf(header, length)));
    var invalidFields = Stream.of(
      new byte[]{1, 0, 0, 1, 127, 0, 0, 1, 0, 0, 7},
      new byte[]{0, 1, 0, 1, 127, 0, 0, 1, 0, 0, 7},
      new byte[]{0, 0, 1, 1, 127, 0, 0, 1, 0, 0, 7},
      new byte[]{0, 0, (byte) 128, 1, 127, 0, 0, 1, 0, 0, 7},
      new byte[]{0, 0, 0, 2, 127, 0, 0, 1, 0, 0, 7},
      new byte[]{0, 0, 0, 3, 0, 0, 0, 7},
      new byte[]{0, 0, 0, 3, (byte) 255, 'a', 0, 0}
    );
    return Stream.concat(shortHeaders, invalidFields).map(ByteBuffer::wrap).distinct().map(ByteBuffer::array);
  }

  @ParameterizedTest
  @MethodSource("invalidPackets")
  void dropsAndReleasesInvalidPacketsWithoutFailingTheChannel(byte[] packet) {
    for (var wrapped : new boolean[]{false, true}) {
      for (var offset : new int[]{0, 3}) {
        var content = PooledByteBufAllocator.DEFAULT.buffer(Math.max(1, offset + packet.length)).writeZero(offset).writeBytes(packet);
        content.readerIndex(offset);
        var message = wrapped ? new DatagramPacket(content, ADDRESS, ADDRESS) : content;
        var channel = new EmbeddedChannel(new Socks5UdpRelayHandler(ADDRESS, null, null));
        try {
          assertAll(
            () -> assertDoesNotThrow(() -> channel.writeInbound(message)),
            () -> assertEquals(0, content.refCnt()),
            () -> assertNull(channel.readInbound()),
            () -> assertTrue(channel.isActive())
          );
        } finally {
          channel.finishAndReleaseAll();
          if (content.refCnt() > 0) {
            content.release();
          }
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("validHeaders")
  void stripsValidHeadersWithoutChangingOwnershipOrMetadata(byte[] header) {
    for (var wrapped : new boolean[]{false, true}) {
      for (var offset : new int[]{0, 3}) {
        var content = PooledByteBufAllocator.DEFAULT.buffer(offset + header.length + PAYLOAD.length).writeZero(offset).writeBytes(header).writeBytes(PAYLOAD);
        content.readerIndex(offset);
        var message = wrapped ? new DatagramPacket(content, ADDRESS, ADDRESS) : content;
        var channel = new EmbeddedChannel(new Socks5UdpRelayHandler(ADDRESS, null, null));
        try {
          assertTrue(channel.writeInbound(message));
          assertSame(message, channel.readInbound());
          assertEquals(1, content.refCnt());
          assertEquals(offset + header.length, content.readerIndex());
          assertArrayEquals(PAYLOAD, readBytes(content));
        } finally {
          channel.finishAndReleaseAll();
          content.release();
        }
      }
    }
  }

  private static byte[] readBytes(ByteBuf buffer) {
    var bytes = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), bytes);
    return bytes;
  }
}
