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

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsPageRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

final class BuiltInPluginNamingTest {
  @ParameterizedTest
  @MethodSource("renamedPages")
  void retainsPluginAndSettingIdentifiers(String id, String displayName, String enabledKey,
                                         Consumer<InstanceSettingsRegistryInitEvent> register) {
    var registry = new SettingsPageRegistry();
    register.accept(new InstanceSettingsRegistryInitEvent(mock(InstanceManager.class), registry));

    var page = registry.exportSettingsPages().getFirst();
    assertEquals(displayName, page.getPageName());
    assertEquals(id, page.getId());
    assertEquals(id, page.getOwningPluginId());
    assertEquals(id, page.getEnabledIdentifier().getNamespace());
    assertEquals(enabledKey, page.getEnabledIdentifier().getKey());
    assertEquals(id, registry.exportRegisteredPlugins().getFirst().getId());
  }

  private static Stream<Arguments> renamedPages() {
    return Stream.of(
      Arguments.of("auto-eat", "Auto eat", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoEat()::onSettingsRegistryInit),
      Arguments.of("auto-respawn", "Auto respawn", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoRespawn()::onSettingsRegistryInit),
      Arguments.of("auto-armor", "Auto equip armor", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoArmor()::onSettingsRegistryInit),
      Arguments.of("auto-totem", "Auto equip totem", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoTotem()::onSettingsRegistryInit),
      Arguments.of("auto-reconnect", "Auto reconnect", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoReconnect()::onSettingsRegistryInit),
      Arguments.of("auto-jump", "Random jump", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoJump()::onSettingsRegistryInit),
      Arguments.of("auto-chat-message", "Auto chat", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoChatMessage()::onSettingsRegistryInit),
      Arguments.of("auto-register", "Auto login and register", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoRegister()::onSettingsRegistryInit),
      Arguments.of("ai-chat-bot", "AI chat", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new AIChatBot()::onSettingsRegistryInit),
      Arguments.of("kill-aura", "Auto attack", "enable", (Consumer<InstanceSettingsRegistryInitEvent>) new KillAura()::onSettingsRegistryInit),
      Arguments.of("captcha-solver", "Captcha solver", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new CaptchaSolver()::onSettingsRegistryInit),
      Arguments.of("chat-logger", "Chat logger", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new ChatLogger()::onSettingsRegistryInit),
      Arguments.of("disconnect-logger", "Disconnect logger", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new DisconnectLogger()::onSettingsRegistryInit),
      Arguments.of("client-settings", "Minecraft client settings", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new ClientSettings()::onSettingsRegistryInit),
      Arguments.of("client-brand", "Client brand", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new ClientBrand()::onSettingsRegistryInit),
      Arguments.of("fake-virtual-host", "Handshake host override", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new FakeVirtualHost()::onSettingsRegistryInit),
      Arguments.of("server-list-bypass", "Ping before join", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new ServerListBypass()::onSettingsRegistryInit),
      Arguments.of("forwarding-bypass", "Player forwarding", "enabled", (Consumer<InstanceSettingsRegistryInitEvent>) new ForwardingBypass()::onSettingsRegistryInit)
    );
  }
}
