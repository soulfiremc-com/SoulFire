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

import com.soulfiremc.test.utils.TestBootstrap;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class SFInventoryHelpersTest {
  @BeforeAll
  static void setup() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void menuResyncRequiresTheStateIdProtocol() {
    assertFalse(SFInventoryHelpers.supportsMenuResync(ProtocolVersion.v1_17));
    assertTrue(SFInventoryHelpers.supportsMenuResync(ProtocolVersion.v1_17_1));
    assertTrue(SFInventoryHelpers.supportsMenuResync(ProtocolVersion.v1_21));
  }

  @Test
  void menuResyncCannotDropOrCloneTheServersCursor() {
    var menu = new AbstractContainerMenu(null, 9) {
      @Override
      public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

      @Override
      public boolean stillValid(Player player) { return true; }
    };
    var connection = mock(ClientPacketListener.class);
    SFInventoryHelpers.requestMenuContents(connection, menu);
    var captured = ArgumentCaptor.forClass(ServerboundContainerClickPacket.class);
    verify(connection).send(captured.capture());
    var request = captured.getValue();
    assertEquals(menu.containerId, request.containerId());
    assertNotEquals(menu.getStateId(), request.stateId());
    assertTrue(request.changedSlots().isEmpty());
    assertTrue(menu.isValidSlotIndex(request.slotNum()));
    assertEquals(ContainerInput.CLONE, request.containerInput());
    var player = mock(Player.class);
    for (var creative : List.of(false, true)) {
      when(player.hasInfiniteMaterials()).thenReturn(creative);
      for (var carried : List.of(ItemStack.EMPTY,
        new ItemStack(Holder.direct(Items.DIAMOND, DataComponentMap.EMPTY), 64))) {
        menu.setCarried(carried.copy());
        menu.clicked(request.slotNum(), request.buttonNum(), request.containerInput(), player);
        assertTrue(ItemStack.matches(carried, menu.getCarried()));
      }
    }
  }

  private static List<ItemStack> hotbar(int... filled) {
    var hotbar = new ArrayList<ItemStack>();
    for (var i = 0; i < 9; i++) {
      hotbar.add(ItemStack.EMPTY);
    }
    for (var i : filled) {
      hotbar.set(i, new ItemStack(Holder.direct(Items.BIRCH_LOG, DataComponentMap.EMPTY), 1));
    }
    return hotbar;
  }

  @Test
  void anItemFromTheOffHandComesInThroughAnEmptyHotbarSlot() {
    // An empty held slot takes it; else another empty one, so the held item
    // isn't swapped into the off-hand; only a full hotbar leaves no choice.
    assertEquals(2, SFInventoryHelpers.hotbarForSwapIn(hotbar(0, 1), 2));
    assertEquals(2, SFInventoryHelpers.hotbarForSwapIn(hotbar(0, 1), 0));
    assertEquals(4, SFInventoryHelpers.hotbarForSwapIn(hotbar(0, 1, 2, 3, 5, 6, 7, 8), 0));
    assertEquals(3, SFInventoryHelpers.hotbarForSwapIn(hotbar(0, 1, 2, 3, 4, 5, 6, 7, 8), 3));
  }
}
