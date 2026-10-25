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

import com.soulfiremc.grpc.generated.FishTask;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FishTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stoppingWithoutControlPreservesTheCurrentOwnersLineAndSelection(boolean suspended) {
    var fixture = new Fixture();
    if (suspended) {
      fixture.execution.control().onStarted();
      fixture.execution.control().onSuspended();
    }
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.gameMode, never()).useItem(fixture.player, InteractionHand.MAIN_HAND);
    verify(fixture.inventory, never()).setSelectedSlot(anyInt());
    assertTrue(fixture.execution.result().isCancelled());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activeAndResumedControlsRetractTheLineAndRestoreSelection(boolean resumed) {
    var fixture = new Fixture();
    fixture.execution.control().onStarted();
    if (resumed) {
      fixture.execution.control().onSuspended();
      fixture.execution.control().onResumed();
    }
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.gameMode).useItem(fixture.player, InteractionHand.MAIN_HAND);
    verify(fixture.inventory).setSelectedSlot(0);
    assertTrue(fixture.execution.result().isCancelled());
  }

  @Test
  void capturesSelectionWhenTheQueuedControlActuallyStarts() {
    var fixture = new Fixture();
    when(fixture.inventory.getSelectedSlot()).thenReturn(3);
    fixture.execution.control().onStarted();
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.inventory).setSelectedSlot(3);
  }

  private static final class Fixture {
    final LocalPlayer player = mock(LocalPlayer.class);
    final Inventory inventory = mock(Inventory.class);
    final MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
    final BotTaskExecution execution;

    Fixture() {
      var minecraft = mock(Minecraft.class);
      minecraft.player = player;
      minecraft.gameMode = gameMode;
      player.fishing = mock(FishingHook.class);
      when(player.getInventory()).thenReturn(inventory);
      var rod = mock(ItemStack.class);
      when(rod.getItem()).thenReturn(Items.FISHING_ROD);
      when(player.getMainHandItem()).thenReturn(rod);
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      var context = new BotTaskContext(null, bot, null, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), _ -> {});
      execution = new FishTaskProvider().start(context,
        FishTask.newBuilder().setRestoreSelectedSlot(true).build());
    }
  }
}
