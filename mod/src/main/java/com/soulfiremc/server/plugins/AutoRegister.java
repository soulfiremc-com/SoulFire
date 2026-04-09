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
import com.soulfiremc.server.api.event.bot.ChatMessageReceiveEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.lenni0451.lambdaevents.EventHandler;

import java.util.concurrent.TimeUnit;

@InternalPluginClass
public final class AutoRegister extends InternalPlugin {
  public AutoRegister() {
    super(new PluginInfo(
      "auto-register",
      "1.0.0",
      "Automatically registers bots AuthMe servers",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com/docs/how-to/auto-register"
    ));
  }

  @EventHandler
  public static void onChat(ChatMessageReceiveEvent event) {
    var connection = event.connection();
    var settingsSource = connection.settingsSource();
    if (!settingsSource.get(AutoRegisterSettings.ENABLED)) {
      return;
    }

    var plainMessage = event.parseToPlainText();
    var password = settingsSource.get(AutoRegisterSettings.PASSWORD_FORMAT);

    var scheduler = connection.scheduler();
    // TODO: Add more password options
    if (plainMessage.contains("/register") || plainMessage.contains("/reg")) {
      scheduler.schedule(() -> {
        var registerCommand = settingsSource.get(AutoRegisterSettings.REGISTER_COMMAND);
        connection.sendChatMessage(registerCommand.replace("%password%", password));
      }, settingsSource.getRandom(AutoRegisterSettings.DELAY).getAsInt(), TimeUnit.MILLISECONDS);
    } else if (plainMessage.contains("/login") || plainMessage.contains("/l")) {
      scheduler.schedule(() -> {
        var loginCommand = settingsSource.get(AutoRegisterSettings.LOGIN_COMMAND);
        connection.sendChatMessage(loginCommand.replace("%password%", password));
      }, settingsSource.getRandom(AutoRegisterSettings.DELAY).getAsInt(), TimeUnit.MILLISECONDS);
    }

  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(AutoRegisterSettings.class, "auto-register", "Auto Register", this, "key-round", AutoRegisterSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.NONE)
  private static class AutoRegisterSettings implements SettingsObject {
    private static final String NAMESPACE = "auto-register";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable Auto Register")
        .description("Make bots run the /register and /login command after joining")
        .defaultValue(false)
        .build();
    public static final StringProperty<SettingsSource.Bot> REGISTER_COMMAND =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("register-command")
        .uiName("Register Command")
        .description("Command to be executed to register")
        .defaultValue("/register %password% %password%")
        .build();
    public static final StringProperty<SettingsSource.Bot> LOGIN_COMMAND =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("login-command")
        .uiName("Login Command")
        .description("Command to be executed to log in")
        .defaultValue("/login %password%")
        .build();

    public static final StringProperty<SettingsSource.Bot> PASSWORD_FORMAT =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("password-format")
        .uiName("Password Format")
        .description("The password for registering")
        .defaultValue("SoulFire")
        .build();

    public static final MinMaxProperty<SettingsSource.Bot> DELAY =
      ImmutableMinMaxProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("delay")
        .minValue(0)
        .maxValue(Integer.MAX_VALUE)
        .minEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Min delay (Milliseconds)")
          .description("Minimum delay before sending")
          .defaultValue(0)
          .build())
        .maxEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Max delay (Milliseconds)")
          .description("Maximum delay before sending")
          .defaultValue(1000)
          .build())
        .build();
  }
}
