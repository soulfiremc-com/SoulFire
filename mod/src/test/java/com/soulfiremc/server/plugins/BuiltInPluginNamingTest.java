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
  void retainsPluginAndSettingIdentifiers(String id, String displayName,
                                         Consumer<InstanceSettingsRegistryInitEvent> register) {
    var registry = new SettingsPageRegistry();
    register.accept(new InstanceSettingsRegistryInitEvent(mock(InstanceManager.class), registry));

    var page = registry.exportSettingsPages().getFirst();
    assertEquals(displayName, page.getPageName());
    assertEquals(id, page.getId());
    assertEquals(id, page.getOwningPluginId());
    assertEquals(id, page.getEnabledIdentifier().getNamespace());
    assertEquals(id, registry.exportRegisteredPlugins().getFirst().getId());
  }

  private static Stream<Arguments> renamedPages() {
    return Stream.of(
      Arguments.of("auto-eat", "Auto eat", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoEat()::onSettingsRegistryInit),
      Arguments.of("auto-respawn", "Auto respawn", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoRespawn()::onSettingsRegistryInit),
      Arguments.of("auto-armor", "Auto equip armor", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoArmor()::onSettingsRegistryInit),
      Arguments.of("auto-totem", "Auto equip totem", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoTotem()::onSettingsRegistryInit),
      Arguments.of("auto-reconnect", "Auto reconnect", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoReconnect()::onSettingsRegistryInit),
      Arguments.of("auto-jump", "Random jump", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoJump()::onSettingsRegistryInit),
      Arguments.of("auto-chat-message", "Auto chat", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoChatMessage()::onSettingsRegistryInit),
      Arguments.of("auto-register", "Auto login and register", (Consumer<InstanceSettingsRegistryInitEvent>) new AutoRegister()::onSettingsRegistryInit),
      Arguments.of("ai-chat-bot", "AI chat", (Consumer<InstanceSettingsRegistryInitEvent>) new AIChatBot()::onSettingsRegistryInit),
      Arguments.of("kill-aura", "Auto attack", (Consumer<InstanceSettingsRegistryInitEvent>) new KillAura()::onSettingsRegistryInit),
      Arguments.of("captcha-solver", "Captcha solver", (Consumer<InstanceSettingsRegistryInitEvent>) new CaptchaSolver()::onSettingsRegistryInit),
      Arguments.of("chat-logger", "Chat logger", (Consumer<InstanceSettingsRegistryInitEvent>) new ChatLogger()::onSettingsRegistryInit),
      Arguments.of("disconnect-logger", "Disconnect logger", (Consumer<InstanceSettingsRegistryInitEvent>) new DisconnectLogger()::onSettingsRegistryInit),
      Arguments.of("client-settings", "Minecraft client settings", (Consumer<InstanceSettingsRegistryInitEvent>) new ClientSettings()::onSettingsRegistryInit),
      Arguments.of("client-brand", "Client brand", (Consumer<InstanceSettingsRegistryInitEvent>) new ClientBrand()::onSettingsRegistryInit),
      Arguments.of("fake-virtual-host", "Handshake host override", (Consumer<InstanceSettingsRegistryInitEvent>) new FakeVirtualHost()::onSettingsRegistryInit),
      Arguments.of("server-list-bypass", "Ping before join", (Consumer<InstanceSettingsRegistryInitEvent>) new ServerListBypass()::onSettingsRegistryInit),
      Arguments.of("forwarding-bypass", "Player forwarding", (Consumer<InstanceSettingsRegistryInitEvent>) new ForwardingBypass()::onSettingsRegistryInit)
    );
  }
}
