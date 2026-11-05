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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.adventure.SoulFireAdventure;
import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.BooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableBooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableIntProperty;
import com.soulfiremc.server.settings.property.IntProperty;
import com.soulfiremc.shared.SFLogAppender;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.lenni0451.lambdaevents.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@InternalPluginClass
public final class DisconnectLogger extends InternalPlugin {
  private static final MetadataKey<Cache<@NotNull String, Integer>> DISCONNECT_MESSAGES = MetadataKey.of("disconnect_logger", "disconnect_messages", Cache.class);

  public DisconnectLogger() {
    super(new PluginInfo(
      "disconnect-logger",
      "1.0.0",
      "Logs all bot disconnects to the terminal\nIncludes deduplication to prevent spamming the same disconnect message too often",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com"
    ));
  }

  @EventHandler
  public static void onBotRemove(BotDisconnectedEvent event) {
    var settingsSource = event.connection().settingsSource().instanceSettings();
    if (!settingsSource.get(DisconnectLoggerSettings.ENABLED)) {
      return;
    }

    // usage of synchronized method so that the disconnectMessages set is not modified while being
    // iterated
    logDisconnectMessage(event.instanceManager(), Component.text("Disconnected with message: ").append(event.message()));
  }

  private static synchronized void logDisconnectMessage(InstanceManager instanceManager, Component message) {
    var disconnectMessage = instanceManager.metadata().getOrSet(DISCONNECT_MESSAGES, () -> Caffeine.newBuilder()
      .expireAfterWrite(5, TimeUnit.SECONDS)
      .build());
    var ansiMessage = SoulFireAdventure.TRUE_COLOR_ANSI_SERIALIZER.serialize(message);

    var deduplicateAmount = instanceManager.settingsSource().get(DisconnectLoggerSettings.DEDUPLICATE_AMOUNT);
    int messageCount = Objects.requireNonNull(disconnectMessage.get(ansiMessage, _ -> 0));
    if (messageCount < deduplicateAmount) {
      // Print to remote console (always true color)
      log.atInfo()
        .addKeyValue(SFLogAppender.SF_SKIP_LOCAL_APPENDERS, "true")
        .log("{}", ansiMessage);
      // Print to local console
      log.atInfo()
        .addKeyValue(SFLogAppender.SF_SKIP_PUBLISHING, "true")
        .log("{}", SoulFireAdventure.ANSI_SERIALIZER.serialize(message));
      disconnectMessage.put(ansiMessage, messageCount + 1);
    }
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(DisconnectLoggerSettings.class, "disconnect-logger", "Disconnect logger", this, "logs", DisconnectLoggerSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class DisconnectLoggerSettings implements SettingsObject {
    private static final String NAMESPACE = "disconnect-logger";
    public static final BooleanProperty<SettingsSource.Instance> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Instance>builder()
        .sourceType(SettingsSource.Instance.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Log disconnects to terminal")
        .description("Log all bot disconnects to the terminal")
        .defaultValue(true)
        .build();
    public static final IntProperty<SettingsSource.Instance> DEDUPLICATE_AMOUNT =
      ImmutableIntProperty.<SettingsSource.Instance>builder()
        .sourceType(SettingsSource.Instance.INSTANCE)
        .namespace(NAMESPACE)
        .key("deduplicate-amount")
        .uiName("Deduplicate amount")
        .description("Soulfire logs the same disconnect message no more than this number of times within five seconds.")
        .defaultValue(1)
        .minValue(1)
        .maxValue(Integer.MAX_VALUE)
        .build();
  }
}
