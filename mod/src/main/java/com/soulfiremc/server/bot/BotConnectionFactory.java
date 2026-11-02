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

import com.google.common.net.HostAndPort;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotConnectionInitEvent;
import com.soulfiremc.server.proxy.SFProxy;
import com.soulfiremc.server.settings.lib.BotSettingsSource;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.kyori.adventure.text.Component;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.IDN;

public record BotConnectionFactory(
  InstanceManager instanceManager,
  BotSettingsSource settingsSource,
  ProtocolVersion protocolVersion,
  ServerAddress serverAddress,
  @Nullable
  SFProxy proxyData) {
  public static ServerAddress parseAddress(String address, ProtocolVersion protocolVersion) {
    if (!ServerAddress.isValidAddress(address)) {
      throw new IllegalArgumentException("Invalid host address: " + address);
    }

    // Default Bedrock to NetherNet, preserving explicit schemes and letting the addon supply the port.
    if (BedrockProtocolVersion.BEDROCK_LATEST.equals(protocolVersion) && !address.contains("://")) {
      var hostAndPort = HostAndPort.fromString(address);
      var host = IDN.toASCII(hostAndPort.getHost());
      var authority = hostAndPort.hasPort()
        ? HostAndPort.fromParts(host, hostAndPort.getPort())
        : HostAndPort.fromHost(host);
      address = "nethernet://" + authority;
    }
    return ServerAddress.parseString(address);
  }

  static ServerAddress resolveLegacyAddress(
    ServerAddress address,
    ProtocolVersion protocolVersion,
    ServerRedirectHandler redirectHandler) {
    // ViaFabricPlus skips SRV lookup in ServerNameResolver for these versions.
    // Parsing happens before the bot's protocol context is available, so resolve with its version here.
    if (!BedrockProtocolVersion.BEDROCK_LATEST.equals(protocolVersion)
      && protocolVersion.olderThanOrEqualTo(ProtocolVersion.v1_16_4)) {
      return redirectHandler.lookupRedirect(address).orElse(address);
    }
    return address;
  }

  public BotConnection prepareConnection(boolean isStatusPing) {
    var botConnection =
      new BotConnection(
        this,
        instanceManager,
        settingsSource,
        protocolVersion,
        serverAddress,
        proxyData,
        isStatusPing);

    try {
      SoulFireAPI.postEvent(new BotConnectionInitEvent(botConnection));
      return botConnection;
    } catch (Throwable throwable) {
      try {
        botConnection.disconnect(Component.text("Connection initialization failed")).join();
      } catch (Throwable cleanup) {
        throwable.addSuppressed(cleanup);
      }
      throw throwable;
    }
  }
}
