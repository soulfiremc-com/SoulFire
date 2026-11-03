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
import com.soulfiremc.server.api.event.bot.BotClientSettingsEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;

@InternalPluginClass
public final class ClientSettings extends InternalPlugin {
  public ClientSettings() {
    super(new PluginInfo(
      "client-settings",
      "1.0.0",
      "Sends client settings to the server",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com"
    ));
  }

  @EventHandler
  public static void onPacket(BotClientSettingsEvent event) {
    var connection = event.connection();
    var settingsSource = connection.settingsSource();
    if (!settingsSource.get(ClientSettingsSettings.ENABLED)) {
      return;
    }

    int modelCustomisation = 0;
    for (var playerModelPart : PlayerModelPart.values()) {
      if (switch (playerModelPart) {
        case CAPE -> settingsSource.get(ClientSettingsSettings.CAPE_ENABLED);
        case JACKET -> settingsSource.get(ClientSettingsSettings.JACKET_ENABLED);
        case LEFT_SLEEVE -> settingsSource.get(ClientSettingsSettings.LEFT_SLEEVE_ENABLED);
        case RIGHT_SLEEVE -> settingsSource.get(ClientSettingsSettings.RIGHT_SLEEVE_ENABLED);
        case LEFT_PANTS_LEG -> settingsSource.get(ClientSettingsSettings.LEFT_PANTS_LEG_ENABLED);
        case RIGHT_PANTS_LEG -> settingsSource.get(ClientSettingsSettings.RIGHT_PANTS_LEG_ENABLED);
        case HAT -> settingsSource.get(ClientSettingsSettings.HAT_ENABLED);
      }) {
        modelCustomisation |= playerModelPart.getMask();
      }
    }

    event.clientInformation(new ClientInformation(
      settingsSource.get(ClientSettingsSettings.CLIENT_LOCALE),
      settingsSource.get(ClientSettingsSettings.RENDER_DISTANCE),
      settingsSource.get(ClientSettingsSettings.CHAT_VISIBILITY, ChatVisiblity.class),
      settingsSource.get(ClientSettingsSettings.USE_CHAT_COLORS),
      modelCustomisation,
      settingsSource.get(ClientSettingsSettings.HAND_PREFERENCE, HumanoidArm.class),
      settingsSource.get(ClientSettingsSettings.TEXT_FILTERING_ENABLED),
      settingsSource.get(ClientSettingsSettings.ALLOWS_LISTING),
      settingsSource.get(ClientSettingsSettings.PARTICLE_STATUS, ParticleStatus.class)
    ));
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(ClientSettingsSettings.class, "client-settings", "Minecraft client settings", this, "settings-2", ClientSettingsSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.NONE)
  private static class ClientSettingsSettings implements SettingsObject {
    private static final String NAMESPACE = "client-settings";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Send client settings")
        .description("Send client settings to the server when joining")
        .defaultValue(true)
        .build();
    public static final StringProperty<SettingsSource.Bot> CLIENT_LOCALE =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("client-locale")
        .uiName("Client locale")
        .description("The locale the client uses for translations")
        .defaultValue("en_us")
        .maxLength(ClientInformation.MAX_LANGUAGE_LENGTH)
        .build();
    public static final IntProperty<SettingsSource.Bot> RENDER_DISTANCE =
      ImmutableIntProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("render-distance")
        .uiName("Render distance")
        .description("How far the client renders chunks. (Use this to load more or less chunks from the server)")
        .defaultValue(2)
        .minValue(2)
        .maxValue(32)
        .build();
    public static final ComboProperty<SettingsSource.Bot> CHAT_VISIBILITY =
      ImmutableComboProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("chat-visibility")
        .uiName("Chat visibility")
        .description("What type of chat messages the client will receive")
        .defaultValue(ChatVisiblity.FULL.name())
        .addOptions(ComboProperty.optionsFromEnum(ChatVisiblity.values(), ComboProperty::capitalizeEnum, e -> switch (e) {
          case FULL -> "eye";
          case SYSTEM -> "view";
          case HIDDEN -> "eye-off";
        }))
        .build();
    public static final BooleanProperty<SettingsSource.Bot> USE_CHAT_COLORS =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("use-chat-colors")
        .uiName("Use chat colors")
        .description("Whether the client will use chat colors")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> CAPE_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("cape-enabled")
        .uiName("Cape enabled")
        .description("Whether to display the bots cape if it has one")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> JACKET_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("jacket-enabled")
        .uiName("Jacket enabled")
        .description("Whether to render the jacket overlay skin layer")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> LEFT_SLEEVE_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("left-sleeve-enabled")
        .uiName("Left sleeve enabled")
        .description("Whether to render the left overlay skin layer")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> RIGHT_SLEEVE_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("right-sleeve-enabled")
        .uiName("Right sleeve enabled")
        .description("Whether to render the right overlay skin layer")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> LEFT_PANTS_LEG_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("left-pants-leg-enabled")
        .uiName("Left pants leg enabled")
        .description("Whether to render the left pants leg overlay skin layer")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> RIGHT_PANTS_LEG_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("right-pants-leg-enabled")
        .uiName("Right pants leg enabled")
        .description("Whether to render the right pants leg overlay skin layer")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> HAT_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("hat-enabled")
        .uiName("Hat enabled")
        .description("Whether to render the hat overlay skin layer")
        .defaultValue(true)
        .build();
    public static final ComboProperty<SettingsSource.Bot> HAND_PREFERENCE =
      ImmutableComboProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("hand-preference")
        .uiName("Hand preference")
        .description("What hand the client prefers to use for items")
        .defaultValue(Player.DEFAULT_MAIN_HAND.name())
        .addOptions(ComboProperty.optionsFromEnum(HumanoidArm.values(), ComboProperty::capitalizeEnum, e -> switch (e) {
          case LEFT -> "circle-arrow-left";
          case RIGHT -> "circle-arrow-right";
        }))
        .build();
    public static final BooleanProperty<SettingsSource.Bot> TEXT_FILTERING_ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("text-filtering-enabled")
        .uiName("Text filtering enabled")
        .description("Whether to filter chat messages from the server")
        .defaultValue(false)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> ALLOWS_LISTING =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("allows-listing")
        .uiName("Allows listing")
        .description("Whether the client wants their username to be shown in the server list")
        .defaultValue(true)
        .build();
    public static final ComboProperty<SettingsSource.Bot> PARTICLE_STATUS =
      ImmutableComboProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("particle-status")
        .uiName("Particle Status")
        .description("How many particles the client will render")
        .defaultValue(ParticleStatus.MINIMAL.name())
        .addOptions(ComboProperty.optionsFromEnum(ParticleStatus.values(), ComboProperty::capitalizeEnum, e -> switch (e) {
          case ALL -> "sparkles";
          case DECREASED -> "sparkle";
          case MINIMAL -> "moon-star";
        }))
        .build();
  }
}
