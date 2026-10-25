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

import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class MountInventoryLayoutTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @CsvSource({"horse,0", "donkey,5", "llama,1", "llama,2", "llama,3", "llama,4", "llama,5"})
  void regionsMatchActiveNativeSlotsAndCargoRows(String kind, int columns) {
    var menu = menu(kind, columns, mock(LocalPlayer.class));
    var layout = BotServiceImpl.buildContainerLayout(menu, "", mock(Minecraft.class));
    var visible = layout.getRegionsList().stream()
      .flatMapToInt(region -> IntStream.range(region.getStartIndex(), region.getStartIndex() + region.getSlotCount()))
      .boxed().toList();
    assertEquals(menu.slots.stream().filter(slot -> slot.isActive()).map(slot -> slot.index).toList(), visible);
    assertEquals(menu.slots.size(), layout.getTotalSlots());
    assertEquals("mount", layout.getContainerType());
    var storage = layout.getRegionsList().stream().filter(region -> region.getId().equals("container")).findFirst();
    assertEquals(columns > 0, storage.isPresent());
    storage.ifPresent(region -> {
      assertEquals(2, region.getStartIndex());
      assertEquals(columns, region.getColumns());
      assertEquals(3 * columns, region.getSlotCount());
    });
  }

  @Test
  void inactiveMountSlotsRejectClicksBeforePredictionOrPackets() {
    var player = mock(LocalPlayer.class);
    player.containerMenu = menu("llama", 2, player);
    var minecraft = mock(Minecraft.class);
    minecraft.player = player;
    minecraft.gameMode = mock(MultiPlayerGameMode.class);
    var failure = assertThrows(StatusRuntimeException.class, () -> ContainerClicks.click(minecraft, 0, 0, ContainerInput.PICKUP));
    assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    verifyNoInteractions(minecraft.gameMode);
  }

  private static HorseInventoryMenu menu(String kind, int columns, LocalPlayer player) {
    var horse = kind.equals("llama") ? mock(Llama.class) : mock(AbstractHorse.class);
    when(horse.canUseSlot(EquipmentSlot.SADDLE)).thenReturn(true);
    when(horse.canUseSlot(EquipmentSlot.BODY)).thenReturn(true);
    when(horse.is(EntityTypeTags.CAN_EQUIP_SADDLE)).thenReturn(!kind.equals("llama"));
    when(horse.is(EntityTypeTags.CAN_WEAR_HORSE_ARMOR)).thenReturn(kind.equals("horse"));
    when(horse.createEquipmentSlotContainer(any(EquipmentSlot.class))).thenAnswer(_ -> new SimpleContainer(1));
    return new HorseInventoryMenu(1, new Inventory(player, new EntityEquipment()), new SimpleContainer(3 * columns), horse, columns);
  }
}
