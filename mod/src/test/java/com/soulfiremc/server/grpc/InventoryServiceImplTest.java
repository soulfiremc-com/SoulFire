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

import com.soulfiremc.grpc.generated.TransferItemsRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InventoryServiceImplTest {
  @Test
  void menuIdStaysWithItsMenu() {
    var menu = menu(1);
    assertEquals(InventoryServiceImpl.menuId(menu), InventoryServiceImpl.menuId(menu));
  }

  @Test
  void menusWithTheSameContainerIdGetDifferentMenuIds() {
    assertNotEquals(InventoryServiceImpl.menuId(menu(1)), InventoryServiceImpl.menuId(menu(1)));
  }

  @Test
  void transferRequiresTheMenuItNames() {
    var opened = menu(1);
    var request = TransferItemsRequest.newBuilder()
      .setMenuId(InventoryServiceImpl.menuId(opened))
      .build();
    assertDoesNotThrow(() -> InventoryServiceImpl.requireMenu(opened, request));
    var error = assertThrows(
      StatusRuntimeException.class,
      () -> InventoryServiceImpl.requireMenu(menu(1), request));
    assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
  }

  @Test
  void transferWithoutMenuIdRunsInAnyMenu() {
    assertDoesNotThrow(() -> InventoryServiceImpl.requireMenu(
      menu(1),
      TransferItemsRequest.getDefaultInstance()));
  }

  private static AbstractContainerMenu menu(int containerId) {
    return new AbstractContainerMenu((MenuType<?>) null, containerId) {
      @Override
      public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
      }

      @Override
      public boolean stillValid(Player player) {
        return true;
      }
    };
  }
}
