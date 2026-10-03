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

import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class CraftTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void acceptsAnIngredientWithPlayerSpecificComponents() {
    var accepted = itemStack(Items.COBBLESTONE);
    var renamed = itemStack(Items.COBBLESTONE);
    renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Building stone"));

    assertTrue(CraftTaskProvider.ingredientMatches(
      List.of(accepted),
      renamed
    ));
  }

  @Test
  void rejectsAStackOfAnotherItem() {
    assertFalse(CraftTaskProvider.ingredientMatches(
      List.of(itemStack(Items.COBBLESTONE)),
      itemStack(Items.DIRT)
    ));
  }

  @Test
  void settlesOnceTheStateIdStaysTheSame() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.restart(menu);

    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  @Test
  void waitsForTheServersAnswerToGridClicks() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.awaitAnswer(menu);

    for (var tick = 0; tick < 10; tick++) {
      assertFalse(sync.settled(menu));
    }
    when(menu.getStateId()).thenReturn(6);
    assertFalse(sync.settled(menu));
    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  @Test
  void waitsForTheStateIdToSettleAgainAfterARestart() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.awaitAnswer(menu);
    when(menu.getStateId()).thenReturn(6);
    for (var tick = 0; tick < 3; tick++) {
      sync.settled(menu);
    }
    assertTrue(sync.settled(menu));

    sync.restart(menu);
    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  private static ItemStack itemStack(Item item) {
    return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), 1);
  }
}
