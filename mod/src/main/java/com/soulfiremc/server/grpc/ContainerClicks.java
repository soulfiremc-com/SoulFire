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
package com.soulfiremc.server.grpc;

import com.soulfiremc.mod.mixin.soulfire.CrafterScreenAccessor;
import com.soulfiremc.mod.mixin.soulfire.CreativeModeInventoryScreenAccessor;
import io.grpc.Status;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.util.Prediction;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CrafterMenu;

/// Client-owned menus must apply their native screen handlers and packet semantics.
final class ContainerClicks {
  private ContainerClicks() {}

  static void click(Minecraft minecraft, int slotId, int button, ContainerInput input) {
    var player = minecraft.player;
    var gameMode = minecraft.gameMode;
    if (player == null || gameMode == null) {
      throw Status.FAILED_PRECONDITION.withDescription("Bot player or game mode is not available").asRuntimeException();
    }
    var menu = player.containerMenu;
    if (slotId != -999 && (slotId < 0 || slotId >= menu.slots.size())) {
      throw Status.INVALID_ARGUMENT.withDescription("Invalid container slot index").asRuntimeException();
    }
    if (input == ContainerInput.SWAP && (button < 0 || button > 8) && button != 40) {
      throw Status.INVALID_ARGUMENT.withDescription("Invalid inventory swap destination").asRuntimeException();
    }
    var slot = slotId == -999 ? null : menu.getSlot(slotId);
    if (slot != null && !slot.isActive()) {
      throw Status.INVALID_ARGUMENT.withDescription("Container slot is inactive").asRuntimeException();
    }
    if (menu instanceof CreativeModeInventoryScreen.ItemPickerMenu) {
      if (!(minecraft.gui.screen() instanceof CreativeModeInventoryScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot creative inventory screen is not available").asRuntimeException();
      }
      if (slot != null && (slot.x < 0 || slot.y < 0)) {
        throw Status.INVALID_ARGUMENT.withDescription("Creative inventory slot is hidden").asRuntimeException();
      }
      // The native inventory tab treats either outside pickup button as a full-stack throw.
      if (slotId == -999 && input == ContainerInput.PICKUP && button == 1 && screen.isInventoryOpen()) {
        var carried = menu.getCarried();
        if (!carried.isEmpty() && player.canDropItems()) {
          var dropped = carried.split(1);
          player.drop(dropped, true, Prediction.PREDICTED);
          gameMode.handleCreativeModeItemDrop(dropped);
        }
        return;
      }
      var access = (CreativeModeInventoryScreenAccessor) screen;
      var outside = access.soulfire$hasClickedOutside();
      try {
        access.soulfire$hasClickedOutside(slotId == -999);
        access.soulfire$clickSlot(slot, slotId, button, input);
      } finally {
        access.soulfire$hasClickedOutside(outside);
      }
    } else if (menu instanceof CrafterMenu) {
      if (!(minecraft.gui.screen() instanceof CrafterScreen screen) || screen.getMenu() != menu) {
        throw Status.FAILED_PRECONDITION.withDescription("Bot crafter screen is not available").asRuntimeException();
      }
      ((CrafterScreenAccessor) screen).soulfire$clickSlot(slot, slotId, button, input);
    } else {
      gameMode.handleContainerInput(menu.containerId, slotId, button, input, player);
    }
  }
}
