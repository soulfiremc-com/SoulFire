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
package com.soulfiremc.server.settings.instance;

import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.viaversion.viaaprilfools.api.AprilFoolsProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import com.viaversion.viafabricplus.protocoltranslator.util.ProtocolVersionDetector;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BotSettings implements SettingsObject {
  public static final Function<String, ProtocolVersion> PROTOCOL_VERSION_PARSER =
    version -> {
      var split = version.split("\\|");
      if (split.length == 1) {
        return ProtocolVersion.getClosest(split[0]);
      }

      return ProtocolVersion.getProtocol(VersionType.valueOf(split[0]), Integer.parseInt(split[1]));
    };
  private static final String NAMESPACE = "bot";
  public static final StringProperty<SettingsSource.Instance> ADDRESS =
    ImmutableStringProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("address")
      .uiName("Address")
      .description("Address to connect to")
      .defaultValue("127.0.0.1:25565")
      .build();
  public static final MinMaxProperty<SettingsSource.Instance> JOIN_DELAY = ImmutableMinMaxProperty.<SettingsSource.Instance>builder()
    .sourceType(SettingsSource.Instance.INSTANCE)
    .namespace(NAMESPACE)
    .key("join-delay")
    .minValue(0)
    .maxValue(Integer.MAX_VALUE)
    .minEntry(ImmutableMinMaxPropertyEntry.builder()
      .uiName("Min Join Delay (ms)")
      .description("Minimum delay between joins in milliseconds")
      .defaultValue(1000)
      .build())
    .maxEntry(ImmutableMinMaxPropertyEntry.builder()
      .uiName("Max Join Delay (ms)")
      .description("Maximum delay between joins in milliseconds")
      .defaultValue(3000)
      .build())
    .build();
  public static final ComboProperty<SettingsSource.Bot> PROTOCOL_VERSION =
    ImmutableComboProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("protocol-version")
      .uiName("Protocol Version")
      .description("Minecraft protocol version to use")
      .defaultValue(getLatestProtocolVersionId())
      .addOptions(getProtocolVersionOptions())
      .build();
  public static final IntProperty<SettingsSource.Bot> READ_TIMEOUT =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("read-timeout")
      .uiName("Read Timeout")
      .description("Read timeout in seconds")
      .defaultValue(30)
      .minValue(0)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final IntProperty<SettingsSource.Bot> WRITE_TIMEOUT =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("write-timeout")
      .uiName("Write Timeout")
      .description("Write timeout in seconds")
      .defaultValue(0)
      .minValue(0)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final IntProperty<SettingsSource.Bot> CONNECT_TIMEOUT =
    ImmutableIntProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("connect-timeout")
      .uiName("Connect Timeout")
      .description("Connect timeout in seconds")
      .defaultValue(30)
      .minValue(0)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> RESOLVE_SRV =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("resolve-srv")
      .uiName("Resolve SRV")
      .description("Try to resolve SRV records from the address")
      .defaultValue(true)
      .build();
  public static final IntProperty<SettingsSource.Instance> CONCURRENT_CONNECTS =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("concurrent-connects")
      .uiName("Concurrent Connects")
      .description("Max amount of bots attempting to connect at once")
      .defaultValue(1)
      .minValue(1)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final BooleanProperty<SettingsSource.Bot> RESTORE_ON_REBOOT =
    ImmutableBooleanProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("restore-on-reboot")
      .uiName("Restore on Reboot")
      .description("Reconnect this bot when SoulFire starts if it was previously desired to be running.")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Bot> IGNORE_PACKET_HANDLING_ERRORS =
    ImmutableBooleanProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("ignore-packet-handling-errors")
      .uiName("Ignore Packet Handling Errors")
      .description("""
        Sometimes a bot fails to process a packet. When that happens it disconnects due to "packet errors".
        When this option is turned on, SoulFire will ignore errors during the packet handling process and keep bots connected.
        This might cause bots to have inconsistent world state though, so it could be detected by the server.""")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Bot> USE_IO_URING =
    ImmutableBooleanProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("use-io-uring")
      .uiName("Use IO_URING")
      .description("""
        Whether to use IO_URING for bot connections. This can improve performance and reduce CPU usage on Linux systems that support it.
        Requires a compatible kernel version and Java version.
        IO_URING won't be used if this option is disabled, your protocol version is bedrock edition or your PC does not support IO_URING.""")
      .defaultValue(false)
      .build();

  private static String formatVersion(ProtocolVersion version) {
    return "%s|%d".formatted(version.getVersionType().name(), version.getOriginalVersion());
  }

  public static List<ProtocolVersion> getAvailableProtocolVersions() {
    return ProtocolVersion.getReversedProtocols()
      .stream()
      .filter(version -> version != ProtocolVersionDetector.AUTO_DETECT_VERSION)
      .toList();
  }

  private static ComboProperty.ComboOption[] getProtocolVersionOptions() {
    return getAvailableProtocolVersions()
      .stream()
      .map(version -> new ComboProperty.ComboOption(
        formatVersion(version),
        version.getName(),
        switch (version.getVersionType()) {
          case CLASSIC -> "archive";
          case ALPHA_INITIAL, ALPHA_LATER -> "flask-conical";
          case BETA_INITIAL, BETA_LATER -> "test-tube";
          case RELEASE_INITIAL, RELEASE -> "box";
          case SPECIAL -> {
            if (BedrockProtocolVersion.PROTOCOLS.contains(version)) {
              yield "brick-wall";
            } else if (AprilFoolsProtocolVersion.PROTOCOLS.contains(version)) {
              yield "ghost";
            } else if (LegacyProtocolVersion.PROTOCOLS.contains(version)) {
              yield "archive";
            } else {
              throw new RuntimeException("Unknown version: " + version);
            }
          }
        },
        Stream.concat(
          version.getIncludedVersions().stream(),
          Stream.of(String.valueOf(version.getOriginalVersion()))
        ).toList()
      ))
      .toArray(ComboProperty.ComboOption[]::new);
  }

  private static String getLatestProtocolVersionId() {
    return formatVersion(ProtocolTranslationImpl.NATIVE_VERSION);
  }
}
