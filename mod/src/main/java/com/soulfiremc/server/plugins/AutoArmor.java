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
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.soulfiremc.server.task.TaskInventorySupport;
import com.soulfiremc.server.util.SFItemHelpers;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Slf4j
@InternalPluginClass
public final class AutoArmor extends InternalPlugin {
  private static final Map<EquipmentSlot, Integer> SLOT_VIEW_MAP = Map.of(
    EquipmentSlot.HEAD, InventoryMenu.ARMOR_SLOT_START,
    EquipmentSlot.CHEST, InventoryMenu.ARMOR_SLOT_START + 1,
    EquipmentSlot.LEGS, InventoryMenu.ARMOR_SLOT_START + 2,
    EquipmentSlot.FEET, InventoryMenu.ARMOR_SLOT_START + 3
  );

  public AutoArmor() {
    super(new PluginInfo(
      "auto-armor",
      "1.0.0",
      "Automatically puts on the best armor",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com"
    ));
  }

  private static IntStream storageSlots() {
    return IntStream.range(InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END);
  }

  private static void putOn(
    BotConnection connection,
    LocalPlayer player,
    EquipmentSlot equipmentSlotEnum) {
    var inventory = player.inventoryMenu;
    var equipmentSlotInt = SLOT_VIEW_MAP.get(equipmentSlotEnum);
    var equipmentSlot = inventory.getSlot(equipmentSlotInt);
    if (!equipmentSlot.mayPickup(player)) {
      return;
    }

    var bestItemSlotOptional =
      storageSlots()
        .mapToObj(inventory::getSlot)
        .filter(
          s -> {
            if (s.getItem().isEmpty()) {
              return false;
            }

            var equippable = s.getItem().get(DataComponents.EQUIPPABLE);
            if (equippable == null) {
              return false;
            }

            return equippable.slot() == equipmentSlotEnum;
          })
        .reduce(
          (first, second) -> {
            var firstArmor = SFItemHelpers.armorScore(first.getItem(), equipmentSlotEnum);
            var secondArmor = SFItemHelpers.armorScore(second.getItem(), equipmentSlotEnum);

            return firstArmor > secondArmor ? first : second;
          });

    if (bestItemSlotOptional.isEmpty()) {
      return;
    }

    var bestItemSlot = bestItemSlotOptional.get();
    var bestItem = bestItemSlot.getItem();
    if (bestItem.isEmpty()) {
      return;
    }

    var equipmentSlotItem = equipmentSlot.getItem();
    if (SFItemHelpers.armorScore(equipmentSlotItem, equipmentSlotEnum)
      >= SFItemHelpers.armorScore(bestItem, equipmentSlotEnum)) {
      return;
    }

    if (
      player.hasContainerOpen()
        || !player.inventoryMenu.getCarried().isEmpty()
    ) {
      return;
    }

    if (connection.botControl().tryStart(
      TaskInventorySupport.swapSlotsViaSelectedHotbar(
      connection,
      "Auto armor",
      ControlPriority.LOW,
      Set.of(ControlResource.INVENTORY, ControlResource.CONTAINER),
      bestItemSlot.index,
      equipmentSlot.index
    ))) {
      log.info("Auto Armor: {} is attempting to equip {} from inventory slot {} in {}",
        connection.accountName(), bestItem.getHoverName().getString(), bestItemSlot.index, equipmentSlotEnum.getName());
    }
  }

  @EventHandler
  public static void onJoined(BotConnectionInitEvent event) {
    var connection = event.connection();
    var settingsSource = connection.settingsSource();
    connection.scheduler().scheduleWithDynamicDelay(
      () -> {
        if (!settingsSource.get(AutoArmorSettings.ENABLED)) {
          return;
        }

        var player = connection.minecraft().player;
        if (player == null) {
          return;
        }

        for (var type : Arrays.stream(EquipmentSlot.values())
          .filter(slot -> slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR)
          .toList()) {
          putOn(connection, player, type);
        }
      },
      settingsSource.getRandom(AutoArmorSettings.DELAY).asLongSupplier(),
      TimeUnit.SECONDS);
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(AutoArmorSettings.class, "auto-armor", "Auto equip armor", this, "shield", AutoArmorSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.NONE)
  private static class AutoArmorSettings implements SettingsObject {
    private static final String NAMESPACE = "auto-armor";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable auto equip armor")
        .description("Put on best armor automatically")
        .defaultValue(false)
        .build();
    public static final MinMaxProperty<SettingsSource.Bot> DELAY =
      ImmutableMinMaxProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("delay")
        .minValue(0)
        .maxValue(Integer.MAX_VALUE)
        .minEntry(
          ImmutableMinMaxPropertyEntry.builder()
            .uiName("Min delay (seconds)")
            .description("Minimum delay between putting on armor")
            .defaultValue(1)
            .build())
        .maxEntry(
          ImmutableMinMaxPropertyEntry.builder()
            .uiName("Max delay (seconds)")
            .description("Maximum delay between putting on armor")
            .defaultValue(2)
            .build())
        .build();
  }
}
