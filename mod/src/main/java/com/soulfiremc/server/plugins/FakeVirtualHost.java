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

import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.bot.BotPacketPreSendEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;

@InternalPluginClass
public final class FakeVirtualHost extends InternalPlugin {
  public FakeVirtualHost() {
    super(new PluginInfo(
      "fake-virtual-host",
      "1.0.0",
      "Fakes the virtual host",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com/docs/how-to/fake-virtual-host"
    ));
  }

  @SuppressWarnings("deprecation")
  @EventHandler
  public static void onPacket(BotPacketPreSendEvent event) {
    if (event.packet() instanceof ClientIntentionPacket intentionPacket) {
      var settingsSource = event.connection().settingsSource();

      if (!settingsSource.get(FakeVirtualHostSettings.ENABLED)) {
        return;
      }

      event.packet(new ClientIntentionPacket(
        intentionPacket.protocolVersion(),
        settingsSource.get(FakeVirtualHostSettings.HOSTNAME),
        settingsSource.get(FakeVirtualHostSettings.PORT),
        intentionPacket.intention()
      ));
    }
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(FakeVirtualHostSettings.class, "fake-virtual-host", "Fake Virtual Host", this, "globe", FakeVirtualHostSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class FakeVirtualHostSettings implements SettingsObject {
    private static final String NAMESPACE = "fake-virtual-host";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Fake virtual host")
        .description("Whether to fake the virtual host or not")
        .defaultValue(false)
        .build();
    public static final StringProperty<SettingsSource.Bot> HOSTNAME =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("hostname")
        .uiName("Hostname")
        .description("The hostname to fake")
        .defaultValue("localhost")
        .build();
    public static final IntProperty<SettingsSource.Bot> PORT =
      ImmutableIntProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("port")
        .uiName("Port")
        .description("The port to fake")
        .defaultValue(25565)
        .minValue(1)
        .maxValue(65535)
        .thousandSeparator(false)
        .build();
  }
}
