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
package com.soulfiremc.server.task;

import io.grpc.Status;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/// Reconciles an output pickup after menu closure returns its cursor to inventory.
/// Keeps counts only; expected items are resolved by the current execution.
final class RecipeOutputCheckpoint {
  private final int outputCount;
  private final int inventoryBefore;

  RecipeOutputCheckpoint(int outputCount, int inventoryBefore) {
    if (outputCount <= 0 || inventoryBefore < 0) {
      throw new IllegalArgumentException("An output and nonnegative inventory count are required");
    }
    this.outputCount = outputCount;
    this.inventoryBefore = inventoryBefore;
  }

  static RecipeOutputCheckpoint capture(ItemStack output, LocalPlayer player) {
    return new RecipeOutputCheckpoint(output.getCount(), countInventory(player, output));
  }

  Location locate(LocalPlayer player, ItemStack output, ItemStack cursor, ItemStack station) {
    return locate(countInventory(player, output), output, cursor, station);
  }

  Location locate(int inventoryCount, ItemStack output, ItemStack cursor, ItemStack station) {
    var stored = (long) inventoryCount - inventoryBefore;
    if (output.getCount() != outputCount || stored < 0 || stored > outputCount
      || !cursor.isEmpty() && !ItemStack.isSameItemSameComponents(cursor, output)) {
      throw Status.ABORTED.withDescription("Recipe output changed while its menu was closed").asRuntimeException();
    }
    if (station.isEmpty()) {
      if (cursor.isEmpty() && stored == output.getCount()) {
        return Location.STORED;
      }
      if (!cursor.isEmpty() && stored + cursor.getCount() == output.getCount()) {
        return Location.CURSOR;
      }
    }
    if (stored == 0 && cursor.isEmpty() && ItemStack.matches(station, output)) {
      return Location.STATION;
    }
    return Location.PENDING;
  }

  private static int countInventory(LocalPlayer player, ItemStack output) {
    var count = 0;
    for (var stack : player.getInventory().getNonEquipmentItems()) {
      if (ItemStack.isSameItemSameComponents(stack, output)) {
        count = Math.addExact(count, stack.getCount());
      }
    }
    var offhand = player.getOffhandItem();
    if (ItemStack.isSameItemSameComponents(offhand, output)) {
      count = Math.addExact(count, offhand.getCount());
    }
    return count;
  }

  enum Location {
    STORED, CURSOR, STATION, PENDING
  }
}
