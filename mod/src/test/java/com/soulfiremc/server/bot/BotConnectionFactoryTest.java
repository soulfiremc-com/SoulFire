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

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

final class BotConnectionFactoryTest {
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
    for (var version : new ProtocolVersion[]{ProtocolVersion.v1_17, BedrockProtocolVersion.bedrockLatest}) {
      assertSame(original, BotConnectionFactory.resolveLegacyAddress(original, version, _ -> {
        fail("Unexpected legacy SRV lookup");
        return Optional.empty();
      }));
    }
  }
}
