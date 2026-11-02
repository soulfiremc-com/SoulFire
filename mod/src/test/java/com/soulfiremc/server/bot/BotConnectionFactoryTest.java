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
package com.soulfiremc.server.bot;

import com.viaversion.viafabricplus.bedrock.injection.access.IServerAddress;
import com.viaversion.viafabricplus.bedrock.protocoltranslator.network.NetherNetHttpAddress;
import com.viaversion.viafabricplus.bedrock.protocoltranslator.network.NetherNetJsonRpcAddress;
import com.viaversion.viafabricplus.bedrock.protocoltranslator.network.NetherNetLanAddress;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

final class BotConnectionFactoryTest {
  @ParameterizedTest
  @CsvSource({
    "example.test, example.test, 25565",
    "example.test:25566, example.test, 25566",
    "127.0.0.1:19133, 127.0.0.1, 19133",
    "[::1], ::1, 25565",
    "[::1]:19133, ::1, 19133",
    "::1, ::1, 25565",
    "bücher.test, xn--bcher-kva.test, 25565"
  })
  void parsesOrdinaryJavaAddresses(String input, String host, int port) {
    var address = BotConnectionFactory.parseAddress(input, ProtocolVersion.v1_21);
    assertEquals(host, address.getHost());
    assertEquals(port, address.getPort());
    assertNull(assertInstanceOf(IServerAddress.class, address).viaFabricPlusBedrock$getNetherNetAddress());
  }

  @ParameterizedTest
  @CsvSource({
    "example.test, example.test, 19132",
    "example.test:25565, example.test, 25565",
    "127.0.0.1:19133, 127.0.0.1, 19133",
    "[::1], [::1], 19132",
    "[::1]:19133, [::1], 19133",
    "::1, [::1], 19132",
    "bücher.test, xn--bcher-kva.test, 19132",
    "bücher.test:19133, xn--bcher-kva.test, 19133"
  })
  void defaultsPlainBedrockAddressesToNetherNet(String input, String host, int port) {
    var address = BotConnectionFactory.parseAddress(input, BedrockProtocolVersion.BEDROCK_LATEST);
    var transport = assertInstanceOf(NetherNetHttpAddress.class,
      assertInstanceOf(IServerAddress.class, address).viaFabricPlusBedrock$getNetherNetAddress());
    assertEquals(host, transport.host());
    assertEquals(port, transport.port());
  }

  @ParameterizedTest
  @CsvSource({
    "nethernet://mineflake.net, 19132",
    "nethernet://mineflake.net:19133, 19133"
  })
  void preservesNetherNetHttpTransport(String input, int port) {
    var address = BotConnectionFactory.parseAddress(input, BedrockProtocolVersion.BEDROCK_LATEST);
    var transport = assertInstanceOf(NetherNetHttpAddress.class,
      assertInstanceOf(IServerAddress.class, address).viaFabricPlusBedrock$getNetherNetAddress());
    assertEquals("mineflake.net", transport.host());
    assertEquals(port, transport.port());
  }

  @ParameterizedTest
  @CsvSource({
    "nethernet-lan://127.0.0.1, 7551",
    "nethernet-lan://127.0.0.1:7552, 7552"
  })
  void preservesNetherNetLanTransport(String input, int port) {
    var address = BotConnectionFactory.parseAddress(input, BedrockProtocolVersion.BEDROCK_LATEST);
    var transport = assertInstanceOf(NetherNetLanAddress.class,
      assertInstanceOf(IServerAddress.class, address).viaFabricPlusBedrock$getNetherNetAddress());
    assertEquals("127.0.0.1", transport.host());
    assertEquals(port, transport.port());
  }

  @ParameterizedTest
  @ValueSource(strings = {"nethernet-xbox://123456789", "nethernet-xbox-json-rpc://123456789"})
  void preservesNetherNetXboxTransport(String input) {
    var address = BotConnectionFactory.parseAddress(input, BedrockProtocolVersion.BEDROCK_LATEST);
    var transport = assertInstanceOf(NetherNetAddress.class,
      assertInstanceOf(IServerAddress.class, address).viaFabricPlusBedrock$getNetherNetAddress());
    assertEquals("123456789", transport.getNetworkId());
    assertEquals(input.startsWith("nethernet-xbox-json-rpc://"), transport instanceof NetherNetJsonRpcAddress);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ":25565", "example.test:nope", "example.test:65536",
    "nethernet://", "nethernet://mineflake.net/path"})
  void rejectsInvalidAddresses(String input) {
    for (var version : new ProtocolVersion[]{ProtocolVersion.v1_21, BedrockProtocolVersion.BEDROCK_LATEST}) {
      assertThrows(IllegalArgumentException.class, () -> BotConnectionFactory.parseAddress(input, version));
    }
  }

  @Test
  void resolvesLegacySrvBeforeViaFabricPlusSkipsConnectionTimeLookup() {
    var original = new ServerAddress("example.test", 25565);
    var redirected = new ServerAddress("mc.example.test", 25566);
    for (var version : new ProtocolVersion[]{ProtocolVersion.v1_8, ProtocolVersion.v1_16_4}) {
      assertSame(redirected, BotConnectionFactory.resolveLegacyAddress(original, version, address -> {
        assertSame(original, address);
        return Optional.of(redirected);
      }));
      assertSame(original, BotConnectionFactory.resolveLegacyAddress(original, version, _ -> Optional.empty()));
    }
  }

  @Test
  void leavesModernAndBedrockResolutionToTheirOwnConnectionPaths() {
    var original = new ServerAddress("example.test", 25565);
    for (var version : new ProtocolVersion[]{ProtocolVersion.v1_17, BedrockProtocolVersion.BEDROCK_LATEST}) {
      assertSame(original, BotConnectionFactory.resolveLegacyAddress(original, version, _ -> {
        fail("Unexpected legacy SRV lookup");
        return Optional.empty();
      }));
    }
  }
}
