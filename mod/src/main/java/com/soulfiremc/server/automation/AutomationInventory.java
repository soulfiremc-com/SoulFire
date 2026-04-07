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
package com.soulfiremc.server.automation;

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.util.SFInventoryHelpers;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.OptionalInt;
import java.util.stream.IntStream;

public final class AutomationInventory {
  private AutomationInventory() {
  }

  public static int countInventory(BotConnection bot, String requirementKey) {
    var player = bot.minecraft().player;
    if (player == null) {
      return 0;
    }

    return AutomationRequirements.countMatching(player.inventoryMenu, requirementKey);
  }

  public static OptionalInt findInventorySlot(BotConnection bot, String requirementKey) {
    var player = bot.minecraft().player;
    if (player == null) {
      return OptionalInt.empty();
    }

    return SFInventoryHelpers.findMatchingSlotForAction(
      player.getInventory(),
      player.inventoryMenu,
      stack -> AutomationRequirements.matches(requirementKey, stack)
    );
  }

  public static boolean ensureHolding(BotConnection bot, String requirementKey) {
    var player = bot.minecraft().player;
    var gameMode = bot.minecraft().gameMode;
    if (player == null || gameMode == null) {
      return false;
    }

    if (AutomationRequirements.matches(requirementKey, player.getMainHandItem())) {
      return true;
    }

    var slot = findInventorySlot(bot, requirementKey);
    if (slot.isEmpty()) {
      return false;
    }

    var inventorySlot = slot.getAsInt();
    if (SFInventoryHelpers.isSelectableHotbarSlot(inventorySlot)) {
      player.getInventory().setSelectedSlot(SFInventoryHelpers.toHotbarIndex(inventorySlot));
      return true;
    }

    if (player.hasContainerOpen()) {
      return false;
    }

    player.sendOpenInventory();
    click(player.inventoryMenu, inventorySlot, 0, ContainerInput.PICKUP, player, gameMode);
    click(player.inventoryMenu, SFInventoryHelpers.getSelectedSlot(player.getInventory()), 0, ContainerInput.PICKUP, player, gameMode);
    if (!player.inventoryMenu.getCarried().isEmpty()) {
      click(player.inventoryMenu, inventorySlot, 0, ContainerInput.PICKUP, player, gameMode);
    }
    player.closeContainer();
    return AutomationRequirements.matches(requirementKey, player.getMainHandItem());
  }

  public static OptionalInt findPlayerInventorySlot(AbstractContainerMenu menu, String requirementKey) {
    return playerInventorySlots(menu)
      .filter(slot -> AutomationRequirements.matches(requirementKey, menu.getSlot(slot).getItem()))
      .findFirst();
  }

  public static void quickMove(AbstractContainerMenu menu, int slot, LocalPlayer player) {
    click(menu, slot, 0, ContainerInput.QUICK_MOVE, player, BotConnection.current().minecraft().gameMode);
  }

  public static void clearCraftingSlots(AbstractContainerMenu menu, IntStream slots, LocalPlayer player) {
    slots
      .filter(slot -> !menu.getSlot(slot).getItem().isEmpty())
      .forEach(slot -> click(menu, slot, 0, ContainerInput.QUICK_MOVE, player, BotConnection.current().minecraft().gameMode));
  }

  public static boolean moveOneIngredient(AbstractContainerMenu menu, int targetSlot, String requirementKey, LocalPlayer player) {
    var gameMode = BotConnection.current().minecraft().gameMode;
    if (gameMode == null || targetSlot >= menu.slots.size()) {
      return false;
    }

    if (!menu.getSlot(targetSlot).getItem().isEmpty()) {
      return AutomationRequirements.matches(requirementKey, menu.getSlot(targetSlot).getItem());
    }

    var sourceSlot = findPlayerInventorySlot(menu, requirementKey);
    if (sourceSlot.isEmpty()) {
      return false;
    }

    // Pick up the stack, place one item into the recipe slot, then put the remainder back.
    click(menu, sourceSlot.getAsInt(), 0, ContainerInput.PICKUP, player, gameMode);
    click(menu, targetSlot, 1, ContainerInput.PICKUP, player, gameMode);
    if (!menu.getCarried().isEmpty()) {
      click(menu, sourceSlot.getAsInt(), 0, ContainerInput.PICKUP, player, gameMode);
    }
    return true;
  }

  public static int playerInventoryCount(AbstractContainerMenu menu, String requirementKey) {
    return playerInventorySlots(menu)
      .map(slot -> {
        var stack = menu.getSlot(slot).getItem();
        return AutomationRequirements.matches(requirementKey, stack) ? stack.getCount() : 0;
      })
      .sum();
  }

  public static MenuLayout menuLayout(AbstractContainerMenu menu) {
    if (menu instanceof InventoryMenu) {
      return new MenuLayout(9, 36, 45, 4);
    }

    if (menu instanceof ChestMenu chestMenu) {
      var containerSize = chestMenu.getRowCount() * 9;
      return new MenuLayout(containerSize, containerSize + 27, -1, containerSize);
    }

    if (menu instanceof AbstractFurnaceMenu) {
      return new MenuLayout(3, 30, -1, 3);
    }

    if (menu instanceof CraftingMenu) {
      return new MenuLayout(10, 37, -1, 10);
    }

    var containerSlots = AutomationWorldMemory.containerSlotCount(menu);
    return new MenuLayout(containerSlots, Math.max(containerSlots, menu.slots.size() - 9), -1, containerSlots);
  }

  public static IntStream playerInventorySlots(AbstractContainerMenu menu) {
    var layout = menuLayout(menu);
    var main = IntStream.range(layout.playerInventoryStart(), Math.min(layout.hotbarStart(), menu.slots.size()));
    var hotbar = IntStream.range(layout.hotbarStart(), Math.min(layout.hotbarStart() + 9, menu.slots.size()));
    if (layout.offhandSlot() >= 0 && layout.offhandSlot() < menu.slots.size()) {
      return IntStream.concat(IntStream.concat(hotbar, main), IntStream.of(layout.offhandSlot()));
    }

    return IntStream.concat(hotbar, main);
  }

  public static void click(AbstractContainerMenu menu,
                           int slot,
                           int mouseButton,
                           ContainerInput input,
                           LocalPlayer player,
                           MultiPlayerGameMode gameMode) {
    if (gameMode == null) {
      return;
    }

    gameMode.handleContainerInput(menu.containerId, slot, mouseButton, input, player);
  }

  public static boolean isFoodInInventory(BotConnection bot) {
    return findInventorySlot(bot, AutomationRequirements.FOOD).isPresent();
  }

  public static boolean hasRangedWeapon(BotConnection bot) {
    return countInventory(bot, AutomationRequirements.BOW) > 0
      && countInventory(bot, AutomationRequirements.ARROW) > 0;
  }

  public static int bestPickaxeTier(BotConnection bot) {
    if (countInventory(bot, AutomationRequirements.DIAMOND_PICKAXE) > 0) {
      return 4;
    }

    if (countInventory(bot, AutomationRequirements.IRON_PICKAXE) > 0) {
      return 3;
    }

    if (countInventory(bot, AutomationRequirements.STONE_PICKAXE) > 0) {
      return 2;
    }

    if (countInventory(bot, AutomationRequirements.WOODEN_PICKAXE) > 0) {
      return 1;
    }

    return 0;
  }

  public static boolean satisfiesToolRequirement(BotConnection bot, AutomationRecipes.ToolRequirement requirement) {
    return switch (requirement) {
      case NONE -> true;
      case WOOD_PICKAXE -> bestPickaxeTier(bot) >= 1;
      case STONE_PICKAXE -> bestPickaxeTier(bot) >= 2;
      case IRON_PICKAXE -> bestPickaxeTier(bot) >= 3;
    };
  }

  public record MenuLayout(
    int playerInventoryStart,
    int hotbarStart,
    int offhandSlot,
    int containerSlots
  ) {
  }
}
