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
package com.soulfiremc.server.util;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;

@Slf4j
public final class SFInventoryHelpers {
  private SFInventoryHelpers() {
  }

  /// Ticks to wait for a newly opened menu's contents before using it anyway.
  public static final int MENU_CONTENTS_GRACE_TICKS = 20;

  /// Whether a newly opened menu has received its contents from the server.
  ///
  /// The server opens a screen and then sends its slots in a separate packet.
  /// A client menu starts at state id 0, and the first content packet sets a
  /// state id of at least 1, so reading the menu before that sees it empty.
  public static boolean hasReceivedContents(AbstractContainerMenu menu) {
    return menu.getStateId() != 0;
  }

  public static void sendUnpredictedPickup(
    ClientPacketListener connection,
    AbstractContainerMenu menu,
    int slot
  ) {
    // Report the state we actually know, leaving the server to send the click's
    // slot and cursor changes. Local prediction cannot confirm a contested pickup.
    connection.send(new ServerboundContainerClickPacket(
      menu.containerId,
      menu.getStateId(),
      (short) slot,
      (byte) 0,
      ContainerInput.PICKUP,
      Int2ObjectMaps.emptyMap(),
      HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())
    ));
  }

  /// Requests authoritative contents after predicted clicks. Cloning outside
  /// the menu changes no items, including when the server still has a cursor
  /// stack. The deliberately stale state id makes vanilla resend the menu.
  public static void requestMenuContents(ClientPacketListener connection, AbstractContainerMenu menu) {
    connection.send(new ServerboundContainerClickPacket(
      menu.containerId,
      -1,
      (short) -999,
      (byte) 0,
      ContainerInput.CLONE,
      Int2ObjectMaps.emptyMap(),
      HashedStack.create(menu.getCarried(), connection.decoratedHashOpsGenenerator())
    ));
  }

  public static boolean supportsMenuResync(ProtocolVersion version) {
    // Earlier Java protocols discard the state id, and Bedrock uses its own
    // transaction acknowledgments. They cannot answer this resync request.
    return version.getVersionType() == VersionType.RELEASE
      && version.newerThanOrEqualTo(ProtocolVersion.v1_17_1);
  }

  public static boolean isSelectableHotbarSlot(int slot) {
    return slot >= InventoryMenu.USE_ROW_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END;
  }

  public static int toHotbarIndex(int slot) {
    return slot - InventoryMenu.USE_ROW_SLOT_START;
  }

  public static int getSelectedSlot(Inventory inventory) {
    return inventory.getSelectedSlot() + InventoryMenu.USE_ROW_SLOT_START;
  }

  /// The hotbar index (0-8) to bring an item from the off-hand into the main
  /// hand through, with a swap: the held one if it's empty, else an empty one
  /// (select it after), else the held one. A swap with a held item puts that
  /// item into the off-hand, where picked-up items of its kind then pile up
  /// and deposits don't look.
  public static int hotbarForSwapIn(Inventory inventory) {
    return hotbarForSwapIn(
      IntStream.range(0, Inventory.getSelectionSize())
        .mapToObj(inventory::getItem)
        .toList(),
      inventory.getSelectedSlot()
    );
  }

  static int hotbarForSwapIn(List<ItemStack> hotbar, int selected) {
    if (hotbar.get(selected).isEmpty()) {
      return selected;
    }
    for (var i = 0; i < hotbar.size(); i++) {
      if (hotbar.get(i).isEmpty()) {
        return i;
      }
    }
    return selected;
  }

  public static OptionalInt findMatchingSlotForAction(Inventory inventory, InventoryMenu menu, Predicate<ItemStack> predicate) {
    var intPredicate = (IntPredicate) i -> predicate.test(menu.getSlot(i).getItem());
    int selectedIndex = InventoryMenu.USE_ROW_SLOT_START + inventory.getSelectedSlot();

    // 1. Held item
    if (intPredicate.test(selectedIndex)) {
      return OptionalInt.of(selectedIndex);
    }

    // 2. Offhand
    if (intPredicate.test(45)) {
      return OptionalInt.of(45);
    }

    // 3. Remaining hotbar slots (36–44) except the selected slot
    var hotbarMatch = IntStream.range(InventoryMenu.USE_ROW_SLOT_START, InventoryMenu.USE_ROW_SLOT_END)
      .filter(i -> i != selectedIndex)
      .filter(intPredicate)
      .findFirst();
    if (hotbarMatch.isPresent()) {
      return hotbarMatch;
    }

    // 4. Main inventory slots (9–35)
    var mainMatch = IntStream.range(9, 36)
      .filter(intPredicate)
      .findFirst();
    if (mainMatch.isPresent()) {
      return mainMatch;
    }

    // 5. Armor slots (5–8)
    return IntStream.range(5, 9)
      .filter(intPredicate)
      .findFirst();
  }

  public static IntStream playerInventorySlots(AbstractContainerMenu menu) {
    var layout = menuLayout(menu);
    var main = IntStream.range(
      layout.playerInventoryStart(),
      Math.min(layout.hotbarStart(), menu.slots.size())
    );
    var hotbar = IntStream.range(
      layout.hotbarStart(),
      Math.min(layout.hotbarStart() + 9, menu.slots.size())
    );
    if (
      layout.offhandSlot() >= 0
        && layout.offhandSlot() < menu.slots.size()
    ) {
      return IntStream.concat(
        IntStream.concat(hotbar, main),
        IntStream.of(layout.offhandSlot())
      );
    }
    return IntStream.concat(hotbar, main);
  }

  private static MenuLayout menuLayout(AbstractContainerMenu menu) {
    if (menu instanceof InventoryMenu) {
      return new MenuLayout(9, 36, 45);
    }
    if (menu instanceof ChestMenu chestMenu) {
      var containerSize = chestMenu.getRowCount() * 9;
      return new MenuLayout(containerSize, containerSize + 27, -1);
    }
    if (menu instanceof AbstractFurnaceMenu) {
      return new MenuLayout(3, 30, -1);
    }
    if (menu instanceof CraftingMenu) {
      return new MenuLayout(10, 37, -1);
    }

    var containerSlots = Math.max(0, menu.slots.size() - 36);
    return new MenuLayout(
      containerSlots,
      Math.max(containerSlots, menu.slots.size() - 9),
      -1
    );
  }

  private record MenuLayout(
    int playerInventoryStart,
    int hotbarStart,
    int offhandSlot
  ) {
  }
}
