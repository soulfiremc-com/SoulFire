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
import com.soulfiremc.server.bot.ControllingTask;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.soulfiremc.server.util.SFInventoryHelpers;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.TimeUnit;

@InternalPluginClass
public final class AutoTotem extends InternalPlugin {
  public AutoTotem() {
    super(new PluginInfo(
      "auto-totem",
      "1.0.0",
      "Automatically puts totems in the offhand slot",
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
        if (!settingsSource.get(AutoTotemSettings.ENABLED)) {
          return;
        }

        var player = connection.minecraft().player;
        if (player == null) {
          return;
        }

        var playerInventory = player.inventoryMenu;
        var offhandItem = playerInventory.getSlot(InventoryMenu.SHIELD_SLOT);

        // We only want to use totems if there are no items in the offhand
        if (!offhandItem.getItem().isEmpty()) {
          return;
        }

        var totemSlot = SFInventoryHelpers.findMatchingSlotForAction(player.getInventory(), playerInventory,
          slot -> slot.getItem() == Items.TOTEM_OF_UNDYING);
        if (totemSlot.isEmpty()) {
          return;
        }

        if (player.hasContainerOpen()) {
          return;
        }

        var gameMode = connection.minecraft().gameMode;
        connection.botControl().maybeRegister(ControllingTask.staged(List.of(
          new ControllingTask.RunnableStage(player::sendOpenInventory),
          new ControllingTask.RunnableStage(() -> gameMode.handleContainerInput(player.inventoryMenu.containerId, totemSlot.getAsInt(), 0, ContainerInput.PICKUP, player)),
          new ControllingTask.WaitDelayStage(() -> 50L),
          new ControllingTask.RunnableStage(() -> gameMode.handleContainerInput(player.inventoryMenu.containerId, InventoryMenu.SHIELD_SLOT, 0, ContainerInput.PICKUP, player)),
          new ControllingTask.WaitDelayStage(() -> 50L),
          new ControllingTask.RunnableStage(() -> {
            if (!playerInventory.getCarried().isEmpty()) {
              gameMode.handleContainerInput(player.inventoryMenu.containerId, totemSlot.getAsInt(), 0, ContainerInput.PICKUP, player);
            }
          }),
          new ControllingTask.WaitDelayStage(() -> 50L),
          new ControllingTask.RunnableStage(player::closeContainer)
        )));
      },
      settingsSource.getRandom(AutoTotemSettings.DELAY).asLongSupplier(),
      TimeUnit.SECONDS);
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(AutoTotemSettings.class, "auto-totem", "Auto Totem", this, "cross", AutoTotemSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class AutoTotemSettings implements SettingsObject {
    private static final String NAMESPACE = "auto-totem";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable Auto Totem")
        .description("Always put available totems in the offhand slot")
        .defaultValue(true)
        .build();
    public static final MinMaxProperty<SettingsSource.Bot> DELAY = ImmutableMinMaxProperty.<SettingsSource.Bot>builder()
      .sourceType(SettingsSource.Bot.INSTANCE)
      .namespace(NAMESPACE)
      .key("delay")
      .minValue(0)
      .maxValue(Integer.MAX_VALUE)
      .minEntry(ImmutableMinMaxPropertyEntry.builder()
        .uiName("Min delay (seconds)")
        .description("Minimum delay between using totems")
        .defaultValue(1)
        .build())
      .maxEntry(ImmutableMinMaxPropertyEntry.builder()
        .uiName("Max delay (seconds)")
        .description("Maximum delay between using totems")
        .defaultValue(2)
        .build())
      .build();
  }
}
