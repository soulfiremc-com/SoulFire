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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RecipeOutputCheckpointTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @CsvSource({
    "7, 0, 0, STORED",
    "3, 4, 0, CURSOR",
    "5, 2, 0, CURSOR",
    "3, 0, 4, STATION",
    "3, 0, 0, PENDING",
    "5, 0, 0, PENDING",
    "3, 4, 4, PENDING",
    "7, 0, 4, PENDING"
  })
  void reconcilesOnlyConservedOutput(int inventory, int cursor, int station,
                                   RecipeOutputCheckpoint.Location expected) {
    var checkpoint = new RecipeOutputCheckpoint(4, 3);
    assertEquals(expected, checkpoint.locate(inventory, stack(4), stack(cursor), stack(station)));
  }

  @ParameterizedTest
  @CsvSource({"2", "8"})
  void rejectsInventoryChangesBeyondThePickup(int inventory) {
    var checkpoint = new RecipeOutputCheckpoint(4, 3);
    var error = assertThrows(StatusRuntimeException.class,
      () -> checkpoint.locate(inventory, stack(4), ItemStack.EMPTY, ItemStack.EMPTY));
    assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
  }

  @Test
  void doesNotTreatChangedComponentsAsThePendingOutput() {
    var checkpoint = new RecipeOutputCheckpoint(4, 3);
    var renamed = stack(4);
    renamed.set(DataComponents.CUSTOM_NAME, Component.literal(getClass().getSimpleName()));
    var error = assertThrows(StatusRuntimeException.class,
      () -> checkpoint.locate(3, stack(4), renamed, ItemStack.EMPTY));
    assertEquals(Status.Code.ABORTED, error.getStatus().getCode());
    assertEquals(RecipeOutputCheckpoint.Location.PENDING,
      checkpoint.locate(3, stack(4), ItemStack.EMPTY, renamed));
  }

  @Test
  void keepsADetachedSnapshotOfTheOutputAmount() {
    var output = stack(4);
    var checkpoint = new RecipeOutputCheckpoint(output.getCount(), 3);
    output.setCount(1);
    assertEquals(RecipeOutputCheckpoint.Location.STORED,
      checkpoint.locate(7, stack(4), ItemStack.EMPTY, ItemStack.EMPTY));
  }

  @Test
  void includesOffhandAndIgnoresOtherItemsWhenCapturingInventory() {
    var player = mock(LocalPlayer.class);
    var inventory = mock(Inventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getNonEquipmentItems()).thenReturn(NonNullList.of(ItemStack.EMPTY, stack(3), new ItemStack(Holder.direct(Items.GOLD_INGOT, DataComponentMap.EMPTY))));
    when(player.getOffhandItem()).thenReturn(stack(2));
    var checkpoint = RecipeOutputCheckpoint.capture(stack(4), player);
    when(inventory.getNonEquipmentItems()).thenReturn(NonNullList.of(ItemStack.EMPTY, stack(7), new ItemStack(Holder.direct(Items.GOLD_INGOT, DataComponentMap.EMPTY))));
    assertEquals(RecipeOutputCheckpoint.Location.STORED,
      checkpoint.locate(player, stack(4), ItemStack.EMPTY, ItemStack.EMPTY));
  }

  private static ItemStack stack(int count) {
    return count == 0 ? ItemStack.EMPTY : new ItemStack(Holder.direct(Items.IRON_INGOT, DataComponentMap.EMPTY), count);
  }
}
