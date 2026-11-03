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
import com.soulfiremc.server.api.event.bot.BotConnectionInitEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.soulfiremc.server.task.EatMealTask;
import com.soulfiremc.server.util.SFInventoryHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;

import java.util.concurrent.TimeUnit;

@Slf4j
@InternalPluginClass
public final class AutoEat extends InternalPlugin {
  public AutoEat() {
    super(new PluginInfo(
      "auto-eat",
      "1.0.0",
      "Automatically eats food when hungry",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com"
    ));
  }

  @EventHandler
  public static void onJoined(BotConnectionInitEvent event) {
    var connection = event.connection();
    var settingsSource = connection.settingsSource();
    connection.scheduler().scheduleWithDynamicDelay(
      () -> {
        if (!settingsSource.get(AutoEatSettings.ENABLED)) {
          return;
        }

        var player = connection.minecraft().player;
        if (player == null || player.isDeadOrDying()) {
          return;
        }

        if (!player.getFoodData().needsFood()) {
          return;
        }

        var playerInventory = player.inventoryMenu;

        var edibleSlot = SFInventoryHelpers.findMatchingSlotForAction(
          player.getInventory(),
          playerInventory,
          SFItemHelpers::isGoodEdibleFood);
        if (edibleSlot.isEmpty()) {
          return;
        }

        var slot = edibleSlot.getAsInt();
        if (player.hasContainerOpen()) {
          return;
        }

        if (connection.minecraft().gameMode == null || !player.inventoryMenu.getCarried().isEmpty()) {
          return;
        }
        if (connection.botControl().tryStart(new EatMealTask(connection, slot, true, ControlPriority.LOW))) {
          log.info("Auto Eat: {} is attempting to eat {} from inventory slot {}",
            connection.accountName(), playerInventory.getSlot(slot).getItem().getHoverName().getString(), slot);
        }
      },
      settingsSource.getRandom(AutoEatSettings.DELAY).asLongSupplier(),
      TimeUnit.SECONDS);
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(AutoEatSettings.class, "auto-eat", "Auto eat", this, "drumstick", AutoEatSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class AutoEatSettings implements SettingsObject {
    private static final String NAMESPACE = "auto-eat";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable auto eat")
        .description("Eat available food automatically when hungry")
        .defaultValue(false)
        .build();
    public static final MinMaxProperty<SettingsSource.Bot> DELAY =
      ImmutableMinMaxProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("delay")
        .minValue(0)
        .maxValue(Integer.MAX_VALUE)
        .minEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Min delay (seconds)")
          .description("Minimum delay between eating")
          .defaultValue(1)
          .build())
        .maxEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Max delay (seconds)")
          .description("Maximum delay between eating")
          .defaultValue(2)
          .build())
        .build();
  }
}
