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
package com.soulfiremc.server.plugins;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.util.UndashedUuid;
import com.soulfiremc.grpc.generated.StringSetting;
import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.bot.BotPacketPreReceiveEvent;
import com.soulfiremc.server.api.event.bot.BotPacketPreSendEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

@Slf4j
@InternalPluginClass(order = 2)
public final class ForwardingBypass extends InternalPlugin {
  public ForwardingBypass() {
    super(new PluginInfo(
      "forwarding-bypass",
      "1.0.0",
      "Allows bypassing proxy forwarding",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com"
    ));
  }

  @SuppressWarnings("deprecation")
  private static ClientIntentionPacket withHostname(ClientIntentionPacket packet, String hostname) {
    return new ClientIntentionPacket(packet.protocolVersion(), hostname, packet.port(), packet.intention());
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(ForwardingBypassSettings.class, "forwarding-bypass", "Forwarding Bypass", this, "milestone", ForwardingBypassSettings.ENABLED);
  }

  @EventHandler
  public void onPacket(BotPacketPreSendEvent event) {
    if (!(event.packet() instanceof ClientIntentionPacket handshake)) {
      return;
    }

    var connection = event.connection();
    var settingsSource = connection.settingsSource();

    if (!settingsSource.get(ForwardingBypassSettings.ENABLED)) {
      return;
    }

    var hostname = handshake.hostName();
    var gameProfile = new GameProfile(
      connection.accountProfileId(),
      connection.accountName()
    );
    switch (settingsSource.get(
      ForwardingBypassSettings.FORWARDING_MODE, ForwardingBypassSettings.ForwardingMode.class)) {
      case LEGACY -> event.packet(
        withHostname(handshake,
          PlayerDataForwarding.createLegacyForwardingAddress(
            hostname,
            settingsSource.get(ForwardingBypassSettings.PLAYER_ADDRESS),
            gameProfile
          )));
      case BUNGEE_GUARD -> event.packet(
        withHostname(handshake,
          PlayerDataForwarding.createBungeeGuardForwardingAddress(
            hostname,
            settingsSource.get(ForwardingBypassSettings.PLAYER_ADDRESS),
            gameProfile,
            settingsSource.get(ForwardingBypassSettings.SECRET).getBytes(StandardCharsets.UTF_8)
          )));
      case SF_BYPASS -> event.packet(
        withHostname(handshake,
          PlayerDataForwarding.createSoulFireBypassAddress(
            hostname,
            settingsSource.get(ForwardingBypassSettings.SECRET))));
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  @EventHandler
  public void onPacketReceive(BotPacketPreReceiveEvent event) {
    if (!(event.packet() instanceof ClientboundCustomQueryPacket(var transactionId, var payload))) {
      return;
    }

    if (!payload.id().equals(PlayerDataForwarding.CHANNEL)) {
      return;
    }

    var connection = event.connection();
    var settingsSource = connection.settingsSource();
    if (!settingsSource.get(ForwardingBypassSettings.ENABLED)) {
      return;
    }

    if (settingsSource.get(
      ForwardingBypassSettings.FORWARDING_MODE, ForwardingBypassSettings.ForwardingMode.class)
      != ForwardingBypassSettings.ForwardingMode.MODERN) {
      log.warn("Received modern forwarding request packet, but forwarding mode is not modern!");
      return;
    }

    var requestedForwardingVersion = PlayerDataForwarding.MODERN_DEFAULT;
    {
      var buf = new FriendlyByteBuf(Unpooled.buffer());
      payload.write(buf);
      if (buf.readableBytes() == 1) {
        requestedForwardingVersion = buf.readByte();
      }
      buf.release();
    }

    var key = connection.minecraft().getProfileKeyPairManager().prepareKeyPair().join().orElse(null);
    var forwardingData =
      PlayerDataForwarding.createForwardingData(
        settingsSource.get(ForwardingBypassSettings.SECRET)
          .getBytes(StandardCharsets.UTF_8),
        settingsSource.get(ForwardingBypassSettings.PLAYER_ADDRESS),
        connection.currentProtocolVersion(),
        new GameProfile(connection.accountProfileId(), connection.accountName()),
        key,
        requestedForwardingVersion);

    var response = new ServerboundCustomQueryAnswerPacket(transactionId, new ByteBufCustomQueryAnswerPayload(forwardingData));
    Objects.requireNonNull(connection.minecraft().getConnection()).send(response);
  }

  private record ByteBufCustomQueryAnswerPayload(ByteBuf data) implements CustomQueryAnswerPayload {
    @Override
    public void write(FriendlyByteBuf buf) {
      buf.writeBytes(data.duplicate());
    }
  }

  private static final class PlayerDataForwarding {
    public static final Identifier CHANNEL = Identifier.parse("velocity:player_info");
    public static final int MODERN_DEFAULT = 1;
    public static final int MODERN_WITH_KEY = 2;
    public static final int MODERN_WITH_KEY_V2 = 3;
    public static final int MODERN_LAZY_SESSION = 4;
    public static final int MODERN_MAX_VERSION = MODERN_LAZY_SESSION;
    private static final String ALGORITHM = "HmacSHA256";
    private static final char LEGACY_SEPARATOR = '\0';

    private static final String BUNGEE_GUARD_TOKEN_PROPERTY_NAME = "bungeeguard-token";

    private PlayerDataForwarding() {
    }

    @SuppressWarnings("NonStrictComparisonCanBeEquality")
    public static ByteBuf createForwardingData(
      final byte[] secret,
      final String address,
      final ProtocolVersion protocol,
      final GameProfile profile,
      final @Nullable ProfileKeyPair key,
      final int requestedVersion
    ) {
      final FriendlyByteBuf forwarded = new FriendlyByteBuf(Unpooled.buffer(2048));
      try {
        final int actualVersion = findForwardingVersion(requestedVersion, protocol, key);

        ByteBufCodecs.VAR_INT.encode(forwarded, actualVersion);
        ByteBufCodecs.STRING_UTF8.encode(forwarded, address);
        UUIDUtil.STREAM_CODEC.encode(forwarded, profile.id());
        ByteBufCodecs.STRING_UTF8.encode(forwarded, profile.name());
        ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(forwarded, profile.properties());

        // This serves as additional redundancy. The key normally is stored in the
        // login start to the server, but some setups require this.
        if (actualVersion >= MODERN_WITH_KEY
          && actualVersion < MODERN_LAZY_SESSION) {
          assert key != null;
          key.publicKey().data().write(forwarded);

          // Provide the signer UUID since the UUID may differ from the
          // assigned UUID. Doing that breaks the signatures anyway but the server
          // should be able to verify the key independently.
          if (actualVersion >= MODERN_WITH_KEY_V2) {
            // We might not want to do it this way in SF. Should work for now.
            if (profile.id() != Util.NIL_UUID) {
              forwarded.writeBoolean(true);
              UUIDUtil.STREAM_CODEC.encode(forwarded, profile.id());
            } else {
              // Should only not be provided if the player was connected
              // as offline-mode and the signer UUID was not backfilled
              forwarded.writeBoolean(false);
            }
          }
        }

        final Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret, ALGORITHM));
        mac.update(forwarded.array(), forwarded.arrayOffset(), forwarded.readableBytes());
        final byte[] sig = mac.doFinal();

        return Unpooled.wrappedBuffer(Unpooled.wrappedBuffer(sig), forwarded);
      } catch (final InvalidKeyException e) {
        forwarded.release();
        throw new RuntimeException("Unable to authenticate data", e);
      } catch (final NoSuchAlgorithmException e) {
        // Should never happen
        forwarded.release();
        throw new AssertionError(e);
      }
    }

    @SuppressWarnings("NonStrictComparisonCanBeEquality")
    private static int findForwardingVersion(
      int requested,
      final ProtocolVersion protocol,
      final @Nullable ProfileKeyPair key
    ) {
      // Ensure we are in range
      requested = Math.min(requested, MODERN_MAX_VERSION);
      if (requested > MODERN_DEFAULT) {
        if (protocol.newerThanOrEqualTo(ProtocolVersion.v1_19_3)) {
          return requested >= MODERN_LAZY_SESSION
            ? MODERN_LAZY_SESSION
            : MODERN_DEFAULT;
        }
        if (key != null) {
          // We only have modern keys in SF
          return requested >= MODERN_WITH_KEY_V2
            ? MODERN_WITH_KEY_V2
            : MODERN_DEFAULT;
          // return switch (key.getKeyRevision()) {
          //   case GENERIC_V1 -> MODERN_WITH_KEY;
          //   // Since V2 is not backwards compatible we have to throw the key if v2 and requested is v1
          //   case LINKED_V2 -> requested >= MODERN_WITH_KEY_V2
          //     ? MODERN_WITH_KEY_V2
          //     : MODERN_DEFAULT;
          // };
        } else {
          return MODERN_DEFAULT;
        }
      }
      return MODERN_DEFAULT;
    }

    public static String createLegacyForwardingAddress(
      final String serverAddress,
      final String playerAddress,
      final GameProfile profile
    ) {
      return createLegacyForwardingAddress(
        serverAddress,
        playerAddress,
        profile,
        UnaryOperator.identity()
      );
    }

    private static String createLegacyForwardingAddress(
      final String serverAddress,
      final String playerAddress,
      final GameProfile profile,
      final UnaryOperator<List<Property>> propertiesTransform
    ) {
      // BungeeCord IP forwarding is simply a special injection after the "address" in the handshake,
      // separated by \0 (the null byte). In order, you send the original host, the player's IP, their
      // UUID (undashed), and if you are in online-mode, their login properties (from Mojang).
      final StringBuilder data = new StringBuilder()
        .append(serverAddress)
        .append(LEGACY_SEPARATOR)
        .append(playerAddress)
        .append(LEGACY_SEPARATOR)
        .append(UndashedUuid.toString(profile.id()))
        .append(LEGACY_SEPARATOR);
      GsonInstance.GSON.toJson(propertiesTransform.apply(profile.properties().values().stream().toList()), data);
      return data.toString();
    }

    public static String createBungeeGuardForwardingAddress(
      final String serverAddress,
      final String playerAddress,
      final GameProfile profile,
      final byte[] forwardingSecret
    ) {
      // Append forwarding secret as a BungeeGuard token.
      final Property property = new Property(
        BUNGEE_GUARD_TOKEN_PROPERTY_NAME,
        new String(forwardingSecret, StandardCharsets.UTF_8),
        ""
      );
      return createLegacyForwardingAddress(
        serverAddress,
        playerAddress,
        profile,
        properties -> ImmutableList.<Property>builder()
          .addAll(properties)
          .add(property)
          .build()
      );
    }

    public static String createSoulFireBypassAddress(String initialHostname, String forwardingSecret) {
      return initialHostname + LEGACY_SEPARATOR + "SF_%s".formatted(forwardingSecret);
    }
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class ForwardingBypassSettings implements SettingsObject {
    private static final String NAMESPACE = "forwarding-bypass";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable forwarding bypass")
        .description("Enable the forwarding bypass")
        .defaultValue(false)
        .build();
    public static final ComboProperty<SettingsSource.Bot> FORWARDING_MODE =
      ImmutableComboProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("forwarding-mode")
        .uiName("Forwarding mode")
        .description("What type of forwarding to use")
        .defaultValue(ForwardingMode.LEGACY.name())
        .addOptions(ComboProperty.optionsFromEnum(ForwardingMode.values(), ForwardingMode::toString, e -> switch (e) {
          case LEGACY -> "hourglass";
          case BUNGEE_GUARD -> "shield-user";
          case MODERN -> "fingerprint";
          case SF_BYPASS -> "door-open";
        }))
        .build();
    public static final StringProperty<SettingsSource.Bot> SECRET =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("secret")
        .uiName("Secret")
        .description("Secret key used for forwarding. (Not needed for legacy mode)")
        .defaultValue("forwarding secret")
        .type(StringSetting.InputType.PASSWORD)
        .build();
    public static final StringProperty<SettingsSource.Bot> PLAYER_ADDRESS =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("player-address")
        .uiName("Player Address")
        .description("What the server should use as the player IP. Only used by some forwarding modes.")
        .defaultValue("127.0.0.1")
        .build();

    @RequiredArgsConstructor
    enum ForwardingMode {
      LEGACY("Legacy"),
      BUNGEE_GUARD("BungeeGuard"),
      MODERN("Modern"),
      SF_BYPASS("SoulFire Bypass");

      private final String displayName;

      @Override
      public String toString() {
        return displayName;
      }
    }
  }
}
